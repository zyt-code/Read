# 变更日志

本项目的所有显著变更都记录在本文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 1.1.0，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/) 2.0.0。

---

## [Unreleased] - 2026-05-24 (v5 跨章节搜索)

> 沿 feat 主线推进 v5：在 v4 章内搜索的基础上引入**跨章节全书搜索**，并同步修复
> 两条 v4 review 遗留：P1-v5-1（`BookshelfViewModel.importBook` 吞 `CancellationException`）
> 与 P1-v5-2（章内搜索 `scrollIntoView` 不联动 ViewPager2 翻页）。
> 顶栏搜索栏增加"章内 ↔ 全书"模式 toggle，全书模式下输入关键词后 `BookSearchEngine`
> 以 `flatMapMerge(concurrency=4)` 并发扫描所有章节，结果通过底部弹出面板列出
> "章节标题 / 匹配数 / 摘要"，点击跳转到对应章节并自动在新章节定位关键词。
> 详细对照表参见 [FEAT_REPORT_v5.md](FEAT_REPORT_v5.md)。

### Added

- **跨章节全书搜索功能**：顶栏 🔍 搜索栏新增 `MenuBook` toggle 切换到全书模式 →
  输入关键词触发并发扫描 → 底部 `ModalBottomSheet` 列出命中章节（章节标题 + N 处徽章 + 3 行摘要）→
  点击结果 `jumpToChapter` + `pendingFindAfterJump` 串联章内搜索定位（`ReaderScreen.kt:577-660`、
  `ReaderViewModel.kt:963-979`）。
- **`BookSearchEngine`**（`app/src/main/java/com/example/read/data/search/BookSearchEngine.kt`）：`@Singleton` Hilt 引擎，
  `flatMapMerge(concurrency=4)` 并发拉取章节纯文本，链式 `String.indexOf(ignoreCase=true)` 计数匹配；
  生成 `SearchResult(chapterIndex, chapterTitle, matchCount, firstMatchSnippet)` 数据类，
  摘要前后各 30 字 + 内部空白压缩 + 上下文边界省略号。
- **`BookRepository.getChapterPlainText(bookId, chapterIndex): String?`**（`BookRepository.kt:79`）：
  v5 新增接口方法，复用 `readMetadata` + `resolveHtmlFile` + `Jsoup.text()`；占位记录 / 失败时返回 null（不抛异常），
  让搜索引擎可以安全跳过单章异常继续扫描其他章节。
- **`SearchMode` 枚举**（`ReaderViewModel.kt:38`）：`InChapter` / `WholeBook` 两种搜索作用域；
  `ReaderViewModel.setSearchMode` 切换时清空查询 / 结果 / 取消 `bookSearchJob`。
- **`ReaderViewModel` 新增 4 个 StateFlow + 3 个方法**：
  - `searchMode: StateFlow<SearchMode>`（`ReaderViewModel.kt:186`）
  - `bookSearchResults: StateFlow<List<SearchResult>>`（`ReaderViewModel.kt:190`）
  - `bookSearchInProgress: StateFlow<Boolean>`（`ReaderViewModel.kt:194`）
  - `setSearchMode(mode)`（`ReaderViewModel.kt:885`）/ `searchWholeBook(query)`（`ReaderViewModel.kt:911`）/
    `onBookSearchResultClicked(result)`（`ReaderViewModel.kt:963`）
  - `pendingFindAfterJump: String?` 跨章定位机制（`ReaderViewModel.kt:205`），由 `attachFindController` 消费
    （`ReaderViewModel.kt:722-733`）。
- **`FindInPageJs.navigate(delta)` 返回 JSON**：旧版返回纯数字 currentIndex，
  v5 升级为 `JSON.stringify({index, page})`（`FindInPageJs.kt:155-179`），其中 `page` 为匹配项所在章内页码
  （由 `getBoundingClientRect().top / window.innerHeight` 计算），供 Kotlin 端联动 ViewPager2。

### Changed

- **`FindInPageController.next/prev` 回调签名升级**（`FindInPageController.kt:50/84/95`）：
  从 `(Int) -> Unit` 改为 `(NavigateResult) -> Unit`，`NavigateResult(index, pageInChapter)` 数据类。
  新增 `parseNavigateResult` 解析 evaluateJavascript 双层引用的 JSON 字符串（`FindInPageController.kt:145-168`），
  对 null / `"null"` / 旧版纯数字均有兜底，保证 v4 → v5 滚动升级期间不抛异常。
- **`ReaderViewModel.findNext` / `findPrev`**（`ReaderViewModel.kt:809-834`）：在 controller 回调中调用新增的
  `onFindMatchLocated(pageInChapter)`（`ReaderViewModel.kt:859`），把匹配项的章内页码转为全局页码并写入 `_currentGlobalPage`，
  让 ViewPager2 通过现有 `collectAsState` 观察者自动翻到匹配所在章内页 ——
  **v4 章内搜索现在能驱动 ViewPager2 翻页**（修复 v4 已知限制 3）。
