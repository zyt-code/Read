# Import Bookshelf Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the next MVP slice: SAF import copies EPUB/TXT files into app-private storage, parses metadata and chapters, stores library data/indexes in Room, and renders the real bookshelf from persisted state.

**Architecture:** Keep parsing in `parser`, persistence and file storage in `data`, page indexing in `reader-core`, and orchestration in `app`. The `ui` module should become mostly state-driven: it receives bookshelf state and callbacks instead of owning sample books.

**Tech Stack:** Kotlin, Android API 26+, Jetpack Compose Material 3, Room + KSP, SAF `ContentResolver`, coroutines with `Dispatchers.IO`, epub4j for EPUB, existing `PaginationEngine` for page index generation.

---

## Scope

This plan implements:
- Import from SAF URI for `EPUB` and `TXT`.
- Copy raw file into `filesDir/books`.
- Store book metadata, chapters, reading progress, page indexes, import tasks, and searchable chapter text in Room.
- Show imported books on Home bookshelf.
- Open a persisted book into the reader using its first chapter and saved progress.

This plan does not implement:
- Directory scanning.
- Cloud sync.
- Real cover image extraction/rendering.
- PDF/MOBI parsing; those formats are detected and stored as unsupported import failures for now.

## File Structure

Create:
- `data/src/main/java/com/example/read/data/database/ReadDatabase.kt` - Room database holder.
- `data/src/main/java/com/example/read/data/database/LibraryEntities.kt` - Room entities and FTS entity.
- `data/src/main/java/com/example/read/data/database/LibraryDao.kt` - DAO API.
- `data/src/main/java/com/example/read/data/library/LibraryRepository.kt` - repository over DAO and transactions.
- `data/src/main/java/com/example/read/data/library/LibraryMappers.kt` - entity/domain mapping and parser-format mapping.
- `data/src/main/java/com/example/read/data/storage/BookFileStorage.kt` - copy imported streams to app-private storage.
- `app/src/main/java/com/example/read/imports/BookParserRouter.kt` - choose EPUB/TXT parser by extension/MIME/display name.
- `app/src/main/java/com/example/read/imports/BookImportCoordinator.kt` - orchestrate candidate metadata, duplicate detection, copy, parse, page indexing, Room writes.
- `app/src/main/java/com/example/read/imports/ImportUriMetadataReader.kt` - query SAF display name, size, modified time, MIME.
- `app/src/main/java/com/example/read/MainViewModel.kt` - app state holder for library, imports, and reader selection.
- `ui/src/main/java/com/example/read/ui/ReadUiState.kt` - UI state and callbacks for bookshelf/reader.

Modify:
- `gradle/libs.versions.toml` - add `androidx-activity-ktx` dependency alias for `MainActivity.viewModels()`.
- `data/build.gradle.kts` - expose Room schema location.
- `app/build.gradle.kts` - add `androidx.activity:activity-ktx`.
- `app/src/main/java/com/example/read/MainActivity.kt` - wire `MainViewModel`, SAF import, and UI callbacks.
- `ui/src/main/java/com/example/read/ui/ReadApp.kt` - render real bookshelf state and open persisted chapters.

Test:
- `data/src/test/java/com/example/read/data/library/LibraryMappersTest.kt`
- `data/src/test/java/com/example/read/data/storage/BookFileStorageTest.kt`
- `app/src/test/java/com/example/read/imports/BookParserRouterTest.kt`
- `app/src/test/java/com/example/read/imports/BookImportCoordinatorTest.kt`
- `ui/src/test/java/com/example/read/ui/BookshelfUiContractTest.kt`

---

### Task 1: Normalize Library Storage Contracts

**Files:**
- Create: `data/src/main/java/com/example/read/data/database/LibraryEntities.kt`
- Create: `data/src/main/java/com/example/read/data/library/LibraryMappers.kt`
- Test: `data/src/test/java/com/example/read/data/library/LibraryMappersTest.kt`

- [ ] **Step 1: Write failing mapper tests**

