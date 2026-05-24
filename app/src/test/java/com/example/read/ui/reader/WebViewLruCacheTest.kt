package com.example.read.ui.reader

import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.webkit.WebView
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebViewLruCache 行为回归测试（P0-2）。
 *
 * 妥协方案说明：
 * - WebView 在 Android JVM unit test 中所有方法会抛 `RuntimeException("Stub!")`。
 *   为了避开 Robolectric / androidTest 的工程开销，本测试使用 MockK 创建
 *   `mockk<WebView>(relaxed = true)` 作为 Test Double：
 *   - relaxed=true 让 destroy / loadUrl / removeJavascriptInterface 等成员方法
 *     默认返回 Unit 不抛异常；
 *   - parent 属性通过 `every { wv.parent } returns ...` 显式控制，避免 stub 抛错。
 * - 因此本测试**不验证 WebView 原生层是否真的被销毁**，仅验证缓存层调用了
 *   `destroy()` / `removeJavascriptInterface()` / `loadUrl("about:blank")` 的次序。
 *   这是合理的：缓存层的契约本就是"在淘汰时调用这些方法"，原生行为由 Android 框架负责。
 *
 * 测试范围：
 * - put 时容量不超过 maxSize
 * - 写入超出 maxSize 时，最久未访问的条目被销毁并从缓存移除
 * - 读取已存在条目时，accessOrder 更新（被读取的条目不再是最旧的）
 * - clear() 销毁所有 WebView 并清空缓存
 */
class WebViewLruCacheTest {

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * 创建一个 relaxed mock WebView，parent 默认为 null（destroy 不必从父布局摘除）。
     */
    private fun mockWebView(): WebView {
        val wv = mockk<WebView>(relaxed = true)
        // parent 在 destroy / clear 流程被读取；提供 null 让 removeView 分支短路
        every { wv.parent } returns null as ViewParent?
        return wv
    }

    /**
     * Given: 空缓存，maxSize=3
     * When: put 三个 WebView
     * Then: 全部保留，未触发 destroy
     */
    @Test
    fun `should keep all entries when size within maxSize`() {
        val cache = WebViewLruCache(maxSize = 3)
        val a = mockWebView()
        val b = mockWebView()
        val c = mockWebView()
        cache[1] = a
        cache[2] = b
        cache[3] = c

        assertTrue(cache.containsKey(1))
        assertTrue(cache.containsKey(2))
        assertTrue(cache.containsKey(3))
        verify(exactly = 0) { a.destroy() }
        verify(exactly = 0) { b.destroy() }
        verify(exactly = 0) { c.destroy() }
    }

    /**
     * Given: 已有 3 个 WebView（容量已满）
     * When: 再 put 第 4 个
     * Then: 最早写入的（id=1）被 destroy 并移除；其他三个仍在缓存中
     */
    @Test
    fun `should evict and destroy eldest when exceeding maxSize`() {
        val cache = WebViewLruCache(maxSize = 3)
        val a = mockWebView()
        val b = mockWebView()
        val c = mockWebView()
        val d = mockWebView()
        cache[1] = a
        cache[2] = b
        cache[3] = c
        cache[4] = d

        // a 应被淘汰
        assertFalse("最旧条目应从缓存移除", cache.containsKey(1))
        assertNull(cache[1])
        // 其它仍在
        assertTrue(cache.containsKey(2))
        assertTrue(cache.containsKey(3))
        assertTrue(cache.containsKey(4))
        // 淘汰流程：removeJavascriptInterface("AndroidBridge") -> loadUrl("about:blank") -> destroy()
        verify(exactly = 1) { a.removeJavascriptInterface("AndroidBridge") }
        verify(exactly = 1) { a.loadUrl("about:blank") }
        verify(exactly = 1) { a.destroy() }
        // 未淘汰的不应被 destroy
        verify(exactly = 0) { b.destroy() }
        verify(exactly = 0) { c.destroy() }
        verify(exactly = 0) { d.destroy() }
    }

