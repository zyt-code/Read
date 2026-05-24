package com.example.read.ui.bookshelf

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.read.domain.model.Book
import com.example.read.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书架页面"占位卡片"的轻量模型（P1-NEW-1 修复引入）。
 *
 * 用途：当用户触发 SAF 导入时，Repository.prepareImport 会把一条
 * `bookDirPath = "PREPARING_<uuid>"` 的占位记录写入 Room，但 P1-1 修复后
 * `BookDao.getAllBooks()` 在 SQL 层把 PREPARING_* 过滤掉了 —— 书架的
 * LazyVerticalGrid 不会再为占位记录渲染 BookCard，原本承载"导入进度环 UI"
 * 的卡片实例消失，用户感知不到任何导入活动。
 *
 * 本数据类作为 ViewModel → UI 的纯内存通道：
 * - 仅在导入过程中存在（startImport 完成或失败后从列表中移除）
 * - 不与 Room 持久化关联（重启后没有活跃 IO 任务，无需恢复进度环）
 * - 与 importProgress 一一对应：bookId（即 prepareImport 返回的 placeholderId）
 *   既是进度 map 的 key，也是 PlaceholderBook 的 id
 *
 * @param id 占位记录在数据库中的主键（同时也是 importProgress 的 key）
 * @param titleHint 用户选择的文件名（可选），用于占位卡片显示"正在导入：xxx.epub"提示
 * @param progress 当前导入进度（0.0~1.0），等同于 importProgress[id]
 */
data class PlaceholderBook(
    val id: Long,
    val titleHint: String? = null,
    val progress: Float,
)

/**
 * 书架页面的 ViewModel，管理书籍列表和导入操作的状态。
 *
 * 状态管理策略：
 * - books: 通过 Room Flow 自动响应数据库变化，新书导入后 UI 自动更新
 * - placeholderBooks: 导入过程中的"占位卡片"列表（P1-NEW-1 修复）。
 *   因为 BookDao.getAllBooks() 在 SQL 层已过滤 PREPARING_* 记录（P1-1 引入），
 *   书架要继续展示导入进度环，需要一条独立的内存通道把占位卡片透传给 UI。
 * - importProgress: 各导入中书籍的进度（bookId -> 0.0~1.0），用于封面进度环
 * - errorMessage: 错误信息，UI 通过 Snackbar 展示后清除
 *
 * stateIn 将 Room 的冷 Flow 转为热 Flow，SharingStarted.WhileSubscribed(5000)
 * 表示在最后一个订阅者取消后 5 秒才停止收集，避免配置变更（如旋转屏幕）时重新查询数据库。
 */
