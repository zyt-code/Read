package com.example.read.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.read.domain.model.Book

/**
 * Room 数据库实体，对应 books 表。
 *
 * 存储书籍的持久化信息：
 * - 元数据：标题、作者、总章节数
 * - 文件路径：EPUB 文件和封面图片在 app 内部存储中的路径
 * - 阅读状态：上次阅读的章节索引和时间戳
 *
 * 使用 @PrimaryKey(autoGenerate = true) 实现自增主键，
 * Room 在 insert 时自动分配 ID 并返回。
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,           // 书籍标题，来自 EPUB metadata
    val author: String,          // 作者姓名
    val coverPath: String?,      // 封面图片文件路径，可能为 null（EPUB 无封面时）
    val bookDirPath: String,        // EPUB 解包目录在内部存储中的路径
    val totalChapters: Int,      // 总章节数，用于阅读器显示进度
    val lastReadChapter: Int = 0, // 上次阅读的章节索引（0-based）
    val lastReadAt: Long = 0L,   // 上次阅读的时间戳（epoch millis），用于排序
)

/**
 * 将 Room 实体转换为领域模型。
 * 这是数据层到领域层的映射，确保领域层不依赖 Room 注解。
 */
fun BookEntity.toDomain() = Book(
    id = id,
    title = title,
    author = author,
    coverPath = coverPath,
    bookDirPath = bookDirPath,
    totalChapters = totalChapters,
    lastReadChapter = lastReadChapter,
    lastReadAt = lastReadAt,
)

/**
 * 将领域模型转换为 Room 实体。
 * 用于 insert 和 delete 操作，将领域层的数据传递给数据层持久化。
 */
fun Book.toEntity() = BookEntity(
    id = id,
    title = title,
    author = author,
    coverPath = coverPath,
    bookDirPath = bookDirPath,
    totalChapters = totalChapters,
    lastReadChapter = lastReadChapter,
    lastReadAt = lastReadAt,
)
