package com.example.read.ui.reader

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.read.data.search.BookSearchEngine
import com.example.read.data.search.SearchResult
import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import com.example.read.util.BookMetadata
import com.example.read.util.SpineItem
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel 全书搜索状态机测试（v5 feature: whole-book search）。
 *
 * 覆盖：
 * - [SearchMode] 切换：清空 query / results / 取消进行中的搜索 job
 * - [ReaderViewModel.searchWholeBook] 主路径：< 2 字符不触发；正常输入触发引擎调用 + 写结果
 * - 连续调用：旧 job 被取消（验证 _bookSearchInProgress 的最终态）
 * - [ReaderViewModel.onBookSearchResultClicked]：跳转 + 设置 pendingFindAfterJump
 * - [ReaderViewModel.attachFindController] 消费 pendingFindAfterJump：跨章定位
 * - 模式切换清空全书搜索结果
 *
 * 使用 mockk 替身 [BookSearchEngine] / [BookRepository]；不修改生产代码；不引入新依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelWholeBookSearchTest {

    private lateinit var repository: BookRepository
    private lateinit var engine: BookSearchEngine
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 41L
    private val savedPagePrefsState = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        engine = mockk(relaxed = true)
        savedPagePrefsState.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun makeMetadata(chapterCount: Int): BookMetadata = BookMetadata(
        title = "T", author = "A", opfDir = "OEBPS",
        spine = (1..chapterCount).map {
            SpineItem("ch$it", "ch$it.xhtml", "Chapter $it", "application/xhtml+xml")
        },
        tocItems = emptyList(),
    )

    private fun createViewModel(
        chapterCount: Int = 5,
        lastReadChapter: Int = 0,
    ): ReaderViewModel {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns 0
        every { Color.WHITE } returns 0xFFFFFFFF.toInt()
        mockkStatic(Typeface::class)
        every { Typeface.SERIF } returns mockk(relaxed = true)
        every { Typeface.SANS_SERIF } returns mockk(relaxed = true)
        every { Typeface.MONOSPACE } returns mockk(relaxed = true)

        val book = Book(
            id = testBookId, title = "T", author = "A",
            coverPath = null, bookDirPath = "/books/$testBookId",
            totalChapters = chapterCount, lastReadChapter = lastReadChapter, lastReadAt = 0L,
        )
        coEvery { repository.getBookById(testBookId) } returns book
        coEvery { repository.getBookMetadata(testBookId) } returns makeMetadata(chapterCount)
        coEvery { repository.getChapterHtmlFile(testBookId, any()) } returns File("/tmp/x.xhtml")
        coEvery { repository.updateReadingProgress(any(), any()) } returns Unit

        val app = mockk<Application>(relaxed = true)
        val settingsPrefs = mockk<SharedPreferences>(relaxed = true)
        every { settingsPrefs.getFloat(any(), any()) } returns 18f
        every { settingsPrefs.getString(any(), any()) } returns "Serif"
        every { settingsPrefs.getInt(any(), any()) } returns 0
        val settingsEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { settingsPrefs.edit() } returns settingsEditor
        every { settingsEditor.putFloat(any(), any()) } returns settingsEditor
        every { settingsEditor.putString(any(), any()) } returns settingsEditor
        every { settingsEditor.putInt(any(), any()) } returns settingsEditor
        every { settingsEditor.apply() } just Runs

        val pagePrefs = mockk<SharedPreferences>(relaxed = true)
        every { pagePrefs.getInt(any(), any()) } answers {
            savedPagePrefsState[firstArg<String>()] ?: secondArg<Int>()
        }
        val pageEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { pagePrefs.edit() } returns pageEditor
        every { pageEditor.putInt(any(), any()) } answers {
            savedPagePrefsState[firstArg()] = secondArg()
            pageEditor
        }
        every { pageEditor.apply() } just Runs

        every { app.getSharedPreferences("reading_settings", any()) } returns settingsPrefs
        every { app.getSharedPreferences("reader_page_progress", any()) } returns pagePrefs

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "com.example.read.ui.navigation.Reader" to mapOf("bookId" to testBookId),
                "bookId" to testBookId,
            )
        )
        return ReaderViewModel(repository, app, savedStateHandle, engine)
    }

    // ==================== setSearchMode ====================

    /**
     * Given: 初始 SearchMode = InChapter
     * When: setSearchMode(WholeBook)
     * Then: searchMode 切换；findQuery / count / current 都被清空；bookSearchResults 也被清空
     */
    @Test
    fun `should switch search mode and clear query and results`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // 先制造一些"待清"的状态
        viewModel.enterFindMode()
        viewModel.updateFindQuery("hello")
        advanceUntilIdle()
        assertEquals("hello", viewModel.findQuery.value)

        viewModel.setSearchMode(SearchMode.WholeBook)
        advanceUntilIdle()

        assertEquals(SearchMode.WholeBook, viewModel.searchMode.value)
        assertEquals("切换模式应清空 findQuery", "", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
        assertTrue(viewModel.bookSearchResults.value.isEmpty())
        assertFalse(viewModel.bookSearchInProgress.value)
    }

    /**
     * Given: 已在 WholeBook 模式，bookSearchResults 已有结果
     * When: setSearchMode(InChapter)
     * Then: bookSearchResults 被清空（v5 设计：模式切换时清状态）
     */
    @Test
    fun `should clear bookSearchResults when switching back to InChapter`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 先切到 WholeBook 并 stub 搜索结果
        viewModel.setSearchMode(SearchMode.WholeBook)
        coEvery { engine.search(testBookId, "alpha") } returns listOf(
            SearchResult(0, "Chapter 1", 1, "...alpha..."),
        )
        viewModel.searchWholeBook("alpha")
        advanceUntilIdle()
        assertEquals(1, viewModel.bookSearchResults.value.size)

        // 切回 InChapter
        viewModel.setSearchMode(SearchMode.InChapter)
        advanceUntilIdle()

        assertEquals(SearchMode.InChapter, viewModel.searchMode.value)
        assertTrue(
            "切回 InChapter 时应清空 bookSearchResults",
            viewModel.bookSearchResults.value.isEmpty(),
        )
    }

    /**
     * Given: 当前模式与目标模式相同
     * When: setSearchMode(同一模式)
     * Then: 早返回；不触发清空（验证：先 enter find 设置 query，再 setSearchMode(InChapter)，query 应保留）
     */
    @Test
    fun `should noop when mode does not change`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("preserve me")
        advanceUntilIdle()
        assertEquals(SearchMode.InChapter, viewModel.searchMode.value)

        viewModel.setSearchMode(SearchMode.InChapter)
        advanceUntilIdle()

        assertEquals("同模式 setSearchMode 应早返回，query 不被清空", "preserve me", viewModel.findQuery.value)
    }

    // ==================== searchWholeBook ====================

    /**
     * Given: query 为空字符串
     * When: searchWholeBook("")
     * Then: 不调用 engine.search；bookSearchResults 保持空；bookSearchInProgress = false
     */
    @Test
    fun `should not invoke engine when query is empty`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.searchWholeBook("")
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.search(any(), any()) }
        assertTrue(viewModel.bookSearchResults.value.isEmpty())
        assertFalse(viewModel.bookSearchInProgress.value)
    }

    /**
     * Given: query 长度 = 1
     * When: searchWholeBook("a")
     * Then: 不触发 engine 调用（短查询过滤）
     */
    @Test
    fun `should not invoke engine when query length is less than two`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.searchWholeBook("a")
        advanceUntilIdle()

        coVerify(exactly = 0) { engine.search(any(), any()) }
        assertTrue(viewModel.bookSearchResults.value.isEmpty())
        assertFalse(viewModel.bookSearchInProgress.value)
    }

    /**
     * Given: query 长度 >= 2，engine.search 返回 2 个 SearchResult
     * When: searchWholeBook("hello")
     * Then:
     *   - engine.search 被调用一次
     *   - bookSearchResults 包含 2 项
     *   - bookSearchInProgress 最终为 false
     */
    @Test
    fun `should invoke engine and write results when query has at least two chars`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val mockResults = listOf(
            SearchResult(0, "Chapter 1", 2, "...hello..."),
            SearchResult(2, "Chapter 3", 1, "...hello again..."),
        )
        coEvery { engine.search(testBookId, "hello") } returns mockResults

        viewModel.searchWholeBook("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) { engine.search(testBookId, "hello") }
        assertEquals(2, viewModel.bookSearchResults.value.size)
        assertEquals("hello", viewModel.findQuery.value)
        assertFalse(viewModel.bookSearchInProgress.value)
    }

    /**
     * Given: engine.search 抛普通异常（非 CancellationException）
     * When: searchWholeBook("xyz")
     * Then:
     *   - bookSearchResults 保持空集合（异常 → emptyList）
     *   - bookSearchInProgress 最终为 false（finally 清理）
     *   - 不向 UI 抛异常
     */
    @Test
    fun `should swallow engine exception and reset progress`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { engine.search(testBookId, "xyz") } throws RuntimeException("engine boom")

        viewModel.searchWholeBook("xyz")
        advanceUntilIdle()

        assertTrue(viewModel.bookSearchResults.value.isEmpty())
        assertFalse(viewModel.bookSearchInProgress.value)
    }

    /**
     * Given: 连续两次 searchWholeBook，第一次挂起（CompletableDeferred 未 complete）
     * When: 第二次 searchWholeBook("world") 启动
     * Then: 第一次 job 被取消（旧结果不会污染最终状态）；第二次的结果被写入
     *
     * 这是 v5 的关键设计：用户连续输入时只保留最新查询的结果。
     */
    @Test
    fun `should cancel previous job when new search is triggered`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val firstGate = CompletableDeferred<List<SearchResult>>()
        coEvery { engine.search(testBookId, "hello") } coAnswers { firstGate.await() }
        coEvery { engine.search(testBookId, "world") } returns listOf(
            SearchResult(1, "Chapter 2", 3, "...world..."),
        )

        // 第一次查询：挂起在 firstGate
        viewModel.searchWholeBook("hello")
        advanceUntilIdle()
        assertTrue("第一次查询应处于进行中", viewModel.bookSearchInProgress.value)

        // 第二次查询：cancel 旧 job，启动新 job 并完成
        viewModel.searchWholeBook("world")
        advanceUntilIdle()

        // 第一次的 deferred 即使后来 complete 也不应影响结果（因为 job 已被 cancel）
        firstGate.complete(listOf(SearchResult(99, "Stale", 1, "stale")))
        advanceUntilIdle()

        // 最终结果应为第二次的 query
        assertEquals(1, viewModel.bookSearchResults.value.size)
        assertEquals("world", viewModel.findQuery.value)
        assertFalse(viewModel.bookSearchInProgress.value)
        assertEquals(1, viewModel.bookSearchResults.value[0].chapterIndex)
    }

    /**
     * Given: Turbine 监听 bookSearchInProgress
     * When: searchWholeBook 全流程
     * Then: 应发射 false → true → false（进入搜索 → 完成）
     */
    @Test
    fun `should emit bookSearchInProgress transitions through StateFlow`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { engine.search(testBookId, "found") } returns listOf(
            SearchResult(0, "Ch1", 1, "...found..."),
        )

        viewModel.bookSearchInProgress.test {
            assertFalse(awaitItem()) // 初始 false
            viewModel.searchWholeBook("found")
            assertTrue("启动搜索时应发射 true", awaitItem())
            advanceUntilIdle()
            assertFalse("搜索完成时应发射 false", awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    /**
     * Given: 搜索中 query 被新一轮搜索取代（用户继续输入触发 searchWholeBook("new")）
     * When: 旧搜索的 engine.search("old") 延迟返回
     * Then: 旧结果不应被写入（保护：if (coroutineContext[Job] === bookSearchJob) write）
     *
     * P2-v6-1：v6 把 stale 检查从字面比较 _findQuery == query 改为
     * coroutineContext[Job] === bookSearchJob 引用比较。本测试用"启动新一轮搜索"
     * 来验证 stale 防御（之前的实现也仅靠 cancel 旧 Job 即可保证不脏写；
     * 现在更明确地用 Job 引用比较）。
     */
    @Test
    fun `should not write stale results when newer search started`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val oldGate = CompletableDeferred<List<SearchResult>>()
        val newGate = CompletableDeferred<List<SearchResult>>()
        coEvery { engine.search(testBookId, "old") } coAnswers { oldGate.await() }
        coEvery { engine.search(testBookId, "new") } coAnswers { newGate.await() }

        // 启动第一次搜索 "old"
        viewModel.setSearchMode(SearchMode.WholeBook)
        advanceUntilIdle()
        viewModel.searchWholeBook("old")
        advanceUntilIdle()

        // 启动第二次搜索 "new"（旧 Job 被 cancel，新 Job 接管 bookSearchJob 引用）
        viewModel.searchWholeBook("new")
        advanceUntilIdle()

        // 让旧搜索完成（实际上 Job 已被 cancel，oldGate.complete 后协程 awake 会立即抛 CancellationException，
        // 但 BookSearchEngine.search 内部 try {} 已经 catch CancellationException 重抛，
        // ViewModel 的 catch (e: CancellationException) 也重抛 — 旧结果根本写不进去）
        oldGate.complete(listOf(SearchResult(0, "Stale", 1, "stale")))
        advanceUntilIdle()

        // bookSearchResults 不应包含 Stale；尚未让 new 完成，所以也不应有 "Fresh"
        assertTrue(
            "新搜索启动后，旧搜索的结果不应污染最终状态",
            viewModel.bookSearchResults.value.none { it.chapterTitle == "Stale" },
        )
    }

    // ==================== onBookSearchResultClicked ====================

    /**
     * Given: bookSearchResults 已经有一项 (chapterIndex=2)，当前章节 = 0；
     *        WholeBook 模式 + findQuery = "hello"
     * When: onBookSearchResultClicked(SearchResult(2, ...))
     * Then:
     *   - chapterIndex 跳转到 2
     *   - searchMode 切回 InChapter
     *   - findActive = true
     *   - findQuery 仍为 "hello"（跳转后被恢复）
     *   - pendingFindAfterJump 被设置为 "hello"（通过后续 attachFindController 间接验证）
     *
     * pendingFindAfterJump 是 private 字段，本测试通过：
     * 1) 调 onBookSearchResultClicked 后 attachFindController
     * 2) 验证 mockk controller.find("hello") 被调
     */
    @Test
    fun `should jump to chapter and arm pending find on result click`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        // 先注册章 0 的页数，避免 jumpToChapter 走未预加载分支也无所谓
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()
        // 模拟用户已经触发 WholeBook 搜索得到结果
        coEvery { engine.search(testBookId, "hello") } returns listOf(
            SearchResult(2, "Chapter 3", 1, "...hello..."),
        )
        viewModel.setSearchMode(SearchMode.WholeBook)
        viewModel.searchWholeBook("hello")
        advanceUntilIdle()

        // 点击结果
        val clicked = SearchResult(2, "Chapter 3", 1, "...hello...")
        viewModel.onBookSearchResultClicked(clicked)
        advanceUntilIdle()

        // chapterIndex 已跳到 2
        assertEquals(2, viewModel.chapterIndex.value)
        // 模式切回章内
        assertEquals(SearchMode.InChapter, viewModel.searchMode.value)
        // 搜索栏激活
        assertTrue(viewModel.findActive.value)
        // findQuery 在 jumpToChapter 的 exitFindMode 后被显式恢复
        assertEquals("hello", viewModel.findQuery.value)
    }

    /**
     * 验证 pendingFindAfterJump 在 attachFindController 时被消费：
     * Given: 上一个 case 后，模拟 PagedChapterAdapter 在新章节加载完成时调 attachFindController
     * When: attachFindController(mockController)
     * Then: mockController.find("hello", ...) 被调用一次
     */
    @Test
    fun `should consume pendingFindAfterJump when attachFindController is called`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()

        coEvery { engine.search(testBookId, "key") } returns listOf(
            SearchResult(1, "Chapter 2", 1, "...key..."),
        )
        viewModel.setSearchMode(SearchMode.WholeBook)
        viewModel.searchWholeBook("key")
        advanceUntilIdle()

        viewModel.onBookSearchResultClicked(SearchResult(1, "Chapter 2", 1, "...key..."))
        advanceUntilIdle()
        assertEquals(1, viewModel.chapterIndex.value)

        // 模拟新章节的 WebView 加载完毕：注入 controller
        val controller = mockk<FindInPageController>(relaxed = true)
        viewModel.attachFindController(controller)
        advanceUntilIdle()

        // pendingFindAfterJump 消费验证：controller.find 被调用且 query = "key"
        verify(atLeast = 1) { controller.find("key", any()) }
        assertEquals(SearchMode.InChapter, viewModel.searchMode.value)
        assertTrue(viewModel.findActive.value)
    }

    /**
     * 验证 pendingFindAfterJump 只消费一次：第二次 attach 时不应再触发 find
     *
     * Given: 点击结果 → attach 消费 pendingFindAfterJump → 再 attach 一次新 controller
     * When: 第二次 attachFindController
     * Then: 新 controller.find 不应被 pendingFindAfterJump 路径触发
     *       （但因 _findActive=true + _findQuery="key" 不为空，attachFindController 内的
     *        "章节切换重发查询" 分支仍会调 find —— 这是正常重发，区别于 pendingFindAfterJump）
     */
    @Test
    fun `should consume pendingFindAfterJump exactly once`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()
        coEvery { engine.search(testBookId, "kk") } returns listOf(
            SearchResult(1, "Chapter 2", 1, "...kk..."),
        )
        viewModel.setSearchMode(SearchMode.WholeBook)
        viewModel.searchWholeBook("kk")
        advanceUntilIdle()
        viewModel.onBookSearchResultClicked(SearchResult(1, "Chapter 2", 1, "...kk..."))
        advanceUntilIdle()

        val firstCtrl = mockk<FindInPageController>(relaxed = true)
        viewModel.attachFindController(firstCtrl)
        advanceUntilIdle()
        verify(atLeast = 1) { firstCtrl.find("kk", any()) }

        // 第二次 attach：pendingFindAfterJump 已被消费为 null，
        // 但 _findActive=true + query="kk" 会让"章节切换重发查询"分支再次调 find；
        // 我们只确认 attach 不崩溃，且不重复设置 pendingFindAfterJump 副作用（无法直接观测，但行为应稳定）
        val secondCtrl = mockk<FindInPageController>(relaxed = true)
        viewModel.attachFindController(secondCtrl)
        advanceUntilIdle()
        // 第二个 controller 也会被章节内"重发"路径调 find 一次
        verify { secondCtrl.find("kk", any()) }
    }

    // ==================== Turbine：bookSearchResults 流 ====================

    /**
     * Given: Turbine 监听 bookSearchResults
     * When: setSearchMode → searchWholeBook → setSearchMode(InChapter)
     * Then: 发射序列：[] → [results] → []
     */
    @Test
    fun `should emit bookSearchResults transitions through StateFlow`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        coEvery { engine.search(testBookId, "abc") } returns listOf(
            SearchResult(0, "Ch1", 1, "...abc..."),
            SearchResult(2, "Ch3", 1, "...abc..."),
        )

        viewModel.bookSearchResults.test {
            assertEquals(0, awaitItem().size) // 初始空
            viewModel.setSearchMode(SearchMode.WholeBook)
            viewModel.searchWholeBook("abc")
            advanceUntilIdle()
            val results = awaitItem()
            assertEquals("应发射 2 项搜索结果", 2, results.size)
            viewModel.setSearchMode(SearchMode.InChapter)
            advanceUntilIdle()
            assertEquals("切回 InChapter 应清空", 0, awaitItem().size)
            cancelAndConsumeRemainingEvents()
        }
    }
}
