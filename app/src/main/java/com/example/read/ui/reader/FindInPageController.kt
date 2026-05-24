package com.example.read.ui.reader

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject

/**
 * 章节内搜索控制器（v4 feature: find-in-page）。
 *
 * 职责：
 * - 持有当前章节的 WebView 引用
 * - 通过 [WebView.evaluateJavascript] 调用 [FindInPageJs] 注入的 `window.ReaderFind`
 *   暴露的查询/导航/清理函数
 * - 把 JS 返回值（字符串形式的数字 / JSON）解析后回调给上层
 *
 * 与既有架构的集成：
 * - 不引入任何新的 JavaScript 桥（不需要 AndroidBridge），因为 evaluateJavascript
 *   的 ValueCallback 已经足够回传查询结果。这绕开了 PaginationBridge 的 nonce 校验
 *   机制（P0-3），降低耦合，且不增加额外的攻击面
 * - 使用顶层 [escapeJsString]（位于 JsEscaping.kt）转义用户输入，防止恶意关键词
 *   通过引号 / 反斜杠 / U+2028 等字符破坏 JS 字符串字面量
 *
 * 生命周期：
 * - 每次切换章节时由 [ReaderScreen] 创建新的 controller 并注入新章节的 WebView
 * - 控制器本身是无状态的轻量对象，可随章节切换自由替换；JS 端状态由
 *   `window.ReaderFind` 闭包独立管理
 *
 * 线程模型：
 * - 所有 WebView 操作必须在主线程进行；evaluateJavascript 的 ValueCallback 也在
 *   主线程触发，因此回调中可直接更新 UI 状态而无需切线程
 *
 * @param webView 当前章节的 WebView（必须已加载完毕，且已注入 FIND_IN_PAGE_JS）
 */
class FindInPageController(private val webView: WebView) {

    /** WebView 主线程 Handler，用于 ValueCallback 之外的兜底（理论上 evaluateJavascript 自身已在主线程） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 单次导航结果：JS 端 navigate(delta) 的解析后形态。
     *
     * P1-v5-2 引入：
     * - index：新的 currentIndex（0-based）；-1 表无匹配
     * - pageInChapter：匹配项位于章内的第几页（0-based）；-1 表无匹配 / 计算失败
     *
     * Kotlin 端用 pageInChapter 驱动 ViewPager2 翻页到匹配项所在的全局页，
     * 修复 v4 已知问题"scrollIntoView 不联动 ViewPager2"。
     */
    data class NavigateResult(val index: Int, val pageInChapter: Int)

    /**
     * 单次查找结果：JS 端 find(query) 的解析后形态（P1-v6-2 新增）。
     *
     * - count：匹配总数（>= 0）；0 表示无匹配
     * - index：首个被选中匹配的 0-based 索引；-1 表示无匹配
     * - pageInChapter：首个匹配所在的章内页码（0-based）；-1 表示无匹配 / 计算失败
     *
     * 升级动机：旧版 find 的 onResult 签名是 `(Int) -> Unit`（仅返回 count），
     * 丢弃了 JS 端 navigate(0) 算出的 pageInChapter，导致全书搜索跨章跳转后
     * 用户停在新章第 1 页（即便匹配项在第 5 页）。新签名 `(FindResult) -> Unit`
     * 让 ViewModel 能在 attachFindController 消费 pendingFindAfterJump 时
     * 同时驱动 ViewPager2 翻到匹配所在页，闭环全书搜索的核心 UX。
     */
    data class FindResult(val count: Int, val index: Int, val pageInChapter: Int)

