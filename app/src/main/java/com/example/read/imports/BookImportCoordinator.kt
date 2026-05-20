package com.example.read.imports

import com.example.read.data.database.BookEntity
import com.example.read.data.database.ChapterEntity
import com.example.read.data.database.ImportTaskEntity
import com.example.read.data.database.PageIndexEntity
import com.example.read.data.database.ReadingProgressEntity
import com.example.read.data.imports.BookFingerprint
import com.example.read.data.imports.BookImportPlanner
import com.example.read.data.imports.ImportCandidate
import com.example.read.parser.ParsedBook
import com.example.read.parser.ParsedChapter
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
            existingFingerprints = existingFingerprints.map(::BookFingerprint).toSet(),
        )
        val now = clock()
        val bookId = UUID.nameUUIDFromBytes(plan.fingerprint.value.toByteArray()).toString()
        val sourceChapters = parsed.chapters
            .sortedBy { it.index }
            .ifEmpty {
                listOf(
                    ParsedChapter(
                        index = 0,
                        title = parsed.metadata.title,
                        content = "",
                    ),
                )
            }
        val chapters = sourceChapters.map { chapter ->
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
