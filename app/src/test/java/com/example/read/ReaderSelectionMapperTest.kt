package com.example.read

import com.example.read.data.model.Book
import com.example.read.data.model.BookFormat
import com.example.read.data.model.Chapter
import com.example.read.data.model.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionMapperTest {

    @Test
    fun selectedReaderUsesPersistedChapterAndProgress() {
        val book = Book(
            id = "book-1",
            title = "Quiet Reading",
            author = "Read MVP",
            storagePath = "/files/books/quiet.txt",
            format = BookFormat.TXT,
        )
        val chapter = Chapter(
            id = "chapter-2",
            bookId = "book-1",
            title = "Second",
            index = 1,
            content = "Persisted text",
        )
        val progress = ReadingProgress(
            bookId = "book-1",
            chapterIndex = 1,
            pageIndex = 3,
            offset = 240,
        )

        val state = book.toSelectedReaderUiState(
            chapter = chapter,
            progress = progress,
            chapterCount = 4,
        )

        assertEquals("book-1", state.bookId)
        assertEquals("Quiet Reading", state.title)
        assertEquals("chapter-2", state.chapterId)
        assertEquals("Second", state.chapterTitle)
        assertEquals("Persisted text", state.chapterContent)
        assertEquals("Page 4", state.pageLabel)
        assertEquals(0.5f, state.progress, 0.01f)
    }

    @Test
    fun selectedReaderFallsBackWhenChapterIsMissing() {
        val book = Book(
            id = "book-1",
            title = "Quiet Reading",
            storagePath = "/files/books/quiet.txt",
            format = BookFormat.TXT,
        )

        val state = book.toSelectedReaderUiState(
            chapter = null,
            progress = null,
            chapterCount = 0,
        )

        assertEquals("book-1", state.chapterId)
        assertEquals("Quiet Reading", state.chapterTitle)
        assertEquals("", state.chapterContent)
        assertEquals("Page 1", state.pageLabel)
        assertEquals(0f, state.progress, 0.01f)
    }
}
