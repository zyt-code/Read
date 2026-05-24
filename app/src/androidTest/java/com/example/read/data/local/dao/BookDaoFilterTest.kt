package com.example.read.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.read.data.local.AppDatabase
import com.example.read.data.local.entity.BookEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BookDao 的 PREPARING_ 占位记录过滤行为集成测试（P1-1 回归）。
 *
 * 验证目标（详见 FIX_REPORT_v2.md Fix-1）：
 * - `getAllBooks()` SQL 增加了 `WHERE bookDirPath NOT LIKE 'PREPARING_%'`，
 *   书架订阅的 Flow 不再收到 PREPARING_ 占位记录。
 * - 新增 `getAllBooksIncludingPreparing()` 不过滤，仅供 cleanupOrphanedBooks
 *   等启动期任务扫描全量记录。
 *
 * 设计要点：
 * - 使用 Room 内存数据库验证真实 SQL 行为
 * - 与 JVM 单测 BookRepositoryImplCleanupTest 互补：那里 mock DAO 验证 Repository
 *   分支判断，这里跑真实 SQL 验证过滤条件实际生效
 *
 * 运行命令：`./gradlew connectedAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookDaoFilterTest {

    private lateinit var database: AppDatabase
    private lateinit var bookDao: BookDao

    /** 已完成的正常书籍记录 */
    private val normalBook = BookEntity(
        title = "正常书籍",
        author = "作者A",
        coverPath = "/covers/1.jpg",
        bookDirPath = "/storage/emulated/0/Android/data/com.example.read/files/books/abc",
        totalChapters = 12,
        lastReadChapter = 3,
        lastReadAt = 5000L,
    )

    /** PREPARING_ 占位记录（导入进行中或残留） */
    private val preparingBook = BookEntity(
        title = "导入中",
        author = "作者B",
        coverPath = null,
        bookDirPath = "PREPARING_uuid-123-xyz",
        totalChapters = 5,
        lastReadChapter = 0,
        lastReadAt = 1000L,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        bookDao = database.bookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Given: 库中同时存在 PREPARING_xxx 占位记录与一条真实路径的正常记录
     * When: 调 `getAllBooks().first()`
     * Then: 仅返回正常记录，PREPARING_ 记录被 SQL WHERE 过滤掉
     */
    @Test
    fun `getAllBooks should filter out PREPARING records`() = runTest {
        bookDao.insertBook(normalBook)
        bookDao.insertBook(preparingBook)

        val books = bookDao.getAllBooks().first()

        assertEquals("书架查询应仅返回 1 条非 PREPARING 记录", 1, books.size)
        assertEquals("正常书籍", books[0].title)
        assertTrue(
            "返回记录的 bookDirPath 不应是 PREPARING_ 开头",
            !books[0].bookDirPath.startsWith("PREPARING_"),
        )
    }

    /**
     * Given: 库中同时存在 PREPARING_xxx 与正常记录
     * When: 调 `getAllBooksIncludingPreparing().first()`
     * Then: 两条记录都返回（无过滤）
     */
    @Test
    fun `getAllBooksIncludingPreparing should return both records`() = runTest {
        bookDao.insertBook(normalBook)
        bookDao.insertBook(preparingBook)

        val books = bookDao.getAllBooksIncludingPreparing().first()

        assertEquals("启动期查询应包含全部 2 条记录", 2, books.size)
        // 两条记录都存在
        val titles = books.map { it.title }.toSet()
        assertTrue(titles.contains("正常书籍"))
        assertTrue(titles.contains("导入中"))
    }

    /**
     * Given: 库中只有 PREPARING_ 占位记录
     * When: 调 `getAllBooks().first()`
     * Then: 返回空列表（书架完全看不到这些"未就绪"书籍）
     */
    @Test
    fun `getAllBooks should return empty when only PREPARING records exist`() = runTest {
        bookDao.insertBook(preparingBook)
        bookDao.insertBook(
            preparingBook.copy(title = "另一本导入中", bookDirPath = "PREPARING_other-uuid"),
        )

        val books = bookDao.getAllBooks().first()

        assertTrue("只有 PREPARING_ 时书架应为空", books.isEmpty())
    }

    /**
     * Given: 库中只有 PREPARING_ 占位记录
     * When: 调 `getAllBooksIncludingPreparing().first()`
     * Then: 返回全部 PREPARING_ 记录（启动期可以看到它们以便清理）
     */
    @Test
    fun `getAllBooksIncludingPreparing should return only PREPARING records when no others`() = runTest {
        bookDao.insertBook(preparingBook)
        bookDao.insertBook(
            preparingBook.copy(title = "另一本导入中", bookDirPath = "PREPARING_other-uuid"),
        )

        val books = bookDao.getAllBooksIncludingPreparing().first()

        assertEquals(2, books.size)
        // 两条都是 PREPARING_ 开头
        assertTrue(books.all { it.bookDirPath.startsWith("PREPARING_") })
    }

    /**
     * Given: bookDirPath 包含 "PREPARING_" 但不是开头（例如真实路径里碰巧含有该子串）
     * When: 调 `getAllBooks().first()`
     * Then: 该记录不被过滤（NOT LIKE 'PREPARING_%' 仅匹配前缀）
     *
     * 边界保护：确保 SQL LIKE 子句的语义是"前缀"而非"包含"。
     */
    @Test
    fun `getAllBooks should not filter records merely containing PREPARING string`() = runTest {
        val tricky = normalBook.copy(
            title = "真实路径含 PREPARING_ 子串",
            bookDirPath = "/storage/.../books/PREPARING_demo_dir",
        )
        bookDao.insertBook(tricky)

        val books = bookDao.getAllBooks().first()

        assertEquals(1, books.size)
        assertEquals("真实路径含 PREPARING_ 子串", books[0].title)
    }

    /**
     * Given: 一条 PREPARING_ 占位记录在 startImport 完成后被 updateBook 改为真实路径
     * When: 再次调 `getAllBooks().first()`
     * Then: 该记录立即出现在书架（验证 SQL 过滤是 WHERE 条件实时生效，不是基于插入时快照）
     */
    @Test
    fun `getAllBooks should include record after PREPARING path replaced with real path`() = runTest {
        val id = bookDao.insertBook(preparingBook)
        // 初始：书架看不到
        assertTrue(bookDao.getAllBooks().first().isEmpty())

        // 模拟 startImport 完成后的 updateBook：把 PREPARING_xxx 替换为真实路径
        val finalEntity = preparingBook.copy(
            id = id,
            bookDirPath = "/storage/.../books/$id",
            totalChapters = 5,
        )
        bookDao.updateBook(finalEntity)

        val books = bookDao.getAllBooks().first()
        assertEquals("替换为真实路径后，书架应能查到", 1, books.size)
        assertEquals(id, books[0].id)
    }
}
