package com.example.read.ui.bookshelf

import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
 * BookshelfViewModel 的单元测试。
 *
 * 测试范围：
 * - books 状态流的初始值和数据变化
 * - importProgress 在导入过程中的状态变化
 * - errorMessage 在正常流程和异常流程中的行为
 * - deleteBook 的调用和错误处理
 *
 * 测试策略：
 * - 使用 MockK 模拟 BookRepository 接口
 * - 使用 kotlinx-coroutines-test 的 TestDispatcher 控制协程执行
 * - 验证 ViewModel 内部状态流的值变化
 *
 * 注意：BookshelfViewModel 的 books 属性通过 stateIn 将 Room Flow 转为热 Flow，
 * 使用 WhileSubscribed(5000) 策略，测试中需要主动收集才能触发数据发射。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

    /** 模拟的书籍仓库，用于控制返回数据和验证调用 */
    private lateinit var repository: BookRepository

    /** 测试用的协程调度器，控制协程执行时机 */
    private val testDispatcher = StandardTestDispatcher()

    /** 被测的 ViewModel 实例 */
    private lateinit var viewModel: BookshelfViewModel

    /** 测试用的示例书籍数据 */
    private val testBook1 = Book(
        id = 1,
        title = "Test Book 1",
        author = "Author 1",
        coverPath = "/covers/test1.jpg",
        bookDirPath = "/epubs/test1.epub",
        totalChapters = 10,
        lastReadChapter = 5,
        lastReadAt = 1000L,
    )

    private val testBook2 = Book(
        id = 2,
        title = "Test Book 2",
        author = "Author 2",
        coverPath = null,
        bookDirPath = "/epubs/test2.epub",
        totalChapters = 20,
        lastReadChapter = 0,
        lastReadAt = 0L,
    )

    /**
     * 每个测试前的初始化。
     * 设置 Main dispatcher 为测试调度器，确保协程在测试线程执行。
     */
    @Before
    fun setUp() {
        // 设置 Main dispatcher 为测试调度器，使 viewModelScope.launch 在测试线程执行
        Dispatchers.setMain(testDispatcher)

        // 创建模拟仓库，默认返回空书籍列表
        repository = mockk(relaxed = true)
        every { repository.getAllBooks() } returns flowOf(emptyList())
    }

    /**
     * 每个测试后的清理。
     * 重置 Main dispatcher，防止影响其他测试。
     */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== 初始状态测试 ====================

    /**
     * 测试 ViewModel 初始化后 books 为空列表。
     * 验证在没有数据时，书架页面显示空状态。
     */
    @Test
    fun books_initialState_isEmptyList() = runTest {
        // 准备：仓库返回空列表（已在 setUp 中配置）

        // 执行：创建 ViewModel
        viewModel = BookshelfViewModel(repository)

        // 验证：初始状态为空列表
        assertEquals(emptyList<Book>(), viewModel.books.value)
    }

    /**
     * 测试 ViewModel 初始化后 importProgress 为空。
     * 验证页面加载时不会错误显示加载指示器。
     */
    @Test
    fun importProgress_initialState_isEmpty() = runTest {
        // 执行：创建 ViewModel
        viewModel = BookshelfViewModel(repository)

        // 验证：初始状态为空 Map
        assertTrue(viewModel.importProgress.value.isEmpty())
    }

    /**
     * 测试 ViewModel 初始化后 errorMessage 为 null。
     * 验证页面加载时不会错误显示错误提示。
     */
    @Test
    fun errorMessage_initialState_isNull() = runTest {
        // 执行：创建 ViewModel
        viewModel = BookshelfViewModel(repository)

        // 验证：初始状态为 null
        assertNull(viewModel.errorMessage.value)
    }

    // ==================== books 数据流测试 ====================

    /**
     * 测试仓库返回书籍列表时，books 状态正确更新。
     * 验证 Room Flow 的数据正确传递到 ViewModel 的 StateFlow。
     *
     * 注意：stateIn 配合 WhileSubscribed 策略时，需要有活跃订阅者才会开始收集上游 Flow。
     * 测试中必须主动 collect StateFlow 才能触发上游数据发射。
     */
    @Test
    fun books_whenRepositoryEmits_updatesCorrectly() = runTest {
        // 准备：仓库返回包含两本书的列表
        val bookList = listOf(testBook1, testBook2)
        every { repository.getAllBooks() } returns flowOf(bookList)

        // 执行：创建 ViewModel
        viewModel = BookshelfViewModel(repository)

        // 主动收集 StateFlow 以触发 WhileSubscribed 上游订阅。
        // WhileSubscribed(5000) 要求有活跃订阅者才会开始收集上游 Flow，
        // 仅读取 .value 不会触发上游订阅。
        val collected = mutableListOf<List<Book>>()
        val collectJob = launch { viewModel.books.collect { collected.add(it) } }
        advanceUntilIdle()

        // 验证：收集到的数据包含两本书
        assertTrue(collected.isNotEmpty())
        val books = collected.last()
        assertEquals(2, books.size)
        assertEquals("Test Book 1", books[0].title)
        assertEquals("Test Book 2", books[1].title)

        collectJob.cancel()
    }

    // ==================== deleteBook 测试 ====================

    /**
     * 测试删除书籍时调用仓库的 deleteBook 方法。
     * 验证 ViewModel 正确委托删除操作给仓库。
     */
    @Test
    fun deleteBook_callsRepositoryDeleteBook() = runTest {
        // 准备：创建 ViewModel
        viewModel = BookshelfViewModel(repository)

        // 执行：删除书籍
        viewModel.deleteBook(testBook1)
        advanceUntilIdle()

        // 验证：仓库的 deleteBook 被调用，参数正确
        coVerify { repository.deleteBook(testBook1) }
    }

    /**
     * 测试删除书籍失败时设置错误信息。
     * 验证异常被捕获并转换为用户可见的错误消息。
     *
     * ViewModel 的错误处理策略：不向用户暴露内部异常细节（如文件路径），
     * 只显示通用的友好提示文案 "删除失败，请重试"。
     */
    @Test
    fun deleteBook_whenException_setsErrorMessage() = runTest {
        // 准备：仓库抛出异常
        coEvery { repository.deleteBook(any()) } throws RuntimeException("File not found")

        // 执行：创建 ViewModel 并删除书籍
        viewModel = BookshelfViewModel(repository)
        viewModel.deleteBook(testBook1)
        advanceUntilIdle()

        // 验证：错误信息被设置为 ViewModel 中的友好提示文案
        val error = viewModel.errorMessage.value
        assertNotNull(error)
        assertTrue(error!!.contains("删除失败"))
    }

    // ==================== clearError 测试 ====================

    /**
     * 测试清除错误信息。
     * 验证 clearError() 将 errorMessage 重置为 null。
     */
    @Test
    fun clearError_setsErrorMessageToNull() = runTest {
        // 准备：先触发一个错误
        coEvery { repository.deleteBook(any()) } throws RuntimeException("Error")
        viewModel = BookshelfViewModel(repository)
        viewModel.deleteBook(testBook1)
        advanceUntilIdle()

        // 验证：错误信息存在
        assertNotNull(viewModel.errorMessage.value)

        // 执行：清除错误
        viewModel.clearError()

        // 验证：错误信息被清除
        assertNull(viewModel.errorMessage.value)
    }

    /**
     * 测试没有错误时调用 clearError 不会崩溃。
     * 验证幂等操作的安全性。
     */
    @Test
    fun clearError_whenNoError_doesNotCrash() = runTest {
        // 准备：创建 ViewModel（无错误）
        viewModel = BookshelfViewModel(repository)

        // 执行：调用 clearError
        viewModel.clearError()

        // 验证：errorMessage 仍为 null
        assertNull(viewModel.errorMessage.value)
    }
}
