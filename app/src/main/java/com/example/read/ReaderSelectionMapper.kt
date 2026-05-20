package com.example.read

import com.example.read.data.model.Book
import com.example.read.data.model.Chapter
import com.example.read.data.model.ReadingProgress
import com.example.read.ui.SelectedReaderUiState

fun Book.toSelectedReaderUiState(
    chapter: Chapter?,
    progress: ReadingProgress?,
    chapterCount: Int,
): SelectedReaderUiState {
    val pageIndex = progress?.pageIndex ?: 0
    val progressValue = if (chapterCount > 0) {
        ((progress?.chapterIndex ?: 0) + 1).toFloat() / chapterCount.toFloat()
    } else {
        0f
    }
    return SelectedReaderUiState(
        bookId = id,
        title = title,
        chapterId = chapter?.id ?: id,
        chapterTitle = chapter?.title ?: title,
        chapterContent = chapter?.content.orEmpty(),
        pageLabel = "Page ${pageIndex + 1}",
        progress = progressValue.coerceIn(0f, 1f),
    )
}
