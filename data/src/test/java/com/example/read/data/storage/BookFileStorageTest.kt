package com.example.read.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class BookFileStorageTest {

    @Test
    fun copiesImportIntoBooksDirectory() {
        val root = Files.createTempDirectory("read-storage").toFile()
        val storage = BookFileStorage(rootDirectory = root)

        val result = storage.copy(
            input = ByteArrayInputStream("hello reader".toByteArray()),
            targetFileName = "quiet.txt",
        )

        assertEquals("quiet.txt", result.file.name)
        assertEquals("hello reader", result.file.readText())
        assertTrue(result.file.absolutePath.contains("/books/"))
        assertEquals(12L, result.bytesCopied)
    }
}
