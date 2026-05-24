package com.example.read.domain.model

/**
 * 章节领域模型，表示书籍中的一个章节。
 *
 * 不持久化到数据库，每次打开章节时从已解包的目录中按需读取 HTML 文件。
 * content 是纯文本内容（从 HTML 转换而来），直接用于阅读器渲染。
 * htmlPath 是 HTML 文件在解包目录中的绝对路径，用于定位原始 HTML 文件。
 *
 * @param index 章节在书籍中的索引（0-based）
 * @param title 章节标题，来自 EPUB 的 TOC（目录）
 * @param content 章节的纯文本内容，由 Jsoup 从 HTML 转换而来
 */
data class Chapter(
    val index: Int,
    val title: String,
    val content: String,
    val htmlPath: String = "",
)