```kotlin
package com.example.read.data.library

import com.example.read.data.database.BookEntity
import com.example.read.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMappersTest {

    @Test
    fun bookEntityMapsToDomainBook() {
        val entity = BookEntity(
            id = "book-1",
            fingerprint = "quiet-2048-1700000000000",
            title = "Quiet Reading",
            author = "Read MVP",
            coverPath = null,
            storagePath = "/files/books/quiet.epub",
            format = "EPUB",
            sizeBytes = 2048,
            importedAtMillis = 1700000000000,
            lastOpenedAtMillis = null,
            isSupported = true,
        )

        val book = entity.toDomain()

        assertEquals("book-1", book.id)
        assertEquals("Quiet Reading", book.title)
        assertEquals("Read MVP", book.author)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals("/files/books/quiet.epub", book.storagePath)
    }

    @Test
    fun unsupportedFormatFallsBackToUnknown() {
        assertEquals(BookFormat.UNKNOWN, "AZW3".toBookFormat())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew --no-daemon --no-configuration-cache :data:testDebugUnitTest --tests com.example.read.data.library.LibraryMappersTest
```

Expected: compile fails because `BookEntity`, `toDomain`, and `toBookFormat` do not exist.

- [ ] **Step 3: Add Room entities**

Create `data/src/main/java/com/example/read/data/database/LibraryEntities.kt`:

```kotlin
package com.example.read.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["title"]),
        Index(value = ["last_opened_at_millis"]),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val title: String,
    val author: String?,
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "storage_path") val storagePath: String,
    val format: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "imported_at_millis") val importedAtMillis: Long,
    @ColumnInfo(name = "last_opened_at_millis") val lastOpenedAtMillis: Long?,
    @ColumnInfo(name = "is_supported") val isSupported: Boolean,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id", "chapter_index"], unique = true),
        Index(value = ["book_id"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    val title: String,
    val content: String,
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "offset") val offset: Int,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

@Entity(
    tableName = "page_indexes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["book_id", "chapter_id", "config_key", "page_index"], unique = true),
        Index(value = ["book_id", "config_key"]),
    ],
)
data class PageIndexEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    @ColumnInfo(name = "config_key") val configKey: String,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
    @ColumnInfo(name = "start_offset") val startOffset: Int,
    @ColumnInfo(name = "end_offset") val endOffset: Int,
)

@Entity(tableName = "import_tasks", indices = [Index(value = ["fingerprint"])])
data class ImportTaskEntity(
    @PrimaryKey val id: String,
    val fingerprint: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val status: String,
    val message: String?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

@Fts4(contentEntity = ChapterEntity::class)
@Entity(tableName = "chapter_fts")
data class ChapterFtsEntity(
    val title: String,
    val content: String,
)
```

- [ ] **Step 4: Add mappers**

Create `data/src/main/java/com/example/read/data/library/LibraryMappers.kt`:

```kotlin
package com.example.read.data.library

import com.example.read.data.database.BookEntity
import com.example.read.data.database.ChapterEntity
import com.example.read.data.database.ReadingProgressEntity
import com.example.read.data.model.Book
import com.example.read.data.model.BookFormat
import com.example.read.data.model.Chapter
import com.example.read.data.model.ReadingProgress
import java.util.Locale

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    coverPath = coverPath,
    storagePath = storagePath,
    format = format.toBookFormat(),
)

fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    title = title,
    index = chapterIndex,
    content = content,
)

fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    bookId = bookId,
    chapterIndex = chapterIndex,
    pageIndex = pageIndex,
    offset = offset,
)

fun String.toBookFormat(): BookFormat = when (uppercase(Locale.US)) {
    "EPUB" -> BookFormat.EPUB
    "TXT" -> BookFormat.TXT
    "PDF" -> BookFormat.PDF
    "MOBI" -> BookFormat.MOBI
    else -> BookFormat.UNKNOWN
}

fun BookFormat.toStorageValue(): String = name
```

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew --no-daemon --no-configuration-cache :data:testDebugUnitTest --tests com.example.read.data.library.LibraryMappersTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add data/src/main/java/com/example/read/data/database/LibraryEntities.kt \
  data/src/main/java/com/example/read/data/library/LibraryMappers.kt \
  data/src/test/java/com/example/read/data/library/LibraryMappersTest.kt
