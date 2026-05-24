package com.example.read.ui.bookshelf

import android.content.Context
import android.net.Uri
import com.example.read.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * BookshelfViewModel 导入流程补强测试。
 *
 * 覆盖 BookshelfViewModelTest 未覆盖的分支：
 * - importBook 成功路径：调用 prepareImport + startImport，进度归零
 * - importBook 失败路径：根据异常类型设置不同错误消息（4 个分支）
 * - cleanupOrphanedBooks 在 init 时被调用
 * - importBook 期间 importProgress 状态正确更新
 *
 * 不修改生产代码，发现潜在 bug 写到报告中。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelImportTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val mockUri = mockk<Uri>(relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.getAllBooks() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== init: cleanupOrphanedBooks ====================

    /**
     * Given: ViewModel 被创建
     * When: init 块执行
     * Then: repository.cleanupOrphanedBooks 被调用一次
     */
    @Test
    fun `should call cleanupOrphanedBooks once on init`() = runTest {
        BookshelfViewModel(repository)
        advanceUntilIdle()
        coVerify(exactly = 1) { repository.cleanupOrphanedBooks() }
    }

    /**
     * Given: cleanupOrphanedBooks 抛异常
     * When: ViewModel 被创建
     * Then: 不暴露错误，errorMessage 保持 null
     */
    @Test
    fun `should swallow cleanupOrphanedBooks failure silently`() = runTest {
        coEvery { repository.cleanupOrphanedBooks() } throws RuntimeException("cleanup boom")
        val viewModel = BookshelfViewModel(repository)
        advanceUntilIdle()
        // 清理失败不应影响 UI
        assertNull(viewModel.errorMessage.value)
    }

    // ==================== importBook 成功路径 ====================

    /**
     * Given: prepareImport 返回 bookId=42，startImport 成功
     * When: 调用 importBook
     * Then: importProgress 最终被清空，无错误信息
     */
    @Test
    fun `should clear progress when import succeeds`() = runTest {
        coEvery { repository.prepareImport(mockUri, mockContext) } returns 42L
        // startImport 接受回调，调用三次 progress
        val progressSlot = slot<(Float) -> Unit>()
        coEvery {
            repository.startImport(eq(42L), eq(mockUri), eq(mockContext), capture(progressSlot))
        } answers {
            progressSlot.captured.invoke(0.3f)
            progressSlot.captured.invoke(0.7f)
            progressSlot.captured.invoke(1.0f)
            Unit
        }

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // 导入完成后清空
        assertTrue(viewModel.importProgress.value.isEmpty())
        assertNull(viewModel.errorMessage.value)
        coVerify { repository.prepareImport(mockUri, mockContext) }
        coVerify { repository.startImport(eq(42L), eq(mockUri), eq(mockContext), any()) }
    }

    // ==================== importBook 失败路径 ====================

    /**
     * Given: prepareImport 抛 IllegalArgumentException("EPUB 格式无效")
     * When: 调用 importBook
     * Then: errorMessage = "EPUB 格式无效"，进度被清空
     */
    @Test
    fun `should show illegal argument message when import throws IllegalArgumentException`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws IllegalArgumentException("EPUB 格式无效")
        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertEquals("EPUB 格式无效", viewModel.errorMessage.value)
        assertTrue(viewModel.importProgress.value.isEmpty())
    }

    /**
     * Given: prepareImport 抛 IllegalArgumentException 但消息为 null
     * When: 调用 importBook
     * Then: 使用兜底消息 "EPUB 文件格式无效"
     */
    @Test
    fun `should use fallback message when IllegalArgumentException has null message`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws IllegalArgumentException(null as String?)
        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertEquals("EPUB 文件格式无效", viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 抛 IOException
     * When: 调用 importBook
     * Then: errorMessage 提示文件读写失败
     */
    @Test
    fun `should show IO error message when import throws IOException`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws IOException("disk full")
        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertEquals("文件读写失败，请检查存储空间是否充足", viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 抛 SecurityException
     * When: 调用 importBook
     * Then: errorMessage 提示 EPUB 文件结构异常
     */
    @Test
    fun `should show security message when import throws SecurityException`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws SecurityException("ZIP Slip")
        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertEquals("EPUB 文件结构异常，无法安全解压", viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 抛未知异常（RuntimeException）
     * When: 调用 importBook
     * Then: errorMessage 使用通用兜底文案 "导入失败，请重试"
     */
    @Test
    fun `should show generic message when import throws unknown exception`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws RuntimeException("???")
        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertEquals("导入失败，请重试", viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 成功，startImport 抛异常
     * When: 调用 importBook
     * Then: importProgress 被清空，errorMessage 设置
     */
    @Test
    fun `should clear progress and set error when startImport fails`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } returns 1L
        coEvery { repository.startImport(any(), any(), any(), any()) } throws RuntimeException("unpack fail")

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertTrue(viewModel.importProgress.value.isEmpty())
        assertNotNull(viewModel.errorMessage.value)
    }

    // ==================== B8: 失败后兜底 deleteBook 占位记录 ====================

    /**
     * B8 回归测试：startImport 抛异常时，ViewModel 显式调用 deleteBook 兜底
     *           清理 prepareImport 已写入的占位记录。
     *
     * Given: prepareImport 返回 placeholderId=99，但 startImport 抛异常
     *        且 repository.getBookById(99L) 返回一个对应占位记录
     * When: 调用 importBook
     * Then: 显式调用 repository.deleteBook(placeholder) 至少一次（兜底清理）
     */
    @Test
    fun `should explicitly delete placeholder via deleteBook when startImport fails`() = runTest {
        val placeholderId = 99L
        val placeholderBook = com.example.read.domain.model.Book(
            id = placeholderId, title = "占位", author = "作者", coverPath = null,
            bookDirPath = "PREPARING_xxx", totalChapters = 0,
        )
        coEvery { repository.prepareImport(any(), any()) } returns placeholderId
        coEvery {
            repository.startImport(eq(placeholderId), any(), any(), any())
        } throws RuntimeException("unpack fail")
        coEvery { repository.getBookById(placeholderId) } returns placeholderBook

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // B8 关键断言：catch 中显式调用了 deleteBook 兜底
        coVerify(atLeast = 1) { repository.deleteBook(placeholderBook) }
        // 错误消息已设置
        assertNotNull(viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 抛异常（placeholderId 未被赋值，保持 -1L）
     * When: 调用 importBook
     * Then: 不会调用 deleteBook（避免删错记录）
     */
    @Test
    fun `should not call deleteBook when prepareImport itself fails`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws RuntimeException("prepare fail")
        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // placeholderId 仍为 -1L，B8 catch 守卫不应触发 deleteBook
        coVerify(exactly = 0) { repository.deleteBook(any()) }
        assertNotNull(viewModel.errorMessage.value)
    }
}
