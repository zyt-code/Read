package com.example.read.data.library

import com.example.read.data.database.BookEntity
import com.example.read.data.database.ChapterEntity
import com.example.read.data.database.ImportTaskEntity
import com.example.read.data.database.LibraryDao
import com.example.read.data.database.PageIndexEntity
import com.example.read.data.database.ReadingProgressEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryRepositoryTest {

    @Test
    fun observeBooksMapsEntitiesToDomainBooks() = runBlocking {
        val dao = FakeLibraryDao()
        dao.books.value = listOf(
            BookEntity(
                id = "book-1",
                fingerprint = "fingerprint-1",
                title = "Quiet Reading",
                author = "Read MVP",
                coverPath = null,
                storagePath = "/files/books/quiet.epub",
                format = "EPUB",
                sizeBytes = 2048,
                importedAtMillis = 1700000000000,
                lastOpenedAtMillis = null,
                isSupported = true,
            )
        )
        val repository = LibraryRepository(dao)

        val books = repository.observeBooks().first()

        assertEquals(1, books.size)
        assertEquals("Quiet Reading", books.first().title)
    }

    @Test
    fun existingFingerprintsReturnsSetFromDao() = runBlocking {
        val dao = FakeLibraryDao()
        dao.fingerprints = listOf("fingerprint-1", "fingerprint-2")
        val repository = LibraryRepository(dao)

        val fingerprints = repository.existingFingerprints()

        assertEquals(setOf("fingerprint-1", "fingerprint-2"), fingerprints)
    }

    private class FakeLibraryDao : LibraryDao {
        val books = MutableStateFlow<List<BookEntity>>(emptyList())
        var fingerprints: List<String> = emptyList()
        var bookById: BookEntity? = null
        var bookByFingerprint: BookEntity? = null
        var chapters: List<ChapterEntity> = emptyList()
        var chapter: ChapterEntity? = null
        var progress: ReadingProgressEntity? = null
        var pageIndexes: List<PageIndexEntity> = emptyList()
        var lastInsertedBook: BookEntity? = null
        var lastInsertedChapters: List<ChapterEntity> = emptyList()
        var lastInsertedPageIndexes: List<PageIndexEntity> = emptyList()
        var lastUpsertedProgress: ReadingProgressEntity? = null
        var lastUpsertedImportTask: ImportTaskEntity? = null
        var openedBookId: String? = null

        override fun observeBooks() = books
        override suspend fun getBook(bookId: String) = bookById
        override suspend fun getBookByFingerprint(fingerprint: String) = bookByFingerprint
        override suspend fun getFingerprints() = fingerprints
        override suspend fun getChapters(bookId: String) = chapters
        override suspend fun getChapter(bookId: String, chapterIndex: Int) = chapter
        override suspend fun getProgress(bookId: String) = progress
        override suspend fun getPageIndexes(bookId: String, configKey: String) = pageIndexes
        override suspend fun insertBook(book: BookEntity) {
            lastInsertedBook = book
        }
        override suspend fun insertChapters(chapters: List<ChapterEntity>) {
            lastInsertedChapters = chapters
        }
        override suspend fun insertPageIndexes(indexes: List<PageIndexEntity>) {
            lastInsertedPageIndexes = indexes
        }
        override suspend fun upsertProgress(progress: ReadingProgressEntity) {
            lastUpsertedProgress = progress
        }
        override suspend fun upsertImportTask(task: ImportTaskEntity) {
            lastUpsertedImportTask = task
        }
        override suspend fun deletePageIndexes(bookId: String, configKey: String) = Unit
        override suspend fun markBookOpened(bookId: String, openedAtMillis: Long) {
            openedBookId = bookId
        }
    }
}
