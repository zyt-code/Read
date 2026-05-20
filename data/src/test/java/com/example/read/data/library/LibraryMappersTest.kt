package com.example.read.data.library

import com.example.read.data.database.BookEntity
import com.example.read.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMappersTest {

    @Test
    fun bookEntityMapsToDomainBook() {
        val entity = BookEntity(
            id = "book-1",
            fingerprint = "quiet-2048-1700000000000",
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

        val book = entity.toDomain()

        assertEquals("book-1", book.id)
        assertEquals("Quiet Reading", book.title)
        assertEquals("Read MVP", book.author)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals("/files/books/quiet.epub", book.storagePath)
    }

    @Test
    fun unsupportedFormatFallsBackToUnknown() {
        assertEquals(BookFormat.UNKNOWN, "AZW3".toBookFormat())
    }
}
