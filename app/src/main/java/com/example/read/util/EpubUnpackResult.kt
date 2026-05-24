package com.example.read.util

import kotlinx.serialization.Serializable
import java.io.File

/**
 * EPUB 解包结果，包含解包后的目录路径、元数据和封面图片字节。
 *
 * unpack() 方法将 EPUB（ZIP 格式）解压到目标目录后返回此结果，
 * 上层调用方可直接使用 bookDir 访问解包后的文件，无需再次解压。
 *
 * @param bookDir 解包后的 EPUB 根目录
 * @param title 书籍标题，来自 OPF metadata
 * @param author 作者姓名，取第一个 dc:creator
 * @param coverBytes 封面图片原始字节（JPEG/PNG），可能为 null
 * @param metadata 书籍结构化元数据（OPF 目录、spine 阅读顺序等）
 */
data class EpubUnpackResult(
    val bookDir: File,
    val title: String,
    val author: String,
    val coverBytes: ByteArray?,
    val metadata: BookMetadata,
)

/**
 * 书籍结构化元数据，从 OPF 文件解析而来。
 *
 * 序列化为 JSON 后存储在解包目录的 metadata.json 文件中，
 * 后续读取章节时通过 spine 列表确定阅读顺序和文件路径。
 *
 * @param title 书籍标题
 * @param author 作者姓名
 * @param opfDir OPF 文件所在的相对目录路径（相对于 bookDir）
 * @param spine 按阅读顺序排列的章节列表
 */
@Serializable
data class BookMetadata(
    val title: String,
    val author: String,
    val opfDir: String,
    val spine: List<SpineItem>,
    /** 目录条目列表，从 NCX 文件解析而来，默认空列表兼容旧版 metadata.json */
    val tocItems: List<TocItem> = emptyList(),
)

/**
 * spine 中的单个章节条目，对应 OPF manifest 中的一个资源。
 *
 * 用于在阅读时定位 HTML 文件：最终路径为 bookDir/opfDir/href。
 *
 * @param id manifest 中的资源 ID
 * @param href 资源文件相对于 opfDir 的路径
 * @param title 章节标题（从 HTML <title> 标签提取，可能为空）
 * @param mediaType MIME 类型（如 application/xhtml+xml）
 */
@Serializable
data class SpineItem(
    val id: String,
    val href: String,
    val title: String,
    val mediaType: String,
)

/**
 * 目录条目，对应 NCX 文件中的一个 navPoint 节点。
 *
 * 用于在阅读器中展示章节目录，用户点击后可跳转到对应章节。
 *
 * @param title 目录条目标题，来自 NCX 的 <text> 元素
 * @param chapterIndex 对应 spine 的索引（0-based），用于定位章节
 * @param level 目录层级（0=一级目录，1=二级目录），支持多级目录展示
 */
@Serializable
data class TocItem(
    val title: String,
    val chapterIndex: Int,
    val level: Int,
)
