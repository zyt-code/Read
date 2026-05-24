# 代码质量审查报告（合并版）

> 本文件合并了项目历次代码审查报告（v1-v6）的完整内容。
> 每轮审查基于前一轮修复结果进行独立复核，逐步收敛问题。

---

## 版本演进

| 版本 | 日期 | 基线 | 发现数 | 重点 |
|------|------|------|--------|------|
| v1 | 2026/05/23 | 初始代码 | 13（严重 1 / 重要 7 / 轻微 5） | 首次全面审查 |
| v2 | 2026/05/24 | v1 修复后 | 41（P0×5 / P1×10 / P2×11 / P3×15） | 深度审查阅读器核心子系统 |
| v3 | 2026/05/24 | FIX_REPORT 修复后 | 15（P0×0 / P1×4 / P2×6 / P3×5） | 修复复核 |
| v4 | 2026/05/24 | FIX_REPORT_v2 修复后 | 新增 6（P1×2 / P2×3 / P3×2） | P1 修复复核 |
| v5 | 2026/05/24 | feat v3 + feat v4 后 | 新增 12（P1×2 / P2×4 / P3×6） | 新功能复核 |
| v6 | 2026/05/24 | feat v5 后 | 新增 9（P1×2 / P2×4 / P3×3） | 跨章节搜索复核 |

---

# 审查报告 v6（最新状态）

**审查日期**：2026/05/24
**审查对象**：feat v5 三件套
1. **P1-v5-1**：`BookshelfViewModel` 三处 `catch (Exception)` 之前显式 `catch CancellationException` 重抛
2. **P1-v5-2**：JS `navigate(delta)` 改返回 `{index, page}` JSON，Kotlin 解析后驱动 ViewPager2
3. **跨章节全书搜索**：`BookSearchEngine` + `SearchMode` 枚举 + `WholeBookSearchResultsSheet`

**输入材料**：`FEAT_REPORT_v5.md`、`REVIEW_REPORT_v5.md`、`FEAT_REPORT_v4.md`、`PROGRESS_v4.md`、`CLAUDE.md`
**审查方式**：只读源码 + 对照报告，不修改任何文件。

---

## 1. 摘要

### 1.1 三件套通过率

| 任务 | 评估 | 通过率 |
|------|------|--------|
| 任务一：CancellationException 重抛 | **部分通过** | ~70%（3 处目标全部命中，但仓库还有 10+ 处同类问题未在范围内） |
| 任务二：搜索 ViewPager2 联动（JSON+page） | **基本通过** | ~85%（JSON 协议正确、线程模型正确、找到了一处 `findFirstResult` 不消费 page 的遗留盲点） |
| 任务三：跨章节全书搜索 | **基本通过但有 P1 时序缺陷** | ~75%（引擎设计合理，但 `onBookSearchResultClicked` 状态机存在多处时序与契约破坏） |
| **整体** | **~77%** | 主线功能可用，新发现 1 个 P1 + 4 个 P2 + 4 个 P3 |

### 1.2 新发现 TOP 3

| 排名 | 严重度 | 标题 |
|---|---|---|
| 1 | **P1** | `onBookSearchResultClicked` 直接覆盖 `_searchMode.value` 而非调 `setSearchMode()`，绕过 `bookSearchJob.cancel()` |
| 2 | **P1** | 跨章跳转后的搜索 `find()` 不联动翻页 — 用户从全书结果点进新章后停在该章第 1 页而非匹配所在页 |
| 3 | **P2** | `searchWholeBook` 用 `_findQuery == query` 做"过期回执"保护，但 `setSearchMode` 会清 `_findQuery` 为 "" |

---

## 2. 任务一核对（P1-v5-1 CancellationException）

### 2.1 三处目标修复

核对源码（`BookshelfViewModel.kt:67-80 / 159-194 / 230-242`）：

| 位置 | 顺序 | 实现 |
|---|---|---|
| `init { cleanupOrphanedBooks }` | `catch (CancellationException) -> throw e` 在 `catch (Exception)` 之前 | 正确 |
| `importBook(...)` 主流程 | 重抛前显式清理 `_importProgress` + `_placeholderBooks`，再 `throw e` | 正确 |
| `deleteBook(...)` | `catch (CancellationException) -> throw e` 在 `catch (Exception)` 之前 | 正确 |

