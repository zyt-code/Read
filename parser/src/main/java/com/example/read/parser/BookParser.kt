package com.example.read.parser

import java.io.InputStream

interface BookParser {
    fun parse(input: InputStream, fileName: String): ParsedBook
}

data class ParsedBook(
    val metadata: ParsedBookMetadata,
    val chapters: List<ParsedChapter>,
)

data class ParsedBookMetadata(
    val title: String,
    val author: String? = null,
    val format: BookFormat,
)

data class ParsedChapter(
    val index: Int,
    val title: String,
    val content: String,
)

enum class BookFormat {
    EPUB,
    TXT,
    PDF,
    MOBI,
    UNKNOWN,
}
