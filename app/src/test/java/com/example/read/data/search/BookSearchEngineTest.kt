package com.example.read.data.search

import com.example.read.domain.repository.BookRepository
import com.example.read.util.BookMetadata
import com.example.read.util.SpineItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BookSearchEngine 跨章节全书搜索引擎单元测试（v5 feature: whole-book search）。
 *
 * 覆盖：
 * - 空 query 返回空集合
 * - 单章命中 / 跨章命中 / 多匹配计数
 * - 大小写不敏感
 * - 摘要（前后 30 字 + 省略号标记）
 * - 缺失章节文本（getChapterPlainText 返回 null）的跳过
 * - 元数据异常时返回空集合（不上抛）
 *
 * 不引入新依赖；使用现有的 MockK + kotlinx-coroutines-test。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchEngineTest {

    private fun metadataOf(vararg titles: String): BookMetadata = BookMetadata(
        title = "T", author = "A", opfDir = "OEBPS",
        spine = titles.mapIndexed { i, t ->
            SpineItem("ch$i", "ch$i.xhtml", t, "application/xhtml+xml")
        },
        tocItems = emptyList(),
    )

    @Test
    fun `should return empty when query is empty`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(any()) } returns metadataOf("Ch1")
        coEvery { repo.getChapterPlainText(any(), any()) } returns "hello world"
        val engine = BookSearchEngine(repo)

        val results = engine.search(bookId = 1L, query = "")
        assertEquals(0, results.size)
    }

    @Test
    fun `should find matches in single chapter and report count`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("Chapter 1")
        // "hello" 出现 3 次（含 hello + HELLO + Hello）
        coEvery { repo.getChapterPlainText(1L, 0) } returns
            "say hello to the world. HELLO again. Hello once more."
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "hello")
        assertEquals(1, results.size)
        val r = results[0]
        assertEquals(0, r.chapterIndex)
        assertEquals("Chapter 1", r.chapterTitle)
        assertEquals("应大小写不敏感地计数 3 处", 3, r.matchCount)
        assertNotNull(r.firstMatchSnippet)
        assertTrue("摘要应包含命中关键词", r.firstMatchSnippet.contains("hello", ignoreCase = true))
    }

    @Test
    fun `should aggregate matches across multiple chapters in spine order`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns
            metadataOf("Intro", "Body", "Epilogue")
        coEvery { repo.getChapterPlainText(1L, 0) } returns "alpha beta gamma"
        coEvery { repo.getChapterPlainText(1L, 1) } returns "nothing here"
        coEvery { repo.getChapterPlainText(1L, 2) } returns "alpha again and alpha again"
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "alpha")
        // chapter 0 命中 1 次 + chapter 2 命中 2 次；chapter 1 无命中应被剔除
        assertEquals(2, results.size)
        assertEquals(0, results[0].chapterIndex)
        assertEquals(1, results[0].matchCount)
        assertEquals(2, results[1].chapterIndex)
        assertEquals(2, results[1].matchCount)
    }

    @Test
    fun `should skip chapters whose plain text is null`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("Ch0", "Ch1")
        coEvery { repo.getChapterPlainText(1L, 0) } returns null
        coEvery { repo.getChapterPlainText(1L, 1) } returns "find me"
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "find")
        assertEquals(1, results.size)
        assertEquals(1, results[0].chapterIndex)
    }

    @Test
    fun `should return empty when metadata throws`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } throws IllegalStateException("no metadata")
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "anything")
        assertEquals(0, results.size)
    }

    @Test
    fun `should include ellipsis when snippet has context before or after`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("Ch0")
        // 长文本，确保 query 前后都有 >30 字符 → 摘要应带前后省略号
        val longText = "x".repeat(50) + " findme " + "y".repeat(50)
        coEvery { repo.getChapterPlainText(1L, 0) } returns longText
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "findme")
        assertEquals(1, results.size)
        val snippet = results[0].firstMatchSnippet
        assertTrue("摘要应以省略号开头标记上文有内容: $snippet", snippet.startsWith("…"))
        assertTrue("摘要应以省略号结尾标记下文有内容: $snippet", snippet.endsWith("…"))
    }

    @Test
    fun `should fallback to chapter index label when spine title is blank`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("")
        coEvery { repo.getChapterPlainText(1L, 0) } returns "match here"
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "match")
        assertEquals(1, results.size)
        assertEquals("第1章", results[0].chapterTitle)
    }

    // ==================== v5 补强：取消语义 / 边界 ====================

    /**
     * Given: getChapterPlainText 抛 [CancellationException]
     * When: engine.search 触发该章节扫描
     * Then: CancellationException 应被重抛（结构化并发约定），而不是被吞掉返回空集合
     *
     * 这是 FEAT_REPORT_v5.md § 1 协程取消修复在 BookSearchEngine 端的对称契约：
     * BookSearchEngine.searchOneChapter 内显式 `catch (CancellationException) → throw e`。
     */
    @Test
    fun `should rethrow CancellationException from getChapterPlainText`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("Ch0")
        coEvery { repo.getChapterPlainText(1L, 0) } throws CancellationException("cancelled")
        val engine = BookSearchEngine(repo)

        try {
            engine.search(1L, "anything")
            fail("CancellationException 应被重抛，而非被引擎吞掉返回空集合")
        } catch (e: CancellationException) {
            // 期望：CancellationException 被重抛
            assertTrue(true)
        }
    }

    /**
     * Given: getBookMetadata 抛 [CancellationException]
     * When: engine.search
     * Then: 应被重抛（不应走 `catch (Exception) → return emptyList()` 路径）
     */
    @Test
    fun `should rethrow CancellationException from getBookMetadata`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } throws CancellationException("cancelled at metadata")
        val engine = BookSearchEngine(repo)

        try {
            engine.search(1L, "anything")
            fail("元数据阶段的 CancellationException 应被重抛")
        } catch (e: CancellationException) {
            assertTrue(true)
        }
    }

    /**
     * Given: spine 为空（无章节可扫描）
     * When: engine.search
     * Then: 返回空集合（不应抛异常）
     */
    @Test
    fun `should return empty when spine is empty`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns BookMetadata(
            title = "T", author = "A", opfDir = "OEBPS",
            spine = emptyList(), tocItems = emptyList(),
        )
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "any")
        assertEquals(0, results.size)
    }

    /**
     * Given: 章节文本为空字符串
     * When: engine.search
     * Then: 该章节应被跳过（不出现在结果集中）
     */
    @Test
    fun `should skip chapter when plain text is empty string`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("Ch0", "Ch1")
        coEvery { repo.getChapterPlainText(1L, 0) } returns ""
        coEvery { repo.getChapterPlainText(1L, 1) } returns "found here"
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "found")
        assertEquals(1, results.size)
        assertEquals(1, results[0].chapterIndex)
    }

    /**
     * Given: 单章异常（非 CancellationException）
     * When: engine.search
     * Then: 单章失败不应中断整本书搜索，其他章节仍能正常命中
     */
    @Test
    fun `should continue search when one chapter throws non-cancellation exception`() = runTest {
        val repo = mockk<BookRepository>(relaxed = true)
        coEvery { repo.getBookMetadata(1L) } returns metadataOf("Ch0", "Ch1", "Ch2")
        coEvery { repo.getChapterPlainText(1L, 0) } returns "alpha"
        coEvery { repo.getChapterPlainText(1L, 1) } throws RuntimeException("IO fail")
        coEvery { repo.getChapterPlainText(1L, 2) } returns "alpha again"
        val engine = BookSearchEngine(repo)

        val results = engine.search(1L, "alpha")
        // 章 1 异常被吞掉，章 0 + 章 2 仍正常命中
        assertEquals(2, results.size)
        assertEquals(0, results[0].chapterIndex)
        assertEquals(2, results[1].chapterIndex)
    }
}
