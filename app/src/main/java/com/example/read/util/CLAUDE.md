# com.example.read.util -- 工具层

## 包概述

存放通用工具类，包含 EPUB 文件解析器和解包结果数据模型。
负责将 EPUB 二进制文件解压到目录结构，解析 OPF 元数据和 NCX 目录生成结构化的 BookMetadata。

## 文件列表

| 文件 | 职责 |
|------|------|
| `EpubParser.kt` | EPUB 文件解析器：ZIP 解压 + OPF 解析 + NCX 目录解析 |
| `EpubUnpackResult.kt` | 解包结果数据模型：EpubUnpackResult、BookMetadata、SpineItem、TocItem |

## 关键类

### EpubParser

EPUB 文件解析器，无状态，可复用。

**内部数据类**：

| 类 | 说明 |
|----|------|
| `ParseResult` | 旧版解析结果（已废弃）：title、author、coverBytes、chapters |
| `ChapterData` | 旧版单章数据（已废弃）：title、htmlContent |

### 核心方法

#### `unpack(inputStream: InputStream, targetDir: File): EpubUnpackResult`

EPUB 解包流程（当前主方法）：

```
InputStream
  -> ZipInputStream 逐条目解压到 targetDir（ZIP Slip 防护：校验解压路径是否在目标目录内）
  -> 单条目大小上限 100MB（ZIP 炸弹防护，常量 MAX_ZIP_ENTRY_SIZE）
  -> 读取 META-INF/container.xml 获取 OPF 路径
  -> 解析 OPF 文件：提取 metadata（标题、作者）和 manifest/spine
  -> 提取封面图片字节（从磁盘读取）
  -> 构建 spine 列表（SpineItem），优先使用 NCX 标题，回退到 HTML h1/h2/title 标签
  -> 解析 NCX 目录文件（parseNcx），提取目录层级结构（TocItem 列表）
  -> 将 TocItem 映射到 spine 索引，设置到 BookMetadata
  -> 序列化 BookMetadata 为 metadata.json 写入解包目录
  -> 返回 EpubUnpackResult（bookDir、title、author、coverBytes、metadata）
```

#### `parse(inputStream: InputStream): ParseResult`（已废弃）

旧版解析方法，标记为 @Deprecated，保留向后兼容。
将整个 EPUB 读入内存，使用 epublib 解析。新代码应使用 `unpack()`。

#### 私有方法

- `parseContainerXml()`: 从 container.xml 提取 OPF 文件路径
- `parseOpf()`: 解析 OPF 文件，提取 manifest 和 spine
- `findCoverImage() / findCoverImageFromDisk()`: 查找封面图片（内存/磁盘两个版本）
- `extractHtmlTitle()`: 从 HTML 的 `<title>` 标签提取标题
- `isHtmlResource()`: 判断资源是否为 HTML 文件（.html/.htm/.xhtml）
- `parseNcx()`: 从 OPF manifest 中查找 NCX 文件并解析目录结构
- `parseNavPoints()`: 递归解析 NCX 的 navPoint 元素，提取标题和层级关系
- `extractHeadingFromHtml()`: 从 HTML 正文提取标题（优先级：h1 > h2 > title 标签）

### EpubUnpackResult

EPUB 解包结果，包含解包后的目录路径、元数据和封面图片字节。

| 字段 | 类型 | 说明 |
|------|------|------|
| `bookDir` | `File` | 解包后的 EPUB 根目录 |
| `title` | `String` | 书籍标题，来自 OPF metadata |
| `author` | `String` | 作者姓名，取第一个 dc:creator |
| `coverBytes` | `ByteArray?` | 封面图片原始字节，可能为 null |
| `metadata` | `BookMetadata` | 书籍结构化元数据 |

### BookMetadata

书籍结构化元数据，从 OPF 和 NCX 文件解析而来，`@Serializable` 注解支持 JSON 序列化。
序列化后存储在解包目录的 `metadata.json` 文件中。

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `String` | 书籍标题 |
| `author` | `String` | 作者姓名 |
| `opfDir` | `String` | OPF 文件所在的相对目录路径 |
| `spine` | `List<SpineItem>` | 按阅读顺序排列的章节列表 |
| `tocItems` | `List<TocItem>` | NCX 目录条目列表，记录章节标题和层级关系 |

### SpineItem

spine 中的单个章节条目，`@Serializable` 注解。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | manifest 中的资源 ID |
| `href` | `String` | 资源文件相对于 opfDir 的路径 |
| `title` | `String` | 章节标题（优先从 NCX 提取，回退到 HTML h1/h2/title 标签） |
| `mediaType` | `String` | MIME 类型 |

### TocItem

NCX 目录中的单个条目，`@Serializable` 注解，表示目录树中的一个节点。

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `String` | 目录条目标题（来自 NCX navLabel） |
| `chapterIndex` | `Int` | 对应 spine 中的章节索引（0-based） |
| `level` | `Int` | 目录层级（0 为顶级，递增表示子级） |

## 依赖关系

- **依赖**：`org.jsoup.Jsoup`（HTML 解析）、`kotlinx.serialization`（JSON 序列化）
- **被依赖**：`data.repository.BookRepositoryImpl`（使用 EpubParser.unpack() 解包 EPUB）

## EPUB 格式说明

EPUB 本质是 ZIP 压缩包，包含：
- XHTML 文件（章节内容）
- CSS 样式表
- 图片资源
- OPF 打包文件（描述书籍结构和元数据）
- NCX/Navigation 文件（目录，由 `parseNcx()` 解析为 TocItem 列表）

`unpack()` 将 ZIP 解压到目录后，解析 OPF 和 NCX 构建 BookMetadata（含目录结构），后续阅读时直接从目录读取 HTML 文件。

## 编码规范

- `EpubParser` 设计为无状态类，不持有任何实例变量（除 JSON 实例），可安全复用
- 解析结果使用 `data class` 封装，便于传递和解构
- BookMetadata/SpineItem/TocItem 使用 `@Serializable` 注解，支持 kotlinx.serialization JSON 序列化
- 字符编码使用 UTF-8（EPUB 标准要求）
- 封面字节返回 `ByteArray?`，null 表示 EPUB 无封面
- 旧版 `parse()` 方法标记为 `@Deprecated`，新代码应使用 `unpack()`