@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val repository: BookRepository,
) : ViewModel() {

    /** 书籍列表，按最近阅读时间倒序排列，Room Flow 自动响应数据库变化 */
    val books: StateFlow<List<Book>> = repository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 应用启动时清理孤立的书籍记录（导入中途崩溃或迁移产生的残留数据）
        viewModelScope.launch {
            try {
                repository.cleanupOrphanedBooks()
            } catch (e: CancellationException) {
                // P1-v5-1：协程取消必须重抛，让结构化并发链路正常向上传播
                // CancellationException 不是业务错误，不应被 catch (Exception) 吞掉
                throw e
            } catch (e: Exception) {
                // 清理失败不影响正常功能，静默忽略
                Log.w("BookshelfViewModel", "清理孤立记录失败", e)
            }
        }
    }

    /** 正在导入的书籍进度，key 为 bookId，value 为 0.0~1.0 的进度值 */
    private val _importProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val importProgress: StateFlow<Map<Long, Float>> = _importProgress

    /**
     * 导入过程中的占位卡片列表（P1-NEW-1）。
     *
     * 因为 SQL 层已经把 `PREPARING_*` 占位记录从 books flow 中过滤掉，
     * UI 层无法再通过遍历 books 拿到任何承载导入进度的卡片实例。
     * 本 StateFlow 作为 ViewModel → BookshelfScreen 的独立通道，
     * 让 BookshelfScreen 在 LazyVerticalGrid 顶部追加这些占位卡片，
     * 用户可以看到"导入中"的进度环并感知导入活动。
     *
     * 生命周期：
     * - prepareImport 成功获得 placeholderId 后，立即 add（progress=0.05f）
     * - startImport 进度回调时，update 同一个 id 的 progress
     * - startImport 成功或失败的 finally 块中 remove（成功时 books flow 会
     *   同步发射真实记录，无需 placeholder 兜底）
     */
    private val _placeholderBooks = MutableStateFlow<List<PlaceholderBook>>(emptyList())
    val placeholderBooks: StateFlow<List<PlaceholderBook>> = _placeholderBooks

    /** 错误信息，UI 层通过 Snackbar 展示后调用 clearError() 清除 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /**
     * 导入 EPUB 文件，带进度回调。
     * 两阶段导入：先快速提取元数据让书籍出现在书架上，再完整解包并更新进度。
     *
     * 错误恢复（B8）：
     * - prepareImport 成功但 startImport 抛异常时，Repository.startImport 内部
     *   已经清理过 sentinel 目录与占位记录。但若 prepareImport 之后到 startImport 之间
     *   被取消或意外抛错（如 OOM、进程被杀重启前的瞬间），占位记录会残留。
     *   本方法显式在 catch 中再做一次清理兜底：根据 bookId 查询占位记录并删除，
     *   保证书架不会出现 "PREPARING_xxx" 路径的幽灵记录。
     *
     * 占位卡片维护（P1-NEW-1）：
     * - 进入 try 块即向 _placeholderBooks 追加一条 PlaceholderBook（progress=0.05f）
     * - startImport 进度回调内同步更新 progress
     * - 无论成功还是失败，都在 finally 等价点把该 id 从 _placeholderBooks 中移除：
     *   成功 → books flow 会发射真实记录，占位让位；
     *   失败 → 占位记录已被 deleteBook 清除，UI 无需残留进度环。
     *
     * @param uri SAF 文件选择器返回的 EPUB 文件 URI
     * @param context Android Context，用于访问 ContentResolver
     */
    fun importBook(uri: Uri, context: Context) {
        viewModelScope.launch {
            _errorMessage.value = null
            var placeholderId: Long = -1L
            try {
                // 第一阶段：快速提取元数据 + 插入占位记录
                // prepareImport 在 IO 线程执行，完成后书籍立即出现在书架上
                placeholderId = repository.prepareImport(uri, context)

                // 第二阶段：完整解包，进度绑定到真实 bookId
                _importProgress.value = mapOf(placeholderId to 0.05f)
                // P1-NEW-1：占位卡片随 importProgress 一起出现，初始进度 0.05
                // 这样 UI 在 prepareImport 完成的瞬间就能看到进度环卡片
                _placeholderBooks.upsertPlaceholder(placeholderId) { existing ->
                    existing ?: PlaceholderBook(id = placeholderId, titleHint = null, progress = 0.05f)
                }
                repository.startImport(placeholderId, uri, context) { progress ->
                    _importProgress.value = mapOf(placeholderId to progress)
                    // 同步占位卡片进度：UI 上的 CircularProgressIndicator 跟随刷新
                    _placeholderBooks.upsertPlaceholder(placeholderId) { existing ->
                        existing?.copy(progress = progress)
                            ?: PlaceholderBook(id = placeholderId, titleHint = null, progress = progress)
                    }
                }
                // 导入完成，清除进度（封面已由 Room Flow 自动更新）
                _importProgress.value = emptyMap()
                // P1-NEW-1：成功路径下，真实 BookEntity 已被 startImport 写入并被 books flow 发射，
                // 此时移除占位避免书架短窗口同时出现"占位卡片 + 真实卡片"
                _placeholderBooks.removePlaceholder(placeholderId)
            } catch (e: CancellationException) {
                // P1-v5-1：协程取消（如 ViewModel 销毁、配置变更）必须重新抛出，
                // 让 viewModelScope 正常完成取消传播。Kotlin 协程约定：
                // CancellationException 是 Job 取消的协议信号，被 catch (Exception)
                // 吞掉会破坏结构化并发；下游 await/select 会陷入死锁，
                // 父 Job 也无法识别"子已取消"导致状态错乱。
                //
                // 这里在重抛前先快速清理 UI 上的占位卡片与进度环，避免取消瞬间
                // 残留进度环视觉（虽然 ViewModel 即将销毁，StateFlow 自然被 GC，
                // 但显式清理符合"取消应当像没发生过一样"的语义）。
                _importProgress.value = emptyMap()
                if (placeholderId > 0) {
                    _placeholderBooks.removePlaceholder(placeholderId)
                }
                throw e
            } catch (e: Exception) {
                _importProgress.value = emptyMap()
                // P1-NEW-1：失败路径同样移除占位卡片，避免遗留进度环
                if (placeholderId > 0) {
                    _placeholderBooks.removePlaceholder(placeholderId)
                }
                Log.e("BookshelfViewModel", "导入 EPUB 失败", e)
                // B8 兜底：占位记录已写入但 startImport 失败，显式触发删除。
                // startImport 内部已尝试过清理，此处幂等再清一次，规避边缘情况。
                if (placeholderId > 0) {
                    runCatching {
                        repository.getBookById(placeholderId)?.let { repository.deleteBook(it) }
                    }
                }
                _errorMessage.value = when (e) {
                    is IllegalArgumentException -> e.message ?: "EPUB 文件格式无效"
                    is java.io.IOException -> "文件读写失败，请检查存储空间是否充足"
                    is SecurityException -> "EPUB 文件结构异常，无法安全解压"
                    else -> "导入失败，请重试"
                }
            }
        }
    }

    /**
     * 内部工具：根据 id 找到对应的 PlaceholderBook 并执行 transform，
     * 若不存在则使用 transform(null) 的返回值新增（返回 null 表示不新增）。
     *
     * 实现上避免与 `kotlinx.coroutines.flow.update` 同名扩展冲突，故命名为 upsertPlaceholder。
     */
    private fun MutableStateFlow<List<PlaceholderBook>>.upsertPlaceholder(
        id: Long,
        transform: (PlaceholderBook?) -> PlaceholderBook?,
    ) {
        val current = this.value
        val existing = current.firstOrNull { it.id == id }
        val next = transform(existing) ?: return
        this.value = if (existing == null) {
            // 新增：追加在末尾，UI 渲染时占位条目整体位于真实 books 之前
            current + next
        } else {
            current.map { if (it.id == id) next else it }
        }
    }

    /** 内部工具：移除指定 id 的占位卡片（若不存在则空操作） */
    private fun MutableStateFlow<List<PlaceholderBook>>.removePlaceholder(id: Long) {
        val current = this.value
        if (current.none { it.id == id }) return
        this.value = current.filterNot { it.id == id }
    }

    /**
     * 删除书籍，同时清理文件系统中的 EPUB 和封面文件，以及数据库记录。
     * Room Flow 会自动触发 UI 更新，书籍从网格中消失。
     */
    fun deleteBook(book: Book) {
        viewModelScope.launch {
            try {
                repository.deleteBook(book)
            } catch (e: CancellationException) {
                // P1-v5-1：协程取消必须重抛，遵循 Kotlin 结构化并发约定
                throw e
            } catch (e: Exception) {
                // 不暴露内部路径，只显示友好提示
                _errorMessage.value = "删除失败，请重试"
            }
        }
    }

    /** 清除错误信息，通常在 Snackbar 显示后调用 */
    fun clearError() {
        _errorMessage.value = null
    }
}
