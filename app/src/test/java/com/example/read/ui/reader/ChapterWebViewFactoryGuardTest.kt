package com.example.read.ui.reader

import android.content.Context
import android.webkit.WebView
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * ChapterWebViewFactory.loadHtml 防御性短路测试（P1-2 回归）。
 *
 * 修复要点（详见 FIX_REPORT_v2.md Fix-2 + REVIEW_REPORT_v3.md P1-2）：
 * `WebViewAssetLoader.InternalStoragePathHandler` 构造时会校验 directory 必须位于
 * `context.getDataDir()` 子目录内，否则抛 `IllegalArgumentException`。
 * 阅读器开屏时存在两种合法但"未就绪"路径：
 * 1) `bookDirPath = ""` —— `ReaderScreen.getBookDirPath()` 在 book 未加载时返回空串
 * 2) `bookDirPath = "PREPARING_<uuid>"` —— 用户进入仍在 prepareImport / startImport
 *    流程的占位记录（理论上 P1-1 已让书架不显示，但这里留下兜底）
 * 3) `bookDirPath = "/non/existent/path"` —— 真实路径但目录已被外部删除
 *
 * 期望：loadHtml 在三类情况下都不调用底层 WebView/WebViewAssetLoader 构造期 API，
 * 而是直接回调 `onPageCountReady(1)` 让 UI 显示空白页。
 *
 * 妥协方案：
 * - ChapterWebViewFactory 持 Context、操作 WebView，这些是 Android stub 类，
 *   在 JVM 测试中调用会抛 `RuntimeException("Stub!")`。
 * - 因此用 MockK 创建 `mockk<Context>(relaxed = true)` 和 `mockk<WebView>(relaxed = true)`，
 *   通过 relaxed 让所有 Android API 默认返回 Unit/null，不真正构造 WebViewAssetLoader。
 * - **不验证** `WebViewAssetLoader.Builder` 未被实例化（mockkConstructor 对 Android
 *   类支持不稳定且会引入复杂性），降级为：仅断言 `onPageCountReady(1)` 被回调，
 *   且短路路径下 webView 不会触发 `addJavascriptInterface` / `loadDataWithBaseURL`
 *   这两个会触及底层 WebViewAssetLoader 链路的方法。
 *
 * 严格的 androidTest 验证建议参见 TDD_REPORT_v3.md § 4 不可测项。
 */
class ChapterWebViewFactoryGuardTest {