- **`FindInPageBar`**（`ReaderScreen.kt:1021`）新增 `searchMode` + `onToggleMode` 参数：
  - 章内模式：显示 `x / y` 计数 + 上下导航箭头 + `MenuBook` 图标（切换到全书）
  - 全书模式：隐藏计数 / 导航，仅留输入框 + `FindInPage` 图标（切换到章内）+ 关闭
  - 输入框 placeholder 根据模式切换（"查找章内内容" / "全书搜索"）
- **`ReaderScreen` 底部弹出面板**（`ReaderScreen.kt:577-680`）：当 `findActive && searchMode==WholeBook` 时
  渲染 `WholeBookSearchResultsSheet`，含 4 种状态文案（输入不足 / 搜索中 / 无结果 / 已命中 N 章）+
  `LinearProgressIndicator` + `WholeBookSearchResultItem`（章节标题 Bold + 右侧"N 处"徽章 + 3 行摘要）。

### Fixed

- **P1-v5-1 `BookshelfViewModel.importBook` 吞 `CancellationException`**：3 处 `catch (Exception)` 之前显式
  `catch (CancellationException) { throw e }`，避免协程取消信号被吞掉破坏结构化并发：
  - `init { cleanupOrphanedBooks }`（`BookshelfViewModel.kt:72-79`）
  - `importBook` 主流程（`BookshelfViewModel.kt:159-173`），重抛前先清理 `_importProgress` 与 `_placeholderBooks` 防止
    取消瞬间残留进度环
  - `deleteBook`（`BookshelfViewModel.kt:234-236`）
- **P1-v5-2 章内搜索匹配项位于章内第 2+ 页时高亮不可见**：v4 已知限制 3 修复。JS 端 `navigate(delta)` 返回
  匹配项所在章内页码（通过 `getBoundingClientRect().top / innerHeight` 计算），Kotlin 端在 `findNext` / `findPrev`
  回调里通过 `onFindMatchLocated` 把章内页码转为全局页码写入 `_currentGlobalPage`，ReaderScreen 的
  `AndroidView.update` 观察到变化后自动 `viewPager.setCurrentItem(targetGlobal, false)`，ViewPager2
  与 WebView `scrollIntoView` 现在同步联动。`find()` 首次定位不联动翻页（避免用户输完关键词被突兀翻到章内深处）。

### Security

- **无新增**。`BookSearchEngine` 仅依赖 `BookRepository` 接口的领域方法，不触碰 WebView 或 JS 桥；
  v5 章内搜索路径继续沿用 `escapeJsString`（`JsEscaping.kt`）与正则元字符转义二次防御，
  完全解耦于 P0-3 nonce 校验机制。攻击面无新增。

### 已知限制

1. **无持久化索引**：每次搜索都全量线性扫描所有章节纯文本，长篇书（50+ 章）耗时 1-3 秒；
   下次开书的同样关键词不缓存。v6 规划引入 SQLite FTS / 离线倒排索引解决。
2. **章内搜索 `find()` 首次定位不联动翻页**：有意为之 —— 用户刚输完关键词还在第 1 页，
   立刻跳到章内深处会突兀；按"下一个"才驱动 ViewPager2 翻页。
3. **跨章跳转后 `attachFindController` 仅 `find(query)`**：不主动调 `findNext()`，若关键词位于新章节深处，
   首次进入仍停在第 1 页，用户需按"下一个"才能翻到。下一轮可优化。
4. **`SearchMode` 与 `exitFindMode` 的交互**：`exitFindMode` 不重置 `_searchMode`，
   切到全书模式 → 关闭搜索 → 再次进入仍是 WholeBook 模式（语义不一致）。下一轮统一。
5. **跨内联元素匹配**：章内搜索沿用 v4 TreeWalker 限制（`a<em>b</em>c` 不能匹配 "abc"）；
   全书搜索因走 `Jsoup.text()` 提取纯文本反而能匹配跨内联，是顺带的工程意外收获。

### 验证清单

```bash
# 单元测试
./gradlew :app:testDebugUnitTest

# 手动验证 v5 跨章节全书搜索
# 1. 打开一本 30+ 章的 EPUB，进入阅读器
# 2. 点击顶栏右侧 🔍 按钮 → 顶栏切换为搜索栏（章内模式）
# 3. 点击右侧 MenuBook 图标 → 切换到全书模式，输入框 placeholder 变为"全书搜索"
# 4. 输入常见关键词（如 "the" / "他"）→ 底部弹出面板显示进度条 + 结果列表
# 5. 点击某条结果 → 跳转到对应章节并自动定位关键词（黄色高亮）
# 6. 按"下一个" → ViewPager2 自动翻到匹配所在章内页（v4 修复）

# 手动验证 P1-v5-1 协程取消
# 1. 导入大文件（>50MB），导入到 30% 时返回书架（触发 ViewModel 销毁）
# 2. logcat 应该看不到"导入 EPUB 失败"的 e 级日志（CancellationException 已重抛）

# 手动验证 P1-v5-2 ViewPager2 联动
# 1. 打开一本章节较长的 EPUB（单章 > 5 页）
# 2. 进入章内搜索，输入只在章节深处出现的关键词
# 3. 按"下一个"→ ViewPager2 翻到匹配所在章内页，高亮可见（不再停在第 1 页）
```

