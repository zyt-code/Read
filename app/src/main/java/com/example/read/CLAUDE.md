# com.example.read -- 应用根包

## 包概述

应用入口层，包含 Hilt 初始化和 Single-Activity 架构的唯一 Activity。
所有页面（书架、阅读器）均为 Composable，通过 Navigation Compose 管理导航。

## 目录结构

```
com.example.read/
  ReadApplication.kt          # @HiltAndroidApp，Hilt 依赖注入入口
  MainActivity.kt             # @AndroidEntryPoint，Single-Activity 入口
  data/                       # 数据层：Room 数据库、DAO、Repository 实现
  domain/                     # 领域层：纯 Kotlin 模型、Repository 接口
  di/                         # 依赖注入：Hilt Module
  ui/                         # 表现层：Compose 屏幕、ViewModel、组件、导航、主题
  util/                       # 工具层：EPUB 解析器
```

## 架构模式

- **MVVM + Repository**：ViewModel 持有 UI 状态，Repository 抽象数据源
- **单向数据流**：Room Flow -> Repository -> ViewModel StateFlow -> Compose UI
- **依赖倒置**：ViewModel 依赖 Repository 接口，不直接依赖 Room/文件系统
- **Single-Activity**：全应用一个 Activity，页面切换由 Compose Navigation 处理

## 核心类

| 类名 | 职责 |
|------|------|
| `ReadApplication` | `@HiltAndroidApp` 注解触发 Hilt 代码生成，作为依赖注入的根组件 |
| `MainActivity` | `@AndroidEntryPoint`，启用 `enableEdgeToEdge()` 边到边显示，设置 `ReadTheme` + `ReadNavHost` |

## 依赖关系

- **被依赖**：`di/AppModule` 通过 `@ApplicationContext` 获取 Application 级 Context
- **依赖**：`ui.navigation.ReadNavHost`（导航图）、`ui.theme.ReadTheme`（主题）

## 编码规范

- 单 Activity 架构，不创建新的 Activity
- 所有页面通过 `@Composable` 函数定义
- Hilt 注入使用 `@AndroidEntryPoint` 和 `@HiltViewModel`
- 边到边显示通过 `enableEdgeToEdge()` 启用，系统栏避让由 Compose WindowInsets 处理

## 子包说明

| 子包 | 用途 |
|------|------|
| `data.local` | Room 数据库定义（AppDatabase、Entity、DAO、Migrations） |
| `data.repository` | Repository 模式实现，协调数据库和文件系统 |
| `domain.model` | 纯 Kotlin 领域模型，无 Android 依赖 |
| `domain.repository` | Repository 接口定义，依赖倒置的契约层 |
| `di` | Hilt 依赖注入模块 |
| `ui.bookshelf` | 书架页面（书籍列表、导入、删除） |
| `ui.reader` | 阅读器页面（WebView 渲染、全局页面流、翻页、目录导航、进度） |
| `ui.components` | 可复用 UI 组件（BookCard、EmptyState） |
| `ui.navigation` | Navigation Compose 类型安全路由 |
| `ui.theme` | Material 3 主题（动态取色、排版） |
| `util` | EPUB 解析工具（ZIP 解压 + OPF 解析 + NCX 目录解析 + unpack） |
