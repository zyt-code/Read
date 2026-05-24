package com.example.read.domain.model

/**
 * 书籍领域模型，表示应用中的一本书。
 *
 * 这是纯 Kotlin 数据类，不依赖任何 Android 框架或 Room 注解。
 * ViewModel 和 UI 层只使用这个模型，不直接接触 Room 的 BookEntity。
 *
 * 属性说明：
 * - id: 数据库自增主键，新增时为 0，插入后由 Room 分配
 * - coverPath: 封面图片在 app 内部存储中的绝对路径，null 表示 EPUB 无封面
 * - bookDirPath: EPUB 解包目录在 app 内部存储中的绝对路径
 * - totalChapters: 章节总数，用于阅读器显示进度和边界检查
 * - lastReadChapter: 上次阅读的章节索引（0-based），用于恢复阅读位置
 * - lastReadAt: 上次阅读的时间戳（epoch millis），用于书架按最近阅读排序
 */
data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val coverPath: String?,
    val bookDirPath: String,
    val totalChapters: Int,
    val lastReadChapter: Int = 0,
    val lastReadAt: Long = 0L,
)
