package com.example.read.data.library

import com.example.read.data.database.BookEntity
import com.example.read.data.database.ChapterEntity
import com.example.read.data.database.ImportTaskEntity
import com.example.read.data.database.LibraryDao
import com.example.read.data.database.PageIndexEntity
import com.example.read.data.database.ReadingProgressEntity
import com.example.read.data.model.Book
import com.example.read.data.model.Chapter
import com.example.read.data.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(
    private val dao: LibraryDao,
) {
    fun observeBooks(): Flow<List<Book>> = dao.observeBooks().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun existingFingerprints(): Set<String> = dao.getFingerprints().toSet()

    suspend fun getBook(bookId: String): Book? = dao.getBook(bookId)?.toDomain()

    suspend fun getChapters(bookId: String): List<Chapter> = dao.getChapters(bookId).map { it.toDomain() }

    suspend fun getChapter(bookId: String, chapterIndex: Int): Chapter? {
        return dao.getChapter(bookId, chapterIndex)?.toDomain()
    }

    suspend fun getProgress(bookId: String): ReadingProgress? = dao.getProgress(bookId)?.toDomain()

    suspend fun markBookOpened(bookId: String, openedAtMillis: Long) {
        dao.markBookOpened(bookId, openedAtMillis)
    }

    suspend fun insertImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        pageIndexes: List<PageIndexEntity>,
        progress: ReadingProgressEntity,
        importTask: ImportTaskEntity,
    ) {
        dao.insertImportedBook(
            book = book,
            chapters = chapters,
            pageIndexes = pageIndexes,
            progress = progress,
            importTask = importTask,
        )
    }
}
