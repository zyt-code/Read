package com.example.read.imports

import com.example.read.parser.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BookParserRouterTest {

    @Test
    fun routesTxtAndEpubExtensions() {
        val router = BookParserRouter()

        assertEquals(BookFormat.TXT, router.resolveFormat("notes.txt", "text/plain"))
        assertEquals(BookFormat.EPUB, router.resolveFormat("book.epub", "application/epub+zip"))
    }

    @Test
    fun unsupportedFormatsAreDetected() {
        val router = BookParserRouter()

        assertEquals(BookFormat.PDF, router.resolveFormat("manual.pdf", "application/pdf"))
        assertEquals(BookFormat.MOBI, router.resolveFormat("novel.mobi", "application/x-mobipocket-ebook"))
        assertEquals(BookFormat.UNKNOWN, router.resolveFormat("archive.bin", "application/octet-stream"))
    }

    @Test
    fun parserIsOnlyAvailableForSupportedMvpFormats() {
        val router = BookParserRouter()

        assertNotNull(router.parserFor(BookFormat.TXT))
        assertNotNull(router.parserFor(BookFormat.EPUB))
        assertNull(router.parserFor(BookFormat.PDF))
        assertNull(router.parserFor(BookFormat.MOBI))
        assertNull(router.parserFor(BookFormat.UNKNOWN))
    }
}
