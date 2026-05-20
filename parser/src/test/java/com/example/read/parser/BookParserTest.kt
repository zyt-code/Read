package com.example.read.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BookParserTest {

    @Test
    fun txtParserExtractsTitleAndChaptersFromPlainText() {
        val parser = TxtBookParser()
        val text = """
            The Silent Library
            
            Chapter One
            Morning light settled on the desk.
            
            Chapter Two
            The room grew quiet again.
        """.trimIndent()

        val parsed = parser.parse(
            input = ByteArrayInputStream(text.toByteArray()),
            fileName = "silent-library.txt",
        )

        assertEquals("The Silent Library", parsed.metadata.title)
        assertEquals(BookFormat.TXT, parsed.metadata.format)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter One", parsed.chapters.first().title)
        assertTrue(parsed.chapters.first().content.contains("Morning light"))
    }

    @Test
    fun epubParserDelegatesToAdapterForPredictableMetadata() {
        val adapter = FakeEpubMetadataReader(
            result = ParsedBook(
                metadata = ParsedBookMetadata(
                    title = "Adapter Title",
                    author = "Adapter Author",
                    format = BookFormat.EPUB,
                ),
                chapters = listOf(ParsedChapter(index = 0, title = "Start", content = "Hello")),
            ),
        )
        val parser = EpubBookParser(adapter)

        val parsed = parser.parse(ByteArrayInputStream(ByteArray(0)), "adapter.epub")

        assertEquals("Adapter Title", parsed.metadata.title)
        assertEquals("Adapter Author", parsed.metadata.author)
        assertEquals(BookFormat.EPUB, parsed.metadata.format)
        assertEquals("Start", parsed.chapters.single().title)
    }

    private class FakeEpubMetadataReader(
        private val result: ParsedBook,
    ) : EpubMetadataReader {
        override fun read(input: java.io.InputStream, fileName: String): ParsedBook = result
    }
}
