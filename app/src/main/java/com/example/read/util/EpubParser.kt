package com.example.read.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 快速提取的元数据结果，仅包含书架展示所需的最小信息。
 * 用于导入时立即在书架上显示书籍，无需等待完整解包。
 *
 * @param title 书籍标题
 * @param author 作者姓名
 * @param coverBytes 封面图片字节，null 表示无封面（需单独提取）
 * @param totalChapters 章节总数（spine 条目数）
 * @param coverId OPF 中声明的封面 manifest ID，用于后续提取封面
 */
data class QuickMetadata(
    val title: String,
    val author: String,
    val coverBytes: ByteArray?,
    val totalChapters: Int,
    val coverId: String? = null,
)

/**
 * EPUB 文件解析器，使用 Jsoup XML Parser 进行结构化解析。
 *
 * 解析流程：
 * 1. 解压 ZIP 到目标目录
 * 2. 解析 META-INF/container.xml 获取 OPF 文件路径
 * 3. 解析 OPF 文件：提取 metadata、manifest、spine、封面信息
 * 4. 生成 metadata.json 缓存 spine 阅读顺序
 *
 * XML 解析使用 Jsoup Parser.xmlParser()，通过 DOM 遍历提取数据，
 * 不依赖正则表达式，避免转义和边界匹配问题。
 */
class EpubParser {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    /** 单个 ZIP 条目的最大允许大小（100MB），防止 ZIP 炸弹耗尽存储空间 */
    private companion object {
        const val MAX_ZIP_ENTRY_SIZE = 100L * 1024 * 1024
    }

    /** 旧版解析结果（已废弃） */
    @Deprecated("Use unpack() instead", replaceWith = ReplaceWith("unpack(inputStream, targetDir)"))
    data class ParseResult(
        val title: String,
        val author: String,
        val coverBytes: ByteArray?,
        val chapters: List<ChapterData>,
    )

    /** 旧版章节数据（已废弃） */
    @Deprecated("Use SpineItem instead")
    data class ChapterData(
        val title: String,
        val htmlContent: String,
    )

    // ==================== 公开 API ====================

    /**
     * 将 EPUB 文件解包到指定目录，并生成 metadata.json。
     *
     * @param inputStream EPUB 文件的输入流
     * @param targetDir 解包目标目录
     * @return 解包结果，包含元数据和封面图片字节
     * @throws IllegalArgumentException 如果 EPUB 结构无效
     */
    fun unpack(inputStream: InputStream, targetDir: File): EpubUnpackResult {
        // 第一步：解压 ZIP 到目标目录
        targetDir.mkdirs()
        extractZip(inputStream, targetDir)

        // 第二步：解析 container.xml 获取 OPF 路径
        val containerFile = File(targetDir, "META-INF/container.xml")
        require(containerFile.exists()) { "Invalid EPUB: missing META-INF/container.xml" }
        val opfPath = parseContainerXml(containerFile.readText(Charsets.UTF_8))

        // 第三步：解析 OPF 文件
        val opfFile = File(targetDir, opfPath)
        require(opfFile.exists()) { "Invalid EPUB: OPF file not found at $opfPath" }
        val opfDir = opfPath.substringBeforeLast("/", "")
        val opfDoc = Jsoup.parse(opfFile.readText(Charsets.UTF_8), "", Parser.xmlParser())
        val opfResult = parseOpf(opfDoc, opfDir)

        // 第四步：提取封面图片
        val coverBytes = findCoverImage(targetDir, opfResult.manifest, opfDir, opfResult.coverId)

        // 第五步：解析 NCX 文件获取目录结构
        val ncxResult = parseNcx(targetDir, opfResult.manifest, opfDir)

        // 第六步：构建 spine 列表，优先使用 NCX 标题
        val spineItems = buildSpineItems(targetDir, opfResult, opfDir, ncxResult)

        // 第七步：修正 tocItems 的 chapterIndex，通过 href 匹配 spine 索引
        // 构建 href -> spine 索引的映射（spineItems 中的 href 就是相对于 opfDir 的路径）
        val hrefToSpineIndex = spineItems.mapIndexed { index, spineItem -> spineItem.href to index }.toMap()
        val fixedTocItems = ncxResult.tocItems.mapIndexed { i, tocItem ->
            val href = ncxResult.tocHrefs.getOrElse(i) { "" }
            val spineIdx = hrefToSpineIndex[href] ?: -1
            tocItem.copy(chapterIndex = spineIdx)
        }.filter { it.chapterIndex >= 0 } // 过滤掉无法匹配的条目

        // 第八步：生成 metadata.json
        val bookMetadata = BookMetadata(opfResult.title, opfResult.author, opfDir, spineItems, fixedTocItems)
        File(targetDir, "metadata.json").writeText(json.encodeToString(bookMetadata), Charsets.UTF_8)

        return EpubUnpackResult(targetDir, opfResult.title, opfResult.author, coverBytes, bookMetadata)
    }

