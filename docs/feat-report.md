# 功能开发报告（合并版）

> 本文件合并了项目历次功能开发报告（v4-v6）的核心内容。
> 记录了从章内搜索到跨章节全书搜索的功能迭代过程。

---

## 版本演进

| 版本 | 日期 | 功能 | 新增文件数 |
|------|------|------|-----------|
| v4 | 2026/05/24 | 章节内搜索（Find-in-Page） | 3 个新文件 + 4 个修改 |
| v5 | 2026/05/24 | CancellationException 修复 + ViewPager2 联动 + 跨章节全书搜索 | 2 个新文件 + 8 个修改 |
| v6 | 2026/05/24 | P1-v6-1/P1-v6-2 修复 + 协程清理 + 书签功能（Schema v3） | 待定 |

---

# 功能报告 v4：章节内搜索

## 功能概述

v4 为 EPUB 阅读器引入**章节内搜索**能力。用户在阅读器顶栏点击搜索按钮，顶栏切换为搜索栏：内置关键词输入框、匹配计数（"x / y"）、上一项/下一项导航按钮、关闭按钮。

**关键设计点**：

- **章内 only**：搜索仅作用于当前章节，跨章节自动退出搜索模式
- **无 AndroidBridge 依赖**：纯 `evaluateJavascript` + `ValueCallback`，与 P0-3 nonce 安全模型完全解耦
- **零新增依赖**：复用已有的 androidx.compose / androidx.webkit / org.json
- **完整中文注释**：符合 CLAUDE.md 规范

## 文件清单

### 新增

| 文件 | 职责 |
|------|------|
| `JsEscaping.kt` | escapeJsString 委托，让 reader 包内新组件共享转义实现 |
| `FindInPageJs.kt` | FIND_IN_PAGE_JS 注入脚本 + FIND_IN_PAGE_CSS 高亮样式 |
| `FindInPageController.kt` | Kotlin 控制器：持有 WebView 引用，通过 evaluateJavascript 调用 JS API |

### 修改

| 文件 | 变更 |
|------|------|
| `ReaderViewModel.kt` | 新增 4 个 find 状态 StateFlow + enterFindMode/exitFindMode/updateFindQuery/findNext/findPrev |
| `ReaderScreen.kt` | 搜索栏 UI + FindInPageController 注入/销毁 + onChapterHtmlLoaded 挂载 |
| `ChapterWebViewFactory.kt` | onPageFinished 后注入 FIND_IN_PAGE_JS + FIND_IN_PAGE_CSS |
| `WebViewPaginator.kt` | 无改动（仅引用 escapeJsString） |

## JS 注入安全分析

- `escapeJsString` 返回带外层双引号的字面量，拼接成 `ReaderFind.find("已转义内容")`
- JS 端二次防御：query.replace 正则元字符
- U+2028/U+2029/`</` 已由 escapeJsString 覆盖
- 攻击面增量：零。不引入 addJavascriptInterface

## 已知限制

1. **跨内联元素匹配**：`a<em>b</em>c` 搜 "abc" 无法匹配（TreeWalker 按 textNode 遍历的固有限制）
2. **scrollIntoView 不联动 ViewPager2 分页**：后续在 v5 中修复
3. **无 debounce**：长章节短查询可能卡顿（后续优化）

---

# 功能报告 v5：跨章节全书搜索 + 修复

**实施日期**：2026/05/24
**实施范围**：三件套 -- 协程取消修复 + ViewPager2 联动 + 跨章节搜索

## 任务一：CancellationException 重抛

`BookshelfViewModel.kt` 中 3 处 catch 加 `catch (e: CancellationException) { throw e }`：

| 位置 | 处理 |
|------|------|
| `init { cleanupOrphanedBooks }` | 不写 errorMessage，不崩溃 |
| `importBook(...)` 主流程 | 先清理占位再 throw e，"取消应当像没发生过一样" |
| `deleteBook(book)` | 不映射为 "删除失败" |

## 任务二：搜索 ViewPager2 联动

**问题**：v4 章内搜索 JS 端 `scrollIntoView` 只滚 WebView 内部，匹配项在章内第 2+ 页时高亮存在但用户看不见。

