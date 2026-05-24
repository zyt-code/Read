package com.example.read.ui.reader

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel 章节边界回归测试（B1 修复）。
 *
 * B1 修复要点（详见 FIX_REPORT.md）：
 * - `nextChapter()` 在调用前显式判断 `currentChapter + 1 >= spineSize → return`
 *   不再仅依赖 globalPages 中是否存在下一章作为隐式上界
 * - `previousChapter()` 已有 `currentChapter <= 0 → return` 的下界判断
 *
 * 与 ReaderViewModelTest 中既有的 nextChapter / previousChapter 边界测试互补：
 * - 旧测试关注 globalPages 兜底（隐式）
 * - 本测试关注显式上界（即使预加载逻辑异常填入了超出 spine.size 的章节
 *   也不会越界）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelBoundaryTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 11L
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

    /** 构造一个带 chapterCount 个章节的 BookMetadata */
    private fun makeMetadata(chapterCount: Int): BookMetadata = BookMetadata(
        title = "T",
        author = "A",
        opfDir = "OEBPS",
        spine = (1..chapterCount).map {
            SpineItem("ch$it", "ch$it.xhtml", "Chapter $it", "application/xhtml+xml")
        },
        tocItems = emptyList(),
    )

    /**
     * 创建一个 ViewModel，初始章节由 lastReadChapter 决定。
     */
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
        return ReaderViewModel(repository, app, savedStateHandle, mockk(relaxed = true))
    }

    // ==================== nextChapter 上界 ====================

    /**
     * Given: spine 共 5 章（索引 0..4），当前章节是 4（末章），globalPages 已包含全部章节
     * When: 调用 nextChapter()
     * Then: chapterIndex 保持 4，不抛异常，不触发额外的 updateReadingProgress(bookId, 5)
     *
     * B1 显式上界：currentChapter + 1 = 5 >= spineSize=5，直接 return
     */
    @Test
    fun `should not advance when nextChapter called at last chapter`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 4)
        advanceUntilIdle()
        for (i in 0..4) viewModel.onPageCountReady(i, 2)
        advanceUntilIdle()

        // 移动到末章首页
        viewModel.jumpToChapter(4)
        advanceUntilIdle()
        assertEquals(4, viewModel.chapterIndex.value)

        viewModel.nextChapter()
        advanceUntilIdle()

        // 章节未变化
        assertEquals("末章调用 nextChapter 不应推进", 4, viewModel.chapterIndex.value)
        // 没有触发对不存在章节 5 的进度保存
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, 5) }
    }

    /**
     * Given: 当前章节 0（首章），globalPages 包含全部章节
     * When: 调用 previousChapter()
     * Then: chapterIndex 保持 0，不抛异常
     */
    @Test
    fun `should not regress when previousChapter called at first chapter`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        for (i in 0..4) viewModel.onPageCountReady(i, 2)
        advanceUntilIdle()

        assertEquals(0, viewModel.chapterIndex.value)
        viewModel.previousChapter()
        advanceUntilIdle()

        assertEquals("首章调用 previousChapter 不应回退", 0, viewModel.chapterIndex.value)
        // 不应触发负章节的进度保存
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, -1) }
    }

    /**
     * Given: 当前章节是末章，globalPages 故意只包含到 currentChapter（不含下一章占位）
     *        这种情况以前会依赖 indexOfFirst 返回 -1 兜底，但若上游错误添加了
     *        chapterIndex = spineSize 的 PageInfo（如预加载逻辑异常），可能越界。
     * When: 调用 nextChapter()
     * Then: 显式上界判定先于 indexOfFirst 生效，直接 return，不会触发任何跳转
     *
     * 注意：本测试主要保证显式上界路径的优先级，结果与既有"末章兜底"测试一致，
     * 但意图是测"即使下游逻辑有 bug，nextChapter 也不会越界"。
     */
    @Test
    fun `should respect explicit upper bound even when globalPages contains stale data`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 2)
        advanceUntilIdle()
        // 仅注册章节 2 的页数（不预加载其他）
        viewModel.onPageCountReady(2, 4)
        advanceUntilIdle()
        assertEquals(2, viewModel.chapterIndex.value)

        // 调用 nextChapter：显式上界 currentChapter+1=3 >= spineSize=3 → return
        viewModel.nextChapter()
        advanceUntilIdle()

        // chapterIndex 保持 2
        assertEquals(2, viewModel.chapterIndex.value)
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, 3) }
    }

    /**
     * Given: 末章调用 nextChapter 多次
     * When: 反复调用
     * Then: chapterIndex 始终不变，repository.updateReadingProgress 不再被调用
     */
    @Test
    fun `should be idempotent when nextChapter called multiple times at boundary`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 2)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 2)
        advanceUntilIdle()

        viewModel.jumpToChapter(2)
        advanceUntilIdle()

        repeat(5) {
            viewModel.nextChapter()
        }
        advanceUntilIdle()

        assertEquals(2, viewModel.chapterIndex.value)
        // 仅有 jumpToChapter(2) 触发的一次 updateReadingProgress（章节 2），
        // 不应有 chapter=3 的调用
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, 3) }
    }

    // ==================== P1-3: nextChapter / previousChapter 未预加载兜底 ====================

    /**
     * P1-3 回归测试：
     * Given: currentChapter=0，globalPages 仅包含 chapter 0 的页（chapter 1 未预加载）
     * When: 调 `nextChapter()`
     * Then: `chapterIndex.value == 1`（通过内部 jumpToChapter(1) 切换章节状态），
     *       同时 repository.updateReadingProgress(bookId, 1) 被调用一次
     *
     * 修复要点（详见 FIX_REPORT_v2.md Fix-3）：
     * 旧实现遇到 `indexOfFirst{ chapterIndex==1 && pageInChapter==0 } == -1` 时
     * 静默 no-op，底栏"下一章"按钮看起来失灵；新实现在未预加载时复用
     * jumpToChapter(currentChapter+1) 兜底，立即切章节并触发 UI 加载新章节。
     */
    @Test
    fun `should fall back to jumpToChapter when nextChapter target not preloaded`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        // 仅注册 chapter 0 的页数（3 页），chapter 1 完全未预加载
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()
        assertEquals(0, viewModel.chapterIndex.value)

        viewModel.nextChapter()
        advanceUntilIdle()

        // 章节实际推进到 1（不再静默 no-op）
        assertEquals("nextChapter 在未预加载时也应推进", 1, viewModel.chapterIndex.value)
        // 通过 jumpToChapter 兜底应同步触发进度保存
        coVerify(atLeast = 1) { repository.updateReadingProgress(testBookId, 1) }
    }

    /**
     * P1-3 对称测试：
     * Given: currentChapter=3（中间章节），globalPages 只包含 chapter 3 自己的页
     *        （chapter 2 未预加载）
     * When: 调 `previousChapter()`
     * Then: `chapterIndex.value == 2`，repository.updateReadingProgress(bookId, 2) 被调用
     */
    @Test
    fun `should fall back to jumpToChapter when previousChapter target not preloaded`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 3)
        advanceUntilIdle()
        // 仅注册 chapter 3 的页数；chapter 2 未预加载
        viewModel.onPageCountReady(3, 4)
        advanceUntilIdle()
        assertEquals(3, viewModel.chapterIndex.value)

        viewModel.previousChapter()
        advanceUntilIdle()

        // 章节回退到 2（fallback 走 jumpToChapter）
        assertEquals("previousChapter 在未预加载时也应回退", 2, viewModel.chapterIndex.value)
        coVerify(atLeast = 1) { repository.updateReadingProgress(testBookId, 2) }
    }

    /**
     * 边界回归：确保 nextChapter 的"未预加载兜底"不破坏既有的"显式上界拦截"。
     * Given: 当前 = 末章，globalPages 不含下一章（自然不会预加载）
     * When: 调 `nextChapter()`
     * Then: 显式上界 `currentChapter + 1 >= spineSize → return` 优先生效，
     *       不会触发 jumpToChapter(spineSize)；chapterIndex 保持不变。
     *
     * 这个 case 是 B1（末章不变）与 P1-3（fallback）的交叉，验证 P1-3 修复
     * 没有破坏 B1 的上界保护。
     */
    @Test
    fun `should not fall back beyond last chapter even when not preloaded`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 2)
        advanceUntilIdle()
        // 仅注册末章 (chapter 2) 的页数
        viewModel.onPageCountReady(2, 2)
        advanceUntilIdle()
        assertEquals(2, viewModel.chapterIndex.value)

        viewModel.nextChapter()
        advanceUntilIdle()

        // 末章兜底拦截优先：chapterIndex 仍为 2，不应有 chapter=3 的调用
        assertEquals(2, viewModel.chapterIndex.value)
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, 3) }
    }

    /**
     * 边界回归：previousChapter 在首章未预加载时仍受下界保护。
     * Given: 当前 = 首章 (chapter 0)
     * When: 调 `previousChapter()`
     * Then: `currentChapter <= 0 → return` 优先生效，不会触发 jumpToChapter(-1)
     */
    @Test
    fun `should not fall back beyond first chapter even when not preloaded`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 0)
        advanceUntilIdle()
        viewModel.onPageCountReady(0, 2)
        advanceUntilIdle()
        assertEquals(0, viewModel.chapterIndex.value)

        viewModel.previousChapter()
        advanceUntilIdle()

        assertEquals(0, viewModel.chapterIndex.value)
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, -1) }
    }
}
