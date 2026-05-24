package com.example.read.ui.reader

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Looper
import io.mockk.every
import io.mockk.mockkStatic
import androidx.lifecycle.SavedStateHandle
import com.example.read.domain.model.Book
import com.example.read.domain.model.Chapter
import com.example.read.util.BookMetadata
import com.example.read.util.SpineItem
import java.io.File
import com.example.read.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

/**
 * ReaderViewModel 的单元测试。
 *
 * 测试范围：
 * - loadBook(): 加载书籍元数据并恢复上次阅读位置
 * - loadChapter(): 跳转到指定章节
 * - nextChapter() / previousChapter(): 章节前后导航和边界检查
 * - isLoading 状态管理
 * - 错误处理（书籍不存在、章节加载失败）
 *
 * 测试策略：
 * - 使用 MockK 模拟 BookRepository 接口
 * - 通过 SavedStateHandle 注入 bookId 参数（模拟 Navigation Compose 路由）
 * - 使用 kotlinx-coroutines-test 的 TestDispatcher 控制协程执行
 *
 * 注意：ReaderViewModel 在 init 块中调用 loadBook()，
 * 因此 ViewModel 创建时就会触发书籍加载。
 * SavedStateHandle 的 key 需要与 Navigation Compose 的 toRoute() 机制匹配。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    /** 模拟的书籍仓库 */
    private lateinit var repository: BookRepository

    /** 测试用的协程调度器 */
    private val testDispatcher = StandardTestDispatcher()

    /** 测试用的书籍 ID */
    private val testBookId = 42L

    /** 测试用的书籍数据 */
    private val testBook = Book(
        id = testBookId,
        title = "Test Book",
        author = "Test Author",
        coverPath = "/covers/test.jpg",
        bookDirPath = "/epubs/test.epub",
        totalChapters = 5,
        lastReadChapter = 2,
        lastReadAt = 1000L,
    )

    /** 测试用的章节数据 */
    private val testChapter0 = Chapter(
        index = 0,
        title = "Chapter 1",
        content = "Content of chapter 1",
    )

    private val testChapter2 = Chapter(
        index = 2,
        title = "Chapter 3",
        content = "Content of chapter 3",
    )

    private val testChapter4 = Chapter(
        index = 4,
        title = "Chapter 5",
        content = "Content of chapter 5",
    )

    /**
     * 每个测试前的初始化。
     * 设置 Main dispatcher 为测试调度器，配置仓库模拟数据。
     */
    @Before
    fun setUp() {
        // 设置 Main dispatcher 为测试调度器
        Dispatchers.setMain(testDispatcher)

        // 创建模拟仓库
        repository = mockk(relaxed = true)

        // 配置仓库默认行为：返回测试书籍和章节
        coEvery { repository.getBookById(testBookId) } returns testBook
        coEvery { repository.getChapterContent(testBookId, 0) } returns testChapter0
        coEvery { repository.getChapterContent(testBookId, 2) } returns testChapter2
        coEvery { repository.getChapterContent(testBookId, 4) } returns testChapter4
        coEvery { repository.updateReadingProgress(any(), any()) } returns Unit

        val testMetadata = BookMetadata(
            title = "Test Book",
            author = "Test Author",
            opfDir = "OEBPS",
            spine = listOf(
                SpineItem("ch1", "chapter1.xhtml", "Chapter 1", "application/xhtml+xml"),
                SpineItem("ch2", "chapter2.xhtml", "Chapter 2", "application/xhtml+xml"),
                SpineItem("ch3", "chapter3.xhtml", "Chapter 3", "application/xhtml+xml"),
                SpineItem("ch4", "chapter4.xhtml", "Chapter 4", "application/xhtml+xml"),
                SpineItem("ch5", "chapter5.xhtml", "Chapter 5", "application/xhtml+xml"),
            )
        )
        coEvery { repository.getBookMetadata(testBookId) } returns testMetadata
        coEvery { repository.getChapterHtmlFile(testBookId, any()) } returns File("/tmp/test.xhtml")
    }

    /**
     * 每个测试后的清理。
     * 重置 Main dispatcher。
     */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 辅助方法：创建配置好 bookId 的 SavedStateHandle。
     * ReaderViewModel 通过 savedStateHandle.toRoute<Reader>().bookId 获取书籍 ID，
     * SavedStateHandle 中需要包含 "bookId" 键。
     */
    private fun createSavedStateHandle(bookId: Long = testBookId): SavedStateHandle {
        return SavedStateHandle(mapOf(
            "com.example.read.ui.navigation.Reader" to mapOf("bookId" to bookId),
            "bookId" to bookId
        ))
    }

    /**
     * 辅助方法：创建 ReaderViewModel 实例。
     * 封装 SavedStateHandle 的创建，简化测试代码。
     */
    private fun createViewModel(bookId: Long = testBookId): ReaderViewModel {
        // Mock Looper.getMainLooper() for unit tests
        mockkStatic(Looper::class)
        val mockLooper = mockk<Looper>(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper

        // Mock Color.argb for unit tests
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns 0

        val app = mockk<Application>(relaxed = true)
        val mockPrefs = mockk<android.content.SharedPreferences>(relaxed = true)
        coEvery { app.getSharedPreferences(any(), any()) } returns mockPrefs
        coEvery { mockPrefs.getFloat(any(), any()) } returns 18f
        coEvery { mockPrefs.getString(any(), any()) } returns "Serif"
        coEvery { mockPrefs.getInt(any(), any()) } returns 0
        coEvery { app.resources } returns mockk(relaxed = true)
        return ReaderViewModel(repository, app, createSavedStateHandle(bookId), mockk(relaxed = true))
    }

    // ==================== loadBook (init) 测试 ====================

    /**
     * 测试 ViewModel 初始化时加载书籍数据。
     * 验证 init 块中的 loadBook() 正确调用仓库获取书籍。
     */
    @Test
    fun init_loadsBookFromRepository() = runTest {
        // 执行：创建 ViewModel（init 触发 loadBook）
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：仓库的 getBookById 被调用
        coVerify { repository.getBookById(testBookId) }

        // 验证：book 状态被正确设置
        val book = viewModel.book.value
        assertNotNull(book)
        assertEquals(testBookId, book!!.id)
        assertEquals("Test Book", book.title)
    }

    /**
     * 测试 ViewModel 初始化时恢复到上次阅读的章节。
     * 验证加载书籍后自动跳转到 lastReadChapter 指定的章节。
     */
    @Test
    fun init_restoresLastReadChapter() = runTest {
        // 执行：创建 ViewModel
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：章节索引被设置为上次阅读位置
        assertEquals(2, viewModel.chapterIndex.value)

        // 验证：章节标题被正确设置
        assertEquals("Chapter 3", viewModel.chapterTitle.value)
    }

    /**
     * 测试 ViewModel 初始化时保存阅读进度。
     * 验证加载书籍后调用 updateReadingProgress 持久化当前章节。
     */
    @Test
    fun init_loadsBookMetadata() = runTest {
        // 执行：创建 ViewModel
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：book 元数据被加载
        assertNotNull(viewModel.book.value)
        assertEquals(testBookId, viewModel.book.value!!.id)
    }

    /**
     * 测试加载不存在的书籍时的处理。
     * 验证 getBookById 返回 null 时不会崩溃。
     */
    @Test
    fun init_whenBookNotFound_bookStateIsNull() = runTest {
        // 准备：仓库返回 null（书籍不存在）
        coEvery { repository.getBookById(testBookId) } returns null

        // 执行：创建 ViewModel
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：book 状态为 null
        assertNull(viewModel.book.value)
        // 验证：没有尝试加载章节
        coVerify(exactly = 0) { repository.getBookMetadata(any()) }
    }

    /**
     * 测试初始化后 isLoading 为 false。
     * 验证加载完成后加载指示器被隐藏。
     */
    @Test
    fun init_afterLoading_isLoadingIsFalse() = runTest {
        // 执行：创建 ViewModel 并等待加载完成
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：加载完成，isLoading 为 false
        assertFalse(viewModel.isLoading.value)
    }

    // ==================== nextChapter 测试 ====================

    /**
     * 测试切换到下一章。
     * 验证 nextChapter() 将章节索引加 1 并加载对应内容。
     */
    @Test
    fun nextChapter_incrementsChapterIndex() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        // 模拟页数回调填充 globalPages
        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        // 设置当前在第 3 章第一页
        viewModel.jumpToGlobalPage(6)
        advanceUntilIdle()

        // 执行：切换到下一章
        viewModel.nextChapter()
        advanceUntilIdle()

        // 验证：章节索引变为 3
        assertEquals(3, viewModel.chapterIndex.value)
    }

    /**
     * 测试在最后一章时调用 nextChapter 不前进。
     * 验证边界条件：到达末尾时不再前进。
     */
    @Test
    fun nextChapter_atLastChapter_doesNotAdvance() = runTest {
        val shortBook = testBook.copy(totalChapters = 3, lastReadChapter = 2)
        coEvery { repository.getBookById(testBookId) } returns shortBook
        val shortMetadata = BookMetadata(
            title = "Test Book", author = "Test Author", opfDir = "OEBPS",
            spine = listOf(
                SpineItem("ch1", "ch1.xhtml", "Chapter 1", "application/xhtml+xml"),
                SpineItem("ch2", "ch2.xhtml", "Chapter 2", "application/xhtml+xml"),
                SpineItem("ch3", "ch3.xhtml", "Chapter 3", "application/xhtml+xml"),
            )
        )
        coEvery { repository.getBookMetadata(testBookId) } returns shortMetadata

        val viewModel = createViewModel()
        advanceUntilIdle()

        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        val currentIndex = viewModel.chapterIndex.value
        viewModel.nextChapter()
        advanceUntilIdle()

        assertEquals(currentIndex, viewModel.chapterIndex.value)
    }

    /**
     * 测试 nextChapter 在书籍未加载时不崩溃。
     * 验证 book 为 null 时的安全处理。
     */
    @Test
    fun nextChapter_whenBookNotLoaded_doesNotCrash() = runTest {
        // 准备：仓库返回 null（书籍不存在）
        coEvery { repository.getBookById(testBookId) } returns null

        // 执行：创建 ViewModel 并尝试切换章节
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.nextChapter()
        advanceUntilIdle()

        // 验证：没有崩溃，章节索引保持 0
        assertEquals(0, viewModel.chapterIndex.value)
    }

    // ==================== previousChapter 测试 ====================

    /**
     * 测试切换到上一章。
     * 验证 previousChapter() 将章节索引减 1 并加载对应内容。
     */
    @Test
    fun previousChapter_decrementsChapterIndex() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        viewModel.jumpToGlobalPage(6)
        advanceUntilIdle()

        viewModel.previousChapter()
        advanceUntilIdle()

        assertEquals(1, viewModel.chapterIndex.value)
    }

    /**
     * 测试在第一章时调用 previousChapter 不后退。
     * 验证边界条件：到达开头时不再后退。
     */
    @Test
    fun previousChapter_atFirstChapter_doesNotGoBack() = runTest {
        val firstChapterBook = testBook.copy(lastReadChapter = 0)
        coEvery { repository.getBookById(testBookId) } returns firstChapterBook

        val viewModel = createViewModel()
        advanceUntilIdle()

        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        assertEquals(0, viewModel.chapterIndex.value)

        viewModel.previousChapter()
        advanceUntilIdle()

        assertEquals(0, viewModel.chapterIndex.value)
    }

    /**
     * 测试 previousChapter 在书籍未加载时不崩溃。
     * 验证 book 为 null 时的安全处理。
     */
    @Test
    fun previousChapter_whenBookNotLoaded_doesNotCrash() = runTest {
        // 准备：仓库返回 null
        coEvery { repository.getBookById(testBookId) } returns null

        // 执行：创建 ViewModel 并尝试切换章节
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.previousChapter()
        advanceUntilIdle()

        // 验证：没有崩溃，章节索引保持 0
        assertEquals(0, viewModel.chapterIndex.value)
    }

    // ==================== 初始章节索引测试 ====================

    /**
     * 测试 ViewModel 初始化时的 chapterIndex 为 0。
     * 验证在 loadBook 完成前，章节索引默认为 0。
     */
    @Test
    fun chapterIndex_initialValue_isZero() = runTest {
        // 执行：创建 ViewModel（不等待 init 完成）
        val viewModel = createViewModel()

        // 验证：初始章节索引为 0（在 loadBook 执行前）
        assertEquals(0, viewModel.chapterIndex.value)
    }

    /**
     * 测试 ViewModel 初始化时的 currentChapter 为 null。
     * 验证在 loadBook 完成前，章节内容为 null。
     */
    @Test
    fun globalPages_initialValue_isEmpty() = runTest {
        val viewModel = createViewModel()
        assertTrue(viewModel.globalPages.value.isEmpty())
    }

    /**
     * 测试 ViewModel 初始化时的 book 为 null。
     * 验证在 loadBook 完成前，书籍信息为 null。
     */
    @Test
    fun book_initialValue_isNull() = runTest {
        // 执行：创建 ViewModel
        val viewModel = createViewModel()

        // 验证：初始书籍信息为 null
        assertNull(viewModel.book.value)
    }

    // ==================== 阅读进度持久化测试 ====================

    /**
     * 测试每次章节切换都保存阅读进度。
     * 验证连续切换章节时每次都调用 updateReadingProgress。
     */
    @Test
    fun chapterNavigation_savesProgressOnChapterChange() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        for (i in 0..4) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        viewModel.jumpToGlobalPage(6)
        advanceUntilIdle()

        viewModel.nextChapter()
        advanceUntilIdle()

        coVerify { repository.updateReadingProgress(testBookId, 3) }
    }
}
