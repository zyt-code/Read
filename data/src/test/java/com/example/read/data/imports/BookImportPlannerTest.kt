package com.example.read.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImportPlannerTest {

    @Test
    fun createsStableStoragePathFromFingerprintAndFileName() {
        val planner = BookImportPlanner(rootDirectory = "/library/books")
        val candidate = ImportCandidate(
            displayName = "Quiet Reading.epub",
            sizeBytes = 2048L,
            modifiedAtMillis = 1700000000000L,
        )

        val plan = planner.plan(candidate, existingFingerprints = emptySet())

        assertEquals("quiet-reading-2048-1700000000000", plan.fingerprint.value)
        assertEquals("/library/books/quiet-reading-2048-1700000000000.epub", plan.storagePath)
        assertFalse(plan.isDuplicate)
    }

    @Test
    fun marksImportAsDuplicateWhenFingerprintAlreadyExists() {
        val planner = BookImportPlanner(rootDirectory = "/library/books")
        val candidate = ImportCandidate(
            displayName = "Quiet Reading.epub",
            sizeBytes = 2048L,
            modifiedAtMillis = 1700000000000L,
        )

        val plan = planner.plan(
            candidate = candidate,
            existingFingerprints = setOf(BookFingerprint("quiet-reading-2048-1700000000000")),
        )

        assertTrue(plan.isDuplicate)
    }
}