git commit -m "feat: add library storage entities"
```

---

### Task 2: Add Room DAO, Database, and Repository

**Files:**
- Create: `data/src/main/java/com/example/read/data/database/LibraryDao.kt`
- Create: `data/src/main/java/com/example/read/data/database/ReadDatabase.kt`
- Create: `data/src/main/java/com/example/read/data/library/LibraryRepository.kt`
- Modify: `data/build.gradle.kts`

- [ ] **Step 1: Add DAO contract**

Create `data/src/main/java/com/example/read/data/database/LibraryDao.kt`:

```kotlin
package com.example.read.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY last_opened_at_millis DESC, imported_at_millis DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getBookByFingerprint(fingerprint: String): BookEntity?

    @Query("SELECT fingerprint FROM books")
    suspend fun getFingerprints(): List<String>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY chapter_index ASC")
    suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId AND chapter_index = :chapterIndex LIMIT 1")
    suspend fun getChapter(bookId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId LIMIT 1")
    suspend fun getProgress(bookId: String): ReadingProgressEntity?

    @Query("SELECT * FROM page_indexes WHERE book_id = :bookId AND config_key = :configKey ORDER BY chapter_id ASC, page_index ASC")
    suspend fun getPageIndexes(bookId: String, configKey: String): List<PageIndexEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageIndexes(indexes: List<PageIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportTask(task: ImportTaskEntity)

    @Query("DELETE FROM page_indexes WHERE book_id = :bookId AND config_key = :configKey")
    suspend fun deletePageIndexes(bookId: String, configKey: String)

    @Query("UPDATE books SET last_opened_at_millis = :openedAtMillis WHERE id = :bookId")
    suspend fun markBookOpened(bookId: String, openedAtMillis: Long)

    @Transaction
    suspend fun insertImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        pageIndexes: List<PageIndexEntity>,
        progress: ReadingProgressEntity,
        importTask: ImportTaskEntity,
    ) {
        insertBook(book)
        insertChapters(chapters)
        insertPageIndexes(pageIndexes)
        upsertProgress(progress)
        upsertImportTask(importTask)
    }
}
```

- [ ] **Step 2: Add Room database**

Create `data/src/main/java/com/example/read/data/database/ReadDatabase.kt`:

```kotlin
package com.example.read.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        PageIndexEntity::class,
        ImportTaskEntity::class,
        ChapterFtsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReadDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
```

- [ ] **Step 3: Add repository**

Create `data/src/main/java/com/example/read/data/library/LibraryRepository.kt`:

```kotlin
package com.example.read.data.library

import com.example.read.data.database.BookEntity
import com.example.read.data.database.ChapterEntity
import com.example.read.data.database.ImportTaskEntity
import com.example.read.data.database.LibraryDao
import com.example.read.data.database.PageIndexEntity
import com.example.read.data.database.ReadingProgressEntity
import com.example.read.data.model.Book
import com.example.read.data.model.Chapter
import com.example.read.data.model.ReadingProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(
    private val dao: LibraryDao,
) {
    fun observeBooks(): Flow<List<Book>> = dao.observeBooks().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun existingFingerprints(): Set<String> = dao.getFingerprints().toSet()

    suspend fun getBook(bookId: String): Book? = dao.getBook(bookId)?.toDomain()

    suspend fun getChapters(bookId: String): List<Chapter> = dao.getChapters(bookId).map { it.toDomain() }

    suspend fun getChapter(bookId: String, chapterIndex: Int): Chapter? {
        return dao.getChapter(bookId, chapterIndex)?.toDomain()
    }

    suspend fun getProgress(bookId: String): ReadingProgress? = dao.getProgress(bookId)?.toDomain()

    suspend fun markBookOpened(bookId: String, openedAtMillis: Long) {
        dao.markBookOpened(bookId, openedAtMillis)
    }

    suspend fun insertImportedBook(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        pageIndexes: List<PageIndexEntity>,
        progress: ReadingProgressEntity,
        importTask: ImportTaskEntity,
    ) {
        dao.insertImportedBook(
            book = book,
            chapters = chapters,
            pageIndexes = pageIndexes,
            progress = progress,
            importTask = importTask,
        )
    }
}
```

- [ ] **Step 4: Configure Room schema location**

Modify `data/build.gradle.kts` inside `android {}`:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

- [ ] **Step 5: Verify Room code generation**

Run:

```bash
./gradlew --no-daemon --no-configuration-cache :data:compileDebugKotlin
```

Expected: pass and generate Room implementation.

- [ ] **Step 6: Commit**

```bash
git add data/build.gradle.kts data/src/main/java/com/example/read/data/database data/src/main/java/com/example/read/data/library/LibraryRepository.kt
git commit -m "feat: add Room library repository"
```

---

### Task 3: Add Private File Storage Copy

**Files:**
- Create: `data/src/main/java/com/example/read/data/storage/BookFileStorage.kt`
- Test: `data/src/test/java/com/example/read/data/storage/BookFileStorageTest.kt`

- [ ] **Step 1: Write failing storage tests**

```kotlin
package com.example.read.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class BookFileStorageTest {

    @Test
    fun copiesImportIntoBooksDirectory() {
        val root = Files.createTempDirectory("read-storage").toFile()
        val storage = BookFileStorage(rootDirectory = root)

        val result = storage.copy(
            input = ByteArrayInputStream("hello reader".toByteArray()),
            targetFileName = "quiet.txt",
        )

        assertEquals("quiet.txt", result.file.name)
        assertEquals("hello reader", result.file.readText())
        assertTrue(result.file.absolutePath.contains("/books/"))
        assertEquals(12L, result.bytesCopied)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon --no-configuration-cache :data:testDebugUnitTest --tests com.example.read.data.storage.BookFileStorageTest
```

Expected: compile fails because `BookFileStorage` does not exist.

- [ ] **Step 3: Implement storage**

Create `data/src/main/java/com/example/read/data/storage/BookFileStorage.kt`:

```kotlin
package com.example.read.data.storage

import java.io.File
import java.io.InputStream

data class StoredBookFile(
    val file: File,
    val bytesCopied: Long,
)

class BookFileStorage(
    private val rootDirectory: File,
) {
    private val booksDirectory: File
        get() = File(rootDirectory, BOOKS_DIRECTORY_NAME)

    fun copy(
        input: InputStream,
        targetFileName: String,
    ): StoredBookFile {
        booksDirectory.mkdirs()
        val target = File(booksDirectory, targetFileName).canonicalFile
        require(target.parentFile == booksDirectory.canonicalFile) {
            "Target file must stay inside books directory."
        }

        var bytes = 0L
        target.outputStream().use { output ->
            input.use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytes += read
                }
            }
        }
        return StoredBookFile(file = target, bytesCopied = bytes)
    }

    companion object {
        const val BOOKS_DIRECTORY_NAME = "books"
    }
}
```

- [ ] **Step 4: Run test**

```bash
./gradlew --no-daemon --no-configuration-cache :data:testDebugUnitTest --tests com.example.read.data.storage.BookFileStorageTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add data/src/main/java/com/example/read/data/storage/BookFileStorage.kt data/src/test/java/com/example/read/data/storage/BookFileStorageTest.kt
git commit -m "feat: add private book file storage"
```

---

### Task 4: Route Parsers by Imported File

**Files:**
- Create: `app/src/main/java/com/example/read/imports/BookParserRouter.kt`
- Test: `app/src/test/java/com/example/read/imports/BookParserRouterTest.kt`

- [ ] **Step 1: Write failing router tests**

```kotlin
package com.example.read.imports