---

## [Unreleased] - 2026-05-24 (v4 章节内搜索)

> 在 Patch v3 收尾完成之后，沿 feat 主线推进 v4：为阅读器引入**章节内搜索**（find-in-page）能力。
> 用户在阅读器顶栏点击搜索按钮，顶栏即切换为搜索栏（输入框 + 匹配计数 + 上下导航 + 关闭按钮），
> 输入关键词后实时高亮当前章节中所有匹配项（黄色），当前选中项使用更醒目的橙色背景并自动滚动可见。
> 设计与既有 `AndroidBridge` nonce 安全模型解耦：纯 `evaluateJavascript` + `ValueCallback`，
> 无新增 JS 桥，无新增依赖，攻击面无新增。
> 详细对照表参见 [FEAT_REPORT_v4.md](FEAT_REPORT_v4.md)。

### Added

- **章节内搜索功能**：顶栏 🔍 → 搜索栏（输入框 + `x / y` 计数 + 上下导航 + 关闭）→ 章内全部匹配项黄色高亮、当前项橙色 + `scrollIntoView`。仅作用于当前章节，跨章节自动退出（与 v5 全书搜索清晰隔离）。
- **`FindInPageController`**（`app/src/main/java/com/example/read/ui/reader/FindInPageController.kt`）：Kotlin 控制器，持有当前章节 WebView 引用，通过 `evaluateJavascript` 调用 `window.ReaderFind.{find,next,prev,clear}` 并把 JS 返回的字符串数字解析为 `Int`。
- **`FindInPageJs`**（`app/src/main/java/com/example/read/ui/reader/FindInPageJs.kt`）：包含 `FIND_IN_PAGE_JS` 注入脚本（IIFE 封装 + TreeWalker + `<mark class="reader-find">` 重写文本节点 + 正则元字符转义）和 `FIND_IN_PAGE_CSS` 高亮样式常量。
- **`JsEscaping`**（`app/src/main/java/com/example/read/ui/reader/JsEscaping.kt`）：reader 包内的 internal 顶层 `escapeJsString(s: String): String`，委托到 `WebViewPaginator.escapeJsString`，避免重复实现并保持单点责任。
- **`PlaceholderBookCard`**（`app/src/main/java/com/example/read/ui/components/PlaceholderBookCard.kt`，v3 引入此处再次列出便于检索）：导入中占位卡片组件，已在 v3 收尾中落地（详见 v3 段）。
- **`BookshelfViewModel.placeholderBooks: StateFlow<List<PlaceholderBook>>`**：导入过程中占位卡片列表（v3 引入此处再次列出便于检索）。
- **`ReaderViewModel` 新增 4 个 StateFlow**：
  - `findActive: StateFlow<Boolean>`（搜索栏是否激活，`ReaderViewModel.kt:137`）
  - `findQuery: StateFlow<String>`（用户输入的关键词，`ReaderViewModel.kt:141`）
  - `findCount: StateFlow<Int>`（当前章节内匹配总数，`ReaderViewModel.kt:145`）
  - `findCurrent: StateFlow<Int>`（当前选中匹配项索引，-1 表无匹配，`ReaderViewModel.kt:149`）
- **`ReaderViewModel` 新增 6 个方法**：`attachFindController`（`ReaderViewModel.kt:662`，注入当前章节 WebView 的控制器并自动重发查询）、`enterFindMode`（`ReaderViewModel.kt:681`）、`exitFindMode`（`ReaderViewModel.kt:694`，清空 4 个 StateFlow 并调用 `controller.clear()`）、`updateFindQuery`（`ReaderViewModel.kt:715`）、`findNext`（`ReaderViewModel.kt:745`）、`findPrev`（`ReaderViewModel.kt:756`）。

### Changed

