package com.example.read.ui.reader

import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FindInPageController 单元测试（v4 章内搜索）。
 *
 * 测试目标：
 * - find / next / prev / clear 调用 webView.evaluateJavascript 时传入的 JS 字符串
 *   形式符合契约（与 FIND_IN_PAGE_JS 注入的 window.ReaderFind 对接）
 * - find 的 query 经 escapeJsString 转义后嵌入 JS 字面量，特殊字符（双引号、反斜杠、
 *   U+2028、</script>）不会破坏字面量边界
 * - parseIntOrZero 通过观察 callback 行为间接验证（next/prev 的回调路径）
 *
 * Android stub 限制：
 * - WebView 是 final class 且依赖 native，无法直接 new。MockK 的 `mockk<WebView>(relaxed = true)`
 *   可以创建代理对象，所有方法默认 no-op
 * - escapeJsString 依赖 `org.json.JSONObject.quote`，在 Android stub 中抛 Stub!；
 *   沿用 WebViewPaginatorEscapeTest 的 mockkStatic 策略提供参考实现（同源代码已验证）
 * - android.os.Handler / Looper 在 FindInPageController 构造时会被引用（mainHandler 字段），
 *   通过 mockkStatic(Looper) 兜底；测试不真正调用 mainHandler.post（生产代码也未使用）
 *
 * 不修改生产代码；不引入新依赖。
 */
class FindInPageControllerTest {

    private lateinit var webView: WebView
    private lateinit var controller: FindInPageController

