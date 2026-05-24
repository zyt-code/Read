# 修复执行报告（合并版）

> 本文件合并了项目历次修复执行报告（v1-v3）的完整内容。
> 每轮修复基于审查报告的发现进行集中修复。

---

## 版本演进

| 版本 | 日期 | 修复目标 | 修复数 |
|------|------|----------|--------|
| v1 | 2026/05/24 | P0 级缺陷 + 高价值 Bug（基于 v2 审查 + TDD 报告） | 14 项（P0×5 + B×6 + C×3） |
| v2 | 2026/05/24 | v3 审查报告中的 3 条 P1 新发现 | 3 项（P1-1 / P1-2 / P1-3） |
| v3 | 2026/05/24 | v4 审查报告中的 2 条 P1 退化 | 2 项（P1-NEW-1 / P1-NEW-2） |

---

# 修复报告 v3（最新）

**报告时间**：2026/05/24（Patch v3 收尾）
**输入**：REVIEW_REPORT_v4.md、FIX_REPORT_v2.md、PROGRESS_v3.md、CLAUDE.md
**任务边界**：不动 Schema；不引入新依赖；不跑 `./gradlew`；现有测试不破坏

## 修复总览

| Issue ID | 严重度 | 状态 | 主要文件 |
|---|---|---|---|
| P1-NEW-1 | P1 | 完全修复 | `BookshelfViewModel.kt`、`BookshelfScreen.kt`、`PlaceholderBookCard.kt`（新增） |
| P1-NEW-2 | P1 | 完全修复 | `ReaderViewModel.kt` |
| Doc 同步 | - | 完成 | `README.md`、`CHANGELOG.md` |

## Fix-1：P1-NEW-1 书架导入进度环 UI 消失

### 根因

上一轮 Patch P1 在 SQL 层为 `BookDao.getAllBooks()` 加 `WHERE bookDirPath NOT LIKE 'PREPARING_%'`，阻止幽灵占位记录出现在书架。但同一条 SQL 路径同时被书架的进度环 UI 依赖，导致导入过程完全静默。

### 修复方案（A 方案：ViewModel 暴露独立 placeholderBooks StateFlow）

1. **新增 `PlaceholderBook` 数据类**（同包同文件）：
   ```kotlin
   data class PlaceholderBook(
       val id: Long,
       val titleHint: String? = null,
       val progress: Float,
   )
   ```

2. **新增 StateFlow**：
   ```kotlin
   private val _placeholderBooks = MutableStateFlow<List<PlaceholderBook>>(emptyList())
   val placeholderBooks: StateFlow<List<PlaceholderBook>> = _placeholderBooks
   ```

3. **新增 `PlaceholderBookCard.kt`** 组件：视觉与 BookCard 一致（2:3 封面 + 圆角 + 进度环 + 百分比），不可点击/长按。

4. **`BookshelfScreen.kt` 两段渲染**：占位卡片在网格顶部，真实书籍在后。空状态判定改为 `books.isEmpty() && placeholderBooks.isEmpty()`。

## Fix-2：P1-NEW-2 jumpToChapter 未预加载分支错位

### 根因

`jumpToChapter` 未预加载分支写 `_currentGlobalPage.value = 0`，但 globalPages 保留旧章节的页，索引 0 指向旧章节第一页。

### 修复方案

移除 `_currentGlobalPage.value = 0`，改用 `pendingPositionRestore` 状态机：
```kotlin
pendingPositionRestore = true
pendingPageInChapter = 0
```

`onPageCountReady` 已有的恢复逻辑无需改动：`chapterIndex == _chapterIndex.value` 防止预加载误触发，`findGlobalPageByPageInChapter(chapterIndex, 0)` 精确定位目标章节首页。

## 变更文件清单

### 修改
- `app/src/main/java/com/example/read/ui/bookshelf/BookshelfViewModel.kt`
- `app/src/main/java/com/example/read/ui/bookshelf/BookshelfScreen.kt`
- `app/src/main/java/com/example/read/ui/reader/ReaderViewModel.kt`
- `README.md`
- `CHANGELOG.md`

### 新增
- `app/src/main/java/com/example/read/ui/components/PlaceholderBookCard.kt`

---

# 修复报告 v2（历史）

**报告时间**：2026/05/24
**输入**：REVIEW_REPORT_v3.md、FIX_REPORT.md、PROGRESS_v2.md
**范围**：三条 P1 修复，不动 Schema，不引入新依赖

## Fix-1：P1-1 getAllBooks() 未过滤 PREPARING_* 占位记录 + 幽灵清理

