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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel.updateSettings 阅读位置恢复回归测试（B2 修复）。
 *
 * B2 修复要点（详见 FIX_REPORT.md）：
 * - `updateSettings` 在清空 globalPages 和 chapterPageCounts 缓存的同时，
 *   显式将 `_currentGlobalPage.value` 重置为 0，避免重新分页期间
 *   currentGlobalPage 指向超出 size=0 的旧值（瞬时越界）。
 * - pendingPositionRestore + pendingPageInChapter 仍负责在 onPageCountReady
 *   回调触发时把全局页码恢复到原百分比位置。
 *
 * 与 `ReaderViewModelExtraTest.should clear page cache and pending restore page when settings updated`
 * 互补：旧测试只断言"重新分页后回到目标位置"，本测试新增"重置发生瞬间 currentGlobalPage=0"
 * 的中间态断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelSettingsRestoreTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 31L
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

    private fun makeMetadata(chapterCount: Int = 5): BookMetadata = BookMetadata(
        title = "T", author = "A", opfDir = "OEBPS",
        spine = (1..chapterCount).map {
            SpineItem("ch$it", "ch$it.xhtml", "Chapter $it", "application/xhtml+xml")
        },
        tocItems = emptyList(),
    )

    private fun createViewModel(): ReaderViewModel {
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
            totalChapters = 5, lastReadChapter = 0, lastReadAt = 0L,
        )
        coEvery { repository.getBookById(testBookId) } returns book
        coEvery { repository.getBookMetadata(testBookId) } returns makeMetadata()
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

    /**
     * Given: 章节预加载完成，当前位于章节 2 第二页（globalPage=7）
     * When: 调用 updateSettings 切换字号
     * Then: 立即断言（onPageCountReady 尚未回调）：
     *   - globalPages 为空
     *   - chapterPageCounts 为空
     *   - currentGlobalPage 重置为 0（B2 修复关键点）
     *   - currentPageCount = 0
     */
    @Test
    fun `should reset currentGlobalPage to zero immediately on updateSettings`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // 预加载 5 章，每章 3 页
        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        // 跳到 globalPage=7（章节 2 / 章内页 1）
        viewModel.jumpToGlobalPage(7)
        advanceUntilIdle()
        assertEquals("跳转后应处于全局页 7", 7, viewModel.currentGlobalPage.value)
        assertEquals("跳转后应处于章节 2", 2, viewModel.chapterIndex.value)

        // 切换设置（字号变更）
        viewModel.updateSettings(ReadingSettings(fontSize = 24f))
        advanceUntilIdle()

        // === B2 关键回归断言 ===
        assertTrue("globalPages 应被清空", viewModel.globalPages.value.isEmpty())
        assertTrue("chapterPageCounts 应被清空", viewModel.chapterPageCounts.value.isEmpty())
        assertEquals("currentGlobalPage 必须重置为 0（B2 修复）", 0, viewModel.currentGlobalPage.value)
        assertEquals("currentPageCount 应重置为 0", 0, viewModel.currentPageCount.value)
    }

    /**
     * Given: 切换设置后 globalPages 已清空，currentGlobalPage=0（中间态）
     * When: WebView 重新分页完成，触发 onPageCountReady
     * Then: pendingPositionRestore 把全局页码恢复到原百分比位置
     *
     * 验证 B2 修复并未破坏既有的"位置恢复"功能。
     */
    @Test
    fun `should restore page position after rebuild via onPageCountReady`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // 初始预加载：每章 3 页
        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        // 跳到章节 2，章内页 1（全局页 7）
        viewModel.jumpToGlobalPage(7)
        advanceUntilIdle()

        // 触发 updateSettings -> 立即 reset
        viewModel.updateSettings(ReadingSettings(fontSize = 24f))
        advanceUntilIdle()
        assertEquals(0, viewModel.currentGlobalPage.value)

        // 模拟 WebView 重新分页：每章 5 页
        for (i in 0..4) viewModel.onPageCountReady(i, 5)
        advanceUntilIdle()

        // 章节 2 章内页 1 在新分页下应为 globalPage = 2*5 + 1 = 11
        assertEquals("位置恢复应回到 globalPage=11", 11, viewModel.currentGlobalPage.value)
        assertEquals("章节索引应保持 2", 2, viewModel.chapterIndex.value)
    }

    /**
     * Given: 当前位于章节 0 章内页 0（首页）
     * When: 调用 updateSettings
     * Then: currentGlobalPage 重置为 0 不会破坏首页位置（边界场景）
     */
    @Test
    fun `should not break first-page state when updateSettings called at globalPage zero`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()
        assertEquals(0, viewModel.currentGlobalPage.value)

        viewModel.updateSettings(ReadingSettings(fontSize = 22f))
        advanceUntilIdle()
        assertEquals(0, viewModel.currentGlobalPage.value)
        assertTrue(viewModel.globalPages.value.isEmpty())

        // 重新分页：章节 0 共 4 页
        viewModel.onPageCountReady(0, 4)
        advanceUntilIdle()
        // 章内页 0 仍然映射到 globalPage=0
        assertEquals(0, viewModel.currentGlobalPage.value)
    }
}
