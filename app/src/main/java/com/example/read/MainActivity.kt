package com.example.read

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.read.ui.navigation.ReadNavHost
import com.example.read.ui.theme.ReadTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 应用唯一的 Activity，采用 Single-Activity 架构。
 *
 * 所有页面（书架、阅读器）都是 Composable，通过 Navigation Compose 管理导航。
 * 这是现代 Android 开发的推荐模式：
 * - 一个 Activity 承载整个应用
 * - 页面切换由 Compose Navigation 处理，无需多个 Activity
 * - Hilt 通过 @AndroidEntryPoint 注入依赖
 *
 * enableEdgeToEdge() 启用边到边显示，内容延伸到状态栏和导航栏下方，
 * 配合 WindowInsets 处理系统栏避让。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReadTheme {
                ReadNavHost()
            }
        }
    }
}
