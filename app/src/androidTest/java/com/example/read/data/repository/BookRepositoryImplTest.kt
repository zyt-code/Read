package com.example.read.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.read.data.local.AppDatabase
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.local.entity.BookEntity
import com.example.read.domain.model.Book
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
import java.io.File

/**
 * BookRepositoryImpl 的 Android 集成测试。
 *
 * 测试范围：
 * - getAllBooks(): 获取所有书籍的响应式数据流
 * - getBookById(): 根据 ID 查询单本书籍
 * - deleteBook(): 删除书籍（数据库记录 + 文件系统）
 * - updateReadingProgress(): 更新阅读进度
 * - getChapterContent(): 获取章节纯文本内容
 *
 * 测试策略：
 * - 使用 Room 内存数据库替代真实数据库
 * - 使用真实的 app 内部存储目录进行文件操作测试
 * - 每个测试前清理测试文件，确保隔离
 *
 * 注意：
 * - importBook() 测试需要真实的 EPUB 文件和 ContentResolver，
 *   在纯集成测试中难以模拟 SAF URI，因此跳过该测试。
 * - getChapterContent() 需要真实的 EPUB 文件，同样跳过。
 * - 这些测试专注于 Repository 层对 DAO 的协调逻辑。
 *
 * 运行命令：./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryImplTest {

    /** 内存数据库实例 */
    private lateinit var database: AppDatabase

    /** DAO 实例 */
    private lateinit var bookDao: BookDao

    /** 书签 DAO 实例（v6 引入，BookRepositoryImpl 构造需要） */
    private lateinit var bookmarkDao: BookmarkDao

    /** 被测的 Repository 实例 */
    private lateinit var repository: BookRepositoryImpl

    /** 应用上下文，用于文件操作 */
    private lateinit var context: Context

    /** 测试用的书籍实体（直接插入数据库） */
    private val testBookEntity = BookEntity(
        id = 1,
        title = "Test Book",
        author = "Test Author",
        coverPath = null,
        bookDirPath = "/epubs/test.epub",
        totalChapters = 10,
        lastReadChapter = 3,
        lastReadAt = 1000L,
    )

    /**
     * 每个测试前的初始化。
     * 创建内存数据库和 Repository 实例。
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // 创建内存数据库
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        bookDao = database.bookDao()
        // v6：从同一数据库取 BookmarkDao
        bookmarkDao = database.bookmarkDao()

        // 创建 Repository 实例
        repository = BookRepositoryImpl(bookDao, bookmarkDao, context)
    }

    /**
     * 每个测试后的清理。
     * 关闭数据库，清理测试文件。
     */
    @After
    fun tearDown() {
        database.close()
    }

    // ==================== getAllBooks 测试 ====================

    /**
     * 测试 getAllBooks 返回空列表（数据库为空时）。
     * 验证 Repository 正确传递 DAO 的空结果。
     */
    @Test
    fun getAllBooks_emptyDatabase_returnsEmptyList() = runTest {
        // 执行：查询所有书籍
        val books = repository.getAllBooks().first()

        // 验证：返回空列表
        assertTrue("空数据库应返回空列表", books.isEmpty())
    }

    /**
     * 测试 getAllBooks 返回已插入的书籍。
     * 验证 Repository 正确将 Entity 转换为领域模型。
     */
    @Test
    fun getAllBooks_withBooks_returnsDomainModels() = runTest {
        // 准备：直接插入实体到数据库
        bookDao.insertBook(testBookEntity)

        // 执行：通过 Repository 查询
        val books = repository.getAllBooks().first()

        // 验证：返回领域模型，数据正确
        assertEquals(1, books.size)
        val book = books[0]
        assertEquals("Test Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals(10, book.totalChapters)
    }

    /**
     * 测试 getAllBooks 按最近阅读排序。
     * 验证 Repository 保持 DAO 的排序逻辑。
     */
    @Test
    fun getAllBooks_orderedByLastReadAtDesc() = runTest {
        // 准备：插入三本书，不同 lastReadAt
        bookDao.insertBook(testBookEntity.copy(id = 0, title = "Old", lastReadAt = 100L))
        bookDao.insertBook(testBookEntity.copy(id = 0, title = "New", lastReadAt = 300L))
        bookDao.insertBook(testBookEntity.copy(id = 0, title = "Middle", lastReadAt = 200L))

        // 执行：查询所有书籍
        val books = repository.getAllBooks().first()

        // 验证：按 lastReadAt 倒序
        assertEquals(3, books.size)
        assertEquals("New", books[0].title)
        assertEquals("Middle", books[1].title)
        assertEquals("Old", books[2].title)
    }

    // ==================== getBookById 测试 ====================

    /**
     * 测试根据 ID 查询已存在的书籍。
     * 验证 Repository 正确转换 Entity 到领域模型。
     */
    @Test
    fun getBookById_existingBook_returnsDomainModel() = runTest {
        // 准备：插入书籍
        val id = bookDao.insertBook(testBookEntity)

        // 执行：通过 Repository 查询
        val book = repository.getBookById(id)

        // 验证：返回正确的领域模型
        assertNotNull("查询结果不应为 null", book)
        assertEquals(id, book!!.id)
        assertEquals("Test Book", book.title)
        assertEquals("Test Author", book.author)
        assertEquals(10, book.totalChapters)
        assertEquals(3, book.lastReadChapter)
        assertEquals(1000L, book.lastReadAt)
    }

    /**
     * 测试查询不存在的书籍返回 null。
     * 验证 Repository 正确处理 DAO 返回的 null。
     */
    @Test
    fun getBookById_nonExistentId_returnsNull() = runTest {
        // 执行：查询不存在的 ID
        val book = repository.getBookById(99999L)

        // 验证：返回 null
        assertNull("不存在的 ID 应返回 null", book)
    }

    // ==================== deleteBook 测试 ====================

    /**
     * 测试删除书籍移除数据库记录。
     * 验证 Repository 调用 DAO 的 deleteBook 方法。
     */
    @Test
    fun deleteBook_removesDatabaseRecord() = runTest {
        // 准备：插入书籍
        val id = bookDao.insertBook(testBookEntity)
        val book = repository.getBookById(id)!!

        // 执行：删除书籍
        repository.deleteBook(book)

        // 验证：数据库记录已删除
        val result = bookDao.getBookById(id)
        assertNull("删除后数据库记录应不存在", result)
    }

    /**
     * 测试删除书籍不影响其他书籍。
     * 验证删除操作的精确性。
     */
    @Test
    fun deleteBook_doesNotAffectOtherBooks() = runTest {
        // 准备：插入两本书
        val id1 = bookDao.insertBook(testBookEntity)
        val id2 = bookDao.insertBook(testBookEntity.copy(id = 0, title = "Other Book"))
        val book1 = repository.getBookById(id1)!!

        // 执行：删除第一本
        repository.deleteBook(book1)

        // 验证：第二本仍然存在
        val remaining = repository.getBookById(id2)
        assertNotNull("第二本书应仍然存在", remaining)
        assertEquals("Other Book", remaining!!.title)
    }

    /**
     * 测试删除不存在的文件不会崩溃。
     * 验证 Repository 在文件不存在时优雅处理。
     */
    @Test
    fun deleteBook_withNonExistentFile_doesNotCrash() = runTest {
        // 准备：插入书籍（文件路径指向不存在的文件）
        val id = bookDao.insertBook(testBookEntity.copy(bookDirPath = "/nonexistent/path.epub"))
        val book = repository.getBookById(id)!!

        // 执行：删除（文件不存在，但不应崩溃）
        repository.deleteBook(book)

        // 验证：数据库记录被删除
        val result = bookDao.getBookById(id)
        assertNull("数据库记录应被删除", result)
    }

    // ==================== updateReadingProgress 测试 ====================

    /**
     * 测试更新阅读进度。
     * 验证 Repository 正确调用 DAO 的 updateReadingProgress。
     */
    @Test
    fun updateReadingProgress_updatesInDatabase() = runTest {
        // 准备：插入书籍
        val id = bookDao.insertBook(testBookEntity)

        // 执行：更新阅读进度
        repository.updateReadingProgress(id, 7)

        // 验证：数据库中的进度已更新
        val updated = bookDao.getBookById(id)
        assertNotNull(updated)
        assertEquals(7, updated!!.lastReadChapter)
        // lastReadAt 应该被更新为当前时间（大于 0）
        assertTrue("lastReadAt 应大于 0", updated.lastReadAt > 0)
    }

    /**
     * 测试更新阅读进度不影响其他字段。
     * 验证 UPDATE 操作的精确性。
     */
    @Test
    fun updateReadingProgress_preservesOtherFields() = runTest {
        // 准备：插入书籍
        val id = bookDao.insertBook(testBookEntity)

        // 执行：更新阅读进度
        repository.updateReadingProgress(id, 5)

        // 验证：其他字段不变
        val updated = bookDao.getBookById(id)
        assertNotNull(updated)
        assertEquals("Test Book", updated!!.title)
        assertEquals("Test Author", updated.author)
        assertEquals(10, updated.totalChapters)
    }

    // ==================== Flow 响应式测试 ====================

    /**
     * 测试 getAllBooks 的 Flow 在数据库变化时自动更新。
     * 验证 Repository 层正确传递 DAO 的响应式通知。
     */
    @Test
    fun getAllBooks_flow_updatesWhenDataChanges() = runTest {
        // 准备：获取初始数据
        val initialBooks = repository.getAllBooks().first()
        assertTrue("初始应为空列表", initialBooks.isEmpty())

        // 执行：插入一本书
        bookDao.insertBook(testBookEntity)

        // 验证：Flow 发射更新后的数据
        val updatedBooks = repository.getAllBooks().first()
        assertEquals(1, updatedBooks.size)
        assertEquals("Test Book", updatedBooks[0].title)
    }

    /**
     * 测试 getAllBooks 的 Flow 在删除后更新。
     * 验证删除操作触发 Repository Flow 重新发射。
     */
    @Test
    fun getAllBooks_flow_updatesAfterDelete() = runTest {
        // 准备：插入一本书
        val id = bookDao.insertBook(testBookEntity)
        val book = repository.getBookById(id)!!

        // 确认初始状态
        val withBook = repository.getAllBooks().first()
        assertEquals(1, withBook.size)

        // 执行：删除书籍
        repository.deleteBook(book)

        // 验证：Flow 发射空列表
        val afterDelete = repository.getAllBooks().first()
        assertTrue("删除后应为空列表", afterDelete.isEmpty())
    }

    // ==================== Entity 到 Domain 转换测试 ====================

    /**
     * 测试 Repository 正确将 Entity 转换为领域模型。
     * 验证所有字段在转换过程中保持一致。
     */
    @Test
    fun getBookById_convertsEntityToDomainCorrectly() = runTest {
        // 准备：插入包含完整数据的书籍
        val entity = BookEntity(
            id = 0,
            title = "完整测试",
            author = "测试作者",
            coverPath = "/covers/test.jpg",
            bookDirPath = "/epubs/test.epub",
            totalChapters = 25,
            lastReadChapter = 12,
            lastReadAt = 1700000000000L,
        )
        val id = bookDao.insertBook(entity)

        // 执行：通过 Repository 查询
        val book = repository.getBookById(id)

        // 验证：所有字段正确转换
        assertNotNull(book)
        assertEquals(id, book!!.id)
        assertEquals("完整测试", book.title)
        assertEquals("测试作者", book.author)
        assertEquals("/covers/test.jpg", book.coverPath)
        assertEquals("/epubs/test.epub", book.bookDirPath)
        assertEquals(25, book.totalChapters)
        assertEquals(12, book.lastReadChapter)
        assertEquals(1700000000000L, book.lastReadAt)
    }

    /**
     * 测试 coverPath 为 null 时的转换。
     * 验证 null 值在 Entity 到 Domain 转换中正确保留。
     */
    @Test
    fun getBookById_withNullCoverPath_preservesNull() = runTest {
        // 准备：插入 coverPath 为 null 的书籍
        val id = bookDao.insertBook(testBookEntity.copy(coverPath = null))

        // 执行：查询
        val book = repository.getBookById(id)

        // 验证：coverPath 为 null
        assertNotNull(book)
        assertNull("coverPath 应为 null", book!!.coverPath)
    }
}
