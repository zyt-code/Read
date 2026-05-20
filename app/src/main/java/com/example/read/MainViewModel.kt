package com.example.read

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.read.data.database.ReadDatabase
import com.example.read.data.imports.BookFingerprint
import com.example.read.data.imports.BookImportPlanner
import com.example.read.data.library.LibraryRepository
import com.example.read.data.model.Book
import com.example.read.data.storage.BookFileStorage
import com.example.read.imports.BookImportCoordinator
import com.example.read.imports.BookParserRouter
import com.example.read.imports.ImportUriMetadataReader
import com.example.read.ui.ReadUiState
import com.example.read.ui.SelectedReaderUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = Room.databaseBuilder(
        application,
        ReadDatabase::class.java,
        DATABASE_NAME,
    ).build()
    private val repository = LibraryRepository(database.libraryDao())
    private val importState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<String?>(null)
    private val selectedReaderState = MutableStateFlow<SelectedReaderUiState?>(null)
    private val metadataReader = ImportUriMetadataReader(application.contentResolver)
    private val router = BookParserRouter()
    private val fileStorage = BookFileStorage(application.filesDir)
    private val importPlanner = BookImportPlanner(
        rootDirectory = "${application.filesDir.absolutePath}/books",
    )
    private val coordinator = BookImportCoordinator(
        planner = importPlanner,
        clock = { System.currentTimeMillis() },
        pageConfigKey = PAGE_CONFIG_KEY,
        maxCharsPerPage = MAX_CHARS_PER_PAGE,
    )

    val uiState: StateFlow<ReadUiState> = combine(
        repository.observeBooks(),
        importState,
        messageState,
        selectedReaderState,
    ) { books, importing, message, selectedReader ->
        ReadUiState(
            books = books,
            isImporting = importing,
            importMessage = message,
            selectedReader = selectedReader,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReadUiState(),
    )

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            importState.value = true
            messageState.value = null
            runCatching {
                withContext(Dispatchers.IO) {
                    importBookOnIo(uri)
                }
            }.onSuccess { imported ->
                messageState.value = if (imported) {
                    "Imported book"
                } else {
                    "Book already on shelf"
                }
            }.onFailure { error ->
                messageState.value = error.message ?: "Import failed"
            }
            importState.value = false
        }
    }

    fun openBook(book: Book) {
        viewModelScope.launch {
            val selectedReader = withContext(Dispatchers.IO) {
                repository.markBookOpened(book.id, System.currentTimeMillis())
                val progress = repository.getProgress(book.id)
                val chapters = repository.getChapters(book.id)
                val chapter = repository.getChapter(book.id, progress?.chapterIndex ?: 0)
                    ?: chapters.firstOrNull()
                book.toSelectedReaderUiState(
                    chapter = chapter,
                    progress = progress,
                    chapterCount = chapters.size,
                )
            }
            selectedReaderState.value = selectedReader
        }
    }

    fun closeReader() {
        selectedReaderState.value = null
    }

    private suspend fun importBookOnIo(uri: Uri): Boolean {
        val metadata = metadataReader.read(uri)
        val format = router.resolveFormat(
            displayName = metadata.candidate.displayName,
            mimeType = metadata.mimeType,
        )
        val parser = router.parserFor(format)
            ?: error("${format.name} is not supported yet")
        val resolver = getApplication<Application>().contentResolver
        val existingFingerprints = repository.existingFingerprints()
        val plan = importPlanner.plan(
            candidate = metadata.candidate,
            existingFingerprints = existingFingerprints.map(::BookFingerprint).toSet(),
        )
        if (plan.isDuplicate) return false

        val inputForCopy = resolver.openInputStream(uri)
            ?: error("Cannot open selected file")
        val stored = fileStorage.copy(
            input = inputForCopy,
            targetFileName = File(plan.storagePath).name,
        )
        val parsed = stored.file.inputStream().use { input ->
            parser.parse(input, metadata.candidate.displayName)
        }
        val payload = coordinator.buildPayload(
            candidate = metadata.candidate,
            parsed = parsed,
            storedPath = stored.file.absolutePath,
            existingFingerprints = existingFingerprints,
        )

        repository.insertImportedBook(
            book = payload.book,
            chapters = payload.chapters,
            pageIndexes = payload.pageIndexes,
            progress = payload.progress,
            importTask = payload.importTask,
        )
        return true
    }

    private companion object {
        const val DATABASE_NAME = "read.db"
        const val PAGE_CONFIG_KEY = "chars-1200"
        const val MAX_CHARS_PER_PAGE = 1200
    }
}
