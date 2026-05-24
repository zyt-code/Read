package com.example.read.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Update
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.read.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO（数据访问对象），定义 books 表的所有数据库操作。
 *
 * 设计要点：
 * - getAllBooks() 返回 Flow，数据库变化时自动通知 UI（响应式）
 * - 其他操作返回 suspend 函数，在协程中执行
 * - 查询按 lastReadAt 倒序排列，最近阅读的书排在最前面
 */
@Dao
interface BookDao {

    /**
     * 获取所有书籍的响应式数据流。
     * 返回 Flow 意味着当 books 表有任何变化（INSERT/UPDATE/DELETE）时，
     * 收集此 Flow 的代码会自动收到新的书籍列表。
     * 排序规则：最近阅读的书籍排在最前面。
     *
     * 过滤规则（P1-1）：
     * - 排除 bookDirPath 以 "PREPARING_" 开头的占位记录。这些是 BookRepositoryImpl
     *   `prepareImport` 写入、startImport 未完成期间的中间态记录，不应出现在书架。
     *   一旦 startImport 成功，bookDirPath 会被改写为真实路径，立即出现在书架；
     *   失败时由 ViewModel 主动 deleteBook 清除，或由 cleanupOrphanedBooks 的
     *   "幽灵记录" 兜底清除。
     * - 注意：getBookById 不做该过滤，因为导入流程内部要通过 ID 读取占位记录的
     *   PREPARING_<uuid> 字段以推断 sentinel 目录，属于合法访问。
     */
    @Query("SELECT * FROM books WHERE bookDirPath NOT LIKE 'PREPARING_%' ORDER BY lastReadAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    /**
     * 获取数据库内所有书籍（包含 PREPARING_ 占位记录）。
     * 仅供 BookRepositoryImpl.cleanupOrphanedBooks 使用：启动期扫描所有记录，
     * 把空字符串和过期的 PREPARING_ 幽灵记录都清理掉。其他场景请用 getAllBooks()。
     */
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC")
    fun getAllBooksIncludingPreparing(): Flow<List<BookEntity>>

    /**
     * 根据 ID 查询单本书籍。
     * 用于阅读器页面加载时获取书籍的元数据和文件路径。
     * 返回 null 表示书籍不存在（可能已被删除）。
     */
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    /**
     * 插入新书籍记录。
     * 使用 OnConflictStrategy.REPLACE 策略，如果 ID 冲突则替换。
     * 返回自增生成的 ID，用于构建完整的 Book 领域对象。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    /**
     * 删除书籍记录。
     * 注意：只删除数据库记录，文件清理在 Repository 层处理。
     */
    /**
     * 更新书籍记录。
     * 用于导入完成后更新 bookDirPath 等字段。
     */
    @Update
    suspend fun updateBook(book: BookEntity)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    /**
     * 更新阅读进度。
     * 同时更新章节索引和时间戳，时间戳用于书架排序。
     * 每次用户切换章节时调用，实现阅读进度的实时持久化。
     *
     * @param bookId 书籍 ID
     * @param chapterIndex 当前章节索引
     * @param timestamp 当前时间戳（epoch millis）
     */
    @Query("UPDATE books SET lastReadChapter = :chapterIndex, lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateReadingProgress(bookId: Long, chapterIndex: Int, timestamp: Long)
}
