package com.example.read.data.repository

import android.content.Context
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.local.entity.BookEntity
import com.example.read.domain.model.Book
import com.example.read.util.BookMetadata
import com.example.read.util.SpineItem
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * BookRepositoryImpl 文件系统副作用相关的纯 JVM 测试。
 *
 * 测试范围：
 * - deleteBook(): 解包目录与封面文件被删除
 * - deleteBook(): 文件不存在时不抛异常并仍删除数据库记录
 * - deleteBook(): bookDirPath 为空时不进行文件操作
 * - getChapterContent(): 书籍不存在 / 章节索引越界 / metadata.json 缺失 / HTML 文件缺失
 * - getChapterHtmlFile(): 越界与正常路径
 * - readMetadata(): metadata.json 缺失时抛 IllegalStateException
 *
 * 与 androidTest 中的集成测试互补：
 * - androidTest 使用真实 Room 数据库 + 真实 Context.filesDir
 * - 这里使用 mock DAO，真实临时目录，模拟文件系统副作用
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryImplFsTest {

    @MockK
    private lateinit var bookDao: BookDao

    private lateinit var repository: BookRepositoryImpl
    private lateinit var mockContext: Context
    private lateinit var tempRoot: File

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        // 使用真实临时目录模拟 filesDir。Files.createTempDirectory 是 createTempDir 的推荐替代。
        tempRoot = java.nio.file.Files.createTempDirectory("repo_fs_test_").toFile()
        mockContext = mockk<Context>(relaxed = true)
        every { mockContext.filesDir } returns tempRoot
        // v6：构造签名新增 BookmarkDao；本测试不涉及书签，relaxed mock 足够
        val mockBookmarkDao = mockk<BookmarkDao>(relaxed = true)
        repository = BookRepositoryImpl(bookDao, mockBookmarkDao, mockContext)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    /**
     * 构建一个解包目录，写入 metadata.json 和章节文件，返回目录绝对路径。
     */
    private fun setupUnpackedBook(
        bookId: Long,
        chapters: List<Pair<String, String>>, // href -> html
        opfDir: String = "OEBPS",
    ): File {
        val bookDir = File(tempRoot, "books/$bookId")
        bookDir.mkdirs()
        // 写入 metadata.json
        val spine = chapters.mapIndexed { idx, (href, _) ->
            SpineItem("ch$idx", href, "Chapter $idx", "application/xhtml+xml")
        }
        val metadata = BookMetadata("T", "A", opfDir, spine)
        File(bookDir, "metadata.json").writeText(json.encodeToString(metadata), Charsets.UTF_8)
        // 写入章节文件
        val basePath = if (opfDir.isEmpty()) bookDir else File(bookDir, opfDir)
        basePath.mkdirs()
        for ((href, html) in chapters) {
            File(basePath, href).apply {
                parentFile?.mkdirs()
                writeText(html, Charsets.UTF_8)
            }
        }
        return bookDir
    }

    // ==================== deleteBook 文件系统副作用 ====================

    /**
     * Given: 一本书存在解包目录与封面文件
     * When: 调用 deleteBook
     * Then: 解包目录被递归删除，封面文件被删除，数据库记录被删除
     */
    @Test
    fun `should delete book directory and cover file when deleting book`() = runTest {
        val bookId = 100L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"))
        val coverFile = File(tempRoot, "covers/$bookId.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }

        val book = Book(
            id = bookId, title = "T", author = "A",
            coverPath = coverFile.absolutePath,
            bookDirPath = bookDir.absolutePath,
            totalChapters = 1,
        )
        repository.deleteBook(book)

        assertFalse("书籍目录应被删除", bookDir.exists())
        assertFalse("封面文件应被删除", coverFile.exists())
        coVerify { bookDao.deleteBook(any()) }
    }

    /**
     * Given: bookDirPath 指向不存在的目录
     * When: 调用 deleteBook
     * Then: 不抛异常，DAO.deleteBook 仍被调用
     */
    @Test
    fun `should not throw when book directory does not exist on delete`() = runTest {
        val book = Book(
            id = 1, title = "T", author = "A",
            coverPath = null, bookDirPath = "/no/such/path", totalChapters = 1,
        )
        repository.deleteBook(book)
        coVerify { bookDao.deleteBook(any()) }
    }

    /**
     * Given: bookDirPath 为空字符串（孤立书籍记录）
     * When: 调用 deleteBook
     * Then: 不操作文件系统，仅删除数据库记录
     */
    @Test
    fun `should skip filesystem operations when bookDirPath is empty`() = runTest {
        val book = Book(
            id = 2, title = "T", author = "A",
            coverPath = null, bookDirPath = "", totalChapters = 0,
        )
        repository.deleteBook(book)
        coVerify { bookDao.deleteBook(any()) }
    }

    /**
     * Given: coverPath 为 null
     * When: 调用 deleteBook
     * Then: 不抛异常
     */
    @Test
    fun `should handle null coverPath gracefully on delete`() = runTest {
        val bookId = 3L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"))
        val book = Book(
            id = bookId, title = "T", author = "A",
            coverPath = null, bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        repository.deleteBook(book)
        assertFalse(bookDir.exists())
    }

    /**
     * Given: coverPath 指向不存在的文件
     * When: 调用 deleteBook
     * Then: 不抛异常
     */
    @Test
    fun `should handle missing cover file gracefully on delete`() = runTest {
        val bookId = 4L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"))
        val book = Book(
            id = bookId, title = "T", author = "A",
            coverPath = "/no/such/cover.jpg",
            bookDirPath = bookDir.absolutePath,
            totalChapters = 1,
        )
        repository.deleteBook(book)
        // 文件不存在但操作未抛异常
    }

    // ==================== getChapterContent 异常路径 ====================

    /**
     * Given: bookDao.getBookById 返回 null
     * When: 调用 getChapterContent
     * Then: 抛 IllegalArgumentException("Book not found")
     */
    @Test
    fun `should throw when getChapterContent on unknown book`() = runTest {
        coEvery { bookDao.getBookById(any()) } returns null
        val ex = try {
            repository.getChapterContent(999L, 0)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("Book not found"))
    }

    /**
     * Given: 章节索引越界（负值或超过 spine 大小）
     * When: 调用 getChapterContent
     * Then: 抛 IndexOutOfBoundsException
     */
    @Test
    fun `should throw IndexOutOfBoundsException when chapter index out of range`() = runTest {
        val bookId = 5L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html><body>x</body></html>"))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        for (badIdx in listOf(-1, 1, 100)) {
            val threw = try {
                repository.getChapterContent(bookId, badIdx)
                false
            } catch (e: IndexOutOfBoundsException) {
                true
            }
            assertTrue("索引 $badIdx 应抛 IndexOutOfBoundsException", threw)
        }
    }

    /**
     * Given: bookDirPath 存在但 metadata.json 缺失
     * When: 调用 getChapterContent
     * Then: 抛 IllegalStateException("metadata.json not found")
     */
    @Test
    fun `should throw when metadata json is missing`() = runTest {
        val bookId = 6L
        val bookDir = File(tempRoot, "books/$bookId").also { it.mkdirs() }
        // 故意不写 metadata.json
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        val ex = try {
            repository.getChapterContent(bookId, 0)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(ex)
        assertTrue(
            "异常消息应提及 metadata.json，实际: ${ex!!.message}",
            ex.message!!.contains("metadata.json"),
        )
    }

    /**
     * Given: metadata.json 存在但 HTML 文件缺失
     * When: 调用 getChapterContent
     * Then: 抛 IllegalStateException("HTML file not found")
     */
    @Test
    fun `should throw when chapter html file is missing`() = runTest {
        val bookId = 7L
        val bookDir = File(tempRoot, "books/$bookId").also { it.mkdirs() }
        // 写入 metadata.json，但不创建对应的 HTML 文件
        val metadata = BookMetadata(
            "T", "A", "OEBPS",
            listOf(SpineItem("ch1", "missing.xhtml", "Ch1", "application/xhtml+xml")),
        )
        File(bookDir, "metadata.json").writeText(json.encodeToString(metadata))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )

        val ex = try {
            repository.getChapterContent(bookId, 0)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(ex)
        assertTrue(
            "异常消息应提及 HTML 文件，实际: ${ex!!.message}",
            ex.message!!.contains("HTML"),
        )
    }

    /**
     * Given: 正常的解包书籍，metadata.json 与 HTML 都存在
     * When: 调用 getChapterContent
     * Then: 返回 Chapter，content 字段为 Jsoup 解析的纯文本，htmlPath 指向 HTML 文件
     */
    @Test
    fun `should return chapter content as plain text via Jsoup`() = runTest {
        val bookId = 8L
        val html = "<html><body><p>第一段</p><p>第二段</p></body></html>"
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to html))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )

        val chapter = repository.getChapterContent(bookId, 0)
        assertEquals(0, chapter.index)
        assertEquals("Chapter 0", chapter.title) // SpineItem.title = "Chapter $idx"
        assertTrue("纯文本应去除 HTML 标签", chapter.content.contains("第一段"))
        assertTrue(chapter.content.contains("第二段"))
        assertFalse("纯文本不应含 <p> 等标签", chapter.content.contains("<p>"))
        assertTrue(chapter.htmlPath.endsWith("c1.xhtml"))
    }

    /**
     * Given: spine[idx].title 为空字符串
     * When: 调用 getChapterContent
     * Then: title 自动回退为 "Chapter ${idx+1}"
     */
    @Test
    fun `should fallback chapter title to ChapterN when spine title is empty`() = runTest {
        val bookId = 9L
        val bookDir = File(tempRoot, "books/$bookId").also { it.mkdirs() }
        File(bookDir, "OEBPS").mkdirs()
        File(bookDir, "OEBPS/c1.xhtml").writeText("<html/>")

        val metadata = BookMetadata(
            "T", "A", "OEBPS",
            listOf(SpineItem("ch1", "c1.xhtml", "", "application/xhtml+xml")),
        )
        File(bookDir, "metadata.json").writeText(json.encodeToString(metadata))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )

        val chapter = repository.getChapterContent(bookId, 0)
        assertEquals("Chapter 1", chapter.title)
    }

    // ==================== getChapterHtmlFile ====================

    /**
     * Given: 章节索引越界
     * When: 调用 getChapterHtmlFile
     * Then: 抛 IndexOutOfBoundsException
     */
    @Test
    fun `should throw on getChapterHtmlFile when chapter index out of range`() = runTest {
        val bookId = 10L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        val threw = try {
            repository.getChapterHtmlFile(bookId, 5)
            false
        } catch (e: IndexOutOfBoundsException) {
            true
        }
        assertTrue(threw)
    }

    /**
     * Given: 正常元数据
     * When: 调用 getChapterHtmlFile(0)
     * Then: 返回正确的 File 路径
     */
    @Test
    fun `should resolve html file path for getChapterHtmlFile`() = runTest {
        val bookId = 11L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        val file = repository.getChapterHtmlFile(bookId, 0)
        assertTrue(file.exists())
        assertTrue(file.absolutePath.endsWith("c1.xhtml"))
    }

    /**
     * Given: opfDir 为空字符串（OPF 位于根目录）
     * When: 调用 getChapterHtmlFile
     * Then: 文件路径为 bookDirPath/href（不再拼接 opfDir）
     */
    @Test
    fun `should resolve html path without opfDir when opfDir is empty`() = runTest {
        val bookId = 12L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"), opfDir = "")
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        val file = repository.getChapterHtmlFile(bookId, 0)
        assertTrue("文件应在书籍根目录下，路径: ${file.absolutePath}", file.exists())
        assertEquals(bookDir.absolutePath, file.parentFile?.absolutePath)
    }

    // ==================== getBookMetadata ====================

    /**
     * Given: 书籍存在，metadata.json 完整
     * When: 调用 getBookMetadata
     * Then: 返回反序列化的 BookMetadata
     */
    @Test
    fun `should return metadata object for getBookMetadata`() = runTest {
        val bookId = 13L
        val bookDir = setupUnpackedBook(bookId, listOf("c1.xhtml" to "<html/>"))
        coEvery { bookDao.getBookById(bookId) } returns BookEntity(
            id = bookId, title = "T", author = "A", coverPath = null,
            bookDirPath = bookDir.absolutePath, totalChapters = 1,
        )
        val meta = repository.getBookMetadata(bookId)
        assertEquals("OEBPS", meta.opfDir)
        assertEquals(1, meta.spine.size)
    }

    /**
     * Given: 书籍不存在
     * When: 调用 getBookMetadata
     * Then: 抛 IllegalArgumentException("Book not found")
     */
    @Test
    fun `should throw when getBookMetadata on unknown book`() = runTest {
        coEvery { bookDao.getBookById(any()) } returns null
        val threw = try {
            repository.getBookMetadata(99L)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
    }

    // ==================== B5: deleteBook 删除失败兜底 ====================

    /**
     * B5 回归测试：deleteRecursively() 失败时仍清理数据库记录。
     *
     * 妥协方案：Windows / 跨平台环境下，让 File.deleteRecursively() 真实失败
     * （如锁定文件、设为只读）不可靠。本测试用更稳妥的策略：
     *   将 bookDirPath 指向一个非目录的文件。File.deleteRecursively() 对
     *   非目录文件仍会尝试 delete()，可能成功也可能失败；无论哪种结果，
     *   B5 修复都保证不抛异常并继续调用 bookDao.deleteBook。
     *
     * 真正的"删除失败"场景在 Unix 上可通过把 parent 目录设为只读触发，
     * 跨平台环境下行为不一致，此处不强求复现失败，仅断言"任何情况都调用 DAO"。
     *
     * Given: bookDirPath 指向一个文件（不是目录）
     * When: 调用 deleteBook
     * Then: 不抛异常；bookDao.deleteBook 被调用（数据库一定清理）
     */
    @Test
    fun `should still delete DB record even if file system delete fails`() = runTest {
        // 创建一个文件而非目录作为 bookDirPath
        val badPath = File(tempRoot, "not_a_directory.txt").apply {
            writeText("placeholder")
        }
        val book = Book(
            id = 50L, title = "B5", author = "A",
            coverPath = null,
            bookDirPath = badPath.absolutePath,
            totalChapters = 1,
        )
        // deleteRecursively 对文件会执行 delete()，可能成功
        // 关键：无论文件系统操作结果如何，DAO.deleteBook 必须被调用
        repository.deleteBook(book)
        coVerify { bookDao.deleteBook(any()) }
    }

    /**
     * Given: bookDirPath 指向已存在的目录，但目录内文件包含一个被设为只读的子文件
     *        （在 Windows 上 setReadOnly 不阻止删除，在 Unix 上同样不阻止 owner 删除，
     *         但对常规文件系统行为下仍是"尝试 + 容忍失败"路径的烟雾测试）
     * When: 调用 deleteBook
     * Then: 不抛异常，DAO.deleteBook 被调用
     */
    @Test
    fun `should swallow exception and still cleanup DB on stubborn directory`() = runTest {
        val bookDir = File(tempRoot, "books/60").also { it.mkdirs() }
        val nested = File(bookDir, "nested").also { it.mkdirs() }
        val ro = File(nested, "readonly.bin").apply { writeText("data") }
        @Suppress("UNUSED_VARIABLE")
        val unusedSetReadOnly = ro.setReadOnly()

        val book = Book(
            id = 60L, title = "B5b", author = "A",
            coverPath = null,
            bookDirPath = bookDir.absolutePath,
            totalChapters = 1,
        )
        // 不论平台是否真的"删除失败"，调用都不应抛
        repository.deleteBook(book)
        coVerify { bookDao.deleteBook(any()) }
    }
}
