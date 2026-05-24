package com.example.read.ui.reader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ReadingSettings 与 ReadingSettingsManager 的单元测试。
 *
 * 测试范围：
 * - 数据类默认值（fontSize=18, lineHeight=1.8, fontFamily="Serif", backgroundColor=WHITE）
 * - copy() 创建新实例时字段变更
 * - BackgroundColor 枚举的 composeColor / textColor / argb 字段
 * - getTypeface(): Serif / Sans-serif / Monospace / 未知名称的回退
 * - toReaderCss(): font-family 映射、字号 px、行高、背景色与文字色 hex 输出
 * - ReadingSettingsManager.load() 默认值与显式值
 * - ReadingSettingsManager.save() 写入正确的键
 *
 * 注意：Typeface / android.graphics.Color 是 Android 类，
 * 在纯 JVM 单测中调用其常量返回 0 / null，需通过 mockkStatic 注入。
 * 参考 ReaderViewModelTest 中的做法。
 */
class ReadingSettingsTest {

    @Before
    fun setUp() {
        // 模拟 Typeface 常量，单元测试 JVM 下默认是 null
        mockkStatic(Typeface::class)
        val serif = mockk<Typeface>(relaxed = true)
        val sans = mockk<Typeface>(relaxed = true)
        val mono = mockk<Typeface>(relaxed = true)
        every { Typeface.SERIF } returns serif
        every { Typeface.SANS_SERIF } returns sans
        every { Typeface.MONOSPACE } returns mono

        // 模拟 android.graphics.Color 常量与 argb() 方法
        mockkStatic(android.graphics.Color::class)
        every { android.graphics.Color.WHITE } returns 0xFFFFFFFF.toInt()
        every { android.graphics.Color.argb(any<Int>(), any<Int>(), any<Int>(), any<Int>()) } answers {
            val a = arg<Int>(0)
            val r = arg<Int>(1)
            val g = arg<Int>(2)
            val b = arg<Int>(3)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ==================== 默认值与字段访问 ====================

    /**
     * Given: 使用无参构造器创建 ReadingSettings
     * When: 检查各字段
     * Then: 字段值符合文档规定（fontSize=18, lineHeight=1.8, fontFamily=Serif, bg=WHITE）
     */
    @Test
    fun `should have correct default values when constructed without arguments`() {
        val settings = ReadingSettings()
        assertEquals(18f, settings.fontSize, 0.0001f)
        assertEquals(1.8f, settings.lineHeightMultiplier, 0.0001f)
        assertEquals("Serif", settings.fontFamily)
        assertEquals(ReadingSettings.BackgroundColor.WHITE, settings.backgroundColor)
    }

    /**
     * Given: 一个 ReadingSettings 实例
     * When: 使用 copy 修改单个字段
     * Then: 修改字段更新，其余字段保持不变
     */
    @Test
    fun `should copy with single field modified preserving others`() {
        val original = ReadingSettings()
        val modified = original.copy(fontSize = 24f)
        assertEquals(24f, modified.fontSize, 0.0001f)
        assertEquals(original.lineHeightMultiplier, modified.lineHeightMultiplier, 0.0001f)
        assertEquals(original.fontFamily, modified.fontFamily)
        assertEquals(original.backgroundColor, modified.backgroundColor)
    }

    /**
     * Given: BackgroundColor 三个枚举值
     * When: 访问 composeColor / textColor / argb
     * Then: 字段非空（Compose Color 是值类，永远非空；argb 为整数）
     */
    @Test
    fun `should expose distinct color fields for each BackgroundColor`() {
        val colors = ReadingSettings.BackgroundColor.entries
        assertEquals(3, colors.size)
        // 三种背景色的 composeColor 互不相同
        val composeSet = colors.map { it.composeColor }.toSet()
        assertEquals(3, composeSet.size)
    }

    // ==================== getTypeface ====================

    /**
     * Given: fontFamily 为 "Serif"
     * When: 调用 getTypeface
     * Then: 返回 Typeface.SERIF
     */
    @Test
    fun `should return Serif typeface when fontFamily is Serif`() {
        val tf = ReadingSettings(fontFamily = "Serif").getTypeface()
        assertEquals(Typeface.SERIF, tf)
    }

    /**
     * Given: fontFamily 为 "Sans-serif"
     * When: 调用 getTypeface
     * Then: 返回 Typeface.SANS_SERIF
     */
    @Test
    fun `should return Sans-serif typeface when fontFamily is Sans-serif`() {
        val tf = ReadingSettings(fontFamily = "Sans-serif").getTypeface()
        assertEquals(Typeface.SANS_SERIF, tf)
    }

    /**
     * Given: fontFamily 为 "Monospace"
     * When: 调用 getTypeface
     * Then: 返回 Typeface.MONOSPACE
     */
    @Test
    fun `should return Monospace typeface when fontFamily is Monospace`() {
        val tf = ReadingSettings(fontFamily = "Monospace").getTypeface()
        assertEquals(Typeface.MONOSPACE, tf)
    }

    /**
     * Given: fontFamily 为未知名称（如 "Comic Sans"）
     * When: 调用 getTypeface
     * Then: 回退到 Typeface.SERIF（默认衬线字体）
     */
    @Test
    fun `should fallback to Serif when fontFamily is unknown`() {
        val tf = ReadingSettings(fontFamily = "Comic Sans").getTypeface()
        assertEquals(Typeface.SERIF, tf)
    }

    // ==================== toReaderCss ====================

    /**
     * Given: 默认 ReadingSettings
     * When: 调用 toReaderCss
     * Then: 返回包含 font-family / font-size / line-height / background / color 的 CSS
     */
    @Test
    fun `should render css with default settings`() {
        val css = ReadingSettings().toReaderCss()
        assertTrue("CSS 应包含 font-family", css.contains("font-family: serif"))
        assertTrue("CSS 应包含 18px 字号", css.contains("font-size: 18px"))
        assertTrue("CSS 应包含 line-height: 1.8", css.contains("line-height: 1.8"))
        assertTrue("CSS 应包含 background-color", css.contains("background-color:"))
        assertTrue("CSS 应包含 text-align: justify", css.contains("text-align: justify"))
        // img / 段落 / 标题样式
        assertTrue(css.contains("img { max-width: 100%"))
        assertTrue(css.contains("p { margin-top: 0"))
    }

    /**
     * Given: fontFamily 各种值
     * When: 调用 toReaderCss
     * Then: CSS 中的 font-family 正确映射为 CSS 关键字（小写）
     */
    @Test
    fun `should map fontFamily names to CSS keywords correctly`() {
        assertTrue(ReadingSettings(fontFamily = "Serif").toReaderCss().contains("font-family: serif"))
        assertTrue(ReadingSettings(fontFamily = "Sans-serif").toReaderCss().contains("font-family: sans-serif"))
        assertTrue(ReadingSettings(fontFamily = "Monospace").toReaderCss().contains("font-family: monospace"))
        // 未知字体回退到 serif
        assertTrue(ReadingSettings(fontFamily = "Random").toReaderCss().contains("font-family: serif"))
    }

    /**
     * Given: 不同字号 14 / 20 / 28
     * When: 调用 toReaderCss
     * Then: CSS 包含对应像素值（fontSize.toInt() 转 px）
     */
    @Test
    fun `should write fontSize as integer px in css`() {
        assertTrue(ReadingSettings(fontSize = 14f).toReaderCss().contains("font-size: 14px"))
        assertTrue(ReadingSettings(fontSize = 20f).toReaderCss().contains("font-size: 20px"))
        assertTrue(ReadingSettings(fontSize = 28f).toReaderCss().contains("font-size: 28px"))
        // 浮点数 18.7 应被截断为 18
        assertTrue(ReadingSettings(fontSize = 18.7f).toReaderCss().contains("font-size: 18px"))
    }

    /**
     * Given: 不同 lineHeightMultiplier
     * When: 调用 toReaderCss
     * Then: CSS 包含正确的无单位 line-height 值
     */
    @Test
    fun `should write lineHeight as multiplier without unit`() {
        val css = ReadingSettings(lineHeightMultiplier = 2.0f).toReaderCss()
        assertTrue("CSS 应包含 line-height: 2.0，实际：$css", css.contains("line-height: 2.0"))
    }

    // ==================== ReadingSettingsManager ====================

    /**
     * 构建一个 mock SharedPreferences，配置默认的返回值。
     * 复用现有项目中 ReaderViewModelTest 的模式。
     */
    private fun mockPrefs(
        fontSize: Float = 18f,
        lineHeight: Float = 1.8f,
        fontFamily: String = "Serif",
        bgColor: Int = 0,
    ): Pair<Context, SharedPreferences> {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getFloat("font_size", any()) } returns fontSize
        every { prefs.getFloat("line_height", any()) } returns lineHeight
        every { prefs.getString("font_family", any()) } returns fontFamily
        every { prefs.getInt("bg_color", any()) } returns bgColor

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.apply() } just Runs
        return context to prefs
    }

