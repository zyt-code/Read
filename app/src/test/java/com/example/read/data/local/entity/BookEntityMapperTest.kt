package com.example.read.data.local.entity

import com.example.read.domain.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BookEntity 与 Book 领域模型之间的映射测试。
 *
 * 测试范围：
 * - toDomain(): BookEntity → Book 的转换正确性
 * - toEntity(): Book → BookEntity 的转换正确性
 * - 往返转换: entity → domain → entity 数据完整性
 * - 边界情况: null 值、默认值处理
 *
 * 映射逻辑位于 BookEntity.kt 中的扩展函数，
 * 确保数据层（Room Entity）和领域层（Domain Model）之间的隔离。
 */
class BookEntityMapperTest {

    // ==================== toDomain() 测试 ====================

    /**
     * 测试 BookEntity 转换为 Book 领域模型时所有字段正确映射。
     * 验证每个属性都被正确传递，没有遗漏或错位。
     */
    @Test
    fun toDomain_mapsAllFieldsCorrectly() {
        // 准备：包含所有字段的 BookEntity
        val entity = BookEntity(
            id = 42,
            title = "Kotlin in Action",
            author = "Dmitry Jemerov",
            coverPath = "/data/covers/kotlin.jpg",
            bookDirPath = "/data/epubs/kotlin.epub",
            totalChapters = 15,
            lastReadChapter = 7,
            lastReadAt = 1700000000000L,
        )

        // 执行：转换为领域模型
        val book = entity.toDomain()

        // 验证：所有字段正确映射
        assertEquals(42L, book.id)
        assertEquals("Kotlin in Action", book.title)
        assertEquals("Dmitry Jemerov", book.author)
        assertEquals("/data/covers/kotlin.jpg", book.coverPath)
        assertEquals("/data/epubs/kotlin.epub", book.bookDirPath)
        assertEquals(15, book.totalChapters)
        assertEquals(7, book.lastReadChapter)
        assertEquals(1700000000000L, book.lastReadAt)
    }

    /**
     * 测试 coverPath 为 null 时的转换。
     * 当 EPUB 文件没有封面时，coverPath 为 null，应正确传递到领域模型。
     */
    @Test
    fun toDomain_withNullCoverPath_preservesNull() {
        // 准备：coverPath 为 null 的 BookEntity
        val entity = BookEntity(
            id = 1,
            title = "No Cover Book",
            author = "Anonymous",
            coverPath = null,
            bookDirPath = "/data/epubs/nocover.epub",
            totalChapters = 5,
        )

        // 执行：转换为领域模型
        val book = entity.toDomain()

        // 验证：coverPath 保持 null
        assertNull(book.coverPath)
        assertEquals("No Cover Book", book.title)
    }

    /**
     * 测试默认值（id=0, lastReadChapter=0, lastReadAt=0）的转换。
     * 新插入的书籍实体使用默认值，应正确映射。
     */
    @Test
    fun toDomain_withDefaultValues_mapsCorrectly() {
        // 准备：使用默认值的 BookEntity（模拟新插入的记录）
        val entity = BookEntity(
            id = 0,
            title = "New Book",
            author = "New Author",
            coverPath = null,
            bookDirPath = "/data/epubs/new.epub",
            totalChapters = 10,
            lastReadChapter = 0,
            lastReadAt = 0L,
        )

        // 执行：转换为领域模型
        val book = entity.toDomain()

        // 验证：默认值正确传递
        assertEquals(0L, book.id)
        assertEquals(0, book.lastReadChapter)
        assertEquals(0L, book.lastReadAt)
    }

    // ==================== toEntity() 测试 ====================

