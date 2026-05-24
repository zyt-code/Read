package com.example.read.util

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EpubParser 单元测试。
 *
 * 测试范围：
 * 1. XML 解析逻辑（通过 Jsoup XML Parser 验证 container.xml 和 OPF 解析）
 * 2. EPUB 解包流程（unpack 方法的端到端测试）
 * 3. HTML 转纯文本（Jsoup 依赖行为验证）
 */
class EpubParserTest {

    // ==================== XML 解析验证 ====================

    /**
     * 验证 container.xml 的 DOM 解析能正确提取 full-path 属性。
     * 使用 Jsoup XML Parser 直接解析，验证解析器选择的正确性。
     */
    @Test
    fun parseContainerXml_withStandardFormat_extractsFullPath() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootfile = doc.select("rootfile").first()
        val fullPath = rootfile?.attr("full-path")

        assertEquals("OEBPS/content.opf", fullPath)
    }

    /**
     * 验证 OPF 的 manifest 解析能正确提取所有 item 元素。
     */
    @Test
    fun parseOpf_manifest_extractsAllItems() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Test Book</dc:title>
                <dc:creator>Test Author</dc:creator>
              </metadata>
              <manifest>
                <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()

        val doc = Jsoup.parse(opf, "", Parser.xmlParser())
        val items = doc.select("manifest item")

        assertEquals(4, items.size)

        // 验证第一个 item 的属性
        val coverItem = items[0]
        assertEquals("cover", coverItem.attr("id"))
        assertEquals("cover.jpg", coverItem.attr("href"))
        assertEquals("image/jpeg", coverItem.attr("media-type"))
        assertEquals("cover-image", coverItem.attr("properties"))

        // 验证 HTML 资源
        val ch1 = items[1]
        assertEquals("ch1", ch1.attr("id"))
        assertEquals("chapter1.xhtml", ch1.attr("href"))
    }

    /**
     * 验证 OPF 的 spine 解析能正确提取阅读顺序。
     */
    @Test
    fun parseOpf_spine_extractsReadingOrder() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Test Book</dc:title>
              </metadata>
              <manifest>
                <item id="intro" href="intro.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="intro"/>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()

        val doc = Jsoup.parse(opf, "", Parser.xmlParser())
        val spineItems = doc.select("spine itemref")

        assertEquals(3, spineItems.size)
        assertEquals("intro", spineItems[0].attr("idref"))
        assertEquals("ch1", spineItems[1].attr("idref"))
        assertEquals("ch2", spineItems[2].attr("idref"))
    }

    /**
     * 验证 EPUB 2 的 meta cover 声明解析。
     */
    @Test
    fun parseOpf_epub2CoverMeta_extractsCoverId() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Test Book</dc:title>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/>
              </manifest>
              <spine/>
            </package>
        """.trimIndent()

        val doc = Jsoup.parse(opf, "", Parser.xmlParser())
        // 查找 <meta name="cover" content="..."/>
        var coverId: String? = null
        for (meta in doc.select("metadata meta")) {
            if (meta.attr("name") == "cover") {
                coverId = meta.attr("content")
                break
            }
        }

        assertEquals("cover-img", coverId)
    }

    /**
     * 验证 Dublin Core 命名空间标签的解析。
     * EPUB 使用 dc:title 和 dc:creator 等带命名空间前缀的标签。
     * Jsoup XML parser 将 dc:title 作为完整的标签名（含冒号），
     * 需要用 tagName 遍历而非 CSS 选择器。
     */
    @Test
    fun parseOpf_dublinCoreMetadata_extractsTitleAndAuthor() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>东野圭吾の悪意</dc:title>
                <dc:creator>东野圭吾</dc:creator>
              </metadata>
              <manifest/>
              <spine/>
            </package>
        """.trimIndent()

        val doc = Jsoup.parse(opf, "", Parser.xmlParser())
        // Jsoup XML parser 将 dc:title 作为完整标签名，需要遍历 metadata 子元素
        val metadataChildren = doc.select("metadata > *")
        val title = metadataChildren.firstOrNull { it.tagName().endsWith(":title") || it.tagName() == "title" }?.text()
        val author = metadataChildren.firstOrNull { it.tagName().endsWith(":creator") || it.tagName() == "creator" }?.text()

        assertEquals("东野圭吾の悪意", title)
        assertEquals("东野圭吾", author)
    }

    // ==================== EPUB 解包端到端测试 ====================

    /**
     * 创建最小的测试 EPUB 文件（ZIP 格式）。
     * 包含：container.xml + content.opf + 一个章节 HTML
     */
    private fun createMinimalEpub(title: String, author: String, chapterHtml: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // mimetype 条目（EPUB 必需）
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            // container.xml
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

            // content.opf
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata>
                    <dc:title>$title</dc:title>
                    <dc:creator>$author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // 章节 HTML
            zip.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zip.write(chapterHtml.toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /**
     * 测试 unpack 方法能正确解包最小 EPUB 并提取元数据。
     */
    @Test
    fun unpack_minimalEpub_extractsMetadata() {
        val epubBytes = createMinimalEpub(
            title = "测试书籍",
            author = "测试作者",
            chapterHtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>第一章</title></head>
                <body><p>这是第一章的内容。</p></body>
                </html>
            """.trimIndent()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证元数据
            assertEquals("测试书籍", result.title)
            assertEquals("测试作者", result.author)

            // 验证 spine
            assertEquals(1, result.metadata.spine.size)
            assertEquals("ch1", result.metadata.spine[0].id)
            assertEquals("chapter1.xhtml", result.metadata.spine[0].href)
            assertEquals("第一章", result.metadata.spine[0].title)

            // 验证目录结构
            assertTrue(File(tempDir, "META-INF/container.xml").exists())
            assertTrue(File(tempDir, "OEBPS/content.opf").exists())
            assertTrue(File(tempDir, "OEBPS/chapter1.xhtml").exists())
            assertTrue(File(tempDir, "metadata.json").exists())

            // 验证 opfDir
            assertEquals("OEBPS", result.metadata.opfDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 测试 unpack 方法对无效 EPUB 的错误处理。
     */
    @Test(expected = IllegalArgumentException::class)
    fun unpack_invalidZip_throwsException() {
        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_invalid_${System.nanoTime()}")
        try {
            parser.unpack("not a zip file".byteInputStream(), tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 测试包含封面图片的 EPUB 解包。
     */
    @Test
    fun unpack_epubWithCover_extractsCoverBytes() {
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
                    <dc:title>Book</dc:title>
                    <dc:creator>Author</dc:creator>
                    <meta name="cover" content="cover-img"/>
                  </metadata>
                  <manifest>
                    <item id="cover-img" href="cover.jpg" media-type="image/jpeg"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            // 封面图片（1x1 像素的最小 JPEG 头）
            zip.putNextEntry(ZipEntry("cover.jpg"))
            zip.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("ch1.xhtml"))
            zip.write("<html><head><title>Ch1</title></head><body>Content</body></html>".toByteArray())
            zip.closeEntry()
        }

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_cover_${System.nanoTime()}")
        try {
            val result = parser.unpack(baos.toByteArray().inputStream(), tempDir)

            // 验证封面被提取
            assertTrue("Cover bytes should not be null", result.coverBytes != null)
            assertTrue("Cover bytes should not be empty", result.coverBytes!!.isNotEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== HTML 转纯文本（Jsoup 依赖行为） ====================

    /**
     * 测试 Jsoup 的 HTML 转纯文本行为。
     * 虽然不再直接调用 EpubParser.htmlToPlainText()，
     * 但 Jsoup.parse().text() 仍然是阅读器的依赖。
     */
    @Test
    fun jsoupHtmlToText_simpleParagraph_returnsTextOnly() {
        val result = Jsoup.parse("<p>Hello World</p>").text()
        assertEquals("Hello World", result)
    }

    @Test
    fun jsoupHtmlToText_nestedTags_returnsFlattenedText() {
        val html = "<div><p>First <span>paragraph</span></p><p>Second paragraph</p></div>"
        val result = Jsoup.parse(html).text()
        assertEquals("First paragraph Second paragraph", result)
    }

    @Test
    fun jsoupHtmlToText_unicodeCharacters_preservesCorrectly() {
        val result = Jsoup.parse("<p>这是中文内容 with English</p>").text()
        assertEquals("这是中文内容 with English", result)
    }

    @Test
    fun jsoupHtmlToText_emptyHtml_returnsEmptyString() {
        assertEquals("", Jsoup.parse("").text())
    }

    @Test
    fun jsoupHtmlToText_htmlEntities_decodesCorrectly() {
        val result = Jsoup.parse("<p>Tom &amp; Jerry &lt;friends&gt;</p>").text()
        assertEquals("Tom & Jerry <friends>", result)
    }

    /**
     * 验证 Jsoup XML Parser 能正确处理带命名空间的 EPUB XML。
     * Jsoup XML parser 将命名空间前缀作为标签名的一部分，
     * 如 opf:item 的 tagName 是 "opf:item"，但 CSS 选择器用 "item" 也能匹配。
     */
    @Test
    fun jsoupXmlParser_namespaceHandling_parsesCorrectly() {
        val xml = """
            <opf:package xmlns:opf="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <opf:metadata>
                <dc:title>测试标题</dc:title>
                <dc:creator>测试作者</dc:creator>
              </opf:metadata>
              <opf:manifest>
                <opf:item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </opf:manifest>
              <opf:spine>
                <opf:itemref idref="ch1"/>
              </opf:spine>
            </opf:package>
        """.trimIndent()

        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        // Jsoup XML parser 中 opf:item 的 tagName 是 "opf:item"，
        // CSS 选择器需要遍历子元素按 tagName 后缀匹配
        val allElements = doc.select("*")
        val items = allElements.filter { it.tagName().endsWith(":item") || it.tagName() == "item" }
        assertEquals(1, items.size)
        assertEquals("ch1", items[0].attr("id"))

        val spineItems = allElements.filter { it.tagName().endsWith(":itemref") || it.tagName() == "itemref" }
        assertEquals(1, spineItems.size)
        assertEquals("ch1", spineItems[0].attr("idref"))
    }

    // ==================== NCX 目录解析测试 ====================

    /**
     * 创建包含 NCX 文件的测试 EPUB。
     *
     * 生成标准 EPUB 结构：container.xml + content.opf（含 NCX manifest 条目）+ 章节 HTML + NCX 文件。
     * NCX 内容由调用方提供，支持自定义目录结构（单级、嵌套、带锚点等）。
     *
     * @param title 书籍标题
     * @param chapterHtmls 章节文件名到 HTML 内容的映射（如 "chapter1.xhtml" -> "<html>..."）
     * @param ncxContent NCX XML 内容字符串
     * @param ncxDir NCX 文件相对于 ZIP 根目录的目录（默认 "OEBPS"，与 OPF 同目录）
     * @return EPUB 文件的字节数组
     */
    private fun createEpubWithNcx(
        title: String,
        chapterHtmls: Map<String, String>,
        ncxContent: String,
        ncxDir: String = "OEBPS",
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // mimetype 条目（EPUB 必需）
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()

            // container.xml（OPF 路径指向 OEBPS/content.opf）
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // 构建 manifest 中的章节 item 列表和 spine itemref 列表
            val manifestItems = chapterHtmls.keys.joinToString("\n") { fileName ->
                val id = fileName.substringBefore(".")
                """                    <item id="$id" href="$fileName" media-type="application/xhtml+xml"/>"""
            }
            val spineItems = chapterHtmls.keys.joinToString("\n") { fileName ->
                val id = fileName.substringBefore(".")
                """                    <itemref idref="$id"/>"""
            }

            // content.opf（包含 NCX manifest 条目，media-type 为 application/x-dtbncx+xml）
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata>
                    <dc:title>$title</dc:title>
                    <dc:creator>Test Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
$manifestItems
                  </manifest>
                  <spine>
$spineItems
                  </spine>
                </package>
            """.trimIndent().toByteArray()
            )
            zip.closeEntry()

            // 写入各章节 HTML 文件
            for ((fileName, htmlContent) in chapterHtmls) {
                zip.putNextEntry(ZipEntry("$ncxDir/$fileName"))
                zip.write(htmlContent.toByteArray())
                zip.closeEntry()
            }

            // 写入 NCX 文件
            zip.putNextEntry(ZipEntry("$ncxDir/toc.ncx"))
            zip.write(ncxContent.toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /**
     * 验证 unpack 方法能从 NCX 文件中正确提取目录条目（tocItems）。
     *
     * 测试场景：NCX 包含两个同级 navPoint 节点，分别对应两个章节。
     * 预期：tocItems 包含两个条目，标题和层级正确，chapterIndex 与 spine 索引匹配。
     */
    @Test
    fun unpack_epubWithNcx_extractsTocItems() {
        // 两个章节的 HTML 内容
        val chapterHtmls = mapOf(
            "chapter1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch1</title></head>
                <body><p>第一章内容</p></body>
                </html>""",
            "chapter2.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch2</title></head>
                <body><p>第二章内容</p></body>
                </html>""",
        )

        // NCX 文件：两个同级 navPoint，层级均为 0
        val ncxContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="np1">
                  <navLabel><text>第一章 开始</text></navLabel>
                  <content src="chapter1.xhtml"/>
                </navPoint>
                <navPoint id="np2">
                  <navLabel><text>第二章 发展</text></navLabel>
                  <content src="chapter2.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val epubBytes = createEpubWithNcx(
            title = "NCX测试书籍",
            chapterHtmls = chapterHtmls,
            ncxContent = ncxContent,
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_ncx_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证 tocItems 被正确提取
            assertEquals(2, result.metadata.tocItems.size)

            // 第一个目录条目：标题正确、层级为 0、对应 spine 索引 0
            assertEquals("第一章 开始", result.metadata.tocItems[0].title)
            assertEquals(0, result.metadata.tocItems[0].level)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)

            // 第二个目录条目：标题正确、层级为 0、对应 spine 索引 1
            assertEquals("第二章 发展", result.metadata.tocItems[1].title)
            assertEquals(0, result.metadata.tocItems[1].level)
            assertEquals(1, result.metadata.tocItems[1].chapterIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证 unpack 方法能正确处理 NCX 中的嵌套 navPoint（多级目录）。
     *
     * 测试场景：NCX 包含一个父 navPoint 和一个子 navPoint（child 嵌套在 parent 内部）。
     * 预期：父条目 level=0，子条目 level=1，两个条目都出现在 tocItems 中。
     */
    @Test
    fun unpack_epubWithNestedNcx_extractsNestedTocItems() {
        // 两个章节的 HTML 内容
        val chapterHtmls = mapOf(
            "chapter1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch1</title></head>
                <body><p>内容</p></body>
                </html>""",
            "chapter2.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch2</title></head>
                <body><p>内容</p></body>
                </html>""",
        )

        // NCX 文件：嵌套结构——parent 包含 child navPoint
        val ncxContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="parent">
                  <navLabel><text>第一部分</text></navLabel>
                  <content src="chapter1.xhtml"/>
                  <navPoint id="child">
                    <navLabel><text>第一章 详细内容</text></navLabel>
                    <content src="chapter2.xhtml"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val epubBytes = createEpubWithNcx(
            title = "嵌套目录测试",
            chapterHtmls = chapterHtmls,
            ncxContent = ncxContent,
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_nested_ncx_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 验证两个目录条目都被提取
            assertEquals(2, result.metadata.tocItems.size)

            // 父条目：level=0
            assertEquals("第一部分", result.metadata.tocItems[0].title)
            assertEquals(0, result.metadata.tocItems[0].level)
            assertEquals(0, result.metadata.tocItems[0].chapterIndex)

            // 子条目：level=1（嵌套在父条目内）
            assertEquals("第一章 详细内容", result.metadata.tocItems[1].title)
            assertEquals(1, result.metadata.tocItems[1].level)
            assertEquals(1, result.metadata.tocItems[1].chapterIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证 unpack 方法在 EPUB 没有 NCX 文件时返回空的 tocItems 列表。
     *
     * 测试场景：使用 createMinimalEpub 生成的 EPUB 不包含 NCX 文件（manifest 中无 NCX 条目）。
     * 预期：tocItems 为空列表，spine 列表仍正常构建。
     */
    @Test
    fun unpack_epubWithoutNcx_tocItemsIsEmpty() {
        val epubBytes = createMinimalEpub(
            title = "无NCX书籍",
            author = "作者",
            chapterHtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>唯一章节</title></head>
                <body><p>内容</p></body>
                </html>
            """.trimIndent()
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_no_ncx_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // 没有 NCX 文件时 tocItems 应为空列表
            assertTrue("tocItems should be empty when no NCX exists", result.metadata.tocItems.isEmpty())

            // spine 仍然正常构建（不受 NCX 缺失影响）
            assertEquals(1, result.metadata.spine.size)
            assertEquals("唯一章节", result.metadata.spine[0].title)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证 unpack 方法在有 NCX 时优先使用 NCX 标题作为 spine 的章节标题。
     *
     * 测试场景：HTML 文件的 <title> 标签内容为 "HTML标题"，但 NCX 中对应条目的标题为 "NCX目录标题"。
     * 预期：spine 的 title 字段使用 NCX 标题（"NCX目录标题"），而非 HTML 的 <title> 内容。
     */
    @Test
    fun unpack_epubWithNcx_usesNcxTitlesForSpineItems() {
        // HTML 的 <title> 标签设置为 "HTML标题"，与 NCX 标题不同
        val chapterHtmls = mapOf(
            "chapter1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>HTML标题</title></head>
                <body><p>内容</p></body>
                </html>""",
        )

        // NCX 中的标题为 "NCX目录标题"，与 HTML <title> 不同
        val ncxContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="np1">
                  <navLabel><text>NCX目录标题</text></navLabel>
                  <content src="chapter1.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val epubBytes = createEpubWithNcx(
            title = "标题优先级测试",
            chapterHtmls = chapterHtmls,
            ncxContent = ncxContent,
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_ncx_title_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // spine 的标题应使用 NCX 的标题而非 HTML <title>
            assertEquals(1, result.metadata.spine.size)
            assertEquals(
                "Spine title should prefer NCX title over HTML <title>",
                "NCX目录标题",
                result.metadata.spine[0].title,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * 验证标题提取的优先级：h1 > h2 > title 标签。
     *
     * 测试场景：通过 unpack 间接测试私有方法 extractHeadingFromHtml。
     * 创建不含 NCX 的 EPUB，使 spine 标题从 HTML 内容提取。
     *
     * 场景 1：HTML 同时包含 h1 和 title，预期使用 h1 内容。
     * 场景 2：HTML 只有 title 没有 h1/h2，预期使用 title 内容。
     */
    @Test
    fun extractHeadingFromHtml_prefersH1OverTitle() {
        // 场景 1：同时有 h1 和 title，h1 应优先
        val epubWithH1 = createMinimalEpub(
            title = "h1优先测试",
            author = "作者",
            chapterHtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>这是Title标签</title></head>
                <body><h1>这是H1标题</h1><p>内容</p></body>
                </html>
            """.trimIndent()
        )

        val parser = EpubParser()
        val tempDir1 = File(System.getProperty("java.io.tmpdir"), "epub_test_h1_${System.nanoTime()}")
        try {
            val result1 = parser.unpack(epubWithH1.inputStream(), tempDir1)
            assertEquals(
                "Should prefer <h1> over <title>",
                "这是H1标题",
                result1.metadata.spine[0].title,
            )
        } finally {
            tempDir1.deleteRecursively()
        }

        // 场景 2：只有 title，没有 h1/h2，应回退到 title
        val epubWithTitleOnly = createMinimalEpub(
            title = "title回退测试",
            author = "作者",
            chapterHtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>仅Title标签</title></head>
                <body><p>没有标题标签的内容</p></body>
                </html>
            """.trimIndent()
        )

        val tempDir2 = File(System.getProperty("java.io.tmpdir"), "epub_test_title_${System.nanoTime()}")
        try {
            val result2 = parser.unpack(epubWithTitleOnly.inputStream(), tempDir2)
            assertEquals(
                "Should fall back to <title> when no h1/h2 exists",
                "仅Title标签",
                result2.metadata.spine[0].title,
            )
        } finally {
            tempDir2.deleteRecursively()
        }
    }

    /**
     * 验证 NCX 中的 href 能正确映射到 spine 索引。
     *
     * 测试场景：三个章节按 spine 顺序为 ch1、ch2、ch3。
     * NCX 的 navPoint 乱序引用（先 ch3，再 ch1，最后 ch2），
     * 且部分 href 包含锚点（#section1），验证锚点被正确剥离后匹配 spine 索引。
     *
     * 预期：tocItems 的 chapterIndex 按 href 正确映射到 spine 中的实际索引位置。
     */
    @Test
    fun unpack_epubWithNcx_mapsHrefToSpineIndex() {
        val chapterHtmls = mapOf(
            "chapter1.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch1</title></head>
                <body><p>第一章</p></body>
                </html>""",
            "chapter2.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch2</title></head>
                <body><p>第二章</p></body>
                </html>""",
            "chapter3.xhtml" to """<?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Ch3</title></head>
                <body><p>第三章</p></body>
                </html>""",
        )

        // NCX 中 navPoint 乱序引用章节，且 ch2 的 href 包含锚点
        val ncxContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="np3">
                  <navLabel><text>第三章 先出现</text></navLabel>
                  <content src="chapter3.xhtml"/>
                </navPoint>
                <navPoint id="np1">
                  <navLabel><text>第一章 后出现</text></navLabel>
                  <content src="chapter1.xhtml"/>
                </navPoint>
                <navPoint id="np2">
                  <navLabel><text>第二章 带锚点</text></navLabel>
                  <content src="chapter2.xhtml#section1"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val epubBytes = createEpubWithNcx(
            title = "href映射测试",
            chapterHtmls = chapterHtmls,
            ncxContent = ncxContent,
        )

        val parser = EpubParser()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_href_map_${System.nanoTime()}")
        try {
            val result = parser.unpack(epubBytes.inputStream(), tempDir)

            // spine 顺序固定为：ch1(index=0), ch2(index=1), ch3(index=2)
            assertEquals(3, result.metadata.spine.size)
            assertEquals("chapter1.xhtml", result.metadata.spine[0].href)
            assertEquals("chapter2.xhtml", result.metadata.spine[1].href)
            assertEquals("chapter3.xhtml", result.metadata.spine[2].href)

            // 验证 tocItems 的 chapterIndex 正确映射到 spine 索引
            assertEquals(3, result.metadata.tocItems.size)

            // NCX 第一个条目引用 chapter3.xhtml -> spine 索引 2
            assertEquals("第三章 先出现", result.metadata.tocItems[0].title)
            assertEquals(2, result.metadata.tocItems[0].chapterIndex)

            // NCX 第二个条目引用 chapter1.xhtml -> spine 索引 0
            assertEquals("第一章 后出现", result.metadata.tocItems[1].title)
            assertEquals(0, result.metadata.tocItems[1].chapterIndex)

            // NCX 第三个条目引用 chapter2.xhtml#section1（锚点应被剥离） -> spine 索引 1
            assertEquals("第二章 带锚点", result.metadata.tocItems[2].title)
            assertEquals(1, result.metadata.tocItems[2].chapterIndex)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
