package com.example.read.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/**
 * 导航路由定义和 NavHost 配置。
 *
 * 使用 Navigation Compose 的类型安全路由（2024+ 新特性）：
 * - @Serializable data object：无参数的目的地（书架首页）
 * - @Serializable data class：带参数的目的地（阅读器，需要 bookId）
 *
 * 类型安全路由的优势：
 * - 编译期检查参数类型，不会出现运行时的类型转换错误
 * - 不需要手动构建路由字符串，避免拼写错误
 * - Kotlin Serialization 自动处理参数的序列化/反序列化
 */

/** 书架首页路由，无参数 */
@Serializable
data object Bookshelf

/** 阅读器路由，需要 bookId 参数定位具体书籍 */
@Serializable
data class Reader(val bookId: Long)

/**
 * 应用的导航宿主，定义所有页面和导航关系。
 *
 * 导航图：
 * Bookshelf（首页）→ Reader（阅读器）
 * Reader → 返回 Bookshelf
 */
@Composable
fun ReadNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Bookshelf) {
        // 书架页面：点击书籍时导航到阅读器
        composable<Bookshelf> {
            com.example.read.ui.bookshelf.BookshelfScreen(
                onBookClick = { bookId ->
                    navController.navigate(Reader(bookId = bookId))
                }
            )
        }
        // 阅读器页面：从路由参数中提取 bookId，返回时弹出栈
        composable<Reader> { backStackEntry ->
            val route = backStackEntry.toRoute<Reader>()
            com.example.read.ui.reader.ReaderScreen(
                bookId = route.bookId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
