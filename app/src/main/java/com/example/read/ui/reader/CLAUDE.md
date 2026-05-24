# com.example.read.ui.reader -- 阅读器页面

## 包概述

阅读器页面，使用 WebView 渲染 EPUB 原始 HTML/CSS 内容，通过 JavaScript 实现精确分页。
提供沉浸式阅读体验：ViewPager2 翻页 + PageCurl 卷曲动画，点击内容区域切换顶栏/底栏。
支持 NCX 目录解析和目录底部弹出面板，可快速跳转到任意章节。

## 文件列表

| 文件 | 职责 |
|------|------|
| `ReaderScreen.kt` | 阅读器页面的 Composable UI + PagedChapterAdapter |
| `ReaderViewModel.kt` | 阅读器 ViewModel，管理全局页面流和阅读设置 |
| `ChapterWebViewFactory.kt` | WebView 工厂：创建、配置、加载章节 HTML |
| `WebViewPaginator.kt` | WebView 分页组件：注入 JS 计算页数 + AndroidBridge 回调 |
| `PageInfo.kt` | 全局页面信息数据类（chapterIndex + pageInChapter） |
| `ReadingSettings.kt` | 阅读设置数据类 + ReadingSettingsManager 持久化 |
| `ReadingSettingsDialog.kt` | 设置底部弹出面板：字号/行高/字体/背景色 |
| `PageCurlPageTransformer.kt` | 仿真卷曲翻页动画 |
| `TextSplitter.kt` | 文本分页工具（旧版 StaticLayout，保留兼容） |

## 关键类

### ReaderScreen

阅读器 Composable，参数：`bookId: Long`、`onBack: () -> Unit`。

**布局结构**：

- **TopAppBar**：带滑入/滑出动画，显示书名和章节标题，返回按钮 + 目录按钮 + 设置按钮
- **BottomAppBar**：带滑入/滑出动画，全局进度滑块 + 页码指示 + 上一章/下一章按钮
- **内容区域**：`ViewPager2` + `AndroidView`，每页是一个 WebView 渲染的章节页面
- **目录底部弹出面板**：`ModalBottomSheet` 展示章节目录列表，支持层级缩进、当前章节高亮、自动滚动到当前章节

**WebView 生命周期管理**：
- 每个章节使用独立的 WebView 实例，通过 `webViewCache` 缓存复用
- 设置变更时对所有缓存的 WebView 更新 CSS
- Composable 销毁时通过 `DisposableEffect` 销毁所有 WebView

**交互设计**：
- 触摸事件区分点击和滑动：左 1/4 翻前页，右 1/4 翻后页，中间 1/2 切换工具栏
- 顶栏/底栏：`AnimatedVisibility` + `slideInVertically/slideOutVertically` 动画
- 目录按钮点击展开 `ModalBottomSheet`，显示从 NCX 解析的章节目录
- 目录项按 `level` 字段缩进显示层级关系，当前阅读章节高亮标记
- 点击目录项调用 `jumpToChapter()` 跳转到对应章节

### PagedChapterAdapter

ViewPager2 的 RecyclerView.Adapter，将全局页面列表渲染为 WebView。

**核心设计**：
- 每个 page 对应一个 `PageInfo`（chapterIndex + pageInChapter）
- 每个章节使用独立的 WebView 实例，通过 `webViewCache` 缓存复用
- 绑定时获取或创建 WebView，添加到 FrameLayout 容器并滚动到目标页
- 回收时从容器中移除 WebView（不销毁，可能被其他 page 复用）

### ChapterWebViewFactory

WebView 工厂，负责创建和配置用于章节 HTML 渲染的 WebView 实例。

**方法**：
- `create()`: 创建配置好的 WebView（JS 启用、file 访问、禁用缩放）
- `loadHtml()`: 加载章节 HTML，baseUrl 指向 OPF 目录，注入分页 CSS 和 JS
- `updateCSS()`: 动态更新阅读样式并重新计算页数
- `scrollToPage()`: 滚动到指定页面

### WebViewPaginator

WebView 分页核心组件，向 WebView 注入 CSS 和 JavaScript 实现精确分页。

**分页原理**：
- WebView 加载 HTML 后，JavaScript 测量 `document.scrollHeight` 和 `window.innerHeight`
- 页数 = `ceil(scrollHeight / innerHeight)`
- 翻页时通过 `window.scrollTo(0, pageIndex * innerHeight)` 滚动到目标页
- `PaginationBridge`（JavascriptInterface）接收页数回调

### ReaderViewModel

`@HiltViewModel`，通过 `SavedStateHandle.toRoute<Reader>()` 获取 `bookId`。

**状态管理**：

| 状态 | 类型 | 说明 |
|------|------|------|
| `book` | `StateFlow<Book?>` | 当前书籍元数据 |
| `chapterIndex` | `StateFlow<Int>` | 当前章节索引（0-based） |
| `chapterTitle` | `StateFlow<String>` | 当前章节标题 |
| `globalPages` | `StateFlow<List<PageInfo>>` | 全局连续页面列表 |
| `currentGlobalPage` | `StateFlow<Int>` | 当前全局页码（0-based） |
| `currentPageCount` | `StateFlow<Int>` | 当前章节总页数（JS 回调更新） |
| `chapterPageCounts` | `StateFlow<Map<Int, Int>>` | 各章节页数缓存 |
| `readingSettings` | `StateFlow<ReadingSettings>` | 当前阅读设置 |
| `tocItems` | `StateFlow<List<TocItem>>` | NCX 目录条目列表，供 TOC 底部弹出面板使用 |
| `isLoading` | `StateFlow<Boolean>` | 加载状态 |

**关键流程**：
- `init { loadBook() }` -- 加载书籍元数据，恢复到上次阅读章节和章内页码
- `onPageCountReady(chapterIndex, pageCount)` -- JS 回调，更新页数缓存，重建 globalPages
- `nextPage() / previousPage()` -- 全局翻页，跨章节时自动 syncChapterState
- `preloadAdjacentChapters()` -- 后台预加载前后各 1 章的页数
- `updateSettings()` -- 清空页数缓存，触发重新分页
- `jumpToChapter(chapterIndex)` -- 跳转到指定章节，用于目录导航

**进度保存策略**：
- 章节索引：通过 `repository.updateReadingProgress` 保存到 Room
- 章内页码：通过 SharedPreferences 按 `bookId+chapterIndex` 保存
- ViewModel 销毁时（`onCleared`）保存当前章内页码

## 依赖关系

- **依赖**：`domain.model.Book`、`domain.repository.BookRepository`、`ui.navigation.Reader`（路由定义）、`util.BookMetadata`
- **被依赖**：`ui.navigation.NavGraph`（在导航图中注册 ReaderScreen）

## 编码规范

- `bookId` 从 `SavedStateHandle` 提取，使用类型安全路由 `toRoute<Reader>()`
- WebView 回调在 WebView 内部线程触发，通过 `Handler(Looper.getMainLooper())` 切换到主线程
- 全局页面流由 `rebuildGlobalPages()` 根据 `chapterPageCounts` 缓存构建
- 阅读进度实时保存：章节索引存 Room，章内页码存 SharedPreferences
- WebView 生命周期由 Composable 管理，DisposableEffect 确保销毁时清理