- **`BookshelfScreen` 网格分两段渲染**（v3 引入，此处保留以便对照）：`placeholderBooks` 在前、`books` 在后；空状态判定改为 `books.isEmpty() && placeholderBooks.isEmpty()`。
- **`ChapterWebViewFactory.onPageFinished` 自动注入 `FIND_IN_PAGE_JS`**（`ChapterWebViewFactory.kt:198-202`）：在 `PAGINATION_JS`（含 nonce）注入之后、`updateCSS(escapedCss)` 之前注入；顺序无强约束，但放在 `updateCSS` 之前更直观（先准备搜索能力，再应用样式）。
- **`ReadingSettings.toReaderCss()` 末尾拼接 `FindInPageJs.FIND_IN_PAGE_CSS`**（`ReadingSettings.kt:134`）：让 `mark.reader-find` / `mark.reader-find-current` 高亮样式随每次 `updateCSS` 同步注入，主题切换（白 / 护眼 / 暗黑）后高亮颜色仍生效。
- **`ReaderViewModel.jumpToChapter` 未预加载分支不再立刻设 `_currentGlobalPage.value = 0`**（v3 引入，此处保留以便对照，`ReaderViewModel.kt:429-433`）：改走 `pendingPositionRestore = true; pendingPageInChapter = 0` 状态机，等 `onPageCountReady` 把页码恢复到目标章节首页。
- **`ReaderViewModel` 在 3 处自动调用 `exitFindMode()`**：
  - `syncChapterState`（`ReaderViewModel.kt:307`，跨章节滑动）
  - `jumpToChapter` 未预加载分支（`ReaderViewModel.kt:426`，目录 / 上下章按钮跳转）
  - `updateSettings`（`ReaderViewModel.kt:547`，字号 / 行高 / 字体 / 背景变更，WebView 会被重建）
- **`ReaderScreen` 顶栏二分支渲染**（`ReaderScreen.kt:178-220`）：根据 `findActive` 切换到 `FindInPageBar` Composable（`ReaderScreen.kt:843`），正常顶栏新增 `Icons.Default.Search` 按钮；`onPageSelected` 与 `onChapterHtmlLoaded` 中把当前章节 WebView 包装为 `FindInPageController` 调用 `viewModel.attachFindController(...)`。

### Fixed

- **P1-NEW-1 书架导入进度环 UI 完全消失（功能退化）**（v3 引入，此处保留以便对照）：上一轮 P1-1 在 `BookDao.getAllBooks()` SQL 层过滤 `PREPARING_*` 后，`LazyVerticalGrid(items = books)` 不再为占位记录渲染 `BookCard`。修复后 `BookshelfViewModel` 暴露独立 `placeholderBooks: StateFlow<List<PlaceholderBook>>`，`BookshelfScreen` 通过 `PlaceholderBookCard` 在网格顶部渲染占位条目；同时保留 P1-1 的"幽灵记录不残留"收益。
- **P1-NEW-2 `jumpToChapter` 未预加载分支 `_currentGlobalPage = 0` 错位**（v3 引入，此处保留以便对照）：旧实现写 0 时 globalPages 仍保留旧章节的页，UI 短暂闪回旧章节首页。修复后采用 `pendingPositionRestore + pendingPageInChapter = 0` 状态机，由 `onPageCountReady` 恢复页码。

### Security

- **无新增**。v4 章节内搜索复用既有 `escapeJsString` 转义路径（`WebViewPaginator.escapeJsString` → `JsEscaping.escapeJsString`），JS 端额外做正则元字符转义（`query.replace(/[.*+?^${...}]/g, '\\$&')`）二次防御；FindInPage 仅通过 `evaluateJavascript` 的 `ValueCallback` 取结果，不调用 `addJavascriptInterface`，与 nonce 校验机制完全解耦，攻击面无新增。

### 已知限制

1. **仅章内**：跨章节滑动 / 跳转目录会自动清除高亮并退出搜索模式；全书搜索留待 v5。
2. **不持久化**：搜索关键词、匹配数仅存活于当前 ViewModel 生命周期。
3. **分页位置不跟随匹配**：JS 端 `scrollIntoView` 让 mark 进入视窗，但当前实现下 ViewPager2 的全局页码与匹配项的章内位置不联动；如果匹配项位于章节后段（章内页码 > 当前页），高亮虽然被滚动到视窗中央，但 ViewPager2 仍停留在当前页。
4. **不支持正则 / 大小写选项**：当前固定大小写不敏感、按字面量匹配。
5. **跨内联元素文本不匹配**：TreeWalker 按文本节点遍历，`a<em>b</em>c` 这种被内联元素切断的文本无法作为一个整体匹配（DOM 层这是三个独立文本节点）。
6. **设置变更时高亮丢失**：用户在搜索期间调字号会触发 WebView 重建，搜索状态被同步重置，需要重新输入（这是有意为之）。

### 验证清单

