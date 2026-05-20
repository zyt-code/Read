package com.example.read.ui

import com.example.read.data.model.Book

data class ReadUiState(
    val books: List<Book> = emptyList(),
    val isImporting: Boolean = false,
    val importMessage: String? = null,
    val selectedReader: SelectedReaderUiState? = null,
)

data class SelectedReaderUiState(
    val bookId: String,
    val title: String,
    val chapterId: String,
    val chapterTitle: String,
    val chapterContent: String,
    val pageLabel: String = "Page 1 of 1",
    val progress: Float = 0f,
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
