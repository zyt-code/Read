package com.example.read.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FindInPageJs 常量内容回归测试（v4 章内搜索）。
 *
 * 验证策略：
 * - 不做端到端 JS 执行（JVM 单测无 JS 引擎，引入 V8/J2V8 会违反"不引入新依赖"约束）
 * - 仅做字符串关键标识断言：
 *   1) FIND_IN_PAGE_JS 包含 `window.ReaderFind.find` / `.next` / `.prev` / `.clear`
 *      四个对外 API 的入口标识
 *   2) FIND_IN_PAGE_CSS 包含 `mark.reader-find` 与 `mark.reader-find-current` 两个类选择器
 *
 * 这些字符串是 FindInPageController（通过 evaluateJavascript 拼接调用）以及
 * ReadingSettings.toReaderCss（拼接到阅读 CSS 末尾）的硬契约 —— 一旦常量被改名，
 * 章内搜索的高亮链路会全链路失效，本测试可作为"契约 lockdown"。
 *
 * 不修改生产代码；不引入新依赖。
 */
class FindInPageJsTest {

    // ==================== FIND_IN_PAGE_JS ====================

    /**
     * Given: FindInPageJs.FIND_IN_PAGE_JS
     * When: 检查关键 API 入口
     * Then: 应同时包含 ReaderFind.find / .next / .prev / .clear 四个挂载点
     *
     * 这四个 API 是 FindInPageController 通过 evaluateJavascript 调用的契约点，
     * 改名会导致章内搜索全链路 silent 失效（不报错但 JS 找不到方法 → callback 返回 null）。
     */
    @Test
    fun `should expose ReaderFind find next prev clear apis on window`() {
        val js = FindInPageJs.FIND_IN_PAGE_JS
        // window.ReaderFind 对象本身
        assertTrue("应挂载 window.ReaderFind 对象", js.contains("window.ReaderFind"))
        // 四个对外 API 的方法名（IIFE 内的 find / clear，以及对象字面量的 next/prev）
        assertTrue("应实现 find 方法", js.contains("find:"))
        assertTrue("应实现 next 方法", js.contains("next:"))
        assertTrue("应实现 prev 方法", js.contains("prev:"))
        assertTrue("应实现 clear 方法", js.contains("clear:"))
        // 进一步：FindInPageController.find 拼接的调用串 `window.ReaderFind.find(` 必能在脚本
        // 中找到对应的 find 方法定义。这里通过 `function find(` 的定义形式锁死。
        assertTrue("应定义 function find(query)", js.contains("function find("))
    }

    /**
     * Given: FIND_IN_PAGE_JS
     * When: 检查 mark 元素的 className
     * Then: 必须使用 reader-find / reader-find-current 这两个类名
     *
     * 这两个 className 与 FIND_IN_PAGE_CSS 的两个类选择器一一对应；
     * 改名一侧而不改名另一侧会导致高亮失效。
     */
    @Test
    fun `should use reader-find classnames consistent with css selectors`() {
        val js = FindInPageJs.FIND_IN_PAGE_JS
        // 普通匹配项的 className 赋值
        assertTrue("应给匹配 mark 元素设置 className = reader-find", js.contains("'reader-find'"))
        // 当前选中项追加的 className
        assertTrue(
            "应通过 classList.add 给当前项加 reader-find-current",
            js.contains("'reader-find-current'"),
        )
    }

    /**
     * Given: FIND_IN_PAGE_JS
     * When: 检查 IIFE 包裹与状态闭包
     * Then: 应以 `(function() {` 开头，闭包内声明 matches / currentIndex 局部状态
     *
     * 防止后续重构把 IIFE 改成全局变量泄露 window 命名空间。
     */
    @Test
    fun `should wrap implementation in IIFE to avoid global pollution`() {
        val js = FindInPageJs.FIND_IN_PAGE_JS
        // IIFE 起始
        assertTrue("应使用 IIFE 包裹避免全局污染", js.contains("(function()"))
        // 闭包内的状态变量
        assertTrue("应在闭包内声明 matches 数组", js.contains("var matches"))
        assertTrue("应在闭包内声明 currentIndex", js.contains("var currentIndex"))
    }