import com.example.read.parser.BookFormat
import org.junit.Assert.assertEquals
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
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest --tests com.example.read.imports.BookParserRouterTest
```

Expected: compile fails because `BookParserRouter` does not exist.

- [ ] **Step 3: Implement parser router**

Create `app/src/main/java/com/example/read/imports/BookParserRouter.kt`:

```kotlin
package com.example.read.imports

import com.example.read.parser.BookFormat
import com.example.read.parser.BookParser
import com.example.read.parser.EpubBookParser
import com.example.read.parser.TxtBookParser
import java.util.Locale

class BookParserRouter(
    private val txtParser: BookParser = TxtBookParser(),
    private val epubParser: BookParser = EpubBookParser(),
) {
    fun resolveFormat(displayName: String, mimeType: String?): BookFormat {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.US)
        return when {
            mimeType == "application/epub+zip" || extension == "epub" -> BookFormat.EPUB
            mimeType == "text/plain" || extension == "txt" -> BookFormat.TXT
            mimeType == "application/pdf" || extension == "pdf" -> BookFormat.PDF
            mimeType == "application/x-mobipocket-ebook" || extension == "mobi" -> BookFormat.MOBI
            else -> BookFormat.UNKNOWN
        }
    }

    fun parserFor(format: BookFormat): BookParser? = when (format) {
        BookFormat.EPUB -> epubParser
        BookFormat.TXT -> txtParser
        BookFormat.PDF,
        BookFormat.MOBI,
        BookFormat.UNKNOWN -> null
    }
}
```

- [ ] **Step 4: Run test**

```bash
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest --tests com.example.read.imports.BookParserRouterTest
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/read/imports/BookParserRouter.kt app/src/test/java/com/example/read/imports/BookParserRouterTest.kt
git commit -m "feat: route imported book parsers"
```

---

### Task 5: Implement Import Coordinator

**Files:**
- Create: `app/src/main/java/com/example/read/imports/BookImportCoordinator.kt`
- Create: `app/src/main/java/com/example/read/imports/ImportUriMetadataReader.kt`
- Test: `app/src/test/java/com/example/read/imports/BookImportCoordinatorTest.kt`

- [ ] **Step 1: Write failing coordinator test**

```kotlin
package com.example.read.imports