```bash
# 单元测试：现有用例不破坏；v4 新增测试由 TDD 智能体补齐（参见 TDD_REPORT_v4.md）
./gradlew :app:testDebugUnitTest

# 手动验证 v4 章节内搜索
# 1. 打开一本中型 EPUB，进入阅读器
# 2. 点击顶栏右侧 🔍 按钮 → 顶栏切换为搜索栏
# 3. 输入常见关键词（如 "the" / "他"）→ 应看到黄色高亮和 "1 / N" 计数
# 4. 点击上下箭头 → 橙色高亮跟随循环
# 5. 切换到下一章 → 搜索栏自动关闭，高亮清除
# 6. 切换字号 → 搜索栏自动关闭
# 7. 输入特殊字符（' " \ < ）→ 不应崩溃或注入
```

---

## [Unreleased] - 2026-05-24 (Patch v3 收尾)

> 针对 `REVIEW_REPORT_v4.md` § 3 中两条 P1 新发现做收尾修复：
> 一是上一轮 P1-1 引入的"书架导入进度环 UI 消失"功能退化（P1-NEW-1），
> 二是 `jumpToChapter` 未预加载分支的 `_currentGlobalPage = 0` 错位（P1-NEW-2，
> 历史 bug 被上一轮 P1-3 放大了复现路径）。
> 详细对照表参见 [FIX_REPORT_v3.md](FIX_REPORT_v3.md)。

### Added

- **`PlaceholderBook` 数据类**（`app/src/main/java/com/example/read/ui/bookshelf/BookshelfViewModel.kt`）：导入中占位卡片的轻量内存模型（`id` / `titleHint` / `progress`），仅在导入过程中存在，不与 Room 持久化关联。
- **`BookshelfViewModel.placeholderBooks: StateFlow<List<PlaceholderBook>>`**：与 `importProgress` 同步维护的"占位卡片"列表，作为 ViewModel → UI 的独立通道。`prepareImport` 完成后追加，进度回调时更新，导入结束（成功 / 失败）从中移除。
- **`PlaceholderBookCard` Composable**（`app/src/main/java/com/example/read/ui/components/PlaceholderBookCard.kt`）：书架"导入中"占位卡片组件，与 `BookCard` 视觉一致（2:3 封面 + 圆角 + 标题位），但不可点击，仅展示半透明遮罩 + `CircularProgressIndicator` + 百分比 + "导入中"文字提示。

### Changed

- **`BookshelfScreen` 网格渲染分两段**（`app/src/main/java/com/example/read/ui/bookshelf/BookshelfScreen.kt`）：先渲染 `placeholderBooks`（占位卡片排在最前），再渲染 `books`（真实书籍）。`items` 的 key 使用 `"placeholder_${it.id}"` 前缀避免与真实 book id 冲突。空状态判定改为 `books.isEmpty() && placeholderBooks.isEmpty()`（原先是 `books.isEmpty() && importProgress.isEmpty()`）。
- **`ReaderViewModel.jumpToChapter` 未预加载分支**（`app/src/main/java/com/example/read/ui/reader/ReaderViewModel.kt`）：不再立即写 `_currentGlobalPage.value = 0`（这会把 UI 错误地带回旧 globalPages[0]，即旧章节第一页）；改为标记 `pendingPositionRestore = true; pendingPageInChapter = 0`，等目标章节的 `onPageCountReady` 回调到达并重建 globalPages 后，由位置恢复机制把 `_currentGlobalPage` 设到新章节首页的全局索引。与 `updateSettings` 的状态机契约一致。

### Fixed

- **P1-NEW-1 书架导入进度环 UI 完全消失（功能退化）**：上一轮 P1-1 在 `BookDao.getAllBooks()` SQL 层过滤 `PREPARING_*` 后，`LazyVerticalGrid(items = books)` 不再为占位记录渲染 `BookCard`，整段导入过程书架完全静默。修复后 `BookshelfViewModel` 暴露独立的 `placeholderBooks` StateFlow，`BookshelfScreen` 通过 `PlaceholderBookCard` 渲染占位条目，用户从触发 SAF 选择文件到 `startImport` 完成的整个时间段都能看到进度环和百分比；同时保留 P1-1 的"幽灵记录不在书架残留"收益。
- **P1-NEW-2 `jumpToChapter` 未预加载分支 `_currentGlobalPage = 0` 错位**：旧实现写 0 时 globalPages 仍保留旧章节的页，UI 短暂跳到旧章节第一页；新章节加载完毕后 `currentGlobalPage` 没有重新定位，用户被错误地停在旧章节首页。该 bug 在上一轮 P1-3 把 `nextChapter` / `previousChapter` 接到 `jumpToChapter` 兜底分支后复现路径显著增多。修复后采用与 `updateSettings` 一致的 `pendingPositionRestore` + `pendingPageInChapter = 0` 状态机，由 `onPageCountReady` 把页码恢复到目标章节首页。

### Security

- 无新增。本轮不涉及 WebView 配置、JS 桥、ProGuard 规则或 Schema 变更。

### 验证清单

