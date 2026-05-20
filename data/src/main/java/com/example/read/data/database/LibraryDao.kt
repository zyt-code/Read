package com.example.read.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY last_opened_at_millis DESC, imported_at_millis DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getBookByFingerprint(fingerprint: String): BookEntity?

    @Query("SELECT fingerprint FROM books")
    suspend fun getFingerprints(): List<String>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index ASC")
    suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId AND chapter_index = :chapterIndex LIMIT 1")
    suspend fun getChapter(bookId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId LIMIT 1")
    suspend fun getProgress(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM page_indexes WHERE book_id = :bookId AND config_key = :configKey ORDER BY chapter_id ASC, page_index ASC")
    suspend fun getPageIndexes(bookId: String, configKey: String): List<PageIndexEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageIndexes(indexes: List<PageIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportTask(task: ImportTaskEntity)

    @Query("DELETE FROM page_indexes WHERE book_id = :bookId AND config_key = :configKey")
    suspend fun deletePageIndexes(bookId: String, configKey: String)

    @Query("UPDATE books SET last_opened_at_millis = :openedAtMillis WHERE id = :bookId")
    suspend fun markBookOpened(bookId: String, openedAtMillis: Long)

    @Transaction
    suspend fun insertImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        pageIndexes: List<PageIndexEntity>,
        progress: ReadingProgressEntity,
        importTask: ImportTaskEntity,
    ) {
        insertBook(book)
        insertChapters(chapters)
        insertPageIndexes(pageIndexes)
        upsertProgress(progress)
        upsertImportTask(importTask)
    }
}
