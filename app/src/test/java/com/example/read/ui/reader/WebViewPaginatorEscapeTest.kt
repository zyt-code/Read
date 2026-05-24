package com.example.read.ui.reader

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebViewPaginator.escapeJsString 与 newNonce 的纯 JVM 单元测试（回归 P0-4）。
 *
 * 测试目标：
 * - 转义后的字符串能安全嵌入 JavaScript 字面量
 * - 不会被 U+2028 / U+2029（ECMAScript 旧规范中视为换行）截断
 * - </ 序列被转义为 <\/，避免在 <script>/<style> 注入场景误闭合
 * - 单引号、双引号、反斜杠、控制字符均被 JSON.quote 标准转义
 * - 纯 ASCII 与空字符串走兜底路径
 *
 * Android 单元测试限制：
 * - `org.json.JSONObject.quote()` 在标准 Android stub 中抛 `RuntimeException("Stub!")`
 * - 通过 mockkStatic 把 `JSONObject.quote` 替换为一个手写的合法 JSON 转义实现，
 *   以便测试 `escapeJsString` 在 JSON 转义结果之上额外做的 U+2028/2029、</ 增量转义。
 * - 此妥协方案是必要的：要么引入 org.json:json 真实依赖（违反"不引入新依赖"约束），
 *   要么使用 Robolectric（同上）。当前选择 mockkStatic + 手写参考实现是最小侵入的方式。
 *
 * 注意：手写的 JSON.quote 替身覆盖 escapeJsString 实际依赖的全部输入特性（双引号、
 * 反斜杠、控制字符、U+2028/U+2029、< 字符），与真实 org.json.JSONObject.quote 的输出
 * 在这些字符上行为一致。
 */
class WebViewPaginatorEscapeTest {