### 2.2 持续遗留

`grep "catch (e: Exception)"` 在仓库范围内查到 **12 处**仍未处理 CancellationException 重抛。本轮仅修了 BookshelfViewModel 的 3 处：

| 文件 | 位置 | 风险 |
|---|---|---|
| `BookRepositoryImpl.kt:93` | `importBook` 失败清理临时目录 | cancel 信号被当作业务异常 |
| `BookRepositoryImpl.kt:189` | `startImport` 失败清理 | 同上 |
| `BookRepositoryImpl.kt:310` | `getChapterPlainText` 失败返回 null | 新增方法就引入了同类问题 |
| `ReaderViewModel.kt:246/359/483/522/673` | 5 处全部吞 cancel | 配置变更触发 ViewModel 取消时可能闪错误 toast |

---

## 3. 任务二核对（搜索 ViewPager2 联动）

### 3.1 JSON 协议 / page 计算

JS 端 `pageOfMatch()` 公式（`FindInPageJs.kt:137-150`）：`Math.floor(absoluteTop / viewportH)` 与原生分页一致。正确性核对通过。

### 3.2 线程模型 / nonce / 安全面

- `evaluateJavascript()` 必须在 UI 线程调用，ValueCallback 也在 UI 线程触发
- 任务二走 `WebView.evaluateJavascript("window.ReaderFind.next()")` + `ValueCallback`，不经 `AndroidBridge.onPageCount` 路径
- 安全分析：减少了攻击面（更少桥接），未引入新风险

### 3.3 找到一处遗留盲点（P1-v6-2）

`find()` 不回报 pageInChapter：JS 端 `find()` 内部确实调了 `navigate(0)`，但其 JSON 返回被 `find` 的 `return matches.length` 覆盖了。用户在阅读器输入关键词时 ViewPager2 不翻页。

---

## 4. 任务三核对（跨章节全书搜索）

### 4.1 `BookSearchEngine` 设计

- 并发模型：`indices.asFlow().flatMapMerge(concurrency = 4)` 并发扫描章节
- 取消传播：结构化，外层 cancel 会传播到所有子 flow 的协程
- `String.contains` 性能：O(N * M)，短查询 + 中等文本可接受

### 4.2 `onBookSearchResultClicked` 跳转状态机

**P1-v6-1**：`_searchMode.value = InChapter` 绕过 `setSearchMode()` 导致旧的全书搜索 Job 不会被取消，可能 race 把过期结果写回 `_bookSearchResults`。

**P2-v6-3**：`jumpToChapter` 内的 `exitFindMode` 与外层恢复构成"先清再恢复"反模式，StateFlow 在同一调用栈内被设置 -> 清空 -> 恢复，UI 收到中间空值。

---

## 5. 新发现问题汇总

### P1 -- 重要

| ID | 标题 | 文件 |
|---|---|---|
| P1-v6-1 | `onBookSearchResultClicked` 直接赋值 `_searchMode.value`，绕过 setSearchMode 的清理逻辑 | `ReaderViewModel.kt:967` |
| P1-v6-2 | 跨章跳转后的 `find()` 不联动翻页 — 全书搜索点击结果后仍停在新章节第 1 页 | `FindInPageJs.kt`、`FindInPageController.kt` |

### P2 -- 改进

| ID | 标题 |
|---|---|
| P2-v6-1 | `searchWholeBook` 用 `_findQuery == query` 做"过期回执"保护不严密 |
| P2-v6-2 | `find()` 接口契约丢失 page 信息（与 P1-v6-2 同根） |
| P2-v6-3 | `onBookSearchResultClicked` "先清再恢复"反模式 |
| P2-v6-4 | `onPageCountReady` 在跨章跳转后不消费 pendingFindAfterJump |

### P3 -- 风格 / 可维护性

