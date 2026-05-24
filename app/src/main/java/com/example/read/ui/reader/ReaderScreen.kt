package com.example.read.ui.reader

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.viewpager2.widget.ViewPager2
import com.example.read.data.search.SearchResult
import com.example.read.domain.model.Bookmark
import com.example.read.util.TocItem

/**
 * 阅读器页面，展示书籍章节的分页 WebView 内容，支持翻页卷曲动画。
 *
 * 核心功能：
 * 1. ViewPager2 实现翻页，每页是一个 WebView 渲染的章节页面
 * 2. PageCurlPageTransformer 提供逼真的卷曲翻页效果
 * 3. 点击左右边缘区域翻页，点击中间区域切换顶栏/底栏
 * 4. 底栏包含章节导航、进度条和设置入口
 * 5. 设置面板可实时调整字号、行高、字体和背景色
 *
 * WebView 生命周期管理：
 * - 每个章节使用独立的 WebView 实例，通过缓存复用
 * - 设置变更时对所有缓存的 WebView 更新 CSS
 * - Composable 销毁时销毁所有 WebView
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    // 收集 ViewModel 状态
    val book by viewModel.book.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val chapterIndex by viewModel.chapterIndex.collectAsState()
    val chapterTitle by viewModel.chapterTitle.collectAsState()
    val globalPages by viewModel.globalPages.collectAsState()
    val currentGlobalPage by viewModel.currentGlobalPage.collectAsState()
    val currentPageCount by viewModel.currentPageCount.collectAsState()
    val readingSettings by viewModel.readingSettings.collectAsState()
    val showSettingsDialog by viewModel.showSettings.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val tocItems by viewModel.tocItems.collectAsState()
    // v4：章内搜索状态
    val findActive by viewModel.findActive.collectAsState()
    val findQuery by viewModel.findQuery.collectAsState()
    val findCount by viewModel.findCount.collectAsState()
    val findCurrent by viewModel.findCurrent.collectAsState()
    // v5：全书搜索状态
    val searchMode by viewModel.searchMode.collectAsState()
    val bookSearchResults by viewModel.bookSearchResults.collectAsState()
    val bookSearchInProgress by viewModel.bookSearchInProgress.collectAsState()
    // v6：书签状态
    val bookmarks by viewModel.bookmarks.collectAsState()
    val totalChapters = book?.totalChapters ?: 0
    val totalPages = globalPages.size

    // 顶栏/底栏可见性状态
    var barsVisible by remember { mutableStateOf(true) }
    // 目录面板是否显示
    var showToc by remember { mutableStateOf(false) }
    // v6：书签操作菜单（DropdownMenu）是否展开
    var bookmarkMenuExpanded by remember { mutableStateOf(false) }
    // v6：书签列表 Sheet 是否显示
    var showBookmarkSheet by remember { mutableStateOf(false) }
    // v6：待删除的书签（弹出确认对话框时填充，确认或取消后置空）
    var bookmarkPendingDelete by remember { mutableStateOf<Bookmark?>(null) }
    // v6：触发 SnackBar 提示"已添加书签"的计数器；每次 ++ 都会让 LaunchedEffect 重发一次
    var bookmarkAddedTick by remember { mutableStateOf(0) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 主线程 Handler
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // WebView 工厂实例（使用 applicationContext 避免持有 Activity，参见 P0-2）
    val webViewFactory = remember { ChapterWebViewFactory(context.applicationContext) }

    // WebView LRU 缓存：最多保留 3 个 WebView（当前章 + 前后各 1，参见 P0-2）。
    // 超出窗口的 WebView 立即销毁并解绑 JS 桥，防止原生内存堆积与 Activity 泄漏。
    val webViewCache = remember { WebViewLruCache(maxSize = 3) }

    // ViewPager2 引用
    var viewPagerRef by remember { mutableStateOf<ViewPager2?>(null) }

    // PagedChapterAdapter 引用，用于主动触发章节加载
    var adapterRef by remember { mutableStateOf<PagedChapterAdapter?>(null) }

    // 背景色
    val bgColor = readingSettings.backgroundColor.argb

    // 书籍信息加载完成后预加载相邻章节
    LaunchedEffect(book, chapterIndex) {
        if (book != null) {
            viewModel.preloadAdjacentChapters(context)
        }
    }

    // 章节切换或适配器就绪时，主动触发章节 HTML 加载
    // 这解决了初始加载死锁：ViewPager2 创建时 pages 为空，onBindViewHolder 不会触发，
    // 需要主动加载第一章的 HTML，JS 回调后 pages 才会填充
    LaunchedEffect(chapterIndex, adapterRef) {
        adapterRef?.loadChapter(chapterIndex)
    }

    // 错误处理
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // v6：每次点击"添加书签"后弹一次 SnackBar；
    // 用 bookmarkAddedTick 作 key 让 LaunchedEffect 在 tick 改变时重新执行。
    LaunchedEffect(bookmarkAddedTick) {
        if (bookmarkAddedTick > 0) {
            snackbarHostState.showSnackbar("已添加书签")
        }
    }

    // Composable 销毁时清理所有 WebView（LRU 缓存的 clear() 会逐一 destroy）
    DisposableEffect(Unit) {
        onDispose {
            webViewCache.clear()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = barsVisible,
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
            ) {
                // v4：根据 findActive 切换顶栏形态
                // - findActive == false：原有 TopAppBar（书名 + 章节标题 + 操作按钮）
                // - findActive == true ：搜索栏（输入框 + 计数 + 上下导航 + 关闭）
                if (findActive) {
                    FindInPageBar(
                        query = findQuery,
                        count = findCount,
                        current = findCurrent,
                        searchMode = searchMode,
                        onQueryChange = { q ->
                            // 章内 / 全书模式分别走不同的 ViewModel 入口
                            if (searchMode == SearchMode.WholeBook) {
                                viewModel.searchWholeBook(q)
                            } else {
                                viewModel.updateFindQuery(q)
                            }
                        },
                        onPrev = { viewModel.findPrev() },
                        onNext = { viewModel.findNext() },
                        onClose = { viewModel.exitFindMode() },
                        onToggleMode = {
                            // v5：在"章内 / 全书"之间切换；ViewModel 内部会清空当前查询与结果
                            val next = if (searchMode == SearchMode.InChapter) {
                                SearchMode.WholeBook
                            } else {
                                SearchMode.InChapter
                            }
                            viewModel.setSearchMode(next)
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = book?.title ?: "",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = chapterTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            // v4：搜索按钮，点击进入搜索模式（顶栏切换为搜索栏）
                            IconButton(onClick = { viewModel.enterFindMode() }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                            // v6：书签按钮 + DropdownMenu（"添加书签 / 查看书签"）。
                            // 位置：搜索 (Search) 与目录 (List) 之间，遵循"高频→低频"的视觉权重
                            Box {
                                IconButton(onClick = { bookmarkMenuExpanded = true }) {
                                    Icon(Icons.Default.Bookmark, contentDescription = "书签")
                                }
                                DropdownMenu(
                                    expanded = bookmarkMenuExpanded,
                                    onDismissRequest = { bookmarkMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("添加书签") },
                                        onClick = {
                                            bookmarkMenuExpanded = false
                                            // 调 ViewModel 把当前位置写入 Repository
                                            viewModel.addCurrentPageBookmark()
                                            // 触发 SnackBar 提示（LaunchedEffect 监听 bookmarkAddedTick）
                                            bookmarkAddedTick++
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("查看书签（${bookmarks.size}）") },
                                        onClick = {
                                            bookmarkMenuExpanded = false
                                            showBookmarkSheet = true
                                        },
                                    )
                                }
                            }
                            // 目录按钮：仅当有目录条目时显示，点击打开目录面板
                            if (tocItems.isNotEmpty()) {
                                IconButton(onClick = { showToc = true }) {
                                    Icon(Icons.Default.List, contentDescription = "目录")
                                }
                            }
                            IconButton(onClick = { viewModel.showSettings() }) {
                                Icon(Icons.Default.Settings, contentDescription = "设置")
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = barsVisible,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BottomAppBar {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        // 全局进度滑块
                        if (totalPages > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Slider(
                                    value = currentGlobalPage.toFloat(),
                                    onValueChange = { newValue ->
                                        val targetPage = newValue.toInt()
                                        viewModel.jumpToGlobalPage(targetPage)
                                        viewPagerRef?.setCurrentItem(targetPage, false)
                                    },
                                    valueRange = 0f..(totalPages - 1).toFloat().coerceAtLeast(1f),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }

                        // 底部信息行：全局页码 + 章节导航
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "第${currentGlobalPage + 1}页 / 共${totalPages}页",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )

                            IconButton(
                                onClick = { viewModel.previousChapter() },
                                enabled = chapterIndex > 0,
                            ) {
                                Text(
                                    text = "上一章",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (chapterIndex > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                )
                            }

                            IconButton(
                                onClick = { viewModel.nextChapter() },
                                enabled = chapterIndex < totalChapters - 1,
                            ) {
                                Text(
                                    text = "下一章",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (chapterIndex < totalChapters - 1) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->

        // 内容区域：ViewPager2 实现翻页
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                AndroidView(
                    factory = { ctx ->
                        ViewPager2(ctx).apply {
                            orientation = ViewPager2.ORIENTATION_HORIZONTAL
                            setPageTransformer(PageCurlPageTransformer())
                            offscreenPageLimit = 1

                            // 创建页面适配器
                            val adapter = PagedChapterAdapter(
                                webViewFactory = webViewFactory,
                                webViewCache = webViewCache,
                                readingSettings = readingSettings,
                                onPageCountReady = { chIdx, pageCount ->
                                    mainHandler.post {
                                        viewModel.onPageCountReady(chIdx, pageCount)
                                    }
                                },
                                getBookDirPath = { book?.bookDirPath ?: "" },
                                getOpfDir = { viewModel.bookMetadataPublic?.opfDir ?: "" },
                                getChapterHtmlPath = { chIdx, onResult ->
                                    viewModel.getChapterHtmlPath(chIdx, onResult)
                                },
                                onChapterHtmlLoaded = { chIdx ->
                                    // HTML 加载完成后，如果当前页属于该章节，重新滚动到目标页
                                    // 注意：此回调在 WebView JS 线程触发，必须 post 到主线程操作 ViewPager2
                                    mainHandler.post {
                                        val currentPages = viewModel.globalPages.value
                                        val currentGlobalPage = viewModel.currentGlobalPage.value
                                        if (currentGlobalPage >= 0 && currentGlobalPage < currentPages.size) {
                                            if (currentPages[currentGlobalPage].chapterIndex == chIdx) {
                                                viewPagerRef?.setCurrentItem(currentGlobalPage, false)
                                                // v4：当前章节 JS 注入完毕后，把对应 WebView 包装为
                                                // FindInPageController 并注入 ViewModel，供搜索调用。
                                                // 仅当 chIdx 是当前章节时注入，避免预加载章节抢占 controller。
                                                webViewCache[chIdx]?.let { wv ->
                                                    viewModel.attachFindController(FindInPageController(wv))
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                            this.adapter = adapter
                            adapterRef = adapter

                            // 页面切换回调
                            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                                override fun onPageSelected(position: Int) {
                                    super.onPageSelected(position)
                                    viewModel.jumpToGlobalPage(position)
                                    // v4：滑动到新的全局页时，把当前章节对应的 WebView 注入为
                                    // FindInPageController。如果 WebView 还未在缓存里（新章节首次访问），
                                    // 真正注入会由后续的 onChapterHtmlLoaded 回调完成；这里仅处理"回到
                                    // 已加载章节"的情况，避免章节切换时 controller 残留指向旧 WebView。
                                    val pages = viewModel.globalPages.value
                                    if (position in pages.indices) {
                                        val chIdx = pages[position].chapterIndex
                                        webViewCache[chIdx]?.let { wv ->
                                            viewModel.attachFindController(FindInPageController(wv))
                                        }
                                    }
                                }
                            })

                            // 触摸事件处理：区分点击和滑动
                            var downX = 0f
                            var downY = 0f
                            setOnTouchListener { view, event ->
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = event.x
                                        downY = event.y
                                        false
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        val dx = event.x - downX
                                        val dy = event.y - downY
                                        val density = view.context.resources.displayMetrics.density
                                        val isTap = Math.abs(dx) < 10 * density && Math.abs(dy) < 10 * density
                                        if (isTap) {
                                            val x = event.x
                                            val width = view.width
                                            when {
                                                x < width * 0.25f -> {
                                                    viewModel.previousPage()
                                                    true
                                                }
                                                x > width * 0.75f -> {
                                                    viewModel.nextPage()
                                                    true
                                                }
                                                else -> {
                                                    barsVisible = !barsVisible
                                                    false
                                                }
                                            }
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            }

                            viewPagerRef = this
                        }
                    },
                    update = { viewPager ->
                        val adapter = viewPager.adapter as? PagedChapterAdapter ?: return@AndroidView

                        // 更新适配器数据
                        adapter.updateData(globalPages, readingSettings)

                        // 同步 ViewPager2 到当前全局页码
                        if (viewPager.currentItem != currentGlobalPage) {
                            viewPager.setCurrentItem(currentGlobalPage, false)
                        }

                        // 更新背景色
                        viewPager.setBackgroundColor(bgColor)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 阅读设置底部弹出面板
    if (showSettingsDialog) {
        ReadingSettingsDialog(
            currentSettings = readingSettings,
            onSettingsChange = { newSettings ->
                viewModel.updateSettings(newSettings)
                // P0-2：设置变更必须销毁所有缓存 WebView 重新分页，
                // 不复用旧实例。复用会导致旧 PaginationBridge 闭包累积、CSS 注入冗余、
                // 章内页码与新分页错位等问题。
                webViewCache.clear()
                // 适配器的 loadedChapters 也需清空（由 PagedChapterAdapter.updateData 处理）
            },
            onDismiss = { viewModel.hideSettings() },
        )
    }

    // 目录底部弹出面板
    if (showToc) {
        val sheetState = rememberModalBottomSheetState()
        // 自动滚动到当前章节所在位置
        val listState = rememberLazyListState()
        // 计算当前章节在目录列表中的索引，用于初始滚动
        val currentTocIndex = tocItems.indexOfFirst { it.chapterIndex == chapterIndex }
            .coerceAtLeast(0)

        // 面板显示后自动滚动到当前章节位置
        LaunchedEffect(showToc, currentTocIndex) {
            if (showToc && currentTocIndex > 0) {
                listState.animateScrollToItem(currentTocIndex)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showToc = false },
            sheetState = sheetState,
        ) {
            // 目录标题
            Text(
                text = "目录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 目录条目列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                items(tocItems) { tocItem ->
                    val isCurrentChapter = tocItem.chapterIndex == chapterIndex
                    // 根据目录层级缩进：一级目录无缩进，二级缩进 24dp
                    val indentPadding = (tocItem.level * 24).dp

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.jumpToChapter(tocItem.chapterIndex)
                                showToc = false
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .padding(start = indentPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tocItem.title,
                            style = MaterialTheme.typography.bodyLarge,
                            // 当前章节高亮显示
                            fontWeight = if (isCurrentChapter) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrentChapter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 当前章节标记
                        if (isCurrentChapter) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    // v5：全书搜索结果底部弹出面板。
    // 仅当 findActive=true 且 searchMode=WholeBook 时显示（避免用户切回章内模式后
    // sheet 仍占据屏幕一半）；面板内显示"章节标题 / 匹配数 / 摘要"，点击跳转。
    if (findActive && searchMode == SearchMode.WholeBook) {
        WholeBookSearchResultsSheet(
            query = findQuery,
            results = bookSearchResults,
            inProgress = bookSearchInProgress,
            onResultClick = { result ->
                // 跳转到对应章节并在新章自动定位关键词（pendingFindAfterJump 机制）
                viewModel.onBookSearchResultClicked(result)
            },
            onDismissRequest = {
                // 用户下滑关闭面板：切回章内模式让顶栏回到章内搜索栏
                viewModel.setSearchMode(SearchMode.InChapter)
            },
        )
    }

    // v6：书签列表底部弹出面板。
    // 用户在顶栏点"查看书签"打开；点击列表项跳转到对应位置 + 关闭面板。
    if (showBookmarkSheet) {
        BookmarkListSheet(
            bookmarks = bookmarks,
            tocItems = tocItems,
            onBookmarkClick = { bookmark ->
                showBookmarkSheet = false
                // 跳转到书签所在 (chapterIndex, pageInChapter)
                viewModel.jumpToBookmark(bookmark)
            },
            onBookmarkDelete = { bookmark ->
                // 长按 / 删除按钮触发：先弹确认对话框，确认后才真删除
                bookmarkPendingDelete = bookmark
            },
            onDismissRequest = { showBookmarkSheet = false },
        )
    }

    // v6：删除书签确认对话框。
    bookmarkPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { bookmarkPendingDelete = null },
            title = { Text("删除书签？") },
            text = {
                Text("将删除位于 第${pending.chapterIndex + 1}章 第${pending.pageInChapter + 1}页 的书签。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBookmark(pending.id)
                    bookmarkPendingDelete = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkPendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * v5：全书搜索结果底部弹出面板。
 *
 * 形态：
 * - 顶部：搜索摘要 "全书搜索：{query}（{N} 章命中）"
 * - 进度态：LinearProgressIndicator 横条
 * - 空态：当 query 长度 < 2 时显示"输入至少 2 个字符开始搜索"
 *   当结果为空时显示"未在书中找到该关键词"
 * - 结果列表：LazyColumn 渲染 [SearchResult]，每项可点击
 *
 * 用 [ModalBottomSheet] 实现，sheetState 默认半屏。点击外层背景 / 下滑会触发
 * [onDismissRequest]，由调用方决定关闭策略（这里 ReaderScreen 切回章内模式）。
 *
 * @param query 当前查询词（用于顶部摘要展示）
 * @param results 命中结果列表
 * @param inProgress 是否正在搜索（显示进度条）
 * @param onResultClick 点击单项的回调；通常 ViewModel.onBookSearchResultClicked + 自动关闭面板
 * @param onDismissRequest 关闭面板的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WholeBookSearchResultsSheet(
    query: String,
    results: List<SearchResult>,
    inProgress: Boolean,
    onResultClick: (SearchResult) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // 顶部摘要：根据状态显示不同文案
            val summary = when {
                query.length < 2 -> "输入至少 2 个字符开始全书搜索"
                inProgress -> "正在搜索“$query”…"
                results.isEmpty() -> "未在书中找到“$query”"
                else -> "全书搜索“$query” · ${results.size} 章命中"
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // 进度条：仅搜索中显示，使用低强度颜色避免抢眼
            if (inProgress) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            // 结果列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                items(results, key = { "search_${it.chapterIndex}" }) { result ->
                    WholeBookSearchResultItem(
                        result = result,
                        onClick = { onResultClick(result) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * 单条全书搜索结果项的渲染。
 *
 * 布局：
 * - 章节标题（Bold）+ 右侧匹配数徽章（"N 处"）
 * - 摘要文本（最多 3 行，超出省略）
 *
 * 点击触发 [onClick]，调用方负责 jumpToChapter + 关闭面板。
 */
