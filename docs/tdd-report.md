# TDD 测试补强报告（合并版）

> 本文件合并了项目历次 TDD 测试补强报告（v1-v5）的核心内容。
> 每轮测试补强在不修改生产代码的前提下，按 TDD 思路为既有功能补强测试。

---

## 版本演进

| 版本 | 日期 | 作用域 | 新增测试方法数 |
|------|------|--------|---------------|
| v1 | 2026/05/24 | 初始覆盖率审计 + 潜在 bug 发现 | 79 |
| v2 | 2026/05/24 | FIX_REPORT v1 修复回归专项 | 42 |
| v3 | 2026/05/24 | FIX_REPORT_v2 P1 修复回归专项 | 17 |
| v4 | 2026/05/24 | FIX_REPORT_v3 + feat v4 章内搜索回归专项 | 42 |
| v5 | 2026/05/24 | feat v5 三件套回归专项 | 40 |
| **累计** | | | **220** |

---

## 完整测试矩阵

### 单元测试（JVM，`app/src/test/`）

| 测试类 | 测试方法数 | 范围 |
|--------|-----------|------|
| `EpubParserTest` | 11 | HTML 转纯文本（Jsoup）：嵌套标签、实体字符、Unicode、空输入 |
| `EpubParserExtraTest` | 15 | spine 顺序、quickExtractMetadata、异常路径、normalize `..` |
| `EpubParserSecurityTest` | -- | Zip Slip / Zip 炸弹防御 |
| `EpubFullFlowTest` | -- | OPF/NCX 完整流、嵌套 NavPoints |
| `EpubUnpackResultTest` | 7 | 序列化往返、字段默认值、Unicode |
| `BookEntityMapperTest` | 9 | Entity/Domain 双向映射、往返转换、边界值 |
| `BookRepositoryImplTest` | -- | Repository 基础协调 |
| `BookRepositoryImplFsTest` | 18 | 文件副作用 / getChapterContent 全分支 / deleteRecursively 失败 |
| `BookRepositoryImplCleanupTest` | 10 | PREPARING_ 前缀跳过、幽灵清理、边界值 |
| `BookshelfViewModelTest` | 6 | 状态管理、删除、错误处理、clearError 幂等性 |
| `BookshelfViewModelImportTest` | 11 | 导入流程 4 种异常分支 + B8 兜底 |
| `BookshelfViewModelPlaceholderTest` | 8 | P1-NEW-1 回归：占位生命周期、并发导入 |
| `BookshelfViewModelCancellationTest` | 9 | P1-v5-1 回归：CancellationException 重抛 |
| `ReaderViewModelTest` | 12 | 加载、章节切换、边界检查、进度保存 |
| `ReaderViewModelExtraTest` | 14 | 跳转边界、updateSettings 恢复、onCleared |
| `ReaderViewModelBoundaryTest` | 8 | 章节边界、未预加载兜底 |
| `ReaderViewModelSettingsRestoreTest` | 3 | updateSettings 重置与恢复 |
| `ReaderViewModelDebounceTest` | 3 | pagePrefs 100ms debounce |
| `ReaderViewModelJumpFixTest` | 4 | P1-NEW-2 回归：jumpToChapter 状态机 |
| `ReaderViewModelFindTest` | 11 | feat v4 章内搜索回归 |
| `ReaderViewModelFindMatchLocatedTest` | 8 | P1-v5-2 ViewModel 端 |
| `ReaderViewModelWholeBookSearchTest` | 13 | 跨章节全书搜索回归 |
| `ReadingSettingsTest` | 16 | 默认值、getTypeface、toReaderCss、Manager |
| `WebViewPaginatorEscapeTest` | 16 | escapeJsString 全转义路径 + nonce |
| `WebViewLruCacheTest` | 6 | LRU 容量 / 驱逐 / destroy |
| `FindInPageJsTest` | 8 | feat v4 JS 常量契约 |
| `FindInPageControllerTest` | 16 | Controller JSON 解析 + 边界 |
| `ChapterWebViewFactoryGuardTest` | 4 | loadHtml 入口防御 |
| `BookSearchEngineTest` | 12 | 全书搜索引擎 + 取消 |

