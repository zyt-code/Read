# com.example.read.data.local.dao -- Room 数据访问对象

## 包概述

定义 Room DAO 接口，包含 `books` 表的所有数据库操作。
查询操作返回 `Flow` 实现响应式数据流，写操作使用 `suspend` 函数。

## 文件列表

| 文件 | 职责 |
|------|------|
| `BookDao.kt` | books 表的 CRUD 操作和阅读进度更新 |

## 关键类

### BookDao

`@Dao` 注解的接口，Room 自动生成实现类。

**方法清单**：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `getAllBooks()` | `Flow<List<BookEntity>>` | 响应式查询所有书籍，按 `lastReadAt DESC` 排序 |
| `getBookById(id)` | `suspend BookEntity?` | 根据 ID 查询单本书籍，null 表示不存在 |
| `insertBook(book)` | `suspend Long` | 插入新书籍，返回自增 ID |
| `updateBook(book)` | `suspend Unit` | 更新书籍记录（用于更新 bookDirPath 等字段） |
| `deleteBook(book)` | `suspend Unit` | 删除书籍记录（文件清理在 Repository 层） |
| `updateReadingProgress(bookId, chapterIndex, timestamp)` | `suspend Unit` | 更新阅读进度和时间戳 |

## 查询设计

- **`getAllBooks()`**：返回 `Flow`，当 `books` 表有 INSERT/UPDATE/DELETE 时自动发射新数据，驱动 UI 重组。按 `lastReadAt DESC` 排序，最近阅读的书排在最前面。
- **`getBookById()`**：`suspend` 函数，一次性查询，用于阅读器加载书籍元数据。
- **`insertBook()`**：使用 `OnConflictStrategy.REPLACE`，ID 冲突时替换。返回自增 ID。
- **`updateBook()`**：通用更新方法，用于导入完成后更新 `bookDirPath` 等字段。
- **`updateReadingProgress()`**：原子更新章节索引和时间戳，仅修改需要的字段而非整行。

## 依赖关系

- **依赖**：`data.local.entity.BookEntity`（操作的数据类型）
- **被依赖**：`di.AppModule`（从 Database 获取 DAO 实例）、`data.repository.BookRepositoryImpl`（调用 DAO 方法）

## 编码规范

- 查询返回 `Flow` 实现响应式（UI 自动更新），写操作使用 `suspend`（协程执行）
- 排序字段 `lastReadAt DESC` 确保最近阅读的书在书架最前面
- 删除操作只删数据库记录，文件清理由 Repository 层负责（职责分离）
- SQL 使用 `@Query` 注解编写原生 SQL，不做额外抽象
- `updateBook()` 使用 `@Update` 注解，Room 根据主键匹配更新整行
