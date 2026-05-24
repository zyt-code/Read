# com.example.read.domain.model -- 领域模型

## 包概述

纯 Kotlin 数据类定义，表示应用的核心业务实体。
不依赖任何 Android 框架或 Room 注解，ViewModel 和 UI 层只使用这些模型。

## 文件列表

| 文件 | 职责 |
|------|------|
| `Book.kt` | 书籍领域模型 |
| `Chapter.kt` | 章节领域模型 |

## 关键类

### Book

书籍领域模型，表示应用中的一本书。所有字段与 `BookEntity` 一一对应。

| 字段 | 类型 | 默认值 | 业务含义 |
|------|------|--------|----------|
| `id` | `Long` | 0 | 数据库自增主键，新增时为 0 |
| `title` | `String` | - | 书籍标题 |
| `author` | `String` | - | 作者姓名 |
| `coverPath` | `String?` | null | 封面图片路径，null 表示无封面 |
| `bookDirPath` | `String` | "" | EPUB 解包目录路径（替代旧版 filePath） |
| `totalChapters` | `Int` | - | 总章节数 |
| `lastReadChapter` | `Int` | 0 | 上次阅读章节索引 |
| `lastReadAt` | `Long` | 0L | 上次阅读时间戳 |

### Chapter

章节领域模型，表示书籍中的一个章节。不持久化，每次按需从解包目录读取 HTML 文件。

| 字段 | 类型 | 默认值 | 业务含义 |
|------|------|--------|----------|
| `index` | `Int` | - | 章节在书籍中的索引（0-based） |
| `title` | `String` | - | 章节标题，来自 spine 的 title 字段 |
| `content` | `String` | - | 纯文本内容，由 Jsoup 从 HTML 转换 |
| `htmlPath` | `String` | "" | HTML 文件在解包目录中的绝对路径 |

## 依赖关系

- **依赖**：无（纯 Kotlin，零外部依赖）
- **被依赖**：
  - `data.local.entity` -- Entity 通过 `toDomain()/toEntity()` 与此包互相映射
  - `domain.repository` -- 接口方法签名使用此包的类型
  - `data.repository` -- 实现层操作这些模型
  - `ui.bookshelf/reader` -- ViewModel 和 UI 层直接使用这些模型
  - `ui.components` -- BookCard 组件接受 Book 参数

## 编码规范

- 纯 Kotlin `data class`，不使用任何注解（无 `@Entity`、`@SerializedName` 等）
- 不导入任何 `android.*` 或 `androidx.*` 包
- 使用默认参数值简化构造（`id = 0`, `lastReadChapter = 0`, `bookDirPath = ""`, `htmlPath = ""`）
- `Book` 和 `BookEntity` 的字段一一对应，通过扩展函数映射
- `Chapter` 不持久化，仅作为章节内容的传输对象
