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
import com.example.read.util.TocItem
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel 的补强测试。
 *
 * 覆盖 ReaderViewModelTest 未覆盖的分支：
 * - jumpToChapter(): 已预加载和未预加载两条路径
 * - jumpToGlobalPage(): 越界裁剪
 * - updateSettings(): 清空缓存并保留章内页码用于重新分页恢复
 * - onPageCountReady(): 用于触发位置恢复（pendingPositionRestore）
 * - tocItems: 从 BookMetadata 透传
 * - showSettings / hideSettings: 状态切换
 * - clearError: 错误清除
 * - 错误路径：getBookMetadata 失败时设置错误信息
 * - getChapterHtmlPath(): 回调 null 与有效路径
 *
 * 不修改生产代码，发现的潜在 bug 写到报告中。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelExtraTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 7L

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

    /**
     * 构造书籍元数据（默认 5 个章节，可带 NCX 目录）。
     */
    private fun makeMetadata(
        chapterCount: Int = 5,
        toc: List<TocItem> = emptyList(),
    ): BookMetadata = BookMetadata(
        title = "T",
        author = "A",
        opfDir = "OEBPS",
        spine = (1..chapterCount).map {
            SpineItem("ch$it", "ch$it.xhtml", "Chapter $it", "application/xhtml+xml")
        },
        tocItems = toc,
    )

    /**
     * 创建带 mock 配置的 ViewModel。
     * 同时为 page_progress SharedPreferences 配置一个可读写的内存存储。
     */
    private fun createViewModel(
        bookId: Long = testBookId,
        book: Book? = Book(
            id = testBookId, title = "T", author = "A",
            coverPath = null, bookDirPath = "/books/$testBookId",
            totalChapters = 5, lastReadChapter = 0, lastReadAt = 0L,
        ),
        metadata: BookMetadata = makeMetadata(),
        metadataThrows: Throwable? = null,
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

        coEvery { repository.getBookById(bookId) } returns book
        if (metadataThrows != null) {
            coEvery { repository.getBookMetadata(bookId) } throws metadataThrows
        } else {
            coEvery { repository.getBookMetadata(bookId) } returns metadata
        }
        coEvery { repository.getChapterHtmlFile(bookId, any()) } returns File("/tmp/x.xhtml")
        coEvery { repository.updateReadingProgress(any(), any()) } returns Unit

        val app = mockk<Application>(relaxed = true)
        // reading_settings 的 prefs
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

        // reader_page_progress 的 prefs：使用一个内存 Map 模拟读写
        val pagePrefs = mockk<SharedPreferences>(relaxed = true)
        every { pagePrefs.getInt(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Int>()
            savedPagePrefsState[key] ?: default
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
                "com.example.read.ui.navigation.Reader" to mapOf("bookId" to bookId),
                "bookId" to bookId,
            )
        )
        return ReaderViewModel(repository, app, savedStateHandle, mockk(relaxed = true))
    }

    // ==================== jumpToChapter ====================

    /**
     * Given: 全部章节已预加载（onPageCountReady 完成），ViewModel 知道每章的页数
     * When: 调用 jumpToChapter(3)
     * Then: 跳转到该章的第一页，chapterIndex 更新为 3，调用 updateReadingProgress
     */
    @Test
    fun `should jump to chapter first page when chapter already preloaded`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        viewModel.jumpToChapter(3)
        advanceUntilIdle()

        assertEquals(3, viewModel.chapterIndex.value)
        // 第 3 章的第一页全局索引 = 3 * 3 = 9
        assertEquals(9, viewModel.currentGlobalPage.value)
        coVerify { repository.updateReadingProgress(testBookId, 3) }
    }

    /**
     * Given: 章节尚未预加载（globalPages 为空）
     * When: 调用 jumpToChapter(2)
     * Then: chapterIndex 立刻更新，触发 loadChapterHtmlPath 与 updateReadingProgress
     */
    @Test
    fun `should switch chapter state immediately when target chapter not preloaded`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        // 故意不预加载任何章节的页数

        viewModel.jumpToChapter(2)
        advanceUntilIdle()

        assertEquals(2, viewModel.chapterIndex.value)
        coVerify { repository.updateReadingProgress(testBookId, 2) }
    }

    /**
     * Given: jumpToChapter 索引越界（负值或超过 spine 大小）
     * When: 调用 jumpToChapter(-1) 与 jumpToChapter(99)
     * Then: 不更新 chapterIndex，不调用 updateReadingProgress
     */
    @Test
    fun `should ignore jumpToChapter when index out of range`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        val originalChapter = viewModel.chapterIndex.value

        viewModel.jumpToChapter(-1)
        viewModel.jumpToChapter(99)
        advanceUntilIdle()

        assertEquals(originalChapter, viewModel.chapterIndex.value)
        // 仅有 init 时的可能调用，jumpToChapter 越界不再触发
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, -1) }
        coVerify(exactly = 0) { repository.updateReadingProgress(testBookId, 99) }
    }

    // ==================== jumpToGlobalPage ====================

    /**
     * Given: globalPages 包含 9 页（3 章 x 3 页）
     * When: 调用 jumpToGlobalPage(100)
     * Then: 被裁剪到 totalPages-1，currentGlobalPage 等于最后一页
     */
    @Test
    fun `should clamp jumpToGlobalPage to last valid index`() = runTest {
        val viewModel = createViewModel(metadata = makeMetadata(chapterCount = 3))
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        viewModel.jumpToGlobalPage(100)
        advanceUntilIdle()

        assertEquals(8, viewModel.currentGlobalPage.value) // 9 页 - 1
    }

    /**
     * Given: globalPages 为空
     * When: 调用 jumpToGlobalPage(0)
     * Then: 不抛异常，currentGlobalPage 保持初始 0
     */
    @Test
    fun `should not crash when jumpToGlobalPage on empty pages`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.jumpToGlobalPage(0)
        viewModel.jumpToGlobalPage(5)
        advanceUntilIdle()
        assertEquals(0, viewModel.currentGlobalPage.value)
    }

    // ==================== updateSettings ====================

    /**
     * Given: 章节已预加载，当前位于章节 2 的第二页（章内页码 = 1）
     * When: 调用 updateSettings 更换字号
     * Then: 页数缓存清空、globalPages 清空，待恢复的章内页码被记下用于重新分页后恢复
     */
    @Test
    fun `should clear page cache and pending restore page when settings updated`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        viewModel.jumpToGlobalPage(7) // 章节 2 的页 1 -> globalIndex = 7
        advanceUntilIdle()
        assertEquals(2, viewModel.chapterIndex.value)
        assertEquals(7, viewModel.currentGlobalPage.value)

        viewModel.updateSettings(ReadingSettings(fontSize = 24f))
        advanceUntilIdle()

        // 缓存与全局页都被清空
        assertTrue(viewModel.chapterPageCounts.value.isEmpty())
        assertTrue(viewModel.globalPages.value.isEmpty())
        assertEquals(0, viewModel.currentPageCount.value)

        // 模拟 WebView 重新分页：每章 5 页
        for (i in 0..4) viewModel.onPageCountReady(i, 5)
        advanceUntilIdle()

        // 章节 2 的 pageInChapter=1 -> 新全局索引 = 2*5 + 1 = 11
        assertEquals(11, viewModel.currentGlobalPage.value)
        assertEquals(2, viewModel.chapterIndex.value)
    }

    // ==================== onPageCountReady ====================

    /**
     * Given: 一个章节回调页数
     * When: onPageCountReady(chapterIndex=1, pageCount=4)
     * Then: chapterPageCounts 与 globalPages 同步更新
     */
    @Test
    fun `should update page counts and global pages on onPageCountReady`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPageCountReady(1, 4)
        advanceUntilIdle()

        assertEquals(4, viewModel.chapterPageCounts.value[1])
        assertEquals(4, viewModel.globalPages.value.size)
        // 全部页面属于章节 1
        assertTrue(viewModel.globalPages.value.all { it.chapterIndex == 1 })
    }

    /**
     * Given: 当前章节是 2，回调当前章节页数
     * When: onPageCountReady(2, 5)
     * Then: currentPageCount 同步更新为 5
     */
    @Test
    fun `should sync currentPageCount when callback matches current chapter`() = runTest {
        // 初始章节由 lastReadChapter 决定，这里默认为 0；先切到 2 再触发回调
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.jumpToChapter(2)
        advanceUntilIdle()

        viewModel.onPageCountReady(2, 5)
        advanceUntilIdle()
        assertEquals(5, viewModel.currentPageCount.value)
    }

    // ==================== tocItems ====================

    /**
     * Given: 元数据带 NCX 目录
     * When: ViewModel 加载完成
     * Then: tocItems 等于 metadata.tocItems
     */
    @Test
    fun `should expose tocItems from metadata`() = runTest {
        val toc = listOf(
            TocItem("第一章", 0, 0),
            TocItem("1.1 节", 0, 1),
            TocItem("第二章", 1, 0),
        )
        val viewModel = createViewModel(metadata = makeMetadata(toc = toc))
        advanceUntilIdle()
        assertEquals(toc, viewModel.tocItems.value)
    }

    // ==================== 设置面板可见性 ====================

    /**
     * Given: ViewModel 初始化完成
     * When: 调用 showSettings / hideSettings
     * Then: showSettings 状态正确切换
     */
    @Test
    fun `should toggle settings panel visibility`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.showSettings.value)
        viewModel.showSettings()
        assertTrue(viewModel.showSettings.value)
        viewModel.hideSettings()
        assertFalse(viewModel.showSettings.value)
    }

    // ==================== clearError ====================

    /**
     * Given: getBookMetadata 抛异常
     * When: ViewModel 加载完成
     * Then: errorMessage 被设置；调用 clearError 后恢复 null
     */
    @Test
    fun `should set error message when loading metadata fails and clear via clearError`() = runTest {
        val viewModel = createViewModel(metadataThrows = RuntimeException("boom"))
        advanceUntilIdle()
        assertNotNull("加载失败应设置错误消息", viewModel.errorMessage.value)

        viewModel.clearError()
        assertNull(viewModel.errorMessage.value)
    }

    // ==================== getChapterHtmlPath ====================

    /**
     * Given: repository.getChapterHtmlFile 正常返回 File
     * When: 调用 getChapterHtmlPath
     * Then: 回调收到非空 path
     */
    @Test
    fun `should invoke callback with absolute path when getChapterHtmlPath succeeds`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var captured: String? = null
        viewModel.getChapterHtmlPath(1) { captured = it }
        advanceUntilIdle()
        assertNotNull(captured)
    }

    /**
     * Given: repository.getChapterHtmlFile 抛异常
     * When: 调用 getChapterHtmlPath
     * Then: 回调收到 null
     */
    @Test
    fun `should invoke callback with null when getChapterHtmlPath fails`() = runTest {
        coEvery { repository.getChapterHtmlFile(testBookId, any()) } throws RuntimeException("io error")
        val viewModel = createViewModel()
        advanceUntilIdle()

        var captured: String? = "not null"
        viewModel.getChapterHtmlPath(0) { captured = it }
        advanceUntilIdle()
        assertNull(captured)
    }

    // ==================== 进度保存：onCleared ====================

    /**
     * Given: 已切换到章节 2 的某个章内页
     * When: ViewModel 被显式销毁（onCleared 通过反射调用）
     * Then: 当前章内页码被写入 SharedPreferences
     *
     * 注意：onCleared 是 protected，使用反射调用以验证行为。
     */
    @Test
    fun `should save current page in chapter to prefs when ViewModel is cleared`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        viewModel.jumpToGlobalPage(7) // 章节 2 / 页 1
        advanceUntilIdle()

        // 反射调用 onCleared（AndroidViewModel 中是 protected）
        val onClearedMethod = androidx.lifecycle.ViewModel::class.java
            .getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)
        advanceUntilIdle()

        // SharedPreferences 内存模拟应记录到 "page_ch_<bookId>_<chapter>"
        val expectedKey = "page_ch_${testBookId}_2"
        assertEquals(1, savedPagePrefsState[expectedKey])
    }
}