    /**
     * Given: SharedPreferences 中无任何设置（getFloat 返回提供的默认值）
     * When: ReadingSettingsManager.load()
     * Then: 返回带默认值的 ReadingSettings
     */
    @Test
    fun `should load default settings when SharedPreferences is empty`() {
        val (context, _) = mockPrefs(fontSize = 18f, lineHeight = 1.8f, fontFamily = "Serif", bgColor = 0)
        val manager = ReadingSettingsManager(context)
        val settings = manager.load()
        assertEquals(18f, settings.fontSize, 0.0001f)
        assertEquals(1.8f, settings.lineHeightMultiplier, 0.0001f)
        assertEquals("Serif", settings.fontFamily)
        assertEquals(ReadingSettings.BackgroundColor.WHITE, settings.backgroundColor)
    }

    /**
     * Given: SharedPreferences 中存在已保存的设置
     * When: ReadingSettingsManager.load()
     * Then: 返回与 SharedPreferences 一致的 ReadingSettings
     */
    @Test
    fun `should load saved settings from SharedPreferences`() {
        val (context, _) = mockPrefs(fontSize = 24f, lineHeight = 2.0f, fontFamily = "Sans-serif", bgColor = 2)
        val manager = ReadingSettingsManager(context)
        val settings = manager.load()
        assertEquals(24f, settings.fontSize, 0.0001f)
        assertEquals(2.0f, settings.lineHeightMultiplier, 0.0001f)
        assertEquals("Sans-serif", settings.fontFamily)
        // bgColor=2 对应 ordinal=2 -> DARK
        assertEquals(ReadingSettings.BackgroundColor.DARK, settings.backgroundColor)
    }

