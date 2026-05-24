package com.example.read.ui.reader

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.read.domain.model.Chapter

/**
 * 文本分页工具，将章节的长文本按照屏幕尺寸分割为多页。
 *
 * 分页算法：
 * 1. 将文本按段落分割（以换行符为界）
 * 2. 对每个段落，使用 StaticLayout 精确计算在当前宽度下的实际行数
 * 3. 逐段落填入当前页，当页面行数达到上限时开始新的一页
 * 4. 如果单个段落超过一页容量，按行拆分到多页
 *
 * 使用 StaticLayout 而非简单字符计数的原因：
 * - CJK 字符和拉丁字符宽度差异大，无法用统一的字符数估算
 * - StaticLayout 考虑了 Paint 的字体、字号、字间距等所有排版参数
 * - 能正确处理自动换行（word wrap）的场景
 *
 * @param settings 当前阅读设置，包含字号、行高、字体等参数
 * @param density 屏幕密度，用于将 sp 单位转换为 px 单位
 */
class TextSplitter(private val settings: ReadingSettings, private val density: Float = 2.0f) {

    /** 文本测量画笔，配置与阅读器一致的字体参数 */
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // 使用与 TextView.setTextSize() 相同的 sp 转 px 公式：sp * density
        // 这样 TextSplitter 的行数计算与 TextView 的实际渲染一致
        textSize = settings.fontSize * density
        typeface = settings.getTypeface()
        letterSpacing = 0.005f // 字间距，与阅读器一致
    }

    /** 行间距（px），由字号（px）乘以行高倍数计算得出 */
    private val lineSpacing = settings.fontSize * density * settings.lineHeightMultiplier

    /**
     * 将章节内容分割为适合指定尺寸的页面列表。
     *
     * @param chapter 要分割的章节数据
     * @param pageWidth 页面可用宽度（px）
     * @param pageHeight 页面可用高度（px）
     * @return 每页纯文本内容的列表
     */
    fun split(chapter: Chapter, pageWidth: Int, pageHeight: Int): List<String> {
        // 参数校验，防止无效尺寸导致异常
        if (pageWidth <= 0 || pageHeight <= 0) return listOf(chapter.content)

        // 计算当前尺寸下每页可容纳的最大行数
        val maxLinesPerPage = (pageHeight / lineSpacing).toInt()
        // 至少容纳一行，防止页面尺寸过小时无法分页
        if (maxLinesPerPage <= 0) return listOf(chapter.content)

        // 按段落分割文本，保留空行作为段落分隔
        val paragraphs = chapter.content.split("\n")

        // 分页结果列表
        val pages = mutableListOf<String>()
        // 当前页正在累积的文本行
        val currentPageLines = mutableListOf<String>()
        // 当前页已占用的行数
        var currentLineCount = 0

        /**
         * 将当前页的行列表保存为一页，并重置计数器。
         * 只在有实际内容时保存，避免产生空白页。
         */
        fun saveCurrentPage() {
            if (currentPageLines.isNotEmpty()) {
                pages.add(currentPageLines.joinToString("\n"))
                currentPageLines.clear()
                currentLineCount = 0
            }
        }

        // 遍历每个段落进行分页
        for (paragraph in paragraphs) {
            // 空行：作为段落分隔符，添加到当前页
            if (paragraph.isBlank()) {
                if (currentLineCount < maxLinesPerPage) {
                    currentPageLines.add("")
                    currentLineCount++
                } else {
                    // 当前页已满，保存并开始新页
                    saveCurrentPage()
                    currentPageLines.add("")
                    currentLineCount = 1
                }
                continue
            }

            // 计算该段落在此页面宽度下需要的行数
            val paragraphLineCount = measureLineCount(paragraph, pageWidth)

            // 段落不需要换页：直接添加到当前页
            if (currentLineCount + paragraphLineCount <= maxLinesPerPage) {
                currentPageLines.add(paragraph)
                currentLineCount += paragraphLineCount
            }
            // 段落需要跨页：按行拆分到多页
            else {
                // 先填满当前页的剩余空间
                val remainingLines = maxLinesPerPage - currentLineCount
                if (remainingLines > 0) {
                    val firstPart = extractLines(paragraph, pageWidth, 0, remainingLines)
                    if (firstPart.isNotBlank()) {
                        currentPageLines.add(firstPart)
                    }
                    saveCurrentPage()

                    // 剩余内容分配到后续页面
                    var startLine = remainingLines
                    while (startLine < paragraphLineCount) {
                        val endLine = minOf(startLine + maxLinesPerPage, paragraphLineCount)
                        val part = extractLines(paragraph, pageWidth, startLine, endLine)
                        if (part.isNotBlank()) {
                            currentPageLines.add(part)
                        }
                        currentLineCount = endLine - startLine
                        startLine = endLine

                        // 如果当前页已满，保存并继续
                        if (currentLineCount >= maxLinesPerPage) {
                            saveCurrentPage()
                        }
                    }
                } else {
                    // 当前页已无空间，直接保存并开始新页
                    saveCurrentPage()
                    // 将整个段落添加到新页（可能需要再次跨页）
                    currentPageLines.add(paragraph)
                    currentLineCount = paragraphLineCount
                }
            }
        }

        // 保存最后一页（如果有内容）
        saveCurrentPage()

        // 确保至少有一页（防止空白章节导致空列表）
        return pages.ifEmpty { listOf(chapter.content) }
    }

    /**
     * 使用 StaticLayout 测量文本在指定宽度下的行数。
     *
     * StaticLayout 是 Android 的文本排版引擎，能精确计算：
     * - 自动换行（word wrap）
     * - CJK 字符和拉丁字符的宽度差异
     * - 字间距、行间距等排版参数
     *
     * @param text 要测量的文本
     * @param width 可用宽度（px）
     * @return 文本占用的行数
     */
    private fun measureLineCount(text: String, width: Int): Int {
        // 空文本只占一行
        if (text.isBlank()) return 1

        val layout = StaticLayout.Builder.obtain(
            text, 0, text.length, textPaint, width
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f) // 行间距由外部分页逻辑控制
            .setIncludePad(false)   // 不包含额外的上下内边距
            .build()

        return layout.lineCount
    }

    /**
     * 从段落中提取指定行范围的文本。
     *
     * 用于将过长的段落拆分到多页：
     * - startLine: 起始行号（0-based）
     * - endLine: 结束行号（不含）
     *
     * 使用 StaticLayout.getLineStart() 和 getLineEnd() 精确定位每行的字符范围。
     *
     * @param text 源文本
     * @param width 可用宽度（px）
     * @param startLine 起始行号
     * @param endLine 结束行号（不含）
     * @return 指定行范围的文本字符串
     */
    private fun extractLines(text: String, width: Int, startLine: Int, endLine: Int): String {
        if (text.isBlank()) return text

        val layout = StaticLayout.Builder.obtain(
            text, 0, text.length, textPaint, width
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        // 防止行号越界
        val safeStartLine = startLine.coerceIn(0, layout.lineCount - 1)
        val safeEndLine = endLine.coerceIn(safeStartLine, layout.lineCount)

        // 提取指定行范围的起始和结束字符位置
        val startChar = layout.getLineStart(safeStartLine)
        val endChar = layout.getLineEnd(safeEndLine - 1)

        return text.substring(startChar, endChar).trimEnd()
    }
}
