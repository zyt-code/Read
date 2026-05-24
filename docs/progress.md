# 项目进度报告（合并版）

> 本文件合并了项目历次进度报告（v1-v5）的核心内容。
> 记录了从初始审查到最新功能开发的完整迭代过程。

---

## 迭代总览

| 轮次 | 日期 | 阶段 | 核心产出 |
|------|------|------|----------|
| 第一轮 | 2026/05/24 | review + TDD + docs | v2 审查报告（41 条问题）、79 个测试方法、文档体系 |
| 第二轮 | 2026/05/24 | fix -> review/tdd/docs | 14 项修复（P0×5 + B×6 + C×3）、42 个回归测试 |
| 第三轮 | 2026/05/24 | fix -> review/tdd/docs | 3 条 P1 修复、17 个回归测试 |
| 第四轮 | 2026/05/24 | feat v3 -> feat v4 -> review/tdd/docs | 2 条 P1 退化修复 + 章内搜索、42 个回归测试 |
| 第五轮 | 2026/05/24 | feat v5 -> review/tdd/docs | 跨章节全书搜索 + CancellationException 修复、40 个回归测试 |

---

## 第五轮（最新）：v5 主线

**执行方式**：feat v5 串行 -> 并行 review v6 / tddtest v5 / docs
**目标**：v5 主线（跨章节全书搜索）+ 两条 P1 收尾

### feat v5 三件套

| 任务 | 内容 | 状态 |
|------|------|------|
| 任务一 | CancellationException 重抛（BookshelfViewModel 3 处） | 完成 |
| 任务二 | 搜索 ViewPager2 联动（JS 返回 JSON + Kotlin 解析驱动翻页） | 完成 |
| 任务三 | 跨章节全书搜索（BookSearchEngine + SearchMode + WholeBookSearchResultsSheet） | 完成 |

### review v6 核心发现

- 通过率 ~77%
- 新发现 9 条：P1×2 + P2×4 + P3×3
- P1-v6-1：`onBookSearchResultClicked` 直接赋值绕过 setSearchMode
- P1-v6-2：跨章跳转后 find() 不联动翻页

### 测试新增

40 个新回归测试（3 个新文件 + 2 个追加）

---

## 第四轮：v3 收尾 + v4 主线

**执行方式**：feat v3 串行 -> feat v4 串行 -> 并行 review v5 / tddtest v4 / docs
**目标**：v3 patch 收尾 + 引入 v4 核心新特性

### feat v3：v3 patch 收尾

| Issue | 实施方案 | 通过率 |
|---|---|---|
| P1-NEW-1 书架导入进度环 UI 消失 | placeholderBooks StateFlow + PlaceholderBookCard 组件 | 部分 |
| P1-NEW-2 jumpToChapter 未预加载分支闪回 | pendingPositionRestore 状态机 | 完全 |

### feat v4：章内搜索

新增 3 个文件：
- `JsEscaping.kt` -- escapeJsString 委托
- `FindInPageJs.kt` -- 注入脚本 + 高亮样式
- `FindInPageController.kt` -- Kotlin 控制器

关键设计：零新增依赖、零新增 JS 桥、与 P0-3 nonce 安全模型解耦。

### 测试新增

42 个新回归测试（5 个新 JVM 测试文件）

---

## 第三轮：Patch P1

**执行方式**：fix -> 并行 review/tddtest/docs
**聚焦**：P1-1 / P1-2 / P1-3 三条 v3 遗留收尾

### fix v2 执行结果

| Issue | 标题 | 状态 |
|---|---|---|
| P1-1 | getAllBooks() 未过滤 PREPARING_ + 幽灵清理 | 完全修复 |
| P1-2 | WebViewAssetLoader 路径校验防御 | 完全修复 |
| P1-3 | nextChapter 未预加载兜底 | 完全修复 |

### 关键警告

review v4 发现：**P1-1 修复引入功能退化** -- SQL 层过滤 PREPARING_ 后，书架在整个导入过程完全看不到进度环。后续在 feat v3 中通过独立 placeholderBooks StateFlow 修复。

