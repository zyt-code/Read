package com.example.read.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库版本迁移脚本。
 *
 * MIGRATION_1_2: 将 EPUB 存储从单文件复制模式改为解包到目录结构模式。
 * 使用重建表策略（而非 ALTER TABLE DROP COLUMN），因为 DROP COLUMN 需要 SQLite 3.35.0+
 * 而 Android minSdk 26 的设备可能不支持。
 *
 * 迁移步骤：
 * 1. 创建新表 books_new，使用新的列定义（bookDirPath 替代 filePath）
 * 2. 从旧表复制数据（bookDirPath 设为默认空字符串，因为旧数据需要重新导入）
 * 3. 删除旧表
 * 4. 将新表重命名为 books
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建新表，使用 bookDirPath 替代 filePath
        db.execSQL("""
            CREATE TABLE books_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                coverPath TEXT,
                bookDirPath TEXT NOT NULL DEFAULT '',
                totalChapters INTEGER NOT NULL,
                lastReadChapter INTEGER NOT NULL DEFAULT 0,
                lastReadAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        // 从旧表复制数据，bookDirPath 设为空（旧书籍需要重新导入）
        db.execSQL("""
            INSERT INTO books_new (id, title, author, coverPath, bookDirPath, totalChapters, lastReadChapter, lastReadAt)
            SELECT id, title, author, coverPath, '', totalChapters, lastReadChapter, lastReadAt FROM books
        """.trimIndent())
        // 删除旧表并重命名新表
        db.execSQL("DROP TABLE books")
        db.execSQL("ALTER TABLE books_new RENAME TO books")
    }
}

/**
 * MIGRATION_2_3: 新增 bookmarks 表（v6 feature: 书签 / Schema v3）。
 *
 * 设计要点：
 * - 不动 BookEntity，外键关系新表
 * - 外键 ON DELETE CASCADE：删除书籍时关联书签自动清理，避免幽灵书签
 * - 复合索引 (bookId, createdAt)：支持"按书 + 按时间倒序" 的高频查询
 *   （ReaderViewModel 的 `getBookmarks(bookId)` 流以此查询为底）
 *
 * SQL 与 BookmarkEntity 字段定义保持一致；Room 在 schema 校验时会比较实际 schema
 * 与 entity 推断的 schema，任何不一致都会抛 IllegalStateException 提示迁移有问题。
 *
 * SQLite 默认不开启外键约束（PRAGMA foreign_keys = OFF），Room 在打开数据库时
 * 会自动 PRAGMA foreign_keys = ON，所以 CASCADE 行为可被正确触发。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 创建 bookmarks 表：与 BookmarkEntity 注解推断的 schema 保持一致
        // - bookId 外键 -> books.id，CASCADE DELETE 让书签随书籍一起被清理
        // - 字段顺序、类型、NOT NULL 约束都需与 entity 完全一致，否则 Room 启动校验失败
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                chapterIndex INTEGER NOT NULL,
                pageInChapter INTEGER NOT NULL,
                note TEXT,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        // 复合索引 (bookId, createdAt)，名称需与 BookmarkEntity 注解中声明的完全一致
        // 否则 Room 启动校验会报"expected index xxx but found none / different"。
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_bookmarks_book_created
                ON bookmarks (bookId, createdAt)
        """.trimIndent())
    }
}