```bash
# 单元测试：现有 BookshelfViewModelImportTest / ReaderViewModelBoundaryTest / ReaderViewModelExtraTest 不破坏
./gradlew :app:testDebugUnitTest

# 手动验证 P1-NEW-1：导入大文件可见进度环
# 1. 从 SAF 选择一个较大的 EPUB（>10MB）
# 2. 选中后立刻观察书架：网格中应立即出现一张半透明占位卡片，
#    标题区显示"正在导入…"，封面区显示进度环 + 百分比
# 3. startImport 完成后，占位卡片消失，真实书籍卡片出现在网格中

# 手动验证 P1-NEW-2：未预加载切章不闪回首章
# 1. 打开一本 30+ 章的 EPUB（不要等待预加载完成）
# 2. 立刻点击底栏"下一章" → 章节标题切换为下一章，ViewPager2 不闪回第一章首页
# 3. WebView 加载完成后，页面停在下一章第一页（而非滚动到旧 globalPages[0]）
```

---

## [Unreleased] - 2026-05-24 (Patch P1)

> 在同日 P0/B 修复批次之后，针对 `REVIEW_REPORT_v3.md` § 3 中的三条 P1 新发现进行集中修复：占位记录幽灵残留、阅读器对未就绪路径开屏崩溃、底栏"上一章/下一章"按钮在未预加载时静默 no-op。
> 详细对照表参见 [FIX_REPORT_v2.md](FIX_REPORT_v2.md)。

### Added

- **`BookDao.getAllBooksIncludingPreparing()`**（`app/src/main/java/com/example/read/data/local/dao/BookDao.kt:46`）：返回全量 `Flow<List<BookEntity>>`（含 `PREPARING_` 占位记录），按 `lastReadAt DESC` 排序。**仅供 `cleanupOrphanedBooks` 使用**，UI 路径继续走过滤后的 `getAllBooks()`。
- **`BookRepositoryImpl.PREPARING_GHOST_THRESHOLD_MS = 3600000L`** 常量（`app/src/main/java/com/example/read/data/repository/BookRepositoryImpl.kt:311`）：占位记录"幽灵阈值"（1 小时）。超过该时长仍未完成第二阶段导入的 `PREPARING_` 记录视为进程崩溃残留，启动清理时主动删除。

### Changed

- **`BookDao.getAllBooks()` SQL 层过滤 `PREPARING_%` 占位记录**（`app/src/main/java/com/example/read/data/local/dao/BookDao.kt:38`）：UI 订阅的 Flow 不再收到 `PREPARING_` 中间态记录，根除"导入瞬间闪占位卡片"和"崩溃后永久幽灵书"两条 UX 路径。
- **`BookRepositoryImpl.cleanupOrphanedBooks` 改走 `getAllBooksIncludingPreparing()`**（`app/src/main/java/com/example/read/data/repository/BookRepositoryImpl.kt:330-348`）：扫描全量记录，对 `lastReadAt` 距今 ≥ `PREPARING_GHOST_THRESHOLD_MS` 的 `PREPARING_` 记录主动 `deleteBook`；活跃导入（< 1h）跳过；空字符串记录沿用 P0-5 的删除逻辑。
- **`ReaderViewModel.nextChapter` / `previousChapter` 加 `jumpToChapter(±1)` 兜底**（`app/src/main/java/com/example/read/ui/reader/ReaderViewModel.kt:295-331`）：当 `globalPages` 中未命中目标章节首页（预加载滞后），不再静默 no-op，转走 `jumpToChapter` 触发章节状态切换 + HTML 重新加载。
- **`PagedChapterAdapter.getOrCreateWebView` 包裹 try-catch 兜底**（`app/src/main/java/com/example/read/ui/reader/ReaderScreen.kt:686-723`）：捕获 `loadHtml` 抛出的 `IllegalArgumentException`（如未来 `WebViewAssetLoader` 对其他路径校验失败）或其他 `Throwable`，统一 fallback 到 `webView.loadUrl("about:blank")` + `onPageCountReady(chapterIndex, 1)`，不让阅读器主线程崩溃。
- **`ChapterWebViewFactory.loadHtml` 入口短路**（`app/src/main/java/com/example/read/ui/reader/ChapterWebViewFactory.kt:121-157`）：检测到 `bookDirPath` 为空字符串、`PREPARING_` 前缀或目录不存在时，直接 `Log.w` + `onPageCountReady(1)` 返回；不构造 `WebViewAssetLoader.InternalStoragePathHandler`，绕开其内部目录校验的 IAE。

### Fixed

