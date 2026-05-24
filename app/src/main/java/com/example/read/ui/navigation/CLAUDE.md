# com.example.read.ui.navigation -- 导航图

## 包概述

Navigation Compose 导航图定义，使用类型安全路由（2024+ 新特性）。
管理页面之间的导航关系和参数传递。

## 文件列表

| 文件 | 职责 |
|------|------|
| `NavGraph.kt` | 路由定义和 NavHost 配置 |

## 关键类

### 路由定义

使用 `@Serializable` 注解实现编译期类型安全的路由：

| 路由 | 类型 | 参数 | 说明 |
|------|------|------|------|
| `Bookshelf` | `data object` | 无 | 书架首页 |
| `Reader` | `data class` | `bookId: Long` | 阅读器页面 |

### ReadNavHost

导航宿主 Composable，定义所有页面和导航关系。

**导航图**：
```
Bookshelf（首页，startDestination）
    |
    |-- 点击书籍 --> Reader(bookId)
    |
    <-- 返回（popBackStack）--
```

**实现细节**：
- `rememberNavController()` 创建导航控制器
- `NavHost` 设置 `startDestination = Bookshelf`
- `composable<Bookshelf>` 注册书架页面
- `composable<Reader>` 注册阅读器页面，通过 `backStackEntry.toRoute<Reader>()` 提取参数

## 依赖关系

- **依赖**：`ui.bookshelf.BookshelfScreen`、`ui.reader.ReaderScreen`
- **被依赖**：`MainActivity`（调用 `ReadNavHost()` 设置导航）

## 编码规范

- 路由使用 `@Serializable data object/data class`，Kotlin Serialization 自动处理序列化
- 路由参数类型编译期检查，不会出现运行时类型转换错误
- 导航动作在 `NavHost` 的 `composable` 块中通过 lambda 传递给 Screen
- 返回操作使用 `navController.popBackStack()`
- startDestination 必须是无参数的 `data object` 路由