    /**
     * 参考实现：模拟 org.json.JSONObject.quote。
     * 与 WebViewPaginatorEscapeTest.referenceJsonQuote 完全一致 —— escapeJsString 委托
     * 到 WebViewPaginator.escapeJsString，同一份 JSON.quote 替身可复用。
     */
    private fun referenceJsonQuote(input: String?): String {
        if (input == null) return "\"\""
        val sb = StringBuilder(input.length + 2)
        sb.append('"')
        for (c in input) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\u000C' -> sb.append("\\f")
                '\r' -> sb.append("\\r")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    @Before
    fun setUp() {
        // Looper.getMainLooper() 在 FindInPageController 字段 mainHandler 初始化时被引用
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        // JSONObject.quote 在 Android stub 下抛 Stub!，需替换为参考实现
        mockkStatic(JSONObject::class)
        every { JSONObject.quote(any()) } answers {
            referenceJsonQuote(firstArg<String?>())
        }

        webView = mockk(relaxed = true)
        controller = FindInPageController(webView)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== find ====================

    /**
     * Given: 简单 ASCII 查询词 "hello"
     * When: 调 controller.find("hello") { ... }
     * Then: webView.evaluateJavascript 被调用一次，JS 字符串为
     *       `window.ReaderFind && window.ReaderFind.find("hello")`
     *
     * 注意点：
     * - 生产代码使用 `escapeJsString(query)`，返回值含外层双引号 → "hello"
     * - 故拼接结果是 `window.ReaderFind.find("hello")` 而不是 `find('hello')`
     */
    @Test
    fun `should call evaluateJavascript with quoted query on find`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            // 模拟 JS 返回匹配数 12（v5/v4 老版本：纯数字；FindResult 解析后 count=12）
            secondArg<ValueCallback<String>>().onReceiveValue("12")
        }

        // P1-v6-2：find 的 onResult 签名升级为 FindResult，含 count + index + pageInChapter
        var resultCount = -1
        controller.find("hello") { resultCount = it.count }

        // 验证 evaluateJavascript 被调用一次
        verify(exactly = 1) {
            webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>())
        }
        val js = captured.captured
        assertTrue("应调用 window.ReaderFind.find 入口，实际: $js", js.contains("window.ReaderFind.find"))
        // 应使用 escapeJsString 的双引号字面量
        assertTrue("应包含 \"hello\"，实际: $js", js.contains("\"hello\""))
        // 应有 `&&` 短路保护（FindInPageJs 未就绪时不抛错）
        assertTrue("应包含 && 短路保护", js.contains("window.ReaderFind &&"))
        // callback 收到正确的解析结果
        assertEquals("应回调匹配数 12（兼容旧版纯数字返回）", 12, resultCount)
    }

    /**
     * Given: 含特殊字符的 query：单引号、双引号、反斜杠
     * When: 调 controller.find("""it's a "test" with \""")
     * Then: 拼接的 JS 字符串里特殊字符被安全转义：
     *       - 双引号 → \" （JSON.quote 转义）
     *       - 反斜杠 → \\ （JSON.quote 转义）
     *       - 单引号原样保留（JSON 不必转义单引号；外层是双引号包裹，无字面量截断风险）
     *
     * 这是 escapeJsString 防御的核心 case：恶意 query 无法通过引号截断字面量注入 JS。
     */
    @Test
    fun `should escape special characters in query safely`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("3")
        }

        val rawQuery = "it's a \"test\" with \\"
        controller.find(rawQuery) { /* ignore */ }

        val js = captured.captured
        // 整体形如：window.ReaderFind && window.ReaderFind.find("it's a \"test\" with \\")
        // 1) 双引号被转义为 \"
        assertTrue("应转义内部双引号为 \\\"，实际: $js", js.contains("\\\""))
        // 2) 反斜杠被转义为 \\
        assertTrue("应转义反斜杠为 \\\\，实际: $js", js.contains("\\\\"))
        // 3) 单引号原样保留（JSON.quote 不必转义）
        assertTrue("单引号原样保留，实际: $js", js.contains("it's"))
        // 4) 整体以 `.find(` 后接双引号包裹的字面量结束
        assertTrue("应以 .find(\" 形式拼接", js.contains(".find(\""))
        // 5) 不应出现"原始未转义"的 `with \\)` 这样的危险序列（反斜杠未转义会把右括号吞掉）
        // 这里通过断言反斜杠成对出现（且至少有一对，因为原串有 1 个反斜杠 → \\）
        assertTrue(
            "原始 \\ 必须以 \\\\ 形式出现",
            js.contains("\\\\"),
        )
    }

    /**
     * Given: 含 </script> 序列的恶意 query
     * When: 调 controller.find
     * Then: </ 序列被转义为 <\/，避免在 evaluateJavascript 字面量解析时被误识别为 HTML 注入
     */
    @Test
    fun `should escape closing script tag in query`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("0")
        }

        controller.find("</script>") { /* ignore */ }

        val js = captured.captured
        assertTrue("应转义 </script> 为 <\\/script>，实际: $js", js.contains("<\\/script>"))
        // 确认整体仍是 .find("<\/script>") 形式，没有把字面量截断
        assertTrue(js.contains(".find(\""))
        assertTrue(js.endsWith(")"))
    }

    /**
     * Given: 含 U+2028（行分隔符）的 query —— ECMAScript 旧规范视为字符串内换行
     * When: 调 controller.find
     * Then: U+2028 被转义为   字面量
     */
    @Test
    fun `should escape U+2028 in query`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("0")
        }

        controller.find("a b") { /* ignore */ }

        val js = captured.captured
        assertTrue("应转义 U+2028 为字面量 \\u2028，实际: $js", js.contains("\\u2028"))
        assertFalse("结果不应包含真实 U+2028", js.contains(" "))
    }

    /**
     * Given: 空字符串 query
     * When: 调 controller.find("")
     * Then: 仍然调用 evaluateJavascript（JS 端会自行 short-circuit 为 0 匹配）；
     *       字面量为 `""`（escapeJsString 对空串的输出）
     *
     * 注意：FindInPageController.find 不在 Kotlin 端做空串 short-circuit，
     * 由 ReaderViewModel.updateFindQuery 负责短路 clear() 路径。
     *
     * P1-v6-2：onResult 签名升级为 FindResult，count=0 / index=-1 / page=-1。
     */
    @Test
    fun `should pass empty string through to evaluateJavascript`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("0")
        }

        var result = -99
        controller.find("") { result = it.count }

        val js = captured.captured
        assertTrue("空字符串应转义为 \"\"", js.contains(".find(\"\")"))
        assertEquals("JS 返回 0 匹配应回调 count=0", 0, result)
    }

    // ==================== next / prev ====================

    /**
     * Given: 当前章节有匹配项
     * When: 调 controller.next { ... }
     * Then: webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.next()", ...) 被调用
     *
     * P1-v5-2 更新：next/prev 回调签名变为 [FindInPageController.NavigateResult]
     * （含 index + pageInChapter）。本测试仅断言 index 字段，对应旧的纯 Int 语义。
     * parseNavigateResult 对"纯数字字符串"保持兼容：返回 NavigateResult(n, -1)。
     */
    @Test
    fun `should call evaluateJavascript with ReaderFind next on next`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("3")
        }

        var newIndex = -99
        controller.next { newIndex = it.index }

        val js = captured.captured
        assertTrue("应调用 window.ReaderFind.next，实际: $js", js.contains("window.ReaderFind.next()"))
        assertTrue("应包含 && 短路", js.contains("window.ReaderFind &&"))
        assertEquals("应回调新的 currentIndex=3", 3, newIndex)
    }

    /**
     * Given: 当前章节有匹配项
     * When: 调 controller.prev { ... }
     * Then: webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.prev()", ...) 被调用
     */
    @Test
    fun `should call evaluateJavascript with ReaderFind prev on prev`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("5")
        }

        var newIndex = -99
        controller.prev { newIndex = it.index }

        val js = captured.captured
        assertTrue("应调用 window.ReaderFind.prev，实际: $js", js.contains("window.ReaderFind.prev()"))
        assertEquals("应回调新的 currentIndex=5", 5, newIndex)
    }

    /**
     * Given: next 的 JS 返回 "null"（未注入或无匹配的兜底）
     * When: 调 controller.next { ... }
     * Then: callback 收到 NavigateResult(-1, -1)（parseNavigateResult 的兜底）
     *
     * 验证"null 兜底"行为：next/prev 的默认值是 -1（表示"无匹配"语义）。
     */
    @Test
    fun `should return -1 when next callback gets null`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue(null)
        }

        var newIndex = -99
        controller.next { newIndex = it.index }

        assertEquals("null 应回退到 -1（next/prev 兜底）", -1, newIndex)
    }

    /**
     * Given: next 的 JS 返回字符串 "null"（不是 Java null，而是 JS null 的 toString）
     * When: 调 controller.next
     * Then: callback 同样收到 NavigateResult(-1, -1)
     */
    @Test
    fun `should return -1 when next callback gets literal null string`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("null")
        }

        var newIndex = -99
        controller.next { newIndex = it.index }

        assertEquals("\"null\" 字符串也应回退到 -1", -1, newIndex)
    }

    /**
     * P1-v5-2 新增：next 的 JS 返回 JSON 字符串 `"{\"index\":2,\"page\":3}"`
     * （v5 navigate 的新返回形态）。
     * Then: 解析为 NavigateResult(index=2, pageInChapter=3)
     */
    @Test
    fun `should parse JSON navigate result and expose pageInChapter`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            // evaluateJavascript 把 JS 字符串值包成 JSON 字符串字面量：双引号 + 内层转义
            secondArg<ValueCallback<String>>().onReceiveValue("\"{\\\"index\\\":2,\\\"page\\\":3}\"")
        }

        var captured = FindInPageController.NavigateResult(-99, -99)
        controller.next { captured = it }

        assertEquals("应解析 JSON.index=2", 2, captured.index)
        assertEquals("应解析 JSON.page=3", 3, captured.pageInChapter)
    }

    /**
     * Given: find 的 JS 返回 "null"（极端 case，JS 未就绪）
     * When: 调 controller.find
     * Then: callback 收到 FindResult(count=0, index=-1, page=-1)
     *
     * P1-v6-2：onResult 签名升级为 FindResult；null 兜底 count=0。
     */
    @Test
    fun `should return 0 when find callback gets null`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue(null)
        }

        var result = -1
        controller.find("test") { result = it.count }

        assertEquals("find null 兜底 count 应为 0", 0, result)
    }

    // ==================== clear ====================

    /**
     * Given: controller 持有 WebView
     * When: 调 controller.clear()
     * Then: webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.clear()", null) 被调用
     *       （注意 callback 参数是 null，与 find/next/prev 不同）
     */
    @Test
    fun `should call evaluateJavascript with ReaderFind clear and null callback`() {
        val captured = slot<String>()
        every { webView.evaluateJavascript(capture(captured), any()) } returns Unit

        controller.clear()

        val js = captured.captured
        assertTrue("应调用 window.ReaderFind.clear，实际: $js", js.contains("window.ReaderFind.clear()"))
        assertTrue("应包含 && 短路保护", js.contains("window.ReaderFind &&"))
        // 验证调用以 null callback 形式发起（重载签名：evaluateJavascript(String, ValueCallback?)）
        verify(exactly = 1) {
            webView.evaluateJavascript(any<String>(), null)
        }
    }

    // ==================== P1-v5-2 JSON parsing edge cases ====================

    /**
     * P1-v5-2 prev 路径的 JSON 解析：与 next 对称，验证 prev() 也走 parseNavigateResult。
     * Given: prev 的 JS 返回 `"{\"index\":4,\"page\":2}"`
     * When: 调 controller.prev
     * Then: callback 拿到 NavigateResult(index=4, pageInChapter=2)
     */
    @Test
    fun `should parse JSON navigate result on prev`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("\"{\\\"index\\\":4,\\\"page\\\":2}\"")
        }

        var captured = FindInPageController.NavigateResult(-99, -99)
        controller.prev { captured = it }

        assertEquals(4, captured.index)
        assertEquals(2, captured.pageInChapter)
    }

    /**
     * P1-v5-2 兜底：JS 返回非 JSON 字符串（如 "abc"）
     * Then: parseNavigateResult 兜底为 NavigateResult(-1, -1)
     *
     * 验证 "纯文本非数字非 JSON" 路径走异常分支（JSONObject 构造抛 JSONException）
     */
    @Test
    fun `should fallback to minus one when navigate result is non-json non-numeric`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("\"abc\"")
        }

        var captured = FindInPageController.NavigateResult(-99, -99)
        controller.next { captured = it }

        assertEquals("非 JSON 非数字应回退到 index=-1", -1, captured.index)
        assertEquals("非 JSON 非数字应回退到 pageInChapter=-1", -1, captured.pageInChapter)
    }

    /**
     * P1-v5-2 兜底：JSON 缺少 index / page 字段
     * Given: JS 返回 `"{\"foo\":1}"`（既无 index 也无 page）
     * Then: NavigateResult(-1, -1) —— optInt 默认值是 -1
     */
    @Test
    fun `should fallback to minus one when navigate result json misses fields`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            // 序列化后的字符串：外层引号 + 内层转义双引号
            secondArg<ValueCallback<String>>().onReceiveValue("\"{\\\"foo\\\":1}\"")
        }

        var captured = FindInPageController.NavigateResult(-99, -99)
        controller.next { captured = it }

        assertEquals("缺 index 字段应回退到 -1", -1, captured.index)
        assertEquals("缺 page 字段应回退到 -1", -1, captured.pageInChapter)
    }

    /**
     * P1-v5-2 部分字段缺失：只有 index 没有 page
     * Given: JS 返回 `"{\"index\":7}"` （v5 边缘 case：JS 计算 viewport 失败时未回报 page）
     * Then: NavigateResult(index=7, pageInChapter=-1) —— optInt(page, -1) 兜底
     */
    @Test
    fun `should parse index and fallback page when navigate json has only index`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            secondArg<ValueCallback<String>>().onReceiveValue("\"{\\\"index\\\":7}\"")
        }

        var captured = FindInPageController.NavigateResult(-99, -99)
        controller.next { captured = it }

        assertEquals("应正确解析 index=7", 7, captured.index)
        assertEquals("缺 page 字段时应回退到 -1", -1, captured.pageInChapter)
    }

    /**
     * P1-v5-2 跨版本兼容：JS 返回老版本的纯数字（v4 contract）
     * Given: 老 WebView 未升级到 v5 JS，next 返回纯数字 "5"
     * Then: NavigateResult(index=5, pageInChapter=-1) —— parseNavigateResult 内
     *       `unwrapped.toIntOrNull()?.let { return NavigateResult(it, -1) }` 兜底
     */
    @Test
    fun `should support legacy numeric return for navigate as compatibility`() {
        every { webView.evaluateJavascript(any<String>(), any<ValueCallback<String>>()) } answers {
            // evaluateJavascript 对 Number 类型不会再包一层引号；直接 toString
            secondArg<ValueCallback<String>>().onReceiveValue("5")
        }

        var captured = FindInPageController.NavigateResult(-99, -99)
        controller.next { captured = it }

        assertEquals("老版本纯数字返回应解析为 index=5", 5, captured.index)
        assertEquals("老版本无 page 信息时应回退到 -1", -1, captured.pageInChapter)
    }
}
