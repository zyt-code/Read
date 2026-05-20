package com.example.read.data.storage

import java.io.File
import java.io.InputStream

data class StoredBookFile(
    val file: File,
    val bytesCopied: Long,
)

class BookFileStorage(
    private val rootDirectory: File,
) {
    private val booksDirectory: File
        get() = File(rootDirectory, BOOKS_DIRECTORY_NAME)

    fun copy(
        input: InputStream,
        targetFileName: String,
    ): StoredBookFile {
        booksDirectory.mkdirs()
        val canonicalBooksDirectory = booksDirectory.canonicalFile
        val target = File(canonicalBooksDirectory, targetFileName).canonicalFile
        require(target.parentFile == canonicalBooksDirectory) {
            "Target file must stay inside books directory."
        }

        var bytesCopied = 0L
        target.outputStream().use { output ->
            input.use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    bytesCopied += read
                }
            }
        }

        return StoredBookFile(file = target, bytesCopied = bytesCopied)
    }

    companion object {
        const val BOOKS_DIRECTORY_NAME = "books"
    }
}