| ID | 标题 |
|---|---|
| P3-v6-1 | `MIN_QUERY_LENGTH` 硬编码 `2` |
| P3-v6-2 | `BookRepository.getChapterContent` 与 `getChapterPlainText` 逻辑重复 80% |
| P3-v6-3 | `pendingFindAfterJump` 是普通 var，非 volatile 且无线程注释 |
| P3-v6-4 | FEAT_REPORT_v5 描述"水平翻页"与代码"纵向滚动"不一致 |

---

## 6. 下一轮建议

### 立刻修（< 1 天）

1. **P1-v6-1** `onBookSearchResultClicked` 改用 `internalSetSearchMode(InChapter, preserveQuery=true)`
2. **P1-v6-2** `find()` 联动翻页 -- 修方案 B（`attachFindController` 消费 pendingFindAfterJump 后调一次 `findNext()`）

### 短期（1-2 周）

3. 用 token 取代字符串相等的过期回执检查
4. `FindInPageController.find` 升级 NavigateResult 签名
5. 抽 `jumpToChapterPreservingFindState`
6. 仓库范围引入 lint 规则禁止裸 `catch (e: Exception)`

### 中期

7. `BookRepository` 接口剥离 `Uri/Context`（四轮连续遗留）
8. FTS3 / FTS5 索引加速全书搜索
9. 跨内联匹配支持
10. 搜索历史持久化

---

# 审查报告 v1（历史）

**审查日期**：2026/05/23
**审查范围**：全部 25 个 Kotlin 源文件 + build 配置

## 总结

| 等级 | 数量 | 说明 |
|------|------|------|
| 严重 (Critical) | 1 | 构建失败 / 安全漏洞 |
| 重要 (Major) | 7 | 架构问题 / 性能隐患 / 可靠性缺陷 |
| 轻微 (Minor) | 5 | 代码风格 / 最佳实践 |

## 已修复的严重问题

### 1. [已修复] ReadingSettingsDialog.kt 第 364 行 -- 构建失败

`.clickable { onColorSelected(bgColor }` 缺少函数调用的右括号 `)`，已修复为 `.clickable { onColorSelected(bgColor) }`。

## 重要问题 (Major) -- 历史记录

- **M1**. BookRepository 接口违反领域层纯净性（`importBook(uri: Uri, context: Context)` 直接依赖 Android 框架）
- **M2**. ReaderViewModel 无错误处理，异常静默丢失（后续已修复，加入 `_errorMessage` 状态）
- **M3**. 错误信息泄露内部文件路径
- **M4**. `collectAsState` 应替换为 `collectAsStateWithLifecycle`
- **M5**. TextSplitter 中 StaticLayout 重复创建（后续已不适用，TextSplitter 已退役）
- **M6**. EpubParser.parse() 无异常处理（后续已不适用，新 `unpack()` 由 ViewModel 分类捕获）
- **M7**. PageCurlPageTransformer 的 drawShadowAndBackSide 实际无效

## 轻微问题 (Minor) -- 历史记录

- **m1**. NavGraph 使用全限定类名而非 import
- **m2**. BookshelfScreen 导入进度指示器与内容重叠（后续已重设计）
- **m3**. ReaderViewModel.splitPages() 使用硬编码默认尺寸（后续已不适用）
- **m4**. ReadingSettingsManager 未通过 Hilt 注入
- **m5**. EpubParser.ParseResult.coverBytes 使用 ByteArray 的 data class（后续已不适用）

## 架构亮点（正面评价）

1. MVVM + Repository 架构：清晰的三层分离
2. Room Flow 响应式数据流：单向数据流，数据库变化自动驱动 UI 更新
3. 类型安全导航：使用 `@Serializable data class` 定义路由
4. EPUB 按需解析策略：章节内容不预存数据库
5. TextSplitter 使用 StaticLayout：精确计算 CJK/拉丁字符混排的分页
6. 阅读进度百分比恢复：设置变更后按百分比保持阅读位置
7. 代码注释：每个文件都有完整的中文业务逻辑注释

---

# 审查报告 v2（历史）

**审查日期**：2026/05/24
**审查范围**：全部 25 个 Kotlin 源文件 + 构建脚本 + Manifest + Migration
**对比基线**：v1

## 总体评估

项目整体架构清晰（MVVM + Repository + Hilt + 单一 Activity + 类型安全 Navigation），代码注释非常充分。EPUB 解析层已加入 ZIP Slip 与 ZIP 炸弹防护。

