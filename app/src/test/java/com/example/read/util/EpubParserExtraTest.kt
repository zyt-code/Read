package com.example.read.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EpubParser 补强测试：聚焦于既有测试未覆盖的分支。
 *
 * 测试范围：
 * - OPF spine 严格按声明顺序排列（即使 manifest 顺序与 spine 不同）
 * - manifest 引用：spine 中 idref 找不到对应 manifest 时被静默跳过
 * - quickExtractMetadata() 快速元数据提取（成功与失败）
 * - quickExtractMetadata() 返回的 coverId 字段
 * - extractCoverFromZip() 通过 coverId 提取封面
 * - 空 ZIP、缺少 container.xml / 缺少 OPF 的回退分支（验证异常类型与消息）
 * - container.xml 缺少 rootfile 元素的错误
 * - container.xml 中 full-path 属性为空的错误
 * - 无 metadata 字段（dc:title/dc:creator）时使用 "Unknown" 兜底
 *
 * 这些测试与 EpubParserTest、EpubFullFlowTest 互补，避免重复，专注边界与异常路径。
 */
class EpubParserExtraTest {

    // ==================== 辅助方法 ====================

    /**
     * 创建包含指定条目的 ZIP 字节数组。
     */
    private fun createZipWithEntries(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /**
     * 创建一个临时目录，并保证测试结束时清理。
     */
    private fun newTempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "epub_extra_${tag}_${System.nanoTime()}")

    // ==================== OPF spine 顺序解析 ====================