### 测试新增

17 个新回归测试（2 个新文件 + 2 个追加）

---

## 第二轮：P0 集中修复

**执行方式**：fix -> 并行 review/tddtest/docs
**聚焦**：release 构建可用性 + 阅读器内存安全 + WebView 安全模型

### fix 执行结果（14 项）

| 类别 | 通过率 |
|---|---|
| P0×5（ProGuard / WebView 缓存 / 安全配置 / JS 转义 / 导入竞态） | 3 完全 + 2 部分 |
| B×6（边界 / 设置重置 / NCX / 删除检查 / 预加载超时 / 占位清理） | 5 完全 + 1 部分 |
| C×3（IO 切换 / debounce / Adapter IO） | 3 完全 |

### 关键设计变更

1. **WebView 安全模型重写**：file:// -> WebViewAssetLoader + https + nonce 校验
2. **WebView 内存模型确定化**：LRU(maxSize=3) + 全销毁重建 + 5 秒超时
3. **导入态机制**：PREPARING_ 前缀（不动 Schema）

### 测试新增

42 个回归测试（6 个新文件 + 3 个追加）

---

## 第一轮：初始审查与测试

**执行方式**：多子智能体并行（代码审查 / TDD 测试补强 / 文档重写）

### 代码审查 v2 核心发现

**41 条问题**：P0×5 / P1×10 / P2×11 / P3×15

P0 必修清单：
1. release 构建缺 ProGuard/R8 规则
2. WebView 缓存只增不减
3. WebView 安全配置存在敏感文件读取风险
4. JS 注入 CSS 字符串转义不完整
5. cleanupOrphanedBooks 与活跃 import 的竞态

### TDD 测试补强

7 个测试文件、79 个测试方法、8 个潜在生产 Bug 发现

### 文档体系

6 个文档：README.md、CONTRIBUTING.md、docs/architecture.md、docs/data-model.md、docs/development.md、docs/testing.md

---

## 当前仓库状态快照

### 源码

```
app/src/main/java/com/example/read/   25 个 Kotlin 文件
├── di/AppModule.kt
├── data/ ............ Room + Repository
├── domain/ .......... Book / Chapter / Repository 接口
├── ui/ .............. bookshelf + reader + components + navigation + theme
└── util/ ............ EpubParser + EpubUnpackResult
```

### 测试

- JVM 单元测试：约 28 个测试类、约 230 个测试方法
- Android 集成测试：4 个测试类、30 个测试方法

### 风险状态

| 风险维度 | 初始状态 | 当前状态 |
|---|---|---|
| Release 构建可发版 | 必崩 | 已配 ProGuard（待冒烟验证） |
| 长篇 EPUB 内存安全 | 几十个 WebView 不释放 | LRU 限 3 个；DiffUtil 待补 |
| 恶意 EPUB 文件读取 | file:// + allowFileAccess=true | WebViewAssetLoader + nonce |
| 导入并发安全 | 误删/占位残留 | PREPARING_ 前缀 + 幽灵清理 |
| 搜索功能 | 无 | 章内搜索 + 跨章节全书搜索 |
| 测试覆盖 | 部分 | 220+ 个测试方法 |

---

## 综合建议路线图

### 立刻

1. P1-v6-1：`onBookSearchResultClicked` 改用 `internalSetSearchMode`
2. P1-v6-2：`find()` 联动翻页
3. 本地跑 `./gradlew assembleRelease` 验证 ProGuard 规则

### 短期（1-2 周）

4. `BookRepository` 接口剥离 `Uri/Context`
5. DiffUtil 化 PagedChapterAdapter
6. 仓库范围引入 lint 规则禁止裸 `catch (e: Exception)`

### 中期

7. 引入 Robolectric 打通 WebView 相关测试
8. 引入 Compose UI Test 覆盖 UI 层
9. FTS3/FTS5 索引加速全书搜索