    /**
     * Given: FIND_IN_PAGE_JS
     * When: 检查 TreeWalker / NodeFilter 使用
     * Then: 应使用 document.createTreeWalker + NodeFilter.SHOW_TEXT 仅遍历文本节点
     *
     * 这是 find 的核心实现细节：避免破坏 <script>/<style> 等元素的内联内容。
     */
    @Test
    fun `should use TreeWalker to traverse text nodes only`() {
        val js = FindInPageJs.FIND_IN_PAGE_JS
        assertTrue("应使用 createTreeWalker 遍历", js.contains("createTreeWalker"))
        assertTrue("应过滤为仅文本节点", js.contains("NodeFilter.SHOW_TEXT"))
        // 显式排除 SCRIPT 和 STYLE
        assertTrue("应排除 SCRIPT 标签", js.contains("SCRIPT"))
        assertTrue("应排除 STYLE 标签", js.contains("STYLE"))
    }

    // ==================== FIND_IN_PAGE_CSS ====================

    /**
     * Given: FindInPageJs.FIND_IN_PAGE_CSS
     * When: 检查两个高亮类选择器
     * Then: 应包含 `mark.reader-find` 与 `mark.reader-find-current` 两个选择器
     *
     * 这两个选择器是 reader CSS 与 JS DOM 操作的耦合点（className 与选择器须一致），
     * 也是任务 B1 节明确要求的断言对象。
     */
    @Test
    fun `should declare both reader-find and reader-find-current css selectors`() {
        val css = FindInPageJs.FIND_IN_PAGE_CSS
        // 普通匹配项的样式选择器
        assertTrue(
            "CSS 应包含 mark.reader-find 选择器",
            css.contains("mark.reader-find"),
        )
        // 当前选中项的样式选择器
        assertTrue(
            "CSS 应包含 mark.reader-find-current 选择器",
            css.contains("mark.reader-find-current"),
        )
    }

    /**
     * Given: FIND_IN_PAGE_CSS
     * When: 检查高亮配色
     * Then: 应同时包含柔和黄 (#FFE066) 与橙色 (#FF9933)，
     *       以保证暗黑主题下仍有足够对比度（详见 FEAT_REPORT_v4.md）
     */
    @Test
    fun `should declare yellow and orange highlight background colors`() {
        val css = FindInPageJs.FIND_IN_PAGE_CSS
        assertTrue("普通匹配应用 #FFE066 黄色", css.contains("#FFE066"))
        assertTrue("当前选中应用 #FF9933 橙色", css.contains("#FF9933"))
    }

    /**
     * Given: FIND_IN_PAGE_CSS
     * When: 检查文字颜色
     * Then: 应使用 `color: inherit` 让 mark 内文字继承正文颜色，
     *       避免暗黑主题下浏览器默认 mark 黑色字体造成低对比
     */
    @Test
    fun `should use inherit color so dark theme keeps contrast`() {
        val css = FindInPageJs.FIND_IN_PAGE_CSS
        assertTrue("应使用 color: inherit", css.contains("color: inherit"))
    }

    /**
     * Given: 两个常量
     * When: 检查它们非空
     * Then: 任何一方为空都说明 v4 feature 没编译进来
     */
    @Test
    fun `should not be empty strings`() {
        assertFalse(
            "FIND_IN_PAGE_JS 不应为空",
            FindInPageJs.FIND_IN_PAGE_JS.isBlank(),
        )
        assertFalse(
            "FIND_IN_PAGE_CSS 不应为空",
            FindInPageJs.FIND_IN_PAGE_CSS.isBlank(),
        )
    }
}
