# com.example.read.data.local -- 本地数据层

## 包概述

Room 数据库层，负责书籍数据的本地持久化。包含数据库定义、实体、数据访问对象和版本迁移脚本。
通过 Room 的 Flow 支持实现响应式数据流，数据库变化时自动通知 UI 更新。

## 文件列表

| 文件 | 职责 |
|------|------|
| `AppDatabase.kt` | Room 数据库定义，声明所有表和版本号 |
| `Migrations.kt` | 数据库版本迁移脚本 |
| `entity/BookEntity.kt` | books 表的实体定义和领域模型映射 |
| `dao/BookDao.kt` | books 表的数据访问对象 |

## 关键类

### AppDatabase

- 继承 `RoomDatabase()`，使用 `@Database` 注解声明实体列表
- 当前版本：version = 2，仅包含 `books` 表
- `exportSchema = true` 导出 schema JSON，用于版本迁移测试
- 通过 `abstract fun bookDao(): BookDao` 暴露 DAO 实例，Room 自动生成实现

### Migrations

数据库版本迁移脚本集合。

- **MIGRATION_1_2**：将 EPUB 存储从单文件复制模式改为解包到目录结构模式
  - `filePath` 列替换为 `bookDirPath` 列
  - 使用重建表策略（CREATE TABLE books_new -> INSERT -> DROP -> RENAME）
  - 旧数据的 `bookDirPath` 设为空字符串（需重新导入）
  - 使用重建表而非 ALTER TABLE DROP COLUMN，因为 DROP COLUMN 需要 SQLite 3.35.0+

## 数据库 Schema

**books 表**（对应 `BookEntity`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK, 自增) | 书籍唯一标识 |
| title | String | 书名 |
| author | String | 作者 |
| coverPath | String? | 封面图片文件路径（nullable） |
| bookDirPath | String | EPUB 解包目录路径（v2 新增，替代 filePath） |
| totalChapters | Int | 总章节数 |
| lastReadChapter | Int | 上次阅读章节索引（0-based） |
| lastReadAt | Long | 上次阅读时间戳（epoch millis） |

## 依赖关系

- **依赖**：`domain.model.Book`（领域模型，用于 Entity 映射）
- **被依赖**：`data.repository.BookRepositoryImpl`（通过 DAO 操作数据库）、`di.AppModule`（提供 Database、DAO 和 Migration 实例）

## 编码规范

- Entity 字段使用 `val`（不可变），与领域模型保持一致
- Entity 到领域模型的映射通过扩展函数 `toDomain()` / `toEntity()` 实现
- DAO 中查询方法使用 `Flow<List<T>>` 实现响应式，写操作使用 `suspend`
- 数据库版本变更时必须提供 Migration，通过 `addMigrations()` 注册到 Database Builder
