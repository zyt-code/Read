package com.example.read.ui.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 阅读设置数据类，包含所有可配置的阅读参数。
 *
 * 参数说明：
 * - fontSize: 正文字号（sp），范围 14-28，默认 18
 * - lineHeightMultiplier: 行高倍数，范围 1.2-2.5，默认 1.8
 * - fontFamily: 字体系列名称，支持 Serif/Sans-serif/Monospace
 * - backgroundColor: 背景颜色枚举值
 *
 * 这些设置影响文本排版和分页计算，修改后需要重新分页。
 */
data class ReadingSettings(
    /** 正文字号，单位 sp */
    val fontSize: Float = 18f,
    /** 行高倍数，相对于字号的倍数 */
    val lineHeightMultiplier: Float = 1.8f,
    /** 字体系列名称 */
    val fontFamily: String = "Serif",
    /** 背景颜色类型 */
    val backgroundColor: BackgroundColor = BackgroundColor.WHITE,
) {
    /**
     * 背景颜色枚举，定义三种常用阅读背景。
     *
     * - WHITE: 纯白背景，适合光线充足的环境
     * - SEPIA: 护眼羊皮纸色，减少蓝光，适合长时间阅读
     * - DARK: 深色背景，适合夜间或暗光环境
     */
    enum class BackgroundColor(
        /** Compose 颜色值，用于页面背景 */
        val composeColor: Color,
        /** 文字颜色，与背景形成足够对比度 */
        val textColor: Color,
        /** ARGB 整数值，用于 Paint 和 Canvas 操作 */
        val argb: Int,
    ) {
        /** 纯白背景 + 黑色文字 */
        WHITE(
            composeColor = Color.White,
            textColor = Color(0xFF333333),
            argb = android.graphics.Color.WHITE,
        ),
        /** 羊皮纸色背景 + 深棕色文字，护眼效果 */
        SEPIA(
            composeColor = Color(0xFFF5E6D0),
            textColor = Color(0xFF5B4636),
            argb = android.graphics.Color.argb(255, 245, 230, 208),
        ),
        /** 深色背景 + 浅灰色文字，适合夜间阅读 */
        DARK(
            composeColor = Color(0xFF1A1A2E),
            textColor = Color(0xFFD0D0D0),
            argb = android.graphics.Color.argb(255, 26, 26, 46),
        ),
    }

    /**
     * 根据 fontFamily 名称获取对应的 Android Typeface 对象。
     *
     * @return 对应的 Typeface，未知名称返回 Serif
     */
    fun getTypeface(): Typeface {
        return when (fontFamily) {
            "Sans-serif" -> Typeface.SANS_SERIF
            "Monospace" -> Typeface.MONOSPACE
            else -> Typeface.SERIF
        }
    }

    /**
     * 将阅读设置转换为 WebView 阅读器的 CSS 字符串。
     *
     * 转换规则：
     * - fontFamily: Compose 的字体名称映射为 CSS font-family 值
     *   "Serif" -> "serif", "Sans-serif" -> "sans-serif", "Monospace" -> "monospace"
     * - fontSize: sp 值直接作为 px 使用（WebView 中 1sp 对应 1px，由设备密度决定）
     * - lineHeightMultiplier: 直接作为无单位的 line-height 值
     * - backgroundColor: 从 BackgroundColor 枚举提取背景色和文字颜色的 hex 值
     *
     * CSS 包含：
     * - body 基础样式（边距、内边距、字体、颜色、两端对齐）
     * - img 自适应宽度（max-width: 100%）
     * - h1-h6 防止分页断裂（page-break-after: avoid）
     * - p 段落间距（margin-bottom: 0.8em）
     *
     * @return 完整的 CSS 样式字符串，可直接注入 WebView
     */
    fun toReaderCss(): String {
        // 字体名称映射：Compose 使用的名称 -> CSS font-family 值
        val fontFamilyCss = when (fontFamily) {
            "Sans-serif" -> "sans-serif"
            "Monospace" -> "monospace"
            else -> "serif"  // 默认使用衬线字体，适合长文阅读
        }

        // 将 BackgroundColor 枚举的 ARGB 整数值转为 CSS hex 色值
        // 0xFFFFFF 掩码去除 alpha 通道，只保留 RGB 分量
        val bgColorHex = String.format("#%06X", 0xFFFFFF and backgroundColor.argb)
        // textColor 是 Compose Color 对象，通过 toArgb() 转为 ARGB 整数再提取 RGB
        val textColorHex = String.format("#%06X", 0xFFFFFF and backgroundColor.textColor.toArgb())

        // sp 值直接作为 px 使用，WebView 中由设备密度自动处理
        val fontSizePx = fontSize.toInt()

        // 阅读样式末尾附加章内搜索（v4 feature: find-in-page）的高亮 CSS。
        // 放在 toReaderCss 里有两个好处：
        // 1) 每次 updateCSS 都会同步注入，避免主题切换后 mark 样式失效
        // 2) 设置变更触发的 WebView 重建后无需额外注入步骤，跟随 PAGINATION 流程自然生效
        return """
body {
    margin: 0;
    padding: 16px 24px;
    overflow: hidden;
    font-family: $fontFamilyCss;
    font-size: ${fontSizePx}px;
    line-height: $lineHeightMultiplier;
    color: $textColorHex;
    background-color: $bgColorHex;
    -webkit-text-size-adjust: none;
    word-wrap: break-word;
    overflow-wrap: break-word;
    text-align: justify;
}
img { max-width: 100%; height: auto; }
h1, h2, h3, h4, h5, h6 { page-break-after: avoid; }
p { margin-top: 0; margin-bottom: 0.8em; }
${FindInPageJs.FIND_IN_PAGE_CSS}
""".trimIndent()
    }
}

