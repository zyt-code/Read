package com.example.read.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["title"]),
        Index(value = ["last_opened_at_millis"]),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val title: String,
    val author: String?,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "storage_path") val storagePath: String,
    val format: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "imported_at_millis") val importedAtMillis: Long,
    @ColumnInfo(name = "last_opened_at_millis") val lastOpenedAtMillis: Long?,
    @ColumnInfo(name = "is_supported") val isSupported: Boolean,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id", "chapter_index"], unique = true),
        Index(value = ["book_id"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    val title: String,
    val content: String,
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "offset") val offset: Int,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

@Entity(
    tableName = "page_indexes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id", "chapter_id", "config_key", "page_index"], unique = true),
        Index(value = ["book_id", "config_key"]),
    ],
)
data class PageIndexEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "config_key") val configKey: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
)

@Entity(tableName = "import_tasks", indices = [Index(value = ["fingerprint"])])
data class ImportTaskEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val status: String,
    val message: String?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

@Fts4(contentEntity = ChapterEntity::class)
@Entity(tableName = "chapter_fts")
data class ChapterFtsEntity(
    val title: String,
    val content: String,
)
