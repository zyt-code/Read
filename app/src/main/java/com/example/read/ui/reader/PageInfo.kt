package com.example.read.ui.reader

/**
 * 全局页面信息，表示阅读器中的一页。
 * 由章节索引和章内页码组成，用于构建跨章节的连续页面流。
 *
 * 设计说明：
 * - chapterIndex 对应 EPUB 中 spine 的阅读顺序（0-based）
 * - pageInChapter 表示该章节内 WebView 渲染后的页码（0-based）
 * - 两个字段组合唯一标识阅读器中的任意一页
 *
 * 使用场景：
 * - ViewPager2 的全局页面索引与章节/页码之间的映射
 * - 阅读进度的精确保存和恢复
 * - 跨章节翻页时的边界计算
 */
data class PageInfo(
    /** 章节索引（0-based），对应 spine 阅读顺序 */
    val chapterIndex: Int,
    /** 章内页码（0-based），由 WebView 分页计算得出 */
    val pageInChapter: Int,
)
