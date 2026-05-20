package com.example.read

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.read.ui.ReadApp

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val openBookDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        viewModel.importBook(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ReadApp(
                state = state,
                onOpenFilePicker = {
                    openBookDocument.launch(SUPPORTED_BOOK_MIME_TYPES)
                },
                onOpenBook = viewModel::openBook,
                onCloseReader = viewModel::closeReader,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: return
        viewModel.importBook(uri)
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
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