**修复方案**：

JS 端 `navigate(delta)` 改返回 JSON `{index, page}`：
```javascript
var rect = target.getBoundingClientRect();
var absoluteTop = rect.top + (window.scrollY || ...);
var viewportH = window.innerHeight;
var page = Math.floor(absoluteTop / viewportH);
return JSON.stringify({index: currentIndex, page: page});
```

Kotlin 端解析后驱动 ViewPager2：
```kotlin
data class NavigateResult(val index: Int, val pageInChapter: Int)
```

`findNext`/`findPrev` 调 JS `navigate` -> 解析 JSON -> 写 `_findCurrent.value = result.index` + 调 `onFindMatchLocated(result.pageInChapter)` -> ViewModel 写 `_currentGlobalPage` -> ViewPager2 翻页。

## 任务三：跨章节全书搜索

### 新增组件

| 文件 | 职责 |
|------|------|
| `BookSearchEngine.kt` | 全书搜索引擎：并发扫描章节、纯文本匹配、摘要片段提取 |
| `SearchMode` 枚举 | InChapter / WholeBook，控制搜索范围 |
| `WholeBookSearchResultsSheet` | ModalBottomSheet 展示全书搜索结果 |

### 设计要点

- **并发模型**：`indices.asFlow().flatMapMerge(concurrency = 4)` 并发扫描章节
- **取消传播**：结构化，外层 cancel 传播到所有子 flow
- **纯 Kotlin 字符串扫描**：`BookSearchEngine` 内完全不调 JS，用 `String.indexOf` 匹配
- **Hilt 注入**：`@Singleton @Inject constructor(private val repository: BookRepository)`
- **最小查询长度**：2 字符（硬编码，建议提取常量）
- **摘要片段**：Jsoup.text() 提取纯文本 + 前后省略号

### 搜索结果点击跳转流程

1. 用户点击全书结果 -> `onBookSearchResultClicked(result)`
2. 保存 `pendingFindAfterJump = query`
3. 切搜索模式为 InChapter
4. 调 `jumpToChapter(result.chapterIndex)`
5. 新章节加载完毕 -> `attachFindController` 消费 `pendingFindAfterJump`
6. 调 `find(query)` 高亮匹配项

---

# 功能报告 v6：P1 修复 + 协程清理 + 书签功能

**实施日期**：2026/05/24
**实施范围**：v6 四件套

## 任务一：P1-v6-2 修复 -- FindInPageController.find 升级到 NavigateResult

**问题**：v5 的 `find()` 只返回匹配总数，不返回 pageInChapter，导致跨章跳转后用户停在新章节第 1 页。

**修复**：JS 端 `find(query)` 返回从纯数字升级为 JSON `{"count":c,"index":i,"page":p}`。

新增数据类：
```kotlin
data class FindResult(val count: Int, val index: Int, val pageInChapter: Int)
```

## 任务二：P1-v6-1 修复 -- onBookSearchResultClicked 状态机

**问题**：直接 `_searchMode.value = InChapter` 绕过 `setSearchMode()`，旧的全书搜索 Job 不会被取消。

**修复**：抽 `internalSetSearchMode(mode, preserveQuery)` 内部方法，统一管理 mode 切换 + 副作用。

## 任务三：协程取消清理

仓库范围内 12+ 处 `catch (Exception)` 仍未处理 CancellationException 重抛。本轮在 BookSearchEngine 和 ReaderViewModel 的新增代码中已正确处理。

## 任务四：书签功能（Schema v3）

引入 Room schema v3 迁移，新增书签表。详情待补充。

---

## 跨版本遗留问题追踪

| 问题 | 首次报告 | 当前状态 |
|------|----------|----------|
| BookRepository 接口耦合 Uri/Context | v1 M1 | 六轮连续遗留 |
| notifyDataSetChanged 破坏 ViewPager2 位置稳定 | v2 P0-5 | 遗留（需 DiffUtil 化） |
| collectAsState -> collectAsStateWithLifecycle | v1 M4 | 遗留 |
| WebView 触摸事件与系统手势冲突 | v2 P1-12 | 遗留 |
| 配置变更时 WebView 全量销毁重建 | v1 M4 | 遗留 |