import com.example.read.data.imports.BookFingerprint
import com.example.read.data.imports.BookImportPlan
import com.example.read.data.imports.BookImportPlanner
import com.example.read.data.imports.ImportCandidate
import com.example.read.parser.BookFormat
import com.example.read.parser.ParsedBook
import com.example.read.parser.ParsedBookMetadata
import com.example.read.parser.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream

class BookImportCoordinatorTest {

    @Test
    fun buildsStoredBookPayloadFromParsedBook() {
        val coordinator = BookImportCoordinator(
            planner = BookImportPlanner(rootDirectory = "/files/books"),
            clock = { 1700000000000L },
            pageConfigKey = "chars-1200",
            maxCharsPerPage = 1200,
        )
        val candidate = ImportCandidate(
            displayName = "Quiet.txt",
            sizeBytes = 12L,
            modifiedAtMillis = 1600000000000L,
        )
        val parsed = ParsedBook(
            metadata = ParsedBookMetadata(title = "Quiet", author = "Reader", format = BookFormat.TXT),
            chapters = listOf(ParsedChapter(index = 0, title = "Start", content = "hello reader")),
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
        assertEquals(1, payload.chapters.size)
        assertEquals(1, payload.pageIndexes.size)
        assertEquals(0, payload.progress.pageIndex)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest --tests com.example.read.imports.BookImportCoordinatorTest
```

Expected: compile fails because `BookImportCoordinator` does not exist.

- [ ] **Step 3: Implement coordinator payload model**

Create `app/src/main/java/com/example/read/imports/BookImportCoordinator.kt`:

```kotlin
package com.example.read.imports

import com.example.read.data.database.BookEntity
import com.example.read.data.database.ChapterEntity
import com.example.read.data.database.ImportTaskEntity
import com.example.read.data.database.PageIndexEntity
import com.example.read.data.database.ReadingProgressEntity
import com.example.read.data.imports.BookImportPlanner
import com.example.read.data.imports.ImportCandidate
import com.example.read.parser.ParsedBook
import com.example.read.reader.PaginationConfig
import com.example.read.reader.PaginationEngine
import com.example.read.reader.ReaderChapter
import java.util.UUID

data class ImportPayload(
    val isDuplicate: Boolean,
    val book: BookEntity,
    val chapters: List<ChapterEntity>,
    val pageIndexes: List<PageIndexEntity>,
    val progress: ReadingProgressEntity,
    val importTask: ImportTaskEntity,
)

class BookImportCoordinator(
    private val planner: BookImportPlanner,
    private val clock: () -> Long,
    private val pageConfigKey: String,
    private val maxCharsPerPage: Int,
    private val paginationEngine: PaginationEngine = PaginationEngine(),
) {
    fun buildPayload(
        candidate: ImportCandidate,
        parsed: ParsedBook,
        storedPath: String,
        existingFingerprints: Set<String>,
    ): ImportPayload {
        val plan = planner.plan(
            candidate = candidate,
            existingFingerprints = existingFingerprints.map { com.example.read.data.imports.BookFingerprint(it) }.toSet(),
        )
        val now = clock()
        val bookId = UUID.nameUUIDFromBytes(plan.fingerprint.value.toByteArray()).toString()
        val chapters = parsed.chapters.map { chapter ->
            ChapterEntity(
                id = "$bookId-chapter-${chapter.index}",
                bookId = bookId,
                chapterIndex = chapter.index,
                title = chapter.title,
                content = chapter.content,
            )
        }
        val pageIndexes = chapters.flatMap { chapter ->
            paginationEngine.paginate(
                chapter = ReaderChapter(
                    id = chapter.id,
                    title = chapter.title,
                    content = chapter.content,
                ),
                config = PaginationConfig(maxCharsPerPage = maxCharsPerPage),
            ).map { page ->
                PageIndexEntity(
                    id = "${chapter.id}-$pageConfigKey-${page.pageIndex}",
                    bookId = bookId,
                    chapterId = chapter.id,
                    configKey = pageConfigKey,
                    pageIndex = page.pageIndex,
                    startOffset = page.startOffset,
                    endOffset = page.endOffset,
                )
            }
        }

        return ImportPayload(
            isDuplicate = plan.isDuplicate,
            book = BookEntity(
                id = bookId,
                fingerprint = plan.fingerprint.value,
                title = parsed.metadata.title,
                author = parsed.metadata.author,
                coverPath = null,
                storagePath = storedPath,
                format = parsed.metadata.format.name,
                sizeBytes = candidate.sizeBytes,
                importedAtMillis = now,
                lastOpenedAtMillis = null,
                isSupported = true,
            ),
            chapters = chapters,
            pageIndexes = pageIndexes,
            progress = ReadingProgressEntity(
                bookId = bookId,
                chapterIndex = 0,
                pageIndex = 0,
                offset = 0,
                updatedAtMillis = now,
            ),
            importTask = ImportTaskEntity(
                id = "$bookId-import",
                fingerprint = plan.fingerprint.value,
                displayName = candidate.displayName,
                status = "COMPLETED",
                message = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }
}
```

- [ ] **Step 4: Add SAF metadata reader**

Create `app/src/main/java/com/example/read/imports/ImportUriMetadataReader.kt`:

```kotlin
package com.example.read.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.example.read.data.imports.ImportCandidate

data class ImportUriMetadata(
    val candidate: ImportCandidate,
    val mimeType: String?,
)

class ImportUriMetadataReader(
    private val contentResolver: ContentResolver,
    private val clock: () -> Long,
) {
    fun read(uri: Uri): ImportUriMetadata {
        val mimeType = contentResolver.getType(uri)
        val cursorValues = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else uri.lastPathSegment
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            displayName to size
        }
        return ImportUriMetadata(
            candidate = ImportCandidate(
                displayName = cursorValues?.first ?: "imported-book",
                sizeBytes = cursorValues?.second ?: 0L,
                modifiedAtMillis = clock(),
            ),
            mimeType = mimeType,
        )
    }
}
```

- [ ] **Step 5: Run coordinator tests**

```bash
./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest --tests com.example.read.imports.BookImportCoordinatorTest
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/read/imports app/src/test/java/com/example/read/imports/BookImportCoordinatorTest.kt
git commit -m "feat: build import payloads"
```

---

### Task 6: Wire MainViewModel and Real Import Flow

**Files:**
- Create: `app/src/main/java/com/example/read/MainViewModel.kt`
- Modify: `app/src/main/java/com/example/read/MainActivity.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add Activity KTX dependency**

Modify `gradle/libs.versions.toml`:

```toml
androidx-activity-ktx = { module = "androidx.activity:activity-ktx", version.ref = "activityCompose" }
```

Modify `app/build.gradle.kts`:

```kotlin
implementation(libs.androidx.activity.ktx)
```

- [ ] **Step 2: Create ViewModel state**

Create `app/src/main/java/com/example/read/MainViewModel.kt`:

```kotlin
package com.example.read

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.read.data.database.ReadDatabase
import com.example.read.data.imports.BookImportPlanner
import com.example.read.data.library.LibraryRepository
import com.example.read.data.model.Book
import com.example.read.data.storage.BookFileStorage
import com.example.read.imports.BookImportCoordinator
import com.example.read.imports.BookParserRouter
import com.example.read.imports.ImportUriMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val books: List<Book> = emptyList(),
    val isImporting: Boolean = false,
    val importMessage: String? = null,
)

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val database = Room.databaseBuilder(
        application,
        ReadDatabase::class.java,
        "read.db",
    ).build()
    private val repository = LibraryRepository(database.libraryDao())
    private val importState = MutableStateFlow(false)
    private val messageState = MutableStateFlow<String?>(null)
    private val metadataReader = ImportUriMetadataReader(application.contentResolver) { System.currentTimeMillis() }
    private val router = BookParserRouter()
    private val fileStorage = BookFileStorage(application.filesDir)
    private val coordinator = BookImportCoordinator(
        planner = BookImportPlanner(rootDirectory = application.filesDir.absolutePath + "/books"),
        clock = { System.currentTimeMillis() },
        pageConfigKey = "chars-1200",
        maxCharsPerPage = 1200,
    )

    val uiState: StateFlow<MainUiState> = combine(
        repository.observeBooks(),
        importState,
        messageState,
    ) { books, importing, message ->
        MainUiState(books = books, isImporting = importing, importMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            importState.value = true
            messageState.value = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val metadata = metadataReader.read(uri)
                    val format = router.resolveFormat(metadata.candidate.displayName, metadata.mimeType)
                    val parser = router.parserFor(format) ?: error("${format.name} is not supported yet.")
                    val existing = repository.existingFingerprints()
                    val inputForCopy = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("Cannot open selected file.")
                    val targetFileName = metadata.candidate.displayName.ifBlank { "imported-book" }
                    val stored = fileStorage.copy(inputForCopy, targetFileName)
                    val inputForParse = stored.file.inputStream()
                    val parsed = parser.parse(inputForParse, metadata.candidate.displayName)
                    val payload = coordinator.buildPayload(
                        candidate = metadata.candidate,
                        parsed = parsed,
                        storedPath = stored.file.absolutePath,
                        existingFingerprints = existing,
                    )
                    if (!payload.isDuplicate) {
                        repository.insertImportedBook(
                            book = payload.book,
                            chapters = payload.chapters,
                            pageIndexes = payload.pageIndexes,
                            progress = payload.progress,
                            importTask = payload.importTask,
                        )
                    }
                }
            }.onFailure { error ->
                messageState.value = error.message ?: "Import failed."
            }
            importState.value = false
        }
    }
}
```

- [ ] **Step 3: Wire MainActivity**

Modify `app/src/main/java/com/example/read/MainActivity.kt` so `setContent` collects `uiState` and calls `viewModel.importBook(uri)` after permission is persisted. Preserve the existing MIME list.

```kotlin
private val viewModel: MainViewModel by viewModels()
```

Inside `setContent`:

```kotlin
val state by viewModel.uiState.collectAsStateWithLifecycle()
ReadApp(
    books = state.books,
    isImporting = state.isImporting,
    importMessage = state.importMessage,
    onOpenFilePicker = { openBookDocument.launch(SUPPORTED_BOOK_MIME_TYPES) },
)
```

Inside the file picker callback:

```kotlin
viewModel.importBook(uri)
```

- [ ] **Step 4: Run compile**

```bash
./gradlew --no-daemon --no-configuration-cache :app:compileDebugKotlin
```

Expected: compile fails until UI accepts the new state parameters. Continue to Task 7 before committing.

---

### Task 7: Render Real Bookshelf State in Compose

**Files:**
- Create: `ui/src/main/java/com/example/read/ui/ReadUiState.kt`
- Modify: `ui/src/main/java/com/example/read/ui/ReadApp.kt`
- Test: `ui/src/test/java/com/example/read/ui/BookshelfUiContractTest.kt`

- [ ] **Step 1: Write failing UI contract test**

```kotlin
package com.example.read.ui

import com.example.read.data.model.Book
import com.example.read.data.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfUiContractTest {

    @Test
    fun bookshelfTitleUsesImportedBookTitleAndAuthor() {
        val book = Book(
            id = "book-1",
            title = "Quiet Reading",
            author = "Read MVP",
            storagePath = "/files/books/quiet.epub",
            format = BookFormat.EPUB,
        )

        val item = book.toBookshelfItem()

        assertEquals("Quiet Reading", item.title)
        assertEquals("Read MVP · EPUB", item.subtitle)
    }
}
```

- [ ] **Step 2: Add UI state model**

Create `ui/src/main/java/com/example/read/ui/ReadUiState.kt`:

```kotlin
package com.example.read.ui

import com.example.read.data.model.Book

data class ReadUiState(
    val books: List<Book> = emptyList(),
    val isImporting: Boolean = false,
    val importMessage: String? = null,
)

data class BookshelfItem(
    val id: String,
    val title: String,
    val subtitle: String,
)

fun Book.toBookshelfItem(): BookshelfItem = BookshelfItem(
    id = id,
    title = title,
    subtitle = listOfNotNull(author, format.name).joinToString(" · "),
)
```

- [ ] **Step 3: Modify ReadApp API**

Modify `ReadApp` signature:

```kotlin
fun ReadApp(
    books: List<Book> = emptyList(),
    isImporting: Boolean = false,
    importMessage: String? = null,
    onOpenFilePicker: () -> Unit = {},
    onOpenBook: (Book) -> Unit = {},
)
```

Modify Home:
- Keep right-bottom `FloatingActionButton` with `Icons.Rounded.Add`.
- Add a `LazyColumn` only when books are non-empty.
- Render each book as an 8dp rounded Material 3 card/list row.
- Do not bring back a large centered import button.

- [ ] **Step 4: Run UI tests**

```bash
./gradlew --no-daemon --no-configuration-cache :ui:testDebugUnitTest --tests com.example.read.ui.BookshelfUiContractTest --tests com.example.read.ui.HomeNavigationUiContractTest
```

Expected: pass.

- [ ] **Step 5: Run app compile**

```bash
./gradlew --no-daemon --no-configuration-cache :app:compileDebugKotlin
```

Expected: pass after `MainActivity` and `ReadApp` signatures agree.

- [ ] **Step 6: Commit Task 6 and 7 together**

```bash
git add app/src/main/java/com/example/read/MainActivity.kt app/src/main/java/com/example/read/MainViewModel.kt \
  ui/src/main/java/com/example/read/ui/ReadApp.kt ui/src/main/java/com/example/read/ui/ReadUiState.kt \
  ui/src/test/java/com/example/read/ui/BookshelfUiContractTest.kt app/build.gradle.kts
git commit -m "feat: show imported books on shelf"
```

---

### Task 8: Open Persisted Books into Reader

**Files:**
- Modify: `app/src/main/java/com/example/read/MainViewModel.kt`
- Modify: `app/src/main/java/com/example/read/MainActivity.kt`
- Modify: `ui/src/main/java/com/example/read/ui/ReadApp.kt`

- [ ] **Step 1: Add selected reader state**

Add to `MainUiState`:

```kotlin
val selectedBookId: String? = null,
val selectedChapterTitle: String? = null,
val selectedChapterContent: String? = null,
val pageLabel: String = "Page 1 of 1",
val progress: Float = 0f,
```

Add ViewModel function:

```kotlin
fun openBook(book: Book) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            repository.markBookOpened(book.id, System.currentTimeMillis())
            val progress = repository.getProgress(book.id)
            val chapter = repository.getChapter(book.id, progress?.chapterIndex ?: 0)
            selectedReaderState.value = SelectedReaderState(
                bookId = book.id,
                title = book.title,
                chapterTitle = chapter?.title ?: book.title,
                chapterContent = chapter?.content.orEmpty(),
                pageLabel = "Page ${(progress?.pageIndex ?: 0) + 1}",
                progress = 0f,
            )
        }
    }
}
```

- [ ] **Step 2: Pass selected reader state into UI**

Change `ReadApp` so it opens `ReaderShell` from state instead of internal sample state. Keep `ReaderDisplaySettings` in UI because it is display preference state.

- [ ] **Step 3: Verify compile and smoke path**

```bash
./gradlew --no-daemon --no-configuration-cache :app:assembleDebug
```

Expected: pass. Manual smoke: install debug APK, import a TXT file, see it on shelf, tap book, reader shows first chapter content.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/read/MainViewModel.kt app/src/main/java/com/example/read/MainActivity.kt ui/src/main/java/com/example/read/ui/ReadApp.kt
git commit -m "feat: open persisted books in reader"
```

---

### Task 9: Final Verification

**Files:**
- No code files unless verification reveals a failure.

- [ ] **Step 1: Run full unit and build verification**

```bash
./gradlew --no-daemon --no-configuration-cache :data:testDebugUnitTest :parser:testDebugUnitTest :reader-core:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Expected: all tasks pass.

- [ ] **Step 2: Manual smoke checklist**

Use a debug install and verify:
- Home opens on `Home` tab.
- Right-bottom `+` opens SAF picker.
- Importing a `.txt` file copies it into app private storage and adds a shelf item.
- Tapping shelf item opens reader content from stored chapter.
- Returning from reader shows shelf.
- `Settings` tab still toggles night mode.
- Importing the same file twice does not create a duplicate shelf item.
- Importing PDF/MOBI shows an import message and does not crash.

- [ ] **Step 3: Confirm worktree state**

```bash
git status --short --branch
```

Expected after all task commits: `## main...origin/main [ahead N]` with no staged or unstaged files.

- [ ] **Step 4: Push branch**

```bash
git push
```

Expected: remote `origin/main` receives the new commits.

---

## Self-Review

Spec coverage:
- Import from file picker: Tasks 4-6.
- File copy into app-private storage: Task 3.
- Metadata and chapters: Tasks 4-6.
- Room persistence for books, chapters, progress, page indexes, import tasks, and FTS: Tasks 1-2.
- Bookshelf rendering from stored books: Task 7.
- Reader opens persisted chapter content: Task 8.
- Verification: Task 9.

Known implementation choice:
- FTS table is created with Room, but search UI is not added in this slice. This is intentional because the user asked for storage and indexes first.
- Page indexes use `maxCharsPerPage = 1200` for MVP stability. A future reader-layout slice should regenerate indexes per actual viewport/font config.
