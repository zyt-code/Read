package com.example.read.data.imports

import java.util.Locale

data class ImportCandidate(
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

data class BookFingerprint(val value: String)

data class BookImportPlan(
    val fingerprint: BookFingerprint,
    val storagePath: String,
    val isDuplicate: Boolean,
)

class BookImportPlanner(
    private val rootDirectory: String,
) {
    fun plan(
        candidate: ImportCandidate,
        existingFingerprints: Set<BookFingerprint>,
    ): BookImportPlan {
        val extension = candidate.displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.US)
        val baseName = candidate.displayName.substringBeforeLast('.', candidate.displayName)
            .slugify()
            .ifBlank { "book" }
        val fingerprint = BookFingerprint(
            value = "$baseName-${candidate.sizeBytes}-${candidate.modifiedAtMillis}",
        )
        val fileName = if (extension.isBlank()) {
            fingerprint.value
        } else {
            "${fingerprint.value}.$extension"
        }

        return BookImportPlan(
            fingerprint = fingerprint,
            storagePath = "${rootDirectory.trimEnd('/')}/$fileName",
            isDuplicate = fingerprint in existingFingerprints,
        )
    }

    private fun String.slugify(): String = trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}
