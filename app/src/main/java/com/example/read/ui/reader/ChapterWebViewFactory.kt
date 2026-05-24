package com.example.read.ui.reader

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * WebView 工厂，负责创建和配置用于章节 HTML 渲染的 WebView 实例。
 *
 * 设计说明：
 * - 每个章节使用独立的 WebView 实例，避免跨章节的 DOM 状态污染
 * - 通过 WebViewAssetLoader 将 EPUB 解包目录映射为 https://appassets.androidplatform.net/，
 *   避免使用 file:// 直读引起的私有文件泄露风险（P0-3）。Origin 是 https 而不是 file，
 *   WebView 会按同源策略隔离 EPUB 内联脚本与系统其他 file:// 资源。
 * - 分页 CSS 和 JavaScript 在 HTML 加载后注入，由 onPageFinished 回调触发
 * - JS 注入的 CSS 字符串经 WebViewPaginator.escapeJsString 严格转义（P0-4）
 * - AndroidBridge 通过随机 nonce 校验来源（P0-3），防止 EPUB 内嵌脚本伪造回调
 *
 * WebView 生命周期：
 * - 创建：章节切换时由 ChapterWebViewFactory.create() 创建
 * - 加载：loadHtml() 加载章节 HTML 并注入分页脚本
 * - 销毁：页面销毁时调用 WebView.destroy() 释放资源
 *
 * Context 选择（P0-2）：
 * - 推荐传入 applicationContext，避免持有 Activity 引用造成泄漏；
 *   预加载场景必须传 applicationContext
 *
 * @param context Android Context，用于创建 WebView 实例
 */
class ChapterWebViewFactory(private val context: Context) {

    companion object {
        /** WebViewAssetLoader 暴露 EPUB 资源时使用的 https authority（推荐固定值） */
        private const val ASSET_HOST = "appassets.androidplatform.net"
        /** WebViewAssetLoader 路径前缀 */
        private const val ASSET_PATH = "/epub/"
        /** AndroidBridge 名称，与 PAGINATION_JS 中 window.AndroidBridge 对应 */
        private const val BRIDGE_NAME = "AndroidBridge"
    }

