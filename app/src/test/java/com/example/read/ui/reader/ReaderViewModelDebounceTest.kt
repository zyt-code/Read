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
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel pagePrefs debounce 节流回归测试。
 *
 * 修复要点（详见 FIX_REPORT.md C 项）：
 * - 章内页码写入采用 100ms debounce：
 *   - 调用 savePageInChapter / 章节切换时不立即 apply，而是把 (key, value)
 *     存入 pendingPageWrites 内存映射，并启动/重排 100ms 延时 Job
 *   - 100ms 内多次调用，仅最后一次值生效，apply() 只被调用 1 次
 *   - getPageInChapter 优先读 pending 值，避免 debounce 窗口内读到陈旧值
 *
 * 该测试用 StandardTestDispatcher + advanceTimeBy 精确控制虚拟时间，
 * 验证 100ms 内连续触发的多次 savePageInChapter 最终只触发 1 次 apply。
 *
 * 注意：savePageInChapter 是 private，无法直接调用，需通过触发该函数的公开 API：
 * - syncChapterState（章节切换）会调用 savePageInChapter
 * - 也可通过反射调用 savePageInChapter；这里选择反射方式更可靠
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelDebounceTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 51L
    private val savedPagePrefsState = mutableMapOf<String, Int>()

    private lateinit var pageEditor: SharedPreferences.Editor

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

    private fun makeMetadata(): BookMetadata = BookMetadata(
        title = "T", author = "A", opfDir = "OEBPS",
        spine = (1..5).map {
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
        pageEditor = mockk<SharedPreferences.Editor>(relaxed = true)
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
     * 反射调用 ReaderViewModel.savePageInChapter（private）
     */
    private fun invokeSavePageInChapter(viewModel: ReaderViewModel, chapterIndex: Int, pageInChapter: Int) {
        val method = ReaderViewModel::class.java.getDeclaredMethod(
            "savePageInChapter", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(viewModel, chapterIndex, pageInChapter)
    }

    /**
     * Given: 100ms 内连续 5 次 savePageInChapter，每次值不同
     * When: 时间推进到 100ms 之后
     * Then: pageEditor.apply() 只被调用 1 次（debounce 生效），并且最终值是最后一次的
     */
    @Test
    fun `should coalesce multiple rapid saves into one apply within debounce window`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 在虚拟时间 0ms 时连续 5 次写入
        invokeSavePageInChapter(viewModel, 1, 0)
        invokeSavePageInChapter(viewModel, 1, 1)
        invokeSavePageInChapter(viewModel, 1, 2)
        invokeSavePageInChapter(viewModel, 1, 3)
        invokeSavePageInChapter(viewModel, 1, 4)

        // 此时尚未到 100ms，不应触发 apply
        runCurrent()
        verify(exactly = 0) { pageEditor.apply() }

        // 推进 50ms，仍不到 100ms
        advanceTimeBy(50)
        runCurrent()
        verify(exactly = 0) { pageEditor.apply() }

        // 再推进 60ms（累计 110ms），超过 debounce 窗口
        advanceTimeBy(60)
        runCurrent()
        advanceUntilIdle()

        // === 关键断言：apply 只触发 1 次 ===
        verify(exactly = 1) { pageEditor.apply() }
        // 最终值是最后一次的 4
        val key = "page_ch_${testBookId}_1"
        assertEquals(4, savedPagePrefsState[key])
    }

    /**
     * Given: 第一次写入完成（apply 被调用一次），间隔 200ms 后再次写入
     * When: 时间分别推进过两个 debounce 窗口
     * Then: apply 总共被调用 2 次（每个 debounce 周期独立）
     */
    @Test
    fun `should apply twice when saves are spaced beyond debounce window`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        invokeSavePageInChapter(viewModel, 0, 5)
        advanceTimeBy(150) // 触发第一次 apply
        runCurrent()
        verify(exactly = 1) { pageEditor.apply() }

        // 间隔后再写入
        invokeSavePageInChapter(viewModel, 0, 6)
        advanceTimeBy(150)
        runCurrent()
        advanceUntilIdle()

        verify(exactly = 2) { pageEditor.apply() }
        assertEquals(6, savedPagePrefsState["page_ch_${testBookId}_0"])
    }

    /**
     * Given: debounce 窗口内写入了多个不同章节的 pageInChapter
     * When: 推进到 100ms 之后
     * Then: 一次 apply() 中通过 putInt 写入了多个键值（batch apply）
     */
    @Test
    fun `should batch writes across different chapters in single apply`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        invokeSavePageInChapter(viewModel, 0, 1)
        invokeSavePageInChapter(viewModel, 1, 2)
        invokeSavePageInChapter(viewModel, 2, 3)
        advanceTimeBy(150)
        runCurrent()
        advanceUntilIdle()

        // apply 只触发一次
        verify(exactly = 1) { pageEditor.apply() }
        // 3 个键都被写入
        assertEquals(1, savedPagePrefsState["page_ch_${testBookId}_0"])
        assertEquals(2, savedPagePrefsState["page_ch_${testBookId}_1"])
        assertEquals(3, savedPagePrefsState["page_ch_${testBookId}_2"])
    }
}
