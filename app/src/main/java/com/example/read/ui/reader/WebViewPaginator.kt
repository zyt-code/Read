package com.example.read.ui.reader

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * WebView 分页核心组件，负责向 WebView 注入 CSS 和 JavaScript 实现精确分页。
 *
 * 核心职责：
 * 1. 提供分页 JavaScript 代码常量（测量内容高度、计算页数、滚动到指定页）
 * 2. 通过 JavascriptInterface 桥接类接收页数回调
 * 3. 提供安全的 JS 字符串转义（escapeJsString），用于把任意 Kotlin 字符串嵌入 JS 字面量
 *
 * 分页原理：
 * - WebView 加载 HTML 后，JavaScript 测量 document.scrollHeight 和 window.innerHeight
 * - 页数 = ceil(scrollHeight / innerHeight)
 * - 翻页时通过 window.scrollTo(0, pageIndex * innerHeight) 滚动到目标页
 * - 设置变更时通过 updateCSS(css) 动态更新样式并重新计算页数
 *
 * 安全设计（P0-3 / P0-4）：
 * - JS 注入的所有字符串都经 escapeJsString 严格转义，避免 U+2028/U+2029 等隐藏断行符
 *   把 JS 字面量截断、或 </script> / </style> 在某些场景下被外层文档解释
 * - AndroidBridge 通过 PaginationBridge 暴露给 JS，构造时绑定一个随机 nonce；
 *   JS 端在每次回调时回传 nonce，Kotlin 端校验 nonce 不匹配则丢弃回调，
 *   防止 EPUB 内嵌恶意脚本伪造 AndroidBridge 调用
 *
 * CSS 生成由 ReadingSettings.toReaderCss() 负责，WebView 创建由 ChapterWebViewFactory 负责。
 *
 * 线程说明：
 * - JavascriptInterface 回调在 WebView 的内部线程触发
 * - 调用方需要确保 UI 更新切换到主线程
 */
class WebViewPaginator {

