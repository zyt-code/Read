package com.example.read.parser

import nl.siegmann.epublib.epub.EpubReader
import java.io.InputStream

class EpubBookParser(
    private val metadataReader: EpubMetadataReader = Epub4jMetadataReader(),
) : BookParser {
    override fun parse(input: InputStream, fileName: String): ParsedBook {
        return metadataReader.read(input, fileName)
    }
}

interface EpubMetadataReader {
    fun read(input: InputStream, fileName: String): ParsedBook
}

class Epub4jMetadataReader : EpubMetadataReader {
    override fun read(input: InputStream, fileName: String): ParsedBook {
        val book = EpubReader().readEpub(input)
        val metadata = book.metadata
        val title = metadata.titles.firstOrNull()
            ?: fileName.substringBeforeLast('.', fileName)
        val author = metadata.authors.firstOrNull()?.let { author ->
            listOf(author.firstname, author.lastname)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { null }
        }
        val chapters = book.spine.spineReferences.mapIndexedNotNull { index, reference ->
            val resource = reference.resource ?: return@mapIndexedNotNull null
            val content = resource.reader.use { it.readText() }
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            ParsedChapter(
                index = index,
                title = resource.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
                content = content,
            )
        }

        return ParsedBook(
            metadata = ParsedBookMetadata(
                title = title,
                author = author,
                format = BookFormat.EPUB,
            ),
            chapters = chapters,
        )
    }
}