    /**
     * 在当前章节内查找关键词并高亮所有匹配项。
     *
     * 行为：
     * - query 为空时 JS 侧会调用 clear() 并返回 `{count:0, index:-1, page:-1}`
     * - 否则匹配总数 >= 0；JS 内部会默认选中第一个匹配并 scrollIntoView
     * - 大小写不敏感，正则元字符已在 JS 端转义为字面量
     *
     * P1-v6-2 升级：onResult 签名从 `(Int) -> Unit` 升级为 `(FindResult) -> Unit`。
     * JS 端 find() 现在返回 JSON `{count, index, page}`，内部 navigate(0) 算出的
     * pageInChapter 被合并到返回值。ViewModel 拿到 FindResult 后可以：
     * - 用 count 更新 UI 计数（_findCount）
     * - 用 index 同步当前选中项（_findCurrent）
     * - 用 pageInChapter 调 onFindMatchLocated 驱动 ViewPager2 翻页（仅跨章跳转场景使用）
     *
     * @param query 用户输入的关键词
     * @param onResult 异步回调：JS 计算出的 FindResult（主线程）
     */
    fun find(query: String, onResult: (FindResult) -> Unit) {
        // 使用统一的 escapeJsString 防御 XSS / 字面量截断（与 PAGINATION 路径同源）
        val escaped = escapeJsString(query)
        webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.find($escaped)") { value ->
            onResult(parseFindResult(value))
        }
    }

    /**
     * 跳转到下一个匹配项。
     *
     * P1-v5-2：返回值升级为 [NavigateResult]，同时回传匹配项所在章内页码，
     * 供 ViewModel 驱动 ViewPager2 翻页（修复 scrollIntoView 不联动 ViewPager2）。
     *
     * @param onResult 异步回调：新的 currentIndex 与匹配项所在章内页码；index=-1 表无匹配
     */
    fun next(onResult: (NavigateResult) -> Unit) {
        webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.next()") { value ->
            onResult(parseNavigateResult(value))
        }
    }

    /**
     * 跳转到上一个匹配项。
     *
     * @param onResult 异步回调：新的 currentIndex 与匹配项所在章内页码；index=-1 表无匹配
     */
    fun prev(onResult: (NavigateResult) -> Unit) {
        webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.prev()") { value ->
            onResult(parseNavigateResult(value))
        }
    }

    /**
     * 清理章内所有高亮 mark，恢复 DOM 到搜索前状态。
     *
     * 切换章节、退出搜索栏、用户清空输入框时都应调用。
     * 即使 WebView 尚未注入 FIND_IN_PAGE_JS（极少数情况下，章节切换早于 onPageFinished），
     * 这里也用短路语法 `window.ReaderFind &&` 防御性兜底，避免 JS 报错。
     */
    fun clear() {
        webView.evaluateJavascript("window.ReaderFind && window.ReaderFind.clear()", null)
    }

    /**
     * 把 evaluateJavascript 回传的 JSON 字符串解析为 [NavigateResult]。
     *
     * JS 端 navigate(delta) 返回 `JSON.stringify({index:i, page:p})`，
     * evaluateJavascript 的 ValueCallback 会把 JS 的字符串值再包一层 JSON quote，
     * 形如：`"{\"index\":2,\"page\":1}"`。所以这里需要：
     * 1) 先把 outer JSON.parse（用 org.json.JSONObject）剥掉外层字符串引号
     *    —— 我们用 startsWith("\"") 判断后再用 JSONObject 解析
     * 2) 用 inner JSONObject 取 index / page 字段
     *
     * 若解析失败（null / 老版本 JS 返回纯数字 / 字段缺失），优雅降级为
     * `NavigateResult(unwrapped.toIntOrNull() ?: -1, -1)` —— 把数字解析路径保留，
     * 确保 v4 → v5 滚动升级期间（用户尚未销毁旧 WebView）不抛异常。
     */
    private fun parseNavigateResult(raw: String?): NavigateResult {
        if (raw == null || raw == "null") return NavigateResult(-1, -1)
        return try {
            // evaluateJavascript 把 JS 字符串值包成 JSON 字符串字面量：
            // 例如 JS 返回 '{"index":2,"page":1}' → ValueCallback 收到 `"{\"index\":2,\"page\":1}"`
            // 需要先把外层引号剥掉，再 parse 内层 JSON。
            val unwrapped = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                // 用 JSONObject.tokener 取消转义最稳：构造一个临时数组反序列化
                org.json.JSONTokener(raw).nextValue() as? String ?: raw
            } else {
                // 老版本 JS（v4）返回纯数字时走兼容路径
                raw
            }
            // 若 unwrap 后是纯数字（兼容旧 JS 返回 index 数字），走数字兜底
            unwrapped.toIntOrNull()?.let { return NavigateResult(it, -1) }
            val obj = JSONObject(unwrapped)
            val index = obj.optInt("index", -1)
            val page = obj.optInt("page", -1)
            NavigateResult(index, page)
        } catch (e: Throwable) {
            // 任何解析异常（包括 JSONException / 类型转换）都视作无匹配
            NavigateResult(-1, -1)
        }
    }

    /**
     * 把 evaluateJavascript 回传的 JSON 字符串解析为 [FindResult]（P1-v6-2 新增）。
     *
     * JS 端 find(query) 现在返回 `JSON.stringify({count:c, index:i, page:p})`，
     * ValueCallback 同 navigate 一样被外层 JSON 字符串包了一层。
     *
     * 兼容性兜底：
     * - 旧版 JS（v4 / v5 早期）find 返回纯数字 matches.length → 解析为
     *   `FindResult(count=N, index=if(N>0) 0 else -1, page=-1)`，让 ViewModel
     *   仍能拿到 count 更新计数；page=-1 表示"无章内页码"，ViewModel 会跳过翻页。
     * - 解析失败（null / 空字符串 / 异常）→ `FindResult(0, -1, -1)`，等价无匹配。
     */
    private fun parseFindResult(raw: String?): FindResult {
        if (raw == null || raw == "null") return FindResult(0, -1, -1)
        return try {
            // 同 parseNavigateResult，先剥掉 ValueCallback 包裹的外层 JSON 字符串引号
            val unwrapped = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                org.json.JSONTokener(raw).nextValue() as? String ?: raw
            } else {
                raw
            }
            // 兼容旧版纯数字返回（v4 / v5 early）：count = unwrapped, index/page 兜底
            unwrapped.toIntOrNull()?.let { count ->
                val index = if (count > 0) 0 else -1
                return FindResult(count, index, -1)
            }
            val obj = JSONObject(unwrapped)
            val count = obj.optInt("count", 0)
            val index = obj.optInt("index", if (count > 0) 0 else -1)
            val page = obj.optInt("page", -1)
            FindResult(count, index, page)
        } catch (e: Throwable) {
            // 任何解析异常（含 JSONException）都视作无匹配
            FindResult(0, -1, -1)
        }
    }
}
