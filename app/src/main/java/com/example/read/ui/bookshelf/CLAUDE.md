# com.example.read.ui.bookshelf -- 书架页面

## 包概述

书架页面，展示用户导入的所有书籍，支持导入新书和删除已有书籍。
是应用的首页，使用 2 列网格布局展示书籍封面和标题。

## 文件列表

| 文件 | 职责 |
|------|------|
| `BookshelfScreen.kt` | 书架页面的 Composable UI |
| `BookshelfViewModel.kt` | 书架页面的 ViewModel，管理状态和业务逻辑 |

## 关键类

### BookshelfScreen

书架页面 Composable，布局结构：

- **TopAppBar**：标题"我的书架"，`pinnedScrollBehavior` 固定不隐藏
- **LazyVerticalGrid**：2 列 `GridCells.Fixed(2)`，每项是 `BookCard`
- **FAB**：点击触发 SAF 文件选择器（`OpenDocument` 协议，MIME: `application/epub+zip`）
- **EmptyState**：无书籍时显示引导文案
- **AlertDialog**：长按书籍弹出删除确认
- **CircularProgressIndicator**：导入中全屏加载

**数据流**：`ViewModel.books StateFlow` -> `collectAsState` -> Grid 自动重组

**SAF 集成**：使用 `rememberLauncherForActivityResult(OpenDocument())` 启动文件选择器

### BookshelfViewModel

`@HiltViewModel`，注入 `BookRepository` 接口。

**状态管理**：

| 状态 | 类型 | 说明 |
|------|------|------|
| `books` | `StateFlow<List<Book>>` | 书籍列表，Room Flow 自动响应数据库变化 |
| `isImporting` | `StateFlow<Boolean>` | 导入进行中标志 |
| `errorMessage` | `StateFlow<String?>` | 错误信息，Snackbar 展示后清除 |

**init 块**：启动时调用 `repository.cleanupOrphanedBooks()` 清理导入中途崩溃产生的孤立记录（bookDirPath 为空）。

**关键方法**：
- `importBook(uri, context)` -- 异步导入 EPUB，设置 loading 状态；catch 块根据异常类型提供细粒度错误信息（如文件不存在、解析失败、存储空间不足等）
- `deleteBook(book)` -- 异步删除书籍
- `clearError()` -- 清除错误信息

**stateIn 策略**：`SharingStarted.WhileSubscribed(5000)`，配置变更后 5 秒内重订阅不重新查询

## 依赖关系

- **依赖**：`domain.model.Book`、`domain.repository.BookRepository`、`ui.components.BookCard`、`ui.components.EmptyState`
- **被依赖**：`ui.navigation.NavGraph`（在导航图中注册 BookshelfScreen）

## 编码规范

- ViewModel 通过 `hiltViewModel()` 获取，不手动构造
- 状态使用 `MutableStateFlow`（内部）+ `StateFlow`（外部暴露），不可变
- 错误处理：捕获异常 -> 设置 errorMessage -> Snackbar 展示 -> clearError
- SAF 文件选择器使用 `OpenDocument` 协议，无需存储权限
- 网格列表使用 `key = { it.id }` 确保 DiffUtil 正确识别项
