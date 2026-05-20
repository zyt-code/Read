package com.example.read.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationEngineTest {

    @Test
    fun paginatesChapterIntoStableCharacterWindows() {
        val engine = PaginationEngine()
        val chapter = ReaderChapter(
            id = "chapter-1",
            title = "Chapter One",
            content = "abcdefghijklmnopqrstuvwxyz",
        )

        val pages = engine.paginate(chapter, PaginationConfig(maxCharsPerPage = 10))

        assertEquals(3, pages.size)
        assertEquals("abcdefghij", pages[0].text)
        assertEquals(0, pages[0].startOffset)
        assertEquals(10, pages[0].endOffset)
        assertEquals("uvwxyz", pages[2].text)
    }

    @Test
    fun preservesWordBoundaryWhenPossible() {
        val engine = PaginationEngine()
        val chapter = ReaderChapter(
            id = "chapter-1",
            title = "Chapter One",
            content = "quiet reading needs room",
        )

        val pages = engine.paginate(chapter, PaginationConfig(maxCharsPerPage = 13))

        assertEquals("quiet reading", pages.first().text)
        assertTrue(pages[1].text.startsWith("needs"))
    }
}
