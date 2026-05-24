package com.example.read.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库迁移测试 (MIGRATION_1_2)。
 *
 * 测试范围：
 * - v1 -> v2 schema 迁移：将 filePath 列替换为 bookDirPath
 * - 旧数据保留：id/title/author/coverPath/totalChapters/lastReadChapter/lastReadAt 完整迁移
 * - 新列默认值：bookDirPath 在迁移后的旧记录上必须为空字符串
 * - 迁移后 Room 能成功打开数据库（schema 校验）
 *
 * 工具：
 * - MigrationTestHelper：来自 androidx.room.testing，需要 schema JSON 文件
 * - schemas 目录由 build.gradle.kts 中 ksp room.schemaLocation 指定（"$projectDir/schemas"）
 *
 * 注意（构建配置依赖）：
 * MigrationTestHelper 通过 instrumentation 的 assets 加载 schema JSON。如果运行时报 "Cannot find the schema file" 类错误，
 * 需在 app/build.gradle.kts 的 android { sourceSets { ... } } 中追加：
 *   androidTest { assets.srcDirs("schemas") }
 * 本次任务不修改生产构建脚本，详见 TDD_REPORT.md 第四节"建议"。
 *
 * 运行命令：./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    /** 测试用数据库名称 */
    private val testDb = "migration-test.db"

    /**
     * MigrationTestHelper 通过 schema JSON 文件创建对应版本的数据库。
     * specClasses 留空，因为迁移不依赖 @AutoMigration spec。
     */
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * Given: 一个 v1 数据库，含 filePath 列的真实数据
     * When: 应用 MIGRATION_1_2 迁移
     * Then:
     *  - 列结构变为 bookDirPath（不再含 filePath）
     *  - 旧行的非文件路径字段保留
     *  - 旧行的 bookDirPath 为空字符串
     */
    @Test
    fun `should migrate from v1 to v2 preserving non file path fields`() {
        // 创建 v1 数据库并插入测试数据
        helper.createDatabase(testDb, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO books (id, title, author, coverPath, filePath, totalChapters, lastReadChapter, lastReadAt)
                VALUES (1, '三体', '刘慈欣', '/covers/1.jpg', '/epubs/santi.epub', 36, 5, 1700000000000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO books (id, title, author, coverPath, filePath, totalChapters, lastReadChapter, lastReadAt)
                VALUES (2, '黑暗森林', '刘慈欣', NULL, '/epubs/dark.epub', 22, 0, 1700000001000)
                """.trimIndent()
            )
        }

        // 执行迁移到 v2
        helper.runMigrationsAndValidate(
            testDb, 2, /* validateDroppedTables = */ true, MIGRATION_1_2,
        ).use { db ->
            // 校验列结构：bookDirPath 存在，filePath 已移除
            db.query("SELECT name FROM pragma_table_info('books')").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(0))
                assertTrue("v2 应包含 bookDirPath 列，实际: $columns", columns.contains("bookDirPath"))
                assertTrue("v2 不应包含 filePath 列，实际: $columns", !columns.contains("filePath"))
            }

            // 校验记录数与字段值
            db.query("SELECT id, title, author, coverPath, bookDirPath, totalChapters, lastReadChapter, lastReadAt FROM books ORDER BY id")
                .use { cursor ->
                    assertEquals("应保留两条记录", 2, cursor.count)
                    cursor.moveToFirst()
                    assertEquals(1L, cursor.getLong(0))
                    assertEquals("三体", cursor.getString(1))
                    assertEquals("刘慈欣", cursor.getString(2))
                    assertEquals("/covers/1.jpg", cursor.getString(3))
                    assertEquals("", cursor.getString(4)) // bookDirPath 应为空
                    assertEquals(36, cursor.getInt(5))
                    assertEquals(5, cursor.getInt(6))
                    assertEquals(1700000000000L, cursor.getLong(7))

                    cursor.moveToNext()
                    assertEquals(2L, cursor.getLong(0))
                    assertEquals("黑暗森林", cursor.getString(1))
                    // 第二条 coverPath 应为 null
                    assertTrue("第二行 coverPath 应为 null", cursor.isNull(3))
                    assertEquals("", cursor.getString(4))
                }
        }
    }

    /**
     * Given: 一个空的 v1 数据库
     * When: 应用 MIGRATION_1_2 迁移
     * Then: 迁移成功，books 表存在但行数为 0，新列结构生效
     */
    @Test
    fun `should migrate empty v1 database to v2 schema`() {
        helper.createDatabase(testDb, 1).close()

        helper.runMigrationsAndValidate(
            testDb, 2, true, MIGRATION_1_2,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM books").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            // 列校验：bookDirPath 存在
            db.query("SELECT name FROM pragma_table_info('books') WHERE name = 'bookDirPath'").use { cursor ->
                assertTrue("bookDirPath 列应存在", cursor.moveToFirst())
            }
        }
    }

    /**
     * Given: 通过 MigrationTestHelper 迁移到 v2 后
     * When: 用 Room.databaseBuilder 真实打开数据库
     * Then: 不抛 schema 校验异常，证明迁移产生的 schema 与 Room 期望的 v2 schema 一致
     *
     * v6 修改：因为 AppDatabase 当前 version=3（新增 bookmarks 表），
     * Room.databaseBuilder 打开时若发现实际 schema 是 v2 会尝试找 MIGRATION_2_3 升级。
     * 所以这里 addMigrations(MIGRATION_1_2, MIGRATION_2_3) 让链路完整。
     * 名称保留为 "after migration to v2"，因为本测试的关注点仍是"v1→v2 后能被 Room 链上"。
     */
    @Test
    fun `should open with Room after migration to v2`() {
        helper.createDatabase(testDb, 1).close()
        helper.runMigrationsAndValidate(testDb, 2, true, MIGRATION_1_2).close()

        // 真实 Room 打开：构造 builder 并 fallbackTo... 不允许，强制走 migration
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, testDb)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            // 强制初始化数据库连接，触发 schema 校验
            db.openHelper.writableDatabase
        } finally {
            db.close()
        }
    }

    /**
     * v6 新增：v2 → v3 迁移验证（书签表创建）。
     *
     * Given: 一个 v2 数据库（books 表存在，无 bookmarks）
     * When: 应用 MIGRATION_2_3
     * Then:
     *  - bookmarks 表被创建，包含 id/bookId/chapterIndex/pageInChapter/note/createdAt 列
     *  - 索引 idx_bookmarks_book_created 存在
     *  - 既有 books 表数据 / 列结构不受影响
     */
    @Test
    fun `should migrate from v2 to v3 adding bookmarks table`() {
        // 创建 v2 数据库并插入一条 books 记录用于验证迁移不破坏既有数据
        helper.createDatabase(testDb, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO books (id, title, author, coverPath, bookDirPath, totalChapters, lastReadChapter, lastReadAt)
                VALUES (1, '三体', '刘慈欣', '/covers/1.jpg', '/books/1', 36, 5, 1700000000000)
                """.trimIndent()
            )
        }

        // 执行迁移到 v3
        helper.runMigrationsAndValidate(testDb, 3, true, MIGRATION_2_3).use { db ->
            // 校验 bookmarks 表存在
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='bookmarks'",
            ).use { cursor ->
                assertTrue("v3 应包含 bookmarks 表", cursor.moveToFirst())
            }

            // 校验 bookmarks 列结构
            db.query("SELECT name FROM pragma_table_info('bookmarks')").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(0))
                listOf("id", "bookId", "chapterIndex", "pageInChapter", "note", "createdAt").forEach { col ->
                    assertTrue("bookmarks 应包含列 $col，实际: $columns", columns.contains(col))
                }
            }

            // 校验复合索引存在
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_bookmarks_book_created'",
            ).use { cursor ->
                assertTrue("应创建索引 idx_bookmarks_book_created", cursor.moveToFirst())
            }

            // 校验 books 表数据未受影响
            db.query("SELECT id, title FROM books WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals("三体", cursor.getString(1))
            }
        }
    }

    /**
     * v6 新增：v1 → v2 → v3 链式迁移端到端验证。
     *
     * Given: 一个 v1 数据库
     * When: 依次应用 MIGRATION_1_2 + MIGRATION_2_3
     * Then: 数据库版本到达 v3，bookmarks 表存在且 books 表的 v1→v2 转换完成
     */
    @Test
    fun `should migrate from v1 to v3 chained`() {
        helper.createDatabase(testDb, 1).close()
        helper.runMigrationsAndValidate(testDb, 3, true, MIGRATION_1_2, MIGRATION_2_3).use { db ->
            // bookmarks 表存在
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='bookmarks'",
            ).use { cursor ->
                assertTrue("v3 应包含 bookmarks 表", cursor.moveToFirst())
            }
            // books 表 v2 schema 已生效
            db.query("SELECT name FROM pragma_table_info('books') WHERE name='bookDirPath'").use { cursor ->
                assertTrue("books 应已迁移到 v2 schema（含 bookDirPath）", cursor.moveToFirst())
            }
        }
    }
}
