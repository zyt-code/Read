package com.example.read.ui

import com.example.read.data.model.Book
import com.example.read.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookshelfUiContractTest {

    @Test
    fun bookshelfTitleUsesImportedBookTitleAndAuthor() {
        val book = Book(
            id = "book-1",
            title = "Quiet Reading",
            author = "Read MVP",
            storagePath = "/files/books/quiet.epub",
            format = BookFormat.EPUB,
        )

        val item = book.toBookshelfItem()

        assertEquals("book-1", item.id)
        assertEquals("Quiet Reading", item.title)
        assertEquals("Read MVP · EPUB", item.subtitle)
    }

    @Test
    fun bookshelfSubtitleUsesOnlyFormatWhenAuthorIsMissing() {
        val book = Book(
            id = "book-2",
            title = "Plain Text Notes",
            author = null,
            storagePath = "/files/books/plain.txt",
            format = BookFormat.TXT,
        )

        val item = book.toBookshelfItem()

        assertEquals("TXT", item.subtitle)
    }

    @Test
    fun readUiStateCarriesSelectedReaderContentForAppWiring() {
        val selectedReader = SelectedReaderUiState(
            bookId = "book-1",
            title = "Quiet Reading",
            chapterId = "chapter-1",
            chapterTitle = "Morning",
            chapterContent = "Real persisted chapter text.",
            pageLabel = "Page 2",
            progress = 0.5f,
        )

        val state = ReadUiState(selectedReader = selectedReader)

        assertEquals("book-1", state.selectedReader?.bookId)
        assertEquals("Morning", state.selectedReader?.chapterTitle)
        assertEquals("Real persisted chapter text.", state.selectedReader?.chapterContent)
        assertEquals("Page 2", state.selectedReader?.pageLabel)
        assertEquals(0.5f, state.selectedReader?.progress ?: 0f, 0.01f)
    }

    @Test
    fun defaultReadUiStateStartsWithoutSelectedReader() {
        assertNull(ReadUiState().selectedReader)
    }
}
