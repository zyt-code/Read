# com.example.read.data.local.entity -- Room 实体定义

## 包概述

定义 Room 数据库的实体类（表结构），以及实体与领域模型之间的双向映射函数。
当前仅包含 `BookEntity`，对应 `books` 表。

## 文件列表

| 文件 | 职责 |
|------|------|
| `BookEntity.kt` | books 表的实体定义、`toDomain()` 和 `toEntity()` 扩展函数 |

## 关键类

### BookEntity

`@Entity(tableName = "books")` 注解的 Room 实体，存储书籍的持久化信息。

**字段设计**：

| 字段 | 类型 | 默认值 | 业务含义 |
|------|------|--------|----------|
| `id` | `Long` | 0 | 自增主键，insert 后由 Room 分配 |
| `title` | `String` | - | 书籍标题，来自 EPUB metadata |
| `author` | `String` | - | 作者姓名 |
| `coverPath` | `String?` | null | 封面图片路径，null 表示 EPUB 无封面 |
| `bookDirPath` | `String` | - | EPUB 解包目录在内部存储的绝对路径（v2 替代 filePath） |
| `totalChapters` | `Int` | - | 总章节数，用于阅读器进度显示和边界检查 |
| `lastReadChapter` | `Int` | 0 | 上次阅读章节索引（0-based），用于恢复阅读位置 |
| `lastReadAt` | `Long` | 0L | 上次阅读时间戳，用于书架按最近阅读排序 |

### 映射函数

- **`BookEntity.toDomain()`**：实体 -> 领域模型，逐字段映射，保持领域层不依赖 Room 注解
- **`Book.toEntity()`**：领域模型 -> 实体，用于 insert/delete/update 操作

## 依赖关系

- **依赖**：`domain.model.Book`（映射目标类型）
- **被依赖**：`data.local.dao.BookDao`（作为 DAO 操作的数据类型）、`data.repository.BookRepositoryImpl`（调用映射函数）

## 编码规范

- 使用 `@PrimaryKey(autoGenerate = true)` 实现自增主键
- 所有字段使用 `val`（不可变数据类）
- 可空字段用 `?` 标记并提供合理默认值（如 `coverPath` 默认 null）
- 映射函数定义为顶层扩展函数，不放在类内部
- 映射时逐字段显式赋值，不使用反射或序列化框架（性能和可读性）
