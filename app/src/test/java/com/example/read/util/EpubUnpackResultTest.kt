package com.example.read.util

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EpubUnpackResult / BookMetadata / SpineItem / TocItem 的纯数据模型测试。
 *
 * 测试范围：
 * - 数据类字段访问、默认值（BookMetadata.tocItems 默认空列表）
 * - kotlinx.serialization 的序列化/反序列化往返
 * - 包含中文、特殊字符、Unicode 的字段
 * - tocItems 字段在 v1 metadata.json（不含该字段）中的向后兼容
 *
 * 这些模型是 EPUB 解包结果与 metadata.json 缓存之间的桥梁，
 * 必须保证序列化兼容性，否则升级后旧书无法打开。
 */
class EpubUnpackResultTest {

    /** 公用 JSON 实例：忽略未知字段，与生产代码 BookRepositoryImpl.readMetadata 保持一致 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ==================== BookMetadata 默认值 ====================

    /**
     * Given: 仅提供必填字段构造 BookMetadata
     * When: 不传 tocItems 参数
     * Then: tocItems 应默认为空列表（向后兼容旧版 metadata.json）
     */
    @Test
    fun `should use empty list as default tocItems when not provided`() {
        val metadata = BookMetadata(
            title = "默认值测试",
            author = "作者",
            opfDir = "OEBPS",
            spine = emptyList(),
        )
        assertTrue("tocItems 默认应为空列表", metadata.tocItems.isEmpty())
    }

    /**
     * Given: 标准 BookMetadata
     * When: 序列化为 JSON 再反序列化
     * Then: 所有字段（含 tocItems）应保持一致
     */
    @Test
    fun `should preserve all fields when round trip serialization with tocItems`() {
        val original = BookMetadata(
            title = "往返测试",
            author = "测试作者",
            opfDir = "OEBPS",
            spine = listOf(
                SpineItem(id = "ch1", href = "ch1.xhtml", title = "第一章", mediaType = "application/xhtml+xml"),
                SpineItem(id = "ch2", href = "ch2.xhtml", title = "第二章", mediaType = "application/xhtml+xml"),
            ),
            tocItems = listOf(
                TocItem(title = "第一章", chapterIndex = 0, level = 0),
                TocItem(title = "1.1 小节", chapterIndex = 1, level = 1),
            ),
        )

        val jsonText = json.encodeToString(BookMetadata.serializer(), original)
        val restored = json.decodeFromString(BookMetadata.serializer(), jsonText)

        assertEquals(original.title, restored.title)
        assertEquals(original.author, restored.author)
        assertEquals(original.opfDir, restored.opfDir)
        assertEquals(original.spine.size, restored.spine.size)
        assertEquals(original.tocItems.size, restored.tocItems.size)
        assertEquals("第一章", restored.spine[0].title)
        assertEquals("application/xhtml+xml", restored.spine[0].mediaType)
        assertEquals(1, restored.tocItems[1].level)
    }

    /**
     * Given: 一段不含 tocItems 字段的 metadata.json（模拟 v1 旧版本）
     * When: 使用当前模型反序列化
     * Then: 不应抛异常，tocItems 默认为空列表
     */
    @Test
    fun `should tolerate legacy metadata json without tocItems field`() {
        val legacyJson = """
            {
              "title": "旧版书",
              "author": "旧作者",
              "opfDir": "OEBPS",
              "spine": [
                {"id":"ch1","href":"ch1.xhtml","title":"章节","mediaType":"application/xhtml+xml"}
              ]
            }
        """.trimIndent()

        val restored = json.decodeFromString(BookMetadata.serializer(), legacyJson)
        assertEquals("旧版书", restored.title)
        assertEquals(1, restored.spine.size)
        assertTrue("旧版 JSON 中无 tocItems 字段，应默认为空", restored.tocItems.isEmpty())
    }

    /**
     * Given: 含 Unicode 与特殊字符的 BookMetadata
     * When: 序列化再反序列化
     * Then: 字段保持完整
     */
    @Test
    fun `should preserve unicode characters when serializing`() {
        val original = BookMetadata(
            title = "《三体》— 刘慈欣",
            author = "刘慈欣 (Liu Cixin)",
            opfDir = "OPS",
            spine = listOf(SpineItem("a", "a.xhtml", "序章\n（特殊换行）", "application/xhtml+xml")),
            tocItems = listOf(TocItem("第一章「黑暗森林」", 0, 0)),
        )

        val jsonText = json.encodeToString(BookMetadata.serializer(), original)
        val restored = json.decodeFromString(BookMetadata.serializer(), jsonText)

        assertEquals(original.title, restored.title)
        assertEquals(original.author, restored.author)
        assertEquals(original.spine[0].title, restored.spine[0].title)
        assertEquals(original.tocItems[0].title, restored.tocItems[0].title)
    }

    // ==================== SpineItem / TocItem 字段 ====================

    /**
     * Given: 单独构造 SpineItem
     * When: 检查字段访问
     * Then: 所有字段被正确赋值
     */
    @Test
    fun `should expose all fields correctly for SpineItem`() {
        val item = SpineItem(id = "ch5", href = "x/y/ch5.xhtml", title = "第五章", mediaType = "application/xhtml+xml")
        assertEquals("ch5", item.id)
        assertEquals("x/y/ch5.xhtml", item.href)
        assertEquals("第五章", item.title)
        assertEquals("application/xhtml+xml", item.mediaType)
    }

    /**
     * Given: 单独构造 TocItem
     * When: 检查字段访问
     * Then: 所有字段被正确赋值
     */
    @Test
    fun `should expose all fields correctly for TocItem`() {
        val item = TocItem(title = "第一节", chapterIndex = 3, level = 2)
        assertEquals("第一节", item.title)
        assertEquals(3, item.chapterIndex)
        assertEquals(2, item.level)
    }

    // ==================== EpubUnpackResult ====================

    /**
     * Given: 构造 EpubUnpackResult（封面为 null）
     * When: 检查字段
     * Then: coverBytes 可为 null，其余字段正确
     */
    @Test
    fun `should allow null cover bytes in EpubUnpackResult`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "unpack_result_test")
        val metadata = BookMetadata("书名", "作者", "OEBPS", emptyList())
        val result = EpubUnpackResult(
            bookDir = tempDir,
            title = "书名",
            author = "作者",
            coverBytes = null,
            metadata = metadata,
        )
        assertNotNull(result.bookDir)
        assertEquals("书名", result.title)
        assertEquals("作者", result.author)
        assertEquals(null, result.coverBytes)
        assertEquals(metadata, result.metadata)
    }
}
