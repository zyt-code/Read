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