    /**
     * Given: spine 中 itemref 的顺序与 manifest 中 item 声明顺序不一致
     * When: 调用 unpack
     * Then: spine 列表严格按 itemref 出现顺序排列，而非 manifest 顺序
     */
    @Test
    fun `should preserve spine reading order independent of manifest order`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>顺序测试</dc:title>
                <dc:creator>作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ch3" href="c3.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="c2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
                <itemref idref="ch3"/>
              </spine>
            </package>
        """.trimIndent()

        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            "c1.xhtml" to "<html><body><h1>1</h1></body></html>".toByteArray(),
            "c2.xhtml" to "<html><body><h1>2</h1></body></html>".toByteArray(),
            "c3.xhtml" to "<html><body><h1>3</h1></body></html>".toByteArray(),
        )

        val parser = EpubParser()
        val tempDir = newTempDir("spine_order")
        try {
            val result = parser.unpack(epub.inputStream(), tempDir)
            assertEquals(3, result.metadata.spine.size)
            // spine 顺序应严格按 itemref，而非 manifest 顺序
            assertEquals("c1.xhtml", result.metadata.spine[0].href)
            assertEquals("c2.xhtml", result.metadata.spine[1].href)
            assertEquals("c3.xhtml", result.metadata.spine[2].href)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Given: spine 引用了 manifest 中不存在的 idref（dangling reference）
     * When: 调用 unpack
     * Then: 该条目被静默跳过，最终 spine 只包含可解析的条目
     */
    @Test
    fun `should skip spine itemref when manifest lacks corresponding item`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>悬空 idref</dc:title>
                <dc:creator>作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ghost"/>
              </spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            "c1.xhtml" to "<html><body><h1>1</h1></body></html>".toByteArray(),
        )

        val parser = EpubParser()
        val tempDir = newTempDir("dangling_ref")
        try {
            val result = parser.unpack(epub.inputStream(), tempDir)
            // 仅保留 manifest 中存在的 ch1，ghost 被跳过
            assertEquals(1, result.metadata.spine.size)
            assertEquals("c1.xhtml", result.metadata.spine[0].href)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== quickExtractMetadata ====================

    /**
     * Given: 一个完整的 EPUB 字节流
     * When: 调用 quickExtractMetadata
     * Then: 返回 QuickMetadata，包含 title、author、totalChapters 和 coverId
     *       但 coverBytes 字段始终为 null（需单独调用 extractCoverFromZip）
     */
    @Test
    fun `should extract quick metadata without cover bytes`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>快速元数据</dc:title>
                <dc:creator>测试作者</dc:creator>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="cover-img" href="cover.jpg" media-type="image/jpeg"/>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="c2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            "c1.xhtml" to "<html/>".toByteArray(),
            "c2.xhtml" to "<html/>".toByteArray(),
            "cover.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
        )

        val meta = EpubParser().quickExtractMetadata(epub.inputStream())
        assertNotNull(meta)
        assertEquals("快速元数据", meta!!.title)
        assertEquals("测试作者", meta.author)
        assertEquals(2, meta.totalChapters)
        assertEquals("cover-img", meta.coverId)
        // quickExtractMetadata 始终不提取封面字节
        assertNull(meta.coverBytes)
    }

    /**
     * Given: 非法的字节流（不是 ZIP 格式）
     * When: 调用 quickExtractMetadata
     * Then: 返回 null（内部捕获异常）
     */
    @Test
    fun `should return null when quickExtractMetadata receives invalid bytes`() {
        val meta = EpubParser().quickExtractMetadata("not a zip".byteInputStream())
        assertNull("非 ZIP 输入应返回 null", meta)
    }

    /**
     * Given: ZIP 中缺少 container.xml
     * When: 调用 quickExtractMetadata
     * Then: 返回 null（不抛异常）
     */
    @Test
    fun `should return null when quickExtractMetadata cannot find container xml`() {
        val epub = createZipWithEntries(
            "irrelevant.txt" to "noop".toByteArray(),
        )
        val meta = EpubParser().quickExtractMetadata(epub.inputStream())
        assertNull(meta)
    }

    // ==================== extractCoverFromZip ====================

    /**
     * Given: EPUB 中含封面图片，OPF 通过 meta name="cover" 引用
     * When: 提供 coverId 调用 extractCoverFromZip
     * Then: 返回非空字节数组
     */
    @Test
    fun `should extract cover from zip when coverId provided`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>封面提取</dc:title>
                <dc:creator>作者</dc:creator>
                <meta name="cover" content="cv"/>
              </metadata>
              <manifest>
                <item id="cv" href="cover.jpg" media-type="image/jpeg"/>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val coverPayload = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte(),
        )
        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            "c1.xhtml" to "<html/>".toByteArray(),
            "cover.jpg" to coverPayload,
        )

        val bytes = EpubParser().extractCoverFromZip(epub.inputStream(), "cv")
        assertNotNull(bytes)
        assertTrue("封面字节不应为空", bytes!!.isNotEmpty())
        assertEquals(coverPayload.size, bytes.size)
    }

    /**
     * Given: 损坏的输入流
     * When: 调用 extractCoverFromZip
     * Then: 不抛异常，返回 null
     */
    @Test
    fun `should return null when extractCoverFromZip receives invalid bytes`() {
        val bytes = EpubParser().extractCoverFromZip("garbage".byteInputStream(), "any")
        assertNull(bytes)
    }

    // ==================== 错误路径 ====================

    /**
     * Given: ZIP 中没有 META-INF/container.xml
     * When: 调用 unpack
     * Then: 抛出 IllegalArgumentException，消息包含 "container.xml"
     */
    @Test
    fun `should throw IllegalArgumentException when container xml is missing`() {
        val epub = createZipWithEntries(
            "irrelevant/dummy.txt" to "noop".toByteArray(),
        )
        val parser = EpubParser()
        val tempDir = newTempDir("no_container")
        try {
            val ex = try {
                parser.unpack(epub.inputStream(), tempDir)
                null
            } catch (e: IllegalArgumentException) {
                e
            }
            assertNotNull("缺少 container.xml 应抛 IllegalArgumentException", ex)
            assertTrue(
                "异常消息应提及 container.xml，实际: ${ex!!.message}",
                ex.message!!.contains("container.xml"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Given: container.xml 中 rootfile 元素缺失
     * When: 调用 unpack
     * Then: 抛出 IllegalArgumentException，消息包含 "rootfile"
     */
    @Test
    fun `should throw IllegalArgumentException when container has no rootfile`() {
        val badContainer = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
              </rootfiles>
            </container>
        """.trimIndent()
        val epub = createZipWithEntries(
            "META-INF/container.xml" to badContainer.toByteArray(),
        )
        val parser = EpubParser()
        val tempDir = newTempDir("no_rootfile")
        try {
            val ex = try {
                parser.unpack(epub.inputStream(), tempDir)
                null
            } catch (e: IllegalArgumentException) {
                e
            }
            assertNotNull("缺少 rootfile 应抛 IllegalArgumentException", ex)
            assertTrue(
                "异常消息应提及 rootfile，实际: ${ex!!.message}",
                ex.message!!.contains("rootfile"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Given: container.xml 中 rootfile 的 full-path 属性为空
     * When: 调用 unpack
     * Then: 抛出 IllegalArgumentException
     */
    @Test
    fun `should throw IllegalArgumentException when full-path is blank`() {
        val badContainer = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val epub = createZipWithEntries(
            "META-INF/container.xml" to badContainer.toByteArray(),
        )
        val parser = EpubParser()
        val tempDir = newTempDir("blank_fullpath")
        try {
            val threw = try {
                parser.unpack(epub.inputStream(), tempDir)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("空 full-path 应抛 IllegalArgumentException", threw)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Given: 完全空的 ZIP（没有任何条目）
     * When: 调用 unpack
     * Then: 抛出 IllegalArgumentException（因缺少 container.xml）
     */
    @Test
    fun `should throw when unpacking empty zip`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { /* 空 ZIP */ }
        val parser = EpubParser()
        val tempDir = newTempDir("empty_zip")
        try {
            val threw = try {
                parser.unpack(baos.toByteArray().inputStream(), tempDir)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("空 ZIP 应抛 IllegalArgumentException", threw)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Given: OPF 缺少 dc:title 和 dc:creator
     * When: 调用 unpack
     * Then: title/author 字段使用 "Unknown" 兜底，不抛异常
     */
    @Test
    fun `should fallback to Unknown when metadata title and creator missing`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata/>
              <manifest>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            "c1.xhtml" to "<html><body><h1>仅有正文标题</h1></body></html>".toByteArray(),
        )
        val parser = EpubParser()
        val tempDir = newTempDir("unknown_meta")
        try {
            val result = parser.unpack(epub.inputStream(), tempDir)
            assertEquals("Unknown", result.title)
            assertEquals("Unknown", result.author)
            // spine 仍正常构建
            assertEquals(1, result.metadata.spine.size)
            assertEquals("仅有正文标题", result.metadata.spine[0].title)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Given: OPF 中 manifest 引用一个 NCX 文件，但 ZIP 内不存在该文件（NCX 缺失）
     * When: 调用 unpack
     * Then: 不抛异常，tocItems 返回空列表
     */
    @Test
    fun `should return empty toc when NCX file declared but absent`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>缺少 NCX</dc:title>
                <dc:creator>作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/></spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "content.opf" to opf.toByteArray(),
            "c1.xhtml" to "<html><body><h1>章</h1></body></html>".toByteArray(),
            // 故意不包含 toc.ncx
        )
        val parser = EpubParser()
        val tempDir = newTempDir("ncx_missing")
        try {
            val result = parser.unpack(epub.inputStream(), tempDir)
            assertTrue("NCX 不存在时 tocItems 应为空", result.metadata.tocItems.isEmpty())
            assertEquals(1, result.metadata.spine.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== ZIP Slip 边界场景 ====================

    /**
     * Given: ZIP 条目名含 ../ 但实际仍在目标目录内（无效穿越）
     *        例如 "OEBPS/../OEBPS/c1.xhtml"，规范化后仍在 OEBPS/ 下
     * When: 调用 unpack
     * Then: 不应被误判为 ZIP Slip 攻击（不抛 SecurityException）
     *
     * 注意：此测试验证 ZIP Slip 检查不会误杀合法的相对路径。
     * 由于该路径下没有完整 EPUB 结构，预期抛 IllegalArgumentException（缺少 container.xml）。
     */
    @Test
    fun `should not flag dot-dot path that stays within target as zip slip`() {
        // "OEBPS/../OEBPS/c.xhtml" 规范化后 = "OEBPS/c.xhtml"，仍在目录内
        val epub = createZipWithEntries(
            "META-INF/container.xml" to """<?xml version="1.0"?><container/>""".toByteArray(),
            "OEBPS/../OEBPS/c.xhtml" to "<html/>".toByteArray(),
        )
        val parser = EpubParser()
        val tempDir = newTempDir("safe_dotdot")
        try {
            try {
                parser.unpack(epub.inputStream(), tempDir)
            } catch (e: SecurityException) {
                throw AssertionError("合法的 ../ 路径不应触发 SecurityException", e)
            } catch (e: IllegalArgumentException) {
                // 预期：container.xml 内容不完整，抛 IllegalArgumentException
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ==================== B3: normalizeNcxHref 处理 .. ====================

    /**
     * B3 回归测试：NCX 与 OPF 不在同一目录，src 含 `..`。
     *
     * Given: OPF 在 "OEBPS/content.opf"，NCX 在 "OEBPS/toc.ncx"，
     *        NCX 中的 src="../OEBPS/ch1.xhtml"（虽然罕见但符合 EPUB 规范）。
     *        normalizeNcxHref 必须将其规范化为相对于 opfDir 的 "ch1.xhtml"。
     * When: 调用 unpack
     * Then: tocItems 能成功匹配 spine，chapterIndex 不为 -1
     */
    @Test
    fun `should normalize NCX href containing dot-dot segments`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>dotdot 路径测试</dc:title>
                <dc:creator>作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="ch1"/>
              </spine>
            </package>
        """.trimIndent()

        // NCX 中的 src 含 "../"，规范化后应仍能匹配 ch1.xhtml
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="np1" playOrder="1">
                  <navLabel><text>第一章</text></navLabel>
                  <content src="../OEBPS/ch1.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val epub = createZipWithEntries(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/toc.ncx" to ncx.toByteArray(),
            "OEBPS/ch1.xhtml" to "<html><body><h1>章 1</h1></body></html>".toByteArray(),
        )

        val parser = EpubParser()
        val tempDir = newTempDir("ncx_dotdot")
        try {
            val result = parser.unpack(epub.inputStream(), tempDir)
            // 关键断言：tocItems 通过 normalizeNcxHref 处理 ../ 后能成功匹配 spine
            assertEquals(1, result.metadata.tocItems.size)
            val toc = result.metadata.tocItems[0]
            assertEquals("第一章", toc.title)
            assertEquals(
                "NCX href 含 ../ 时也应能匹配到 spine 索引（B3 修复点）",
                0, toc.chapterIndex,
            )
            // spine 的章节标题也应被 NCX 标题覆盖（hrefTitleMap 同样被规范化）
            assertEquals("第一章", result.metadata.spine[0].title)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