    /**
     * 从 EPUB 流中快速提取元数据（标题、作者、章节数），不解包全部文件。
     *
     * 仅提取 container.xml 和 OPF 文件（体积小），解析完成后清理临时目录。
     * 封面需要单独调用 extractCoverFromZip() 提取（需要先知道 OPF 中的 coverId）。
     *
     * @param inputStream EPUB 文件的输入流
     * @return 快速元数据（不含封面），解析失败时返回 null
     */
    fun quickExtractMetadata(inputStream: InputStream): QuickMetadata? {
        val tempDir = Files.createTempDirectory(null).toFile()
        try {
            // 仅提取 container.xml 和 OPF 文件（体积小，速度快）
            extractZip(inputStream, tempDir) { entry ->
                val name = entry.name.lowercase()
                name == "meta-inf/container.xml" || name.endsWith(".opf")
            }

            val containerFile = File(tempDir, "META-INF/container.xml")
            if (!containerFile.exists()) return null
            val opfPath = parseContainerXml(containerFile.readText(Charsets.UTF_8))

            val opfFile = File(tempDir, opfPath)
            if (!opfFile.exists()) return null
            val opfDir = opfPath.substringBeforeLast("/", "")
            val opfDoc = Jsoup.parse(opfFile.readText(Charsets.UTF_8), "", Parser.xmlParser())
            val opfResult = parseOpf(opfDoc, opfDir)

            return QuickMetadata(
                title = opfResult.title,
                author = opfResult.author,
                coverBytes = null,
                totalChapters = opfResult.spine.size,
                coverId = opfResult.coverId,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // P1-v6-3：本类虽然不是 suspend 但常被 IO 协程调用，CancellationException
            // 可能通过协程取消机制传到这里。必须重抛让协程框架正确终结，不能与"解析失败"
            // 混为一谈（否则上层永远收不到 cancel 信号，导入流程无法被中止）。
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 从 EPUB 流中仅提取封面图片。
     * 需要知道 coverId（来自 OPF metadata），仅解压图片类型的 ZIP 条目。
     *
     * @param inputStream EPUB 文件的输入流（需重新打开）
     * @param coverId OPF 中声明的封面 manifest ID，null 时尝试启发式查找
     * @return 封面图片字节，未找到时返回 null
     */
    fun extractCoverFromZip(inputStream: InputStream, coverId: String?): ByteArray? {
        val tempDir = Files.createTempDirectory(null).toFile()
        try {
            // 提取 container.xml、OPF 和图片文件（用于封面查找）
            extractZip(inputStream, tempDir) { entry ->
                val name = entry.name.lowercase()
                name == "meta-inf/container.xml" || name.endsWith(".opf") || name.endsWith(".jpg")
                    || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
            }

            val containerFile = File(tempDir, "META-INF/container.xml")
            if (!containerFile.exists()) return null
            val opfPath = parseContainerXml(containerFile.readText(Charsets.UTF_8))
            val opfFile = File(tempDir, opfPath)
            if (!opfFile.exists()) return null
            val opfDir = opfPath.substringBeforeLast("/", "")
            val opfDoc = Jsoup.parse(opfFile.readText(Charsets.UTF_8), "", Parser.xmlParser())
            val manifest = parseManifest(opfDoc)

            return findCoverImage(tempDir, manifest, opfDir, coverId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // P1-v6-3：同上，本方法在 IO 协程中调用，取消信号必须重抛
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== ZIP 解压 ====================

    /**
     * 将 ZIP 输入流中的条目解压到目标目录。
     * 保持原始目录结构，自动创建父目录。
     *
     * @param inputStream EPUB 的 ZIP 输入流
     * @param targetDir 解压目标目录
     * @param filter 可选过滤器，仅解压匹配的条目；null 表示解压全部
     *
     * 安全措施：
     * 1. ZIP Slip 防护：校验每个条目的解压路径是否在目标目录内，防止路径穿越攻击
     * 2. 条目大小限制：单个条目最大 100MB，防止 ZIP 炸弹耗尽存储空间
     */
    private fun extractZip(inputStream: InputStream, targetDir: File, filter: ((ZipEntry) -> Boolean)? = null) {
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (filter == null || filter(entry))) {
                    val outFile = File(targetDir, entry.name)

                    // ZIP Slip 防护：通过 canonical path 校验解压目标是否在目标目录内
                    // 恶意 EPUB 可能包含 "../../etc/passwd" 等路径穿越的条目名
                    val canonicalOutPath = outFile.canonicalPath
                    val canonicalTargetPath = targetDir.canonicalPath + File.separator
                    if (!canonicalOutPath.startsWith(canonicalTargetPath)) {
                        throw SecurityException("ZIP 条目路径越界，疑似 ZIP Slip 攻击: ${entry.name}")
                    }

                    // 防止单个条目过大导致存储空间耗尽（ZIP 炸弹防护）
                    // 先检查 ZIP 头部声明的大小（可能被伪造），再在解压过程中实时监控实际字节数
                    val entrySize = entry.size
                    if (entrySize > MAX_ZIP_ENTRY_SIZE) {
                        throw IllegalArgumentException(
                            "ZIP 条目过大，可能为恶意文件: ${entry.name} (${entrySize / 1024 / 1024}MB)"
                        )
                    }

                    outFile.parentFile?.mkdirs()
                    // 使用手动缓冲读写替代 copyTo，实时追踪已解压字节数，
                    // 防止 entry.size=-1（大小未知）时 ZIP 炸弹绕过声明大小检查
                    outFile.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var totalBytes = 0L
                        var bytesRead: Int
                        while (zip.read(buffer).also { bytesRead = it } != -1) {
                            totalBytes += bytesRead
                            if (totalBytes > MAX_ZIP_ENTRY_SIZE) {
                                // 解压过程中发现实际大小超过限制，立即中止
                                throw IllegalArgumentException(
                                    "ZIP 条目解压后过大，可能为恶意文件: ${entry.name} (${totalBytes / 1024 / 1024}MB)"
                                )
                            }
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    // ==================== container.xml 解析 ====================

    /**
     * 从 container.xml 中解析 OPF 文件的路径。
     * 使用 tagName 遍历处理带命名空间前缀的标签。
     */
    private fun parseContainerXml(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootfile = doc.select("*").firstOrNull {
            val tn = it.tagName()
            tn == "rootfile" || tn.endsWith(":rootfile")
        } ?: throw IllegalArgumentException("Invalid container.xml: missing <rootfile> element")
        return rootfile.attr("full-path").also {
            require(it.isNotBlank()) { "Invalid container.xml: <rootfile> missing full-path attribute" }
        }
    }

    // ==================== OPF 解析 ====================

    /** OPF 解析内部结果 */
    private data class OpfResult(
        val title: String,
        val author: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val coverId: String?,
    )

    /** manifest 中的单个资源条目 */
    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String?,
    )

    /**
     * 解析 OPF 文件，提取 metadata、manifest、spine 和封面 ID。
     *
     * OPF 结构：
     * <package>
     *   <metadata>
     *     <dc:title>...</dc:title>
     *     <dc:creator>...</dc:creator>
     *     <meta name="cover" content="coverId"/>  (EPUB 2)
     *   </metadata>
     *   <manifest>
     *     <item id="..." href="..." media-type="..." properties="..."/>
     *   </manifest>
     *   <spine>
     *     <itemref idref="..."/>
     *   </spine>
     * </package>
     */
    private fun parseOpf(doc: org.jsoup.nodes.Document, opfDir: String): OpfResult {
        val title = extractMetadataText(doc, "dc:title") ?: "Unknown"
        val author = extractMetadataText(doc, "dc:creator") ?: "Unknown"
        val coverId = extractCoverMetaId(doc)
        val manifest = parseManifest(doc)
        val spine = parseSpine(doc)
        return OpfResult(title, author, manifest, spine, coverId)
    }

    /**
     * 从 metadata 区域提取指定标签的文本内容。
     * 支持带命名空间前缀的标签（如 dc:title）和不带前缀的标签。
     *
     * Jsoup XML parser 将命名空间前缀作为标签名的一部分，
     * 如 dc:title 的 tagName 是 "dc:title"。CSS 选择器中的冒号
     * 会触发伪类解析导致 SelectorParseException，因此使用 tagName 遍历。
     */
    private fun extractMetadataText(doc: org.jsoup.nodes.Document, tagName: String): String? {
        val localName = tagName.substringAfter(":")
        return doc.select("metadata > *").firstOrNull {
            val tn = it.tagName()
            tn == tagName || tn == localName || tn.endsWith(":$localName")
        }?.text()?.takeIf { it.isNotBlank() }
    }

    /**
     * 提取 EPUB 2 的封面声明：<meta name="cover" content="manifestItemId"/>。
     * 使用 tagName 遍历处理带命名空间前缀的标签。
     */
    private fun extractCoverMetaId(doc: org.jsoup.nodes.Document): String? {
        val metadataEl = doc.select("*").firstOrNull {
            val tn = it.tagName()
            tn == "metadata" || tn.endsWith(":metadata")
        } ?: return null
        for (meta in metadataEl.children()) {
            val tn = meta.tagName()
            if (tn != "meta" && !tn.endsWith(":meta")) continue
            val name = meta.attr("name")
            val content = meta.attr("content")
            if (name == "cover" && content.isNotBlank()) {
                return content
            }
        }
        return null
    }

    /**
     * 解析 manifest 区域，构建 id -> ManifestItem 映射。
     *
     * 使用 tagName 遍历而非 CSS 选择器，因为 Jsoup XML parser
     * 中带命名空间前缀的标签（如 opf:item）用 CSS 选择器 "manifest item" 无法匹配。
     */
    private fun parseManifest(doc: org.jsoup.nodes.Document): Map<String, ManifestItem> {
        val manifest = mutableMapOf<String, ManifestItem>()
        // 查找 manifest 或 opf:manifest 元素，然后遍历其子元素
        val manifestEl = doc.select("*").firstOrNull {
            val tn = it.tagName()
            tn == "manifest" || tn.endsWith(":manifest")
        } ?: return manifest
        for (item in manifestEl.children()) {
            val tn = item.tagName()
            if (tn != "item" && !tn.endsWith(":item")) continue
            val id = item.attr("id").takeIf { it.isNotBlank() } ?: continue
            val href = item.attr("href").takeIf { it.isNotBlank() } ?: continue
            val mediaType = item.attr("media-type")
            val properties = item.attr("properties").takeIf { it.isNotBlank() }
            manifest[id] = ManifestItem(id, href, mediaType, properties)
        }
        return manifest
    }

    /**
     * 解析 spine 区域，按阅读顺序提取资源 idref 列表。
     * 使用 tagName 遍历处理带命名空间前缀的标签。
     */
    private fun parseSpine(doc: org.jsoup.nodes.Document): List<String> {
        val spineEl = doc.select("*").firstOrNull {
            val tn = it.tagName()
            tn == "spine" || tn.endsWith(":spine")
        } ?: return emptyList()
        return spineEl.children().mapNotNull { item ->
            val tn = item.tagName()
            if (tn == "itemref" || tn.endsWith(":itemref")) {
                item.attr("idref").takeIf { it.isNotBlank() }
            } else null
        }
    }

    // ==================== NCX 目录解析 ====================

    /** NCX 解析结果，包含 href 到标题的映射和目录条目列表 */
    private data class NcxResult(
        /** href（相对于 opfDir）到目录标题的映射，用于覆盖 spine 的章节标题 */
        val hrefTitleMap: Map<String, String>,
        /** 从 NCX 解析出的目录条目列表，chapterIndex 待后续修正 */
        val tocItems: List<TocItem>,
        /** 与 tocItems 一一对应的 href 列表，用于将 href 映射到 spine 索引 */
        val tocHrefs: List<String>,
    )

    /**
     * 解析 EPUB 2 的 NCX 文件（Navigation Control for XML），提取目录结构。
     *
     * NCX 文件路径从 manifest 中 media-type="application/x-dtbncx+xml" 的条目获取。
     * NCX 结构：
     * <ncx>
     *   <navMap>
     *     <navPoint>
     *       <navLabel><text>章节标题</text></navLabel>
     *       <content src="chapter.xhtml"/>
     *       <navPoint>...</navPoint>  (嵌套子目录)
     *     </navPoint>
     *   </navMap>
     * </ncx>
     *
     * @param bookDir 解包后的 EPUB 根目录
     * @param manifest OPF manifest 中的资源映射
     * @param opfDir OPF 文件所在的相对目录路径
     * @return NCX 解析结果，如果没有 NCX 文件则返回空结果
     */
    private fun parseNcx(bookDir: File, manifest: Map<String, ManifestItem>, opfDir: String): NcxResult {
        // 从 manifest 中查找 NCX 文件（media-type="application/x-dtbncx+xml"）
        val ncxItem = manifest.values.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml"
        } ?: return NcxResult(emptyMap(), emptyList(), emptyList())

        // 读取 NCX 文件内容
        val ncxPath = if (opfDir.isEmpty()) ncxItem.href else "$opfDir/${ncxItem.href}"
        val ncxFile = File(bookDir, ncxPath)
        if (!ncxFile.exists()) return NcxResult(emptyMap(), emptyList(), emptyList())

        val ncxDoc = Jsoup.parse(ncxFile.readText(Charsets.UTF_8), "", Parser.xmlParser())

        // 查找 navMap 元素（支持命名空间前缀）
        val navMap = ncxDoc.select("*").firstOrNull {
            val tn = it.tagName()
            tn == "navMap" || tn.endsWith(":navMap")
        } ?: return NcxResult(emptyMap(), emptyList(), emptyList())

        // 递归解析 navPoint 节点，构建目录条目
        val tocItems = mutableListOf<TocItem>()
        val tocHrefs = mutableListOf<String>()
        val hrefTitleMap = mutableMapOf<String, String>()
        parseNavPoints(navMap.children(), 0, hrefTitleMap, tocItems, tocHrefs)

        // 归一化 href：NCX 中的 content src 相对于 NCX 文件目录，
        // 而 spine 中的 href 相对于 opfDir，需要将 NCX href 转换为相对于 opfDir 的路径。
        // 例如：NCX 在根目录，opfDir="OEBPS"，src="OEBPS/ch1.xhtml" -> 归一化为 "ch1.xhtml"
        val ncxDir = ncxPath.substringBeforeLast("/", "")
        val normalizedTocHrefs = tocHrefs.map { normalizeNcxHref(it, ncxDir, opfDir) }
        val normalizedHrefTitleMap = hrefTitleMap.mapKeys { (href, _) ->
            normalizeNcxHref(href, ncxDir, opfDir)
        }

        return NcxResult(normalizedHrefTitleMap, tocItems, normalizedTocHrefs)
    }

    /**
     * 递归解析 navPoint 节点列表，提取目录标题和 href 映射。
     *
     * 每个 navPoint 包含：
     * - navLabel > text：目录标题
     * - content[src]：对应的 HTML 文件路径（相对于 NCX 所在目录）
     * - 子 navPoint：嵌套的子目录条目
     *
     * @param elements 当前层级的子元素列表
     * @param level 当前目录层级（0=一级，1=二级，以此类推）
     * @param hrefTitleMap href 到标题的映射，用于覆盖 spine 标题
     * @param tocItems 目录条目列表，递归过程中不断追加（chapterIndex 暂为 -1）
     * @param tocHrefs 与 tocItems 一一对应的 href 列表，用于后续映射到 spine 索引
     */
    private fun parseNavPoints(
        elements: org.jsoup.select.Elements,
        level: Int,
        hrefTitleMap: MutableMap<String, String>,
        tocItems: MutableList<TocItem>,
        tocHrefs: MutableList<String>,
    ) {
        for (el in elements) {
            val tn = el.tagName()
            if (tn != "navPoint" && !tn.endsWith(":navPoint")) continue

            // 提取标题：查找 navLabel > text 元素
            val navLabel = el.children().firstOrNull {
                val labelTn = it.tagName()
                labelTn == "navLabel" || labelTn.endsWith(":navLabel")
            }
            val textEl = navLabel?.children()?.firstOrNull {
                val textTn = it.tagName()
                textTn == "text" || textTn.endsWith(":text")
            }
            val title = textEl?.text()?.takeIf { it.isNotBlank() } ?: ""

            // 提取 content src 属性：指向对应的 HTML 文件
            val contentEl = el.children().firstOrNull {
                val contentTn = it.tagName()
                contentTn == "content" || contentTn.endsWith(":content")
            }
            val src = contentEl?.attr("src")?.takeIf { it.isNotBlank() } ?: ""

            // 从 src 中提取纯 href（去掉锚点 #fragment）
            val href = src.substringBefore("#")

            // 将 href 和标题记录到映射中（仅取 spine 中第一个匹配的 href）
            if (href.isNotBlank() && title.isNotBlank()) {
                hrefTitleMap.putIfAbsent(href, title)
            }

            // 目录条目暂时用 -1 作为 chapterIndex 占位，后续在 unpack 中通过 href 匹配修正
            if (title.isNotBlank()) {
                tocItems.add(TocItem(title = title, chapterIndex = -1, level = level))
                tocHrefs.add(href)
            }

            // 递归解析子 navPoint（嵌套目录）
            parseNavPoints(el.children(), level + 1, hrefTitleMap, tocItems, tocHrefs)
        }
    }

    // ==================== 封面提取 ====================

    /**
     * 从解包目录中查找并读取封面图片。
     *
     * 查找策略（按优先级）：
     * 1. EPUB 3.0: manifest item 的 properties 包含 "cover-image"
     * 2. EPUB 2.0: <meta name="cover" content="id"/> 引用的 manifest item
     * 3. 启发式: manifest item 的 id 包含 "cover" 且为图片类型
     */
    private fun findCoverImage(bookDir: File, manifest: Map<String, ManifestItem>, opfDir: String, coverId: String?): ByteArray? {
        // 策略 1：EPUB 3.0 properties="cover-image"
        manifest.values.firstOrNull { it.properties?.contains("cover-image") == true }?.let { item ->
            readImageFromDisk(bookDir, opfDir, item.href)?.let { return it }
        }
        // 策略 2：EPUB 2 <meta name="cover" content="manifestItemId"/>
        if (coverId != null) {
            manifest[coverId]?.let { item ->
                readImageFromDisk(bookDir, opfDir, item.href)?.let { return it }
            }
        }
        // 策略 3：manifest ID 包含 "cover" 的图片资源
        manifest.values.firstOrNull { it.id.lowercase().contains("cover") && isImageResource(it.mediaType) }?.let { item ->
            readImageFromDisk(bookDir, opfDir, item.href)?.let { return it }
        }
        return null
    }

    /** 从解包目录中读取图片文件的字节 */
    private fun readImageFromDisk(bookDir: File, opfDir: String, href: String): ByteArray? {
        val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
        val file = File(bookDir, path)
        return if (file.exists()) file.readBytes() else null
    }

    // ==================== Spine 构建 ====================

    /**
     * 根据 spine 阅读顺序构建 SpineItem 列表。
     * 跳过非 HTML 资源（如 CSS、图片），按优先级提取章节标题：
     * 1. NCX 目录标题（最准确，来自出版商的目录数据）
     * 2. HTML <h1>/<h2> 标题标签（语义化标题）
     * 3. HTML <title> 标签（兜底方案，可能是书名重复）
     *
     * 同时修正 ncxResult.tocItems 中的 chapterIndex（从 href 匹配 spine 索引）。
     *
     * @param bookDir 解包后的 EPUB 根目录
     * @param opfResult OPF 解析结果
     * @param opfDir OPF 文件所在的相对目录路径
     * @param ncxResult NCX 解析结果，提供 href->标题映射和目录条目
     * @return 构建好的 SpineItem 列表
     */
    private fun buildSpineItems(
        bookDir: File,
        opfResult: OpfResult,
        opfDir: String,
        ncxResult: NcxResult,
    ): List<SpineItem> {
        // 构建 spine 列表
        val spineItems = opfResult.spine.mapNotNull { itemRef ->
            val mi = opfResult.manifest[itemRef] ?: return@mapNotNull null
            if (!isHtmlResource(mi.href)) return@mapNotNull null
            val fullPath = if (opfDir.isEmpty()) mi.href else "$opfDir/${mi.href}"
            val htmlFile = File(bookDir, fullPath)

            // 标题提取优先级：NCX > <h1>/<h2> > <title>
            val ncxTitle = ncxResult.hrefTitleMap[mi.href]
            val title = when {
                !ncxTitle.isNullOrBlank() -> ncxTitle
                htmlFile.exists() -> extractHeadingFromHtml(htmlFile.readText(Charsets.UTF_8))
                else -> ""
            }
            SpineItem(id = mi.id, href = mi.href, title = title, mediaType = mi.mediaType)
        }

        return spineItems
    }

    /**
     * 从 HTML 内容中提取语义化标题。
     * 优先从 <h1> 或 <h2> 标签提取（语义化标题，通常更准确），
     * 如果没有找到则回退到 <title> 标签。
     *
     * @param html HTML 文件内容
     * @return 提取到的标题，如果都没有则返回空字符串
     */
    private fun extractHeadingFromHtml(html: String): String {
        val doc = Jsoup.parse(html)
        // 优先查找 <h1> 标签（通常是一级标题，如章节名称）
        val h1 = doc.select("h1").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        if (h1 != null) return h1
        // 其次查找 <h2> 标签
        val h2 = doc.select("h2").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        if (h2 != null) return h2
        // 最后回退到 <title> 标签
        return doc.select("title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: ""
    }

    // ==================== href 归一化 ====================

    /**
     * 将 NCX 中的 href 归一化为相对于 opfDir 的路径。
     *
     * NCX 的 content src 相对于 NCX 文件所在目录，而 spine 的 href 相对于 opfDir。
     * 当 NCX 和 OPF 不在同一目录时，需要转换路径才能正确匹配。
     *
     * B3 改进：使用 java.nio.file.Paths.get(...).normalize() 处理 `..` 段，
     * 避免 `../ch1.xhtml` 等罕见但合规的 NCX href 因路径未规范化而无法匹配 spine。
     * minSdk = 26 完整支持 java.nio.file API。
     *
     * 例如：NCX 在根目录（ncxDir=""），opfDir="OEBPS"，src="OEBPS/ch1.xhtml"
     * -> 先解析为 bookDir 下的绝对相对路径 "OEBPS/ch1.xhtml"
     * -> 再转换为相对于 opfDir 的路径 "ch1.xhtml"
     *
     * @param src NCX content 元素的 src 属性（可能含锚点 #fragment，调用前应已去除）
     * @param ncxDir NCX 文件所在的相对目录路径（相对于 bookDir）
     * @param opfDir OPF 文件所在的相对目录路径（相对于 bookDir）
     * @return 归一化后的 href，相对于 opfDir
     */
    private fun normalizeNcxHref(src: String, ncxDir: String, opfDir: String): String {
        if (src.isBlank()) return src
        // 将 src 从相对于 ncxDir 转换为相对于 bookDir 的路径
        val joined = if (ncxDir.isEmpty()) src else "$ncxDir/$src"
        // 规范化 `..` 与 `.`：用 java.nio.file.Paths 处理路径段
        val resolved = try {
            val normalized = java.nio.file.Paths.get(joined).normalize().toString()
            // Windows 上 Paths 可能返回反斜杠，统一回斜杠
            normalized.replace('\\', '/')
        } catch (_: Exception) {
            joined
        }
        // 再从相对于 bookDir 转换为相对于 opfDir 的路径
        val opfDirNorm = if (opfDir.isEmpty()) "" else opfDir.trimEnd('/')
        return when {
            opfDirNorm.isEmpty() -> resolved
            resolved == opfDirNorm -> ""
            resolved.startsWith("$opfDirNorm/") -> resolved.removePrefix("$opfDirNorm/")
            else -> resolved
        }
    }

    // ==================== 资源类型判断 ====================

    /** 判断 href 是否指向 HTML 资源 */
    private fun isHtmlResource(href: String): Boolean {
        val lower = href.lowercase()
        return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")
    }

    /** 判断 MIME 类型是否为图片 */
    private fun isImageResource(mediaType: String): Boolean = mediaType.lowercase().startsWith("image/")
}
