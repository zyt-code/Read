package com.example.read.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.read.data.imports.ImportCandidate

data class ImportUriMetadata(
    val candidate: ImportCandidate,
    val mimeType: String?,
)

class ImportUriMetadataReader(
    private val contentResolver: ContentResolver,
) {
    fun read(uri: Uri): ImportUriMetadata {
        val mimeType = contentResolver.getType(uri)
        val cursorValues = contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                cursor.getString(nameIndex)
            } else {
                uri.lastPathSegment
            }
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                cursor.getLong(sizeIndex)
            } else {
                0L
            }
            val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                cursor.getLong(modifiedIndex)
            } else {
                null
            }
            CursorValues(
                displayName = displayName,
                sizeBytes = size,
                modifiedAtMillis = modified,
            )
        }

        return ImportUriMetadata(
            candidate = ImportCandidate(
                displayName = cursorValues?.displayName?.takeIf { it.isNotBlank() } ?: "imported-book",
                sizeBytes = cursorValues?.sizeBytes ?: 0L,
                modifiedAtMillis = cursorValues?.modifiedAtMillis ?: 0L,
            ),
            mimeType = mimeType,
        )
    }

    private data class CursorValues(
        val displayName: String?,
        val sizeBytes: Long,
        val modifiedAtMillis: Long?,
    )
}
