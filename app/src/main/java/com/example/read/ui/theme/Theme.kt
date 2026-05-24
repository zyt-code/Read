package com.example.read.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 浅色主题配色方案，使用棕色系作为主色调（阅读应用的经典配色） */
private val LightColorScheme = lightColorScheme(
    primary = Brown40,
    secondary = BrownGrey40,
    tertiary = Brown40,
)

/** 深色主题配色方案，使用浅棕色系，适配夜间阅读场景 */
private val DarkColorScheme = darkColorScheme(
    primary = Brown80,
    secondary = BrownGrey80,
    tertiary = Brown80,
)

/**
 * 应用主题，支持 Material You 动态取色。
 *
 * 主题策略（优先级从高到低）：
 * 1. 动态取色（Android 12+）：从用户壁纸提取配色，实现 Material You 个性化
 * 2. 静态配色：Android 12 以下设备使用预定义的棕色系配色
 * 3. 亮/暗色：根据系统设置自动切换
 *
 * typography 使用自定义的 ReadTypography，针对阅读场景优化了字体和行高。
 */
@Composable
fun ReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12+ (API 31) 支持动态取色
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 降级方案：使用预定义的静态配色
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ReadTypography,
        content = content
    )
}