    /**
     * Given: SharedPreferences 中 bg_color 是越界的 ordinal（如 99）
     * When: ReadingSettingsManager.load()
     * Then: 不抛异常，背景色回退到 WHITE
     */
    @Test
    fun `should fallback to WHITE when stored bgColor is out of range`() {
        val (context, _) = mockPrefs(bgColor = 99)
        val manager = ReadingSettingsManager(context)
        val settings = manager.load()
        assertEquals(ReadingSettings.BackgroundColor.WHITE, settings.backgroundColor)
    }

    /**
     * Given: SharedPreferences 中 fontFamily 字段被存储为 null
     * When: ReadingSettingsManager.load()
     * Then: 回退到 "Serif"
     */
    @Test
    fun `should fallback to Serif when stored fontFamily is null`() {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getFloat(any(), any()) } returns 18f
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getInt(any(), any()) } returns 0

        val settings = ReadingSettingsManager(context).load()
        assertEquals("Serif", settings.fontFamily)
    }

    /**
     * Given: 一个 ReadingSettings
     * When: ReadingSettingsManager.save(settings)
     * Then: SharedPreferences.Editor 的 putFloat/putString/putInt/apply 被正确调用
     */
    @Test
    fun `should write all fields to SharedPreferences when saving`() {
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.apply() } just Runs
        // 为 load 路径补齐默认 stub（避免初始化时调用挂掉）
        every { prefs.getFloat(any(), any()) } returns 18f
        every { prefs.getString(any(), any()) } returns "Serif"
        every { prefs.getInt(any(), any()) } returns 0

        val manager = ReadingSettingsManager(context)
        val settings = ReadingSettings(
            fontSize = 22f,
            lineHeightMultiplier = 2.2f,
            fontFamily = "Monospace",
            backgroundColor = ReadingSettings.BackgroundColor.SEPIA,
        )
        manager.save(settings)

        verify { editor.putFloat("font_size", 22f) }
        verify { editor.putFloat("line_height", 2.2f) }
        verify { editor.putString("font_family", "Monospace") }
        verify { editor.putInt("bg_color", ReadingSettings.BackgroundColor.SEPIA.ordinal) }
        verify { editor.apply() }
    }
}
