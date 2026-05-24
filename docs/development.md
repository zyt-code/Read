# 开发指南

本文档面向准备在本仓库贡献代码的开发者，覆盖环境搭建、常用命令、调试技巧与编码规范。

---

## 目录

- [本地环境搭建](#本地环境搭建)
- [常用 Gradle 命令](#常用-gradle-命令)
- [调试技巧](#调试技巧)
- [ProGuard / R8](#proguard--r8)
- [编码规范](#编码规范)
- [提交信息约定](#提交信息约定)

> 调试技巧子小节：WebView 远程调试、Room 数据库查看、Hilt 编译问题排查、设备日志、调试 Reader 崩溃（P1-2）、**调试章节内搜索（v4）**、**调试全书搜索（v5）**、Compose 重组追踪。

---

## 本地环境搭建

### 1. 安装 JDK 17+

推荐使用 Temurin / Zulu / Oracle 任一发行版的 JDK 17 或 21。

```bash
# 检查版本
java -version
javac -version
```

如果系统中有多个 JDK，建议把 `JAVA_HOME` 指向 JDK 17：

```bash
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17"

# macOS / Linux
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS
export JAVA_HOME=/usr/lib/jvm/temurin-17-jdk-amd64 # Ubuntu
```

### 2. 安装 Android Studio

- 建议版本：Android Studio Ladybug **2024.2.1** 或更高。
- 通过 **Tools → SDK Manager** 安装：
  - **Android SDK Platform 35**
  - **Android SDK Build-Tools 35.x**
  - **Android Emulator** + **Platform-Tools**
  - 任意一个 API 26+ 的系统映像（用于模拟器测试）

### 3. 克隆并打开项目

```bash
git clone <repo-url>
cd read
```

用 Android Studio 选择 **Open** 打开仓库根目录。等待 Gradle Sync 完成后，IDE 会提示是否同意自动下载 Gradle 8.11.1（由 `gradle/wrapper/gradle-wrapper.properties:3` 指定）。

### 4. 准备 Gradle Wrapper

仓库已包含 `gradle/wrapper/gradle-wrapper.jar` 与 `gradle-wrapper.properties`，无需重新生成。若误删，可执行：

```bash
gradle wrapper --gradle-version 8.11.1
```

### 5. 首次构建

```bash
./gradlew :app:assembleDebug
```

首次构建会下载所有依赖（Compose BOM、Hilt、Room、Coil、Jsoup、ViewPager2、kotlinx-serialization 等），耗时取决于网络。

---

## 常用 Gradle 命令

| 命令 | 用途 |
|------|------|
| `./gradlew tasks` | 列出所有可用任务 |
| `./gradlew :app:assembleDebug` | 构建 Debug APK，输出到 `app/build/outputs/apk/debug/` |
| `./gradlew :app:assembleRelease` | 构建 Release APK（已开启 `isMinifyEnabled = true`，需配置签名） |
| `./gradlew :app:installDebug` | 将 Debug APK 安装到已连接的设备 |
| `./gradlew :app:uninstallDebug` | 卸载 Debug 包 |
| `./gradlew test` | 运行所有 JVM 单元测试（`app/src/test/`） |
| `./gradlew :app:testDebugUnitTest --tests "*EpubParserTest"` | 运行特定测试类 |
| `./gradlew connectedAndroidTest` | 运行 Android 仪器化测试（需要连接设备） |
| `./gradlew lint` | 运行 Android Lint，结果在 `app/build/reports/lint-results-debug.html` |
| `./gradlew clean` | 清理 `build/` 目录 |
| `./gradlew dependencies --configuration releaseRuntimeClasspath` | 查看依赖树 |

> Windows PowerShell 用户请改用 `./gradlew.bat`，或在 Git Bash / WSL 中使用 `./gradlew`。

---

## 调试技巧

### WebView 远程调试

阅读器的所有文本渲染都在 WebView 中。可以用 Chrome 远程调试器检查 DOM、CSS 和 JavaScript。

启用方式：在 `ChapterWebViewFactory.create()`（`app/src/main/java/com/example/read/ui/reader/ChapterWebViewFactory.kt:60`）中临时添加：

```kotlin
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
```

> 仅在 Debug 包启用，避免线上版本被劫持调试。

启用后在 Chrome 中打开 `chrome://inspect/#devices`，连接设备即可看到运行中的 WebView。可以：

- 查看 `PAGINATION_JS` 注入后的 `window.calculatePages` 函数
- 在 Console 手动调用 `calculatePages()` / `scrollToPage(idx)`
- 检查 `body` 的 `scrollHeight` 与 `window.innerHeight`，验证分页计算是否准确
- 调整 `updateCSS()` 测试不同样式

> **2026-05-24 安全模型变更（P0-3）**：WebView 的 baseUrl 从 `file://${bookDirPath}/${opfDir}/` 切换到 `https://appassets.androidplatform.net/epub/<opfDir>/`，所有资源通过 `WebViewAssetLoader.InternalStoragePathHandler` 代理读取。因此在 Chrome `inspect` 中：
>
> - **URL 栏不再看到 `file://` 字样**，而是 `https://appassets.androidplatform.net/epub/...`。
> - DevTools 的 **Sources** 面板把 EPUB 资源归到该 https origin 下，DevTools Network 面板能看到每个资源被 `shouldInterceptRequest` 拦截后的真实响应。
> - 试图通过 DevTools console 执行 `fetch("file:///data/data/com.example.read/databases/read.db")` 会得到 `net::ERR_ACCESS_DENIED`，这是预期行为（`setAllowFileAccess(false)`）。
> - `window.AndroidBridge.onPageCountReady(...)` 需要传入正确的 nonce 才会被 Kotlin 端接受；nonce 嵌在每次注入的 IIFE 闭包中，Console 直接调用回调（不带匹配的 nonce）会被静默丢弃。这是 P0-3 防伪造回调的设计。

### Room 数据库查看

应用使用 SQLite 文件 `read.db`，位于：

```
/data/data/com.example.read/databases/read.db
```

#### 方式 1：Android Studio Database Inspector（推荐）

1. 用 Debug 包启动应用并保持运行。
2. **View → Tool Windows → App Inspection**。
3. 选择 Database Inspector → 进程 `com.example.read` → 数据库 `read.db`。
4. 双击表名查看记录，支持实时 SQL 执行。

#### 方式 2：导出 .db 文件

```bash
adb root                           # 需要 userdebug / eng 系统（模拟器默认可用）
adb pull /data/data/com.example.read/databases/read.db ./read.db
```

然后用 [DB Browser for SQLite](https://sqlitebrowser.org/) 打开。

#### 方式 3：Room schema 比对

Room 启用了 `exportSchema = true`（`app/build.gradle.kts:48`），版本变更后的 JSON 会写入 `app/schemas/com.example.read.data.local.AppDatabase/<version>.json`。提交 PR 修改 `BookEntity` 时务必将新生成的 schema 一并提交，便于回归。

### Hilt 编译问题排查

KSP 注解处理在编译期生成 Hilt 组件，常见报错：

| 错误 | 原因 | 处理 |
|------|------|------|
| `@HiltViewModel must be public` | ViewModel 加了 `private` 修饰符 | 移除 `private`，保持默认 public |
| `Missing binding for ...` | 接口没有 `@Provides` / `@Binds` | 在 `AppModule` 中补充 `@Provides` |
| `Cannot inject member into ...` | 类没有 `@AndroidEntryPoint` 或 `@HiltViewModel` 标注 | 检查注解 |
| KSP 缓存过期导致鬼报错 | 增量编译命中错误中间产物 | `./gradlew clean` 后重试 |

排查时可以打开详细日志：

```bash
./gradlew :app:kspDebugKotlin --info --stacktrace
```

### 设备日志

```bash
# 按 tag 过滤
adb logcat -s ReadApplication BookshelfViewModel ReaderViewModel

# 仅看 W 及以上级别
adb logcat *:W
```

代码中已存在的日志 tag：

- `BookshelfViewModel` -- 见 `app/src/main/java/com/example/read/ui/bookshelf/BookshelfViewModel.kt:45`。
- `ChapterWebViewFactory` -- P1-2 在 `loadHtml` 入口短路时通过 `Log.w("ChapterWebViewFactory", ...)` 打印，见下方"调试 Reader 崩溃"小节。
- `PagedChapterAdapter` -- P1-2 在 adapter try-catch 兜底分支通过 `Log.w("PagedChapterAdapter", ...)` 打印。

### 调试 Reader 崩溃（P1-2）

`WebViewAssetLoader.InternalStoragePathHandler` 在构造时强制校验 `directory` 必须位于 `context.getDataDir()` 子目录内，对 `""` / `"PREPARING_xxx"` / 已删除目录均会抛 `IllegalArgumentException`。该异常发生在 `mainHandler.post {}` 中，直接传到主线程会让阅读器开屏崩溃。

P1-2 修复后，`ChapterWebViewFactory.loadHtml`（`app/src/main/java/com/example/read/ui/reader/ChapterWebViewFactory.kt:121-157`）和 `PagedChapterAdapter.getOrCreateWebView`（`app/src/main/java/com/example/read/ui/reader/ReaderScreen.kt:686-723`）增加了三态校验 + try-catch 双重防御，异常 `bookDirPath` 不再崩溃，而是走兜底分支显示空白页。**排查路径**：

```bash
# 1. 过滤 P1-2 相关日志（两个 tag 都是 W 级）
adb logcat *:S ChapterWebViewFactory:W PagedChapterAdapter:W

# 可能看到的输出形态：
# W/ChapterWebViewFactory: loadHtml skipped: bookDirPath not ready ()                    ← 空字符串
# W/ChapterWebViewFactory: loadHtml skipped: bookDirPath not ready (PREPARING_<uuid>)    ← 占位记录
# W/ChapterWebViewFactory: loadHtml skipped: bookDir not found (/data/.../books/42)      ← 目录被外部删除
# W/PagedChapterAdapter: loadHtml threw IllegalArgumentException (bookDirPath=...)       ← 未来 WebViewAssetLoader 版本变更兜底
# W/PagedChapterAdapter: loadHtml threw unexpected exception                              ← 任意 Throwable
```

排查步骤：

1. 用户反馈"打开某本书白屏"时，先看 `ChapterWebViewFactory` 标签：若出现 `bookDirPath not ready`，说明仍在导入流程或书架展示了 PREPARING_ 占位（理论上 P1-1 已过滤，若复现请检查是否绕过书架 UI 进入）。
2. 若出现 `bookDir not found`，说明 `bookDirPath` 指向已删除目录。在 Database Inspector 检查对应 `bookId` 的记录 → 用户可能用 `adb shell rm -rf` 手动清理过，或外部存储被回收。建议从书架长按删除该记录后重新导入。
3. 若出现 `PagedChapterAdapter` 标签的 IAE，说明命中了"`loadHtml` 入口校验通过但 `WebViewAssetLoader` 内部仍抛异常"的兜底分支。这是 P1-2 预防性兜底（对应未来 androidx.webkit 版本变更或新设备行为差异），需在 logcat 看完整 stacktrace 定位具体原因。
4. 若出现 `loadHtml threw unexpected exception`，是 `addJavascriptInterface` / `loadDataWithBaseURL` 在某些 OEM 设备上的非预期异常。同样看堆栈定位。

> 关闭兜底（仅调试时）：把 `ChapterWebViewFactory.loadHtml` 第 139 行的 `if (bookDirPath.isEmpty() ...) { ... return }` 暂时注释掉，可重现真实 IAE 堆栈帮助定位上游问题；调试完务必还原。

### 调试章节内搜索（v4）

v4 引入的章节内搜索通过 `evaluateJavascript` 注入 `FindInPageJs.FIND_IN_PAGE_JS`（`app/src/main/java/com/example/read/ui/reader/FindInPageJs.kt`），在 DOM 层用 `<mark class="reader-find">` 重写匹配文本节点；当前选中项再加 `reader-find-current` class。该路径不依赖 `AndroidBridge`（不走 nonce 校验），所有结果通过 `ValueCallback` 字符串数字回传。

#### 1. 用 Chrome inspect 查看注入的 `mark.reader-find` DOM

启用 `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)` 后（见上文"WebView 远程调试"），在 Chrome 中打开 `chrome://inspect/#devices`，连接设备的 WebView：

```js
// 在 DevTools Console 中验证 window.ReaderFind 是否已注册
typeof window.ReaderFind   // 期望: "object"
window.ReaderFind.count()  // 期望: 当前章节匹配总数

// 手动触发查询（绕过 Kotlin 端）
window.ReaderFind.find("the")

// 查看 mark DOM
document.querySelectorAll("mark.reader-find")            // 全部高亮节点
document.querySelectorAll("mark.reader-find-current")    // 当前选中节点（应该只有 1 个）

// 清空再验证 DOM 复原
window.ReaderFind.clear()
document.querySelectorAll("mark.reader-find").length     // 期望: 0
```

`clear()` 在 JS 端通过 `parent.replaceChild(textNode, mark)` 把文本节点还原，再调用 `parent.normalize()` 合并相邻 TextNode，保证 DOM 与初始态等价（详见 `FindInPageJs.kt:46-61`）。如果 `document.querySelectorAll("mark.reader-find").length` 在多次 find / clear 后**不归零**，说明出现 DOM 累积，需要在 `FindInPageJs` 中加日志排查。

#### 2. 验证 CSS 跟随主题切换（白 / 护眼 / 暗黑）

`FIND_IN_PAGE_CSS` 由 `ReadingSettings.toReaderCss()` 在末尾拼接（`ReadingSettings.kt:134`），主题切换会触发 WebView 的 `updateCSS(escapedCss)` 重新注入。验证步骤：

1. 进入阅读器 → 点 🔍 → 输入关键词，确认黄色 / 橙色高亮可见
2. 切到设置面板换背景（白 / 护眼 / 暗黑）→ ViewModel 会自动 `exitFindMode()`（搜索栏关闭、高亮清除）—— 这是设计意图，避免 controller 指向已被 LRU 重建的 WebView
3. 重新进入搜索，在新背景下确认 `#FFE066`（柔和黄）/ `#FF9933`（橙）对比度足够
4. 用 Chrome inspect 检查 mark 节点的 computed style 是否包含 `background-color` 与 `color: inherit`

```js
// DevTools Console
var m = document.querySelector("mark.reader-find");
getComputedStyle(m).backgroundColor    // 期望: rgb(255, 224, 102) 或近似
getComputedStyle(m).color              // 期望: 继承自正文（不应为浏览器默认黑）
```

#### 3. 看 JS 异常 / `evaluateJavascript` 回调

`FindInPageController.parseIntOrZero`（`FindInPageController.kt:105`）对 `evaluateJavascript` 的回调字符串做防御性解析：

| ValueCallback 收到 | parseIntOrZero 返回 | 含义 |
|--------------------|---------------------|------|
| `"12"` | `12` | 12 个匹配 |
| `"-1"` | `-1` | next/prev 时表示当前章节无匹配 |
| `"null"` | `defaultValue`（0 或 -1） | `window.ReaderFind` 未注册（onPageFinished 早于注入完成） |
| `null` | `defaultValue` | WebView 已 destroy 或 JS 抛异常 |
| `"\"abc\""` | `defaultValue` | 非数字字符串（理论上不会发生） |

排查路径：

```bash
# 1. 看 JS 异常（Chrome WebView 把未捕获异常以 ConsoleMessage 抛到 logcat）
adb logcat -s chromium:* ConsoleMessage:V

# 在 FindInPageJs.FIND_IN_PAGE_JS 中临时插入 console.log("ReaderFind.find called", query) 可以观察调用频率。

# 2. 若 UI 显示 "0 / 0" 但用户期望有匹配，常见原因：
#    a) onPageFinished 早于 FIND_IN_PAGE_JS 注入完成（window.ReaderFind 还未注册）
#       → evaluateJavascript 用了 `window.ReaderFind && ...` 短路防御，ValueCallback 返回 "null"，
#         parseIntOrZero 回退到 0/-1。等几百毫秒重新输入即可
#    b) WebView 被 LRU 淘汰但 attachFindController 没及时替换 → 看 logcat 找最近的 onChapterHtmlLoaded
#    c) 关键词跨内联元素被切断（如 a<em>b</em>c 中搜索 "abc"）→ 已知限制，详见 docs/architecture.md "章节内搜索 / 已知限制"
```

> **提示**：FindInPage 路径**不会触发 `AndroidBridge.onPageCountReady` 的 nonce 校验**，logcat 中不会看到 `WebViewPaginator` 的相关日志。整条数据路径完全在 `evaluateJavascript` 的 `ValueCallback` 上，不经过 JS 桥。

### 调试全书搜索（v5）

v5 主线引入跨章节全书搜索（`BookSearchEngine` + `ReaderViewModel.searchWholeBook`）。
数据路径完全在 Kotlin / Repository 层：`ReaderViewModel.bookSearchJob` →
`BookSearchEngine.search` → `flatMapMerge(concurrency=4)` 并发拉取章节纯文本 →
`Jsoup.text()` + `String.indexOf` 链式计数 → `SearchResult` 列表写回 `_bookSearchResults`。
WebView 仅在用户点击结果跳转后通过 v4 章内搜索路径完成定位高亮。

#### 1. Android Studio Profiler 观察并发协程

在 Android Studio **Profiler → CPU 录制 → 选 "Trace System Calls" 或 "Trace Java Methods"**，
然后在 app 中触发一次全书搜索（输入 2+ 字符的关键词），观察并发情况：

- 期望看到最多 **4 路并发协程**同时调用 `BookRepositoryImpl.getChapterPlainText`
  （由 `flatMapMerge(concurrency = 4)` 限制）
- 每路协程会执行 `bookDao.getBookById` → `readMetadata` → `htmlFile.readText` →
  `Jsoup.parse(html).text()`，IO 占比应在 70%+
- 若发现 > 4 路并发，检查是否有人改了 `BookSearchEngine.MAX_CHAPTER_CONCURRENCY`；
  若只有 1 路串行，说明 `asFlow().flatMapMerge` 没生效，检查 `@OptIn(ExperimentalCoroutinesApi::class)`

#### 2. logcat 查看 BookSearchEngine 的取消日志

搜索任务的取消路径：用户连续输入新 query → `ReaderViewModel.searchWholeBook` 内
`bookSearchJob?.cancel()` → `flatMapMerge` 内部协程被取消 → `searchOneChapter` 内的
`catch (CancellationException) { throw e }` 重抛 → 引擎结束。

```bash
# 过滤搜索相关日志（BookSearchEngine 当前无显式 Log，但 ReaderViewModel 的异常路径会进 logcat）
adb logcat -s ReaderViewModel:*

# 期望的行为序列：
# 1. 输入 "a"  → length<2 不触发引擎，bookSearchInProgress = false
# 2. 输入 "ab" → bookSearchInProgress = true → 引擎搜索 → bookSearchInProgress = false
# 3. 在 2) 完成前输入 "abc"  → 旧任务被 cancel（不会写 _bookSearchResults），新任务接管

# 如果想加临时调试日志，可在 BookSearchEngine.search 入口 / searchOneChapter 入口
# 加 android.util.Log.d("BookSearchEngine", "search start: $query / ch=$chapterIndex")
# 验证完务必移除
```

#### 3. 在 Chrome inspect 里手动验证 navigate() JSON 返回

P1-v5-2 升级 `window.ReaderFind.next/prev` 返回值为 JSON `{"index":i,"page":p}`。
可以在跳转到匹配所在章节后在 Chrome DevTools Console 手动调用验证：

```js
// 先 find 让 matches 非空
window.ReaderFind.find("the")            // 期望: Number（如 12）

// 调 next 返回 JSON 字符串
window.ReaderFind.next()
// 期望: '{"index":1,"page":2}'  ← 第 2 个匹配位于章内第 3 页（0-based）

// 旧版纯数字返回（v4 兼容）已经被 navigate() JSON 覆盖；如果看到纯数字说明 JS 未刷新到 v5
// 解决：在阅读器中切到下一章再切回，会强制重建 WebView 并注入新 JS

// 验证 pageOfMatch 计算逻辑：
var marks = document.querySelectorAll("mark.reader-find");
var m = marks[1];
var rect = m.getBoundingClientRect();
var absoluteTop = rect.top + window.scrollY;
var page = Math.floor(absoluteTop / window.innerHeight);
console.log("expected pageInChapter:", page);   // 应与 navigate 返回的 page 一致
```

如果 Kotlin 端 ViewPager2 没有翻到匹配所在章内页（P1-v5-2 修复目标），排查顺序：

1. logcat 看 `FindInPageController.parseNavigateResult` 是否抛 JSONException（理论上有兜底，但可加临时日志）
2. 在 `ReaderViewModel.onFindMatchLocated` 加 `Log.d("FindLocate", "pageInChapter=$pageInChapter, targetGlobal=$targetGlobal")`
3. 检查 `globalPages` 是否覆盖目标章（相邻章节预加载未完成时可能查不到全局页 → `findGlobalPageByPageInChapter` 返回 -1 → 静默忽略）

### Compose 重组追踪

在 `app/build.gradle.kts` 临时添加 Compose Compiler 报告：

```kotlin
kotlin {
    sourceSets.configureEach {
        languageSettings.optIn("kotlin.RequiresOptIn")
    }
}
```

或使用 Layout Inspector（**Tools → Layout Inspector**）连接到运行中的 Debug 进程，开启"Show recompositions"实时观察哪些 Composable 被频繁重组。

---

## ProGuard / R8

### 为什么 release 必须有 `proguard-rules.pro`

`app/build.gradle.kts:25-31` 在 `release` 构建上启用了 `isMinifyEnabled = true`，由 R8 执行代码缩减、混淆与优化。项目同时使用以下需要保留特定符号的库：

| 库 | 风险 | 缺失 keep 规则的症状 |
|----|------|----------------------|
| **kotlinx.serialization** | 通过反射访问 `Companion` / `serializer()` 与编译生成的 `$serializer` 内部类 | 启动或首次反序列化时抛 `SerializationException: Serializer for class 'BookMetadata' is not found` |
| **Room** | 编译期生成 `*_Impl` 类、扫描 `@Entity` / `@Dao` | 应用启动时抛 `RuntimeException: cannot find implementation for AppDatabase` |
| **Hilt / Dagger** | 生成 `_HiltModules*` / `_HiltComponents` / `*_Factory` 大量类 | `@HiltViewModel` 注入失败：`IllegalStateException: Cannot create an instance of class ViewModel` |
| **@JavascriptInterface** | WebView JS 通过反射调用 Kotlin 方法 | `Uncaught TypeError: window.AndroidBridge.onPageCountReady is not a function` |
| **Navigation Compose 类型安全路由** | `@Serializable data object Bookshelf` / `data class Reader(...)` 通过反射读取 kotlinx-serialization 描述符 | 跳转 `Reader` 时抛 `MissingFieldException: Field 'bookId' is required` |
| **Coil 3** | 反射加载 image decoder | 封面无法加载，logcat 显示 decoder 类找不到 |

2026-05-24 之前的版本 `proguard-rules.pro` 只有 Jsoup 的几行规则，release 构建无法启动；本批次重写为完整 keep 规则。

### 关键 keep 规则的作用范围

`app/proguard-rules.pro` 按以下分组组织：

| 段落 | 关键规则 | 作用 |
|------|----------|------|
| 通用 | `-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable` | 保留运行时注解 + 异常堆栈定位信息 |
| Kotlin 反射 | `-keep class kotlin.Metadata { *; }` | kotlinx.serialization 依赖 |
| kotlinx.serialization | `-keep,includedescriptorclasses class **$$serializer { *; }`、`-keep @kotlinx.serialization.Serializable class * { *; }`、对 `BookMetadata` / `SpineItem` / `TocItem` 的显式 keep | 保证 `metadata.json` 反序列化和 Navigation 类型安全路由可用 |
| Navigation | `-keep class com.example.read.ui.navigation.** { *; }` | 路由 `@Serializable` 数据类不被混淆 |
| Room | `-keep class * extends androidx.room.RoomDatabase { *; }`、对 `AppDatabase_Impl` / `BookDao_Impl` 的显式 keep | Room 编译期生成类不被剥离 |
| Hilt | `-keep class dagger.hilt.** { *; }`、`-keep class com.example.read.ReadApplication_HiltComponents** { *; }` | Hilt 反射查找 component 与 factory |
| WebView Bridge | `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` + 对 `WebViewPaginator$PaginationBridge` 的显式 keep | `@JavascriptInterface` 方法签名不能被改名 |
| Coil 3 | `-keep class coil3.** { *; }` | 反射 decoder |
| Jsoup | `-keep class org.jsoup.** { *; }` | NCX/OPF XML 解析 |

> 规则偏保守：可能让 release APK 比理论最小尺寸略大几十 KB，但显著降低运行时崩溃概率。后续如果需要进一步缩减，建议先通过 `./gradlew :app:assembleRelease --info` 看 R8 报告再针对性收紧。

### 升级依赖后如何验证 release 构建

每次升级 kotlinx.serialization、Room、Hilt、Navigation Compose、Coil 任一项后，按下列流程验证：

```bash
# 1. 清理一次旧 R8 缓存
./gradlew clean

# 2. 构建 release 包（需要本地配置签名，或临时关闭签名校验）
./gradlew :app:assembleRelease

# 3. 安装并冒烟
./gradlew :app:installRelease
```

冒烟用例至少覆盖：

1. **首次启动**：书架页面应正常显示空状态（验证 Hilt + Room）。
2. **导入一本 EPUB**：验证 kotlinx.serialization 反序列化 `metadata.json`。
3. **打开阅读器并翻页**：验证 `@JavascriptInterface` 桥 + WebView。
4. **修改设置并切换章节**：验证 `WebViewAssetLoader` + 路由参数传递。

如果 R8 报错形如 `Missing class kotlinx.serialization.internal.PluginGeneratedSerialDescriptor`，多半是 kotlinx.serialization 升级后内部类改名，按报错补 `-keep` 规则即可。

---

## 编码规范

### Kotlin 风格

- 遵循 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)。
- 行宽不超过 120 字符。
- 使用 4 空格缩进（与 Android Studio 默认一致）。
- 不使用通配 `import *`（`import com.example.read.*`），保留显式 import。
- `data class` 用于纯数据载体；不要为它写 `equals` / `hashCode` 之外的复杂逻辑。

### 中文注释规范（强制）

CLAUDE.md 中明确要求 **"每段代码都要有完整的中文业务逻辑注释"**。本仓库的注释风格是：

1. **类级注释**：用 KDoc 说明类的职责、设计动机、与上下游模块的协作关系。
2. **方法级注释**：解释"为什么"，而非简单复述"是什么"。

   反例：

   ```kotlin
   // 设置 fontSize 为 18
   fontSize = 18f
   ```

   正例：

   ```kotlin
   // 默认字号 18sp，对应 Material 3 BodyLarge 推荐值，
   // 在 5.5-6.7 寸屏上 CJK 单行可容纳约 18-22 字，长文阅读不易疲劳
   fontSize = 18f
   ```

3. **关键步骤**：复杂业务流程的每一步都用 `// Step N:` 标注（参考 `BookRepositoryImpl.kt:53` 的导入流程）。
4. **风险点 / 兼容性**：涉及版本差异、线程安全、SQLite 限制等场景，必须显式标注，参考 `Migrations.kt:10`。

### 命名约定

| 元素 | 约定 | 示例 |
|------|------|------|
| 包名 | 全小写 | `com.example.read.ui.reader` |
| 类 / 接口 / object | UpperCamelCase | `BookRepositoryImpl`、`ReadingSettingsManager` |
| 函数 / 属性 | lowerCamelCase | `getAllBooks`、`bookDirPath` |
| 常量 | UPPER_SNAKE_CASE，置于 `companion object` | `MAX_ZIP_ENTRY_SIZE` |
| Compose 函数 | UpperCamelCase（与组件名一致） | `BookCard`、`ReaderScreen` |
| 文件名 | 与主要类名一致 | `BookEntity.kt` 内包含 `BookEntity` |
| Room Entity 字段 | 直接对应数据库列名，使用 lowerCamelCase | `lastReadChapter` |

### Compose 最佳实践

1. **状态尽量上提到 ViewModel**：避免在 Composable 内用 `remember { mutableStateOf(...) }` 持有业务状态。UI 临时状态（如对话框可见性）才用 `remember`。
2. **使用 `collectAsState`** 桥接 StateFlow（注：当前实现使用 `collectAsState()`，路线图中将逐步替换为 `collectAsStateWithLifecycle()`）。
3. **`Modifier` 参数排在最后**，并提供默认值 `Modifier = Modifier`：

   ```kotlin
   @Composable
   fun BookCard(
       book: Book,
       onClick: () -> Unit,
       modifier: Modifier = Modifier,
   ) { /* ... */ }
   ```

4. **列表使用稳定 key**：`items(books, key = { it.id })`，避免重组时整列重建。
5. **副作用使用 `LaunchedEffect` / `DisposableEffect`**，不要在 Composable 函数体直接调用 `viewModel.someSuspendCall()`。
6. **WebView 等命令式视图**：通过 `AndroidView` 嵌入，并在 `onRelease` / `DisposableEffect` 中 `webView.destroy()` 防止内存泄漏。

### 架构红线

- 领域层（`domain/`）不得 `import android.*` 或 `androidx.room.*`。
- ViewModel 不得直接访问 `BookRepositoryImpl` 或 `BookDao`，必须经由 `BookRepository` 接口。
- 不要在 `BookEntity` 中加任何业务方法，只允许 `toDomain()` / `toEntity()` 这种纯映射。
- Repository 不返回 `BookEntity`，统一返回 `Book`。
- Compose Screen 不直接调用 Repository，必须经由 ViewModel。

### Room 修改流程

修改 `BookEntity` 字段时：

1. 在 `BookEntity.kt` 修改字段定义并同步 `toDomain()` / `toEntity()`。
2. 在 `AppDatabase.kt:17` 把 `version = N` 递增到 `N+1`。
3. 在 `Migrations.kt` 新增 `MIGRATION_N_N+1`，参考 `MIGRATION_1_2`。
4. 在 `AppModule.kt:42` 通过 `.addMigrations(MIGRATION_N_N+1)` 注册。
5. 执行 `./gradlew :app:assembleDebug`，确认 `app/schemas/com.example.read.data.local.AppDatabase/{N+1}.json` 被生成，提交到仓库。
6. 在 `app/src/androidTest/` 添加迁移测试（参考 Room `MigrationTestHelper`）。

---

## 提交信息约定

使用类似 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/) 风格，但简化为以下前缀：

| 前缀 | 用途 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat(reader): 增加目录底部弹出面板` |
| `fix` | Bug 修复 | `fix(import): 中文标题导致封面文件名冲突` |
| `refactor` | 重构（不改变外部行为） | `refactor(repo): 提取 readMetadata 公共方法` |
| `perf` | 性能优化 | `perf(parser): 复用 StaticLayout 减少分页耗时` |
| `docs` | 文档变更 | `docs: 补充阅读进度恢复流程图` |
| `test` | 测试相关 | `test(reader): 增加章节切换边界测试` |
| `chore` | 构建 / 依赖 / 杂项 | `chore: 升级 Kotlin 至 2.1.0` |
| `style` | 代码格式（不影响逻辑） | `style: 调整 import 顺序` |

正文：

- 第一行 ≤ 72 字符，使用祈使句（"增加"/"修复"/"重构"），不加句号。
- 第二行空行，第三行起写详细说明（为什么改、怎么改、影响范围）。
- 引用 Issue 或 PR：`Closes #123` / `Refs #45`。

示例：

```
feat(reader): 增加阅读设置变更后的位置恢复

设置变更后清空 chapterPageCounts 触发重新分页，并在 WebView 重新
回调 onPageCountReady 时按章内页码恢复，避免用户调整字号后丢失阅读位置。

Refs #42
```

---

## 相关文档

- [README.md](../README.md)：项目快速概览。
- [docs/architecture.md](architecture.md)：分层架构与数据流。
- [docs/testing.md](testing.md)：测试策略。
- [docs/data-model.md](data-model.md)：Room schema 与文件系统布局。
- [CONTRIBUTING.md](../CONTRIBUTING.md)：贡献流程。
