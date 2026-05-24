# 数据与文件模型

本文档详细说明 Read 应用的所有持久化数据：Room 数据库 schema、SharedPreferences 键、文件系统布局、以及 EPUB 解包后的目录结构。

---

## 目录

- [Room 数据库](#room-数据库)
- [数据库迁移历史](#数据库迁移历史)
- [文件系统布局](#文件系统布局)
- [EPUB 解包后目录示例](#epub-解包后目录示例)
- [`metadata.json` 结构](#metadatajson-结构)
- [SharedPreferences 键](#sharedpreferences-键)
- [UI 内存态 `PlaceholderBook`](#ui-内存态-placeholderbookp1-new-1)
- [UI 内存态 `SearchResult`（v5）](#ui-内存态-searchresultv5)
- [数据备份与清理](#数据备份与清理)

---

## Room 数据库

- **数据库名**：`read.db`
- **物理位置**：`/data/data/com.example.read/databases/read.db`
- **当前版本**：`2`（定义于 `app/src/main/java/com/example/read/data/local/AppDatabase.kt:17`）
- **Schema 导出**：`app/schemas/com.example.read.data.local.AppDatabase/<version>.json`（启用了 `exportSchema = true`）

### `books` 表

实体定义见 `app/src/main/java/com/example/read/data/local/entity/BookEntity.kt:19`。

| 列名 | 类型 | 约束 | 默认值 | 业务含义 |
|------|------|------|--------|----------|
| `id` | `INTEGER` | PRIMARY KEY AUTOINCREMENT | - | 自增主键，`insertBook` 后由 Room 分配 |
| `title` | `TEXT` | NOT NULL | - | 书名，来自 EPUB OPF `<dc:title>` |
| `author` | `TEXT` | NOT NULL | - | 作者，来自 EPUB OPF `<dc:creator>` |
| `coverPath` | `TEXT` | NULLABLE | NULL | 封面图片绝对路径，无封面时为 NULL |
| `bookDirPath` | `TEXT` | NOT NULL | `''` | EPUB 解包目录绝对路径，v2 替代旧字段 `filePath`。**临时态**：导入第一阶段会写为 `"PREPARING_{uuid}"`，第二阶段成功后才改写为真实绝对路径（详见下方说明） |
| `totalChapters` | `INTEGER` | NOT NULL | - | spine 章节总数，用于进度展示和边界检查 |
| `lastReadChapter` | `INTEGER` | NOT NULL | `0` | 上次阅读章节索引（0-based） |
| `lastReadAt` | `INTEGER` | NOT NULL | `0` | 上次阅读时间戳（epoch millis），用于排序 |

### 索引与排序

- 没有显式索引（书架查询是简单的 `SELECT *`，全表扫描在百级数据下足够快）。
- 排序：`@Query("SELECT * FROM books ORDER BY lastReadAt DESC")`（见 `BookDao.kt:29`），最近阅读的排在最前。

### 写操作策略

| 方法 | 写入字段 | 触发场景 |
|------|----------|----------|
| `insertBook` | 全部 | EPUB 导入第一阶段（占位，`bookDirPath = "PREPARING_{uuid}"`） |
| `updateBook` | 全部 | EPUB 导入第二阶段（写入真实 `bookDirPath` + `coverPath`） |
| `updateReadingProgress` | `lastReadChapter`, `lastReadAt` | 章节切换时实时更新 |
| `deleteBook` | - | 用户长按删除书籍 |

### `bookDirPath` 取值的三种状态

| 取值形态 | 含义 | 来源 | `cleanupOrphanedBooks` 行为 |
|---------|------|------|----------------------------|
| `"/data/data/com.example.read/files/books/{id}"` | 已完成导入的正常书籍 | `startImport` 成功后 `updateBook` 写入 | 保留 |
| `"PREPARING_{uuid}"` | 第一阶段已完成，第二阶段进行中或异常中断 | `prepareImport` 写入（`BookRepositoryImpl.kt:121`） | **活跃（< 1 小时）跳过**；**幽灵（≥ 1 小时）删除** |
| **`"PREPARING_{uuid}"` 持续 > 1h** | 进程崩溃 / OOM Killer 杀死 `startImport` 留下的占位 | 同上，但 `lastReadAt` 未更新 | **视为幽灵记录，启动 `cleanupOrphanedBooks` 时主动 `deleteBook` 清除**（P1-1） |
| `""`（空字符串） | v1→v2 迁移残留，或非常老版本的导入失败占位 | `MIGRATION_1_2` 默认值 | 删除 |

P0-5 修复后，"占位"语义从"空字符串 + sentinel 目录"切换为 `"PREPARING_{uuid}"` 前缀，避免清理逻辑误判仍在进行中的导入。P1-1 在此基础上引入"幽灵阈值"`PREPARING_GHOST_THRESHOLD_MS = 1 小时`（`BookRepositoryImpl.kt:311`）：超过该时长仍未完成第二阶段导入的占位记录视为崩溃残留，启动清理时按 `now - lastReadAt ≥ 阈值` 判定并删除。schema 没有变化，仅是同一列的不同字面值。

### `getAllBooks` 与 `getAllBooksIncludingPreparing` 的契约差异（P1-1）

DAO 层提供两个查询方法，分别服务于 UI 路径和清理路径：

| DAO 方法 | SQL | 调用方 | 语义 |
|----------|-----|--------|------|
| `getAllBooks()` | `SELECT * FROM books WHERE bookDirPath NOT LIKE 'PREPARING_%' ORDER BY lastReadAt DESC` | `BookRepositoryImpl.getAllBooks` → `BookshelfViewModel.books`（UI） | 仅返回 READY 书籍。书架订阅的 Flow 永远不会发射占位记录。 |
| `getAllBooksIncludingPreparing()` | `SELECT * FROM books ORDER BY lastReadAt DESC` | `BookRepositoryImpl.cleanupOrphanedBooks`（仅启动清理） | 全量记录，含 `PREPARING_`。清理逻辑根据 `lastReadAt` 时间阈值识别活跃/幽灵。 |

**契约约束**：

- 新功能如需展示书籍列表，应始终走 `getAllBooks()`；除非业务明确需要扫描全部占位（如统计/排查工具），否则不要直接使用 `getAllBooksIncludingPreparing()`。
- `getBookById(id)` 不做过滤，因为导入流程内部要通过 ID 读取占位记录推断 sentinel 目录（合法访问）。

### UI 内存态 `PlaceholderBook`（P1-NEW-1）

`PlaceholderBook` 是 ViewModel 层的**轻量内存模型**，定义于 `app/src/main/java/com/example/read/ui/bookshelf/BookshelfViewModel.kt:37`，**不写入 Room、不与持久化关联**。

```kotlin
data class PlaceholderBook(
    val id: Long,              // 即 prepareImport 返回的 placeholderId（也是 Room PREPARING 记录的主键）
    val titleHint: String? = null,
    val progress: Float,       // 0.0 ~ 1.0，对应 importProgress[id]
)
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `Long` | 同 `BookshelfViewModel.importProgress` 的 key，等于 `prepareImport` 返回的 `placeholderId`，也等于 Room 中对应 `PREPARING_` 记录的主键 |
| `titleHint` | `String?` | 可选标题提示（如导入文件名），为 `null` 时 UI 显示通用文案"正在导入…" |
| `progress` | `Float` | 当前导入进度（0.0 ~ 1.0），等同 `importProgress[id]` |

生命周期：

- `BookshelfViewModel.importBook` 的 try 块入口 `upsertPlaceholder(placeholderId, progress = 0.05f)`
- `startImport` 进度回调内 `upsertPlaceholder(...).copy(progress = ...)`
- 成功路径 / 失败路径都在 finally 等价点 `removePlaceholder(placeholderId)`
- 进程重启时**不恢复**：重启后没有活跃 IO 任务，无需恢复进度环卡片；Room 中残留的 `PREPARING_*` 由 `cleanupOrphanedBooks` 在 1 小时阈值后清理。

#### `PlaceholderBook` 与 Room `PREPARING_*` 记录：两套互不影响的视图

P1-1 在 SQL 层过滤 `PREPARING_*` 后，Room 中的占位记录**只服务于清理路径**；P1-NEW-1 引入 `PlaceholderBook` 后，UI 进度环**只读 ViewModel 内存态**。两者通过 `placeholderId` 串联但不共享存储：

| 视图 | 唯一真源 | 消费者 | 触发删除/移除的事件 |
|------|---------|--------|---------------------|
| Room `books` 表中 `bookDirPath = "PREPARING_<uuid>"` 的记录 | 数据库（持久化） | `cleanupOrphanedBooks`（仅启动时） | `startImport` 成功 `updateBook` 改写为真实路径 / `startImport` 失败 `deleteBook` / `cleanupOrphanedBooks` 在 1h 阈值后 `deleteBook` |
| `BookshelfViewModel.placeholderBooks` 中的 `PlaceholderBook` | ViewModel 内存（非持久化） | `BookshelfScreen` 的 `LazyVerticalGrid` | `importBook` 流程 finally 块 `removePlaceholder(id)` |

**一致性窗口**：

- 成功路径：`startImport` 完成 → Room flow 发射真实 `BookEntity` → `_placeholderBooks.removePlaceholder` 紧随其后。Room flow 通常在 `updateBook` 后 < 16ms 内发射新值，两者切换在肉眼一帧内完成。
- 失败路径：catch 块先 `removePlaceholder`，再调用 `repository.deleteBook` 清掉 Room 中的占位记录。两者无序但都幂等，不存在残留风险。
- 进程崩溃：UI 内存态丢失，Room 中的 `PREPARING_*` 记录残留，等下次启动由 `cleanupOrphanedBooks` 在 1h 阈值后清理（详见上一节）。

> 设计动机：**让 UI 的"是否要画进度环"完全由 ViewModel 控制**，避免业务上"幽灵清理"和"进度环显示"两个语义不同的关注点共用同一张 SQL 视图（P1 / P1-NEW-1 两轮修复的核心教训）。

---

## UI 内存态 `SearchResult`（v5）

`SearchResult` 是 v5 跨章节全书搜索的结果项，定义于
`app/src/main/java/com/example/read/data/search/BookSearchEngine.kt:28`，
**不写入 Room、不与持久化关联**，仅存活于 `ReaderViewModel.bookSearchResults: StateFlow<List<SearchResult>>`
的 ViewModel 内存态。

```kotlin
data class SearchResult(
    val chapterIndex: Int,         // 命中章节的 spine 索引（0-based）
    val chapterTitle: String,      // 章节标题（来自 BookMetadata.spine[*].title）
    val matchCount: Int,           // 该章节内的匹配总数（忽略大小写）
    val firstMatchSnippet: String, // 首个匹配位置前后各 30 字 + 上下文省略号
)
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `chapterIndex` | `Int` | 命中所在的章节 spine 索引（0-based）；点击结果时 `ReaderViewModel.jumpToChapter(chapterIndex)` 直接消费 |
| `chapterTitle` | `String` | 章节标题；spine 中没有 title 时回退到"第 N 章" |
| `matchCount` | `Int` | 该章节内匹配总数（链式 `String.indexOf(ignoreCase=true)` 计数） |
| `firstMatchSnippet` | `String` | 首个匹配位置前后各 `SNIPPET_RADIUS=30` 字符；内部空白（`\\s+`）压缩为单空格；上下文边界补 `…` |

### 不创建持久化索引的设计动机

`ReaderViewModel.searchWholeBook(query)` 每次搜索都全量线性扫描所有章节纯文本（详见
[docs/architecture.md#全书搜索](architecture.md#全书搜索)），**不创建任何持久化索引**：

| 决策 | 原因 |
|------|------|
| 不写 Room | Room schema 已稳定，引入"搜索索引表"需要 MIGRATION_2_3 + 全量重建索引；与 v5 主线"低风险渐进式落地"取舍冲突 |
| 不写文件 | 离线倒排索引（如把 spine 拍平成 `term -> [chapterIndex, position]` 写入 JSON）会与 EPUB 重新导入 / 设置变更触发的 WebView 重建状态机出现一致性窗口 |
| 不写内存缓存（跨 ViewModel） | `ReaderViewModel` 在 Activity 销毁时被回收，下次进入阅读器重新调 `searchWholeBook` 即可；缓存命中率低、占用大 |
| 不持久化"最近搜索" | 隐私考虑：阅读关键词可能涉及敏感信息，按需输入比留痕更稳妥 |

v6 路线图中"SQLite FTS / 离线倒排索引"（*规划中*）是面向长篇大部头的优化方向，
当前 v5 实现可在中长书（≤50 章）上 125ms-500ms 内返回结果，无持久化索引开销。

### 生命周期

- `ReaderViewModel.searchWholeBook(query)` 启动 `bookSearchJob` → `BookSearchEngine.search()` 扫描 → 写入 `_bookSearchResults`
- 用户继续输入 → `bookSearchJob.cancel()` → 旧结果被新结果覆盖（短查询 < 2 字符直接清空）
- `setSearchMode(SearchMode.InChapter)` → 立即清空 `_bookSearchResults`、取消 `bookSearchJob`
- `onBookSearchResultClicked(result)` → 跳转后**保留** `_bookSearchResults`（用户返回搜索栏仍能看到上次结果列表）
- ViewModel 销毁 → 整个 StateFlow 链路被 GC，结果集随之消失

### 与章内搜索状态的边界

| 关注点 | 章内搜索（v4） | 全书搜索（v5） |
|--------|---------------|---------------|
| 高亮存储 | WebView DOM 中以 `<mark class="reader-find">` 节点存在 | 不修改 WebView DOM；结果只在 ViewModel 内存态 |
| UI 出口 | 顶栏 `FindInPageBar` 的 `x / y` 计数 + 上下导航 | 底部 `WholeBookSearchResultsSheet` 列表 |
| 跨章节生命周期 | 切章节自动 `exitFindMode` 清空高亮 | 切章节**不清空** `_bookSearchResults`，仅切回 `SearchMode.InChapter` |
| 持久化 | 不持久化 | 不持久化 |

---

## 数据库迁移历史

### `MIGRATION_1_2`：`filePath` → `bookDirPath`

**位置**：`app/src/main/java/com/example/read/data/local/Migrations.kt:19`

**变更原因**：旧版本将整个 EPUB 文件复制到 app 内部存储后保留 `.epub` 文件，每次阅读都要重新解压。新版本改为"导入即解包"，把 ZIP 解压到目录里，运行时直接读取 HTML，**性能大幅提升、内存占用更稳定**。这一变化要求把 `filePath`（指向单个 .epub 文件）改为 `bookDirPath`（指向解包目录）。

**迁移策略**：使用 **重建表** 而非 `ALTER TABLE ... DROP COLUMN`。原因：

- `DROP COLUMN` 需要 SQLite **3.35.0+**（Android 12 起内置）。
- 项目 `minSdk = 26`（Android 8.0），低版本设备 SQLite 不支持，会在迁移时崩溃。

```sql
-- Step 1: 建新表
CREATE TABLE books_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    title TEXT NOT NULL,
    author TEXT NOT NULL,
    coverPath TEXT,
    bookDirPath TEXT NOT NULL DEFAULT '',
    totalChapters INTEGER NOT NULL,
    lastReadChapter INTEGER NOT NULL DEFAULT 0,
    lastReadAt INTEGER NOT NULL DEFAULT 0
);

-- Step 2: 从旧表复制（bookDirPath 留空，旧书需要重新导入）
INSERT INTO books_new (id, title, author, coverPath, bookDirPath, totalChapters, lastReadChapter, lastReadAt)
SELECT id, title, author, coverPath, '', totalChapters, lastReadChapter, lastReadAt FROM books;

-- Step 3: 删除旧表，重命名新表
DROP TABLE books;
ALTER TABLE books_new RENAME TO books;
```

**用户感知**：迁移后 v1 的书籍会在书架上保留标题与封面，但 `bookDirPath = ''`，无法打开阅读器。`BookshelfViewModel.init` 会调用 `cleanupOrphanedBooks()` 自动清理这些孤立记录（`BookshelfViewModel.kt:42`、`BookRepositoryImpl.kt:249`）。用户重新导入即可恢复阅读。

### 后续迁移规范

新增迁移时遵循以下流程（详见 [docs/development.md#room-修改流程](development.md#room-修改流程)）：

1. 修改 `BookEntity` 字段。
2. `AppDatabase` 版本号 `+1`。
3. `Migrations.kt` 新增 `MIGRATION_N_N+1` 并在 `AppModule.provideDatabase` 注册。
4. 提交新生成的 `app/schemas/.../{N+1}.json`。
5. 用 `androidx.room:room-testing` 的 `MigrationTestHelper` 写迁移测试。

---

## 文件系统布局

所有持久化文件都位于 app 内部存储（`Context.filesDir`），路径形如 `/data/data/com.example.read/files/`。使用内部存储有两个好处：

1. **不需要 `READ/WRITE_EXTERNAL_STORAGE` 权限**：与 SAF 配合，零权限模型。
2. **应用卸载时自动清理**：用户卸载 app 后所有书籍数据随之删除。

```
filesDir/
├── books/
│   ├── 1/                          # 书籍 ID 1 的解包目录
│   │   ├── META-INF/
│   │   │   └── container.xml
│   │   ├── OEBPS/                  # OPF 所在目录（具体名称取决于 EPUB 制作工具）
│   │   │   ├── content.opf
│   │   │   ├── toc.ncx
│   │   │   ├── stylesheet.css
│   │   │   ├── ch01.xhtml
│   │   │   ├── ch02.xhtml
│   │   │   └── images/
│   │   │       └── cover.jpg
│   │   └── metadata.json           # Read 自动生成的元数据缓存
│   ├── 2/                          # 书籍 ID 2 的解包目录
│   │   └── ...
│   └── temp_{uuid}/                # 临时解包目录（导入过程中存在）
│
└── covers/
    ├── 1.jpg                       # 书籍 ID 1 的封面（以数据库 ID 命名）
    ├── 2.jpg
    └── ...
```

### 关键设计

| 设计 | 原因 |
|------|------|
| 书籍目录以 **数据库自增 ID** 命名 | 保证唯一性，避免中文标题或同名书籍冲突 |
| 封面文件以 `{id}.jpg` 命名 | 同上，避免文件名特殊字符 |
| 解包目录与封面分离 | 封面文件被 Coil 频繁加载（书架展示），独立目录便于缓存命中 |
| 临时目录使用 `temp_{uuid}` | UUID 避免并发导入冲突；前缀 `temp_` 便于识别和清理 |
| `metadata.json` 缓存 spine / TOC | 避免每次打开书籍都重新解析 OPF + NCX |

### 文件操作映射

| 操作 | 涉及路径 | 代码位置 |
|------|---------|----------|
| 读取章节 HTML | `books/{id}/{opfDir}/{href}` | `BookRepositoryImpl.kt:236` `resolveHtmlFile` |
| 读取 metadata | `books/{id}/metadata.json` | `BookRepositoryImpl.kt:228` `readMetadata` |
| WebView baseUrl | `file://books/{id}/{opfDir}/` | `ReaderViewModel.kt:373` |
| 显示封面 | `covers/{id}.jpg` | Coil `AsyncImage(model = File(coverPath))` |
| 删除书籍 | 递归删除 `books/{id}/` + 删除 `covers/{id}.jpg` | `BookRepositoryImpl.kt:176` `deleteBook` |
| 清理孤立 | `bookDirPath = ''` 的数据库记录（`PREPARING_` 前缀的记录被跳过） | `BookRepositoryImpl.kt:317` `cleanupOrphanedBooks` |

---

## EPUB 解包后目录示例

以下示例展示一本典型 EPUB 解包后的结构。注意：**目录名（如 `OEBPS`）和子目录结构由 EPUB 制作工具决定**，Read 不会重新组织。

```
books/42/
├── mimetype                                   # 内容固定为 application/epub+zip
├── META-INF/
│   └── container.xml                          # 指向 OPF 文件
├── OEBPS/                                     # OPF 所在目录（也可能叫 EPUB/、item/ 等）
│   ├── content.opf                            # 书籍元数据、manifest、spine
│   ├── toc.ncx                                # EPUB 2 目录（NCX 格式）
│   ├── nav.xhtml                              # EPUB 3 目录（部分书籍同时包含）
│   ├── stylesheet.css                         # 内嵌样式表
│   ├── fonts/                                 # 嵌入字体（可选）
│   │   └── SourceHanSerif.otf
│   ├── images/                                # 图片资源
│   │   ├── cover.jpg
│   │   └── chapter01-fig1.png
│   ├── ch01.xhtml                             # 章节 1 HTML
│   ├── ch02.xhtml                             # 章节 2 HTML
│   └── ...
└── metadata.json                              # Read 自动生成，详见下一节
```

### container.xml 示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf"
              media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
```

`EpubParser.parseContainerXml()` 从中提取 `full-path = "OEBPS/content.opf"`。

### content.opf 关键节点（节选）

```xml
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata>
    <dc:title>三体</dc:title>
    <dc:creator>刘慈欣</dc:creator>
    <meta name="cover" content="cover-image"/>          <!-- EPUB 2 风格 -->
  </metadata>
  <manifest>
    <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
    <item id="ch1" href="ch01.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch02.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>
```

`EpubParser.parseOpf()` 同时支持 EPUB 2 和 EPUB 3：

- **EPUB 3 封面**：manifest item `properties="cover-image"`
- **EPUB 2 封面**：`<meta name="cover" content="manifestItemId"/>`
- **启发式回退**：manifest item id 包含 "cover" 且为图片 MIME

---

## `metadata.json` 结构

由 `EpubParser.unpack()` 写入 `books/{id}/metadata.json`，使用 `kotlinx.serialization` 序列化。读取时用 `Json { ignoreUnknownKeys = true }`（见 `BookRepositoryImpl.kt:28`）以兼容未来字段扩展。

完整定义见 `app/src/main/java/com/example/read/util/EpubUnpackResult.kt`。

```json
{
  "title": "三体",
  "author": "刘慈欣",
  "opfDir": "OEBPS",
  "spine": [
    {
      "id": "ch1",
      "href": "ch01.xhtml",
      "title": "第一章 疯狂年代",
      "mediaType": "application/xhtml+xml"
    },
    {
      "id": "ch2",
      "href": "ch02.xhtml",
      "title": "第二章 寂静的春天",
      "mediaType": "application/xhtml+xml"
    }
  ],
  "tocItems": [
    { "title": "第一章 疯狂年代", "chapterIndex": 0, "level": 0 },
    { "title": "第二章 寂静的春天", "chapterIndex": 1, "level": 0 },
    { "title": "小节 2.1", "chapterIndex": 1, "level": 1 }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `String` | 书名（同 Room 中 `books.title`） |
| `author` | `String` | 作者 |
| `opfDir` | `String` | OPF 所在的相对目录（如 `"OEBPS"`、`""`、`"item"`），用于拼接 baseUrl 与章节路径 |
| `spine` | `List<SpineItem>` | 按阅读顺序排列的章节，仅包含 HTML 资源 |
| `spine[].title` | `String` | 标题提取优先级：NCX > `<h1>` > `<h2>` > `<title>` > 空字符串 |
| `tocItems` | `List<TocItem>` | NCX 目录条目，可能为空（无 NCX 文件的 EPUB） |
| `tocItems[].chapterIndex` | `Int` | 对应 spine 索引（0-based），通过 NCX href → spine href 反向映射得到 |
| `tocItems[].level` | `Int` | 目录层级，0 表示顶级；用于 ModalBottomSheet 的缩进展示 |

### 章节 HTML 文件定位规则

```
fullPath = bookDirPath / opfDir / href
```

具体由 `BookRepositoryImpl.resolveHtmlFile()`（`BookRepositoryImpl.kt:236`）实现：

```kotlin
private fun resolveHtmlFile(bookDirPath: String, opfDir: String, href: String): File {
    val basePath = if (opfDir.isEmpty()) bookDirPath else "$bookDirPath/$opfDir"
    return File(basePath, href)
}
```

---

## SharedPreferences 键

应用使用两个独立的 SharedPreferences 文件：

### 1. `reading_settings`

阅读样式偏好，由 `ReadingSettingsManager`（`app/src/main/java/com/example/read/ui/reader/ReadingSettings.kt:144`）管理。

| Key | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| `font_size` | `Float` | `18f` | 字号（sp），范围 14-28 |
| `line_height` | `Float` | `1.8f` | 行高倍数，范围 1.2-2.5 |
| `font_family` | `String` | `"Serif"` | 取值：`"Serif"` / `"Sans-serif"` / `"Monospace"` |
| `bg_color` | `Int` | `0` | `BackgroundColor` 枚举的 `ordinal`：0=WHITE, 1=SEPIA, 2=DARK |

物理位置：`/data/data/com.example.read/shared_prefs/reading_settings.xml`

### 2. `reader_page_progress`

跨章节的章内页码缓存，由 `ReaderViewModel` 直接读写（`ReaderViewModel.kt:63`）。

| Key 模板 | 类型 | 默认值 | 说明 |
|----------|------|--------|------|
| `page_ch_{bookId}_{chapterIndex}` | `Int` | `0` | 该 (书籍, 章节) 上次停留的章内页码（0-based） |

写入时机：

- 章节切换前（`syncChapterState`、`jumpToChapter`）保存"离开章节"的章内页码。
- ViewModel `onCleared()` 时保存当前章节的章内页码。

读取时机：

- `loadBook()` 在恢复阅读位置时读取。
- 设置变更后重新分页时也走相同的恢复路径。

#### 100ms debounce 写入策略

`savePageInChapter` 不直接 `apply()`，而是先把待写入的 key/value 存到内存 `pendingPageWrites` 映射，并启动一个 100ms 延迟的协程 Job 统一 batch apply（`ReaderViewModel.kt:486-510`）。100ms 窗口内的连续翻页只保留最后一个值，每次翻页都触发一次磁盘 I/O 的问题被显著缓解。

实现细节：

- `savePageJob?.cancel()` 取消上一次延时；新调用重排一个新的 100ms 延时。
- `getPageInChapter` 在读取时优先返回 `pendingPageWrites` 中未提交的值，避免 debounce 窗口内读到陈旧值。
- `onCleared()` 中无法等待 `viewModelScope`（已被取消），改为同步调用 `flushPendingPageWrites()` 把待写值立即 `apply()`。

**潜在丢失风险**：

- 进程在 100ms 窗口内被系统强杀（如 OOM Killer、用户从最近任务划掉）时，未 flush 的最后一次 `pendingPageWrites` 会丢失，下次启动会回退到上一次成功 apply 的页码。最差情况下损失 ≤ 100ms 内的翻页进度（通常只有 1 页），属可接受的取舍。
- 如果要进一步降低丢失风险，可考虑把 `apply()` 改成 `commit()`、或减少 debounce 窗口到 50ms。

物理位置：`/data/data/com.example.read/shared_prefs/reader_page_progress.xml`

---

## 数据备份与清理

### 卸载行为

由于所有数据存在内部存储，用户卸载 app 时系统会自动清理：

- `databases/read.db`
- `files/books/`
- `files/covers/`
- `shared_prefs/reading_settings.xml`
- `shared_prefs/reader_page_progress.xml`

### `allowBackup`

`AndroidManifest.xml` 中 `android:allowBackup="true"`，意味着 Google 自动备份机制可能将以上数据备份到云端。涉及隐私时（如读书记录），可考虑在后续版本中改为 `false` 或加 `android:fullBackupContent` 限制。

### 应用内清理

| 操作 | 触发 | 影响 |
|------|------|------|
| 删除书籍 | 用户长按 → 确认 | 递归删除 `books/{id}/`（B5 修复：检查返回值并写日志，但不阻塞 DB 清理）、`covers/{id}.jpg`、`books` 表中对应记录 |
| 清理孤立记录 | 应用启动时（`BookshelfViewModel.init`） | 删除 `bookDirPath = ''` 的数据库记录（v1→v2 迁移残留 / 老版本导入失败）。`PREPARING_{uuid}` 前缀的记录一律跳过，交给 `startImport` 的成功/失败路径处理（P0-5 修复） |
| 临时目录清理 | 导入失败 `catch` 块 | 删除 `books/temp_{uuid}/`、对应的 sentinel 目录、以及对应的占位数据库记录 |

### 没有缓存目录

应用没有使用 `cacheDir`（`/data/data/com.example.read/cache/`），原因：

- 解包后的目录是阅读的核心数据，**不能被系统在低存储时自动清理**。
- Coil 的图片缓存使用其自带的内存 + 磁盘缓存机制，开发者不直接管理。

---

## 相关文档

- [docs/architecture.md](architecture.md)：数据流与设计决策。
- [docs/development.md#room-修改流程](development.md#room-修改流程)：迁移规范。
- [docs/testing.md](testing.md)：迁移测试编写。