/**
 * 阅读设置管理器，使用 SharedPreferences 持久化用户的阅读偏好。
 *
 * 设计原则：
 * - 所有设置都有合理的默认值，首次使用无需配置
 * - 读写操作轻量，不需要协程
 * - 使用 Android 标准的 SharedPreferences 机制
 *
 * @param context Android Context，用于获取 SharedPreferences 实例
 */
class ReadingSettingsManager(context: Context) {

    /** SharedPreferences 实例，存储在 /data/data/包名/shared_prefs/ 目录下 */
    private val prefs = context.getSharedPreferences("reading_settings", Context.MODE_PRIVATE)

    companion object {
        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "reading_settings"
        /** 字号设置的键名 */
        private const val KEY_FONT_SIZE = "font_size"
        /** 行高倍数设置的键名 */
        private const val KEY_LINE_HEIGHT = "line_height"
        /** 字体系列设置的键名 */
        private const val KEY_FONT_FAMILY = "font_family"
        /** 背景颜色设置的键名 */
        private const val KEY_BG_COLOR = "bg_color"
    }

    /**
     * 从 SharedPreferences 加载保存的阅读设置。
     * 如果没有保存过任何设置，返回全部使用默认值的 ReadingSettings 实例。
     *
     * @return 当前保存的阅读设置
     */
    fun load(): ReadingSettings {
        return ReadingSettings(
            fontSize = prefs.getFloat(KEY_FONT_SIZE, 18f),
            lineHeightMultiplier = prefs.getFloat(KEY_LINE_HEIGHT, 1.8f),
            fontFamily = prefs.getString(KEY_FONT_FAMILY, "Serif") ?: "Serif",
            backgroundColor = ReadingSettings.BackgroundColor.entries.getOrElse(
                prefs.getInt(KEY_BG_COLOR, 0)
            ) { ReadingSettings.BackgroundColor.WHITE },
        )
    }

    /**
     * 将阅读设置保存到 SharedPreferences。
     * 使用 apply() 异步写入，不阻塞主线程。
     *
     * @param settings 要保存的阅读设置
     */
    fun save(settings: ReadingSettings) {
        prefs.edit().apply {
            putFloat(KEY_FONT_SIZE, settings.fontSize)
            putFloat(KEY_LINE_HEIGHT, settings.lineHeightMultiplier)
            putString(KEY_FONT_FAMILY, settings.fontFamily)
            putInt(KEY_BG_COLOR, settings.backgroundColor.ordinal)
            apply() // 异步写入磁盘
        }
    }
}