    /**
     * 测试 Book 领域模型转换为 BookEntity 时所有字段正确映射。
     * 验证从领域层到数据层的反向映射。
     */
    @Test
    fun toEntity_mapsAllFieldsCorrectly() {
        // 准备：包含所有字段的 Book 领域模型
        val book = Book(
            id = 99,
            title = "Effective Kotlin",
            author = "Marcin Moskala",
            coverPath = "/data/covers/effective.jpg",
            bookDirPath = "/data/epubs/effective.epub",
            totalChapters = 20,
            lastReadChapter = 3,
            lastReadAt = 1700000000000L,
        )

        // 执行：转换为 Room 实体
        val entity = book.toEntity()

        // 验证：所有字段正确映射
        assertEquals(99L, entity.id)
        assertEquals("Effective Kotlin", entity.title)
        assertEquals("Marcin Moskala", entity.author)
        assertEquals("/data/covers/effective.jpg", entity.coverPath)
        assertEquals("/data/epubs/effective.epub", entity.bookDirPath)
        assertEquals(20, entity.totalChapters)
        assertEquals(3, entity.lastReadChapter)
        assertEquals(1700000000000L, entity.lastReadAt)
    }

    /**
     * 测试 coverPath 为 null 的 Book 转换为 BookEntity。
     * 验证 null 值在反向映射中正确保留。
     */
    @Test
    fun toEntity_withNullCoverPath_preservesNull() {
        // 准备：coverPath 为 null 的 Book
        val book = Book(
            id = 1,
            title = "No Cover",
            author = "Author",
            coverPath = null,
            bookDirPath = "/data/epubs/nocover.epub",
            totalChapters = 5,
        )

        // 执行：转换为 Room 实体
        val entity = book.toEntity()

        // 验证：coverPath 保持 null
        assertNull(entity.coverPath)
    }

    /**
     * 测试新书（id=0）转换为实体。
     * 新书的 id 为 0，Room 会在 insert 时自动生成。
     */
    @Test
    fun toEntity_newBookWithZeroId_preservesZeroId() {
        // 准备：新书领域模型（id=0 表示尚未持久化）
        val book = Book(
            id = 0,
            title = "Brand New Book",
            author = "Author",
            coverPath = null,
            bookDirPath = "/data/epubs/new.epub",
            totalChapters = 8,
        )

        // 执行：转换为 Room 实体
        val entity = book.toEntity()

        // 验证：id 保持为 0
        assertEquals(0L, entity.id)
        assertEquals("Brand New Book", entity.title)
    }

    // ==================== 往返转换测试 ====================

    /**
     * 测试 entity → domain → entity 的往返转换数据完整性。
     * 验证两次转换后数据没有任何丢失或改变。
     */
    @Test
    fun roundTrip_entityToDomainToEntity_preservesAllData() {
        // 准备：原始 BookEntity
        val originalEntity = BookEntity(
            id = 123,
            title = "Round Trip Test",
            author = "Test Author",
            coverPath = "/covers/roundtrip.jpg",
            bookDirPath = "/epubs/roundtrip.epub",
            totalChapters = 30,
            lastReadChapter = 15,
            lastReadAt = 1700000000000L,
        )

        // 执行：entity → domain → entity 的往返转换
        val restoredEntity = originalEntity.toDomain().toEntity()

        // 验证：所有字段与原始值一致
        assertEquals(originalEntity.id, restoredEntity.id)
        assertEquals(originalEntity.title, restoredEntity.title)
        assertEquals(originalEntity.author, restoredEntity.author)
        assertEquals(originalEntity.coverPath, restoredEntity.coverPath)
        assertEquals(originalEntity.bookDirPath, restoredEntity.bookDirPath)
        assertEquals(originalEntity.totalChapters, restoredEntity.totalChapters)
        assertEquals(originalEntity.lastReadChapter, restoredEntity.lastReadChapter)
        assertEquals(originalEntity.lastReadAt, restoredEntity.lastReadAt)
    }

