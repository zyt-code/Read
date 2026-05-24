# com.example.read.ui.theme -- Material 3 主题

## 包概述

Material 3 主题配置，支持 Material You 动态取色。
定义应用的颜色方案、排版样式和主题入口函数。

## 文件列表

| 文件 | 职责 |
|------|------|
| `Theme.kt` | 主题入口函数，动态取色逻辑 |
| `Color.kt` | 静态颜色定义（棕色系） |
| `Type.kt` | 自定义排版样式（阅读优化） |

## 关键类

### ReadTheme（Theme.kt）

主题入口 Composable，封装 `MaterialTheme` 配置。

**主题策略（优先级从高到低）**：
1. **动态取色**（Android 12+）：从用户壁纸提取配色，Material You 个性化
2. **静态配色**：Android 12 以下使用预定义的棕色系配色
3. **亮/暗色**：根据 `isSystemInDarkTheme()` 自动切换

**参数**：
- `darkTheme: Boolean` -- 是否暗色主题（默认跟随系统）
- `dynamicColor: Boolean` -- 是否启用动态取色（默认 true）

### 颜色定义（Color.kt）

棕色系配色，阅读应用的经典配色方案：

| 颜色 | 值 | 用途 |
|------|-----|------|
| `Brown80` | `#D7CCC8` | 深色主题主色 |
| `BrownGrey80` | `#BCAAA4` | 深色主题辅助色 |
| `Brown40` | `#795548` | 浅色主题主色 |
| `BrownGrey40` | `#8D6E63` | 浅色主题辅助色 |

### 排版定义（Type.kt）

`ReadTypography` 针对阅读场景优化：

| 样式 | 字体 | 字号 | 行高 | 用途 |
|------|------|------|------|------|
| `bodyLarge` | Serif | 18sp | 30sp | 正文主样式 |
| `bodyMedium` | Serif | 16sp | 26sp | 正文辅助样式 |
| `titleLarge` | Sans | 22sp | 28sp | 大标题 |
| `titleMedium` | Sans | 18sp | 24sp | 中标题 |
| `titleSmall` | Sans | 14sp | 20sp | 小标题 |
| `bodySmall` | Sans | 12sp | 16sp | 辅助文字 |

**阅读优化要点**：
- 正文使用 `FontFamily.Serif`（宋体风格，长文阅读更舒适）
- 行高约为字号的 1.67-1.78 倍，提供舒适的阅读间距
- 字间距 0.05-0.1sp，增加字间呼吸感

## 依赖关系

- **依赖**：`androidx.compose.material3`（MaterialTheme、Typography）、`android.os.Build`（API 版本检测）
- **被依赖**：`MainActivity`（调用 `ReadTheme { ReadNavHost() }`）

## 编码规范

- 动态取色通过 `Build.VERSION.SDK_INT >= Build.VERSION.S` 判断
- `ReadTypography` 作为顶层 `val` 定义，在 Type.kt 中
- 颜色常量使用 `val` 定义在 Color.kt 中，不使用 `object` 或 `class`
- 主题函数使用 `@Composable` 注解，接受 `content: @Composable () -> Unit` lambda
