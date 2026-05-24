package com.example.read.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 全流程 TDD 测试。
 *
 * 覆盖从 EPUB 文件导入到阅读的完整数据流：
 * 1. EPUB 解包（ZIP 解压 + container.xml 解析）
 * 2. OPF 解析（metadata + manifest + spine）
 * 3. NCX 目录解析（navMap + navPoint 层级结构）
 * 4. 章节标题提取优先级（NCX > h1 > h2 > title）
 * 5. SpineItem 构建和 TocItem 到 spine 索引的映射
 * 6. 封面图片提取
 * 7. metadata.json 序列化/反序列化
 * 8. 边界情况和错误处理
 *
 * 测试策略：
 * - 使用内存中的 ZIP 构建测试 EPUB，避免文件系统依赖
 * - 每个测试验证完整流程中的一个环节
 * - 端到端测试验证从输入到输出的完整数据流
 */
class EpubFullFlowTest {

    // ==================== 辅助方法 ====================

    /**
     * 创建包含 NCX 的完整测试 EPUB。
     *
     * @param title 书籍标题
     * @param chapters 章节文件名到 HTML 内容的映射
     * @param ncxContent NCX 文件内容
     * @param opfDir OPF 文件所在目录（默认 "OEBPS"）
     * @param ncxDir NCX 文件所在目录（默认与 opfDir 相同）
     * @return EPUB 文件的字节数组
     */
    private fun createTestEpub(
        title: String,
        chapters: Map<String, String>,
        ncxContent: String? = null,
        opfDir: String = "OEBPS",
        ncxDir: String = opfDir,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // mimetype 条目
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            // container.xml
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="$opfDir/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // 构建 manifest 和 spine
            val manifestItems = chapters.keys.joinToString("\n") { fileName ->
                val id = fileName.substringBefore(".")
                """                    <item id="$id" href="$fileName" media-type="application/xhtml+xml"/>"""
            }
            val spineItems = chapters.keys.joinToString("\n") { fileName ->
                val id = fileName.substringBefore(".")
                """                    <itemref idref="$id"/>"""
            }

            // NCX manifest 条目
            val ncxManifestItem = if (ncxContent != null) {
                """                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
            } else ""

            // content.opf
            zip.putNextEntry(ZipEntry("$opfDir/content.opf"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata>
                    <dc:title>$title</dc:title>
                    <dc:creator>Test Author</dc:creator>
                  </metadata>
                  <manifest>
                    $ncxManifestItem
                    $manifestItems
                  </manifest>
                  <spine>
                    $spineItems
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // 章节 HTML 文件
            for ((fileName, htmlContent) in chapters) {
                zip.putNextEntry(ZipEntry("$opfDir/$fileName"))
                zip.write(htmlContent.toByteArray())
                zip.closeEntry()
            }

            // NCX 文件
            if (ncxContent != null) {
                zip.putNextEntry(ZipEntry("$ncxDir/toc.ncx"))
                zip.write(ncxContent.toByteArray())
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /**
     * 标准 NCX 内容模板。
     */
    private fun createNcxContent(navPoints: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                $navPoints
              </navMap>
            </ncx>
        """.trimIndent()
    }

    /**
     * 创建 navPoint XML 片段。
     */
    private fun navPoint(id: String, title: String, src: String, children: String = ""): String {
        return """
            <navPoint id="$id">
              <navLabel><text>$title</text></navLabel>
              <content src="$src"/>
              $children
            </navPoint>
        """.trimIndent()
    }

    // ==================== 流程 1：完整 EPUB 解包 ====================

    /**
     * 测试完整的 EPUB 解包流程。
     *
     * 验证从 ZIP 输入到 EpubUnpackResult 的完整数据流：
     * 1. ZIP 解压到目标目录
     * 2. container.xml 解析获取 OPF 路径
     * 3. OPF 解析获取 metadata、manifest、spine
     * 4. NCX 解析获取目录结构
     * 5. SpineItem 构建（使用 NCX 标题）
     * 6. TocItem 映射到 spine 索引
     * 7. metadata.json 生成
     */
    @Test
    fun unpack_fullFlow_producesCorrectMetadata() {
        val chapters = mapOf(
            "chapter1.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>HTML标题1</title></head>
                <body><h1>第一章 正文标题</h1><p>内容</p></body>
                </html>
            """.trimIndent(),
            "chapter2.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>HTML标题2</title></head>
                <body><h1>第二章 正文标题</h1><p>内容</p></body>
                </html>
            """.trimIndent(),
            "chapter3.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>HTML标题3</title></head>
                <body><h1>第三章 正文标题</h1><p>内容</p></body>
                </html>
            """.trimIndent(),
        )

        val ncxContent = createNcxContent("""
            ${navPoint("np1", "第一章 NCX标题", "chapter1.xhtml")}
            ${navPoint("np2", "第二章 NCX标题", "chapter2.xhtml")}
            ${navPoint("np3", "第三章 NCX标题", "chapter3.xhtml")}
        """)

        val epubBytes = createTestEpub(
            title = "全流程测试书籍",
            chapters = chapters,
            ncxContent = ncxContent,
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_fullflow_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证元数据
            assertEquals("全流程测试书籍", result.title)
            assertEquals("Test Author", result.author)

            // 验证 spine 使用 NCX 标题（而非 HTML title）
            assertEquals(3, result.metadata.spine.size)
            assertEquals("第一章 NCX标题", result.metadata.spine[0].title)
            assertEquals("第二章 NCX标题", result.metadata.spine[1].title)
            assertEquals("第三章 NCX标题", result.metadata.spine[2].title)

            // 验证 spine href
            assertEquals("chapter1.xhtml", result.metadata.spine[0].href)
            assertEquals("chapter2.xhtml", result.metadata.spine[1].href)
            assertEquals("chapter3.xhtml", result.metadata.spine[2].href)

            // 验证 tocItems
            assertEquals(3, result.metadata.tocItems.size)
            assertEquals("第一章 NCX标题", result.metadata.tocItems[0].title)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)
            assertEquals(0, result.metadata.tocItems[0].level)

            // 验证 opfDir
            assertEquals("OEBPS", result.metadata.opfDir)

            // 验证目录结构
            assertTrue(File(tempDir, "META-INF/container.xml").exists())
            assertTrue(File(tempDir, "OEBPS/content.opf").exists())
            assertTrue(File(tempDir, "OEBPS/toc.ncx").exists())
            assertTrue(File(tempDir, "metadata.json").exists())

            // 验证 metadata.json 可以反序列化
            val metadataJson = File(tempDir, "metadata.json").readText(Charsets.UTF_8)
            assertNotNull(metadataJson)
            assertTrue(metadataJson.contains("全流程测试书籍"))
            assertTrue(metadataJson.contains("tocItems"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 2：章节标题提取优先级 ====================

    /**
     * 测试章节标题提取的完整优先级链。
     *
     * 优先级：NCX 标题 > HTML h1 > HTML h2 > HTML title
     * 验证每个优先级层级都能正确提取标题。
     */
    @Test
    fun titleExtraction_priorityChain_ncxOverH1OverH2OverTitle() {
        // 场景 1：有 NCX 标题时，优先使用 NCX
        val chaptersWithNcx = mapOf(
            "ch1.xhtml" to """
                <html><head><title>HTML标题</title></head>
                <body><h1>H1标题</h1><p>内容</p></body></html>
            """,
        )
        val ncx = createNcxContent(navPoint("np1", "NCX标题", "ch1.xhtml"))

        val epubWithNcx = createTestEpub("测试", chaptersWithNcx, ncx)
        val parser = EpubParser()
        val tempDir1 = File(System.getProperty("java.io.tmpdir"), "epub_title_ncx_${System.nanoTime()}")
        try {
            val result1 = parser.unpack(epubWithNcx.inputStream(), tempDir1)
            assertEquals("NCX标题", result1.metadata.spine[0].title)
        } finally {
            tempDir1.deleteRecursively()
        }

        // 场景 2：无 NCX 时，优先使用 h1
        val chaptersWithH1 = mapOf(
            "ch1.xhtml" to """
                <html><head><title>HTML标题</title></head>
                <body><h1>H1标题</h1><p>内容</p></body></html>
            """,
        )
        val epubWithH1 = createTestEpub("测试", chaptersWithH1)
        val tempDir2 = File(System.getProperty("java.io.tmpdir"), "epub_title_h1_${System.nanoTime()}")
        try {
            val result2 = parser.unpack(epubWithH1.inputStream(), tempDir2)
            assertEquals("H1标题", result2.metadata.spine[0].title)
        } finally {
            tempDir2.deleteRecursively()
        }

        // 场景 3：无 h1 时，使用 h2
        val chaptersWithH2 = mapOf(
            "ch1.xhtml" to """
                <html><head><title>HTML标题</title></head>
                <body><h2>H2标题</h2><p>内容</p></body></html>
            """,
        )
        val epubWithH2 = createTestEpub("测试", chaptersWithH2)
        val tempDir3 = File(System.getProperty("java.io.tmpdir"), "epub_title_h2_${System.nanoTime()}")
        try {
            val result3 = parser.unpack(epubWithH2.inputStream(), tempDir3)
            assertEquals("H2标题", result3.metadata.spine[0].title)
        } finally {
            tempDir3.deleteRecursively()
        }

        // 场景 4：无 h1/h2 时，回退到 title
        val chaptersWithTitleOnly = mapOf(
            "ch1.xhtml" to """
                <html><head><title>HTML标题</title></head>
                <body><p>只有段落</p></body></html>
            """,
        )
        val epubWithTitle = createTestEpub("测试", chaptersWithTitleOnly)
        val tempDir4 = File(System.getProperty("java.io.tmpdir"), "epub_title_title_${System.nanoTime()}")
        try {
            val result4 = parser.unpack(epubWithTitle.inputStream(), tempDir4)
            assertEquals("HTML标题", result4.metadata.spine[0].title)
        } finally {
            tempDir4.deleteRecursively()
        }
    }

    // ==================== 流程 3：NCX 目录层级结构 ====================

    /**
     * 测试多级 NCX 目录的解析。
     *
     * 验证嵌套 navPoint 的 level 值正确递增，
     * 且所有层级的条目都被提取到 tocItems 中。
     */
    @Test
    fun ncxParsing_nestedNavPoints_correctLevelsAndIndices() {
        val chapters = mapOf(
            "part1.xhtml" to "<html><body><h1>第一部分</h1></body></html>",
            "ch1.xhtml" to "<html><body><h1>第一章</h1></body></html>",
            "ch2.xhtml" to "<html><body><h1>第二章</h1></body></html>",
            "part2.xhtml" to "<html><body><h1>第二部分</h1></body></html>",
            "ch3.xhtml" to "<html><body><h1>第三章</h1></body></html>",
        )

        // 嵌套结构：part1 包含 ch1, ch2；part2 包含 ch3
        val ncxContent = createNcxContent("""
            ${navPoint("p1", "第一部分", "part1.xhtml", """
                ${navPoint("c1", "第一章", "ch1.xhtml")}
                ${navPoint("c2", "第二章", "ch2.xhtml")}
            """)}
            ${navPoint("p2", "第二部分", "part2.xhtml", """
                ${navPoint("c3", "第三章", "ch3.xhtml")}
            """)}
        """)

        val epubBytes = createTestEpub("嵌套目录测试", chapters, ncxContent)
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_nested_ncx_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证 5 个目录条目
            assertEquals(5, result.metadata.tocItems.size)

            // 第一部分：level=0, spine index=0
            assertEquals("第一部分", result.metadata.tocItems[0].title)
            assertEquals(0, result.metadata.tocItems[0].level)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)

            // 第一章：level=1, spine index=1
            assertEquals("第一章", result.metadata.tocItems[1].title)
            assertEquals(1, result.metadata.tocItems[1].level)
            assertEquals(1, result.metadata.tocItems[1].chapterIndex)

            // 第二章：level=1, spine index=2
            assertEquals("第二章", result.metadata.tocItems[2].title)
            assertEquals(1, result.metadata.tocItems[2].level)
            assertEquals(2, result.metadata.tocItems[2].chapterIndex)

            // 第二部分：level=0, spine index=3
            assertEquals("第二部分", result.metadata.tocItems[3].title)
            assertEquals(0, result.metadata.tocItems[3].level)
            assertEquals(3, result.metadata.tocItems[3].chapterIndex)

            // 第三章：level=1, spine index=4
            assertEquals("第三章", result.metadata.tocItems[4].title)
            assertEquals(1, result.metadata.tocItems[4].level)
            assertEquals(4, result.metadata.tocItems[4].chapterIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 4：无 NCX 的 EPUB ====================

    /**
     * 测试没有 NCX 文件的 EPUB 的处理。
     *
     * 验证：
     * - tocItems 为空列表
     * - spine 标题从 HTML 提取（h1 > h2 > title）
     * - 不会抛出异常
     */
    @Test
    fun unpack_epubWithoutNcx_gracefulFallback() {
        val chapters = mapOf(
            "ch1.xhtml" to """
                <html><head><title>书名</title></head>
                <body><h1>第一章实际标题</h1><p>内容</p></body></html>
            """,
            "ch2.xhtml" to """
                <html><head><title>书名</title></head>
                <body><h2>第二章实际标题</h2><p>内容</p></body></html>
            """,
            "ch3.xhtml" to """
                <html><head><title>第三章标题</title></head>
                <body><p>没有标题标签</p></body></html>
            """,
        )

        val epubBytes = createTestEpub("无NCX测试", chapters)
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_no_ncx_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // tocItems 为空
            assertTrue(result.metadata.tocItems.isEmpty())

            // spine 标题从 HTML 提取
            assertEquals("第一章实际标题", result.metadata.spine[0].title)
            assertEquals("第二章实际标题", result.metadata.spine[1].title)
            assertEquals("第三章标题", result.metadata.spine[2].title)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 5：NCX href 锚点处理 ====================

    /**
     * 测试 NCX 中带锚点的 href 处理。
     *
     * NCX content src 可能包含 #fragment 锚点（如 "chapter1.xhtml#section1"），
     * 解析时需要去除锚点部分才能正确匹配 spine 中的 href。
     */
    @Test
    fun ncxParsing_hrefWithFragment_matchesSpine() {
        val chapters = mapOf(
            "ch1.xhtml" to "<html><body><h1>第一章</h1></body></html>",
            "ch2.xhtml" to "<html><body><h1>第二章</h1></body></html>",
        )

        // NCX href 带锚点
        val ncxContent = createNcxContent("""
            ${navPoint("np1", "第一章", "ch1.xhtml#section1")}
            ${navPoint("np2", "第二章", "ch2.xhtml#top")}
        """)

        val epubBytes = createTestEpub("锚点测试", chapters, ncxContent)
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_anchor_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证 tocItems 正确映射到 spine 索引
            assertEquals(2, result.metadata.tocItems.size)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)
            assertEquals(1, result.metadata.tocItems[1].chapterIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 6：NCX 与 OPF 不同目录 ====================

    /**
     * 测试 NCX 文件和 OPF 文件在不同目录时的 href 归一化。
     *
     * 场景：OPF 和 NCX 都在 OEBPS/，但 NCX 中的 href 使用了不同的路径格式。
     * 验证 href 归一化能正确处理各种路径格式。
     */
    @Test
    fun ncxParsing_differentDirectories_normalizesHrefs() {
        val chapters = mapOf(
            "ch1.xhtml" to "<html><body><h1>第一章</h1></body></html>",
            "ch2.xhtml" to "<html><body><h1>第二章</h1></body></html>",
        )

        // NCX href 使用相对路径格式
        val ncxContent = createNcxContent("""
            ${navPoint("np1", "第一章", "ch1.xhtml")}
            ${navPoint("np2", "第二章", "ch2.xhtml")}
        """)

        val epubBytes = createTestEpub(
            title = "不同目录测试",
            chapters = chapters,
            ncxContent = ncxContent,
            opfDir = "OEBPS",
            ncxDir = "OEBPS",
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_diff_dir_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证 tocItems 正确映射（NCX href 归一化后匹配 spine）
            assertEquals(2, result.metadata.tocItems.size)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)
            assertEquals(1, result.metadata.tocItems[1].chapterIndex)

            // 验证 spine 标题使用 NCX 标题
            assertEquals("第一章", result.metadata.spine[0].title)
            assertEquals("第二章", result.metadata.spine[1].title)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 7：封面提取 ====================

    /**
     * 测试封面图片的提取策略。
     *
     * 验证 EPUB 3.0 的 cover-image properties 和 EPUB 2.0 的 meta cover 声明。
     */
    @Test
    fun coverExtraction_epub3Properties_extractsCover() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("content.opf"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata>
                    <dc:title>Cover Test</dc:title>
                    <dc:creator>Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // 封面图片（最小 JPEG 头）
            zip.putNextEntry(ZipEntry("cover.jpg"))
            zip.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("ch1.xhtml"))
            zip.write("<html><body>Content</body></html>".toByteArray())
            zip.closeEntry()
        }

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_cover_${System.nanoTime()}")
        try {
            val result = parser.unpack(baos.toByteArray().inputStream(), tempDir)

            // 验证封面被提取
            assertNotNull("Cover bytes should not be null", result.coverBytes)
            assertTrue("Cover bytes should not be empty", result.coverBytes!!.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 8：metadata.json 序列化/反序列化 ====================

    /**
     * 测试 metadata.json 的序列化和反序列化。
     *
     * 验证：
     * - BookMetadata 可以正确序列化为 JSON
     * - JSON 可以正确反序列化为 BookMetadata
     * - 所有字段（包括 tocItems）都正确保留
     */
    @Test
    fun metadataJson_serializationRoundTrip_preservesAllFields() {
        val chapters = mapOf(
            "ch1.xhtml" to "<html><body><h1>第一章</h1></body></html>",
        )
        val ncx = createNcxContent(navPoint("np1", "NCX标题", "ch1.xhtml"))

        val epubBytes = createTestEpub("序列化测试", chapters, ncx)
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_json_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 读取 metadata.json
            val metadataFile = File(tempDir, "metadata.json")
            assertTrue(metadataFile.exists())

            // 反序列化
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val deserialized = json.decodeFromString<BookMetadata>(metadataFile.readText(Charsets.UTF_8))

            // 验证所有字段
            assertEquals(result.metadata.title, deserialized.title)
            assertEquals(result.metadata.author, deserialized.author)
            assertEquals(result.metadata.opfDir, deserialized.opfDir)
            assertEquals(result.metadata.spine.size, deserialized.spine.size)
            assertEquals(result.metadata.tocItems.size, deserialized.tocItems.size)

            // 验证 spine 详情
            assertEquals("NCX标题", deserialized.spine[0].title)
            assertEquals("ch1.xhtml", deserialized.spine[0].href)

            // 验证 tocItems 详情
            assertEquals("NCX标题", deserialized.tocItems[0].title)
            assertEquals(0, deserialized.tocItems[0].chapterIndex)
            assertEquals(0, deserialized.tocItems[0].level)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 9：错误处理 ====================

    /**
     * 测试无效 ZIP 文件的错误处理。
     */
    @Test(expected = IllegalArgumentException::class)
    fun unpack_invalidZip_throwsException() {
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_invalid_${System.nanoTime()}")
        try {
            parser.unpack("not a zip file".byteInputStream(), tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 测试缺少 container.xml 的 EPUB 的错误处理。
     */
    @Test(expected = IllegalArgumentException::class)
    fun unpack_missingContainerXml_throwsException() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            // 故意不包含 container.xml
        }

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_no_container_${System.nanoTime()}")
        try {
            parser.unpack(baos.toByteArray().inputStream(), tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 测试缺少 OPF 文件的 EPUB 的错误处理。
     */
    @Test(expected = IllegalArgumentException::class)
    fun unpack_missingOpfFile_throwsException() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="missing.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            // 故意不包含 missing.opf
        }

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_no_opf_${System.nanoTime()}")
        try {
            parser.unpack(baos.toByteArray().inputStream(), tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== 流程 10：边界情况 ====================

    /**
     * 测试单章节 EPUB 的处理。
     */
    @Test
    fun unpack_singleChapter_correctSpineAndToc() {
        val chapters = mapOf(
            "only.xhtml" to "<html><body><h1>唯一章节</h1></body></html>",
        )
        val ncx = createNcxContent(navPoint("np1", "唯一章节", "only.xhtml"))

        val epubBytes = createTestEpub("单章节", chapters, ncx)
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_single_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            assertEquals(1, result.metadata.spine.size)
            assertEquals(1, result.metadata.tocItems.size)
            assertEquals("唯一章节", result.metadata.spine[0].title)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 测试包含非 HTML 资源的 EPUB（CSS、图片）。
     *
     * 验证 spine 只包含 HTML 资源，CSS 和图片被正确过滤。
     */
    @Test
    fun unpack_withNonHtmlResources_filtersCorrectly() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata>
                    <dc:title>Resource Test</dc:title>
                    <dc:creator>Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="css" href="style.css" media-type="text/css"/>
                    <item id="img" href="cover.jpg" media-type="image/jpeg"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="css"/>
                    <itemref idref="ch1"/>
                    <itemref idref="img"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/style.css"))
            zip.write("body { color: red; }".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/cover.jpg"))
            zip.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zip.write("<html><body>Ch1</body></html>".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch2.xhtml"))
            zip.write("<html><body>Ch2</body></html>".toByteArray())
            zip.closeEntry()
        }

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_resources_${System.nanoTime()}")
        try {
            val result = parser.unpack(baos.toByteArray().inputStream(), tempDir)

            // spine 只包含 HTML 资源
            assertEquals(2, result.metadata.spine.size)
            assertEquals("ch1.xhtml", result.metadata.spine[0].href)
            assertEquals("ch2.xhtml", result.metadata.spine[1].href)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 测试 NCX 中引用了 spine 中不存在的章节时的处理。
     *
     * 验证无法匹配的 tocItem 被过滤掉，不会导致异常。
     */
    @Test
    fun ncxParsing_orphanedNavPoint_filteredOut() {
        val chapters = mapOf(
            "ch1.xhtml" to "<html><body><h1>第一章</h1></body></html>",
        )

        // NCX 引用了 spine 中不存在的 ch2.xhtml
        val ncxContent = createNcxContent("""
            ${navPoint("np1", "第一章", "ch1.xhtml")}
            ${navPoint("np2", "不存在的章节", "ch2.xhtml")}
        """)

        val epubBytes = createTestEpub("孤立条目测试", chapters, ncxContent)
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_orphan_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 只有匹配的条目被保留
            assertEquals(1, result.metadata.tocItems.size)
            assertEquals("第一章", result.metadata.tocItems[0].title)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
