package com.example.read.ui.reader

/**
 * JavaScript 字面量转义工具，供 WebView evaluateJavascript 注入字符串时使用。
 *
 * 抽取动机（v4 feature: find-in-page）：
 * - WebViewPaginator、ChapterWebViewFactory 与 FindInPageController 都需要把
 *   Kotlin 字符串安全嵌入 JS 字面量
 * - 提供一个 internal 顶层函数 [escapeJsString]，让 reader 包内的新组件
 *   （如 FindInPageController）无需绕过 companion 即可使用同一份转义实现
 * - 实现层面直接委托到 [WebViewPaginator.escapeJsString]，避免重复实现，
 *   也确保未来对转义逻辑的修复在所有调用点同步生效（单点责任）
 *
 * 行为契约（与 WebViewPaginator.escapeJsString 完全一致）：
 * - 使用 JSONObject.quote(s) 生成 JSON 字符串字面量（JS 字面量超集）
 * - 二次转义 U+2028 / U+2029（旧 ECMAScript 视为字符串内换行）
 * - 二次转义 </ 序列为 <\/（防 <script>/<style> 误闭合）
 *
 * 返回值形如 `"已转义内容"`，**包含外层双引号**，调用方无需再加引号。
 *
 * 示例：
 * ```
 * val js = "window.ReaderFind.find(${escapeJsString(query)})"
 * webView.evaluateJavascript(js, null)
 * ```
 *
 * @param s 任意 Kotlin 字符串（含 null 字符、控制符、Unicode 分隔符都安全）
 * @return 形如 `"已转义内容"` 的 JS 字面量片段
 */
internal fun escapeJsString(s: String): String = WebViewPaginator.escapeJsString(s)
