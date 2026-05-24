package com.example.read.domain.repository

import android.content.Context
import android.net.Uri
import com.example.read.domain.model.Book
import com.example.read.domain.model.Bookmark
import com.example.read.util.BookMetadata
import java.io.File
import com.example.read.domain.model.Chapter
import kotlinx.coroutines.flow.Flow

/**
 * 书籍仓库接口，定义所有数据访问操作的契约。
 *
 * 这是领域层的核心接口，ViewModel 只依赖此接口，不直接依赖 Room 或文件系统。
 * 具体实现在 data/repository/BookRepositoryImpl 中。
 *
 * 设计原则：
 * - getAllBooks() 返回 Flow，支持响应式数据流
 * - 其他方法为 suspend 函数，在协程中执行
 * - importBook 需要 Context 参数，因为要通过 ContentResolver 读取 SAF URI
 */
interface BookRepository {

    /** 获取所有书籍的响应式数据流，按最近阅读排序 */
    fun getAllBooks(): Flow<List<Book>>

    /** 根据 ID 获取单本书籍，返回 null 表示不存在 */
    suspend fun getBookById(id: Long): Book?

    /** 导入 EPUB 文件：解析、复制、保存封面、写入数据库 */
    suspend fun importBook(uri: Uri, context: Context): Book

    /**
     * 快速准备导入：提取元数据 + 插入占位记录。
     * 书籍立即出现在书架上（coverPath 和 bookDirPath 为空）。
     *
     * @return 占位书籍的 ID
     */
    suspend fun prepareImport(uri: Uri, context: Context): Long

    /**
     * 完整解包 EPUB，更新占位记录。
     * 在后台线程执行，通过 onProgress 报告进度。
     *
     * @param bookId prepareImport 返回的占位书籍 ID
     * @param uri SAF 文件选择器返回的 EPUB 文件 URI
     * @param context Android Context
     * @param onProgress 进度回调，0.0~1.0
     */
    suspend fun startImport(bookId: Long, uri: Uri, context: Context, onProgress: (Float) -> Unit)

    /** 删除书籍：清理文件系统和数据库 */
    suspend fun deleteBook(book: Book)

    /** 更新阅读进度：记录当前章节索引和时间戳 */
    suspend fun updateReadingProgress(bookId: Long, chapterIndex: Int)

    /** 获取指定章节的纯文本内容，按需从解包目录读取 HTML 并转换 */
    suspend fun getChapterContent(bookId: Long, chapterIndex: Int): Chapter

    /** 获取指定章节的 HTML 文件对象 */
    suspend fun getChapterHtmlFile(bookId: Long, chapterIndex: Int): File

    /**
     * 获取指定章节的纯文本内容（不含 HTML 标签）。
     *
     * v5 跨章节全书搜索（[com.example.read.data.search.BookSearchEngine]）使用，
     * 比 [getChapterContent] 更轻量：不返回 Chapter 元数据，只返回纯文本。
     *
     * 行为：
     * - 内部读取 HTML 文件后用 Jsoup.parse(html).text() 提取纯文本
     * - 找不到章节 / 文件不存在 / 读取失败时返回 null（不抛异常），
     *   让搜索引擎可以安全跳过该章节继续处理其他章
     *
     * @param bookId 书籍 ID
     * @param chapterIndex 章节索引（0-based）
     * @return 章节纯文本；返回 null 表示章节不存在或读取失败
     */
    suspend fun getChapterPlainText(bookId: Long, chapterIndex: Int): String?

    /** 获取书籍的结构化元数据（从 metadata.json 反序列化） */
    suspend fun getBookMetadata(bookId: Long): BookMetadata

    /**
     * 清理孤立的书籍记录（数据库中存在但文件系统中已损坏或缺失的记录）。
     * 通常在应用启动时调用，自动修复因崩溃或迁移产生的不一致数据。
     */
    suspend fun cleanupOrphanedBooks()

    // ====================== v6 书签相关 API ======================

    /**
     * 获取指定书籍的所有书签，按 createdAt DESC 排序（最新在前）。
     *
     * 返回 [Flow]，让 ViewModel 用 stateIn 转为 StateFlow，
     * insert / delete 后 UI 自动响应式刷新。
     *
     * @param bookId 书籍 ID
     * @return 书签 Flow；若书无书签则发射空列表
     */
    fun getBookmarks(bookId: Long): Flow<List<Bookmark>>

    /**
     * 添加一条书签。
     *
     * 调用方提供 (bookId, chapterIndex, pageInChapter)，
     * createdAt 由 Repository 设为 System.currentTimeMillis()，id 由 Room 自增分配。
     * 实施时 note 字段传 null（本期不实现编辑 UI）。
     *
     * @return 新增书签的 id（用于即时反馈 / SnackBar / 后续删除）
     */
    suspend fun addBookmark(bookId: Long, chapterIndex: Int, pageInChapter: Int, note: String? = null): Long

    /**
     * 删除一条书签。
     *
     * @param bookmarkId 书签 ID
     */
    suspend fun removeBookmark(bookmarkId: Long)
}