- **P1-1 占位记录幽灵书残留**：进程在 `startImport` 中途被杀后，`PREPARING_{uuid}` 记录永久停留在书架。修复后启动期 `cleanupOrphanedBooks` 在 1 小时阈值后清理；UI 路径同时通过 SQL `NOT LIKE 'PREPARING_%'` 过滤。
- **P1-2 阅读器对 `PREPARING_*` 或空 `bookDirPath` 开屏崩溃**：`WebViewAssetLoader.InternalStoragePathHandler` 构造期校验 directory 必须在 `context.getDataDir()` 子目录内，对 `PREPARING_xxx` 或空串抛 `IllegalArgumentException` 直接传到主线程。修复后通过 `loadHtml` 入口短路 + adapter try-catch 双重防御。
- **P1-3 底栏"下一章 / 上一章"按钮在未预加载时看似失灵**：原逻辑仅在 `globalPages` 中查找目标章节首页，未命中即 `return`。修复后未命中时复用 `jumpToChapter(±1)`，按钮在快速点击或预加载滞后场景下始终有响应。

### Security

- 无新增。本轮不涉及 WebView 配置、JS 桥或 ProGuard 规则变更。

### 验证清单

```bash
# 单元测试（含 BookRepositoryImplCleanupTest 新增 2 个用例覆盖 P1-1 幽灵清理）
./gradlew :app:testDebugUnitTest --tests "com.example.read.data.repository.BookRepositoryImplCleanupTest"

# 手动验证 P1-1：模拟"导入中被杀"
# 1. 启动 app 导入一本 EPUB，进度环还在转时强制结束进程
# 2. 临时把 PREPARING_GHOST_THRESHOLD_MS 调小到 10 秒，重启 app
# 3. 观察 10 秒后幽灵占位卡片自动消失

# 手动验证 P1-2：异常 bookDirPath
# 1. adb shell rm -rf /data/data/com.example.read/files/books/1
# 2. 从书架点击 id=1 的书 → 应看到空白页而非崩溃，logcat 含 W/ChapterWebViewFactory 标签

# 手动验证 P1-3：未预加载时点下一章
# 1. 打开一本 30+ 章的 EPUB
# 2. 立即（不等预加载完成）连续点击底栏"下一章" → 章节应正常推进
```

---

## [Unreleased] - 2026-05-24

> 本次发布聚焦三条主线：**release 构建可用性**（P0-1）、**WebView 内存安全**（P0-2 / B6）、**WebView 安全模型**（P0-3 / P0-4），并附带 6 个生产 bug 修复。
> 详细对照表参见 [FIX_REPORT.md](FIX_REPORT.md)；技术背景参见 [REVIEW_REPORT_v2.md](REVIEW_REPORT_v2.md) 与 [TDD_REPORT.md](TDD_REPORT.md)。

### Added

- `androidx.webkit:webkit:1.12.1` 依赖（提供 `WebViewAssetLoader`，把 EPUB 解包目录代理到 `https://appassets.androidplatform.net/`）。
- `WebViewLruCache`（`ReaderScreen.kt:518`，容量 = 3）：基于 `LinkedHashMap(accessOrder=true)` 的 LRU，淘汰时按"解绑 JS 桥 → `loadUrl("about:blank")` → 摘除父布局 → `destroy()`"顺序释放原生资源。
- `app/proguard-rules.pro`：从仅有 Jsoup 几行重写为覆盖 kotlinx.serialization / Room / Hilt / `@JavascriptInterface` / Navigation 路由 `@Serializable` / Coil 3 的完整 keep 规则。
- `BookEntity.bookDirPath` 列的 `PREPARING_{uuid}` 前缀作为导入状态标记（同一列、无 schema 变化）。
- `WebViewPaginator.PAGINATION_JS_TEMPLATE`：含 `__NONCE__` 占位符的 JS 模板，配合 `newNonce()` 与 `escapeJsString()` 实现安全注入。

### Changed