    companion object {
        /**
         * 注入到 WebView 的分页 JavaScript 代码模板。
         *
         * 使用占位符 __NONCE__ 在加载时替换为随机 nonce。这样：
         * 1) calculatePages 在回调 AndroidBridge.onPageCountReady 时同时传入 nonce
         * 2) Kotlin 端校验 nonce 与本次会话生成的 nonce 一致后才接受 pageCount
         *
         * 功能：
         * - calculatePages(): 测量内容总高度，计算页数，通过 AndroidBridge 回调
         * - scrollToPage(pageIndex): 滚动到指定页
         * - updateCSS(css): 动态更新样式标签并重新计算页数
         *
         * 使用 IIFE 避免污染全局命名空间。
         */
        const val PAGINATION_JS_TEMPLATE = """
(function() {
    var __NONCE = '__NONCE__';
    function calculatePages() {
        var body = document.body;
        var html = document.documentElement;
        var totalHeight = Math.max(body.scrollHeight, html.scrollHeight);
        var viewportHeight = window.innerHeight;
        var pageCount = Math.ceil(totalHeight / viewportHeight);
        if (window.AndroidBridge) {
            window.AndroidBridge.onPageCountReady(__NONCE, pageCount);
        }
    }
    function scrollToPage(pageIndex) {
        var viewportHeight = window.innerHeight;
        window.scrollTo(0, pageIndex * viewportHeight);
    }
    function updateCSS(css) {
        var style = document.getElementById('reader-style');
        if (!style) {
            style = document.createElement('style');
            style.id = 'reader-style';
            document.head.appendChild(style);
        }
        style.textContent = css;
    }
    window.calculatePages = calculatePages;
    window.scrollToPage = scrollToPage;
    window.updateCSS = updateCSS;
    if (document.readyState === 'complete') {
        calculatePages();
    } else {
        window.addEventListener('load', calculatePages);
    }
    window.addEventListener('resize', calculatePages);
})();
"""

        /**
         * 旧常量保留以兼容旧代码（如可能存在的测试快照），实际使用 PAGINATION_JS_TEMPLATE。
         * 注意：直接注入此常量将不会校验 nonce，不应在生产路径使用。
         */
        @Deprecated("Use PAGINATION_JS_TEMPLATE with nonce substitution instead")
        const val PAGINATION_JS = PAGINATION_JS_TEMPLATE

        /** ECMAScript 字符串字面量中会被当作换行的 Unicode 分隔符 */
        private val LINE_SEPARATOR: Char = 0x2028.toChar()
        private val PARAGRAPH_SEPARATOR: Char = 0x2029.toChar()

        /**
         * 安全地把任意 Kotlin 字符串转义为可嵌入 JavaScript 字面量的字符串。
         *
         * 使用 org.json.JSONObject.quote(s) 生成一个完整、带双引号的 JSON 字符串，
         * 该字符串同时也是合法的 JavaScript 字符串字面量（JSON 是 JS 的严格子集）。
         * JSON 转义已覆盖：反斜杠、引号、控制字符、回车换行等。
         *
         * 额外补充：
         * - U+2028 (Line Separator) 和 U+2029 (Paragraph Separator) 在 ECMAScript 旧规范中
         *   会被解析为 JS 字符串字面量内的换行符，破坏字符串边界；JSONObject.quote 不一定
         *   转义这两个码点，因此在结果上手动二次转义为 \u2028 / \u2029。
         * - "</script>" / "</style>" 在 HTML 文档解析阶段会被识别为闭合标签；对于通过
         *   evaluateJavascript 注入的代码不构成问题，但若未来代码改走 <script> 字面量注入，
         *   此处转义 "</" 为 "<\/" 仍是更稳妥的工程实践。
         *
         * @param s 任意 Kotlin 字符串
         * @return 形如 "\"已转义内容\"" 的 JS 字面量片段，可直接拼入 evaluateJavascript
         */
        fun escapeJsString(s: String): String {
            val quoted = JSONObject.quote(s)
            val sb = StringBuilder(quoted.length + 8)
            var i = 0
            while (i < quoted.length) {
                val c = quoted[i]
                when (c) {
                    LINE_SEPARATOR -> sb.append("\\u2028")
                    PARAGRAPH_SEPARATOR -> sb.append("\\u2029")
                    '<' -> {
                        // 仅在 </ 序列处转义为 <\/，避免破坏其他普通 < 字符
                        if (i + 1 < quoted.length && quoted[i + 1] == '/') {
                            sb.append("<\\/")
                            i += 2
                            continue
                        }
                        sb.append(c)
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        /**
         * 生成一个 URL-safe 的随机 nonce，用于 PAGINATION_JS_TEMPLATE 占位符替换。
         *
         * 使用 16 字节随机数 + Hex 编码，足够防止外部脚本暴力构造。
         */
        fun newNonce(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }

    /**
     * JavascriptInterface 桥接类，接收 WebView 中 JavaScript 的页数回调。
     *
     * 使用 @JavascriptInterface 注解标记的方法会被暴露给 JavaScript，
     * JavaScript 通过 window.AndroidBridge.onPageCountReady(nonce, count) 调用。
     *
     * 来源校验（P0-3）：
     * 构造时绑定一次性 nonce。JS 端必须把同一个 nonce 作为第一个参数传回，
     * Kotlin 端比对 nonce 后才接受 count。EPUB 内嵌恶意脚本无法获取 nonce，
     * 因此即使能拿到 window.AndroidBridge 也无法触发回调。
     *
     * @param expectedNonce 本次会话的 nonce，由 ChapterWebViewFactory 注入 JS 时生成
     * @param onReady 页数就绪时的回调，参数为至少为 1 的页数
     */
    class PaginationBridge(
        private val expectedNonce: String,
        private val onReady: (Int) -> Unit,
    ) {
        /**
         * JavaScript 回调：页数计算完成。
         *
         * 双参数版本：第一个参数是 PAGINATION_JS 中注入的随机 nonce，
         * 第二个参数是 JS 计算出的页数。nonce 不匹配时静默丢弃，
         * 防止恶意 EPUB 脚本伪造回调污染阅读进度。
         *
         * @param nonce JS 端回传的 nonce，与构造时绑定的 expectedNonce 比较
         * @param count JavaScript 计算出的页数
         */
        @JavascriptInterface
        fun onPageCountReady(nonce: String?, count: Int) {
            if (nonce != expectedNonce) {
                // nonce 不匹配：可能是恶意脚本或旧 WebView 实例的残留回调，忽略
                return
            }
            onReady(count.coerceAtLeast(1))
        }
    }
}
