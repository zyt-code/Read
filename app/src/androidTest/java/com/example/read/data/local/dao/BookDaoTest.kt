package com.example.read.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.example.read.data.local.AppDatabase
import com.example.read.data.local.entity.BookEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BookDao 的 Android 集成测试。
 *
 * 测试范围：
 * - insertBook(): 插入书籍并返回自增 ID
 * - getAllBooks(): 查询所有书籍，按 lastReadAt 倒序排列
 * - getBookById(): 按 ID 查询单本书籍
 * - deleteBook(): 删除书籍记录
 * - updateReadingProgress(): 更新阅读进度（章节索引和时间戳）
 * - Flow 响应式更新：数据库变化时自动发射新数据
 *
 * 测试策略：
 * - 使用 Room 内存数据库（In-Memory Database），不写入磁盘
 * - 每个测试前创建新数据库，测试后关闭，确保测试隔离
 * - 使用 Turbine 库测试 Flow 的响应式行为
 *
 * 注意：这是 Android Instrumented Test，需要在设备或模拟器上运行。
 * 运行命令：./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookDaoTest {

    /** 内存数据库实例，每个测试使用独立实例 */
    private lateinit var database: AppDatabase

    /** 被测的 DAO 实例 */
    private lateinit var bookDao: BookDao

    /** 测试用的书籍实体 */
    private val testBook1 = BookEntity(
        title = "Kotlin Coroutines",
        author = "John Doe",
        coverPath = "/covers/coroutines.jpg",
        bookDirPath = "/epubs/coroutines.epub",
        totalChapters = 10,
        lastReadChapter = 5,
        lastReadAt = 1000L,
    )

    private val testBook2 = BookEntity(
        title = "Android Compose",
        author = "Jane Smith",
        coverPath = null,
        bookDirPath = "/epubs/compose.epub",
        totalChapters = 20,
        lastReadChapter = 0,
        lastReadAt = 2000L,
    )

    private val testBook3 = BookEntity(
        title = "Clean Code",
        author = "Robert Martin",
        coverPath = "/covers/cleancode.jpg",
        bookDirPath = "/epubs/cleancode.epub",
        totalChapters = 15,
        lastReadChapter = 10,
        lastReadAt = 3000L,
    )

    /**
     * 每个测试前的初始化。
     * 创建新的内存数据库实例，确保测试之间完全隔离。
     * 内存数据库在进程结束后自动销毁，不影响其他测试。
     */
    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()  // 测试中允许主线程查询

        bookDao = database.bookDao()
    }

    /**
     * 每个测试后的清理。
     * 关闭数据库连接，释放资源。
     */
    @After
    fun tearDown() {
        database.close()
    }

    // ==================== insertBook 测试 ====================

    /**
     * 测试插入书籍返回有效的自增 ID。
     * 验证 Room 的 autoGenerate=true 生效，返回非零 ID。
     */
    @Test
    fun insertBook_returnsValidId() = runTest {
        // 执行：插入测试书籍
        val id = bookDao.insertBook(testBook1)

        // 验证：返回的 ID 大于 0（Room 自动生成）
        assertTrue("插入返回的 ID 应大于 0", id > 0)
    }

    /**
     * 测试插入多本书籍返回不同的 ID。
     * 验证自增主键的唯一性。
     */
    @Test
    fun insertBook_multipleBooks_returnsDifferentIds() = runTest {
        // 执行：插入两本不同的书籍
        val id1 = bookDao.insertBook(testBook1)
        val id2 = bookDao.insertBook(testBook2)

        // 验证：两个 ID 不同
        assertTrue("两本书的 ID 应不同", id1 != id2)
    }

    // ==================== getBookById 测试 ====================

    /**
     * 测试根据 ID 查询已存在的书籍。
     * 验证插入后能正确读取所有字段。
     */
    @Test
    fun getBookById_existingBook_returnsCorrectBook() = runTest {
        // 准备：插入测试书籍
        val id = bookDao.insertBook(testBook1)

        // 执行：根据 ID 查询
        val result = bookDao.getBookById(id)

        // 验证：查询结果与插入数据一致
        assertNotNull("查询结果不应为 null", result)
        assertEquals(id, result!!.id)
        assertEquals("Kotlin Coroutines", result.title)
        assertEquals("John Doe", result.author)
        assertEquals("/covers/coroutines.jpg", result.coverPath)
        assertEquals("/epubs/coroutines.epub", result.bookDirPath)
        assertEquals(10, result.totalChapters)
        assertEquals(5, result.lastReadChapter)
        assertEquals(1000L, result.lastReadAt)
    }

    /**
     * 测试查询不存在的 ID 返回 null。
     * 验证 getBookById 在找不到记录时返回 null 而不是抛异常。
     */
    @Test
    fun getBookById_nonExistentId_returnsNull() = runTest {
        // 执行：查询不存在的 ID
        val result = bookDao.getBookById(99999L)

        // 验证：返回 null
        assertNull("不存在的 ID 应返回 null", result)
    }

    // ==================== getAllBooks 测试 ====================

    /**
     * 测试没有插入书籍时 getAllBooks 返回空列表。
     * 验证空数据库的查询行为。
     */
    @Test
    fun getAllBooks_noBooks_returnsEmptyList() = runTest {
        // 执行：查询所有书籍
        val books = bookDao.getAllBooks().first()

        // 验证：返回空列表
        assertTrue("空数据库应返回空列表", books.isEmpty())
    }

    /**
     * 测试 getAllBooks 按 lastReadAt 倒序排列。
     * 验证最近阅读的书籍排在最前面。
     */
    @Test
    fun getAllBooks_orderedByLastReadAtDesc() = runTest {
        // 准备：插入三本书，lastReadAt 分别为 1000, 2000, 3000
        bookDao.insertBook(testBook1)  // lastReadAt = 1000
        bookDao.insertBook(testBook2)  // lastReadAt = 2000
        bookDao.insertBook(testBook3)  // lastReadAt = 3000

        // 执行：查询所有书籍
        val books = bookDao.getAllBooks().first()

        // 验证：按 lastReadAt 倒序排列
        assertEquals(3, books.size)
        assertEquals("Clean Code", books[0].title)       // lastReadAt = 3000
        assertEquals("Android Compose", books[1].title)   // lastReadAt = 2000
        assertEquals("Kotlin Coroutines", books[2].title) // lastReadAt = 1000
    }

    /**
     * 测试 getAllBooks 返回完整的书籍数据。
     * 验证查询结果包含所有字段，没有遗漏。
     */
    @Test
    fun getAllBooks_returnsCompleteBookData() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)

        // 执行：查询所有书籍
        val books = bookDao.getAllBooks().first()

        // 验证：返回一本书，数据完整
        assertEquals(1, books.size)
        val book = books[0]
        assertEquals(id, book.id)
        assertEquals("Kotlin Coroutines", book.title)
        assertEquals("John Doe", book.author)
        assertEquals("/covers/coroutines.jpg", book.coverPath)
        assertEquals("/epubs/coroutines.epub", book.bookDirPath)
        assertEquals(10, book.totalChapters)
        assertEquals(5, book.lastReadChapter)
        assertEquals(1000L, book.lastReadAt)
    }

    // ==================== deleteBook 测试 ====================

    /**
     * 测试删除书籍后该书籍不再存在。
     * 验证 deleteBook 正确移除数据库记录。
     */
    @Test
    fun deleteBook_removesBookFromDatabase() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)
        val insertedBook = bookDao.getBookById(id)!!

        // 执行：删除书籍
        bookDao.deleteBook(insertedBook)

        // 验证：书籍不再存在
        val result = bookDao.getBookById(id)
        assertNull("删除后查询应返回 null", result)
    }

    /**
     * 测试删除一本书不影响其他书籍。
     * 验证删除操作的精确性。
     */
    @Test
    fun deleteBook_doesNotAffectOtherBooks() = runTest {
        // 准备：插入两本书
        val id1 = bookDao.insertBook(testBook1)
        val id2 = bookDao.insertBook(testBook2)
        val book1 = bookDao.getBookById(id1)!!

        // 执行：删除第一本
        bookDao.deleteBook(book1)

        // 验证：第二本仍然存在
        val remaining = bookDao.getBookById(id2)
        assertNotNull("第二本书应仍然存在", remaining)
        assertEquals("Android Compose", remaining!!.title)
    }

    /**
     * 测试删除书籍后 getAllBooks 不再包含该书籍。
     * 验证删除操作对列表查询的影响。
     */
    @Test
    fun deleteBook_removedFromGetAllBooks() = runTest {
        // 准备：插入两本书
        val id1 = bookDao.insertBook(testBook1)
        bookDao.insertBook(testBook2)
        val book1 = bookDao.getBookById(id1)!!

        // 执行：删除第一本
        bookDao.deleteBook(book1)

        // 验证：列表中只剩一本书
        val books = bookDao.getAllBooks().first()
        assertEquals(1, books.size)
        assertEquals("Android Compose", books[0].title)
    }

    // ==================== updateReadingProgress 测试 ====================

    /**
     * 测试更新阅读进度正确修改章节索引和时间戳。
     * 验证 updateReadingProgress 的 UPDATE 操作。
     */
    @Test
    fun updateReadingProgress_updatesChapterAndTimestamp() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)

        // 执行：更新阅读进度到第 8 章
        val newTimestamp = 9999L
        bookDao.updateReadingProgress(id, 8, newTimestamp)

        // 验证：章节索引和时间戳都被更新
        val updated = bookDao.getBookById(id)
        assertNotNull(updated)
        assertEquals(8, updated!!.lastReadChapter)
        assertEquals(newTimestamp, updated.lastReadAt)
    }

    /**
     * 测试更新阅读进度不影响其他字段。
     * 验证 UPDATE 语句只修改目标字段。
     */
    @Test
    fun updateReadingProgress_preservesOtherFields() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)

        // 执行：更新阅读进度
        bookDao.updateReadingProgress(id, 3, 5000L)

        // 验证：其他字段不变
        val updated = bookDao.getBookById(id)
        assertNotNull(updated)
        assertEquals("Kotlin Coroutines", updated!!.title)
        assertEquals("John Doe", updated.author)
        assertEquals("/covers/coroutines.jpg", updated.coverPath)
        assertEquals(10, updated.totalChapters)
    }

    /**
     * 测试多次更新阅读进度。
     * 验证连续更新时每次都正确生效。
     */
    @Test
    fun updateReadingProgress_multipleUpdates_lastUpdateWins() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)

        // 执行：连续更新三次
        bookDao.updateReadingProgress(id, 1, 100L)
        bookDao.updateReadingProgress(id, 5, 200L)
        bookDao.updateReadingProgress(id, 9, 300L)

        // 验证：最后一次更新生效
        val updated = bookDao.getBookById(id)
        assertNotNull(updated)
        assertEquals(9, updated!!.lastReadChapter)
        assertEquals(300L, updated.lastReadAt)
    }

    // ==================== Flow 响应式测试 ====================

    /**
     * 测试 getAllBooks 的 Flow 在插入新书时自动发射更新。
     * 验证 Room 的响应式通知机制。
     */
    @Test
    fun getAllBooks_flow_emitsOnInsert() = runTest {
        // 准备：使用 Turbine 测试 Flow
        bookDao.getAllBooks().test {
            // 初始状态：空列表
            val initial = awaitItem()
            assertTrue("初始应为空列表", initial.isEmpty())

            // 执行：插入一本书
            bookDao.insertBook(testBook1)

            // 验证：Flow 发射包含新书的列表
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("Kotlin Coroutines", updated[0].title)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 测试 getAllBooks 的 Flow 在删除书籍时自动发射更新。
     * 验证删除操作触发 Flow 重新发射。
     */
    @Test
    fun getAllBooks_flow_emitsOnDelete() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)

        bookDao.getAllBooks().test {
            // 当前状态：包含一本书
            val initial = awaitItem()
            assertEquals(1, initial.size)

            // 执行：删除书籍
            bookDao.deleteBook(initial[0])

            // 验证：Flow 发射空列表
            val updated = awaitItem()
            assertTrue("删除后应为空列表", updated.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 测试 getAllBooks 的 Flow 在更新进度时自动发射更新。
     * 验证 updateReadingProgress 触发 Flow 重新发射。
     * 注意：由于排序基于 lastReadAt，更新时间戳可能改变顺序。
     */
    @Test
    fun getAllBooks_flow_emitsOnProgressUpdate() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBook1)

        bookDao.getAllBooks().test {
            // 初始状态
            val initial = awaitItem()
            assertEquals(1, initial.size)
            assertEquals(5, initial[0].lastReadChapter)

            // 执行：更新阅读进度
            bookDao.updateReadingProgress(id, 8, 9999L)

            // 验证：Flow 发射更新后的数据
            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals(8, updated[0].lastReadChapter)
            assertEquals(9999L, updated[0].lastReadAt)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== coverPath 为 null 的测试 ====================

    /**
     * 测试 coverPath 为 null 的书籍正确存储和读取。
     * 验证 Room 对 null 值的处理。
     */
    @Test
    fun insertBook_withNullCoverPath_storesAndRetrievesNull() = runTest {
        // 准备：coverPath 为 null 的书籍
        val bookWithNullCover = testBook2.copy(coverPath = null)

        // 执行：插入并查询
        val id = bookDao.insertBook(bookWithNullCover)
        val result = bookDao.getBookById(id)

        // 验证：coverPath 为 null
        assertNotNull(result)
        assertNull("coverPath 应为 null", result!!.coverPath)
    }
}
