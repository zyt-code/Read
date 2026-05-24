package com.example.read.ui.reader

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import com.example.read.util.BookMetadata
import com.example.read.util.SpineItem
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * ReaderViewModel.onFindMatchLocated 与 ViewPager2 联动行为测试（P1-v5-2）。
 *
 * 修复背景（FEAT_REPORT_v5.md § 2）：
 * v4 章内搜索的 `scrollIntoView({block:'center'})` 仅在 WebView 内部滚动，
 * 不会触发 ViewPager2 切换 currentItem。匹配项位于章内第 2+ 页时，用户的可见区域
 * 仍停在第 1 页 —— ReaderViewModel 通过 `_currentGlobalPage` 驱动 ViewPager2 翻页。
 *
 * onFindMatchLocated 是 private 方法，本测试通过它的唯一对外触发点 [ReaderViewModel.findNext]
 * 与 [ReaderViewModel.findPrev] 间接验证：
 * - mockk 一个 FindInPageController，通过反射把它注入 `currentFindController` 字段
 * - 用 controller.next 的 callback 模拟 JS 端回报 NavigateResult(pageInChapter)
 * - 断言 `_currentGlobalPage` 被正确转换到章节首页 + pageInChapter
 *
 * 反射注入策略：因 [ReaderViewModel.attachFindController] 会调用 controller.clear()，
 * mockk 的 FindInPageController 是 final class，需要用 mockkConstructor 或反射来注入。
 * 由于 FindInPageController 构造需要 WebView（final + native），无法在 JVM 单测中构造，
 * 故我们通过 [ReaderViewModel.attachFindController] 的公开入口注入 mockk 实例。
 *
 * 不修改生产代码；不引入新依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelFindMatchLocatedTest {

    private lateinit var repository: BookRepository
    private val testDispatcher = StandardTestDispatcher()
    private val testBookId = 31L
    private val savedPagePrefsState = mutableMapOf<String, Int>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        savedPagePrefsState.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun makeMetadata(chapterCount: Int): BookMetadata = BookMetadata(
        title = "T", author = "A", opfDir = "OEBPS",
        spine = (1..chapterCount).map {
            SpineItem("ch$it", "ch$it.xhtml", "Chapter $it", "application/xhtml+xml")
        },
        tocItems = emptyList(),
    )

    private fun createViewModel(
        chapterCount: Int = 5,
        lastReadChapter: Int = 1,
    ): ReaderViewModel {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        mockkStatic(Color::class)
        every { Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns 0
        every { Color.WHITE } returns 0xFFFFFFFF.toInt()
        mockkStatic(Typeface::class)
        every { Typeface.SERIF } returns mockk(relaxed = true)
        every { Typeface.SANS_SERIF } returns mockk(relaxed = true)
        every { Typeface.MONOSPACE } returns mockk(relaxed = true)

        val book = Book(
            id = testBookId, title = "T", author = "A",
            coverPath = null, bookDirPath = "/books/$testBookId",
            totalChapters = chapterCount, lastReadChapter = lastReadChapter, lastReadAt = 0L,
        )
        coEvery { repository.getBookById(testBookId) } returns book
        coEvery { repository.getBookMetadata(testBookId) } returns makeMetadata(chapterCount)
        coEvery { repository.getChapterHtmlFile(testBookId, any()) } returns File("/tmp/x.xhtml")
        coEvery { repository.updateReadingProgress(any(), any()) } returns Unit

        val app = mockk<Application>(relaxed = true)
        val settingsPrefs = mockk<SharedPreferences>(relaxed = true)
        every { settingsPrefs.getFloat(any(), any()) } returns 18f
        every { settingsPrefs.getString(any(), any()) } returns "Serif"
        every { settingsPrefs.getInt(any(), any()) } returns 0
        val settingsEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { settingsPrefs.edit() } returns settingsEditor
        every { settingsEditor.putFloat(any(), any()) } returns settingsEditor
        every { settingsEditor.putString(any(), any()) } returns settingsEditor
        every { settingsEditor.putInt(any(), any()) } returns settingsEditor
        every { settingsEditor.apply() } just Runs

        val pagePrefs = mockk<SharedPreferences>(relaxed = true)
        every { pagePrefs.getInt(any(), any()) } answers {
            savedPagePrefsState[firstArg<String>()] ?: secondArg<Int>()
        }
        val pageEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { pagePrefs.edit() } returns pageEditor
        every { pageEditor.putInt(any(), any()) } answers {
            savedPagePrefsState[firstArg()] = secondArg()
            pageEditor
        }
        every { pageEditor.apply() } just Runs

        every { app.getSharedPreferences("reading_settings", any()) } returns settingsPrefs
        every { app.getSharedPreferences("reader_page_progress", any()) } returns pagePrefs

        val savedStateHandle = SavedStateHandle(
            mapOf(
                "com.example.read.ui.navigation.Reader" to mapOf("bookId" to testBookId),
                "bookId" to testBookId,
            )
        )
        return ReaderViewModel(repository, app, savedStateHandle, mockk(relaxed = true))
    }

    /**
     * 准备一个 mockk FindInPageController，并通过 attachFindController 注入到 ViewModel。
     * - controller.clear() 是 final 方法，需要用 every {} 模拟（mockk(relaxed=true) 已经满足）
     * - controller.next / prev 的回调由测试用例显式调用 callback 来模拟 JS 返回值
     *
     * 由于 FindInPageController 是普通类（非 final 但内含 WebView 强引用），mockk
     * 可以直接代理；attachFindController 内部会调 controller?.clear()（relaxed 兜底）。
     */
    private fun attachMockController(viewModel: ReaderViewModel): FindInPageController {
        val controller = mockk<FindInPageController>(relaxed = true)
        viewModel.attachFindController(controller)
        return controller
    }

    // ==================== findNext 路径：onFindMatchLocated 驱动 ViewPager2 ====================

    /**
     * P1-v5-2 主路径：
     * Given: 当前章节 1，章 0 / 章 1 / 章 2 各 3 页（globalPages 共 9 页）
     *        currentGlobalPage = 3（章 1 首页）
     *        controller.next 模拟 JS 回报 NavigateResult(index=2, pageInChapter=2)
     * When: viewModel.findNext()
     * Then:
     *   - _findCurrent 更新为 2
     *   - _currentGlobalPage 更新为 章 1 首页全局索引 (3) + pageInChapter (2) = 5
     *
     * 这是 P1-v5-2 修复的核心：匹配项的章内页码被正确转换为全局页码，
     * 驱动 ViewPager2 翻到匹配所在的全局页。
     */
    @Test
    fun `should update currentGlobalPage when findNext returns NavigateResult with pageInChapter`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 1)
        advanceUntilIdle()
        // 注册 3 个章节的页数，构建 globalPages
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        val controller = attachMockController(viewModel)
        // findNext 前置：findCount 必须 > 0（findNext 内的 `if (_findCount.value == 0) return` 守卫）
        // 通过模拟 updateFindQuery 让 _findCount 更新（controller.find 模拟回 count=3）
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=3, index=if(3>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("hello")
        advanceUntilIdle()

        // 当前在章 1 首页（globalPage=3）
        viewModel.jumpToGlobalPage(3)
        advanceUntilIdle()
        assertEquals(1, viewModel.chapterIndex.value)
        assertEquals(3, viewModel.currentGlobalPage.value)

        // 模拟 controller.next 回报：匹配项 index=2，位于章内第 2 页
        every { controller.next(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = 2, pageInChapter = 2))
        }

        viewModel.findNext()
        advanceUntilIdle()

        // findCurrent 同步刷新
        assertEquals("findNext 应把 findCurrent 设为 next 回报的 index", 2, viewModel.findCurrent.value)
        // currentGlobalPage 被更新到章 1 + 章内页 2 = 全局 5
        assertEquals(
            "P1-v5-2：onFindMatchLocated 应把 pageInChapter 转为全局页码驱动 ViewPager2",
            5, viewModel.currentGlobalPage.value,
        )
    }

    /**
     * findPrev 路径对称：
     * Given: 同上配置；mock prev 回报 NavigateResult(index=0, pageInChapter=1)
     * When: viewModel.findPrev()
     * Then: _findCurrent=0；_currentGlobalPage = 章 1 首页(3) + 1 = 4
     */
    @Test
    fun `should update currentGlobalPage when findPrev returns NavigateResult with pageInChapter`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 1)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        val controller = attachMockController(viewModel)
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=2, index=if(2>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("foo")
        advanceUntilIdle()

        viewModel.jumpToGlobalPage(3)
        advanceUntilIdle()

        every { controller.prev(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = 0, pageInChapter = 1))
        }

        viewModel.findPrev()
        advanceUntilIdle()

        assertEquals(0, viewModel.findCurrent.value)
        assertEquals("findPrev 也应驱动 ViewPager2 到章内页 1 对应的全局页", 4, viewModel.currentGlobalPage.value)
    }

    /**
     * 边界：JS 端 viewport 计算失败时返回 pageInChapter=-1
     * Given: controller.next 模拟回 NavigateResult(index=1, pageInChapter=-1)
     * When: viewModel.findNext()
     * Then:
     *   - _findCurrent 仍被更新为 1（index>=0）
     *   - _currentGlobalPage 保持不变（onFindMatchLocated 在 pageInChapter<0 时静默 return）
     */
    @Test
    fun `should ignore page update when pageInChapter is negative`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 1)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        val controller = attachMockController(viewModel)
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=2, index=if(2>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("x")
        advanceUntilIdle()
        viewModel.jumpToGlobalPage(3) // 章 1 首页 = global 3
        advanceUntilIdle()
        val beforeGlobal = viewModel.currentGlobalPage.value

        every { controller.next(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = 1, pageInChapter = -1))
        }

        viewModel.findNext()
        advanceUntilIdle()

        assertEquals(1, viewModel.findCurrent.value)
        assertEquals(
            "pageInChapter=-1 时不应触发 ViewPager2 翻页（currentGlobalPage 保留旧值）",
            beforeGlobal, viewModel.currentGlobalPage.value,
        )
    }

    /**
     * 边界：JS 端回报 index=-1（无匹配）
     * Given: controller.next 模拟回 NavigateResult(index=-1, pageInChapter=-1)
     * When: viewModel.findNext()
     * Then:
     *   - _findCurrent 不被更新（findNext 内的 `if (result.index >= 0)` 守卫）
     *   - _currentGlobalPage 不变
     */
    @Test
    fun `should not update findCurrent when index is negative`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 0)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        val controller = attachMockController(viewModel)
        every { controller.find(any(), any()) } answers {
            // 模拟 count=0 不可能进入 findNext 守卫；这里用 count=1 让守卫通过
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=1, index=if(1>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("any")
        advanceUntilIdle()
        val beforeCurrent = viewModel.findCurrent.value
        val beforeGlobal = viewModel.currentGlobalPage.value

        every { controller.next(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = -1, pageInChapter = -1))
        }

        viewModel.findNext()
        advanceUntilIdle()

        // index=-1 时 findNext 不更新 findCurrent；onFindMatchLocated 也不会被调
        assertEquals(beforeCurrent, viewModel.findCurrent.value)
        assertEquals(beforeGlobal, viewModel.currentGlobalPage.value)
    }

    /**
     * 边界：findCount=0 时 findNext 应早返回，不调 controller.next
     * Given: 进入 find 模式但 updateFindQuery 返回 count=0
     * When: viewModel.findNext()
     * Then: controller.next 不被调用；currentGlobalPage 不变
     */
    @Test
    fun `should not call controller next when findCount is zero`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 0)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        val controller = attachMockController(viewModel)
        // count=0：模拟无匹配
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=0, index=if(0>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("nomatch")
        advanceUntilIdle()
        assertEquals(0, viewModel.findCount.value)

        viewModel.findNext()
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) { controller.next(any()) }
    }

    /**
     * 边界：pageInChapter 超出当前章节页数范围（如 99）
     * Given: 章 1 共 3 页（页码 0/1/2），controller.next 回报 pageInChapter=99
     * When: viewModel.findNext()
     * Then: onFindMatchLocated 内部 findGlobalPageByPageInChapter 找不到（返回 -1），
     *       static check 失败 → currentGlobalPage 不变
     */
    @Test
    fun `should ignore page update when pageInChapter is out of range`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 1)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()
        val controller = attachMockController(viewModel)
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=1, index=if(1>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("x")
        advanceUntilIdle()
        viewModel.jumpToGlobalPage(3) // 章 1 首页 = 3
        advanceUntilIdle()
        val beforeGlobal = viewModel.currentGlobalPage.value

        every { controller.next(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = 0, pageInChapter = 99))
        }

        viewModel.findNext()
        advanceUntilIdle()

        assertEquals(
            "pageInChapter=99 越界，findGlobalPageByPageInChapter 返回 -1，currentGlobalPage 应不变",
            beforeGlobal, viewModel.currentGlobalPage.value,
        )
    }

    /**
     * 重复值短路：onFindMatchLocated 仅在目标与当前不同时更新 _currentGlobalPage
     * （避免 StateFlow 发射重复值触发无意义重组）。
     *
     * Given: currentGlobalPage 已经在章 1 第 2 页（global=5），controller.next 回报相同的 pageInChapter=2
     * When: findNext()
     * Then: currentGlobalPage 仍为 5；StateFlow 不应发射重复值（隐式断言）
     */
    @Test
    fun `should not update currentGlobalPage when target equals current`() = runTest {
        val viewModel = createViewModel(chapterCount = 3, lastReadChapter = 1)
        advanceUntilIdle()
        for (i in 0..2) viewModel.onPageCountReady(i, 3)
        advanceUntilIdle()

        val controller = attachMockController(viewModel)
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=1, index=if(1>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("x")
        advanceUntilIdle()
        // 跳到章 1 第 2 页（global=5）
        viewModel.jumpToGlobalPage(5)
        advanceUntilIdle()
        assertEquals(5, viewModel.currentGlobalPage.value)

        every { controller.next(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = 0, pageInChapter = 2))
        }

        viewModel.findNext()
        advanceUntilIdle()

        // 目标 = 当前，currentGlobalPage 不应被覆写（StateFlow 不发射重复值）
        assertEquals(5, viewModel.currentGlobalPage.value)
    }

    /**
     * 与 jumpToChapter 协作：onFindMatchLocated 不应误触发 pendingPositionRestore。
     *
     * Given: 先 jumpToChapter(2)（未预加载），currentGlobalPage 保留旧值；
     *        然后 onPageCountReady(2, 3) 后位置恢复到章 2 首页（global=6）
     * When: 触发 findNext 让 onFindMatchLocated 把页码移到章 2 第 1 页（global=7）
     * Then: currentGlobalPage = 7（onFindMatchLocated 写入），与 pendingPositionRestore 互不干扰
     */
    @Test
    fun `should not interfere with pendingPositionRestore state machine`() = runTest {
        val viewModel = createViewModel(chapterCount = 5, lastReadChapter = 0)
        advanceUntilIdle()
        // 仅注册章 0 的页数
        viewModel.onPageCountReady(0, 3)
        advanceUntilIdle()

        // 跳到未预加载章 2
        viewModel.jumpToChapter(2)
        advanceUntilIdle()
        // currentGlobalPage 保留旧值（章 0 首页 = 0）
        assertEquals(2, viewModel.chapterIndex.value)

        // WebView 加载完毕回调
        viewModel.onPageCountReady(2, 3)
        advanceUntilIdle()
        // pendingPositionRestore 恢复到章 2 首页（global = 3 + 0 = 3）
        assertEquals(3, viewModel.currentGlobalPage.value)

        // 然后再注入 controller，进入 find 模式
        val controller = attachMockController(viewModel)
        every { controller.find(any(), any()) } answers {
            secondArg<(FindInPageController.FindResult) -> Unit>().invoke(FindInPageController.FindResult(count=1, index=if(1>0) 0 else -1, pageInChapter=-1))
        }
        viewModel.enterFindMode()
        viewModel.updateFindQuery("x")
        advanceUntilIdle()

        // findNext 把页码移到章 2 第 1 页（global = 3 + 1 = 4）
        every { controller.next(any()) } answers {
            firstArg<(FindInPageController.NavigateResult) -> Unit>()
                .invoke(FindInPageController.NavigateResult(index = 0, pageInChapter = 1))
        }
        viewModel.findNext()
        advanceUntilIdle()

        // onFindMatchLocated 单独驱动，不应改变 chapterIndex
        assertEquals(2, viewModel.chapterIndex.value)
        assertEquals("findNext 应让 currentGlobalPage 跳到章 2 第 1 页", 4, viewModel.currentGlobalPage.value)
        assertNotEquals(3, viewModel.currentGlobalPage.value)
    }
}
