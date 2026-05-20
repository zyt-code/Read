package com.example.read.parser

import java.io.InputStream

class TxtBookParser : BookParser {
    override fun parse(input: InputStream, fileName: String): ParsedBook {
        val text = input.bufferedReader().use { it.readText() }.trim()
        val lines = text.lines()
        val title = lines.firstOrNull { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.', fileName)

        val bodyAfterTitle = lines.dropWhile { it != title }.drop(1).joinToString("\n").trim()
        val chapters = splitChapters(bodyAfterTitle)

        return ParsedBook(
            metadata = ParsedBookMetadata(
                title = title,
                format = BookFormat.TXT,
            ),
            chapters = chapters.ifEmpty {
                listOf(ParsedChapter(index = 0, title = title, content = bodyAfterTitle.ifBlank { text }))
            },
        )
    }

    private fun splitChapters(body: String): List<ParsedChapter> {
        if (body.isBlank()) return emptyList()

        val chapterTitlePattern = Regex(
            pattern = """^(chapter\s+\S+|第.{1,9}[章节回卷部篇]).*""",
            option = RegexOption.IGNORE_CASE,
        )
        val chapters = mutableListOf<ParsedChapter>()
        var currentTitle: String? = null
        val currentContent = StringBuilder()

        body.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && chapterTitlePattern.matches(trimmed)) {
                appendChapter(chapters, currentTitle, currentContent)
                currentTitle = trimmed
            } else if (currentTitle != null) {
                currentContent.appendLine(line)
            }
        }
        appendChapter(chapters, currentTitle, currentContent)

        return chapters
    }

    private fun appendChapter(
        chapters: MutableList<ParsedChapter>,
        title: String?,
        content: StringBuilder,
    ) {
        if (title == null) return
        chapters += ParsedChapter(
            index = chapters.size,
            title = title,
            content = content.toString().trim(),
        )
        content.clear()
    }
}
