package com.example.read.reader

data class ReaderChapter(
    val id: String,
    val title: String,
    val content: String,
)

data class PaginationConfig(
    val maxCharsPerPage: Int,
) {
    init {
        require(maxCharsPerPage > 0) { "maxCharsPerPage must be positive." }
    }
}

data class ReaderPage(
    val chapterId: String,
    val chapterTitle: String,
    val pageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

class PaginationEngine {
    fun paginate(
        chapter: ReaderChapter,
        config: PaginationConfig,
    ): List<ReaderPage> {
        val content = chapter.content.trim()
        if (content.isBlank()) {
            return listOf(
                ReaderPage(
                    chapterId = chapter.id,
                    chapterTitle = chapter.title,
                    pageIndex = 0,
                    startOffset = 0,
                    endOffset = 0,
                    text = "",
                ),
            )
        }

        val pages = mutableListOf<ReaderPage>()
        var start = 0
        while (start < content.length) {
            val proposedEnd = (start + config.maxCharsPerPage).coerceAtMost(content.length)
            val end = findPageEnd(content, start, proposedEnd)
            val text = content.substring(start, end).trim()
            pages += ReaderPage(
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                pageIndex = pages.size,
                startOffset = start,
                endOffset = end,
                text = text,
            )
            start = end
            while (start < content.length && content[start].isWhitespace()) {
                start += 1
            }
        }
        return pages
    }

    private fun findPageEnd(
        content: String,
        start: Int,
        proposedEnd: Int,
    ): Int {
        if (proposedEnd == content.length) return proposedEnd
        if (!content[proposedEnd - 1].isLetterOrDigit() || content[proposedEnd].isWhitespace()) {
            return proposedEnd
        }

        val boundary = content.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), startIndex = proposedEnd - 1)
        return if (boundary > start) boundary else proposedEnd
    }
}
