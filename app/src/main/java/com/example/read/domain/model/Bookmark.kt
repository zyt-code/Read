package com.example.read.domain.model

/**
 * 书签领域模型（v6 feature: 书签）。
 *
 * 纯 Kotlin 数据类，不依赖 Room / Android。供 ViewModel 与 UI 层使用。
 * 与 [com.example.read.data.local.entity.BookmarkEntity] 字段一一对应；
 * 二者之间通过 `toDomain()` / `toEntity()` 扩展函数互转。
 *
 * 业务含义：
 * - 用户在阅读时主动点击"加书签"按钮，记录当前 (bookId, chapterIndex, pageInChapter)
 * - 后续可在"查看书签"面板列出该书所有书签，点击跳回对应位置
 * - 删除书籍时书签会随 CASCADE 自动清理
 *
 * @param id 数据库主键；新增时为 0，由 Room insert 后分配
 * @param bookId 所属书籍 ID（外键 → books.id）
 * @param chapterIndex 章节索引（0-based，spine 顺序）
 * @param pageInChapter 章内页码（0-based），跳转后 ReaderViewModel 用 pendingPageInChapter
 *   状态机精确恢复到这一页
 * @param note 用户备注；本期不实现编辑 UI，存 null
 * @param createdAt 创建时间戳（epoch millis）；用于按"最近添加"倒序排序
 *   及 UI 渲染"X 分钟前"相对时间
 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val pageInChapter: Int,
    val note: String? = null,
    val createdAt: Long,
)
