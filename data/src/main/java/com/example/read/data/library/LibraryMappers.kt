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