@Composable
private fun WholeBookSearchResultItem(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = result.chapterTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // 匹配数徽章：用 labelSmall + primary 颜色，区分于标题
            Text(
                text = "${result.matchCount} 处",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = result.firstMatchSnippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * v6：书签列表底部弹出面板。
 *
 * 形态（与 WholeBookSearchResultsSheet 同款，保持视觉一致）：
 * - 顶部标题："书签（N）"或"暂无书签，点击顶栏图标添加"
 * - 列表：LazyColumn 渲染所有书签，每项有：
 *   - 章节标题（从 tocItems 查找；找不到时退到"第 X 章"）
 *   - "第 X 页"标识
 *   - 相对时间（"X 分钟前 / X 小时前 / X 天前"）
 *   - 右侧删除按钮（点击触发 onBookmarkDelete，调用方负责弹确认对话框）
 *
 * 排序：bookmarks 列表本身已由 Repository 按 createdAt DESC 排序（最新在前），
 * 这里直接渲染即可。
 *
 * @param bookmarks 当前书的所有书签（已按 createdAt 倒序）
 * @param tocItems NCX 解析的目录条目，用于解析 chapterIndex → 章节标题
 * @param onBookmarkClick 点击书签项的回调（调用方应 jumpToBookmark + 关闭面板）
 * @param onBookmarkDelete 点击单项删除按钮的回调（调用方应弹确认对话框再 removeBookmark）
 * @param onDismissRequest 下滑关闭面板的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkListSheet(
    bookmarks: List<Bookmark>,
    tocItems: List<TocItem>,
    onBookmarkClick: (Bookmark) -> Unit,
    onBookmarkDelete: (Bookmark) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            // 顶部标题：空状态与有书签态分文案显示
            val title = if (bookmarks.isEmpty()) {
                "暂无书签"
            } else {
                "书签（${bookmarks.size}）"
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            // 空状态引导文案
            if (bookmarks.isEmpty()) {
                Text(
                    text = "在阅读时点击顶栏书签图标 → 添加书签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                return@Column
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            // 书签列表
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // key 使用 bookmark.id 让 LazyColumn 能正确做项目识别（删除后动画顺滑）
                items(bookmarks, key = { "bookmark_${it.id}" }) { bookmark ->
                    BookmarkItem(
                        bookmark = bookmark,
                        tocItems = tocItems,
                        onClick = { onBookmarkClick(bookmark) },
                        onDelete = { onBookmarkDelete(bookmark) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * v6：单条书签项的渲染。
 *
 * 布局：
 * - 左侧 Column：章节标题（Bold）+ 第 X 页 + 相对时间（弱化色）
 * - 右侧 IconButton：删除按钮（垃圾桶图标）
 *
 * 章节标题查找策略：优先从 tocItems 匹配 chapterIndex；找不到时退到"第 N 章"。
 * 此处不直接接触 BookMetadata，所有信息从 ViewModel 传入的 tocItems 拿。
 *
 * @param bookmark 当前书签
 * @param tocItems NCX 目录条目，用于查找章节标题
 * @param onClick 点击整项的回调
 * @param onDelete 点击删除图标的回调
 */
@Composable
private fun BookmarkItem(
    bookmark: Bookmark,
    tocItems: List<TocItem>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // 解析章节标题：优先 NCX 目录，缺失时退到"第 N 章"占位
    val chapterTitle = tocItems.firstOrNull { it.chapterIndex == bookmark.chapterIndex }
        ?.title
        ?.takeIf { it.isNotBlank() }
        ?: "第${bookmark.chapterIndex + 1}章"
    val relativeTime = formatRelativeTime(bookmark.createdAt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "第${bookmark.pageInChapter + 1}页 · $relativeTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        // 删除按钮：单独区域，避免误触整项 onClick
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除书签",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * v6：把 epoch millis 格式化为相对时间字符串（"5 分钟前 / 3 小时前 / 2 天前"）。
 *
 * 简单实现，无需依赖第三方库：
 * - <1 分钟 → "刚刚"
 * - <1 小时 → "X 分钟前"
 * - <1 天   → "X 小时前"
 * - <30 天  → "X 天前"
 * - 其他   → "X 月前 / X 年前"
 *
 * 时区使用系统默认（System.currentTimeMillis 是 UTC，但差值与时区无关）。
 *
 * @param epochMillis 创建时间戳
 * @return 相对时间字符串
 */
private fun formatRelativeTime(epochMillis: Long): String {
    val diff = System.currentTimeMillis() - epochMillis
    if (diff < 0L) return "刚刚" // 未来时间兜底（如设备时钟跳跃）
    val seconds = diff / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L
    val months = days / 30L
    val years = days / 365L
    return when {
        seconds < 60L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        hours < 24L -> "${hours} 小时前"
        days < 30L -> "${days} 天前"
        months < 12L -> "${months} 个月前"
        else -> "${years} 年前"
    }
}

/**
 * WebView LRU 缓存（P0-2）。
 *
 * 设计目标：限制同时存活的 WebView 数量，超出窗口时立即销毁原生资源，
 * 防止长篇 EPUB（数十章）连续翻页造成原生堆持续增长直至 OOM。
 *
 * 关键操作：
 * - get/contains/iteration 维持现有调用方式
 * - 写入超出 maxSize 时 removeEldestEntry：
 *   先 removeJavascriptInterface 解绑 AndroidBridge 闭包（释放 ViewModel 引用），
 *   loadUrl("about:blank") 触发 WebView 清空 DOM 与 JS 上下文，
 *   最后 destroy() 释放原生资源
 * - LinkedHashMap accessOrder=true：访问任一章节都会重排顺序，最久未访问的被淘汰
 *
 * 线程要求：所有方法都必须在主线程调用（WebView 操作只能在创建它的线程）。
 *
 * @param maxSize 最多保留的 WebView 数（默认 3 = 当前章 + 前后各 1）
 */
internal class WebViewLruCache(private val maxSize: Int = 3) {

    private val map = object : LinkedHashMap<Int, WebView>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WebView>): Boolean {
            return if (size > maxSize) {
                // 销毁顺序：移除 JS 桥 -> 清空内容 -> 从父布局摘除 -> destroy
                eldest.value.let { wv ->
                    try { wv.removeJavascriptInterface("AndroidBridge") } catch (_: Throwable) {}
                    try { wv.loadUrl("about:blank") } catch (_: Throwable) {}
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    try { wv.destroy() } catch (_: Throwable) {}
                }
                true
            } else false
        }
    }

    /** 取出指定章节的 WebView，并把它放到最近访问位置 */
    operator fun get(key: Int): WebView? = map[key]

    /** 写入 WebView；若超出窗口，eldest 会被销毁并移除 */
    operator fun set(key: Int, value: WebView) {
        map[key] = value
    }

    fun containsKey(key: Int): Boolean = map.containsKey(key)

    /**
     * 清空缓存：销毁所有 WebView。
     * 用于 Composable 销毁或设置变更触发整体重建。
     */
    fun clear() {
        for (wv in map.values.toList()) {
            try { wv.removeJavascriptInterface("AndroidBridge") } catch (_: Throwable) {}
            try { wv.loadUrl("about:blank") } catch (_: Throwable) {}
            (wv.parent as? ViewGroup)?.removeView(wv)
            try { wv.destroy() } catch (_: Throwable) {}
        }
        map.clear()
    }
}

/**
 * ViewPager2 的页面适配器，将全局页面渲染为 WebView。
 *
 * 核心设计：
 * - 每个 page 对应一个 PageInfo（chapterIndex + pageInChapter）
 * - 每个章节使用独立的 WebView 实例，通过 LRU 缓存（WebViewLruCache）复用并限制总量
 * - 当页面绑定时，获取或创建该章节的 WebView，添加到容器并滚动到目标页
 * - 当页面回收时，从容器中移除 WebView（不销毁，仍在 LRU 缓存中可被复用；
 *   超出 LRU 窗口后由缓存负责销毁）
 *
 * @param webViewFactory WebView 工厂，用于创建新的 WebView
 * @param webViewCache LRU 缓存（最多保留 3 个 WebView），淘汰时自动销毁
 * @param readingSettings 当前阅读设置
 * @param onPageCountReady 页数计算完成的回调
 * @param getBookDirPath 获取书籍解包目录路径的回调
 * @param getOpfDir 获取 OPF 相对目录路径的回调
 * @param getChapterHtmlPath 异步获取章节 HTML 路径的回调
 */
private class PagedChapterAdapter(
    private val webViewFactory: ChapterWebViewFactory,
    private val webViewCache: WebViewLruCache,
    private var readingSettings: ReadingSettings,
    private val onPageCountReady: (Int, Int) -> Unit,
    private val getBookDirPath: () -> String,
    private val getOpfDir: () -> String,
    private val getChapterHtmlPath: (Int, (String?) -> Unit) -> Unit,
    private val onChapterHtmlLoaded: (Int) -> Unit = {},
) : androidx.recyclerview.widget.RecyclerView.Adapter<PagedChapterAdapter.PageViewHolder>() {

    /** 当前全局页面列表 */
    private var pages: List<PageInfo> = emptyList()

    /** 已加载 HTML 的章节集合，避免重复加载 */
    private val loadedChapters = mutableSetOf<Int>()

    /** 用于把章节 HTML 文件读取等 I/O 操作切到后台线程（C 项要求，避免主线程 I/O） */
    private val ioExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "reader-html-io").apply { isDaemon = true }
        }

    /** 用于把 IO 线程结果切回主线程（WebView 必须在主线程操作） */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 页面 ViewHolder，持有一个 FrameLayout 容器。
     * WebView 在绑定时动态添加到容器中。
     */
    class PageViewHolder(
        val container: FrameLayout,
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        return PageViewHolder(container)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        if (position >= pages.size) return
        val pageInfo = pages[position]

        // 先清空容器中的旧 WebView
        holder.container.removeAllViews()

        // 获取或创建该章节的 WebView
        val webView = getOrCreateWebView(pageInfo.chapterIndex)

        // 将 WebView 从旧的 parent 中移除（如果有）
        (webView.parent as? ViewGroup)?.removeView(webView)

        // 添加到当前容器
        holder.container.addView(webView)

        // 始终尝试滚动到目标页
        // 如果章节已加载完成，直接滚动；否则 WebView 会在 HTML 加载后自动滚动
        if (loadedChapters.contains(pageInfo.chapterIndex)) {
            webViewFactory.scrollToPage(webView, pageInfo.pageInChapter)
        }
    }

    override fun getItemCount(): Int = pages.size

    /**
     * 获取或创建指定章节的 WebView。
     *
     * 如果缓存中已有，直接返回；否则创建新的并异步加载 HTML：
     * 1. 在 ViewModel 协程中获取 HTML 路径（已在 IO 切换）
     * 2. 在 ioExecutor 线程读取 HTML 内容（C 项：避免主线程 readText）
     * 3. 切回主线程调用 webViewFactory.loadHtml（WebView 操作必须在主线程）
     *
     * loadHtml 使用 WebViewAssetLoader 通过 https 协议提供资源（P0-3），
     * 不再使用 file:// baseUrl。
     */
    private fun getOrCreateWebView(chapterIndex: Int): WebView {
        webViewCache[chapterIndex]?.let { return it }

        val webView = webViewFactory.create()
        webViewCache[chapterIndex] = webView

        // 异步获取 HTML 路径并加载
        getChapterHtmlPath(chapterIndex) { htmlPath ->
            if (htmlPath == null) {
                // HTML 路径获取失败，回退到 1 页避免空状态
                onPageCountReady(chapterIndex, 1)
                return@getChapterHtmlPath
            }
            // 将文件读取下沉到 IO 线程（P1-1 / C 项），避免主线程 ANR
            ioExecutor.execute {
                val htmlFile = java.io.File(htmlPath)
                if (!htmlFile.exists()) {
                    mainHandler.post { onPageCountReady(chapterIndex, 1) }
                    return@execute
                }
                val htmlContent = try {
                    htmlFile.readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    mainHandler.post { onPageCountReady(chapterIndex, 1) }
                    return@execute
                }
                val bookDirPath = getBookDirPath()
                val opfDir = getOpfDir()
                mainHandler.post {
                    // P1-2 防御：即使 ChapterWebViewFactory.loadHtml 内部已对 bookDirPath
                    // 做了 PREPARING_/空串校验，这里再加一层 try-catch 防止未来：
                    // - WebViewAssetLoader 在新版本里对其他路径抛 IllegalArgumentException
                    // - addJavascriptInterface / loadDataWithBaseURL 在某些设备上抛非预期异常
                    // 任一异常都不能让阅读器主线程崩溃，转而显示空白页（HTML 已被加载到 about:blank）。
                    try {
                        webViewFactory.loadHtml(
                            webView = webView,
                            htmlContent = htmlContent,
                            bookDirPath = bookDirPath,
                            opfDirRelative = opfDir,
                            readingSettings = readingSettings,
                        ) { pageCount ->
                            loadedChapters.add(chapterIndex)
                            onPageCountReady(chapterIndex, pageCount)
                            onChapterHtmlLoaded(chapterIndex)
                        }
                    } catch (e: IllegalArgumentException) {
                        // WebViewAssetLoader 路径校验失败：fallback 到空白页
                        android.util.Log.w(
                            "PagedChapterAdapter",
                            "loadHtml threw IllegalArgumentException (bookDirPath=$bookDirPath)",
                            e,
                        )
                        try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
                        onPageCountReady(chapterIndex, 1)
                    } catch (e: Throwable) {
                        // 其他未预期异常同样兜底
                        android.util.Log.w(
                            "PagedChapterAdapter",
                            "loadHtml threw unexpected exception",
                            e,
                        )
                        try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
                        onPageCountReady(chapterIndex, 1)
                    }
                }
            }
        }

        return webView
    }

    /**
     * 主动加载指定章节的 HTML 内容。
     * 用于初始加载场景：ViewPager2 创建时 pages 为空，onBindViewHolder 不会触发，
     * 需要主动调用此方法加载第一章的 HTML 并计算页数。
     *
     * @param chapterIndex 要加载的章节索引
     */
    fun loadChapter(chapterIndex: Int) {
        getOrCreateWebView(chapterIndex)
    }

    /**
     * 更新适配器数据。
     *
     * @param newPages 新的全局页面列表
     * @param newSettings 新的阅读设置
     */
    fun updateData(newPages: List<PageInfo>, newSettings: ReadingSettings) {
        val pagesChanged = pages != newPages
        val settingsChanged = readingSettings != newSettings

        if (pagesChanged || settingsChanged) {
            pages = newPages
            readingSettings = newSettings
            if (settingsChanged) {
                // 设置变更时清除已加载标记，触发重新加载
                loadedChapters.clear()
            }
            notifyDataSetChanged()
        }
    }
}

/**
 * v4：章内搜索的顶栏组件。
 *
 * 形态：单行 Surface 容器，内部从左到右依次是：
 * 1) TextField（关键词输入框，占据剩余宽度）
 * 2) 匹配计数 Text，形如 "1 / 12"（仅章内模式显示）
 * 3) 上一项 IconButton（KeyboardArrowUp，仅章内模式启用）
 * 4) 下一项 IconButton（KeyboardArrowDown，仅章内模式启用）
 * 5) "全书 / 章内"切换 IconButton（v5 新增）
 * 6) 关闭 IconButton（Close，退出搜索模式）
 *
 * 设计取舍：
 * - 不复用 TopAppBar：TopAppBar 的 title slot 对输入框宽度控制不够灵活，
 *   自定义 Surface + Row 能更直接地排版
 * - 高度参考 TopAppBar 默认 64dp，使用 height(64.dp) 显式设置以匹配
 * - TextField 用 TextFieldDefaults.colors 把背景设为 transparent，避免在 Surface
 *   tonal elevation 之上叠加额外色块；同时去掉 indicator 让外观更接近搜索栏
 * - 计数 0 / 0 在 query 非空但无匹配时显示，提示用户该关键词在本章不存在
 * - v5：全书模式下隐藏"x / y"计数和上下导航（全书搜索结果在底部面板独立展示）
 *
 * 输入框未做 IME action 处理（不强制按"完成"键确认），因为搜索是实时触发的
 * （updateFindQuery 每次输入都执行 find），用户无需显式提交。
 *
 * @param query 当前关键词，从 ViewModel.findQuery 流入
 * @param count 当前章节匹配总数
 * @param current 当前选中的匹配索引（0-based）；-1 表示未选中
 * @param searchMode 当前搜索模式（InChapter / WholeBook）
 * @param onQueryChange 输入变化回调，章内模式转发给 updateFindQuery；全书模式转发给 searchWholeBook
 * @param onPrev 上一项点击回调（章内模式有效）
 * @param onNext 下一项点击回调（章内模式有效）
 * @param onClose 关闭按钮回调，应触发 ViewModel.exitFindMode
 * @param onToggleMode 切换搜索模式回调（章内 ↔ 全书）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindInPageBar(
    query: String,
    count: Int,
    current: Int,
    searchMode: SearchMode,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onToggleMode: () -> Unit,
) {
    // 顶栏背景用 Material 3 TopAppBar 的 container color 保持视觉一致
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 关键词输入框：占据剩余宽度，去掉 indicator 让外观更像搜索栏
            // v5：根据 searchMode 显示不同 placeholder，让用户清楚当前作用域
            val placeholderText = if (searchMode == SearchMode.WholeBook) {
                "在全书搜索（≥2 字符）"
            } else {
                "在本章搜索"
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = placeholderText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )

            // 章内模式：显示"x / y"计数 + 上下导航；全书模式由底部 Sheet 显示结果
            if (searchMode == SearchMode.InChapter) {
                // 匹配计数 "x / y"
                // - 无匹配时显示 "0 / 0"
                // - 有匹配时 current 是 0-based 内部索引，UI 上展示为 1-based 更直观
                val displayCurrent = if (count > 0 && current >= 0) current + 1 else 0
                Text(
                    text = "$displayCurrent / $count",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (count > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(horizontal = 8.dp),
                )

                // 上一项（向前查找）
                IconButton(
                    onClick = onPrev,
                    enabled = count > 0,
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "上一个",
                    )
                }
                // 下一项（向后查找）
                IconButton(
                    onClick = onNext,
                    enabled = count > 0,
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "下一个",
                    )
                }
            }

            // v5：搜索模式切换按钮（章内 ↔ 全书）
            // - 章内模式时显示"全书图标"，点击切到全书搜索
            // - 全书模式时显示"页面图标"，点击切回章内搜索
            IconButton(onClick = onToggleMode) {
                if (searchMode == SearchMode.InChapter) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "切换到全书搜索",
                    )
                } else {
                    Icon(
                        Icons.Default.FindInPage,
                        contentDescription = "切换到章内搜索",
                    )
                }
            }

            // 关闭搜索栏
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭搜索",
                )
            }
        }
    }
}
