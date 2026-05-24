# Read

> 一款原生 Android EPUB 阅读器，使用 Kotlin + Jetpack Compose 构建，提供仿真翻页、可定制排版与跨章节连续翻页体验。

**最近更新**：
- 2026-05-24（v5 跨章节搜索）：新增全书搜索（FindInPageBar 模式 toggle + BottomSheet 结果列表 + 跨章定位）；
  修复 P1-v5-1（`BookshelfViewModel` 吞 `CancellationException`）与 P1-v5-2（章内搜索 ViewPager2 联动），
  详见 [CHANGELOG.md](CHANGELOG.md) 与 [FEAT_REPORT_v5.md](FEAT_REPORT_v5.md)。
- 2026-05-24（v4 章节内搜索）：新增章节内 find-in-page 能力（顶栏 🔍 → 搜索栏 + 高亮 + 计数 + 上下导航），详见 [CHANGELOG.md](CHANGELOG.md) 与 [FEAT_REPORT_v4.md](FEAT_REPORT_v4.md)。
- 2026-05-24（Patch v3 收尾）：修复 P1-NEW-1（书架导入进度环 UI 消失）与 P1-NEW-2（jumpToChapter 未预加载分支错位），详见 [CHANGELOG.md](CHANGELOG.md) 与 [FIX_REPORT_v3.md](FIX_REPORT_v3.md)。
- 2026-05-24（Patch P1）：P1 修复（占位记录过滤 / Reader 开屏崩 / 章节按钮失灵），详见 [CHANGELOG.md](CHANGELOG.md) 与 [FIX_REPORT_v2.md](FIX_REPORT_v2.md)。
- 2026-05-24：修复 5 个 P0 + 6 个生产 bug，详见 [CHANGELOG.md](CHANGELOG.md)。

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-blue.svg)](https://kotlinlang.org/)
[![Compose BOM](https://img.shields.io/badge/Compose%20BOM-2024.12.01-brightgreen.svg)](https://developer.android.com/jetpack/compose/bom)
[![Min SDK](https://img.shields.io/badge/minSdk-26-green.svg)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/targetSdk-35-green.svg)](https://developer.android.com/about/versions/15)
[![Build](https://img.shields.io/badge/build-Gradle%208.11.1-orange.svg)](https://gradle.org/)
[![License](https://img.shields.io/badge/license-TBD-lightgrey.svg)](#许可证)

---

## 目录

- [应用截图](#应用截图)
- [功能亮点](#功能亮点)
- [快速开始](#快速开始)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [开发文档](#开发文档)
- [路线图](#路线图)
- [贡献指南](#贡献指南)
- [许可证](#许可证)

---

## 应用截图

![Read 应用截图](screenshot.png)

> 图示为书架页面和阅读器页面。书架以 2 列网格展示书籍封面与标题，阅读器以 WebView 渲染原始 HTML/CSS，并通过仿真卷曲动画完成翻页。

---

## 功能亮点

| 模块 | 能力 |
|------|------|
| **书架** | 2 列网格、Coil 3 异步封面、Room Flow 响应式更新、长按删除、按最近阅读时间排序、空状态引导 |
| **EPUB 导入** | SAF 文件选择器（`application/epub+zip`）、两阶段导入（先快速元数据占位，再后台完整解包）、导入进度环、崩溃残留自动清理 |
| **EPUB 解析** | 纯 Jsoup XML 解析 OPF/NCX、ZIP Slip 防护、单条目大小限制（100MB）抵御 ZIP 炸弹、目录层级提取 |
| **阅读器** | ViewPager2 横滑翻页、PageCurl 仿真卷曲动画、WebView + JS 精确分页、跨章节连续页面流、相邻章节后台预加载、点击 1/4 区域翻页 |
| **章节内搜索** 🔍 | 顶栏 🔍 → 搜索栏（输入框 + `x / y` 计数 + 上下导航）→ 黄色高亮全部匹配 + 橙色高亮当前选中 + 自动滚动可见；切章 / 设置变更自动退出（v4 主线，v5 起匹配项跨章内多页时 ViewPager2 自动联动翻页） |
| **全书搜索** 🔎 | 搜索栏 toggle 切到全书模式 → `BookSearchEngine` 以 `flatMapMerge(concurrency=4)` 并发扫描所有章节 → 底部 BottomSheet 列出"章节标题 / 匹配数 / 摘要"→ 点击结果跳转目标章并自动定位关键词；中长书 125-500ms 返回（v5 主线） |
| **目录导航** | 解析 NCX 生成多级目录、ModalBottomSheet 弹出、当前章节高亮 |
| **阅读设置** | 字号 14-28sp、行高 1.2-2.5x、宋体/黑体/等宽字体、白/护眼/暗黑三种背景、实时重新分页 |
| **进度同步** | 章节索引存 Room、章内页码存 SharedPreferences、设置变更后按百分比恢复阅读位置 |
| **架构** | MVVM + Repository + 依赖倒置、Hilt DI、类型安全 Navigation、Material 3 动态取色 |

---

## 快速开始

### 环境要求

| 依赖 | 推荐版本 |
|------|----------|
| JDK | 17 或更高 |
| Android SDK | API 35（compileSdk/targetSdk） |
| 最低支持系统 | Android 8.0（API 26） |
| Gradle | 8.11.1（由 Wrapper 自动管理） |
| Android Studio | Ladybug 2024.2.1 或更新版本 |

### 构建步骤

```bash
# 克隆仓库
git clone <repo-url>
cd read

# 使用 Gradle Wrapper 同步并构建（首次执行会下载 Gradle 8.11.1）
./gradlew build

# 将 Debug 包安装到已连接的设备/模拟器
./gradlew installDebug
```

如果仓库中未包含 `gradlew`，可先在本机生成：

```bash
gradle wrapper --gradle-version 8.11.1
```

### 运行测试

```bash
# JVM 单元测试
./gradlew test

# Android 集成测试（需要连接设备或启动模拟器）
./gradlew connectedAndroidTest
```

更详细的开发流程请参阅 [docs/development.md](docs/development.md)。

---

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material 3 | BOM 2024.12.01 |
| 架构 | MVVM + Repository + 依赖倒置 | - |
| 依赖注入 | Hilt | 2.53.1 |
| 数据库 | Room（通过 KSP 编译） | 2.6.1 |
| 导航 | Navigation Compose（类型安全路由） | 2.8.5 |
| 图片加载 | Coil 3 | 3.0.4 |
| HTML / XML 解析 | Jsoup | 1.18.3 |
| 章节渲染 | Android WebView | 系统内置 |
| WebView 安全 | androidx.webkit (WebViewAssetLoader) | 1.12.1 |
| 翻页容器 | ViewPager2 | 1.1.0 |
| 序列化 | Kotlinx Serialization JSON | 1.7.3 |
| 协程 | Kotlinx Coroutines | 1.9.0 |
| 构建工具 | AGP（Android Gradle Plugin） | 8.7.3 |
| 单元测试 | JUnit 4 + MockK + Turbine | 4.13.2 / 1.13.13 / 1.2.0 |

---

## 项目结构

精简版结构概览：

```
read/
├── app/
│   ├── build.gradle.kts                # 模块构建脚本
│   ├── schemas/                        # Room 自动导出的数据库 schema
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/read/  # Kotlin 源码（详见 docs/architecture.md）
│       │   └── res/                    # 资源（drawable / values / mipmap）
│       ├── test/                       # JVM 单元测试
│       └── androidTest/                # Android 仪器化测试
├── build.gradle.kts                    # 项目级构建脚本（仅插件声明）
├── gradle/
│   ├── libs.versions.toml              # Version Catalog
│   └── wrapper/
├── docs/                               # 项目文档
├── CLAUDE.md                           # Claude Code 使用的项目说明（请勿修改）
├── REVIEW_REPORT.md                    # 历史代码审查记录
├── screenshot.png
└── README.md
```

完整分层架构、模块职责、数据流与设计权衡请参阅 [docs/architecture.md](docs/architecture.md)。

---

## 开发文档

| 文档 | 适合谁 | 内容 |
|------|--------|------|
| [docs/architecture.md](docs/architecture.md) | 想理解整体设计的开发者 | 分层架构、模块依赖、数据流、关键设计决策 |
| [docs/development.md](docs/development.md) | 准备贡献代码的开发者 | 环境搭建、Gradle 命令、调试技巧、编码规范 |
| [docs/testing.md](docs/testing.md) | 写测试的开发者 | 测试策略、覆盖范围、新增测试模板、Mock 策略 |
| [docs/data-model.md](docs/data-model.md) | 关注数据持久化的开发者 | Room schema、迁移历史、文件系统布局、SharedPreferences keys |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 提 Issue / PR 的人 | 协作流程、分支策略、提交信息、PR checklist |

---

## 路线图

以下条目源自 [REVIEW_REPORT.md](REVIEW_REPORT.md) / [REVIEW_REPORT_v2.md](REVIEW_REPORT_v2.md) 中发现的改进点。标记 ✅ 表示已在 2026-05-24 的修复批次中完成（详见 [CHANGELOG.md](CHANGELOG.md) 与 [FIX_REPORT.md](FIX_REPORT.md)），标记 *(规划中)* 表示尚未实现。

### 已完成（2026-05-24）

- ✅ **P0-1 补齐 ProGuard / R8 keep 规则**：覆盖 kotlinx.serialization、Room、Hilt、@JavascriptInterface、Navigation 路由 `@Serializable`、Coil 3 等，保障 `release` 构建可用（`app/proguard-rules.pro`）。
- ✅ **P0-2 WebView 内存与 Activity Context 泄漏**：新增 `WebViewLruCache(maxSize=3)`（`ReaderScreen.kt:518`），并把 `ChapterWebViewFactory` 与预加载切到 `applicationContext`（`ReaderViewModel.kt:365`）。
- ✅ **P0-3 WebView 安全模型**：从 `file://` + `setAllowFileAccess(true)` 切换到 `WebViewAssetLoader` + `https://appassets.androidplatform.net/`；`AndroidBridge` 加 nonce 校验（`ChapterWebViewFactory.kt`、`WebViewPaginator.kt`）。
- ✅ **P0-4 `updateCSS` JS 注入转义不完整**：`WebViewPaginator.escapeJsString` 基于 `JSONObject.quote`，再额外转义 U+2028 / U+2029 / `</` 序列。
- ✅ **P0-5 `cleanupOrphanedBooks` 与活跃 import 竞态**：占位记录改用 `PREPARING_{uuid}` 前缀（`BookRepositoryImpl.kt:121`），`cleanupOrphanedBooks` 显式跳过这类记录。
- ✅ **B1/B2/B3/B5/B6/B8 六个生产 bug**：章节切换显式上下界、`updateSettings` 同步重置 `_currentGlobalPage`、`normalizeNcxHref` 处理 `..`、`deleteBook` 检查 `deleteRecursively` 返回值、预加载 WebView 5 秒超时 fallback、导入失败兜底清理占位记录。

### 已完成（2026-05-24 Patch P1）

- ⚠️ **P1-1 占位记录幽灵书残留**（功能退化已修复 in Patch v3 收尾）：`BookDao.getAllBooks()` 在 SQL 层过滤 `PREPARING_%` 占位记录（`BookDao.kt:38`），新增 `getAllBooksIncludingPreparing()`（`BookDao.kt:46`）供清理路径使用；`cleanupOrphanedBooks` 引入 `PREPARING_GHOST_THRESHOLD_MS = 1h`（`BookRepositoryImpl.kt:311`）兜底清除崩溃残留。本修复曾导致书架导入进度环 UI 消失（P1-NEW-1），已在 Patch v3 收尾中通过 ViewModel 独立暴露 `placeholderBooks` 修复。
- ✅ **P1-2 阅读器对 `PREPARING_*` 或空路径开屏崩溃**：`ChapterWebViewFactory.loadHtml` 入口对 `bookDirPath` 做三态校验短路（`ChapterWebViewFactory.kt:121-157`）；`PagedChapterAdapter.getOrCreateWebView` 加 try-catch 兜底（`ReaderScreen.kt:686-723`）。
- ✅ **P1-3 底栏"上一章/下一章"按钮在未预加载时看似失灵**：`ReaderViewModel.nextChapter` / `previousChapter` 在 `globalPages` 未命中时复用 `jumpToChapter(±1)`（`ReaderViewModel.kt:295-331`）。

### 已完成（2026-05-24 Patch v3 收尾）

- ✅ **P1-NEW-1 书架导入进度环 UI 消失**：上一轮 P1-1 在 SQL 层过滤 `PREPARING_*` 后，`BookshelfScreen` 的 `LazyVerticalGrid(items = books)` 不再渲染占位卡片，导入过程完全静默。修复后 `BookshelfViewModel` 暴露独立的 `placeholderBooks: StateFlow<List<PlaceholderBook>>`（`BookshelfViewModel.kt`），新增可复用组件 `PlaceholderBookCard`（`ui/components/PlaceholderBookCard.kt`），`BookshelfScreen` 在 grid 顶部追加占位卡片渲染。同时保留 P1-1 的"幽灵记录不残留"收益。
- ✅ **P1-NEW-2 `jumpToChapter` 未预加载分支错位**：未预加载分支不再立即写 `_currentGlobalPage = 0`（这会让 UI 闪回第一章第一页）；改为设置 `pendingPositionRestore = true; pendingPageInChapter = 0`，等 `onPageCountReady` 回调到达后由位置恢复机制把全局页码定位到目标章节首页（`ReaderViewModel.kt`）。

### 已完成（2026-05-24 v4 章节内搜索）

- ✅ **v4 主线 章节内搜索**：阅读器顶栏新增 🔍 按钮，进入搜索栏后输入关键词，章内全部匹配项以黄色高亮，当前选中项以橙色高亮并 `scrollIntoView`；支持上下循环跳转和 `x / y` 计数。仅作用于当前章节，跨章节滑动 / 跳转目录 / 设置变更自动退出搜索模式（`ReaderViewModel.kt:307/426/547`）。新增组件 `FindInPageController`、`FindInPageJs`、`JsEscaping`；`ChapterWebViewFactory.onPageFinished` 自动注入 `FIND_IN_PAGE_JS`（`ChapterWebViewFactory.kt:198-202`），`ReadingSettings.toReaderCss()` 末尾追加 `FIND_IN_PAGE_CSS`（`ReadingSettings.kt:134`）。零新增依赖、零新增 JS 桥，复用 `escapeJsString` 防御字面量截断 / 正则元字符注入，攻击面无新增。

### 已完成（2026-05-24 v5 跨章节搜索 + ViewPager2 联动 + 协程取消修复）

- ✅ **P1-v5-1 `BookshelfViewModel` 吞 `CancellationException`**：`init { cleanupOrphanedBooks }`（`BookshelfViewModel.kt:72-79`）、`importBook`（`BookshelfViewModel.kt:159-173`）、`deleteBook`（`BookshelfViewModel.kt:234-236`）3 处 `catch (Exception)` 之前显式 `catch (CancellationException) { throw e }`，让 `viewModelScope.cancel()` 链路正常传播；`importBook` 重抛前清空 `_importProgress` / `_placeholderBooks` 防止取消瞬间残留进度环。
- ✅ **P1-v5-2 章内搜索 ViewPager2 联动**：v4 已知限制 3 修复。`FindInPageJs.navigate(delta)` 返回 JSON `{index, page}`（`FindInPageJs.kt:155-179`），`FindInPageController.parseNavigateResult` 解析为 `NavigateResult`，`ReaderViewModel.onFindMatchLocated`（`ReaderViewModel.kt:859`）把章内页码转为全局页码写入 `_currentGlobalPage`，ReaderScreen 的 `AndroidView.update` 观察后自动 `viewPager.setCurrentItem`。匹配项位于章内第 2+ 页时高亮现在可见。
- ✅ **v5 主线 跨章节全书搜索**：新增 `BookSearchEngine`（`data/search/BookSearchEngine.kt`）+ `SearchResult` 数据类（不持久化）；`BookRepository.getChapterPlainText` 接口（`BookRepository.kt:79`）；`ReaderViewModel` 增加 `searchMode/bookSearchResults/bookSearchInProgress` StateFlow 与 `setSearchMode/searchWholeBook/onBookSearchResultClicked` 方法（`ReaderViewModel.kt:185-205, 885-979`）；`FindInPageBar` 增加模式 toggle（`ReaderScreen.kt:1021`），全书模式下渲染 `WholeBookSearchResultsSheet`（`ReaderScreen.kt:615`）。`flatMapMerge(concurrency = 4)` 并发扫描，中长书 125-500ms 返回。

### 路线图（v6 及以后）*(规划中)*

- 🔎 **持久化搜索索引**：基于 SQLite FTS5 / Lucene 离线倒排索引，把大部头书的搜索耗时压缩到毫秒级，支持模糊匹配 / 拼音搜索 / 按章节范围限定。
- 🔖 **书签 / 笔记 / 高亮选段**：在 WebView 中长按文字弹出菜单，落库存储；与搜索高亮颜色明确区分。
- 🔊 **TTS 朗读**：基于 Android `TextToSpeech` API，按段落分句朗读，支持后台播放和断点续播。

### 架构与可测试性 *(规划中)*

- 将 `BookRepository` 接口中的 `Uri` / `Context` 替换为 `InputStream` 等纯 JVM 类型，让 Repository 接口可在不依赖 Android 框架的环境下进行单元测试。
- 通过 Hilt 注入 `ReadingSettingsManager`（目前在 `ReaderViewModel` 中手动 `new`），以便测试时 Mock 替换。
- 把预加载逻辑从 `ReaderViewModel` 中迁出（避免 ViewModel 持有 `Application` / `mainHandler.post`）。

### 性能优化 *(规划中)*

- 重构 `TextSplitter`：避免对同一段落多次创建 `StaticLayout`，合并行数测量与行文本提取为单次遍历。
- 修正 `PageCurlPageTransformer`：当前 `drawShadowAndBackSide` 创建了 `shadowPaint` 渐变但未真正绘制，并对每帧调用 `setLayerType(LAYER_TYPE_HARDWARE)`，需要进一步打磨。
- `PagedChapterAdapter` 切换到 `DiffUtil`，替换当前的 `notifyDataSetChanged`。

### 用户体验 *(规划中)*

- 用 `collectAsStateWithLifecycle()` 替换所有 `collectAsState()`，避免 Activity 进入后台时仍持续收集 Flow。
- 触摸区域识别从 `OnTouchListener` 升级到 `GestureDetector` + 边缘排除。
- 导入进度遮罩改为半透明覆盖，避免在书架上看到下层网格闪动。
- 错误信息全部映射为对用户友好的中文短句，杜绝向 Snackbar 暴露内部路径或异常堆栈。

### 功能扩展 *(规划中)*

- 书签、笔记、高亮选段（v6 候选）。
- 持久化搜索索引（SQLite FTS / 离线倒排，v6 候选）：v4 章内搜索 ✅、v5 全书搜索 ✅ 已落地，
  下一步面向大部头书的搜索性能优化。
- 多书架 / 收藏夹分组。
- 跨设备阅读进度同步。
- TTS 朗读（v6 候选）。

---

## 贡献指南

欢迎提 Issue 和 PR。完整流程请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)：

- 中文注释要求（每段业务逻辑都需说明"为什么"，而不仅是"做了什么"）
- 分支策略与提交信息约定
- PR 自检清单

---

## 许可证

License: **待定 (TBD)**。在确定授权方式之前，请将本仓库视作私有项目，外部使用请先与维护者沟通。

---

## 相关文档

- [CHANGELOG.md](CHANGELOG.md)：变更日志（Keep a Changelog 风格）。
- [CLAUDE.md](CLAUDE.md)：Claude Code 使用的项目结构说明（请勿手动修改）。
- [REVIEW_REPORT.md](REVIEW_REPORT.md)：首轮历史代码审查报告。
- [REVIEW_REPORT_v2.md](REVIEW_REPORT_v2.md)：第二轮代码审查报告（P0 / P1 / P2 分级）。
- [FIX_REPORT.md](FIX_REPORT.md)：2026-05-24 修复执行报告。
- [TDD_REPORT.md](TDD_REPORT.md)：测试补强报告，含发现的生产 bug 清单。
