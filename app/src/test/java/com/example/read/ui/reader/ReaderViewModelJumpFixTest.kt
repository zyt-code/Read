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
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel.jumpToChapter 未预加载分支回归测试（P1-NEW-2）。
 *
 * 修复要点（详见 FIX_REPORT_v3.md Fix-2）：
 * - 旧实现：未预加载分支会立即写 `_currentGlobalPage.value = 0`。但此时 globalPages
 *   仍保留旧章节的页，索引 0 指向"旧章节第一页"，UI 上 ViewPager2 会先错位闪到旧章节首页，
 *   新章节加载完毕后页码仍然停在错位（onPageCountReady 不会主动恢复）。
 * - 新实现：不再写 `_currentGlobalPage`（保留旧值），改为标记
 *   `pendingPositionRestore = true` + `pendingPageInChapter = 0`；
 *   等 onPageCountReady(目标章节, pageCount) 回调到达后，由位置恢复机制
 *   把 `_currentGlobalPage` 精确设置到目标章节首页的全局索引。
 *
 * 验证策略：
 * - 无法直接读 private 字段 `pendingPositionRestore`，转而验证可观察的 StateFlow 行为：
 *   1) jumpToChapter 后立刻：`chapterIndex` 已更新到目标值，`currentGlobalPage` 应保留
 *      跳转前的旧值（不被强制设为 0）
 *   2) 模拟 WebView 回调 `onPageCountReady(目标章节, N)` 后：
 *      `currentGlobalPage` 被恢复到目标章节首页对应的全局索引
 *
 * 不修改生产代码；不引入新依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelJumpFixTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 13L
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

    private fun createViewModel(
        chapterCount: Int = 8,
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

    // ==================== P1-NEW-2 核心回归 ====================

    /**
     * P1-NEW-2 核心回归：
     * Given:
     *   - 当前章节 = 5，章节 5 已被 onPageCountReady 注册 3 页，章节 6 未预加载
     *   - 通过 jumpToGlobalPage 把位置定位到章 5 的最后一页（globalPage = 某非零值）
     * When: 调用 jumpToChapter(6)（目标 = 未预加载章节）
     * Then:
     *   - chapterIndex.value == 6 （章节状态已切换）
     *   - currentGlobalPage.value 保留跳转前的旧值（不被强制设为 0），
     *     这是旧实现错位的核心点：旧实现会写成 0，把 UI 错误带回 globalPages[0]
     *   - currentPageCount.value == 0（未预加载，未知页数）
     *
     * 旧实现下：assertion `assertNotEquals(0, currentGlobalPage)` 会失败（实际 = 0）。
     */
    @Test
    fun `should not zero out currentGlobalPage when jumping to unloaded chapter`() = runTest {
        val viewModel = createViewModel(chapterCount = 8, lastReadChapter = 5)
        advanceUntilIdle()
        // 只注册章节 5 的页数（3 页），章节 6 未预加载
        viewModel.onPageCountReady(5, 3)
        advanceUntilIdle()
        // 让 currentGlobalPage 进入非零位置（章 5 的第 3 页，globalPage=2）
        viewModel.jumpToGlobalPage(2)
        advanceUntilIdle()
        val beforeJump = viewModel.currentGlobalPage.value
        assertEquals(2, beforeJump)
        assertEquals(5, viewModel.chapterIndex.value)

        // jumpToChapter(6)：未预加载分支
        viewModel.jumpToChapter(6)
        advanceUntilIdle()

        // 章节索引推进到 6
        assertEquals("章节状态已切换到 6", 6, viewModel.chapterIndex.value)
        // 关键断言：currentGlobalPage 保留旧值（旧实现会写成 0）
        assertEquals(
            "未预加载分支不应把 currentGlobalPage 强制设为 0（P1-NEW-2 修复）",
            beforeJump, viewModel.currentGlobalPage.value,
        )
        assertNotEquals(
            "P1-NEW-2 旧 bug 复现断言：currentGlobalPage 不应为 0",
            0, viewModel.currentGlobalPage.value,
        )
        // 目标章节未预加载，pageCount 为 0
        assertEquals(0, viewModel.currentPageCount.value)
        // 进度保存被触发
        coVerify { repository.updateReadingProgress(testBookId, 6) }
    }

    /**
     * P1-NEW-2 后续回归：onPageCountReady 触发位置恢复
     *
     * Given: 接上一个 case，jumpToChapter(6) 已发生且 currentGlobalPage 保留旧值
     * When: 模拟 WebView 加载完成 → 调用 onPageCountReady(6, 5)
     *       （目标章节 6 共 5 页）
     * Then:
     *   - globalPages 被重建：包含章 5 (3 页) + 章 6 (5 页) = 8 个 PageInfo
     *   - currentGlobalPage 被恢复到章 6 的首页对应的全局索引：
     *     按 chapterIndex 升序排列，章 5 占索引 0..2，章 6 占索引 3..7，
     *     章 6 首页对应 globalIndex = 3
     */
    @Test
    fun `should restore currentGlobalPage to target chapter first page after onPageCountReady`() = runTest {
        val viewModel = createViewModel(chapterCount = 8, lastReadChapter = 5)
        advanceUntilIdle()
        // 注册章节 5 的 3 页，章节 6 未预加载
        viewModel.onPageCountReady(5, 3)
        advanceUntilIdle()
        viewModel.jumpToGlobalPage(2) // 停在章 5 末页（globalPage=2）
        advanceUntilIdle()

        // jumpToChapter 到未预加载的章 6
        viewModel.jumpToChapter(6)
        advanceUntilIdle()
        // 旧值仍为 2，未被强制清零
        assertEquals(2, viewModel.currentGlobalPage.value)

        // 模拟 WebView 加载完成：章 6 共 5 页
        viewModel.onPageCountReady(6, 5)
        advanceUntilIdle()

        // globalPages 已重建：3 (章 5) + 5 (章 6) = 8
        assertEquals(8, viewModel.globalPages.value.size)
        // 章 6 首页对应 globalIndex = 3（排序后章 5 占 0..2）
        assertEquals(
            "onPageCountReady 应将 currentGlobalPage 恢复到目标章节首页（globalIndex=3）",
            3, viewModel.currentGlobalPage.value,
        )
        assertEquals(6, viewModel.chapterIndex.value)
        assertEquals(5, viewModel.currentPageCount.value)
    }

    /**
     * 反向回归：jumpToChapter 到已预加载章节走 jumpToGlobalPage 路径，
     * 不应触发"保留旧值"的状态机（直接精确跳转）。
     *
     * Given: 章 0/1/2 都已预加载，每章 3 页（globalPages 共 9 页），当前在章 0 首页
     * When: jumpToChapter(2)
     * Then: chapterIndex.value == 2；currentGlobalPage.value == 6（章 2 首页）
     *
     * 与上面 case 形成对照，确保 P1-NEW-2 修复没有破坏"已预加载分支"的精确跳转。
     */
    @Test
    fun `should still jump precisely when target chapter already preloaded`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        assertEquals(0, viewModel.chapterIndex.value)
        assertEquals(0, viewModel.currentGlobalPage.value)

        viewModel.jumpToChapter(2)
        advanceUntilIdle()

        assertEquals(2, viewModel.chapterIndex.value)
        // 章 2 首页对应 globalIndex = 6
        assertEquals(6, viewModel.currentGlobalPage.value)
    }

    /**
     * 边界：连续两次 jumpToChapter 到不同未预加载章节，旧值的传递语义。
     *
     * Given: 当前在章 0（currentGlobalPage=0）；onPageCountReady(0, 4) 已注册
     * When: jumpToChapter(5) → onPageCountReady(5, 2) → jumpToChapter(7)（仍未预加载）
     * Then: 第二次 jumpToChapter 后 currentGlobalPage 保留刚才被恢复的章 5 首页 globalIndex，
     *       而不是被清零；onPageCountReady(7, 3) 触发后再次精确恢复到章 7 首页
     */
    @Test
    fun `should chain pending restores across multiple jumps`() = runTest {
        val viewModel = createViewModel(chapterCount = 10, lastReadChapter = 0)
        advanceUntilIdle()
        viewModel.onPageCountReady(0, 4)
        advanceUntilIdle()
        assertEquals(0, viewModel.chapterIndex.value)

        // 第一次跳到未预加载的章 5
        viewModel.jumpToChapter(5)
        advanceUntilIdle()
        // 章节切换但 currentGlobalPage 保留旧值（0）
        assertEquals(5, viewModel.chapterIndex.value)

        // 触发 onPageCountReady(5, 2)：恢复 currentGlobalPage 到章 5 首页（globalIndex=4）
        viewModel.onPageCountReady(5, 2)
        advanceUntilIdle()
        // 4 (章 0) + 0 (章 5 首页) = 4
        assertEquals(4, viewModel.currentGlobalPage.value)

        // 第二次跳到未预加载的章 7
        viewModel.jumpToChapter(7)
        advanceUntilIdle()
        assertEquals(7, viewModel.chapterIndex.value)
        // 旧值（章 5 首页 globalIndex=4）应被保留，不被清零
        assertEquals(
            "二次 jumpToChapter 同样不应清零 currentGlobalPage",
            4, viewModel.currentGlobalPage.value,
        )

        // 触发 onPageCountReady(7, 3)：恢复到章 7 首页
        viewModel.onPageCountReady(7, 3)
        advanceUntilIdle()
        // globalPages = 章 0 (4 页) + 章 5 (2 页) + 章 7 (3 页) = 9
        // 章 7 首页对应 globalIndex = 4 + 2 = 6
        assertEquals(6, viewModel.currentGlobalPage.value)
    }
}
