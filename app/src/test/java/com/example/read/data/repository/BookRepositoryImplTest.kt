package com.example.read.data.repository

import android.content.Context
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.local.entity.BookEntity
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import io.mockk.coVerify
import org.junit.Before
import org.junit.Test

/**
 * BookRepositoryImpl 单元测试（纯 JVM，不依赖 Android 框架）。
 *
 * 测试范围：
 * - cleanupOrphanedBooks(): 清理 bookDirPath 为空的孤立书籍记录
 *
 * 测试策略：
 * - 使用 MockK 模拟 BookDao，不依赖 Room 数据库
 * - 使用 MockK 模拟 Context（cleanupOrphanedBooks 不依赖 Context，但构造函数需要）
 * - 验证 Repository 对 DAO 的调用行为（哪些实体被删除，哪些被保留）
 * - 与 androidTest 中的集成测试互补：集成测试验证真实数据库行为，
 *   单元测试验证 Repository 的业务逻辑（孤立记录识别和清理）
 *
 * 运行命令：./gradlew test
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryImplTest {

    /** 模拟的 BookDao，用于验证 Repository 的数据库调用行为 */
    @MockK
    private lateinit var bookDao: BookDao

    /** 被测的 Repository 实例 */
    private lateinit var repository: BookRepositoryImpl

    /**
     * 每个测试前的初始化。
     * 初始化 MockK 注解，创建被测 Repository 实例。
     * 使用 relaxed mock Context，因为 cleanupOrphanedBooks 不访问 Context。
     */
    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        // 创建 relaxed mock Context，cleanupOrphanedBooks 不会访问 Context 的任何方法
        val mockContext = mockk<Context>(relaxed = true)
        // v6：构造签名新增 BookmarkDao 参数；cleanupOrphanedBooks 不访问书签，relaxed mock 即可
        val mockBookmarkDao = mockk<BookmarkDao>(relaxed = true)
        repository = BookRepositoryImpl(bookDao, mockBookmarkDao, mockContext)
    }

    // ==================== cleanupOrphanedBooks 测试 ====================

    /**
     * 验证 cleanupOrphanedBooks 只删除 bookDirPath 为空的孤立记录。
     *
     * 测试场景：数据库中有 4 条记录：
     * - id=1: bookDirPath="/books/1"（正常记录）
     * - id=2: bookDirPath=""（孤立记录，导入中途崩溃残留）
     * - id=3: bookDirPath="/books/3"（正常记录）
     * - id=4: bookDirPath=""（孤立记录，数据库迁移残留）
     * 预期：只有 id=2 和 id=4 被调用 deleteBook 删除，id=1 和 id=3 不受影响。
     */
    @Test
    fun cleanupOrphanedBooks_deletesOnlyEmptyBookDirPath() = runTest {
        // 准备：模拟数据库返回混合的正常记录和孤立记录
        val entities = listOf(
            BookEntity(id = 1, title = "正常书籍1", author = "作者", coverPath = null, bookDirPath = "/books/1", totalChapters = 10),
            BookEntity(id = 2, title = "孤立书籍1", author = "作者", coverPath = null, bookDirPath = "", totalChapters = 5),
            BookEntity(id = 3, title = "正常书籍2", author = "作者", coverPath = null, bookDirPath = "/books/3", totalChapters = 8),
            BookEntity(id = 4, title = "孤立书籍2", author = "作者", coverPath = null, bookDirPath = "", totalChapters = 3),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        // 执行：调用清理方法
        repository.cleanupOrphanedBooks()

        // 验证：deleteBook 只被调用了两次，且参数是孤立记录
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 2L && it.bookDirPath.isEmpty() }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 4L && it.bookDirPath.isEmpty() }) }

        // 验证：正常记录没有被删除
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 1L }) }
        coVerify(exactly = 0) { bookDao.deleteBook(match { it.id == 3L }) }
    }

    /**
     * 验证当所有记录都是正常记录时，cleanupOrphanedBooks 不删除任何记录。
     *
     * 测试场景：数据库中所有记录都有非空的 bookDirPath。
     * 预期：deleteBook 不被调用。
     */
    @Test
    fun cleanupOrphanedBooks_allNormal_deletesNothing() = runTest {
        // 准备：所有记录都是正常的
        val entities = listOf(
            BookEntity(id = 1, title = "书籍A", author = "作者", coverPath = null, bookDirPath = "/books/1", totalChapters = 10),
            BookEntity(id = 2, title = "书籍B", author = "作者", coverPath = null, bookDirPath = "/books/2", totalChapters = 5),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        // 执行
        repository.cleanupOrphanedBooks()

        // 验证：deleteBook 从未被调用
        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    /**
     * 验证当所有记录都是孤立记录时，cleanupOrphanedBooks 删除全部记录。
     *
     * 测试场景：数据库中所有记录的 bookDirPath 都为空。
     * 预期：每条记录都被调用 deleteBook 删除。
     */
    @Test
    fun cleanupOrphanedBooks_allOrphaned_deletesAll() = runTest {
        // 准备：所有记录都是孤立的
        val entities = listOf(
            BookEntity(id = 10, title = "孤立A", author = "作者", coverPath = null, bookDirPath = "", totalChapters = 2),
            BookEntity(id = 20, title = "孤立B", author = "作者", coverPath = null, bookDirPath = "", totalChapters = 4),
            BookEntity(id = 30, title = "孤立C", author = "作者", coverPath = null, bookDirPath = "", totalChapters = 1),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        // 执行
        repository.cleanupOrphanedBooks()

        // 验证：deleteBook 被调用了 3 次
        coVerify(exactly = 3) { bookDao.deleteBook(any()) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 10L }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 20L }) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 30L }) }
    }

    /**
     * 验证当数据库为空时，cleanupOrphanedBooks 不抛出异常。
     *
     * 测试场景：getAllBooks 返回空列表。
     * 预期：正常完成，deleteBook 不被调用。
     */
    @Test
    fun cleanupOrphanedBooks_emptyDatabase_doesNotThrow() = runTest {
        // 准备：空数据库
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(emptyList())

        // 执行：不应抛出异常
        repository.cleanupOrphanedBooks()

        // 验证：deleteBook 从未被调用
        coVerify(exactly = 0) { bookDao.deleteBook(any()) }
    }

    /**
     * 验证 cleanupOrphanedBooks 正确处理 bookDirPath 为空字符串的边界情况。
     *
     * 测试场景：bookDirPath 的值严格为空字符串 ""（不是 null，不是空白）。
     * 空格的情况不应被误判为孤立记录（代码检查 isEmpty()，空格不是空字符串）。
     * 预期：只有严格等于 "" 的 bookDirPath 被视为孤立记录。
     */
    @Test
    fun cleanupOrphanedBooks_onlyEmptyString_notBlankOrWhitespace() = runTest {
        // 准备：包含各种边界情况的 bookDirPath
        val entities = listOf(
            // 空字符串 -> 孤立记录，应被删除
            BookEntity(id = 1, title = "空字符串", author = "", coverPath = null, bookDirPath = "", totalChapters = 1),
            // 空格 -> 不是空字符串，不应被删除
            BookEntity(id = 2, title = "空格", author = "", coverPath = null, bookDirPath = " ", totalChapters = 1),
            // 正常路径 -> 不应被删除
            BookEntity(id = 3, title = "正常路径", author = "", coverPath = null, bookDirPath = "/books/3", totalChapters = 1),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        // 执行
        repository.cleanupOrphanedBooks()

        // 验证：只有 id=1（空字符串）被删除
        coVerify(exactly = 1) { bookDao.deleteBook(any()) }
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 1L }) }
    }

    /**
     * 验证当 booksDir 目录不存在时，cleanupOrphanedBooks 仍能正常执行。
     *
     * 测试场景：booksDir 不存在（如首次启动或数据被清除），
     * listFiles() 返回 null，清理逻辑应照常执行。
     * 预期：孤立记录被正常清理，不抛出异常。
     */
    @Test
    fun cleanupOrphanedBooks_booksDirNotExist_stillCleansUp() = runTest {
        // 准备：有孤立记录，booksDir 不存在（mock Context 的 filesDir 指向不存在的路径）
        val entities = listOf(
            BookEntity(id = 1, title = "孤立书籍", author = "作者", coverPath = null, bookDirPath = "", totalChapters = 5),
        )
        every { bookDao.getAllBooksIncludingPreparing() } returns flowOf(entities)

        // 执行：不应抛出异常
        repository.cleanupOrphanedBooks()

        // 验证：孤立记录被清理
        coVerify(exactly = 1) { bookDao.deleteBook(match { it.id == 1L }) }
    }
}
