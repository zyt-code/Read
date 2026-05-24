package com.example.read.ui.reader

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.read.data.search.BookSearchEngine
import com.example.read.data.search.SearchResult
import com.example.read.domain.model.Book
import com.example.read.domain.model.Bookmark
import com.example.read.domain.repository.BookRepository
import com.example.read.ui.navigation.Reader
import com.example.read.util.BookMetadata
import com.example.read.util.TocItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 阅读器搜索模式（v5 feature: whole-book search）。
 *
 * - [InChapter]：v4 章内搜索；输入实时高亮当前章节的匹配项，按上下箭头在匹配间循环
 * - [WholeBook]：v5 全书搜索；输入后 [BookSearchEngine] 并发扫描所有章节，结果在
 *   底部弹出面板列出"章节标题 / 匹配数 / 摘要"，点击跳转到对应章并启动章内搜索
 *
 * 切换模式时清空当前查询和结果（searchMode 切换由 [ReaderViewModel.setSearchMode] 处理）。
 */
enum class SearchMode { InChapter, WholeBook }

/**
 * 阅读器页面的 ViewModel，管理当前书籍、全局页面流和阅读设置。
 *
 * 核心架构变更（相比旧版）：
 * - 从每章一个页面列表改为全局连续页面流
 * - 分页由 WebView 的 JavaScript 完成，ViewModel 只接收页数回调
 * - 翻页基于全局页码（globalPage），跨章节无缝衔接
 *
 * 进度保存策略：
 * - 章节索引：通过 repository.updateReadingProgress 保存到 Room 数据库
 * - 章内页码：通过 SharedPreferences 按 bookId+chapterIndex 保存（写入做 100ms debounce 节流）
 * - 恢复时先加载章节，再等 WebView 回调后跳转到保存的章内页码
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: BookRepository,
    private val application: Application,
    savedStateHandle: SavedStateHandle,
    private val bookSearchEngine: BookSearchEngine,
) : AndroidViewModel(application) {

    /** 从导航路由中提取 bookId */
    private val bookId: Long = try {
        savedStateHandle.toRoute<Reader>().bookId
    } catch (e: Exception) {
        savedStateHandle.get<Long>("bookId") ?: 0L
    }

    /** 阅读设置管理器，负责设置的持久化读写 */
    private val settingsManager = ReadingSettingsManager(application)

    /** 主线程 Handler，用于将 WebView 线程的 JS 回调切换到主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 书籍元数据（spine 信息、opfDir 等），加载后缓存 */
    private var bookMetadata: BookMetadata? = null

    /** 章节内页码的 SharedPreferences 存储，用于恢复阅读位置 */
    private val pagePrefs = application.getSharedPreferences(
        "reader_page_progress", Application.MODE_PRIVATE
    )

    /** 章内页码写入的 debounce job，每次 savePageInChapter 都会取消上一次并重排（C 项） */
    private var savePageJob: Job? = null

    /**
     * 待写入的章内页码（key 为 SharedPreferences 键，value 为 pageInChapter）。
     * 在 debounce 窗口内多次写入只在最后真正 commit。
     */
    private val pendingPageWrites = mutableMapOf<String, Int>()

    /** 当前书籍的元数据（标题、作者、章节数等） */
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book

    /** 当前章节索引（0-based），对应 spine 阅读顺序 */
    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex

    /** 当前章节标题，来自 spine 的 title 字段 */
    private val _chapterTitle = MutableStateFlow("")
    val chapterTitle: StateFlow<String> = _chapterTitle

    /** 全局页面列表，所有已知章节的页面连续排列 */
    private val _globalPages = MutableStateFlow<List<PageInfo>>(emptyList())
    val globalPages: StateFlow<List<PageInfo>> = _globalPages

    /** 当前全局页码（0-based），对应 globalPages 列表的索引 */
    private val _currentGlobalPage = MutableStateFlow(0)
    val currentGlobalPage: StateFlow<Int> = _currentGlobalPage

    /** 当前章节的 HTML 文件路径，供 WebView 加载使用 */
    private val _currentHtmlPath = MutableStateFlow<String?>(null)
    val currentHtmlPath: StateFlow<String?> = _currentHtmlPath

    /** 当前章节的总页数，由 WebView JS 计算后回调更新 */
    private val _currentPageCount = MutableStateFlow(0)
    val currentPageCount: StateFlow<Int> = _currentPageCount

    /** 各章节页数缓存，key 为 chapterIndex，value 为该章节的页数 */
    private val _chapterPageCounts = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val chapterPageCounts: StateFlow<Map<Int, Int>> = _chapterPageCounts

    /** 加载状态，默认为 true，避免首帧 composition 时 ViewPager2 以空数据创建导致状态竞态 */
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** 当前阅读设置 */
    private val _readingSettings = MutableStateFlow(settingsManager.load())
    val readingSettings: StateFlow<ReadingSettings> = _readingSettings

    /** 设置面板是否可见 */
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    /** 目录条目列表，从 NCX 文件解析而来，供 UI 展示章节目录 */
    private val _tocItems = MutableStateFlow<List<TocItem>>(emptyList())
    val tocItems: StateFlow<List<TocItem>> = _tocItems

    /** 错误信息 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // ====================== v4 章内搜索（find-in-page）状态 ======================
    // 设计原则：
    // - 搜索完全限定在当前章节内，跨章节由 ReaderScreen 负责在章节切换 / 设置变更时
    //   调用 exitFindMode() 自动重置
    // - controller 不放在 StateFlow 里，避免持有 WebView 引用进入 Compose recomposition；
    //   它由 ReaderScreen 注入并直接调用 ViewModel 方法消费
    // - findCount / findCurrent 都用 Int，未搜索时为 0 / -1，便于 UI 直接渲染 "x / y"

    /** 搜索栏是否处于激活状态（顶栏切换为输入框） */
    private val _findActive = MutableStateFlow(false)
    val findActive: StateFlow<Boolean> = _findActive

    /** 用户输入的搜索关键词；空串视为无查询，会触发清理高亮 */
    private val _findQuery = MutableStateFlow("")
    val findQuery: StateFlow<String> = _findQuery

    /** 当前章节内匹配总数，由 FindInPageController.find 的 JS 回调更新 */
    private val _findCount = MutableStateFlow(0)
    val findCount: StateFlow<Int> = _findCount

    /** 当前选中的匹配项索引（0-based）；-1 表示无匹配或未开始 */
    private val _findCurrent = MutableStateFlow(-1)
    val findCurrent: StateFlow<Int> = _findCurrent

    /**
     * 当前章节的 FindInPageController 实例。
     * 由 ReaderScreen 在 onChapterHtmlLoaded 时通过 [attachFindController] 注入；
     * 切换章节会自动替换。ViewModel 不持有 WebView 强引用以避免泄漏，仅在调用时使用。
     *
     * 注意：不暴露为 StateFlow，因为它包含 WebView 引用，进入 collect 收集链会增加
     * 不必要的引用持有；UI 层也无需直接观察它，所有 find 操作通过 ViewModel 方法走。
     */
    private var currentFindController: FindInPageController? = null

    // ====================== v5 跨章节全书搜索（whole-book search）状态 ======================
    // 设计原则：
    // - 与 v4 章内搜索共用顶栏入口（搜索按钮），通过 SearchMode 切换"章内"/"全书"两种形态
    // - 全书搜索结果不进入 WebView DOM（不像章内搜索那样高亮文本），而是用底部弹出面板
    //   展示"章节标题 / 匹配数 / 摘要"列表，点击列表项跳转到对应章节并启动章内搜索
    // - 搜索任务用单独 Job 管理，连续输入新 query 时取消旧 Job

    /** 搜索模式（章内 vs 全书） */
    private val _searchMode = MutableStateFlow(SearchMode.InChapter)
    val searchMode: StateFlow<SearchMode> = _searchMode

    /** 全书搜索结果列表，按章节顺序排序 */
    private val _bookSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val bookSearchResults: StateFlow<List<SearchResult>> = _bookSearchResults

    /** 全书搜索是否正在进行中（UI 显示 LinearProgressIndicator） */
    private val _bookSearchInProgress = MutableStateFlow(false)
    val bookSearchInProgress: StateFlow<Boolean> = _bookSearchInProgress

    /** 当前全书搜索任务，连续输入时用于取消旧任务 */
    private var bookSearchJob: Job? = null

    /**
     * 跨章节跳转后挂起的章内查询。
     *
     * 用户点击全书搜索结果时，先 jumpToChapter 切到目标章，章节加载完成（onPageCountReady）
     * 后再触发章内搜索定位到关键词。此字段记录待执行的 query；attachFindController 时消费它。
     *
     * 线程安全（P3-v6-3）：仅在主线程访问 —— 赋值来自 ViewModel 公开方法（主线程），
     * 消费来自 attachFindController（ReaderScreen 的 UI 回调，主线程）。
     * 无需 @Volatile 或同步机制；若未来引入后台线程访问，需改为 MutableStateFlow。
     */
    private var pendingFindAfterJump: String? = null

    // ====================== v6 书签状态 ======================

    /**
     * 当前书籍的所有书签，按 createdAt DESC 排序（最新在前）。
     *
     * 来源：[BookRepository.getBookmarks] 返回的 Flow，通过 stateIn 转 StateFlow。
     * 添加 / 删除书签后会自动响应式刷新，UI 无需手动 refresh。
     *
     * SharingStarted.WhileSubscribed(5000)：与 BookshelfViewModel.books 保持一致策略，
     * 让配置变更（旋转）后 5 秒内不重新订阅查询，避免重复 IO。
     */
    val bookmarks: StateFlow<List<Bookmark>> = repository.getBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 跨章节跳转书签后挂起的章内页码（v6 书签）。
     *
     * 用户点击书签结果时，需要先 jumpToChapter 切到目标章，再在 onPageCountReady
     * 回调里精确翻到 bookmark.pageInChapter。此字段记录待恢复的 pageInChapter；
     * onPageCountReady 时由 pendingPositionRestore + pendingPageInChapter 状态机统一消费。
     *
     * 注意：这与 pendingFindAfterJump 是两条独立通道：前者是搜索跳转，后者是书签跳转；
     * 共用底层的 pending PositionRestore 复位机制，互不干扰。
     */


    /** 是否需要在 onPageCountReady 回调后恢复阅读位置 */
    private var pendingPositionRestore = false

    /** 待恢复的章内页码 */
    private var pendingPageInChapter = 0

    /** loadBook 是否成功完成，用于 onCleared 决定是否覆盖已有进度（B 改进） */
    private var loadSucceeded = false

    init {
        loadBook()
    }

    /**
     * 加载书籍元数据和 spine 信息，恢复到上次阅读位置。
     */
    private fun loadBook() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val loadedBook = repository.getBookById(bookId)
                _book.value = loadedBook
                if (loadedBook == null) {
                    _errorMessage.value = "书籍不存在"
                    return@launch
                }
                val metadata = repository.getBookMetadata(bookId)
                bookMetadata = metadata
                _tocItems.value = metadata.tocItems
                val restoreChapter = loadedBook.lastReadChapter
                _chapterIndex.value = restoreChapter
                loadChapterHtmlPath(restoreChapter)
                val savedPageInChapter = getPageInChapter(restoreChapter)
                if (savedPageInChapter > 0) {
                    pendingPositionRestore = true
                    pendingPageInChapter = savedPageInChapter
                }
                loadSucceeded = true
            } catch (e: CancellationException) {
                // P1-v6-3：协程取消必须重抛，让 viewModelScope 完成正常取消传播。
                // 配置变更（旋转）触发 ViewModel 重建时，不应把 cancel 信号当作业务错误
                // 显示"加载书籍失败"的 Snackbar。
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "加载书籍失败，请返回重试"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 加载指定章节的 HTML 文件路径和标题 */
    private suspend fun loadChapterHtmlPath(index: Int) {
        val metadata = bookMetadata ?: return
        if (index < 0 || index >= metadata.spine.size) return
        val spineItem = metadata.spine[index]
        _chapterTitle.value = spineItem.title.ifEmpty { "第${index + 1}章" }
        val htmlFile = repository.getChapterHtmlFile(bookId, index)
        _currentHtmlPath.value = htmlFile.absolutePath
        _currentPageCount.value = _chapterPageCounts.value[index] ?: 0
    }

    /**
     * JS 回调：某个章节的 WebView 计算完页数后调用。
     * 更新 chapterPageCounts 缓存，重建 globalPages，恢复位置。
     */
    fun onPageCountReady(chapterIndex: Int, pageCount: Int) {
        val newCounts = _chapterPageCounts.value.toMutableMap()
        newCounts[chapterIndex] = pageCount
        _chapterPageCounts.value = newCounts
        if (chapterIndex == _chapterIndex.value) {
            _currentPageCount.value = pageCount
        }
        rebuildGlobalPages()
        if (pendingPositionRestore && chapterIndex == _chapterIndex.value) {
            pendingPositionRestore = false
            val globalIndex = findGlobalPageByPageInChapter(chapterIndex, pendingPageInChapter)
            if (globalIndex >= 0 && globalIndex < _globalPages.value.size) {
                _currentGlobalPage.value = globalIndex
            }
        }
    }

    /** 根据已知的各章节页数缓存，重建全局页面列表 */
    private fun rebuildGlobalPages() {
        val counts = _chapterPageCounts.value
        if (counts.isEmpty()) {
            _globalPages.value = emptyList()
            return
        }
        val pages = mutableListOf<PageInfo>()
        val sortedChapters = counts.keys.sorted()
        for (chIdx in sortedChapters) {
            val pageCount = counts[chIdx] ?: continue
            for (p in 0 until pageCount) {
                pages.add(PageInfo(chapterIndex = chIdx, pageInChapter = p))
            }
        }
        _globalPages.value = pages
    }

    /** 在全局页面列表中查找指定章节的指定章内页码对应的全局索引 */
    private fun findGlobalPageByPageInChapter(chapterIndex: Int, pageInChapter: Int): Int {
        return _globalPages.value.indexOfFirst {
            it.chapterIndex == chapterIndex && it.pageInChapter == pageInChapter
        }
    }

    /** 切换到下一页（全局），跨章节时自动更新章节状态 */
    fun nextPage() {
        val totalPages = _globalPages.value.size
        if (totalPages == 0) return
        val next = _currentGlobalPage.value + 1
        if (next < totalPages) {
            _currentGlobalPage.value = next
            syncChapterState(next)
        }
    }

    /** 切换到上一页（全局），跨章节时自动更新章节状态 */
    fun previousPage() {
        if (_globalPages.value.isEmpty()) return
        val prev = _currentGlobalPage.value - 1
        if (prev >= 0) {
            _currentGlobalPage.value = prev
            syncChapterState(prev)
        }
    }

    /** 跳转到指定全局页码 */
    fun jumpToGlobalPage(index: Int) {
        val totalPages = _globalPages.value.size
        if (totalPages == 0) return
        val safeIndex = index.coerceIn(0, totalPages - 1)
        _currentGlobalPage.value = safeIndex
        syncChapterState(safeIndex)
    }

    /**
     * 根据全局页码同步章节状态。
     * 如果目标页属于不同章节，更新相关状态，触发 UI 层加载新章节。
     */
    private fun syncChapterState(globalIndex: Int) {
        val pages = _globalPages.value
        if (globalIndex < 0 || globalIndex >= pages.size) return
        val pageInfo = pages[globalIndex]
        if (pageInfo.chapterIndex != _chapterIndex.value) {
            savePageInChapter(_chapterIndex.value, getCurrentPageInChapter())
            // v4：跨章节自动退出搜索模式，避免旧章节高亮残留在新章节
            exitFindMode()
            _chapterIndex.value = pageInfo.chapterIndex
            _currentPageCount.value = _chapterPageCounts.value[pageInfo.chapterIndex] ?: 0
            viewModelScope.launch {
                try {
                    loadChapterHtmlPath(pageInfo.chapterIndex)
                    repository.updateReadingProgress(bookId, pageInfo.chapterIndex)
                } catch (e: CancellationException) {
                    // P1-v6-3：协程取消必须重抛，不能当作业务错误显示 toast
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "加载章节失败"
                }
            }
        }
    }

    /**
     * 切换到下一章的第一页。
     *
     * B1 改进：除了 globalPages 中查找外，显式做上界校验，
     * 避免预加载逻辑异常时 globalPages 出现超过 spine.size 的章节索引。
     *
     * P1-3 改进：当下一章尚未被预加载（globalPages 中找不到对应条目）时，
     * 复用 jumpToChapter 的"未预加载兜底"路径，触发章节状态切换 + UI 加载新章节。
     * 这样底栏"下一章"按钮在快速点击或预加载滞后场景下也始终有响应，
     * 不再静默 no-op。jumpToChapter 自己会再做一次章节边界校验，安全幂等。
     */
    fun nextChapter() {
        val currentChapter = _chapterIndex.value
        val spineSize = bookMetadata?.spine?.size ?: 0
        // 显式上界校验：到达末章直接返回
        if (currentChapter + 1 >= spineSize) return
        val nextPage = _globalPages.value.indexOfFirst {
            it.chapterIndex == currentChapter + 1 && it.pageInChapter == 0
        }
        if (nextPage >= 0) {
            // 已预加载：直接全局页跳转，保留 globalPages 的连续性
            jumpToGlobalPage(nextPage)
        } else {
            // 未预加载：走章节切换兜底（jumpToChapter 内部也有边界校验，复用即可）
            jumpToChapter(currentChapter + 1)
        }
    }

    /**
     * 切换到上一章的第一页。
     *
     * B1 改进：显式下界校验，第 0 章直接返回。
     *
     * P1-3 改进：与 nextChapter 对称，上一章未在 globalPages 时复用 jumpToChapter。
     */
    fun previousChapter() {
        val currentChapter = _chapterIndex.value
        if (currentChapter <= 0) return
        val prevPage = _globalPages.value.indexOfFirst {
            it.chapterIndex == currentChapter - 1 && it.pageInChapter == 0
        }
        if (prevPage >= 0) {
            jumpToGlobalPage(prevPage)
        } else {
            // 未预加载：走章节切换兜底
            jumpToChapter(currentChapter - 1)
        }
    }

    /**
     * 跳转到指定章节的第一页。
     * 供目录（TOC）界面 / 底栏"上一章/下一章"按钮使用。
     *
     * 如果目标章节已被预加载（在 globalPages 中），直接跳转到对应全局页码；
     * 否则进入"未预加载分支"：仅切换章节状态，等 WebView 加载完毕回调
     * onPageCountReady 时，由位置恢复机制把 currentGlobalPage 设到新章节首页。
     *
     * P1-NEW-2 修复（关键）：
     * 旧实现在未预加载分支里立刻把 `_currentGlobalPage.value = 0`，但此时
     * globalPages 仍保留旧章节的页 —— 0 这个索引指向"旧章节第一页"而非
     * "目标章节第一页"。UI 层 collectAsState 读到这个值后，ViewPager2 会先
     * 跳到全局 page=0，用户感知为"按下下一章却被踢回第一章第一页"。
     *
     * 现在改为：
     * 1) 不写 `_currentGlobalPage`（保留旧值），让 UI 在此一帧不发生位置突变
     * 2) 设置 `pendingPositionRestore = true` + `pendingPageInChapter = 0`，
     *    表示"等目标章节的 onPageCountReady 回调到达后再恢复到该章节第 0 页"
     * 3) onPageCountReady 检测到 `chapterIndex == _chapterIndex.value` 时，
     *    重建 globalPages 之后会查 findGlobalPageByPageInChapter(target, 0)，
     *    把 `_currentGlobalPage` 精确设到目标章节首页的全局索引
     *
     * 与 updateSettings 的协作：
     * - updateSettings 也使用同样的状态机：清空 globalPages → 标记 pending →
     *   等 onPageCountReady 恢复。本修复让 jumpToChapter 的未预加载分支
     *   遵循同一契约，避免两个路径行为不一致。
     * - 若两者并发（用户在 settings 面板里调字号的同时切章），
     *   updateSettings 会把 pendingPageInChapter 重置为"当前章内页码"，
     *   随后用户调 jumpToChapter 会把它覆写为 0（章首）。最终结果以
     *   最后一次调用为准，符合"最近用户意图优先"的预期。
     *
     * 注意：onPageCountReady 触发位置恢复的前提是新章节真的有页可定位
     * （pageCount >= 1）。loadChapterHtmlPath 内部会触发 WebView 加载 →
     * AndroidBridge 回调 onPageCountReady。若中途加载失败（如 P1-2 短路
     * 返回 onPageCountReady(_, 1)），状态机仍能完成位置恢复（落到该章
     * 唯一一页的 globalIndex）。
     *
     * @param chapterIndex 目标章节的 spine 索引（0-based）
     */
    fun jumpToChapter(chapterIndex: Int) {
        val metadata = bookMetadata ?: return
        // 章节索引范围校验
        if (chapterIndex < 0 || chapterIndex >= metadata.spine.size) return
        // 在全局页面列表中查找目标章节的第一页
        val targetPage = _globalPages.value.indexOfFirst {
            it.chapterIndex == chapterIndex && it.pageInChapter == 0
        }
        if (targetPage >= 0) {
            // 章节已预加载，直接跳转
            jumpToGlobalPage(targetPage)
        } else {
            // 章节未预加载分支：仅切换章节元数据 + 标记 pending，
            // 真正的全局页码定位推迟到 onPageCountReady 回调到达后。
            // 保存当前阅读位置（在覆盖 _chapterIndex 之前）
            savePageInChapter(_chapterIndex.value, getCurrentPageInChapter())
            // v4：跨章节自动退出搜索模式，与 syncChapterState 行为一致
            exitFindMode()
            _chapterIndex.value = chapterIndex
            _currentPageCount.value = _chapterPageCounts.value[chapterIndex] ?: 0
            // P1-NEW-2：不再写 `_currentGlobalPage.value = 0`，避免把 UI
            // 错误地带回到旧 globalPages[0]（即旧章节第一页）。
            // 改为标记"等 onPageCountReady 把页码恢复到本章首页"。
            pendingPositionRestore = true
            pendingPageInChapter = 0
            viewModelScope.launch {
                try {
                    loadChapterHtmlPath(chapterIndex)
                    repository.updateReadingProgress(bookId, chapterIndex)
                } catch (e: CancellationException) {
                    // P1-v6-3：协程取消必须重抛
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "加载章节失败"
                }
            }
        }
    }

    /**
     * 跳转到指定章节但保留搜索状态（P2-v6-3 修复）。
     *
     * 与 [jumpToChapter] 的区别：不调用 [exitFindMode]，避免在同一调用栈内
     * "先清再恢复"导致 StateFlow 多次发射中间空值（_findQuery 从 query → "" → query），
     * 造成搜索栏 UI 闪烁。
     *
     * 使用场景：[onBookSearchResultClicked] 已通过 [internalSetSearchMode] 统一管理
     * 搜索状态（取消旧 bookSearchJob + 保留 query + 清空结果），不需要 jumpToChapter
     * 再调一次 exitFindMode 清空搜索状态。
     *
     * 实现逻辑与 [jumpToChapter] 完全对称，仅省略 exitFindMode 调用：
     * - 已预加载分支：直接更新 _currentGlobalPage + _chapterIndex + 启动 HTML 加载
     * - 未预加载分支：标记 pendingPositionRestore，等 onPageCountReady 恢复位置
     *
     * @param chapterIndex 目标章节的 spine 索引（0-based）
     */
    private fun jumpToChapterPreservingFindState(chapterIndex: Int) {
        val metadata = bookMetadata ?: return
        if (chapterIndex < 0 || chapterIndex >= metadata.spine.size) return
        val targetPage = _globalPages.value.indexOfFirst {
            it.chapterIndex == chapterIndex && it.pageInChapter == 0
        }
        if (targetPage >= 0) {
            // 章节已预加载：直接更新全局页码和章节状态
            // 不走 jumpToGlobalPage → syncChapterState 路径，因为 syncChapterState
            // 内部也会调 exitFindMode，与本方法"保留搜索状态"的语义冲突
            val pageInfo = _globalPages.value[targetPage]
            if (pageInfo.chapterIndex != _chapterIndex.value) {
                savePageInChapter(_chapterIndex.value, getCurrentPageInChapter())
                // 不调 exitFindMode —— 搜索状态已由 internalSetSearchMode 管理
                _chapterIndex.value = pageInfo.chapterIndex
                _currentPageCount.value = _chapterPageCounts.value[pageInfo.chapterIndex] ?: 0
                viewModelScope.launch {
                    try {
                        loadChapterHtmlPath(pageInfo.chapterIndex)
                        repository.updateReadingProgress(bookId, pageInfo.chapterIndex)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _errorMessage.value = "加载章节失败"
                    }
                }
            }
            _currentGlobalPage.value = targetPage
        } else {
            // 章节未预加载分支：与 jumpToChapter 相同，但跳过 exitFindMode
            savePageInChapter(_chapterIndex.value, getCurrentPageInChapter())
            // 不调 exitFindMode —— 搜索状态已由 internalSetSearchMode 管理
            _chapterIndex.value = chapterIndex
            _currentPageCount.value = _chapterPageCounts.value[chapterIndex] ?: 0
            pendingPositionRestore = true
            pendingPageInChapter = 0
            viewModelScope.launch {
                try {
                    loadChapterHtmlPath(chapterIndex)
                    repository.updateReadingProgress(bookId, chapterIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errorMessage.value = "加载章节失败"
                }
            }
        }
    }
    /**
     * 预加载相邻章节的页数。
     * 后台预加载前后各 1 章，创建临时 WebView 计算页数后销毁。
     *
     * 为避免持有 Activity Context 导致泄漏（P0-2），内部统一使用 application context；
     * 调用方传入的 context 参数被忽略，仅保留以维持二进制兼容。
     */
    fun preloadAdjacentChapters(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
        val metadata = bookMetadata ?: return
        val currentIdx = _chapterIndex.value
        val counts = _chapterPageCounts.value
        val chaptersToPreload = mutableListOf<Int>()
        if (currentIdx > 0 && !counts.containsKey(currentIdx - 1)) {
            chaptersToPreload.add(currentIdx - 1)
        }
        if (currentIdx < metadata.spine.size - 1 && !counts.containsKey(currentIdx + 1)) {
            chaptersToPreload.add(currentIdx + 1)
        }
        viewModelScope.launch {
            for (chIdx in chaptersToPreload) {
                try {
                    val htmlFile = withContext(Dispatchers.IO) {
                        repository.getChapterHtmlFile(bookId, chIdx)
                    }
                    // P1-1：把 readText 也下沉到 IO，避免主线程 I/O
                    val htmlContent = withContext(Dispatchers.IO) {
                        runCatching { htmlFile.readText(Charsets.UTF_8) }.getOrNull()
                    }
                    if (htmlContent == null) continue
                    withContext(Dispatchers.Main) {
                        preloadChapterPageCount(chIdx, htmlContent)
                    }
                } catch (e: CancellationException) {
                    // P1-v6-3：预加载循环中协程被取消（ViewModel 销毁）也必须重抛，
                    // 不能继续 loop 让取消信号丢失
                    throw e
                } catch (e: Exception) {
                    // 预加载失败静默忽略，不影响主流程
                }
            }
        }
    }

    /**
     * 创建临时 WebView 预加载指定章节的页数。
     * 加载完成后在回调中销毁 WebView。
     *
     * 改进点：
     * - P0-2：使用 application context，不持有 Activity 引用
     * - B6：增加 5 秒超时 fallback，超时后强制销毁 WebView 防止泄漏
     */
    private fun preloadChapterPageCount(chapterIndex: Int, htmlContent: String) {
        val factory = ChapterWebViewFactory(application)
        val webView = factory.create()
        val book = _book.value ?: run { webView.destroy(); return }
        val metadata = bookMetadata ?: run { webView.destroy(); return }
        val opfDir = metadata.opfDir
        val bookDir = book.bookDirPath
        if (bookDir.isEmpty()) {
            webView.destroy()
            return
        }
        val settings = _readingSettings.value
        // 标记防止超时和正常回调重复销毁同一个 WebView
        var disposed = false
        val timeoutRunnable = Runnable {
            if (!disposed) {
                disposed = true
                try { webView.destroy() } catch (_: Throwable) {}
            }
        }
        // B6：5 秒超时 fallback，到期后无条件销毁 WebView
        mainHandler.postDelayed(timeoutRunnable, 5_000L)
        factory.loadHtml(
            webView = webView,
            htmlContent = htmlContent,
            bookDirPath = bookDir,
            opfDirRelative = opfDir,
            readingSettings = settings,
        ) { pageCount ->
            mainHandler.post {
                if (!disposed) {
                    disposed = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    val newCounts = _chapterPageCounts.value.toMutableMap()
                    newCounts[chapterIndex] = pageCount
                    _chapterPageCounts.value = newCounts
                    rebuildGlobalPages()
                    try { webView.destroy() } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * 更新阅读设置，清空页数缓存触发重新分页。
     *
     * B2 改进：清空 globalPages 的同时同步重置 _currentGlobalPage 为 0，
     * 避免重新分页期间 currentGlobalPage 指向超出 size=0 的旧值。
     * pendingPositionRestore + pendingPageInChapter 仍会在 onPageCountReady
     * 触发时把页码恢复到目标位置。
     */
    fun updateSettings(newSettings: ReadingSettings) {
        val currentPageInChapter = getCurrentPageInChapter()
        // v4：设置变更会重建所有 WebView，旧 controller 失效，搜索高亮也会一并消失，
        // 这里同步退出搜索模式让 UI 状态与 DOM 状态保持一致
        exitFindMode()
        _readingSettings.value = newSettings
        settingsManager.save(newSettings)
        _chapterPageCounts.value = emptyMap()
        _globalPages.value = emptyList()
        _currentPageCount.value = 0
        _currentGlobalPage.value = 0
        pendingPositionRestore = true
        pendingPageInChapter = currentPageInChapter
    }

    /** 获取当前全局页码对应的章内页码 */
    private fun getCurrentPageInChapter(): Int {
        val pages = _globalPages.value
        val globalIdx = _currentGlobalPage.value
        if (globalIdx < 0 || globalIdx >= pages.size) return 0
        return pages[globalIdx].pageInChapter
    }

    /**
     * 保存指定章节的章内页码到 SharedPreferences（带 debounce 节流）。
     *
     * C 项：每次翻页都调用会产生大量磁盘 I/O，故采用 100ms debounce：
     * - 把待写入键值记录在内存 pendingPageWrites
     * - 启动/重排一个延迟 100ms 的 Job，到期才统一 apply()
     * - 100ms 内的连续调用只会保留最后一个值
     *
     * onCleared 会强制 flush 待写入数据。
     */
    private fun savePageInChapter(chapterIndex: Int, pageInChapter: Int) {
        val key = "page_ch_${bookId}_${chapterIndex}"
        synchronized(pendingPageWrites) {
            pendingPageWrites[key] = pageInChapter
        }
        // 取消上一次延时写入，重排一个新的延时
        savePageJob?.cancel()
        savePageJob = viewModelScope.launch {
            delay(100L)
            flushPendingPageWrites()
        }
    }

    /** 立即把待写入的章内页码全部 apply 到 SharedPreferences */
    private fun flushPendingPageWrites() {
        val snapshot: Map<String, Int> = synchronized(pendingPageWrites) {
            if (pendingPageWrites.isEmpty()) return
            val copy = HashMap(pendingPageWrites)
            pendingPageWrites.clear()
            copy
        }
        val editor = pagePrefs.edit()
        for ((k, v) in snapshot) editor.putInt(k, v)
        editor.apply()
    }

    /** 从 SharedPreferences 读取指定章节的章内页码 */
    private fun getPageInChapter(chapterIndex: Int): Int {
        val key = "page_ch_${bookId}_${chapterIndex}"
        // 若 debounce 队列里有更新但尚未 apply，应优先读未提交的最新值
        synchronized(pendingPageWrites) {
            pendingPageWrites[key]?.let { return it }
        }
        return pagePrefs.getInt(key, 0)
    }

    /** 书籍元数据的公开访问器，供 UI 层使用 */
    val bookMetadataPublic: BookMetadata?
        get() = bookMetadata

    /**
     * 获取指定章节的 HTML 文件路径。
     * 供 PagedChapterAdapter 使用，异步从 Repository 获取。
     *
     * @param chapterIndex 章节索引
     * @param onResult 获取结果的回调，在主线程调用
     */
    fun getChapterHtmlPath(chapterIndex: Int, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val htmlFile = repository.getChapterHtmlFile(bookId, chapterIndex)
                onResult(htmlFile.absolutePath)
            } catch (e: CancellationException) {
                // P1-v6-3：取消时不调 onResult（调用方持有的 callback 也会随 ViewModel 销毁失效），
                // 必须重抛保持结构化并发
                throw e
            } catch (e: Exception) {
                onResult(null)
            }
        }
    }

    /** 显示设置面板 */
    fun showSettings() {
        _showSettings.value = true
    }

    /** 隐藏设置面板 */
    fun hideSettings() {
        _showSettings.value = false
    }

    /** 清除错误信息 */
    fun clearError() {
        _errorMessage.value = null
    }

    // ====================== v4 章内搜索（find-in-page）方法 ======================

    /**
     * 注入或更新当前章节的 FindInPageController。
     *
     * 由 [com.example.read.ui.reader.ReaderScreen] 在章节 HTML 加载完毕后调用，传入
     * 当前可见 WebView 包装而成的 controller。每次章节切换都会重新注入。
     *
     * 调用时机：onChapterHtmlLoaded（PagedChapterAdapter 回调）或 ViewPager2 OnPageChange
     * 的章节切换。前者保证 JS 已注入完毕，是更稳妥的注入点。
     *
     * @param controller 当前章节 WebView 对应的 controller；传 null 表示当前没有可用 WebView
     */
    fun attachFindController(controller: FindInPageController?) {
        // 替换前先清理旧 controller 在旧 WebView 上的高亮，避免回到老 WebView 时残留状态
        // （webView 实例由 LRU 缓存持有，可能复用；显式 clear 让状态机一致）
        currentFindController?.clear()
        currentFindController = controller
        // 章节切换后若用户仍处于 find 模式，重发当前 query 让新章节也高亮
        if (_findActive.value && _findQuery.value.isNotEmpty()) {
            controller?.find(_findQuery.value) { result ->
                _findCount.value = result.count
                _findCurrent.value = result.index
                // 章节内 find 重发：仅同步 count / index，不主动驱动 ViewPager2 翻页
                // （用户刚切到该章，跳走会突兀；按下"下一个"才翻页）
            }
        }
        // v5：处理"全书搜索结果点击 → 跨章跳转 → 章节加载完成 → 在新章定位关键词"流程。
        // 全书搜索点击 onBookSearchResultClicked 时仅记录 pendingFindAfterJump（query），
        // 此处 controller 就绪后才真正触发 find；JS 端会自动选中第 0 个匹配并 scrollIntoView。
        val pending = pendingFindAfterJump
        if (pending != null && controller != null) {
            pendingFindAfterJump = null
            // 进入章内搜索模式，让用户能看到搜索栏 + 计数
            _searchMode.value = SearchMode.InChapter
            _findActive.value = true
            _findQuery.value = pending
            controller.find(pending) { result ->
                _findCount.value = result.count
                _findCurrent.value = result.index
                // P1-v6-2：跨章跳转后，find 的 FindResult 已包含首个匹配的 pageInChapter，
                // 直接驱动 ViewPager2 翻到匹配所在的章内页。这样用户从全书搜索结果点击
                // 跳转到新章节后能直接看到匹配项（即便它在章内第 5 页），不再停留在第 1 页。
                if (result.count > 0 && result.pageInChapter >= 0) {
                    onFindMatchLocated(result.pageInChapter)
                }
            }
        }
    }

    /**
     * 进入搜索模式：把顶栏切换为搜索栏。
     *
     * 不重置 query / count，允许用户重新打开搜索栏看到上次的关键词（直到章节切换或退出搜索）。
     */
    fun enterFindMode() {
        _findActive.value = true
    }

    /**
     * 退出搜索模式：关闭搜索栏并清理章内高亮。
     *
     * 调用场景：
     * - 用户点击关闭按钮
     * - 章节切换（[syncChapterState] / [jumpToChapter] 自动调用）
     * - 设置变更（[updateSettings] 自动调用）
     * - ViewModel 销毁（无需调用，WebView 整体销毁自然清理）
     */
    fun exitFindMode() {
        _findActive.value = false
        _findQuery.value = ""
        _findCount.value = 0
        _findCurrent.value = -1
        currentFindController?.clear()
    }

    /**
     * 更新搜索关键词，触发实时搜索。
     *
     * 行为：
     * - 空串：清理高亮，count = 0，current = -1
     * - 非空：调用 controller.find 异步取得匹配总数，更新 count 和 current
     *
     * 节流策略：本版本不做 debounce，靠 WebView 端 TreeWalker 自身的性能可接受
     * （EPUB 章节内容通常 < 10KB 文本，遍历耗时 < 50ms）。若未来章节体积变大可在此
     * 引入 viewModelScope.launch { delay(N); ... } 节流，但需注意 cancel 旧 job。
     *
     * @param query 用户输入的关键词
     */
    fun updateFindQuery(query: String) {
        _findQuery.value = query
        val controller = currentFindController
        if (controller == null) {
            // controller 尚未就绪（极少数情况下用户在 HTML 加载完毕前快速输入），
            // 仅更新 query；attachFindController 注入时会自动重发查询
            _findCount.value = 0
            _findCurrent.value = -1
            return
        }
        if (query.isEmpty()) {
            // 空字符串：清理高亮并显示 "0 / 0"
            controller.clear()
            _findCount.value = 0
            _findCurrent.value = -1
            return
        }
        controller.find(query) { result ->
            // P1-v6-2：升级为 FindResult，包含 count + index + pageInChapter
            _findCount.value = result.count
            // JS 端的 find() 在 count > 0 时已自动选中第 0 项（FindResult.index = 0），与这里同步
            _findCurrent.value = result.index
            // 章内搜索路径不主动驱动 ViewPager2：用户刚输完关键词在第 1 页，
            // 立刻跳走会突兀；按"下一个"才触发翻页（findNext）。
            // 跨章跳转的首次定位走 attachFindController 路径，会调 onFindMatchLocated。
        }
    }

    /**
     * 跳转到下一个匹配项。
     *
     * 无匹配或 controller 未就绪时静默 no-op。JS 端 navigate(1) 内部会循环（达到末尾后回到首位），
     * UI 上"x / y"会随之刷新。
     *
     * P1-v5-2：JS 返回值包含匹配项的章内页码（pageInChapter），这里把它转为全局页码
     * 并写入 _currentGlobalPage，让 ViewPager2 通过现有 collectAsState 观察者自动翻页。
     * 这样匹配项位于章内第 2+ 页时，用户不仅看到 mark 高亮，ViewPager2 也会同步翻到对应页。
     */
    fun findNext() {
        val controller = currentFindController ?: return
        if (_findCount.value == 0) return
        controller.next { result ->
            if (result.index >= 0) {
                _findCurrent.value = result.index
                onFindMatchLocated(result.pageInChapter)
            }
        }
    }

    /**
     * 跳转到上一个匹配项。
     *
     * P1-v5-2：同 findNext，回传 pageInChapter 后驱动 ViewPager2 翻页。
     */
    fun findPrev() {
        val controller = currentFindController ?: return
        if (_findCount.value == 0) return
        controller.prev { result ->
            if (result.index >= 0) {
                _findCurrent.value = result.index
                onFindMatchLocated(result.pageInChapter)
            }
        }
    }

    /**
     * 当搜索匹配项被定位到某章内页时的回调（P1-v5-2 新增）。
     *
     * 实施背景：v4 章内搜索的 JS 端 `target.scrollIntoView({block:'center'})` 仅在
     * WebView 内部的 scrollY 上滚动，不会触发 ViewPager2 切换 currentItem。
     * 因此匹配项位于章内第 2+ 页时，高亮虽然在 DOM 中存在，但用户的可见区域
     * 仍在第 1 页 —— 这是 REVIEW_REPORT_v5.md § 3.6 / P1-v5-2 的核心问题。
     *
     * 修复策略：
     * 1) JS 端 navigate(delta) 同时回传匹配项的 `pageInChapter`
     * 2) Controller 解析为 NavigateResult 后调用 ViewModel.onFindMatchLocated
     * 3) ViewModel 把 pageInChapter 转换为全局页码（findGlobalPageByPageInChapter），
     *    并写入 _currentGlobalPage —— ReaderScreen 的 collectAsState 观察者会在
     *    Compose update lambda 中调用 viewPager.setCurrentItem，自动翻页
     *
     * 注意：
     * - 仅在 next/prev 触发时才会回报（find 首次定位不回报，避免页面频繁切换）
     * - 仅当 pageInChapter >= 0 时才驱动翻页；JS 计算失败（如 viewport=0）会传 -1，
     *   此时静默忽略
     * - 仅当 pageInChapter 与当前章内页不同时才更新 _currentGlobalPage，避免无效重组
     *
     * @param pageInChapter 匹配项所在的章内页码（0-based），-1 表示未知 / 计算失败
     */
    private fun onFindMatchLocated(pageInChapter: Int) {
        if (pageInChapter < 0) return
        val currentChapter = _chapterIndex.value
        // 计算匹配项对应的全局页码
        val targetGlobal = findGlobalPageByPageInChapter(currentChapter, pageInChapter)
        if (targetGlobal < 0) return
        // 仅当目标与当前不同时才更新，避免 StateFlow 发射重复值触发无意义重组
        if (targetGlobal != _currentGlobalPage.value) {
            _currentGlobalPage.value = targetGlobal
        }
    }

    // ====================== v5 跨章节全书搜索方法 ======================

    /**
     * 切换搜索模式（章内 ↔ 全书）。
     *
     * 行为：
     * - 切到目标模式时，清空当前查询与结果（[_findQuery] / [_bookSearchResults]）
     * - 同步取消正在进行的全书搜索任务（避免旧查询的结果晚到刷脏 UI）
     * - 不清章内匹配高亮（[currentFindController.clear] 不调用）—— 切回章内模式时
     *   用户重新输入会触发新的 find，旧高亮自然被 clear 覆盖；切到全书模式时
     *   章内 DOM 仍保留旧 mark，但章内搜索栏的计数会被清零，UI 不再展示这些 mark
     *
     * P1-v6-1 配套：本方法是"进入指定模式 + 取消旧 bookSearchJob"的唯一权威入口。
     * onBookSearchResultClicked 等场景必须复用本方法（或下方 internalSetSearchMode），
     * 不允许直接给 `_searchMode.value` 赋值绕过取消逻辑。
     *
     * @param mode 目标模式
     */
    fun setSearchMode(mode: SearchMode) {
        internalSetSearchMode(mode, preserveQuery = false)
    }

    /**
     * 模式切换的内部统一入口（P1-v6-1 引入）。
     *
     * 与公开的 [setSearchMode] 区别：
     * - preserveQuery=false（默认对外行为）：清空 _findQuery / _findCount / _findCurrent / results
     * - preserveQuery=true ：保留 _findQuery（其他 find/result 状态仍重置），
     *   供 [onBookSearchResultClicked] 等"跨模式跳转但 query 复用"的场景使用，
     *   避免 StateFlow 多次发射中间空值造成 UI 闪烁
     *
     * 无论哪种调用，**始终** cancel `bookSearchJob` —— 这是修 P1-v6-1 的关键：
     * 即使是从全书模式跳到章内模式（onBookSearchResultClicked），仍可能有
     * 进行中的全书搜索任务，必须取消防止它把过期结果写回 _bookSearchResults。
     */
    private fun internalSetSearchMode(mode: SearchMode, preserveQuery: Boolean) {
        // 即使 mode 未变也要 cancel 旧任务（兼容 onBookSearchResultClicked 的"同模式切换"语义）
        // —— 用户从全书模式点击结果后仍是 InChapter，但 ViewModel 的旧 bookSearchJob 可能还在跑。
        bookSearchJob?.cancel()
        bookSearchJob = null
        // 早退：mode 真的没变且不需要清状态时静默返回
        if (_searchMode.value == mode && !preserveQuery) return
        _searchMode.value = mode
        // 默认重置 query 与 find 计数；preserveQuery=true 则保留 query 字面值
        if (!preserveQuery) {
            _findQuery.value = ""
        }
        _findCount.value = 0
        _findCurrent.value = -1
        _bookSearchResults.value = emptyList()
        _bookSearchInProgress.value = false
        // 章内 mark 也清理一次，避免用户切回章内模式时仍能看到旧关键词的高亮
        currentFindController?.clear()
    }

    /**
     * 全书搜索入口：根据 query 异步扫描整本书。
     *
     * 行为：
     * - query 为空或长度 < 2 时清空结果并退出（避免高频扫描）
     * - 启动新搜索任务前 cancel 旧任务（连续输入只跑最后一次）
     * - 进入"搜索中"状态 → 引擎扫描 → 完成后写入 [_bookSearchResults]
     * - 任何异常都不上抛 UI（设置空结果 + 退出 progress 状态）
     *
     * P2-v6-1 修复：stale 检查从"字面比较 _findQuery == query"改为
     * "比较 currentCoroutineContext()[Job] 是否仍是 bookSearchJob"。字面比较有脏写风险：
     * 用户输入 "abc" → 切 InChapter → 切回 WholeBook → 又输入 "abc"，
     * 旧任务延迟回执时 _findQuery 仍等于 "abc"，会误把旧结果写入；
     * Job 引用比较则不受字面值干扰，启动新 Job 后旧 Job 永远 stale。
     *
     * @param query 用户输入的关键词；调用方无需 trim，引擎按字面量匹配
     */
    fun searchWholeBook(query: String) {
        // 同步更新 findQuery 供 UI 输入框 + 后续 attachFindController 跨章定位时复用
        _findQuery.value = query
        // 取消上一次进行中的搜索：用户已切换关键词，旧结果无效
        bookSearchJob?.cancel()
        // 短查询不触发：1 字符过于宽泛，扫描所有章节性价比低
        if (query.isEmpty() || query.length < MIN_WHOLE_BOOK_QUERY_LENGTH) {
            _bookSearchResults.value = emptyList()
            _bookSearchInProgress.value = false
            return
        }
        // 启动新任务：进度态 → 调引擎 → 写结果
        val launched = viewModelScope.launch {
            _bookSearchInProgress.value = true
            try {
                // 引擎内部已切到 Dispatchers.IO，本协程仍在主线程（viewModelScope 默认 Main.immediate）
                val results = bookSearchEngine.search(bookId, query)
                // P2-v6-1：用"当前协程仍是最新 bookSearchJob"判 stale，比字面对比 query 更可靠
                if (coroutineContext[Job] === bookSearchJob) {
                    _bookSearchResults.value = results
                }
            } catch (e: CancellationException) {
                // 协程取消：不写入结果，让外层的新搜索接管
                throw e
            } catch (e: Exception) {
                // 引擎异常视为无结果；UI 仍然能正常退出 progress 态
                if (coroutineContext[Job] === bookSearchJob) {
                    _bookSearchResults.value = emptyList()
                }
            } finally {
                // 仅当本协程仍是最新 Job 时才把 progress 设为 false；
                // 否则会把"新启动的搜索"也错误地标记为"已完成"
                if (coroutineContext[Job] === bookSearchJob) {
                    _bookSearchInProgress.value = false
                }
            }
        }
        bookSearchJob = launched
    }

    /**
     * 全书搜索结果项被点击：跳转到对应章节并在新章节自动定位关键词。
     *
     * 实现流程（P1-v6-1 + P1-v6-2 + P2-v6-3 修复后）：
     * 1) 记录 pendingFindAfterJump = 当前查询关键词（供 attachFindController 消费）
     * 2) **走 internalSetSearchMode(InChapter, preserveQuery=true)** 统一切换模式：
     *    - 取消正在进行的 bookSearchJob（修 P1-v6-1：避免旧任务脏写 _bookSearchResults）
     *    - 清空 _bookSearchResults / _bookSearchInProgress
     *    - 保留 _findQuery（query 在跳转后由 attachFindController 复用，
     *      不能像默认 setSearchMode 那样清掉，否则跨章 find 拿不到关键词）
     * 3) 调 [jumpToChapterPreservingFindState] 切到目标章节（P2-v6-3：不调 exitFindMode，
     *    避免 _findQuery / _findState 经历清空-恢复的中间态发射）
     * 4) ReaderScreen 在 onChapterHtmlLoaded 把新 controller 通过 attachFindController
     *    注入；此时消费 pendingFindAfterJump 触发 controller.find；
     *    P1-v6-2 修复后 controller.find 返回 FindResult 包含 pageInChapter，
     *    attachFindController 会调 onFindMatchLocated 驱动 ViewPager2 翻到匹配所在的
     *    章内页 —— 全书搜索 UX 至此闭环（不再停留在新章第 1 页）
     *
     * 注意：点击结果不直接关闭 ModalBottomSheet —— internalSetSearchMode 切到 InChapter
     * 已隐式让 sheet 不再渲染（ReaderScreen 的可见性条件是 searchMode == WholeBook）。
     *
     * @param result 被点击的结果
     */
    fun onBookSearchResultClicked(result: SearchResult) {
        val query = _findQuery.value
        // 记录待跳转后执行的查询，由 attachFindController 消费
        pendingFindAfterJump = query
        // P1-v6-1：走 internalSetSearchMode 统一路径，取消 bookSearchJob 避免脏写 _bookSearchResults。
        // preserveQuery=true 让 _findQuery 在模式切换中保持原值，跨章跳转后 controller.find 仍能用。
        internalSetSearchMode(SearchMode.InChapter, preserveQuery = true)
        // 关键词输入框保持显示该 query
        _findActive.value = true
        // P2-v6-3：使用 jumpToChapterPreservingFindState 替代 jumpToChapter，
        // 避免 exitFindMode 先清空 _findQuery / _findActive 再由外层恢复的"先清再恢复"反模式。
        // 旧路径：jumpToChapter → exitFindMode → _findQuery="" + _findActive=false
        //         → 外层 _findQuery=query + _findActive=true（StateFlow 发射 3 次中间态）
        // 新路径：jumpToChapterPreservingFindState 不调 exitFindMode，搜索状态由
        //         internalSetSearchMode 已统一管理（cancel bookSearchJob + preserveQuery），
        //         _findQuery 和 _findActive 保持原值不经历清空-恢复的中间态。
        jumpToChapterPreservingFindState(result.chapterIndex)
        // pendingFindAfterJump 再次确认（exitFindMode 不会清它，但保持显式赋值便于代码审查）
        pendingFindAfterJump = query
    }

    companion object {
        /**
         * 全书搜索触发的最小关键词长度（P3-v6-1 引入常量）。
         *
         * 短于此长度的 query 不触发搜索：1 字符过于宽泛，会让 Engine 扫描整本书
         * 拿到上千条命中，UI 渲染压力大且对用户无价值。
         */
        private const val MIN_WHOLE_BOOK_QUERY_LENGTH = 2
    }

    // ====================== v6 书签方法 ======================

    /**
     * 给当前阅读位置加书签。
     *
     * 行为：
     * - 读取当前 chapterIndex + pageInChapter，调 [BookRepository.addBookmark]
     * - createdAt 由 Repository 用 System.currentTimeMillis() 填充
     * - 写入成功后 books 表的 Flow 会自动发射新书签列表（UI 在 Sheet 中能立即看到）
     * - 失败时把错误消息写入 [_errorMessage]（章节切换的取消信号则正常重抛）
     */
    fun addCurrentPageBookmark() {
        val currentChapter = _chapterIndex.value
        val currentPageInChapter = getCurrentPageInChapter()
        viewModelScope.launch {
            try {
                repository.addBookmark(
                    bookId = bookId,
                    chapterIndex = currentChapter,
                    pageInChapter = currentPageInChapter,
                    note = null,
                )
            } catch (e: CancellationException) {
                // P1-v6-3：协程取消必须重抛
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "添加书签失败"
            }
        }
    }

    /**
     * 删除指定书签。
     *
     * 用户在书签 Sheet 上长按 / 右滑删除时调用。
     * 删除后 books_marks 表 Flow 自动发射新列表，UI 自动更新。
     */
    fun removeBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            try {
                repository.removeBookmark(bookmarkId)
            } catch (e: CancellationException) {
                // P1-v6-3：协程取消必须重抛
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "删除书签失败"
            }
        }
    }

    /**
     * 跳转到指定书签的位置。
     *
     * 实现流程：
     * 1) 调 [jumpToChapter]：切到书签所在章节（同时设 pendingPositionRestore = true，
     *    pendingPageInChapter = 0，等 onPageCountReady 把页码恢复到该章节首页）
     * 2) **覆盖** pendingPageInChapter 为书签真实的 pageInChapter，让 onPageCountReady
     *    把页码恢复到书签所在页（而不是该章首页）
     *
     * 这样无论书签所在的页是该章第 1 页还是第 N 页，都能精确翻到对应章内页。
     *
     * @param bookmark 要跳转的书签
     */
    fun jumpToBookmark(bookmark: Bookmark) {
        // jumpToChapter 内部会重置 pendingPageInChapter = 0，这里在调用后覆写
        jumpToChapter(bookmark.chapterIndex)
        pendingPositionRestore = true
        pendingPageInChapter = bookmark.pageInChapter
    }

    /**
     * ViewModel 销毁时保存当前阅读进度。
     * 将当前章内页码保存到 SharedPreferences，并强制 flush 待写入数据。
     *
     * 注意：仅当 loadBook 成功完成后才覆盖进度，避免加载失败时把
     * 0 写入覆盖原有进度（B 改进）。
     */
    override fun onCleared() {
        super.onCleared()
        // v4：清理对 WebView 的引用，避免外部仍持有 ViewModel 时长期挂着 WebView。
        // exitFindMode 内部会调用 controller.clear()，但 WebView 此刻可能已被 ReaderScreen
        // 的 DisposableEffect 销毁，clear 的 evaluateJavascript 是 no-op，安全。
        currentFindController = null
        if (loadSucceeded) {
            // 同步写入当前章内页码，绕过 debounce 直接落盘
            val key = "page_ch_${bookId}_${_chapterIndex.value}"
            synchronized(pendingPageWrites) {
                pendingPageWrites[key] = getCurrentPageInChapter()
            }
            savePageJob?.cancel()
            // onCleared 中协程无法保证完成，用同步 apply 直接写
            flushPendingPageWrites()
        }
    }
}
