package com.example.read

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.read.ui.ReadApp

class MainActivity : ComponentActivity() {
    private var importedBookTitle by mutableStateOf<String?>(null)

    private val openBookDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        importedBookTitle = resolveDisplayName(uri) ?: "Imported book"
        // The MVP import pipeline will copy this URI into app-private storage next.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReadApp(
                importedBookTitle = importedBookTitle,
                onImportedBookConsumed = { importedBookTitle = null },
                onOpenFilePicker = {
                    openBookDocument.launch(SUPPORTED_BOOK_MIME_TYPES)
                },
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex < 0) return@use null
                cursor.getString(columnIndex)
            }
    }

    private companion object {
        val SUPPORTED_BOOK_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "text/plain",
            "application/pdf",
            "application/x-mobipocket-ebook",
            "application/octet-stream",
        )
    }
}