    private lateinit var context: Context
    private lateinit var webView: WebView
    private lateinit var factory: ChapterWebViewFactory

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        // android.graphics.Color 在 ReadingSettings 枚举初始化时被引用（argb / WHITE 等常量）。
        // 纯 JVM 测试下未 mock 会抛 RuntimeException("Stub!")，故 mockkStatic 兜底。
        mockkStatic(android.graphics.Color::class)
        every { android.graphics.Color.WHITE } returns 0xFFFFFFFF.toInt()
        every { android.graphics.Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            val a = arg<Int>(0); val r = arg<Int>(1); val g = arg<Int>(2); val b = arg<Int>(3)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        // android.util.Log.w 在短路路径中被调用，JVM stub 会抛 Stub!，mock 掉。
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        // relaxed mock：所有 Context / WebView 方法默认返回 Unit/null，避免抛 Stub!
        context = mockk(relaxed = true)
        webView = mockk(relaxed = true)
        factory = ChapterWebViewFactory(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** 简易 ReadingSettings 实例（loadHtml 在短路路径下不会真的使用，但需要非空） */
    private fun defaultSettings(): ReadingSettings = ReadingSettings(
        fontSize = 18f,
        lineHeightMultiplier = 1.6f,
        fontFamily = "Serif",
        backgroundColor = ReadingSettings.BackgroundColor.WHITE,
    )

    /**
     * Given: `bookDirPath = ""`（书籍尚未加载，ReaderScreen.getBookDirPath 默认返回空串）
     * When: 调用 `loadHtml(...)`
     * Then: onPageCountReady 被回调以参数 1；不会触发 addJavascriptInterface / loadDataWithBaseURL。
     */
    @Test
    fun `should short-circuit and callback 1 when bookDirPath is empty`() {
        var captured: Int? = null
        factory.loadHtml(
            webView = webView,
            htmlContent = "<html></html>",
            bookDirPath = "",
            opfDirRelative = "",
            readingSettings = defaultSettings(),
            onPageCountReady = { captured = it },
        )

        assertNotNull("短路路径应回调 onPageCountReady", captured)
        assertEquals("短路路径应回调 pageCount=1", 1, captured)
        // 短路路径不应触及 WebViewAssetLoader 链路相关 API
        verify(exactly = 0) { webView.addJavascriptInterface(any(), any()) }
        verify(exactly = 0) { webView.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
    }

    /**
     * Given: `bookDirPath = "PREPARING_uuid-123"`（占位记录，导入未完成）
     * When: 调用 `loadHtml(...)`
     * Then: 同上，onPageCountReady(1) 被回调；底层 WebView 加载 API 未触达
     *
     * 这是 P1-2 的核心场景：即使 P1-1 已让书架不显示 PREPARING_，
     * deeplink / 外部启动入口仍可能直接传 bookId 进来。
     */
    @Test
    fun `should short-circuit when bookDirPath starts with PREPARING prefix`() {
        var captured: Int? = null
        factory.loadHtml(
            webView = webView,
            htmlContent = "<html></html>",
            bookDirPath = "PREPARING_uuid-123-xyz",
            opfDirRelative = "OEBPS",
            readingSettings = defaultSettings(),
            onPageCountReady = { captured = it },
        )

        assertEquals(1, captured)
        verify(exactly = 0) { webView.addJavascriptInterface(any(), any()) }
        verify(exactly = 0) { webView.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
    }

    /**
     * Given: `bookDirPath = "/non/existent/path/that/does/not/exist"`（路径真实但目录不存在）
     * When: 调用 `loadHtml(...)`
     * Then: onPageCountReady(1) 被回调；不进入 WebViewAssetLoader 构造路径
     *
     * 生产代码使用 `File(bookDirPath).exists() || !.isDirectory` 兜底，
     * 避免 InternalStoragePathHandler 因目录不存在抛 IAE 让主线程崩溃。
     */
    @Test
    fun `should short-circuit when bookDir does not exist`() {
        var captured: Int? = null
        factory.loadHtml(
            webView = webView,
            htmlContent = "<html></html>",
            bookDirPath = "/non/existent/path/that/does/not/exist",
            opfDirRelative = "",
            readingSettings = defaultSettings(),
            onPageCountReady = { captured = it },
        )

        assertEquals(1, captured)
        verify(exactly = 0) { webView.addJavascriptInterface(any(), any()) }
        verify(exactly = 0) { webView.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
    }

    /**
     * Given: `bookDirPath` 是一个真实存在的文件（而非目录）
     * When: 调用 `loadHtml(...)`
     * Then: 短路返回 1；File.isDirectory() == false 防御也生效
     *
     * 防御 bookDirPath 字段被外部错误填充的情形（如人工写库或迁移脚本错误）。
     */
    @Test
    fun `should short-circuit when bookDir is a regular file not directory`() {
        // 创建一个真实临时文件以确保 File.exists() 为 true 但 isDirectory 为 false
        val tempFile = java.io.File.createTempFile("not_a_dir_", ".txt")
        tempFile.deleteOnExit()

        var captured: Int? = null
        factory.loadHtml(
            webView = webView,
            htmlContent = "<html></html>",
            bookDirPath = tempFile.absolutePath,
            opfDirRelative = "",
            readingSettings = defaultSettings(),
            onPageCountReady = { captured = it },
        )

        assertEquals(1, captured)
        verify(exactly = 0) { webView.addJavascriptInterface(any(), any()) }
        verify(exactly = 0) { webView.loadDataWithBaseURL(any(), any(), any(), any(), any()) }
    }
}