### 集成测试（Android，`app/src/androidTest/`）

| 测试类 | 测试方法数 | 范围 |
|--------|-----------|------|
| `BookDaoTest` | 12 | CRUD、排序、Flow 响应式更新 |
| `BookDaoFilterTest` | 6 | getAllBooks 过滤 PREPARING_ 记录 |
| `BookRepositoryImplTest` | 9 | 真实数据库协调 |
| `MigrationsTest` | 3 | v1 -> v2 Room 迁移 |

**测试工具**：JUnit4、MockK、kotlinx-coroutines-test、Turbine（Flow 测试）、Room testing

---

## v1 覆盖率审计

### util/EpubParser.kt

| 未测路径 | 状态 |
|---|---|
| OPF spine 严格按 itemref 顺序 | 已覆盖 |
| spine itemref 在 manifest 中找不到 | 已覆盖 |
| quickExtractMetadata() 成功/失败路径 | 已覆盖 |
| container.xml 缺 rootfile / full-path 为空 | 已覆盖 |
| dc:title / dc:creator 缺失时的兜底 | 已覆盖 |
| NCX 在 manifest 中声明但 ZIP 内文件缺失 | 已覆盖 |
| ZIP Slip 误杀：合法 `OEBPS/../OEBPS/x` | 已覆盖 |

### data/repository/BookRepositoryImpl.kt

| 未测路径 | 状态 |
|---|---|
| deleteBook 删除目录 + 封面 | 已覆盖 |
| deleteBook 文件不存在 / bookDirPath 空 / coverPath null | 已覆盖 |
| getChapterContent 全分支 | 已覆盖 |
| importBook / prepareImport / startImport | 不可测（需 SAF URI） |

### ui/reader/ReaderViewModel.kt

| 未测路径 | 状态 |
|---|---|
| jumpToChapter 已预加载/未预加载分支 | 已覆盖 |
| jumpToGlobalPage 越界裁剪 | 已覆盖 |
| updateSettings 清空缓存 + 保留章内页码 | 已覆盖 |
| onCleared 保存章内页码 | 已覆盖 |
| preloadAdjacentChapters | 不可测（需 WebView） |

---

## v1 发现的潜在生产 Bug

| ID | 位置 | 现象 | 后续状态 |
|---|---|---|---|
| B1 | ReaderViewModel.kt:270 | nextChapter 缺显式上界校验 | 已修复（v1 fix） |
| B2 | ReaderViewModel.kt:398 | updateSettings 未重置 _currentGlobalPage | 已修复（v1 fix） |
| B3 | EpubParser.kt:648 | normalizeNcxHref 不处理 `..` 段 | 已修复（v1 fix） |
| B4 | EpubParser.kt:518 | 同一 href 多 navPoint 时只取第一个 | 遗留（低优先级） |
| B5 | BookRepositoryImpl.kt:176 | deleteRecursively() 返回值未检查 | 已修复（v1 fix） |
| B6 | ReaderViewModel.kt:366 | preload 临时 WebView 资源泄漏 | 已修复（v1 fix） |
| B7 | EpubParser.kt:266 | parseContainerXml 未按 media-type 选 rootfile | 遗留（低优先级） |
| B8 | BookshelfViewModel.kt:65 | importBook 失败时未清理占位记录 | 已修复（v1 fix） |

---

## 不可测项与建议引入的测试库

| 类 | 阻塞原因 | 建议 |
|---|---|---|
| TextSplitter | 依赖 StaticLayout（JVM 返回 null） | 引入 Robolectric |
| ReaderScreen / BookshelfScreen | Compose UI | 引入 AndroidX Compose UI Test |
| WebViewPaginator / ChapterWebViewFactory | 需 WebView 实例 | Robolectric 或 androidTest |
| preloadAdjacentChapters | 依赖 WebView 实例化 | 同上 |