    @Before
    fun setUp() {
        // Android stub 中 JSONObject.quote 抛 Stub!，需 mockkStatic 替换为真实实现
        mockkStatic(JSONObject::class)
        every { JSONObject.quote(any()) } answers {
            referenceJsonQuote(firstArg<String?>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * 参考实现：模拟 org.json.JSONObject.quote 的核心行为，
     * 用于在 Android 单元测试中替换 Stub。仅覆盖 escapeJsString 关心的转义点：
     * - null 输入 -> "\"\""
     * - 包裹双引号
     * - 反斜杠、双引号、控制字符、退格、换行、回车、制表符均按 JSON 规范转义
     * - 其他 ASCII 直接输出
     * - 非 ASCII 与 U+2028/U+2029 直接输出（escapeJsString 的二次转义会处理）
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

    // ==================== 基础字符与边界 ====================

    /**
     * Given: 空字符串
     * When: 调用 escapeJsString
     * Then: 返回 `""`（一个仅含双引号的 JS 空字符串字面量），且可被嵌入 JS 字面量
     */
    @Test
    fun `should escape empty string to JS empty literal`() {
        val result = WebViewPaginator.escapeJsString("")
        assertEquals("\"\"", result)
    }

    /**
     * Given: 纯 ASCII 文本（无需特殊处理的字符）
     * When: 调用 escapeJsString
     * Then: 返回带双引号包裹的字面量，内部字符未被破坏
     */
    @Test
    fun `should escape plain ASCII safely with surrounding quotes`() {
        val result = WebViewPaginator.escapeJsString("hello world")
        assertEquals("\"hello world\"", result)
    }

    // ==================== 引号与反斜杠 ====================

    /**
     * Given: 含单引号的字符串
     * When: 调用 escapeJsString
     * Then: 单引号不必转义（JSON 不对单引号转义），但整体被双引号包裹，可直接放入 JS 字面量
     */
    @Test
    fun `should not corrupt single quotes`() {
        val raw = "it's"
        val result = WebViewPaginator.escapeJsString(raw)
        // 直接拼接到 evaluateJavascript 后应是 "it's"
        assertTrue("结果应是双引号包裹，实际: $result", result.startsWith("\"") && result.endsWith("\""))
        assertTrue(result.contains("it"))
        assertTrue(result.contains("'s"))
    }

    /**
     * Given: 含双引号的字符串
     * When: 调用 escapeJsString
     * Then: 内部双引号被转义为 \"，外层仍是双引号包裹
     */
    @Test
    fun `should escape inner double quotes`() {
        val raw = "say \"hi\""
        val result = WebViewPaginator.escapeJsString(raw)
        // 必须包含 \" 表示转义后的双引号
        assertTrue("结果应含 \\\" 转义序列，实际: $result", result.contains("\\\""))
        // 整体首尾仍是包裹用的双引号
        assertTrue(result.startsWith("\""))
        assertTrue(result.endsWith("\""))
    }

    /**
     * Given: 含反斜杠的字符串
     * When: 调用 escapeJsString
     * Then: 反斜杠被转义为 \\
     */
    @Test
    fun `should escape backslash`() {
        val raw = "C:\\path"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("反斜杠应被转义为 \\\\，实际: $result", result.contains("\\\\"))
    }

    // ==================== 控制字符 ====================

    /**
     * Given: 含 \n（换行符）的字符串
     * When: 调用 escapeJsString
     * Then: 换行被转义为字面量 \n（两字符），原始 LF 字节不再出现
     */
    @Test
    fun `should escape newline to literal backslash n`() {
        val raw = "line1\nline2"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("结果应含 \\n 转义序列，实际: $result", result.contains("\\n"))
        assertFalse("结果不应包含真实换行符", result.contains("\n"))
    }

    /**
     * Given: 含 \r（回车符）的字符串
     * When: 调用 escapeJsString
     * Then: 回车被转义为字面量 \r（两字符）
     */
    @Test
    fun `should escape carriage return to literal backslash r`() {
        val raw = "a\rb"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("结果应含 \\r 转义序列，实际: $result", result.contains("\\r"))
        assertFalse("结果不应包含真实回车符", result.contains("\r"))
    }

    /**
     * Given: 含 \t（制表符）的字符串
     * When: 调用 escapeJsString
     * Then: 制表符被转义为字面量 \t（两字符）
     */
    @Test
    fun `should escape tab to literal backslash t`() {
        val raw = "a\tb"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("结果应含 \\t 转义序列，实际: $result", result.contains("\\t"))
        assertFalse("结果不应包含真实制表符", result.contains("\t"))
    }

    // ==================== </script> / </style> 边界 ====================

    /**
     * Given: 含 </script> 的字符串
     * When: 调用 escapeJsString
     * Then: </ 序列被转义为 <\/，整体不再出现完整的 </script> 序列
     */
    @Test
    fun `should escape closing script tag prefix`() {
        val raw = "</script>"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("应转义为 <\\/script>，实际: $result", result.contains("<\\/script>"))
        // 不应保留原始的 </script> 字面量（除外层引号外）
        assertFalse("结果不应原样保留 </script>，实际: $result", result.contains("</script>"))
    }

    /**
     * Given: 含 </style> 的字符串（典型 CSS 注入场景）
     * When: 调用 escapeJsString
     * Then: </ 序列被转义，</style> 不再原样出现
     */
    @Test
    fun `should escape closing style tag prefix`() {
        val raw = "body{color:red;}</style><script>alert(1)</script>"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("应转义为 <\\/style>，实际: $result", result.contains("<\\/style>"))
        assertFalse("结果不应原样保留 </style>", result.contains("</style>"))
        assertFalse("结果不应原样保留 </script>", result.contains("</script>"))
    }

    /**
     * Given: 含单独 < 字符（不组成 </ 序列）的字符串
     * When: 调用 escapeJsString
     * Then: 单独的 < 不被转义（保持原样）
     */
    @Test
    fun `should not over-escape standalone less than`() {
        val raw = "a < b"
        val result = WebViewPaginator.escapeJsString(raw)
        // 应保留普通的 <
        assertTrue("普通 < 不应被转义，实际: $result", result.contains("a < b"))
        assertFalse("不应错误地转义为 <\\/", result.contains("<\\/"))
    }

    // ==================== U+2028 / U+2029 ====================

    /**
     * Given: 含 U+2028（Line Separator）的字符串
     * When: 调用 escapeJsString
     * Then: U+2028 被显式转义为   字面量
     *
     * 这是 P0-4 修复的核心：U+2028 在 ECMAScript 旧规范中会被视为换行，
     * 不转义会破坏 JS 字符串字面量的边界。
     */
    @Test
    fun `should escape U+2028 line separator`() {
        val raw = "a b"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("应转义为 \\u2028 字面量，实际: $result", result.contains("\\u2028"))
        // 真实的 U+2028 不应出现在结果中（不会破坏 JS 字面量）
        assertFalse("不应包含真实 U+2028", result.contains(" "))
    }

    /**
     * Given: 含 U+2029（Paragraph Separator）的字符串
     * When: 调用 escapeJsString
     * Then: U+2029 被显式转义为   字面量
     */
    @Test
    fun `should escape U+2029 paragraph separator`() {
        val raw = "a b"
        val result = WebViewPaginator.escapeJsString(raw)
        assertTrue("应转义为 \\u2029 字面量，实际: $result", result.contains("\\u2029"))
        assertFalse("不应包含真实 U+2029", result.contains(" "))
    }

    // ==================== 综合场景：CSS 注入 ====================

    /**
     * Given: 完整的 CSS 注入场景（含多个特殊字符）
     * When: 调用 escapeJsString
     * Then: 结果可拼入 updateCSS($result) 形式安全注入 JS
     */
    @Test
    fun `should produce safe injectable JS literal for full css`() {
        // 模拟实际的 CSS 注入：包含换行、反斜杠、引号、</style>、U+2028
        val css = "body{\n  font-family: \"Serif\";\n  background: \\#fff;\n} </style> end"
        val result = WebViewPaginator.escapeJsString(css)
        // 整体被双引号包裹
        assertTrue(result.startsWith("\""))
        assertTrue(result.endsWith("\""))
        // 关键转义都已生效
        assertTrue("应转义换行符", result.contains("\\n"))
        assertTrue("应转义反斜杠", result.contains("\\\\"))
        assertTrue("应转义双引号", result.contains("\\\""))
        assertTrue("应转义 </style>", result.contains("<\\/style>"))
        assertTrue("应转义 U+2028", result.contains("\\u2028"))
        // 真实的危险字符不存在
        assertFalse(result.contains("\n"))
        assertFalse(result.contains(" "))
        assertFalse(result.contains("</style>"))
    }

    // ==================== newNonce ====================

    /**
     * Given: 多次调用 newNonce
     * When: 每次都返回一个新值
     * Then: 长度固定（16 字节 hex = 32 个字符），仅含 0-9a-f
     */
    @Test
    fun `should generate 32-char hex nonce with valid charset`() {
        val nonce = WebViewPaginator.newNonce()
        assertEquals("nonce 长度应为 32 个字符", 32, nonce.length)
        // 仅含小写 hex 字符
        assertTrue(
            "nonce 应仅含 0-9a-f，实际: $nonce",
            nonce.all { it in '0'..'9' || it in 'a'..'f' },
        )
    }

    /**
     * Given: 多次调用 newNonce（基本随机性）
     * When: 比较两次的结果
     * Then: 两次返回的 nonce 不相同（理论上还有 1/2^128 的碰撞概率，可忽略）
     */
    @Test
    fun `should generate distinct nonce values on subsequent calls`() {
        val n1 = WebViewPaginator.newNonce()
        val n2 = WebViewPaginator.newNonce()
        assertNotNull(n1)
        assertNotNull(n2)
        assertFalse("连续两次 nonce 不应相同（碰撞概率 1/2^128）", n1 == n2)
    }
}