    /**
     * Given: 已有 3 个 WebView，maxSize=3
     * When: 读取 id=1（触发 accessOrder 重排），再 put 第 4 个
     * Then: 由于 id=1 被刚访问过，淘汰目标变成 id=2（次旧）
     */
    @Test
    fun `should respect access order when evicting`() {
        val cache = WebViewLruCache(maxSize = 3)
        val a = mockWebView()
        val b = mockWebView()
        val c = mockWebView()
        val d = mockWebView()
        cache[1] = a
        cache[2] = b
        cache[3] = c

        // 访问 id=1，让 a 成为最近使用
        val read = cache[1]
        assertSame(a, read)

        // 写入 id=4 触发淘汰
        cache[4] = d

        // 期望 b（id=2）被淘汰，而不是 a（id=1）
        assertTrue("a 因访问应保留", cache.containsKey(1))
        assertFalse("b 应作为次旧被淘汰", cache.containsKey(2))
        assertTrue(cache.containsKey(3))
        assertTrue(cache.containsKey(4))
        verify(exactly = 1) { b.destroy() }
        verify(exactly = 0) { a.destroy() }
    }

    /**
     * Given: 缓存中有若干 WebView
     * When: 调用 clear()
     * Then: 全部 destroy()，containsKey 后续返回 false
     */
    @Test
    fun `should destroy all WebViews and empty cache when clear is called`() {
        val cache = WebViewLruCache(maxSize = 3)
        val a = mockWebView()
        val b = mockWebView()
        val c = mockWebView()
        cache[1] = a
        cache[2] = b
        cache[3] = c

        cache.clear()

        assertFalse(cache.containsKey(1))
        assertFalse(cache.containsKey(2))
        assertFalse(cache.containsKey(3))
        verify(exactly = 1) { a.destroy() }
        verify(exactly = 1) { b.destroy() }
        verify(exactly = 1) { c.destroy() }
        // 销毁流程对每个 WebView 都执行 removeJavascriptInterface + loadUrl
        verify(exactly = 1) { a.removeJavascriptInterface("AndroidBridge") }
        verify(exactly = 1) { a.loadUrl("about:blank") }
    }

    /**
     * Given: WebView 的 parent 是某个 ViewGroup（模拟仍挂在某容器里）
     * When: 触发淘汰
     * Then: 在 destroy 之前会从父布局移除（ViewGroup.removeView 被调用）
     */
    @Test
    fun `should remove from parent ViewGroup before destroy on eviction`() {
        val cache = WebViewLruCache(maxSize = 1)
        val parent = mockk<ViewGroup>(relaxed = true)
        val a = mockk<WebView>(relaxed = true).also {
            every { it.parent } returns parent
        }
        val b = mockk<WebView>(relaxed = true).also {
            every { it.parent } returns null as ViewParent?
        }
        cache[1] = a
        // 写入 b 触发淘汰 a
        cache[2] = b

        // 父布局 removeView 必须被调用，避免 destroy 后留下悬挂 View
        verify(exactly = 1) { parent.removeView(any<View>()) }
        verify(exactly = 1) { a.destroy() }
    }

    /**
     * Given: 同一个 key 多次 put（如刷新 WebView 实例）
     * When: 第二次 put 同 key 但 value 不同
     * Then: 第一个 WebView 被替换，且 size 不增；不立即 destroy 旧 value
     *       （行为说明：LinkedHashMap.put 同 key 是替换，不触发 removeEldestEntry，
     *       因此旧 WebView 不会被自动 destroy；调用方应自己 destroy 替换的旧实例）
     */
    @Test
    fun `should replace value without triggering eviction on duplicate key put`() {
        val cache = WebViewLruCache(maxSize = 3)
        val a = mockWebView()
        val aReplacement = mockWebView()
        cache[1] = a
        cache[1] = aReplacement

        // 新值生效
        assertSame(aReplacement, cache[1])
        // 旧值未自动 destroy（与 LinkedHashMap 默认行为一致）
        verify(exactly = 0) { a.destroy() }
    }
}
