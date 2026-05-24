# com.example.read.ui.components -- 可复用 UI 组件

## 包概述

存放可复用的 Compose UI 组件，供多个页面共享使用。
当前包含书籍卡片和空状态两个组件。

## 文件列表

| 文件 | 职责 |
|------|------|
| `BookCard.kt` | 书籍卡片，展示封面和标题，支持点击和长按 |
| `EmptyState.kt` | 空状态组件，无书籍时显示引导文案 |

## 关键类

### BookCard

书籍卡片组件，用于书架网格中的单本书展示。

**布局**：上方封面图片（2:3 比例） + 下方标题文本（最多 2 行）

**参数**：
- `book: Book` -- 书籍数据
- `onClick: () -> Unit` -- 点击回调（进入阅读器）
- `onLongClick: (() -> Unit)?` -- 长按回调（触发删除）
- `modifier: Modifier` -- 外部修饰符

**封面加载**：
- 使用 Coil 3 的 `AsyncImage`，支持从文件路径加载
- `model = book.coverPath?.let { File(it) } ?: R.drawable.placeholder_cover`
- `ContentScale.Crop` 裁剪填充保持比例
- `aspectRatio(2f / 3f)` 标准书籍封面宽高比

**交互**：使用 `combinedClickable`（foundation 库）同时处理点击和长按

### EmptyState

空状态组件，居中展示书本图标和引导文案。

**布局**：垂直居中的 Column，包含图标 + "还没有书籍" + "点击右下角 + 导入你的第一本书"

**样式**：
- 图标使用 `Icons.Outlined.MenuBook`，80dp，半透明处理
- 主标题使用 `titleMedium`，副标题使用 `bodyMedium`（0.7f 透明度）
- 颜色使用 `onSurfaceVariant`，视觉上不喧宾夺主

## 依赖关系

- **依赖**：`domain.model.Book`（BookCard 参数）、`R.drawable.placeholder_cover`（占位图资源）
- **被依赖**：`ui.bookshelf.BookshelfScreen`（使用 BookCard 和 EmptyState）

## 编码规范

- 组件使用 `@Composable` 函数定义，参数使用命名参数
- 提供 `modifier: Modifier = Modifier` 参数，允许外部自定义布局
- 使用 Material 3 组件（Card、Icon、Text）和 MaterialTheme 主题系统
- `combinedClickable` 需要 `@OptIn(ExperimentalFoundationApi::class)`
- 图片加载使用 Coil 3 的 `AsyncImage`，自动处理缓存和生命周期