    /**
     * 测试 domain → entity → domain 的往返转换数据完整性。
     * 验证从领域层出发的往返转换同样保持数据完整。
     */
    @Test
    fun roundTrip_domainToEntityToDomain_preservesAllData() {
        // 准备：原始 Book 领域模型
        val originalBook = Book(
            id = 456,
            title = "Domain Round Trip",
            author = "Domain Author",
            coverPath = "/covers/domain.jpg",
            bookDirPath = "/epubs/domain.epub",
            totalChapters = 12,
            lastReadChapter = 6,
            lastReadAt = 1700000000000L,
        )

        // 执行：domain → entity → domain 的往返转换
        val restoredBook = originalBook.toEntity().toDomain()

        // 验证：所有字段与原始值一致
        assertEquals(originalBook.id, restoredBook.id)
        assertEquals(originalBook.title, restoredBook.title)
        assertEquals(originalBook.author, restoredBook.author)
        assertEquals(originalBook.coverPath, restoredBook.coverPath)
        assertEquals(originalBook.bookDirPath, restoredBook.bookDirPath)
        assertEquals(originalBook.totalChapters, restoredBook.totalChapters)
        assertEquals(originalBook.lastReadChapter, restoredBook.lastReadChapter)
        assertEquals(originalBook.lastReadAt, restoredBook.lastReadAt)
    }

    /**
     * 测试包含 null coverPath 的往返转换。
     * 验证 null 值在往返过程中不会被意外转换为空字符串。
     */
    @Test
    fun roundTrip_withNullCoverPath_preservesNull() {
        // 准备：coverPath 为 null 的 BookEntity
        val originalEntity = BookEntity(
            id = 789,
            title = "Null Cover Round Trip",
            author = "Author",
            coverPath = null,
            bookDirPath = "/epubs/nullcover.epub",
            totalChapters = 5,
            lastReadChapter = 0,
            lastReadAt = 0L,
        )

        // 执行：往返转换
        val restoredEntity = originalEntity.toDomain().toEntity()

        // 验证：null 值正确保留
        assertNull(restoredEntity.coverPath)
        assertEquals(originalEntity.title, restoredEntity.title)
    }

    // ==================== 边界值测试 ====================

    /**
     * 测试最大 Long 值的 id 和时间戳。
     * 验证极端数值在映射过程中不会溢出或被截断。
     */
    @Test
    fun toDomain_withMaxValues_mapsCorrectly() {
        // 准备：使用最大值的 BookEntity
        val entity = BookEntity(
            id = Long.MAX_VALUE,
            title = "Max Values",
            author = "Author",
            coverPath = "/path/to/cover.jpg",
            bookDirPath = "/path/to/file.epub",
            totalChapters = Int.MAX_VALUE,
            lastReadChapter = Int.MAX_VALUE - 1,
            lastReadAt = Long.MAX_VALUE,
        )

        // 执行：转换
        val book = entity.toDomain()

        // 验证：最大值正确保留
        assertEquals(Long.MAX_VALUE, book.id)
        assertEquals(Int.MAX_VALUE, book.totalChapters)
        assertEquals(Int.MAX_VALUE - 1, book.lastReadChapter)
        assertEquals(Long.MAX_VALUE, book.lastReadAt)
    }

    /**
     * 测试包含特殊字符的标题和作者。
     * 验证特殊字符（Unicode、标点）在映射中不被修改。
     */
    @Test
    fun toDomain_withSpecialCharacters_preservesCorrectly() {
        // 准备：包含特殊字符的 BookEntity
        val entity = BookEntity(
            id = 1,
            title = "《三体》— 刘慈欣",
            author = "刘慈欣 (Liu Cixin)",
            coverPath = "/covers/三体.jpg",
            bookDirPath = "/epubs/三体.epub",
            totalChapters = 36,
        )

        // 执行：转换为领域模型
        val book = entity.toDomain()

        // 验证：特殊字符正确保留
        assertEquals("《三体》— 刘慈欣", book.title)
        assertEquals("刘慈欣 (Liu Cixin)", book.author)
        assertEquals("/covers/三体.jpg", book.coverPath)
    }
}
