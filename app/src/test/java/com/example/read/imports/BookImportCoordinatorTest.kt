package com.example.read.imports

import com.example.read.data.imports.BookImportPlanner
import com.example.read.data.imports.ImportCandidate
import com.example.read.parser.BookFormat
import com.example.read.parser.ParsedBook
import com.example.read.parser.ParsedBookMetadata
import com.example.read.parser.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImportCoordinatorTest {

    @Test
    fun buildsStoredBookPayloadFromParsedBook() {
        val coordinator = BookImportCoordinator(
            planner = BookImportPlanner(rootDirectory = "/files/books"),
            clock = { 1_700_000_000_000L },
            pageConfigKey = "chars-1200",
            maxCharsPerPage = 1200,
        )
        val candidate = ImportCandidate(
            displayName = "Quiet.txt",
            sizeBytes = 12L,
            modifiedAtMillis = 1_600_000_000_000L,
        )
        val parsed = ParsedBook(
            metadata = ParsedBookMetadata(
                title = "Quiet",
                author = "Reader",
                format = BookFormat.TXT,
            ),
            chapters = listOf(
                ParsedChapter(index = 0, title = "Start", content = "hello reader"),
            ),
        )

        val payload = coordinator.buildPayload(
            candidate = candidate,
            parsed = parsed,
            storedPath = "/files/books/quiet.txt",
            existingFingerprints = emptySet(),
        )

        assertFalse(payload.isDuplicate)
        assertEquals("Quiet", payload.book.title)
        assertEquals("Reader", payload.book.author)
        assertEquals("TXT", payload.book.format)
        assertEquals(1, payload.chapters.size)
        assertEquals("Start", payload.chapters.first().title)
        assertEquals(1, payload.pageIndexes.size)
        assertEquals(0, payload.progress.pageIndex)
        assertEquals("COMPLETED", payload.importTask.status)
    }

    @Test
    fun marksPayloadDuplicateWhenFingerprintAlreadyExists() {
        val coordinator = BookImportCoordinator(
            planner = BookImportPlanner(rootDirectory = "/files/books"),
            clock = { 1_700_000_000_000L },
            pageConfigKey = "chars-4",
            maxCharsPerPage = 4,
        )
        val candidate = ImportCandidate(
            displayName = "Quiet.txt",
            sizeBytes = 12L,
            modifiedAtMillis = 1_600_000_000_000L,
        )
        val parsed = ParsedBook(
            metadata = ParsedBookMetadata(title = "Quiet", format = BookFormat.TXT),
            chapters = listOf(ParsedChapter(index = 0, title = "Start", content = "hello reader")),
        )

        val payload = coordinator.buildPayload(
            candidate = candidate,
            parsed = parsed,
            storedPath = "/files/books/quiet.txt",
            existingFingerprints = setOf("quiet-12-1600000000000"),
        )

        assertTrue(payload.isDuplicate)
    }
}