**修改文件**：
- `BookDao.kt`：`getAllBooks` SQL 加 `WHERE bookDirPath NOT LIKE 'PREPARING_%'`；新增 `getAllBooksIncludingPreparing()`
- `BookRepositoryImpl.kt`：`cleanupOrphanedBooks` 新增幽灵清理（`PREPARING_GHOST_THRESHOLD_MS = 1 小时`）
- `BookRepositoryImplCleanupTest.kt`（重写）
- `BookRepositoryImplTest.kt`（6 处 mock 路径切换）

## Fix-2：P1-2 WebViewAssetLoader 对空/占位路径抛 IllegalArgumentException

**修改文件**：
- `ChapterWebViewFactory.kt`：`loadHtml` 入口防御（`bookDirPath.isEmpty() || startsWith("PREPARING_")` 短路返回）
- `ReaderScreen.kt`：`PagedChapterAdapter.getOrCreateWebView` 加 try-catch 兜底

## Fix-3：P1-3 nextChapter 未预加载时静默 no-op

**修改文件**：
- `ReaderViewModel.kt`：`nextChapter()` 和 `previousChapter()` 在 `nextPage < 0` 时复用 `jumpToChapter` 兜底

---

# 修复报告 v1（历史）

**报告时间**：2026/05/24
**输入**：REVIEW_REPORT_v2.md + TDD_REPORT.md
**范围**：P0 级缺陷 + 高价值 Bug

## 修复对照表

| Issue | 修改要点 | 风险 |
|-------|----------|------|
| P0-1 ProGuard 规则缺失 | 新建 `app/proguard-rules.pro`，覆盖 kotlinx.serialization/Room/Hilt/Coil/@JavascriptInterface/Navigation 路由 keep | 低 |
| P0-2 WebView 缓存只增不减 | 新增 `WebViewLruCache(maxSize=3)`；改用 `applicationContext`；`updateSettings` 后全销毁重建 | 中 |
| P0-3 WebView 不安全配置 | 引入 `androidx.webkit:webkit:1.12.1`；关闭 file access；WebViewAssetLoader 走 https；AndroidBridge nonce 校验 | 中 |
| P0-4 JS 注入转义不完整 | 新增 `escapeJsString`：基于 `JSONObject.quote` + 二次转义 U+2028/U+2029/`</` | 低 |
| P0-5 cleanupOrphanedBooks 竞态 | `prepareImport` 用 `PREPARING_<uuid>` 前缀；cleanupOrphanedBooks 跳过该前缀 | 中 |
| B1 章节切换缺上下界 | `nextChapter` 增加上界校验 | 低 |
| B2 updateSettings 未重置 currentGlobalPage | 清空 globalPages 时同步 `_currentGlobalPage.value = 0` | 低 |
| B3 normalizeNcxHref 不处理 `..` | 使用 `Paths.get().normalize()` | 低 |
| B5 deleteBook 不检查返回值 | try-catch 包裹，失败 Log.w 仍清理 DB | 低 |
| B6 预加载临时 WebView 泄漏 | 5 秒超时 + 强制 destroy() + disposed 标志 | 低 |
| B8 导入失败未清理占位 | catch 中显式 deleteBook 兜底 | 低 |
| C-1 主线程 readText | 移到 `Dispatchers.IO` | 低 |
| C-2 pagePrefs 未节流 | 100ms debounce + pendingPageWrites map | 中 |
| C-3 Adapter HTML 主线程 I/O | 新增 single-thread ioExecutor | 低 |

## 文件清单

### 修改
- `app/build.gradle.kts` -- 追加 `implementation(libs.androidx.webkit)`
- `gradle/libs.versions.toml` -- 新增 webkit 版本
- `app/proguard-rules.pro` -- 从 Jsoup-only 重写为完整 keep 规则
- `WebViewPaginator.kt` -- 加 nonce 模板与 `escapeJsString` 工具
- `ChapterWebViewFactory.kt` -- 改 https + WebViewAssetLoader + nonce
- `ReaderScreen.kt` -- 新增 WebViewLruCache、用 applicationContext、调整 adapter
- `ReaderViewModel.kt` -- B1/B2/B6 修复 + pagePrefs debounce + IO 切换
- `BookRepositoryImpl.kt` -- PREPARING_ 前缀方案、B5 deleteBook 检查
- `BookshelfViewModel.kt` -- B8 catch 中显式 deleteBook 兜底
- `EpubParser.kt` -- B3 normalizeNcxHref 用 Paths.normalize()

## 放弃修复 / 推迟到下一轮

- 未动 Room schema
- 未重构 BookRepository 接口
- 未修 P1/P2/P3 未列出的问题
- 未引入 Robolectric / Compose UI Test 等新测试依赖
