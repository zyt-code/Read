package com.example.read.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.read.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书签 DAO（v6 feature: 书签 / Schema v3）。
 *
 * 设计要点：
 * - `getBookmarks(bookId)` 返回 [Flow]，让 ReaderViewModel 通过 stateIn 转为 StateFlow，
 *   UI 可以响应式刷新（添加 / 删除书签时无需手动 refresh）
 * - 按 createdAt DESC 排序，与"最近添加 → 最早添加"的用户预期一致；
 *   配合 `(bookId, createdAt)` 复合索引可走索引扫描，性能稳定
 * - insert 用 [OnConflictStrategy.REPLACE]：理论上不会冲突（id 自增），保留兜底
 * - 不提供 update 方法：书签字段在添加后通常不变（note 编辑不在本期范围）
 */
@Dao
interface BookmarkDao {

    /**
     * 获取指定书籍的所有书签，按创建时间倒序（最新在前）。
     *
     * 返回 [Flow] 实现响应式：调用方用 `stateIn` 转为 StateFlow，
     * insert / delete 后会自动发射新列表，UI 自动重组。
     *
     * @param bookId 书籍 ID
     */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun getBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    /**
     * 插入一条书签，返回自增主键。
     *
     * @return 新增书签的 id；用于即时反馈 / 后续删除引用
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    /**
     * 删除一条书签。
     *
     * 注意：仅删除数据库记录；UI 上的 Sheet 关闭由调用方负责。
     */
    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    /**
     * 根据 ID 删除书签。
     *
     * 用于 UI 拿到 bookmarkId 后直接调用，不必先查 BookmarkEntity 再 delete。
     */
    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteById(bookmarkId: Long)
}
