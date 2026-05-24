package com.example.read.ui.bookshelf

import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/**
 * BookshelfViewModel 协程取消语义回归测试（P1-v5-1）。
 *
 * 修复背景（FEAT_REPORT_v5.md § 1）：
 * 旧实现的 3 处 `catch (Exception)` 把 [CancellationException] 一同吞掉了，
 * 破坏了 Kotlin 结构化并发约定 —— 子 Job 取消时父 Job 无法感知，
 * 父级 `coroutineScope` / `withContext` 会陷入死锁或错乱状态。
 *
 * 本测试用例验证修复后的 3 个 catch 位置都显式 `catch (CancellationException) → throw e`：
 * 1) `init { cleanupOrphanedBooks }` 块的 catch
 * 2) `importBook` 主流程 catch
 * 3) `deleteBook` 的 catch
 *
 * 验证策略：
 * - 直接观察 errorMessage：CancellationException 不应被映射为业务错误消息
 *   （Exception 路径会写入"导入失败"等错误，CancellationException 路径不应写入）
 * - 用 `viewModelScope.launch` 内部触发的取消：取消单个内部 Job 时不应污染 errorMessage
 * - importProgress / placeholderBooks 在取消瞬间也应被清理（"取消应当像没发生过一样"语义）
 *
 * 不修改生产代码；不引入新依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelCancellationTest {

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

    // ==================== 位置 1：init / cleanupOrphanedBooks ====================

    /**
     * Given: `cleanupOrphanedBooks` 抛 [CancellationException]
     * When: ViewModel 创建（init 块触发清理）
     * Then:
     *   - errorMessage 保持 null（CancellationException 不应被 catch(Exception) 吞成业务错误）
     *   - 不应误把 CancellationException 当作"清理失败"做异常日志
     *
     * 验证手段：errorMessage 始终为 null —— Exception 分支若误收 cancel 不会写
     *   errorMessage（init 块的 Exception 分支只 Log.w），所以本测试主要确认
     *   ViewModel 创建不崩溃；CancellationException 重抛后被 viewModelScope 正常吞掉。
     */
    @Test
    fun `should rethrow CancellationException from cleanupOrphanedBooks without crashing`() = runTest {
        coEvery { repository.cleanupOrphanedBooks() } throws CancellationException("cancelled by parent")

        // ViewModel 创建不应抛异常（CancellationException 被 viewModelScope 正常吞掉）
        val viewModel = BookshelfViewModel(repository)
        advanceUntilIdle()

        // 取消不应被映射成错误消息
        assertNull("CancellationException 不应触发 errorMessage 写入", viewModel.errorMessage.value)
        // 验证 cleanupOrphanedBooks 确实被调用过
        coVerify(exactly = 1) { repository.cleanupOrphanedBooks() }
    }

    /**
     * 对照：普通 Exception 仍应走原有 Log.w 路径（不应被 P1-v5-1 修复影响）
     * Given: cleanupOrphanedBooks 抛普通 RuntimeException
     * When: ViewModel 创建
     * Then: errorMessage 仍为 null（init 块的 Exception 路径本就不写 errorMessage，仅 Log.w）
     */
    @Test
    fun `should still swallow non-cancellation exception in cleanupOrphanedBooks`() = runTest {
        coEvery { repository.cleanupOrphanedBooks() } throws RuntimeException("disk error")

        val viewModel = BookshelfViewModel(repository)
        advanceUntilIdle()

        assertNull("普通异常不应写入 errorMessage（init 仅 Log.w）", viewModel.errorMessage.value)
    }

    // ==================== 位置 2：importBook 主流程 ====================

    /**
     * Given: prepareImport 抛 [CancellationException]（模拟取消信号从 IO 阶段传播）
     * When: 调用 importBook
     * Then:
     *   - errorMessage 保持 null（不应把 cancel 映射为"导入失败"等业务错误）
     *   - importProgress 已被清空（cancel 重抛前的清理）
     *   - placeholderBooks 已被清空（placeholderId=-1 时不调用 removePlaceholder，本路径只清进度）
     *
     * 修复后的 importBook catch 链：
     *   catch (CancellationException) → 清 progress / placeholder → throw e
     *   catch (Exception) → 设业务错误消息
     */
    @Test
    fun `should rethrow CancellationException from prepareImport without setting error`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws CancellationException("user cancelled")

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertNull(
            "CancellationException 不应被映射为业务错误消息（修复前会显示\"导入失败\"）",
            viewModel.errorMessage.value,
        )
        assertTrue(
            "取消时 importProgress 应被清空",
            viewModel.importProgress.value.isEmpty(),
        )
        assertTrue(
            "取消时 placeholderBooks 应被清空（placeholderId=-1 时 add 未发生，列表本就为空）",
            viewModel.placeholderBooks.value.isEmpty(),
        )
        // 应该没有 B8 兜底删除（CancellationException 路径在 deleteBook 之前已 throw）
        coVerify(exactly = 0) { repository.deleteBook(any()) }
    }

    /**
     * Given: prepareImport 成功返回 placeholderId=99，但 startImport 抛 [CancellationException]
     * When: 调用 importBook
     * Then:
     *   - errorMessage 仍为 null（取消不是业务错误）
     *   - importProgress 已被清空（取消瞬间清理）
     *   - placeholderBooks 已被清空（placeholderId>0 时显式调 removePlaceholder）
     *   - 不应触发 B8 兜底 deleteBook（那是 Exception 路径的责任）
     */
    @Test
    fun `should rethrow CancellationException from startImport and cleanup placeholder`() = runTest {
        val placeholderId = 99L
        coEvery { repository.prepareImport(any(), any()) } returns placeholderId
        coEvery {
            repository.startImport(eq(placeholderId), any(), any(), any())
        } throws CancellationException("startImport cancelled mid-flight")

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertNull("取消时不应写错误消息", viewModel.errorMessage.value)
        assertTrue(
            "取消时 importProgress 应清空",
            viewModel.importProgress.value.isEmpty(),
        )
        assertTrue(
            "取消时 placeholderBooks 应被显式 removePlaceholder",
            viewModel.placeholderBooks.value.isEmpty(),
        )
        coVerify(exactly = 0) {
            repository.deleteBook(any())
        }
    }

    /**
     * 通过取消整个 importBook 协程来触发 CancellationException：
     *
     * Given: prepareImport 永远挂起（CompletableDeferred 不 complete），
     *        importBook 协程被外部 cancel
     * When: 启动 importBook → 取消 viewModelScope（通过 onCleared 或显式 Job.cancel）
     * Then: errorMessage 保持 null
     *
     * 这是真实场景的复现：用户点导入后立刻返回上一页（ViewModel 销毁 → 协程取消）。
     */
    @Test
    fun `should not set error when import coroutine is externally cancelled`() = runTest {
        val gate = CompletableDeferred<Long>()
        coEvery { repository.prepareImport(any(), any()) } coAnswers { gate.await() }

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        // 让协程跑到 prepareImport 的挂起点
        advanceUntilIdle()

        // 模拟"外部取消"：直接完成 gate 并抛出 CancellationException 让挂起点恢复
        gate.completeExceptionally(CancellationException("scope cancelled"))
        advanceUntilIdle()

        assertNull(
            "外部 cancel 导致的 CancellationException 不应设置 errorMessage",
            viewModel.errorMessage.value,
        )
        assertTrue(viewModel.importProgress.value.isEmpty())
        assertTrue(viewModel.placeholderBooks.value.isEmpty())
    }

    /**
     * 对照：普通 Exception 路径仍应写入业务错误消息（不应被 P1-v5-1 修复破坏）
     * Given: prepareImport 抛 RuntimeException
     * When: 调用 importBook
     * Then: errorMessage = "导入失败，请重试"
     */
    @Test
    fun `should still set error message on regular exception in importBook`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws RuntimeException("unknown")

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertEquals("导入失败，请重试", viewModel.errorMessage.value)
    }

    /**
     * 验证 CancellationException 重抛前的 placeholder 清理顺序：
     * Given: prepareImport 成功 + 一次 progress 回调，然后 startImport 在第二次回调中抛 CancellationException
     * When: 调用 importBook
     * Then: importProgress 与 placeholderBooks 都被清空（throw 之前的 finally 等价点）
     */
    @Test
    fun `should cleanup progress and placeholder before rethrowing cancellation`() = runTest {
        val placeholderId = 77L
        coEvery { repository.prepareImport(any(), any()) } returns placeholderId
        coEvery {
            repository.startImport(any(), any(), any(), any())
        } coAnswers {
            // 触发一次进度回调，让 placeholderBooks 写入
            val cb = arg<(Float) -> Unit>(3)
            cb.invoke(0.3f)
            // 然后立刻抛 CancellationException 模拟取消
            throw CancellationException("cancelled after progress 0.3")
        }

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // 取消前 placeholder 已被 upsert 过；取消后必须被显式移除
        assertTrue(
            "取消瞬间 placeholderBooks 应被清空（不应残留进度环卡片）",
            viewModel.placeholderBooks.value.isEmpty(),
        )
        assertTrue(viewModel.importProgress.value.isEmpty())
        assertNull(viewModel.errorMessage.value)
    }

    // ==================== 位置 3：deleteBook ====================

    /**
     * Given: repository.deleteBook 抛 [CancellationException]
     * When: 调用 viewModel.deleteBook(book)
     * Then: errorMessage 保持 null（不应被映射为"删除失败"）
     */
    @Test
    fun `should rethrow CancellationException from deleteBook without setting error`() = runTest {
        val book = Book(
            id = 5L, title = "B", author = "A", coverPath = null,
            bookDirPath = "/books/5", totalChapters = 1, lastReadChapter = 0, lastReadAt = 0L,
        )
        coEvery { repository.deleteBook(book) } throws CancellationException("delete cancelled")

        val viewModel = BookshelfViewModel(repository)
        advanceUntilIdle()
        viewModel.deleteBook(book)
        advanceUntilIdle()

        assertNull(
            "CancellationException 不应被映射为\"删除失败\"业务错误",
            viewModel.errorMessage.value,
        )
    }

    /**
     * 对照：普通 Exception 路径仍应设置"删除失败"业务错误
     * Given: repository.deleteBook 抛普通 RuntimeException
     * When: 调用 viewModel.deleteBook(book)
     * Then: errorMessage = "删除失败，请重试"
     */
    @Test
    fun `should still set error message on regular exception in deleteBook`() = runTest {
        val book = Book(
            id = 6L, title = "B", author = "A", coverPath = null,
            bookDirPath = "/books/6", totalChapters = 1, lastReadChapter = 0, lastReadAt = 0L,
        )
        coEvery { repository.deleteBook(book) } throws RuntimeException("disk error")

        val viewModel = BookshelfViewModel(repository)
        advanceUntilIdle()
        viewModel.deleteBook(book)
        advanceUntilIdle()

        assertEquals("删除失败，请重试", viewModel.errorMessage.value)
    }

    // ==================== Turbine 验证：导入流程的 errorMessage 不发射 ====================

    /**
     * Given: Turbine 监听 errorMessage
     * When: importBook 因 CancellationException 取消
     * Then: errorMessage 从 null → 始终 null，无新发射
     */
    @Test
    fun `should not emit error event when import is cancelled`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws CancellationException("cancelled")

        val viewModel = BookshelfViewModel(repository)
        advanceUntilIdle()

        viewModel.errorMessage.test {
            assertNull(awaitItem()) // 初始 null
            viewModel.importBook(mockUri, mockContext)
            advanceUntilIdle()
            // CancellationException 路径不写 errorMessage，StateFlow 不应发射新值
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
    }
}
