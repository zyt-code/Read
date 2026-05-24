package com.example.read.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.read.data.local.dao.BookDao
import com.example.read.data.local.dao.BookmarkDao
import com.example.read.data.local.entity.BookEntity
import com.example.read.data.local.entity.BookmarkEntity

/**
 * Room 数据库定义，管理本地 SQLite 数据库。
 *
 * - entities: 声明数据库中所有的表
 *   - books（v1 起）
 *   - bookmarks（v3 新增，v6 feature: 书签）
 * - version: 数据库版本号；schema 变更时必须递增并在 [Migrations] 中提供迁移脚本
 *   - v1 → v2：filePath 改为 bookDirPath（MIGRATION_1_2）
 *   - v2 → v3：新增 bookmarks 表 + 复合索引（MIGRATION_2_3）
 * - exportSchema: 导出 schema JSON 文件，用于版本迁移测试
 *
 * Room 会自动生成 AppDatabase 的实现类，处理 SQLite 连接和线程管理。
 */
@Database(
    entities = [BookEntity::class, BookmarkEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    /** 获取 BookDao 实例，Room 自动生成其实现 */
    abstract fun bookDao(): BookDao

    /** 获取 BookmarkDao 实例（v6 书签），Room 自动生成其实现 */
    abstract fun bookmarkDao(): BookmarkDao
}
