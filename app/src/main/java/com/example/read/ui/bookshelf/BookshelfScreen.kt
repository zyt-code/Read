package com.example.read.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.read.domain.model.Book
import com.example.read.ui.components.BookCard
import com.example.read.ui.components.EmptyState
import com.example.read.ui.components.PlaceholderBookCard

/**
 * 书架页面，展示用户导入的所有书籍。
 *
 * 布局结构：
 * - 顶部：Material 3 TopAppBar，标题"我的书架"
 * - 主体：2 列 LazyVerticalGrid 网格，每个格子是一个 BookCard
 * - 右下角：FAB 按钮触发 EPUB 文件导入
 * - 空状态：无书籍时显示引导文案
 * - 删除确认：长按书籍弹出 AlertDialog
 *
 * 数据流：ViewModel 的 books StateFlow → collectAsState → Grid 自动更新
 * 导入流：FAB → SAF 文件选择器 → ViewModel.importBook → Room 自动通知 → Grid 更新
 *
 * P1-NEW-1 修复：
 * 由于 BookDao.getAllBooks() 在 SQL 层已经过滤掉 `PREPARING_*` 占位记录，
 * `books` 列表中不再包含导入中的临时条目。为了恢复"导入过程中显示进度环卡片"
 * 这一 UX，UI 改为：先渲染 ViewModel.placeholderBooks（导入中的占位卡片，
 * 通过 PlaceholderBookCard 渲染半透明进度环），再渲染 books（真实书籍卡片）。
 * 这样既保留了 P1-1 的"幽灵记录不残留"收益，又补回了导入进度可见性。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBookClick: (Long) -> Unit, // 点击书籍的回调，传入 bookId 用于导航到阅读器
    viewModel: BookshelfViewModel = hiltViewModel(),
) {
    // 收集 ViewModel 的状态，Compose 会自动响应变化重组 UI
    val books by viewModel.books.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    // P1-NEW-1：占位卡片列表（仅在导入过程中非空），与 books 合并展示
    val placeholderBooks by viewModel.placeholderBooks.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // pinnedScrollBehavior 让 TopAppBar 在滚动时保持固定（不隐藏）
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 待删除的书籍，非 null 时显示确认对话框
    var bookToDelete by remember { mutableStateOf<Book?>(null) }

    // SAF 文件选择器：使用 OpenDocument 协议，无需存储权限
    // MIME 类型 "application/epub+zip" 是 EPUB 文件的标准 MIME 类型
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // 用户选择了文件后，调用 ViewModel 导入
        uri?.let { viewModel.importBook(it, context) }
    }

    // 错误处理：将错误信息通过 Snackbar 展示给用户
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // 删除确认对话框：长按书籍卡片时触发
    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("删除书籍") },
            text = { Text("确定要删除《${book.title}》吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book)
                    bookToDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("我的书架") },
                scrollBehavior = scrollBehavior,
            )
        },
        // FAB：点击触发 SAF 文件选择器，筛选 EPUB 文件
        floatingActionButton = {
            FloatingActionButton(
                onClick = { launcher.launch(arrayOf("application/epub+zip")) },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import EPUB")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 空状态判定：必须 books 与 placeholderBooks 都为空才显示引导文案
        // 否则即使只有占位卡片，也应让用户看到导入活动
        if (books.isEmpty() && placeholderBooks.isEmpty()) {
            // 空状态：引导用户导入第一本书
            EmptyState(modifier = Modifier.padding(padding))
        } else {
            // 2 列网格书架，key = { it.id } 确保列表项的稳定标识
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // P1-NEW-1：占位卡片优先渲染在书架最前面（最近导入的最显眼），
                // key 使用 "placeholder_<id>" 前缀避免与真实 book id 冲突。
                // 这些卡片不可点击，仅展示半透明封面 + CircularProgressIndicator + 百分比。
                items(placeholderBooks, key = { "placeholder_${it.id}" }) { placeholder ->
                    PlaceholderBookCard(
                        titleHint = placeholder.titleHint,
                        progress = placeholder.progress,
                    )
                }
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onBookClick(book.id) },
                        onLongClick = { bookToDelete = book },
                        importProgress = importProgress[book.id],
                    )
                }
            }
        }
    }
}
