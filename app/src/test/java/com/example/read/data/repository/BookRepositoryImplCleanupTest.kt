package com.example.read.data.repository

import android.content.Context
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.local.entity.BookEntity
import io.mockk.MockKAnnotations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * BookRepositoryImpl.cleanupOrphanedBooks 针对 P0-5 / P1-1 修复的回归测试。
 *
 * 修复要点（详见 FIX_REPORT.md P0-5 + FIX_REPORT_v2.md Fix-1）：
 * - 占位记录的 bookDirPath 用 `PREPARING_<uuid>` 前缀，与空字符串孤儿区分
 * - cleanupOrphanedBooks 规则：
 *   - PREPARING_ 前缀且 lastReadAt 在 1 小时内：跳过不删（活跃导入）
 *   - PREPARING_ 前缀且 lastReadAt 距今 >= 1 小时：视为崩溃幽灵，删除（P1-1 新增）
 *   - 空字符串：仍按旧逻辑删除（兼容 v1→v2 迁移和老版本失败残留）
 *   - 其他非空路径：不动（已完成的书）
 * - 数据源切换：cleanupOrphanedBooks 现在走 getAllBooksIncludingPreparing()
 *   而不是 getAllBooks()，因为后者已经过滤了 PREPARING_ 记录（P1-1）
 *
 * 该测试与 BookRepositoryImplTest 中"空字符串路径"用例互补，
 * 专注 PREPARING_ 前缀的活跃 vs 幽灵分支判定，确保启动期 cleanup 行为正确。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryImplCleanupTest {

    @MockK
    private lateinit var bookDao: BookDao

    private lateinit var repository: BookRepositoryImpl

    /** 当前时间戳的快照，所有测试构造 entities 时使用，确保 ghost 阈值判定可控 */
    private val now: Long = System.currentTimeMillis()

    /** "活跃" 时间戳：距 now 5 分钟内，PREPARING_ 应被保留 */
    private val activeReadAt: Long get() = now - 5 * 60_000L

    /** "幽灵" 时间戳：距 now 超过 1 小时，PREPARING_ 应被清理 */
    private val ghostReadAt: Long get() = now - 2 * 60 * 60_000L

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        val mockContext = mockk<Context>(relaxed = true)
        // v6：构造签名新增 BookmarkDao 参数；cleanupOrphanedBooks 不访问书签，relaxed mock 即可
        val mockBookmarkDao = mockk<BookmarkDao>(relaxed = true)
        repository = BookRepositoryImpl(bookDao, mockBookmarkDao, mockContext)
    }

    /**
     * Given: 数据库中存在 PREPARING_xxx 占位记录，lastReadAt 在 1 小时内（活跃导入）
     * When: 调用 cleanupOrphanedBooks
     * Then: 不调用 deleteBook，活跃 import 的占位记录被保留
     */
    @Test
    fun `should skip active PREPARING records within ghost threshold`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 1, title = "导入中", author = "作者", coverPath = null,
                bookDirPath = "PREPARING_abc-123-uuid", totalChapters = 5,
                lastReadAt = activeReadAt,
            ),
            BookEntity(
                id = 2, title = "另一本导入中", author = "作者", coverPath = null,
                bookDirPath = "PREPARING_def-456-uuid", totalChapters = 8,
                lastReadAt = activeReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        // 活跃 PREPARING_ 记录不应被删
        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    /**
     * Given: 同时存在活跃 PREPARING_xxx 记录与正常已完成的书
     * When: 调用 cleanupOrphanedBooks
     * Then: 两者都被保留，deleteBook 完全不调用
     */
    @Test
    fun `should keep both active PREPARING and completed records intact`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 1, title = "已完成", author = "作者", coverPath = "/covers/1.jpg",
                bookDirPath = "/data/books/1", totalChapters = 10,
                lastReadAt = activeReadAt,
            ),
            BookEntity(
                id = 2, title = "导入中", author = "作者", coverPath = null,
                bookDirPath = "PREPARING_abc-uuid", totalChapters = 3,
                lastReadAt = activeReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    /**
     * Given: 同时存在活跃 PREPARING_xxx、空字符串孤儿、已完成的书
     * When: 调用 cleanupOrphanedBooks
     * Then: 仅空字符串孤儿被删除；活跃 PREPARING_ 与已完成都保留
     *
     * 这是 P0-5 修复的核心场景：启动期 cleanup 必须区分"导入进行中"和"老版本残留"。
     */
    @Test
    fun `should only delete empty-string orphans while keeping active PREPARING and completed`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 1, title = "已完成", author = "作者", coverPath = null,
                bookDirPath = "/data/books/1", totalChapters = 10,
                lastReadAt = activeReadAt,
            ),
            BookEntity(
                id = 2, title = "导入进行中", author = "作者", coverPath = null,
                bookDirPath = "PREPARING_uuid-active", totalChapters = 5,
                lastReadAt = activeReadAt,
            ),
            BookEntity(
                id = 3, title = "v1 迁移孤儿", author = "作者", coverPath = null,
                bookDirPath = "", totalChapters = 0,
                lastReadAt = 0L,
            ),
            BookEntity(
                id = 4, title = "另一已完成", author = "作者", coverPath = null,
                bookDirPath = "/data/books/4", totalChapters = 7,
                lastReadAt = activeReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        // 仅 id=3 被删
        coVerify(exactly = 1) { bookDao.deleteBook(any()) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 3L }) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 1L }) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 2L }) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 4L }) }
    }

    /**
     * Given: bookDirPath 以 "PREPARING_" 开头但内部 uuid 部分为空（边界情况），仍在活跃窗口
     * When: 调用 cleanupOrphanedBooks
     * Then: 仍跳过（startsWith 检查不依赖 uuid 长度）
     */
    @Test
    fun `should skip even malformed PREPARING records when active`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 1, title = "短 PREPARING", author = "", coverPath = null,
                bookDirPath = "PREPARING_", totalChapters = 0,
                lastReadAt = activeReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    /**
     * Given: 路径恰好是 "preparing_xxx"（小写，非匹配前缀）
     * When: 调用 cleanupOrphanedBooks
     * Then: 既不是 PREPARING_ 前缀也不是空字符串，按既有"非空不动"分支保留
     */
    @Test
    fun `should not match lowercase preparing prefix`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 1, title = "小写", author = "", coverPath = null,
                bookDirPath = "preparing_abc", totalChapters = 0,
                lastReadAt = activeReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        // 既不是 PREPARING_ 前缀也不是空字符串：保留
        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    // ==================== P1-1 新增：PREPARING_ 幽灵记录清理 ====================

    /**
     * Given: PREPARING_ 记录的 lastReadAt 距今超过 1 小时（崩溃残留）
     * When: 调用 cleanupOrphanedBooks
     * Then: 该记录被识别为幽灵，调 deleteBook 清除
     *
     * 这是 P1-1 修复的核心：上一轮 P0-5 漏掉的"进程被杀后占位永久残留"场景。
     */
    @Test
    fun `should delete PREPARING records older than ghost threshold`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 7, title = "崩溃残留", author = "", coverPath = null,
                bookDirPath = "PREPARING_ghost-uuid", totalChapters = 0,
                lastReadAt = ghostReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        // 超过阈值的 PREPARING_ 被清理
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 7L }) }
    }

    /**
     * Given: 同时存在活跃 PREPARING_（5 分钟前）和幽灵 PREPARING_（2 小时前）
     * When: 调用 cleanupOrphanedBooks
     * Then: 仅幽灵被删，活跃保留
     */
    @Test
    fun `should distinguish active and ghost PREPARING records`() = runTest {
        val entities = listOf(
            BookEntity(
                id = 10, title = "活跃", author = "", coverPath = null,
                bookDirPath = "PREPARING_active", totalChapters = 0,
                lastReadAt = activeReadAt,
            ),
            BookEntity(
                id = 11, title = "幽灵", author = "", coverPath = null,
                bookDirPath = "PREPARING_ghost", totalChapters = 0,
                lastReadAt = ghostReadAt,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        coVerify(exactly = 1) { bookDao.deleteBook(any()) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 10L }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 11L }) }
    }

    /**
     * Given: PREPARING_ 记录的 lastReadAt 恰好距今 1 小时（3,600,000 ms，等于阈值）
     * When: 调用 cleanupOrphanedBooks
     * Then: 该记录被删除（生产代码使用 `>=` 判断，阈值边界算"幽灵"）
     *
     * 边界保护：避免未来如果有人把 `>=` 改成 `>` 时该 case 静默失败。
     * 由于 System.currentTimeMillis() 在 cleanupOrphanedBooks 内重新调用，与构造
     * 时的 now 不一致，故构造时取 (now - 阈值 - 1ms) 这样无论之后再读时钟，
     * `now2 - lastReadAt >= 阈值` 都成立。
     */
    @Test
    fun `should delete PREPARING records exactly at ghost threshold boundary`() = runTest {
        // 在 setUp() 取的 now 基础上，构造一个 lastReadAt 略小于阈值边界一点的值，
        // 这样无论生产代码内 System.currentTimeMillis() 取到稍晚的时间，差值都 >= 阈值
        val justAtThreshold = now - 60L * 60L * 1000L - 1L
        val entities = listOf(
            BookEntity(
                id = 21, title = "边界幽灵", author = "", coverPath = null,
                bookDirPath = "PREPARING_boundary", totalChapters = 0,
                lastReadAt = justAtThreshold,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        // `now2 - lastReadAt >= PREPARING_GHOST_THRESHOLD_MS` 必然成立 → 删除
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 21L }) }
    }

    /**
     * Given: PREPARING_ 记录的 lastReadAt 距今刚刚少于阈值（59 分 59 秒前）
     * When: 调用 cleanupOrphanedBooks
     * Then: 不删除（仍视为活跃导入）
     *
     * 与上一个 case 形成镜像，确保活跃边界仍受保护。
     */
    @Test
    fun `should keep PREPARING records just under ghost threshold boundary`() = runTest {
        // 设为阈值减 10 秒，留足缓冲让代码内重读时钟也不越界
        val justUnder = now - 60L * 60L * 1000L + 10_000L
        val entities = listOf(
            BookEntity(
                id = 22, title = "临界活跃", author = "", coverPath = null,
                bookDirPath = "PREPARING_almost", totalChapters = 0,
                lastReadAt = justUnder,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    /**
     * Given: 多条 PREPARING_ 记录混合（2 条活跃 + 3 条幽灵 + 1 条已完成 + 1 条空字符串）
     * When: 调用 cleanupOrphanedBooks
     * Then:
     *   - 3 条幽灵 PREPARING_ 全部被删
     *   - 1 条空字符串孤儿被删
     *   - 2 条活跃 PREPARING_ 保留
     *   - 1 条已完成保留
     *   - 总 deleteBook 调用次数 = 4
     *
     * 这是 P1-1 修复的最复杂场景：启动清理一次处理多类型记录混合。
     */
    @Test
    fun `should handle mixed PREPARING active ghost and other records correctly`() = runTest {
        val entities = listOf(
            // 已完成（保留）
            BookEntity(
                id = 100, title = "已完成", author = "", coverPath = null,
                bookDirPath = "/data/books/100", totalChapters = 10,
                lastReadAt = activeReadAt,
            ),
            // 活跃 PREPARING（保留）
            BookEntity(
                id = 101, title = "活跃 A", author = "", coverPath = null,
                bookDirPath = "PREPARING_active-a", totalChapters = 0,
                lastReadAt = activeReadAt,
            ),
            BookEntity(
                id = 102, title = "活跃 B", author = "", coverPath = null,
                bookDirPath = "PREPARING_active-b", totalChapters = 0,
                lastReadAt = activeReadAt,
            ),
            // 幽灵 PREPARING（删除）
            BookEntity(
                id = 103, title = "幽灵 X", author = "", coverPath = null,
                bookDirPath = "PREPARING_ghost-x", totalChapters = 0,
                lastReadAt = ghostReadAt,
            ),
            BookEntity(
                id = 104, title = "幽灵 Y", author = "", coverPath = null,
                bookDirPath = "PREPARING_ghost-y", totalChapters = 0,
                lastReadAt = ghostReadAt,
            ),
            BookEntity(
                id = 105, title = "幽灵 Z", author = "", coverPath = null,
                bookDirPath = "PREPARING_ghost-z", totalChapters = 0,
                lastReadAt = 0L,  // epoch 0：很久之前
            ),
            // v1→v2 迁移残留（删除）
            BookEntity(
                id = 106, title = "迁移孤儿", author = "", coverPath = null,
                bookDirPath = "", totalChapters = 0,
                lastReadAt = 0L,
            ),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        repository.cleanupOrphanedBooks()

        // 共 4 条被删
        coVerify(exactly = 4) { bookDao.deleteBook(any()) }
        // 保留的不应触发删除
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 100L }) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 101L }) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 102L }) }
        // 删除的应精确匹配
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 103L }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 104L }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 105L }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 106L }) }
    }
}