- **WebView 加载方式**：从 `loadDataWithBaseURL(html, baseUrl = "file://...")` + `setAllowFileAccess(true)` 切换到 `WebViewAssetLoader` + `https://appassets.androidplatform.net/epub/`。
- **`AndroidBridge` 加 nonce 校验**：`PaginationBridge.onPageCountReady(nonce, count)` 比对构造时绑定的 `expectedNonce`，不匹配静默丢弃；防止 EPUB 内嵌恶意脚本伪造回调（`WebViewPaginator.kt:184-191`）。
- **`WebViewPaginator.escapeJsString`**：基于 `org.json.JSONObject.quote` 生成 JSON 字面量，再额外转义 U+2028 / U+2029 / `</` 序列；`ChapterWebViewFactory.loadHtml` / `updateCSS` 改用该函数注入 CSS。
- **`cleanupOrphanedBooks`**：跳过 `bookDirPath` 以 `PREPARING_` 开头的记录，只删除空字符串记录；不再依赖"是否存在 `temp_` 目录"这一脆弱条件（`BookRepositoryImpl.kt:317`）。
- **`preloadChapterPageCount`**：移到 `Dispatchers.IO` 读取 HTML 后再切回 `Dispatchers.Main` 调 `loadHtml`；新增 5 秒超时 `Runnable` 与 `disposed` 标志，到期强制 `destroy()` WebView 防止泄漏（`ReaderViewModel.kt:405-446`）。
- **`pagePrefs.apply()` 加 100ms debounce**：连续翻页时把写入存到内存 `pendingPageWrites`，通过 `savePageJob` 延迟 100ms batch apply；`onCleared()` 强制 flush（`ReaderViewModel.kt:486-510`）。
- **`ReaderViewModel.nextChapter` / `previousChapter`**：显式上下界校验，`nextChapter` 中 `currentChapter + 1 >= spineSize → return`，避免预加载异常时 `globalPages` 出现越界章节。
- **`updateSettings`**：清空 `chapterPageCounts` / `globalPages` 时同步 `_currentGlobalPage.value = 0`，避免重新分页期间 `currentGlobalPage` 指向陈旧索引（`ReaderViewModel.kt:456`）。
- **`EpubParser.normalizeNcxHref`**：用 `java.nio.file.Paths.get(joined).normalize()` 处理 `..` / `.`，并把 Windows 反斜杠归一为正斜杠；`opfDir == resolved` 边界返回空字符串。
- **`BookRepositoryImpl.deleteBook`**：用 try-catch 包裹 `deleteRecursively()` / `delete()`，失败时 `Log.w` 不上抛，最终仍执行 `bookDao.deleteBook` 防止幽灵记录。
- **`BookshelfViewModel.importBook`**：把 `placeholderId` 提到外部变量，catch 中通过 `repository.getBookById(...)` + `deleteBook` 幂等兜底清理占位记录。
- **`ChapterWebViewFactory`**：构造参数与 `preloadChapterPageCount` 内部统一使用 `applicationContext`，避免 Activity Context 泄漏。

### Security

- **关闭 `WebView.setAllowFileAccess(true)`** 及 `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` / `allowContentAccess`，阻止 EPUB 内联 JS 读取应用沙盒私有文件（如 `read.db`、SharedPreferences XML）。
- **`mixedContentMode = NEVER_ALLOW`**：禁止 https 文档加载 http 资源。
- **`shouldInterceptRequest` 仅放行 `appassets.androidplatform.net` host**，其他外链一律返回空响应；`shouldOverrideUrlLoading` 拒绝跨域导航。
- **`updateCSS` 转义覆盖 U+2028 / U+2029 / `</style>` / `</script>`**：杜绝 CSS 字符串截断 JS 字面量、或未来 `<script>` 字面量注入路径下的 XSS 风险。
- **`AndroidBridge` nonce 校验**：见上方 Changed 段；同时在每次 `loadHtml` 前 `removeJavascriptInterface("AndroidBridge")` 防止闭包累积持有 ViewModel 引用。

### Fixed

- **5 个 P0**：
  - P0-1 ProGuard 规则缺失导致 release 构建启动崩溃
  - P0-2 WebView 缓存只增不减 + 持有 Activity Context 导致 OOM / 泄漏
  - P0-3 不安全的 WebView 配置 + JS 桥来源未校验 → 应用私有文件读取风险
  - P0-4 `updateCSS` JS 注入转义不完整（U+2028 / U+2029 / `</` 序列）
  - P0-5 `cleanupOrphanedBooks` 与活跃 import 竞态导致占位记录被误删
- **6 个生产 bug**（B1 / B2 / B3 / B5 / B6 / B8）：详见 [TDD_REPORT.md](TDD_REPORT.md) 第三节"测试过程中发现的潜在生产 bug"。

### Deferred

下列项按任务约束推迟到下一轮：

- 未动 Room schema（B6 / P1-6 的 `state: String` 列方案）。
- 未重构 `BookRepository` 接口（P1-5 领域纯净），UI 层仍以 `Uri` / `Context` 调入。
- 未做 `PagedChapterAdapter` 的 `DiffUtil` 化（P0-5 报告中的另一条支线）。
- 未替换 `collectAsState` → `collectAsStateWithLifecycle`（P1-9）。
- 未把 `OnTouchListener` 升级为 `GestureDetector` + 边缘排除（P1-12）。
- 未引入 Robolectric / Compose UI Test 等新测试依赖；新增测试待下一轮。

### 验证清单

```bash
# 单元测试
./gradlew :app:test

# release 冒烟（主要为验证 ProGuard 规则）
./gradlew :app:assembleRelease
./gradlew :app:installRelease
# 手动验证：导入一本中型 EPUB（约 30 章）→ 顺序翻页 → 调字号 →
# 切到末章 → 验证内存占用稳定（adb shell dumpsys meminfo com.example.read）
```

---

## 历史版本

本 CHANGELOG 自 2026-05-24 引入。在此之前的提交历史请参阅 `git log` 与 [REVIEW_REPORT.md](REVIEW_REPORT.md)。
