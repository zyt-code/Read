package com.example.read.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.read.domain.model.Bookmark

/**
 * 书签 Room 实体（v6 feature: 书签 / Schema v3）。
 *
 * 字段设计：
 * - id：自增主键，对外不要求稳定，仅做"删除单条书签"的索引
 * - bookId：外键关联 [BookEntity.id]；CASCADE 删除策略让书籍被删除时书签一起清理，
 *   避免幽灵书签（指向不存在的书）
 * - chapterIndex：章节 spine 索引（0-based），与 [Bookmark] 领域模型一致
 * - pageInChapter：章内页码（0-based），跨章跳转后由阅读器再次定位时使用
 * - note：用户备注（本期暂不实现编辑 UI，预留为 null）
 * - createdAt：创建时间戳（epoch millis），用于按"最近添加"倒序排序
 *
 * 索引：
 * - (bookId, createdAt DESC)：DAO 的 `getBookmarks(bookId)` 用此索引快速取
 *   "某本书的所有书签 按创建时间倒序"
 *
 * 外键 CASCADE：
 * - 删除书籍 → 关联书签自动删除，业务层无需手动清理
 *
 * @see Bookmark 领域模型
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId", "createdAt"], name = "idx_bookmarks_book_created"),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val pageInChapter: Int,
    val note: String? = null,
    val createdAt: Long,
)

/**
 * Entity -> 领域模型映射。
 *
 * 让 ViewModel 层与领域层不依赖 Room 注解，仅处理纯 Kotlin [Bookmark]。
 */
fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    pageInChapter = pageInChapter,
    note = note,
    createdAt = createdAt,
)

/**
 * 领域模型 -> Entity 映射。
 *
 * 用于 insert / delete 操作；id=0 时 Room 会按 autoGenerate 主键分配新 ID。
 */
fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    pageInChapter = pageInChapter,
    note = note,
    createdAt = createdAt,
)
