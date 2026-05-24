package com.example.read.ui.bookshelf

import android.content.Context
import android.net.Uri
import app.cash.turbine.test
import com.example.read.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import java.io.IOException

/**
 * BookshelfViewModel placeholderBooks StateFlow 回归测试（P1-NEW-1）。
 *
 * 修复要点（详见 FIX_REPORT_v3.md Fix-1）：
 * - P1-1 让 BookDao.getAllBooks() 在 SQL 层过滤掉 `PREPARING_*` 占位记录，
 *   导致书架 UI 在 prepareImport 完成后到 startImport 完成前的整段时间
 *   完全静默（看不到导入进度环）。
 * - 修复方案：ViewModel 新增独立的 placeholderBooks StateFlow，
 *   导入开始时立即追加占位，结束（成功 / 失败）后移除。
 *
 * 本测试覆盖：
 * - importBook 成功路径：prepareImport 后立即出现 placeholder；startImport 完成后被移除
 * - importBook 异常路径（startImport 抛）：placeholder 被移除（不残留）
 * - importBook 异常路径（prepareImport 抛 IllegalArgumentException）：placeholder 列表
 *   保持空（因为 placeholderId 尚未被赋值）
 * - placeholder.progress 字段在导入回调过程中随 progress 同步联动
 * - 并发场景：两次 importBook 应同时出现两条 placeholder
 *
 * 工具：Turbine `test {}` 直接观察 StateFlow 发射序列。
 *
 * 不修改生产代码；不引入新依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelPlaceholderTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val mockUri = mockk<Uri>(relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        // books 流默认为空：确保 init 块的 cleanupOrphanedBooks 不会异常阻塞
        every { repository.getAllBooks() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== 成功路径：placeholder 生命周期 ====================

    /**
     * Given: prepareImport 返回 placeholderId=42；startImport 通过回调上报 progress 后成功
     * When: 调用 importBook(uri, context) 并等待协程完成
     * Then:
     *   1) 完成态：placeholderBooks 应被清空（成功路径不残留）
     *   2) 阻塞中间态：placeholderBooks 含一项（id=42）
     *
     * 实现细节：用 CompletableDeferred 把 startImport 阻塞在指定阶段，
     * 在阻塞期间断言 placeholder 存在；释放 gate 后断言已被清除。
     */
    @Test
    fun `should add placeholder after prepareImport and remove after startImport succeeds`() = runTest {
        coEvery { repository.prepareImport(mockUri, mockContext) } returns 42L
        val pauseGate = CompletableDeferred<Unit>()
        val progressSlot = slot<(Float) -> Unit>()
        coEvery {
            repository.startImport(eq(42L), any(), any(), capture(progressSlot))
        } coAnswers {
            progressSlot.captured.invoke(0.5f)
            pauseGate.await()
            progressSlot.captured.invoke(1.0f)
        }

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // 阻塞中间态：placeholder 应存在，id=42，progress 是最后一次 invoke 的值 0.5f
        val snapshotDuringImport = viewModel.placeholderBooks.value
        assertEquals(
            "startImport 阻塞期间应存在 1 个 placeholder",
            1, snapshotDuringImport.size,
        )
        assertEquals(42L, snapshotDuringImport[0].id)
        assertEquals(0.5f, snapshotDuringImport[0].progress, 0.0001f)

        // 同时 importProgress 也有对应记录
        assertEquals(0.5f, viewModel.importProgress.value[42L]!!, 0.0001f)

        // 解除 gate 让 startImport 走完整流程
        pauseGate.complete(Unit)
        advanceUntilIdle()

        // 完成态：placeholderBooks 被清空
        assertTrue(
            "成功路径完成后 placeholderBooks 应被清空",
            viewModel.placeholderBooks.value.isEmpty(),
        )
        // importProgress 也被清空
        assertTrue(viewModel.importProgress.value.isEmpty())
        // 成功路径不应留下 errorMessage
        assertNull(viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 返回 42；startImport 立即同步成功（不阻塞）
     * When: 调用 importBook，并用 Turbine 监听整个生命周期
     * Then: Turbine 应观察到至少一次"非空"的中间态发射，最终回到空状态
     *
     * 用 Turbine 验证 StateFlow 至少产生过 [placeholder] 的中间发射，
     * 而不是 prepareImport / startImport 全部 inline 完成后只剩"empty → empty"。
     */
    @Test
    fun `should emit non-empty placeholder list during import lifecycle via Turbine`() = runTest {
        coEvery { repository.prepareImport(mockUri, mockContext) } returns 42L
        coEvery { repository.startImport(eq(42L), any(), any(), any()) } returns Unit

        val viewModel = BookshelfViewModel(repository)

        // 收集所有发射的快照
        val emitted = mutableListOf<List<PlaceholderBook>>()
        viewModel.placeholderBooks.test {
            // 初始：空
            emitted.add(awaitItem())
            viewModel.importBook(mockUri, mockContext)
            advanceUntilIdle()
            // 尝试消费所有后续发射；StateFlow 在稳定后不再发射，runCatching 兜住超时
            repeat(6) {
                runCatching { emitted.add(awaitItem()) }.getOrNull() ?: return@repeat
            }
            cancelAndConsumeRemainingEvents()
        }

        assertTrue(
            "应至少观察到一次包含 placeholder id=42 的发射，实际: $emitted",
            emitted.any { snap -> snap.any { it.id == 42L } },
        )
        assertTrue(
            "最终应回到 empty 列表，实际: ${emitted.last()}",
            emitted.last().isEmpty(),
        )
    }

    /**
     * Given: prepareImport 返回 99；startImport 抛 RuntimeException
     * When: 调用 importBook
     * Then: placeholder 在 prepareImport 后出现 → startImport 异常后被移除（不残留）
     */
    @Test
    fun `should remove placeholder when startImport throws`() = runTest {
        coEvery { repository.prepareImport(mockUri, mockContext) } returns 99L
        coEvery {
            repository.startImport(eq(99L), any(), any(), any())
        } throws RuntimeException("unpack fail")
        // 兜底删除路径会调用 getBookById；返回 null 避免 deleteBook 触达
        coEvery { repository.getBookById(99L) } returns null

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // 异常路径完成后：placeholder 被移除
        assertTrue(
            "startImport 异常后 placeholder 应被清空",
            viewModel.placeholderBooks.value.isEmpty(),
        )
        // 异常路径应设置 errorMessage（generic 兜底）
        assertNotNull(viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 本身抛 IllegalArgumentException（placeholderId 保持 -1L）
     * When: 调用 importBook
     * Then: placeholderBooks 始终保持空（因为 placeholderId 未被赋值，没有添加过 placeholder）
     */
    @Test
    fun `should keep placeholderBooks empty when prepareImport throws IllegalArgumentException`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws IllegalArgumentException("EPUB 格式无效")

        val viewModel = BookshelfViewModel(repository)

        viewModel.placeholderBooks.test {
            assertEquals(emptyList<PlaceholderBook>(), awaitItem())

            viewModel.importBook(mockUri, mockContext)
            advanceUntilIdle()

            // 不应有任何新发射（prepareImport 抛异常发生在 upsertPlaceholder 之前）
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }

        // errorMessage 应映射为 IllegalArgumentException 的消息
        assertEquals("EPUB 格式无效", viewModel.errorMessage.value)
    }

    /**
     * Given: prepareImport 抛 IOException（placeholderId 保持 -1L）
     * When: 调用 importBook
     * Then: placeholderBooks 始终保持空
     */
    @Test
    fun `should keep placeholderBooks empty when prepareImport throws IOException`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } throws IOException("disk full")

        val viewModel = BookshelfViewModel(repository)

        viewModel.placeholderBooks.test {
            assertEquals(emptyList<PlaceholderBook>(), awaitItem())

            viewModel.importBook(mockUri, mockContext)
            advanceUntilIdle()

            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
    }

    // ==================== progress 字段联动 ====================

    /**
     * Given: prepareImport 返回 7L；startImport 内多次 progress 回调（0.1f → 0.4f → 0.9f）；
     *        最后通过 gate 阻塞在 0.9f 处，让外层观察"中间态"
     * When: 在 gate 阻塞期间检查 placeholder.progress
     * Then: progress 字段应反映最后一次回调的值（0.9f）；
     *       说明 P1-NEW-1 修复实现了"placeholder.progress 与 importProgress 同步联动"
     */
    @Test
    fun `should update placeholder progress on each callback`() = runTest {
        coEvery { repository.prepareImport(mockUri, mockContext) } returns 7L
        val pauseGate = CompletableDeferred<Unit>()
        val progressSlot = slot<(Float) -> Unit>()
        coEvery {
            repository.startImport(eq(7L), any(), any(), capture(progressSlot))
        } coAnswers {
            progressSlot.captured.invoke(0.1f)
            progressSlot.captured.invoke(0.4f)
            progressSlot.captured.invoke(0.9f)
            pauseGate.await()
        }

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        // 阻塞中间态：progress 应为 0.9f（最后一次 invoke）
        val snapshot = viewModel.placeholderBooks.value
        assertEquals(1, snapshot.size)
        assertEquals(7L, snapshot[0].id)
        assertEquals(
            "placeholder.progress 应同步联动到最后一次 progress 回调",
            0.9f, snapshot[0].progress, 0.0001f,
        )
        // importProgress 也应同步到 0.9f
        assertEquals(0.9f, viewModel.importProgress.value[7L]!!, 0.0001f)

        // 释放 gate 让 startImport 走完
        pauseGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.placeholderBooks.value.isEmpty())
    }

    // ==================== 并发：多次 importBook ====================

    /**
     * Given: 两次连续调用 importBook（uriA → placeholderId=10；uriB → placeholderId=11），
     *        两次 startImport 都在 pauseGate 上阻塞，模拟并发导入
     * When: 在 startImport 阻塞期间观察 placeholderBooks
     * Then: 列表应同时包含 id=10 与 id=11 两条占位
     *
     * 注意点：
     * - prepareImport / startImport 各自走独立的 viewModelScope 协程，互不阻塞
     * - 第二次调用追加而非替换，verify list 由 upsertPlaceholder 维护
     */
    @Test
    fun `should hold two placeholders concurrently for two parallel imports`() = runTest {
        val uriA = mockk<Uri>(relaxed = true)
        val uriB = mockk<Uri>(relaxed = true)

        coEvery { repository.prepareImport(uriA, mockContext) } returns 10L
        coEvery { repository.prepareImport(uriB, mockContext) } returns 11L

        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        coEvery {
            repository.startImport(eq(10L), any(), any(), any())
        } coAnswers { gateA.await() }
        coEvery {
            repository.startImport(eq(11L), any(), any(), any())
        } coAnswers { gateB.await() }

        val viewModel = BookshelfViewModel(repository)

        // 两个导入任务都被 gate 阻塞在 startImport 内部，期间 placeholderBooks 应同时持有两条
        viewModel.importBook(uriA, mockContext)
        viewModel.importBook(uriB, mockContext)
        advanceUntilIdle()

        // 不使用 Turbine 序列断言（并发回调会让中间发射顺序非确定性），
        // 直接读取阻塞期间的最终快照，断言两条 placeholder 共存
        val snapshot = viewModel.placeholderBooks.value
        assertEquals("两次并发导入应共存两个 placeholder", 2, snapshot.size)
        val ids = snapshot.map { it.id }.toSet()
        assertTrue("应包含 placeholder id=10", 10L in ids)
        assertTrue("应包含 placeholder id=11", 11L in ids)

        // 解除两个 gate，让 startImport 完成；最终 placeholderBooks 应清空
        gateA.complete(Unit)
        gateB.complete(Unit)
        advanceUntilIdle()
        assertTrue(
            "所有 startImport 完成后 placeholder 应被清空",
            viewModel.placeholderBooks.value.isEmpty(),
        )
    }

    /**
     * Given: 单次 importBook 成功
     * When: 验证仓库调用顺序与计数
     * Then: prepareImport 1 次 + startImport 1 次（确保 placeholder 添加不阻塞主流程）
     */
    @Test
    fun `should not block main import flow when maintaining placeholderBooks`() = runTest {
        coEvery { repository.prepareImport(any(), any()) } returns 33L
        coEvery { repository.startImport(eq(33L), any(), any(), any()) } returns Unit

        val viewModel = BookshelfViewModel(repository)
        viewModel.importBook(mockUri, mockContext)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.prepareImport(mockUri, mockContext) }
        coVerify(exactly = 1) { repository.startImport(eq(33L), any(), any(), any()) }
        // 结束态：placeholders 已清空
        assertTrue(viewModel.placeholderBooks.value.isEmpty())
    }
}
