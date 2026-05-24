# 测试策略

本文档说明 Read 项目的测试组织、覆盖范围、Mock 策略和新测试的编写指南。

---

## 目录

- [测试金字塔](#测试金字塔)
- [单元测试覆盖范围](#单元测试覆盖范围)
- [集成测试覆盖范围](#集成测试覆盖范围)
- [运行测试](#运行测试)
- [新增测试模板](#新增测试模板)
- [Mock 策略](#mock-策略)
- [测试依赖](#测试依赖)

---

## 测试金字塔

```
                  ▲ 仪器化测试（少量、慢）
                  │   app/src/androidTest/
                  │   - DAO 集成
                  │   - Repository 集成
                  │
                  │ 单元测试（多、快）
                  │   app/src/test/
                  │   - EpubParser、Mapper
                  │   - ViewModel 状态机
                  │   - 端到端 EPUB 解包验证
                  ▼
```

| 层级 | 数量（@Test 计） | 工具 | 运行环境 |
|------|------------------|------|----------|
| **JVM 单元测试** | 82 | JUnit 4 + MockK + Turbine + kotlinx-coroutines-test | 本机 JVM |
| **Android 仪器化测试** | 31 | JUnit 4 + Room 内存数据库 + Turbine | 真机 / 模拟器 |

测试边界：

- **只依赖 JVM** 的逻辑（数据映射、EPUB 解析、ViewModel 状态）放 `app/src/test/`。
- 依赖 **Android Framework**（Room SQLite、`Context`、`Looper`）的逻辑放 `app/src/androidTest/`。

---

## 单元测试覆盖范围

位于 `app/src/test/java/com/example/read/`：

| 测试类 | 覆盖范围 | 数量 |
|--------|---------|------|
| `util/EpubParserTest.kt` | container.xml 解析、OPF 解析、HTML 转纯文本、特殊字符 / 嵌套标签 / Unicode | 20 |
| `util/EpubFullFlowTest.kt` | EPUB 端到端解包：`unpack()` 流程、metadata.json 生成、spine/NCX 拼接 | 14 |
| `util/EpubParserSecurityTest.kt` | ZIP Slip 防护、ZIP 炸弹检测（声明大小 + 实际大小） | 8 |
| `data/local/entity/BookEntityMapperTest.kt` | `toDomain()` / `toEntity()` 双向映射、往返、边界值、特殊字符 | 11 |
| `data/repository/BookRepositoryImplTest.kt` | Repository 协调 DAO + 映射（纯 JVM，部分依赖 mock） | 6 |
| `ui/bookshelf/BookshelfViewModelTest.kt` | 状态管理、导入流程、删除、错误处理、`clearError()` 幂等性 | 8 |
| `ui/reader/ReaderViewModelTest.kt` | 书籍加载、章节切换、边界检查、进度保存、设置变更 | 15 |

### 2026-05-24 修复批次回归测试

本轮 fix 智能体并未直接新增测试文件，但所有 P0 / B 修复都依赖上一轮 TDD 补强的覆盖：

| 修复 | 覆盖测试 | 备注 |
|------|----------|------|
| B1（章节切换边界）| `ReaderViewModelTest.nextChapter_atLastChapter_doesNotAdvance` | 既有用例 |
| B2（`updateSettings` 重置 `_currentGlobalPage`）| `ReaderViewModelExtraTest.should clear page cache and pending restore page when settings updated` | 上一轮新增 |
| B3（`normalizeNcxHref` 处理 `..`）| **建议补一条** `../ch1.xhtml` 用例（本轮 fix 未追加） | 强建议 |
| B5（`deleteBook` 返回值检查）| `BookRepositoryImplFsTest` 四组用例（不直接断言返回值，但覆盖调用路径） | 既有用例 |
| B6（预加载 5 秒超时 fallback）| **未覆盖**（需 Robolectric + 时间推进） | 强建议 |
| B8（导入异常时清理占位）| `BookshelfViewModelImportTest.startImport fails` 系列用例 | 上一轮新增 |
| P0-3 / P0-4（`escapeJsString` / `newNonce`）| **未覆盖**（接口已提取为 `companion object` 可单测） | 强建议 |
| P0-5（`PREPARING_` 前缀 vs 空字符串 vs 正常路径）| **未覆盖** | 强建议补集成测试 |

### 2026-05-24 Patch P1 修复批次回归测试

本轮针对 P1-1 / P1-2 / P1-3 的修复，TDD 智能体新增 / 调整的回归测试列表见 [TDD_REPORT_v3.md](../TDD_REPORT_v3.md)（占位，待 TDD 智能体补齐）。

**测试矩阵（占位）**：

| P1 修复 | 已落地的回归测试 | 测试文件 |
|--------|----------------|---------|
| P1-1 占位记录幽灵清理 | 参见 TDD_REPORT_v3.md（含 `BookRepositoryImplCleanupTest` 新增 2 个用例） | 参见 TDD_REPORT_v3.md |
| P1-2 阅读器对未就绪路径开屏崩 | 参见 TDD_REPORT_v3.md | 参见 TDD_REPORT_v3.md |
| P1-3 底栏章节按钮在未预加载时失灵 | 参见 TDD_REPORT_v3.md | 参见 TDD_REPORT_v3.md |

### 2026-05-24 v4 章节内搜索测试矩阵（占位）

v4 主线引入章节内搜索（find-in-page），TDD 智能体即将补齐的回归测试由 [TDD_REPORT_v4.md](../TDD_REPORT_v4.md)（占位，待 TDD 智能体补齐）跟踪。下表列出**建议**的测试文件名与覆盖范围，详细用例请以 TDD_REPORT_v4.md 为准：

| 范围 | 建议测试文件 | 覆盖点 |
|------|--------------|--------|
| `FindInPageController` 转义与 JS 拼装 | `app/src/test/java/com/example/read/ui/reader/FindInPageControllerTest.kt` | mock `WebView`，验证 `find / next / prev / clear` 调用 `evaluateJavascript` 时传入的 JS 字符串正确；验证 `parseIntOrZero` 对 `null` / `"null"` / `"5"` / `"\"abc\""` 的兜底；验证特殊字符（双引号、反斜杠、`</script>`、U+2028）被 `escapeJsString` 正确转义 |
| `ReaderViewModel` 搜索状态机 | `app/src/test/java/com/example/read/ui/reader/ReaderViewModelFindTest.kt` | `enterFindMode` 仅切换 `findActive=true` 不重置 query / count；`exitFindMode` 清空 4 个 StateFlow 且调用 `controller.clear()`；`updateFindQuery("")` 触发 clear 路径；`updateFindQuery("xx")` 在 `controller==null` 时仅更新 query 不崩溃；`syncChapterState` 跨章节时自动调用 `exitFindMode`（mock controller 断言 clear）；`updateSettings` 自动退出搜索模式 |
| `FindInPageJs` 常量内容 | `app/src/test/java/com/example/read/ui/reader/FindInPageJsConstantTest.kt` | `FIND_IN_PAGE_JS` 包含 `window.ReaderFind` / `reader-find` 关键标识；`FIND_IN_PAGE_CSS` 包含 `#FFE066` / `#FF9933` 两种背景色 |
| 真实 WebView 集成（可选） | `app/src/androidTest/java/com/example/read/ui/reader/FindInPageIntegrationTest.kt` | 真实 WebView 加载一段 HTML 后注入 `FIND_IN_PAGE_JS`，调用 `find` 返回正确匹配数；`clear()` 之后 `document.body.innerHTML` 与初始一致 |

完整测试建议清单（含手动验证步骤）详见 [FEAT_REPORT_v4.md](../FEAT_REPORT_v4.md) 的"测试建议"段；落地后请把以上表格中的"建议"改为对应的真实测试方法名并补充计数到[回归基线](#回归基线)。

### 2026-05-24 v5 跨章节搜索测试矩阵（占位）

v5 主线引入跨章节全书搜索 + 章内搜索 ViewPager2 联动 + `BookshelfViewModel.importBook` `CancellationException` 修复。
TDD 智能体即将补齐的回归测试由 [TDD_REPORT_v5.md](../TDD_REPORT_v5.md)（占位，待 TDD 智能体补齐）跟踪。
下表列出**本轮预期新增**的测试文件名与覆盖范围，详细用例请以 TDD_REPORT_v5.md 为准：

| 范围 | 建议测试文件 | 覆盖点 |
|------|--------------|--------|
| `BookSearchEngine` 引擎核心 | `app/src/test/java/com/example/read/data/search/BookSearchEngineTest.kt` | 空 query 返回空集合；单章命中；跨章 N 章命中；`getChapterPlainText` 返回 null 时跳过；`getBookMetadata` 抛异常时返回空集合；摘要前后省略号边界（开头 / 结尾匹配）；章节标题回退到"第 N 章"。FEAT_REPORT_v5.md §3.1 列出 7 个测试 |
| `FindInPageController` JSON 解析（升级） | `app/src/test/java/com/example/read/ui/reader/FindInPageControllerTest.kt` | 旧测试改用 `it.index`；新增 `should parse JSON navigate result and expose pageInChapter` 验证 v5 JSON 解析；`should fallback to NavigateResult(_, -1) when raw is plain number`（v4 兼容）；`should return NavigateResult(-1, -1) on null / JSON parse error`（兜底） |
| `ReaderViewModel` 全书搜索状态机 | `app/src/test/java/com/example/read/ui/reader/ReaderViewModelWholeBookSearchTest.kt` | `setSearchMode(WholeBook)` 清空 query/results/job；`searchWholeBook("a")` 长度 < 2 不触发引擎；`searchWholeBook("xx")` 写入 `_bookSearchResults`；连续输入 cancel 旧 Job；`onBookSearchResultClicked(result)` 触发 `jumpToChapter` + 设 `pendingFindAfterJump`；`attachFindController` 消费 `pendingFindAfterJump` → `controller.find(query)` 被调 |
| `ReaderViewModel` find 导航联动 | `app/src/test/java/com/example/read/ui/reader/ReaderViewModelFindNavigationTest.kt` | `findNext` 在 controller 返回 `NavigateResult(2, 1)` 时把 `_currentGlobalPage` 同步到 chapter 内 page 1 的全局索引；`onFindMatchLocated(-1)` 静默 no-op；`findCount==0` 时 `findNext` 不调 controller.next |
| `BookshelfViewModel` CancellationException 重抛 | `app/src/test/java/com/example/read/ui/bookshelf/BookshelfViewModelCancellationTest.kt`（建议） | mock repository.startImport 抛 `CancellationException`，断言：a) `importBook` 内 catch 块**没有**写 `_errorMessage`；b) `_importProgress` / `_placeholderBooks` 被清空；c) 异常重抛到 viewModelScope 后 Job 进入 `isCancelled` 状态。`init { cleanupOrphanedBooks }` / `deleteBook` 同步覆盖 |
| 集成测试（可选） | `app/src/androidTest/java/com/example/read/data/repository/BookRepositoryImplPlainTextTest.kt` | `getChapterPlainText` 对真实解包目录 + Jsoup 提取的 happy path；`PREPARING_` 前缀返回 null；不存在的 bookId / chapterIndex 越界返回 null |

### 回归基线

下表列出截至 2026-05-24（含 Patch P1）已经有回归测试守护的关键修复点。新增功能或修复时请补齐对应行，保持基线可追溯。

| 修复 ID | 简述 | 测试文件 | 测试方法 / 备注 |
|--------|------|---------|----------------|
| P0-5 | `cleanupOrphanedBooks` 跳过 `PREPARING_` 前缀 | `BookRepositoryImplCleanupTest`（重写） | 既有用例覆盖空字符串清理路径 |
| P1-1 | 占位记录幽灵清理（> 1h 阈值） | `BookRepositoryImplCleanupTest`（重写） | 新增 `should delete PREPARING records older than ghost threshold`、`should distinguish active and ghost PREPARING records`（参见 FIX_REPORT_v2.md §一·Fix-1） |
| P1-1 | `getAllBooks` SQL 过滤 `PREPARING_%`（UI 路径） | **未覆盖**（建议下一轮在 `BookDaoTest` 写一组真实 Room 用例） | 强建议 |
| P1-2 | `loadHtml` 入口三态校验 + adapter try-catch | **未覆盖**（建议 Robolectric / androidTest 测异常 bookDirPath 不抛 IAE） | 强建议 |
| P1-3 | `nextChapter` / `previousChapter` 未预加载兜底 | **未覆盖**（建议在 `ReaderViewModelBoundaryTest` 加一条 `should jump to next chapter when nextChapter called without preload`） | 强建议（FIX_REPORT_v2.md §一·Fix-3 给出了样板） |
| P1-NEW-1 | 书架导入进度环 UI（`placeholderBooks` StateFlow + `PlaceholderBookCard`） | **未覆盖**（建议在 `BookshelfViewModelImportTest` 加 `should expose placeholder during import lifecycle`，样板见 FIX_REPORT_v3.md §二·2.4） | 强建议 |
| P1-NEW-2 | `jumpToChapter` 未预加载分支 `_currentGlobalPage` 不闪回 | **未覆盖**（建议样板见 FIX_REPORT_v3.md §三·3.5） | 强建议 |
| v4 | `FindInPageController` 转义 + JS 拼装 | 建议 `FindInPageControllerTest` | 详见 [v4 测试矩阵](#2026-05-24-v4-章节内搜索测试矩阵占位) |
| v4 | `ReaderViewModel` 搜索状态机（enter / exit / updateQuery / next / prev） | 建议 `ReaderViewModelFindTest` | 详见 [v4 测试矩阵](#2026-05-24-v4-章节内搜索测试矩阵占位) |
| v4 | `FindInPageJs` / `FIND_IN_PAGE_CSS` 常量内容 | 建议 `FindInPageJsConstantTest` | 详见 [v4 测试矩阵](#2026-05-24-v4-章节内搜索测试矩阵占位) |
| P1-v5-1 | `BookshelfViewModel.importBook` / `init.cleanupOrphanedBooks` / `deleteBook` 三处重抛 `CancellationException` | 建议 `BookshelfViewModelCancellationTest` | 详见 [v5 测试矩阵](#2026-05-24-v5-跨章节搜索测试矩阵占位)；FEAT_REPORT_v5.md §1 |
| P1-v5-2 | `FindInPageController.parseNavigateResult` JSON 解析 + 旧版纯数字兜底 | 建议 `FindInPageControllerTest`（升级） | 详见 [v5 测试矩阵](#2026-05-24-v5-跨章节搜索测试矩阵占位)；FEAT_REPORT_v5.md §2 |
| P1-v5-2 | `ReaderViewModel.findNext/findPrev` 通过 `onFindMatchLocated` 写 `_currentGlobalPage` | 建议 `ReaderViewModelFindNavigationTest` | 详见 [v5 测试矩阵](#2026-05-24-v5-跨章节搜索测试矩阵占位)；FEAT_REPORT_v5.md §2.4 |
| v5 | `BookSearchEngine` 并发扫描 / 摘要生成 / 单章异常跳过 | 建议 `BookSearchEngineTest` | 详见 [v5 测试矩阵](#2026-05-24-v5-跨章节搜索测试矩阵占位)；FEAT_REPORT_v5.md §3.1 |
| v5 | `ReaderViewModel.setSearchMode/searchWholeBook/onBookSearchResultClicked` 全书搜索状态机 | 建议 `ReaderViewModelWholeBookSearchTest` | 详见 [v5 测试矩阵](#2026-05-24-v5-跨章节搜索测试矩阵占位)；FEAT_REPORT_v5.md §4.2 |
| v5 | `BookRepository.getChapterPlainText` 占位记录 / 异常路径 | 建议 `BookRepositoryImplPlainTextTest`（androidTest） | 详见 [v5 测试矩阵](#2026-05-24-v5-跨章节搜索测试矩阵占位) |
| B1 | 章节切换边界 | `ReaderViewModelTest` | `nextChapter_atLastChapter_doesNotAdvance` |
| B2 | `updateSettings` 重置 `_currentGlobalPage` | `ReaderViewModelExtraTest` | `should clear page cache and pending restore page when settings updated` |
| B8 | 导入异常清理占位 | `BookshelfViewModelImportTest` | `startImport fails` 系列 |

> 完整列表与详细背景请参阅 `FIX_REPORT.md`、`FIX_REPORT_v2.md` 的"修复对照表"，以及 `TDD_REPORT.md` / `TDD_REPORT_v3.md`（待补）。

---

## 集成测试覆盖范围

位于 `app/src/androidTest/java/com/example/read/`：

| 测试类 | 覆盖范围 | 数量 |
|--------|---------|------|
| `data/local/dao/BookDaoTest.kt` | DAO CRUD、`lastReadAt DESC` 排序、`Flow` 响应式更新、null 处理 | 17 |
| `data/repository/BookRepositoryImplTest.kt` | Repository 与真实 DAO 协调、Entity-Domain 转换、Flow 响应式（无 mock） | 14 |

均使用 **Room 内存数据库** 隔离测试：

```kotlin
Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
    .allowMainThreadQueries()
    .build()
```

---

## 运行测试

### JVM 单元测试

```bash
# 全部
./gradlew test

# 指定模块
./gradlew :app:testDebugUnitTest

# 指定测试类
./gradlew :app:testDebugUnitTest --tests "com.example.read.util.EpubParserTest"

# 指定单个测试方法（注意反引号符号需要 shell 转义）
./gradlew :app:testDebugUnitTest --tests "com.example.read.util.EpubParserTest.parseContainerXml_withStandardFormat_extractsFullPath"

# 含 stacktrace
./gradlew test --stacktrace --info
```

报告位置：`app/build/reports/tests/testDebugUnitTest/index.html`

### Android 仪器化测试

需要连接物理设备或启动模拟器（API 26+）：

```bash
# 启动模拟器后
./gradlew connectedAndroidTest

# 指定测试
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.read.data.local.dao.BookDaoTest
```

报告位置：`app/build/reports/androidTests/connected/debug/index.html`

### Android Studio 内运行

- 右键测试类 / 方法 → **Run 'TestName'**。
- Gutter 上的绿色三角形可直接运行单测。
- 失败堆栈可点击跳转到对应行。

---

## 新增测试模板

### JVM ViewModel 测试模板

适用：纯 Kotlin ViewModel 单元测试，Mock Repository。

```kotlin
package com.example.read.ui.bookshelf

import app.cash.turbine.test
import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

    private val repository: BookRepository = mockk(relaxed = true)
    private lateinit var viewModel: BookshelfViewModel

    @Before
    fun setUp() {
        // 替换主调度器为测试调度器，让 viewModelScope 在测试线程执行
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // 准备 Repository 的桩
        coEvery { repository.getAllBooks() } returns flowOf(emptyList())

        viewModel = BookshelfViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `初始状态 books 为空列表`() = runTest {
        viewModel.books.test {
            assertEquals(emptyList<Book>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteBook 应调用 repository deleteBook`() = runTest {
        val book = Book(id = 1, title = "T", author = "A", coverPath = null, bookDirPath = "/x", totalChapters = 1)
        viewModel.deleteBook(book)
        coVerify { repository.deleteBook(book) }
    }
}
```

### EPUB 解析测试模板

适用：构造内存 ZIP 验证 `EpubParser` 行为。参考 `EpubParserTest.kt` 中的样板：

```kotlin
private fun buildEpubZip(entries: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

@Test
fun `unpack 应生成 metadata json`() {
    val targetDir = Files.createTempDirectory("epub").toFile()
    val zipBytes = buildEpubZip(mapOf(
        "META-INF/container.xml" to containerXml.toByteArray(),
        "OEBPS/content.opf" to opfXml.toByteArray(),
        "OEBPS/ch1.xhtml" to "<html><body><h1>第一章</h1></body></html>".toByteArray(),
    ))

    val result = EpubParser().unpack(zipBytes.inputStream(), targetDir)

    assertEquals("书名", result.title)
    assertEquals(1, result.metadata.spine.size)
    val metaJson = File(targetDir, "metadata.json")
    assertTrue(metaJson.exists())

    targetDir.deleteRecursively()
}
```

### DAO 集成测试模板

适用：`app/src/androidTest/`，需要 Android 框架。

```kotlin
package com.example.read.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.example.read.data.local.AppDatabase
import com.example.read.data.local.entity.BookEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class BookDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.bookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertBook_应返回自增ID() = runTest {
        val id = dao.insertBook(
            BookEntity(title = "T", author = "A", coverPath = null,
                bookDirPath = "/x", totalChapters = 1)
        )
        assertEquals(1L, id)
    }

    @Test
    fun getAllBooks_应按lastReadAt倒序() = runTest {
        dao.insertBook(BookEntity(title = "A", author = "x", coverPath = null,
            bookDirPath = "/a", totalChapters = 1, lastReadAt = 100))
        dao.insertBook(BookEntity(title = "B", author = "x", coverPath = null,
            bookDirPath = "/b", totalChapters = 1, lastReadAt = 200))

        dao.getAllBooks().test {
            val list = awaitItem()
            assertEquals("B", list[0].title)
            assertEquals("A", list[1].title)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

### 测试命名约定

- 中文反引号风格：`` `输入_条件_期望结果`() ``，便于阅读。
- 英文下划线风格：`methodName_whenCondition_expectedResult()`。
- 同一文件统一一种风格。

---

## Mock 策略

### 何时 Mock

| 场景 | 是否 Mock |
|------|-----------|
| Repository 接口（在 ViewModel 单元测试中） | **Mock**（用 MockK） |
| DAO（在 Repository 单元测试中） | **Mock** |
| Room Database（在 DAO 集成测试中） | **不 Mock**，用 `inMemoryDatabaseBuilder` |
| 真实 Repository（在 Repository 集成测试中） | **不 Mock**，用真实 DAO + 内存数据库 |
| `EpubParser`（在 Repository 测试中） | **不 Mock**，用真实解析器 + 内存 ZIP |
| `Context` / `Application` | 集成测试中用 `ApplicationProvider.getApplicationContext()`，单元测试不要依赖 |

### MockK 常用模式

```kotlin
// 协程方法
coEvery { repository.getBookById(1L) } returns Book(...)
coEvery { repository.importBook(any(), any()) } throws IOException("disk full")

// Flow 返回值
every { repository.getAllBooks() } returns flowOf(listOf(book1, book2))

// 校验调用
coVerify(exactly = 1) { repository.deleteBook(book) }
coVerify { repository.updateReadingProgress(1L, 5) }

// 部分 mock（保留真实行为，仅覆盖某些方法）
val partialRepo: BookRepository = mockk(relaxed = true)
```

### 协程测试

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class FooTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun foo() = runTest {
        // viewModelScope 内的 launch 会立即执行
    }
}
```

### Turbine 测试 Flow

```kotlin
viewModel.books.test {
    val initial = awaitItem()      // 等待首个值
    assertEquals(0, initial.size)

    // 触发动作
    viewModel.addBook(book)

    val updated = awaitItem()      // 等待下一个发射值
    assertEquals(1, updated.size)

    cancelAndIgnoreRemainingEvents()  // 收尾
}
```

---

## 测试依赖

定义于 `gradle/libs.versions.toml` 与 `app/build.gradle.kts:85-97`：

| 依赖 | 版本 | 用途 |
|------|------|------|
| `junit:junit` | 4.13.2 | JUnit 4 测试框架 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.9.0 | `runTest` / `TestDispatcher` / `setMain` |
| `io.mockk:mockk` | 1.13.13 | Kotlin Mock 框架（含 coroutines 支持） |
| `app.cash.turbine:turbine` | 1.2.0 | Flow 测试工具 |
| `androidx.test.ext:junit` | 1.2.1 | Android JUnit Runner |
| `androidx.test:runner` | 1.6.2 | Android 测试 Runner |
| `androidx.test:core` | 1.6.1 | `ApplicationProvider` 等 |
| `androidx.room:room-testing` | 2.6.1 | `MigrationTestHelper`（用于迁移测试） |

---

## 推荐引入的测试库

下列依赖不在本轮（2026-05-24）修复范围内（按 FIX_REPORT 约束"仅追加 `androidx.webkit`，不引入新测试依赖"），但能覆盖当前测试矩阵的盲区，建议下一轮按需引入：

| 候选依赖 | 用途 | 覆盖的盲区 |
|----------|------|------------|
| `org.robolectric:robolectric` | 在 JVM 上跑需要 Android Framework 的代码 | `WebViewLruCache` 的淘汰行为、`preloadChapterPageCount` 的 5 秒超时 fallback、`SharedPreferences` debounce 写入策略 |
| `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` | Compose UI 测试 | `BookshelfScreen` / `ReaderScreen` 的交互（点击区域分区、目录面板展开、Snackbar 显示） |
| `kotlinx-coroutines-test` 的 `TestScope` + `advanceTimeBy` | 推进虚拟时间 | 验证 `pagePrefs.apply()` 在 100ms debounce 边界的行为 |
| `androidx.test.espresso:espresso-web` | 仪器化测试中操作 WebView | 验证 `WebViewAssetLoader` 的 https origin 隔离效果、`AndroidBridge` nonce 校验回归 |

引入新依赖请同步修改 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`，并在本文档"测试依赖"表中追加。

---

## 相关文档

- [docs/development.md](development.md)：开发环境与调试。
- [docs/architecture.md](architecture.md)：模块划分，决定测试边界。
- [docs/data-model.md](data-model.md)：数据库迁移测试相关。
