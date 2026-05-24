package com.example.read.data.repository

import android.content.Context
import android.net.Uri
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.local.entity.BookmarkEntity
import com.example.read.data.local.entity.toDomain
import com.example.read.data.local.entity.toEntity
import com.example.read.domain.model.Book
import com.example.read.domain.model.Bookmark
import com.example.read.domain.model.Chapter
import com.example.read.domain.repository.BookRepository
import com.example.read.util.BookMetadata
import com.example.read.util.EpubParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File

class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val context: Context,
) : BookRepository {

    private val epubParser = EpubParser()
    private val json = Json { ignoreUnknownKeys = true }

    private val booksDir: File
        get() = File(context.filesDir, "books").also { it.mkdirs() }

    private val coversDir: File
        get() = File(context.filesDir, "covers").also { it.mkdirs() }

    override fun getAllBooks(): Flow<List<Book>> {
        return bookDao.getAllBooks().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getBookById(id: Long): Book? = withContext(Dispatchers.IO) {
        bookDao.getBookById(id)?.toDomain()
    }


    override suspend fun importBook(uri: Uri, context: Context): Book = withContext(Dispatchers.IO) {
        // 通过 SAF URI 打开文件输入流
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法打开所选文件")

        // 使用 UUID 作为临时目录名，避免并发导入时时间戳冲突
        val tempDir = File(booksDir, "temp_${java.util.UUID.randomUUID()}")

        try {
            // Step 1: 解包 EPUB 到临时目录，解析 OPF/NCX 元数据
            val unpackResult = inputStream.use { epubParser.unpack(it, tempDir) }

            // Step 2: 先插入数据库获取自增 ID，bookDirPath 暂时为空
            val book = Book(
                title = unpackResult.title,
                author = unpackResult.author,
                coverPath = null,
                bookDirPath = "",
                totalChapters = unpackResult.metadata.spine.size,
            )
            val id = bookDao.insertBook(book.toEntity())

            // Step 3: 用数据库 ID 构建最终目录路径，重命名临时目录
            val finalDir = File(booksDir, id.toString())
            val renamed = tempDir.renameTo(finalDir)
            if (!renamed) {
                // renameTo 在跨文件系统或权限受限时可能失败，
                // 使用 Files.move 作为兜底方案，支持跨文件系统移动
                java.nio.file.Files.move(
                    tempDir.toPath(),
                    finalDir.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }

            // Step 4: 保存封面图片（用 bookId 命名，避免标题特殊字符导致文件名碰撞）
            var coverPath: String? = null
            if (unpackResult.coverBytes != null) {
                val coverFile = File(coversDir, "$id.jpg")
                coverFile.writeBytes(unpackResult.coverBytes)
                coverPath = coverFile.absolutePath
            }

            // Step 5: 更新数据库记录，写入最终的目录路径和封面路径
            val finalBook = book.copy(id = id, bookDirPath = finalDir.absolutePath, coverPath = coverPath)
            bookDao.updateBook(finalBook.toEntity())

            finalBook
        } catch (e: CancellationException) {
            // P1-v6-3：协程取消时仍要清理临时目录，然后重抛 CancellationException
            // 让 viewModelScope 的取消信号正常传播；不能被 catch (Exception) 吞掉
            tempDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            // 任何步骤失败都清理临时目录，防止孤立文件残留
            tempDir.deleteRecursively()
            throw e
        }
    }

    override suspend fun prepareImport(uri: Uri, context: Context): Long = withContext(Dispatchers.IO) {
        // 快速提取元数据（仅解析 container.xml + OPF，不提取全部文件）
        val quickMeta = context.contentResolver.openInputStream(uri)?.use { stream ->
            epubParser.quickExtractMetadata(stream)
        } ?: throw IllegalArgumentException("无法打开所选文件")

        // 提取封面图片（单独打开流，仅提取图片类型的 ZIP 条目）
        val coverBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            epubParser.extractCoverFromZip(stream, quickMeta.coverId)
        }

        // 在插入占位记录前先在文件系统创建临时哨兵目录（temp_<uuid>），
        // 该目录的存在让本进程与其他并发清理任务知道"导入正在进行中"，
        // 从而提早把"占位记录已存在但导入未完成"的窗口锁住（P0-5）。
        val preparingUuid = java.util.UUID.randomUUID().toString()
        val sentinelDir = File(booksDir, "temp_$preparingUuid")
        sentinelDir.mkdirs()

        // 占位记录的 bookDirPath 使用 PREPARING_ 前缀 + uuid 标识，
        // 让 cleanupOrphanedBooks 可以识别"正在准备中"的记录，跳过清理。
        // 既不动 schema，又比"空字符串 + temp_ 目录"的兜底更可靠。
        val placeholderPath = "PREPARING_$preparingUuid"
        val placeholder = Book(
            title = quickMeta.title,
            author = quickMeta.author,
            coverPath = null,
            bookDirPath = placeholderPath,
            totalChapters = quickMeta.totalChapters,
        )
        val id = bookDao.insertBook(placeholder.toEntity())

        // 保存封面（用 bookId 命名），有封面时立即更新数据库让书架显示
        if (coverBytes != null) {
            val coverFile = File(coversDir, "$id.jpg")
            coverFile.writeBytes(coverBytes)
            bookDao.updateBook(
                placeholder.toEntity().copy(id = id, coverPath = coverFile.absolutePath),
            )
        }

        id
    }

    override suspend fun startImport(bookId: Long, uri: Uri, context: Context, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val tempDir = File(booksDir, "temp_${java.util.UUID.randomUUID()}")
            // 解析占位记录的 PREPARING_<uuid> 对应的 sentinel 目录，在最终成功 / 失败时
            // 一并清理，避免 temp_ 目录长期残留（P0-5 配套）
            val placeholderPath = bookDao.getBookById(bookId)?.bookDirPath.orEmpty()
            val sentinelDir: File? = if (placeholderPath.startsWith("PREPARING_")) {
                val uuid = placeholderPath.removePrefix("PREPARING_")
                File(booksDir, "temp_$uuid")
            } else null
            try {
                // 完整解包 EPUB（ZIP 解压 + OPF 解析 + NCX 解析 + spine 构建）
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("无法打开所选文件")

                val unpackResult = inputStream.use { epubParser.unpack(it, tempDir) }
                onProgress(0.7f)

                // 重命名临时目录为最终目录
                val finalDir = File(booksDir, bookId.toString())
                val renamed = tempDir.renameTo(finalDir)
                if (!renamed) {
                    java.nio.file.Files.move(
                        tempDir.toPath(),
                        finalDir.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                onProgress(0.9f)

                // 获取已有的封面路径（prepareImport 阶段已保存）
                val existingCoverPath = bookDao.getBookById(bookId)?.coverPath

                // 更新数据库记录，写入完整的书籍信息（bookDirPath 从 PREPARING_ 前缀切到真实路径）
                val finalBook = Book(
                    id = bookId,
                    title = unpackResult.title,
                    author = unpackResult.author,
                    coverPath = existingCoverPath,
                    bookDirPath = finalDir.absolutePath,
                    totalChapters = unpackResult.metadata.spine.size,
                )
                bookDao.updateBook(finalBook.toEntity())
                onProgress(1.0f)
                // 成功路径：清理 sentinel 目录（启动哨兵的使命结束）
                sentinelDir?.takeIf { it.exists() }?.deleteRecursively()
            } catch (e: CancellationException) {
                // P1-v6-3：取消也需要清理 sentinel 目录与占位记录，避免幽灵残留；
                // 但必须重抛 CancellationException 让结构化并发链路正确传播
                runCatching { tempDir.deleteRecursively() }
                sentinelDir?.takeIf { it.exists() }?.let { runCatching { it.deleteRecursively() } }
                runCatching { bookDao.getBookById(bookId)?.let { bookDao.deleteBook(it) } }
                throw e
            } catch (e: Exception) {
                // 解包失败时清理临时目录、sentinel 目录、占位记录
                runCatching { tempDir.deleteRecursively() }
                sentinelDir?.takeIf { it.exists() }?.let { runCatching { it.deleteRecursively() } }
                bookDao.getBookById(bookId)?.let { bookDao.deleteBook(it) }
                throw e
            }
        }

    /**
     * 删除书籍：清理文件系统和数据库记录。
     *
     * B5 改进：deleteRecursively() 返回值会被检查，删除失败时记录日志，
     * 但仍继续删除数据库记录（避免幽灵记录长期残留），并不上抛异常。
     * 若希望严格阻塞，可在调用方根据 errorMessage 提示用户手动清理。
     */
    override suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        if (book.bookDirPath.isNotEmpty()) {
            val dir = File(book.bookDirPath)
            if (dir.exists()) {
                val ok = try {
                    dir.deleteRecursively()
                } catch (e: CancellationException) {
                    // P1-v6-3：协程取消必须重抛，不能与 deleteRecursively 的业务异常混为一谈
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("BookRepository", "deleteRecursively threw", e)
                    false
                }
                if (!ok) {
                    // 删除失败：可能是文件被占用（如 WebView 渲染进程未释放）或权限受限。
                    // 不上抛、不阻塞 DB 清理，仅记录日志，便于后续诊断幽灵目录。
                    android.util.Log.w(
                        "BookRepository",
                        "Failed to delete book directory: ${book.bookDirPath}",
                    )
                }
            }
        }
        book.coverPath?.let { coverPath ->
            val coverFile = File(coverPath)
            if (coverFile.exists()) {
                val ok = try {
                    coverFile.delete()
                } catch (e: CancellationException) {
                    // P1-v6-3：同上，取消信号必须重抛
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("BookRepository", "cover delete threw", e)
                    false
                }
                if (!ok) {
                    android.util.Log.w(
                        "BookRepository",
                        "Failed to delete cover file: $coverPath",
                    )
                }
            }
        }
        bookDao.deleteBook(book.toEntity())
    }

    override suspend fun updateReadingProgress(bookId: Long, chapterIndex: Int) =
        withContext(Dispatchers.IO) {
            bookDao.updateReadingProgress(bookId, chapterIndex, System.currentTimeMillis())
        }

    override suspend fun getChapterContent(bookId: Long, chapterIndex: Int): Chapter =
        withContext(Dispatchers.IO) {
            val bookEntity = bookDao.getBookById(bookId)
                ?: throw IllegalArgumentException("Book not found: $bookId")
            val metadata = readMetadata(bookEntity.bookDirPath)
            if (chapterIndex < 0 || chapterIndex >= metadata.spine.size) {
                throw IndexOutOfBoundsException("Chapter index $chapterIndex out of range")
            }
            val spineItem = metadata.spine[chapterIndex]
            // P3-v6-2：复用 readChapterPlainText 共用方法，消除与 getChapterPlainText 的重复逻辑
            val (plainText, htmlFile) = readChapterPlainText(bookEntity.bookDirPath, metadata.opfDir, spineItem.href)
                ?: throw IllegalStateException("HTML file not found for chapter $chapterIndex")
            Chapter(
                index = chapterIndex,
                title = spineItem.title.ifEmpty { "Chapter ${chapterIndex + 1}" },
                content = plainText,
                htmlPath = htmlFile.absolutePath,
            )
        }

    override suspend fun getChapterHtmlFile(bookId: Long, chapterIndex: Int): File =
        withContext(Dispatchers.IO) {
            val bookEntity = bookDao.getBookById(bookId)
                ?: throw IllegalArgumentException("Book not found: $bookId")
            val metadata = readMetadata(bookEntity.bookDirPath)
            if (chapterIndex < 0 || chapterIndex >= metadata.spine.size) {
                throw IndexOutOfBoundsException("Chapter index $chapterIndex out of range")
            }
            resolveHtmlFile(bookEntity.bookDirPath, metadata.opfDir, metadata.spine[chapterIndex].href)
        }

    /**
     * 获取指定章节的纯文本内容（v5 跨章节全书搜索使用）。
     *
     * 与 [getChapterContent] 的区别：
     * - 不返回 Chapter 元数据，只返回纯文本字符串
     * - 失败路径返回 null 而非抛异常 —— 全书搜索遍历整本书时不希望单章异常
     *   中断整个搜索流程（如 EPUB 中某章 HTML 损坏）
     *
     * 实现路径：复用 [getChapterHtmlFile] 解析 metadata + 定位文件，再用 Jsoup 提取纯文本。
     */
    override suspend fun getChapterPlainText(bookId: Long, chapterIndex: Int): String? =
        withContext(Dispatchers.IO) {
            try {
                val bookEntity = bookDao.getBookById(bookId) ?: return@withContext null
                if (bookEntity.bookDirPath.isEmpty() ||
                    bookEntity.bookDirPath.startsWith("PREPARING_")
                ) {
                    // 占位记录 / 未完成导入的书籍：直接返回 null，避免搜索意外读到不可用路径
                    return@withContext null
                }
                val metadata = readMetadata(bookEntity.bookDirPath)
                if (chapterIndex < 0 || chapterIndex >= metadata.spine.size) return@withContext null
                // P3-v6-2：复用 readChapterPlainText 共用方法，消除与 getChapterContent 的重复逻辑
                readChapterPlainText(bookEntity.bookDirPath, metadata.opfDir, metadata.spine[chapterIndex].href)?.first
            } catch (e: CancellationException) {
                // P1-v6-3：全书搜索引擎遍历章节时若被取消（用户切换 query / 退出搜索），
                // 必须把取消信号传给 BookSearchEngine.searchOneChapter 的 catch，
                // 让 flatMapMerge 链路正常完成取消传播；不能当作业务异常返回 null
                throw e
            } catch (e: Exception) {
                // 单章失败不影响整体搜索；记录日志便于诊断但向上传 null
                android.util.Log.w(
                    "BookRepository",
                    "getChapterPlainText failed: bookId=$bookId chapter=$chapterIndex",
                    e,
                )
                null
            }
        }

    override suspend fun getBookMetadata(bookId: Long): BookMetadata =
        withContext(Dispatchers.IO) {
            val bookEntity = bookDao.getBookById(bookId)
                ?: throw IllegalArgumentException("Book not found: $bookId")
            readMetadata(bookEntity.bookDirPath)
        }

    /**
     * 从 HTML 文件提取纯文本内容（P3-v6-2 共用底层方法）。
     *
     * 消除 [getChapterContent] 与 [getChapterPlainText] 中重复的
     * "定位 HTML 文件 → 读取内容 → Jsoup 解析为纯文本"逻辑。
     *
     * 调用方需自行完成书籍存在性校验、metadata 解析和章节索引边界检查。
     * 此方法仅负责文件 I/O + HTML 解析，不抛业务异常 —— 文件不存在时返回 null。
     *
     * @param bookDirPath 书籍解包目录的绝对路径
     * @param opfDir OPF 文件相对于 bookDirPath 的目录（可能为空串）
     * @param href 章节 HTML 文件相对于 opfDir 的路径
     * @return Pair(plainText, htmlFile)；文件不存在或路径无效时返回 null
     */
    private fun readChapterPlainText(bookDirPath: String, opfDir: String, href: String): Pair<String, File>? {
        val htmlFile = resolveHtmlFile(bookDirPath, opfDir, href)
        if (!htmlFile.exists()) return null
        val html = htmlFile.readText(Charsets.UTF_8)
        return Pair(Jsoup.parse(html).text(), htmlFile)
    }
    private fun readMetadata(bookDirPath: String): BookMetadata {
        val metadataFile = File(bookDirPath, "metadata.json")
        if (!metadataFile.exists()) {
            throw IllegalStateException("metadata.json not found in $bookDirPath")
        }
        return json.decodeFromString<BookMetadata>(metadataFile.readText(Charsets.UTF_8))
    }

    private fun resolveHtmlFile(bookDirPath: String, opfDir: String, href: String): File {
        val basePath = if (opfDir.isEmpty()) bookDirPath else "$bookDirPath/$opfDir"
        return File(basePath, href)
    }

    companion object {
        /**
         * PREPARING_ 占位记录的"幽灵阈值"（毫秒）：超过该时长仍未完成导入的占位记录，
         * 视为进程崩溃/被杀留下的真正残留，启动清理时直接删除。
         *
         * 为什么是 1 小时？正常的两阶段导入（prepareImport + startImport）应在
         * 数秒到数十秒内完成；即使是几百 MB 的超大 EPUB，1 小时也足够覆盖。
         * 给一个宽松上限避免误清理正在进行中的导入。
         */
        private const val PREPARING_GHOST_THRESHOLD_MS = 60L * 60L * 1000L
    }

    /**
     * 清理孤立的书籍记录。
     *
     * 孤立判定规则（P0-5 / P1-1 加强）：
     * - bookDirPath 为空字符串：历史遗留的占位记录（v1 → v2 迁移产生），
     *   或非常老版本的导入失败残留。这类记录可以安全删除。
     * - bookDirPath 以 "PREPARING_" 前缀开头：
     *   - 若 lastReadAt 距离当前时间 < 1 小时（PREPARING_GHOST_THRESHOLD_MS），
     *     视为正在进行中的导入，跳过。startImport 自己会处理成功/失败状态。
     *   - 若 lastReadAt 距离当前时间 >= 1 小时，视为进程崩溃留下的"幽灵记录"
     *     （上一轮 P0-5 未处理的兜底场景），直接 deleteBook 清除。
     *     避免用户在书架上看到一本永远点不开的书。
     *
     * 该方法走 getAllBooksIncludingPreparing 取全量记录（包含 PREPARING_），
     * 与 UI 用的 getAllBooks（已过滤 PREPARING_）形成职责分离。
     */
    override suspend fun cleanupOrphanedBooks() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        bookDao.getAllBooksIncludingPreparing().first().forEach { entity ->
            val path = entity.bookDirPath
            when {
                // PREPARING_ 占位记录：根据时间戳判断是活跃导入还是幽灵
                path.startsWith("PREPARING_") -> {
                    if (now - entity.lastReadAt >= PREPARING_GHOST_THRESHOLD_MS) {
                        // 超过 1 小时仍是占位状态，认定为崩溃残留，清理之
                        bookDao.deleteBook(entity)
                    }
                    // 否则视为正在进行中的导入，跳过
                }
                // 空字符串：v1→v2 迁移残留或老版本错误占位，删除
                path.isEmpty() -> bookDao.deleteBook(entity)
                else -> Unit
            }
        }
    }

    // ====================== v6 书签 API 实现 ======================

    /**
     * 获取指定书籍的所有书签（Flow，按 createdAt DESC 排序）。
     *
     * 实现：直接转发 [BookmarkDao.getBookmarks]，map 阶段把 BookmarkEntity 转 Bookmark
     * 让上层完全不接触 Room Entity。
     *
     * 线程：Room Flow 在收集时自动切到 IO 线程；调用方（ViewModel）用 stateIn 收集即可。
     */
    override fun getBookmarks(bookId: Long): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarks(bookId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    /**
     * 添加一条书签。
     *
     * - createdAt 在 Repository 层用 System.currentTimeMillis() 填充，
     *   保证多次调用 timestamp 单调（避免 ViewModel 与测试调用方各算各的造成排序错乱）
     * - id 留 0 让 Room 自增分配
     * - 切到 Dispatchers.IO 避免在主线程做 SQLite 写
     *
     * @return 新增书签的 id
     */
    override suspend fun addBookmark(
        bookId: Long,
        chapterIndex: Int,
        pageInChapter: Int,
        note: String?,
    ): Long = withContext(Dispatchers.IO) {
        val entity = BookmarkEntity(
            id = 0,
            bookId = bookId,
            chapterIndex = chapterIndex,
            pageInChapter = pageInChapter,
            note = note,
            createdAt = System.currentTimeMillis(),
        )
        bookmarkDao.insert(entity)
    }

    /**
     * 根据 ID 删除书签。
     *
     * 复用 [BookmarkDao.deleteById] 走单条 SQL，省去先查后删；不存在的 id 静默 no-op。
     */
    override suspend fun removeBookmark(bookmarkId: Long) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteById(bookmarkId)
    }
}