    /**
     * 创建并配置一个用于章节渲染的 WebView 实例。
     *
     * WebView 配置说明（P0-3 收紧）：
     * - javaScriptEnabled = true: 启用 JS，分页计算和样式更新依赖 JS
     * - allowFileAccess = false: 关闭 file:// 直读，所有 EPUB 资源通过 WebViewAssetLoader 提供
     * - allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs: 显式关闭，
     *   防止潜在的 file:// 跨域读取
     * - allowContentAccess = false: 禁用 content:// 协议访问
     * - mixedContentMode = NEVER_ALLOW: 禁止 https 文档加载 http 资源
     * - 其余缩放相关配置与原版保持一致
     *
     * @return 配置完成的 WebView 实例
     */
    fun create(): WebView {
        return WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                builtInZoomControls = false
                displayZoomControls = false
                loadWithOverviewMode = false
                useWideViewPort = false
                defaultTextEncodingName = "UTF-8"
                setSupportZoom(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
    }

    /**
     * 构建 WebViewAssetLoader 实例，把 EPUB 解包目录映射到 https 资源。
     *
     * URL 形式：https://appassets.androidplatform.net/epub/<相对路径>
     * 例如：https://appassets.androidplatform.net/epub/OEBPS/ch1.xhtml
     *
     * @param bookDirPath 书籍解包根目录（绝对路径）
     */
    private fun buildAssetLoader(bookDirPath: String): WebViewAssetLoader {
        return WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler(
                ASSET_PATH,
                WebViewAssetLoader.InternalStoragePathHandler(context, File(bookDirPath)),
            )
            .build()
    }

    /**
     * 向 WebView 加载章节 HTML 内容。
     *
     * 使用 loadDataWithBaseURL 而非 loadData 的原因：
     * - loadData 会对内容进行 URL 编码，可能导致中文乱码
     * - loadDataWithBaseURL 允许指定 baseUrl，EPUB 中的相对路径（CSS、图片）
     *   可以基于此路径正确解析
     *
     * baseUrl 设为 https://appassets.androidplatform.net/epub/<opfDir>/，
     * 由 WebViewAssetLoader 在 shouldInterceptRequest 中拦截并从内部存储读取真实文件。
     * 这样 EPUB 中 <link href="style.css"> / <img src="images/fig1.png"> 都能正确解析。
     *
     * 安全增强（P0-3 / P0-4）：
     * - 加载前移除可能残留的 AndroidBridge，再以新 nonce 重新注册（防止 PaginationBridge 累积）
     * - WebViewClient.shouldInterceptRequest 把 https://appassets.androidplatform.net 的请求
     *   交给 WebViewAssetLoader，其他 host 的请求一律返回空响应（拒绝网络外联）
     *
     * @param webView 目标 WebView 实例
     * @param htmlContent 章节的原始 HTML 内容
     * @param bookDirPath 书籍解包根目录绝对路径（用于初始化 WebViewAssetLoader）
     * @param opfDirRelative OPF 目录相对 bookDirPath 的路径，决定 baseUrl 的子路径
     * @param readingSettings 当前阅读设置，用于生成初始 CSS
     * @param onPageCountReady 页数计算完成的回调，参数为至少为 1 的页数
     */
    fun loadHtml(
        webView: WebView,
        htmlContent: String,
        bookDirPath: String,
        opfDirRelative: String,
        readingSettings: ReadingSettings,
        onPageCountReady: (Int) -> Unit,
    ) {
        // P1-2 防御：WebViewAssetLoader.InternalStoragePathHandler 在构造时会校验
        // directory 必须位于 context.getDataDir() 子目录内，否则抛 IllegalArgumentException。
        // 阅读器开屏时存在以下两种合法但非"已就绪"的状态：
        // 1) book 尚未从 Repository 加载完毕 → ReaderScreen 的 getBookDirPath() 返回 ""；
        // 2) 用户从书架进入一本仍在 prepareImport / startImport 流程中的占位记录
        //    → bookDirPath = "PREPARING_<uuid>"，根本不是文件系统路径。
        // 任一种都会触发 InternalStoragePathHandler 构造期异常，把崩溃面暴露到
        // 主线程（来自 PagedChapterAdapter 的 mainHandler.post）。
        // 这里直接短路：回调 1 页空白，让 WebView 不加载任何内容，UI 层会显示空白页，
        // 但不会崩溃。后续 book 正常加载后会重新触发分页流程。
        if (bookDirPath.isEmpty() || bookDirPath.startsWith("PREPARING_")) {
            android.util.Log.w(
                "ChapterWebViewFactory",
                "loadHtml skipped: bookDirPath not ready ($bookDirPath)",
            )
            onPageCountReady(1)
            return
        }
        val bookDir = File(bookDirPath)
        if (!bookDir.exists() || !bookDir.isDirectory) {
            // 目录可能因为外部删除（用户清空缓存、卸载分包）而不存在，
            // 同样回退到空白页避免 InternalStoragePathHandler 抛异常
            android.util.Log.w(
                "ChapterWebViewFactory",
                "loadHtml skipped: bookDir not found ($bookDirPath)",
            )
            onPageCountReady(1)
            return
        }

        // 移除旧 bridge 防止多次 addJavascriptInterface 累积闭包（P0-2）
        webView.removeJavascriptInterface(BRIDGE_NAME)

        // 为本次加载生成专属 nonce，注入 JS 与桥同时绑定（P0-3 来源校验）
        val nonce = WebViewPaginator.newNonce()
        webView.addJavascriptInterface(
            WebViewPaginator.PaginationBridge(nonce, onPageCountReady),
            BRIDGE_NAME,
        )

        val assetLoader = buildAssetLoader(bookDirPath)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // 仅放行映射到 EPUB 资源的 https 请求，其他外链一律拦截（拒绝外联）
                return if (request.url.host == ASSET_HOST) {
                    assetLoader.shouldInterceptRequest(request.url)
                } else {
                    // 同域以外的请求（http(s) 网络资源、file://、content://）一律阻断
                    WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // 拒绝跨域导航（章节内点击外链不打开），仅允许内部 host 内的跳转
                return request.url.host != ASSET_HOST
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 第一步：注入分页 JS（用 escapeJsString 把 nonce 安全嵌入字符串字面量）
                // PAGINATION_JS_TEMPLATE 内部使用 '__NONCE__' 字面量，作字符串替换即可
                val jsWithNonce = WebViewPaginator.PAGINATION_JS_TEMPLATE.replace("__NONCE__", nonce)
                view?.evaluateJavascript(jsWithNonce, null)
                // 第二步：注入章内搜索 JS（v4 feature: find-in-page）
                // FIND_IN_PAGE_JS 是无 nonce 的纯 IIFE，使用 evaluateJavascript 回调取结果，
                // 不依赖 AndroidBridge，与现有 P0-3 安全模型解耦。必须在 PAGINATION_JS 之后注入
                // 顺序无强约束，但放在 updateCSS 之前更直观（先准备好搜索能力，再应用样式）。
                view?.evaluateJavascript(FindInPageJs.FIND_IN_PAGE_JS, null)
                // 第三步：注入阅读 CSS（此时 updateCSS 函数已定义；CSS 末尾包含 mark.reader-find 高亮样式）
                val css = readingSettings.toReaderCss()
                val escapedCss = WebViewPaginator.escapeJsString(css)
                view?.evaluateJavascript("updateCSS($escapedCss)", null)
                // 第四步：计算页数（此时 CSS 已生效，测量结果准确）
                view?.evaluateJavascript("calculatePages()", null)
            }
        }

        // 构造 baseUrl：放置在 EPUB OPF 目录下，确保 EPUB 的相对资源路径能正确解析
        val baseUrl = if (opfDirRelative.isEmpty()) {
            "https://$ASSET_HOST$ASSET_PATH"
        } else {
            "https://$ASSET_HOST$ASSET_PATH$opfDirRelative/"
        }

        webView.loadDataWithBaseURL(
            baseUrl,
            htmlContent,
            "text/html",
            "UTF-8",
            null,
        )
    }

    /**
     * 动态更新 WebView 的阅读样式。
     *
     * 当用户修改阅读设置（字号、行高、字体、背景色）时调用，
     * 通过 JavaScript 更新 style 标签并重新计算页数。
     *
     * 使用 WebViewPaginator.escapeJsString 转义（P0-4），覆盖反斜杠、引号、
     * 控制字符、U+2028/U+2029、</ 序列，杜绝 JS 注入与字符串截断风险。
     *
     * @param webView 目标 WebView 实例
     * @param readingSettings 新的阅读设置
     */
    fun updateCSS(webView: WebView, readingSettings: ReadingSettings) {
        val css = readingSettings.toReaderCss()
        val escapedCss = WebViewPaginator.escapeJsString(css)
        webView.evaluateJavascript("updateCSS($escapedCss)", null)
    }

    /**
     * 滚动 WebView 到指定页面。
     *
     * @param webView 目标 WebView 实例
     * @param pageIndex 目标页码（0-based）
     */
    fun scrollToPage(webView: WebView, pageIndex: Int) {
        webView.evaluateJavascript("scrollToPage($pageIndex)", null)
    }
}
