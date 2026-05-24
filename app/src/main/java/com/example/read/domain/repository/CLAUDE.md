# com.example.read.domain.repository -- Repository 接口定义

## 包概述

定义数据访问层的接口契约，是依赖倒置原则（DIP）的核心。
ViewModel 只依赖此接口，不直接依赖 Room、文件系统或任何数据源实现。

## 文件列表

| 文件 | 职责 |
|------|------|
| `BookRepository.kt` | 书籍仓库接口，定义所有数据访问操作 |

## 关键类

### BookRepository

接口定义，所有方法对应一种数据访问操作。

**方法清单**：

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `getAllBooks()` | `Flow<List<Book>>` | 响应式获取所有书籍，按最近阅读排序 |
| `getBookById(id)` | `suspend Book?` | 根据 ID 获取单本书籍 |
| `importBook(uri, context)` | `suspend Book` | 导入 EPUB：解包、保存封面、写入数据库 |
| `deleteBook(book)` | `suspend Unit` | 删除书籍：清理文件系统和数据库 |
| `updateReadingProgress(bookId, chapterIndex)` | `suspend Unit` | 更新阅读进度 |
| `getChapterContent(bookId, chapterIndex)` | `suspend Chapter` | 获取章节纯文本内容（Jsoup 转换） |
| `getChapterHtmlFile(bookId, chapterIndex)` | `suspend File` | 获取章节 HTML 文件对象（WebView 加载用） |
| `getBookMetadata(bookId)` | `suspend BookMetadata` | 获取书籍结构化元数据（从 metadata.json） |
| `cleanupOrphanedBooks()` | `suspend Unit` | 清理 bookDirPath 为空的孤立书籍记录（导入崩溃恢复） |

## 设计要点

- `getAllBooks()` 返回 `Flow`，支持响应式数据流，数据库变化时 UI 自动更新
- 其他方法为 `suspend` 函数，在协程中执行
- `importBook` 需要 `Context` 参数，因为要通过 ContentResolver 读取 SAF URI
- 接口只使用领域模型（`Book`、`Chapter`）和 `File`/ `BookMetadata`，不暴露 Room Entity
- `getChapterHtmlFile()` 返回 `File` 对象，供 WebView 通过 file:// 协议加载
- `getBookMetadata()` 返回 `BookMetadata`，包含 spine 阅读顺序信息

## 依赖关系

- **依赖**：`domain.model.Book`、`domain.model.Chapter`（方法签名中的类型）、`util.BookMetadata`
- **被依赖**：
  - `data.repository.BookRepositoryImpl`（实现此接口）
  - `di.AppModule`（绑定实现到接口）
  - `ui.bookshelf.BookshelfViewModel`（注入此接口）
  - `ui.reader.ReaderViewModel`（注入此接口）

## 编码规范

- 接口方法只使用领域模型类型，不使用 Room Entity 或 Android 特定类型
- `getAllBooks()` 返回 `Flow`（响应式），其余方法返回 `suspend`（一次性）
- `importBook` 是唯一需要 `Context` 参数的方法（SAF URI 读取需要）
- 实现类在 `data.repository` 包中，不在本包