但在阅读器核心子系统（ViewPager2 + WebView + 全局页面流）仍存在结构性问题。

## 关键风险 TOP 5

| 排名 | 严重度 | 标题 |
|---|---|---|
| 1 | P0 | release 构建缺 ProGuard/R8 规则 |
| 2 | P0 | WebView 缓存只增不减，存在原生内存泄漏 |
| 3 | P0 | WebView 安全配置组合存在敏感文件读取风险 |
| 4 | P0 | JS 注入 CSS 字符串转义不完整 |
| 5 | P1 | BookRepository 领域接口持续耦合 Uri/Context |

## 严重度汇总

| 严重度 | 数量 |
|---|---|
| P0 | 5 |
| P1 | 10 |
| P2 | 11 |
| P3 | 15 |
| **合计** | **41** |

---

# 审查报告 v3（历史）

**审查日期**：2026/05/24
**审查对象**：fix 智能体根据 v2 报告 + TDD 报告提交的修复
**对比基线**：v2（41 条问题）

## 修复通过率

| 状态 | 数量 | 占比 |
|---|---|---|
| 完全修复 | 8 | 73% |
| 部分修复 / 有残留 | 3 | 27% |
| 修错 / 引入新 bug | 0 | 0% |

## 新发现 TOP 5

| 排名 | 严重度 | 标题 |
|---|---|---|
| 1 | P1 | `getAllBooks()` 未过滤 `PREPARING_*` 占位记录 |
| 2 | P1 | `WebViewAssetLoader.InternalStoragePathHandler` 路径校验抛异常 |
| 3 | P1 | `nextChapter` 未预加载时静默 no-op |
| 4 | P2 | `LRU.clear()` 缺线程断言 |
| 5 | P2 | `BookCard.AsyncImage` 的 coverPath 在 PREPARING_ 占位上通常 null |

---

# 审查报告 v4（历史）

**审查日期**：2026/05/24
**审查对象**：fix v2 智能体根据 v3 报告提交的三条 P1 修复
**对比基线**：v3

## 修复通过率

| Issue | 状态 | 备注 |
|---|---|---|
| P1-1 PREPARING_ 过滤 + 幽灵清理 | 部分修复 | 主修复到位；但意外屏蔽了书架导入进度环 UI |
| P1-2 loadHtml 防御 + adapter try-catch | 完全修复 | 入口短路 + 双层 try-catch |
| P1-3 nextChapter/previousChapter 兜底 | 部分修复 | 复用 jumpToChapter 正确；但 previousChapter 跳到第一页而非最后一页 |

## 新发现 TOP 3

| 排名 | 严重度 | 标题 |
|---|---|---|
| 1 | P1 | books flow 过滤 PREPARING_ 后，书架的导入进度环 UI 完全消失 |
| 2 | P2 | previousChapter() 未预加载时跳到上一章第一页 |
| 3 | P2 | getBookById(PREPARING_id) 仍然返回非 null，深链接可直达 ReaderViewModel |

---

# 审查报告 v5（历史）

**审查日期**：2026/05/24
**审查对象**：feat v3（P1 退化修复）+ feat v4（章内搜索）
**对比基线**：v4

## 通过率

| 维度 | 通过率 | 备注 |
|---|---|---|
| feat v3 / P1-NEW-1 | 2/3 完全修复 + 1/3 部分修复 | CancellationException 路径未覆盖 |
| feat v3 / P1-NEW-2 | 完全修复 | pendingPositionRestore 路径正确 |
| feat v4 / XSS 防御 | 完全 | escapeJsString 在 find 路径使用正确 |
| feat v4 / 用户体验完整性 | 部分 | scrollIntoView 与 ViewPager2 分页不联动 |

## 新发现 TOP 3

| 排名 | 严重度 | 标题 |
|---|---|---|
| 1 | P1 | scrollIntoView 与 ViewPager2 分页不联动 -- 用户输入关键词后看不到匹配项 |
| 2 | P1 | importBook 失败路径未处理 CancellationException |
| 3 | P2 | LRU 驱逐章节的 WebView 后，旧 FindInPageController 持有野指针 |
