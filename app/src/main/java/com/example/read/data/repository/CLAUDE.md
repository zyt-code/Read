# com.example.read.data.repository -- Repository 实现层

## 包概述

`BookRepository` 接口的具体实现，负责协调文件系统操作、EPUB 解包和数据库存储。
是数据层的核心，连接 Room 数据库（通过 DAO）和文件系统（内部存储）。

## 文件列表

| 文件 | 职责 |
|------|------|
| `BookRepositoryImpl.kt` | BookRepository 接口的完整实现 |

## 关键类

### BookRepositoryImpl

构造参数：`BookDao` + `Context`，实现 `BookRepository` 接口。

**内部依赖**：
- `epubParser: EpubParser` -- EPUB 解析工具，无状态可复用
- `booksDir: File` -- 书籍解包目录 (`filesDir/books/`)
- `coversDir: File` -- 封面图片存储目录 (`filesDir/covers/`)
- `json: Json` -- kotlinx.serialization JSON 实例，用于反序列化 metadata.json

## 核心业务流程

### EPUB 导入流程 (`importBook`)

```
SAF URI -> ContentResolver.openInputStream
        -> 创建 UUID 临时目录（booksDir/.tmp/{uuid}/）
        -> try {
             EpubParser.unpack（解压到临时目录，解析 OPF 元数据）
             DAO.insertBook 获取自增 ID
             临时目录重命名为 finalDir（booksDir/{id}/），失败时回退到 Files.move()（跨文件系统兼容）
             DAO.updateBook 更新 bookDirPath
             保存封面图片为 {id}.jpg（目录重命名后执行，确保 ID 可用）
             返回带自增 ID 的 Book
           } catch（任意异常时清理临时目录，防止残留）
```

**崩溃恢复**：应用启动时 `BookshelfViewModel.init` 调用 `cleanupOrphanedBooks()` 清理 bookDirPath 为空的孤立记录（导入中途崩溃导致）。

### 章节内容加载 (`getChapterContent`)

- **按需解析**：章节内容不预存数据库，每次打开章节时从解包目录读取 HTML 文件
- **流程**：DAO 查书籍元数据 -> readMetadata 读取 metadata.json -> 定位 HTML 文件 -> Jsoup 转纯文本 -> 返回 Chapter

### 章节 HTML 文件获取 (`getChapterHtmlFile`)

- 从 metadata.json 读取 spine 信息，根据章节索引定位 HTML 文件路径
- 返回 File 对象，供 WebView 直接加载

### 书籍元数据获取 (`getBookMetadata`)

- 从解包目录的 metadata.json 反序列化 BookMetadata
- 包含 spine 阅读顺序、opfDir 等信息

### 删除流程 (`deleteBook`)

- 先删除文件系统（解包目录 + 封面），再删除数据库记录
- 文件不存在时跳过（已被外部删除的情况），继续清理数据库

## 文件存储策略

| 类型 | 路径 | 说明 |
|------|------|------|
| 解包目录 | `filesDir/books/{id}/` | EPUB 解压后的完整目录结构 |
| 元数据 | `filesDir/books/{id}/metadata.json` | BookMetadata JSON 序列化 |
| 封面图片 | `filesDir/covers/{id}.jpg` | 从 EPUB 提取，以数据库 ID 命名避免 CJK 标题碰撞 |

- 书籍目录以数据库自增 ID 命名，确保唯一性
- 封面文件以 `{id}.jpg` 命名，避免中文标题导致文件名冲突
- 使用内部存储 (`filesDir`)，不需要存储权限

## 依赖关系

- **依赖**：`BookDao`（数据库操作）、`EpubParser`（EPUB 解包）、`domain.model.Book/Chapter`（领域模型）、`data.local.entity.toDomain/toEntity`（映射）、`util.BookMetadata`（元数据模型）
- **被依赖**：`di.AppModule`（注入到 Repository 接口绑定）、通过接口被 `BookshelfViewModel` 和 `ReaderViewModel` 使用

## 编码规范

- 所有 IO 操作在 `Dispatchers.IO` 线程池执行（`withContext(Dispatchers.IO)`）
- Room Flow 的 map 转换在协程上下文中自动执行
- 异常处理：文件系统操作容忍删除失败，数据库操作向上传播异常
- metadata.json 通过 kotlinx.serialization 反序列化，`ignoreUnknownKeys = true` 兼容未来扩展
- HTML 文件路径解析：`bookDirPath/opfDir/href`，opfDir 为空时直接 `bookDirPath/href`
