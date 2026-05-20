package com.example.read.data.model

data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val coverPath: String? = null,
    val storagePath: String,
    val format: BookFormat,
)

data class Chapter(
    val id: String,
    val bookId: String,
    val title: String,
    val index: Int,
    val content: String,
)

data class ReadingProgress(
    val bookId: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val offset: Int,
)

enum class BookFormat {
    EPUB,
    TXT,
    PDF,
    MOBI,
    UNKNOWN,
}
