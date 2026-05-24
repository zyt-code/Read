package com.example.read.ui.reader

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import com.example.read.util.BookMetadata
import com.example.read.util.SpineItem
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel 章内搜索状态机回归测试（v4 feature: find-in-page）。
 *
 * 覆盖：
 * - enterFindMode：只切换 findActive 为 true，不重置 query / count
 * - exitFindMode：所有 4 个 StateFlow 归零（findActive=false, findQuery="", findCount=0, findCurrent=-1）
 * - updateFindQuery：query 字段同步更新；controller==null 时不崩溃；空串走 clear 路径
 * - 自动退出搜索模式：jumpToChapter 未预加载分支 / updateSettings 内部调用 exitFindMode
 *
 * Turbine 验证 StateFlow 发射序列。不修改生产代码；不引入新依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelFindTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 21L
    private val savedPagePrefsState = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
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

    private fun createViewModel(chapterCount: Int = 5, lastReadChapter: Int = 0): ReaderViewModel {
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
        // v5：ReaderViewModel 新增 BookSearchEngine 注入参数；测试用 relaxed mock 兜底，
        // 章内搜索路径不会调到 engine，全书搜索路径若被测试覆盖会自行 stub 行为。
        val engine = mockk<com.example.read.data.search.BookSearchEngine>(relaxed = true)
        return ReaderViewModel(repository, app, savedStateHandle, engine)
    }

    // ==================== enterFindMode ====================

    /**
     * Given: 初始 findActive = false
     * When: 调 enterFindMode()
     * Then: findActive 切换为 true；其它 find* StateFlow 保持初始值
     */
    @Test
    fun `should set findActive to true on enterFindMode`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // 初始
        assertFalse(viewModel.findActive.value)
        assertEquals("", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)

        viewModel.enterFindMode()
        advanceUntilIdle()

        assertTrue("enterFindMode 应切换 findActive=true", viewModel.findActive.value)
        // enterFindMode 不应重置 query / count / current（FEAT_REPORT_v4.md 设计要点）
        assertEquals("", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
    }

    // ==================== updateFindQuery ====================

    /**
     * Given: findActive=true，controller 未注入（attachFindController 未被调用）
     * When: updateFindQuery("test")
     * Then: findQuery 同步更新；count=0, current=-1（controller==null 分支）；
     *       不应崩溃（无 NPE）
     */
    @Test
    fun `should update findQuery and not crash when controller is null`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("test")
        advanceUntilIdle()

        assertEquals("test", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
    }

    /**
     * Given: 之前有非空 query
     * When: 再次 updateFindQuery("")
     * Then: findQuery 被设为空串；count=0, current=-1
     *
     * 这是 ReaderScreen 关闭搜索栏 / 用户清空输入框的关键路径。
     */
    @Test
    fun `should clear count and current when query becomes empty`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("hello")
        advanceUntilIdle()
        assertEquals("hello", viewModel.findQuery.value)

        viewModel.updateFindQuery("")
        advanceUntilIdle()

        assertEquals("", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
    }

    /**
     * Given: Turbine 监听 findQuery
     * When: 连续 updateFindQuery 多次
     * Then: 每次都应发射对应的字符串值
     */
    @Test
    fun `should emit each query update through StateFlow`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.findQuery.test {
            assertEquals("", awaitItem())
            viewModel.enterFindMode()
            viewModel.updateFindQuery("ab")
            assertEquals("ab", awaitItem())
            viewModel.updateFindQuery("abc")
            assertEquals("abc", awaitItem())
            viewModel.updateFindQuery("")
            assertEquals("", awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    // ==================== exitFindMode ====================

    /**
     * Given: 进入搜索模式并设置 query
     * When: 调 exitFindMode()
     * Then: 四个 find* StateFlow 全部归零
     *       - findActive = false
     *       - findQuery = ""
     *       - findCount = 0
     *       - findCurrent = -1
     */
    @Test
    fun `should reset all find state on exitFindMode`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("foo")
        advanceUntilIdle()
        assertTrue(viewModel.findActive.value)
        assertEquals("foo", viewModel.findQuery.value)

        viewModel.exitFindMode()
        advanceUntilIdle()

        assertFalse(viewModel.findActive.value)
        assertEquals("", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
    }

    // ==================== 自动退出：jumpToChapter ====================

    /**
     * Given: 已进入搜索模式 (findActive=true, findQuery="foo")，章节 1 未预加载
     * When: 调 jumpToChapter(1)（走未预加载分支，内部调用 exitFindMode）
     * Then: findActive 被设为 false（v4 设计：跨章节自动退出搜索）
     *
     * 同步验证 P1-NEW-2 修复后 jumpToChapter 未预加载分支仍保留了 exitFindMode 调用。
     */
    @Test
    fun `should auto exit find mode when jumpToChapter to unloaded chapter`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        // 仅注册章 0
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("foo")
        advanceUntilIdle()
        assertTrue(viewModel.findActive.value)

        viewModel.jumpToChapter(1) // 未预加载 → 走未预加载分支 → 调用 exitFindMode
        advanceUntilIdle()

        assertFalse("跨章节应自动退出搜索模式", viewModel.findActive.value)
        assertEquals("", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
    }

    /**
     * Given: 已进入搜索模式，章 0/1/2 都已预加载
     * When: 调 jumpToChapter(2)（走已预加载分支 → jumpToGlobalPage → syncChapterState）
     * Then: 同样应触发 exitFindMode（syncChapterState 也内置 exitFindMode）
     *
     * 这两条路径都需要清理搜索状态，避免高亮残留在错误的章节。
     */
    @Test
    fun `should auto exit find mode when jumpToChapter to preloaded chapter via syncChapterState`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("bar")
        advanceUntilIdle()
        assertTrue(viewModel.findActive.value)

        // 章 2 已预加载，jumpToChapter 走 jumpToGlobalPage → syncChapterState
        viewModel.jumpToChapter(2)
        advanceUntilIdle()

        assertFalse(
            "已预加载路径 syncChapterState 也应触发 exitFindMode",
            viewModel.findActive.value,
        )
    }

    // ==================== 自动退出：updateSettings ====================

    /**
     * Given: 已进入搜索模式
     * When: 调 updateSettings(新设置) —— WebView 会被重建，旧 controller 失效
     * Then: 搜索模式应自动退出（FEAT_REPORT_v4.md 设计：updateSettings 内部调 exitFindMode）
     */
    @Test
    fun `should auto exit find mode when updateSettings is called`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("query")
        advanceUntilIdle()
        assertTrue(viewModel.findActive.value)
        assertEquals("query", viewModel.findQuery.value)

        viewModel.updateSettings(ReadingSettings(fontSize = 22f))
        advanceUntilIdle()

        assertFalse("设置变更应自动退出搜索模式", viewModel.findActive.value)
        assertEquals("", viewModel.findQuery.value)
        assertEquals(0, viewModel.findCount.value)
        assertEquals(-1, viewModel.findCurrent.value)
    }

    // ==================== Turbine 综合 ====================

    /**
     * Given: Turbine 同时监听 findActive
     * When: 完整的 enter / update / exit 循环
     * Then: findActive 序列：false → true → false
     */
    @Test
    fun `should emit findActive transitions through StateFlow`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.findActive.test {
            assertFalse(awaitItem()) // 初始 false
            viewModel.enterFindMode()
            assertTrue(awaitItem())  // enterFindMode 切到 true
            viewModel.exitFindMode()
            assertFalse(awaitItem()) // exitFindMode 切回 false
            cancelAndConsumeRemainingEvents()
        }
    }

    /**
     * Given: Turbine 监听 findCount
     * When: 无 controller 时 updateFindQuery("anything")
     * Then: findCount 应保持 0（controller==null 分支会主动设 0）；
     *       从初始 0 → 仍然 0，可能不会发射新事件（StateFlow 只在值改变时发射）
     */
    @Test
    fun `should not emit duplicate findCount values when controller is null`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.findCount.test {
            assertEquals(0, awaitItem())
            viewModel.enterFindMode()
            viewModel.updateFindQuery("x")
            advanceUntilIdle()
            // StateFlow 不发射重复值，0 → 0 期望无新发射
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
    }

    /**
     * Given: 进入搜索模式但 controller 始终为 null
     * When: 调 findNext / findPrev（应静默 no-op）
     * Then: 不抛异常；findCurrent 保持 -1
     */
    @Test
    fun `should silently no-op for findNext and findPrev when controller is null`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.enterFindMode()
        viewModel.updateFindQuery("anything")
        advanceUntilIdle()

        // controller 未 attach，findCount 仍为 0，findNext/findPrev 应早返回
        viewModel.findNext()
        viewModel.findPrev()
        advanceUntilIdle()

        assertEquals(-1, viewModel.findCurrent.value)
    }
}
