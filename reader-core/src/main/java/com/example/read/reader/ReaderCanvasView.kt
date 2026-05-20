package com.example.read.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max

class ReaderCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(DEFAULT_FONT_SIZE_SP)
    }
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(CHROME_FONT_SIZE_SP)
    }
    private var pageState = ReaderPageRenderState(
        text = "",
        foregroundColor = Color.BLACK,
        pageBackgroundColor = Color.WHITE,
    )

    @JvmOverloads
    fun renderPage(
        text: String,
        foregroundColor: Int,
        pageBackgroundColor: Int,
        chapterTitle: String? = null,
        progressLabel: String? = null,
        fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
        lineHeightMultiplier: Float = DEFAULT_LINE_HEIGHT_MULTIPLIER,
        paddingDp: Float = DEFAULT_PADDING_DP,
    ) {
        pageState = ReaderPageRenderState(
            text = text,
            foregroundColor = foregroundColor,
            pageBackgroundColor = pageBackgroundColor,
            chapterTitle = chapterTitle,
            progressLabel = progressLabel,
            fontSizeSp = fontSizeSp.coerceAtLeast(MIN_FONT_SIZE_SP),
            lineHeightMultiplier = lineHeightMultiplier.coerceAtLeast(MIN_LINE_HEIGHT_MULTIPLIER),
            paddingDp = paddingDp.coerceAtLeast(MIN_PADDING_DP),
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = pageState
        canvas.drawColor(state.pageBackgroundColor)

        val contentPadding = dpToPx(state.paddingDp)
        val chromeGap = dpToPx(CHROME_GAP_DP)
        val footerBottom = height - paddingBottom - contentPadding
        val headerTop = paddingTop + contentPadding
        val contentLeft = paddingLeft + contentPadding
        val contentRight = width - paddingRight - contentPadding
        val maxLineWidth = max(0f, contentRight - contentLeft)

        textPaint.textSize = spToPx(state.fontSizeSp)
        textPaint.color = state.foregroundColor
        chromePaint.color = state.foregroundColor.withAlpha(CHROME_ALPHA)
        chromePaint.textSize = spToPx(CHROME_FONT_SIZE_SP)

        val headerBaseline = drawHeader(canvas, state.chapterTitle, contentLeft, contentRight, headerTop)
        val footerBaseline = drawFooter(canvas, state.progressLabel, contentLeft, contentRight, footerBottom)
        val bodyTop = max(headerBaseline + chromeGap, headerTop) - textPaint.fontMetrics.ascent
        val bodyBottom = if (state.progressLabel.isNullOrBlank()) {
            height - paddingBottom - contentPadding
        } else {
            footerBaseline + chromePaint.fontMetrics.ascent - chromeGap
        }
        val lineHeight = textPaint.fontSpacing * state.lineHeightMultiplier
        val layout = buildReaderPageLayout(
            text = state.text,
            maxLineWidthPx = maxLineWidth,
            textPaint = textPaint,
        )

        var baseline = bodyTop
        layout.bodyLines.forEach { line ->
            if (baseline <= bodyBottom) {
                canvas.drawText(line, contentLeft, baseline, textPaint)
                baseline += lineHeight
            }
        }
    }

    private fun drawHeader(
        canvas: Canvas,
        chapterTitle: String?,
        left: Float,
        right: Float,
        top: Float,
    ): Float {
        if (chapterTitle.isNullOrBlank()) return top

        val title = chapterTitle.trim()
        val availableWidth = max(0f, right - left)
        val count = chromePaint.breakText(title, true, availableWidth, null)
        val visibleTitle = title.take(count.coerceAtLeast(0))
        val baseline = top - chromePaint.fontMetrics.ascent
        canvas.drawText(visibleTitle, left, baseline, chromePaint)
        return baseline
    }

    private fun drawFooter(
        canvas: Canvas,
        progressLabel: String?,
        left: Float,
        right: Float,
        bottom: Float,
    ): Float {
        if (progressLabel.isNullOrBlank()) return bottom

        val label = progressLabel.trim()
        val availableWidth = max(0f, right - left)
        val count = chromePaint.breakText(label, true, availableWidth, null)
        val visibleLabel = label.take(count.coerceAtLeast(0))
        val labelWidth = chromePaint.measureText(visibleLabel)
        val baseline = bottom - chromePaint.fontMetrics.descent
        canvas.drawText(visibleLabel, max(left, right - labelWidth), baseline, chromePaint)
        return baseline
    }

    private fun spToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private fun dpToPx(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(this),
        Color.green(this),
        Color.blue(this),
    )

    private companion object {
        const val DEFAULT_FONT_SIZE_SP = 18f
        const val DEFAULT_LINE_HEIGHT_MULTIPLIER = 1.55f
        const val DEFAULT_PADDING_DP = 28f
        const val MIN_FONT_SIZE_SP = 8f
        const val MIN_LINE_HEIGHT_MULTIPLIER = 1f
        const val MIN_PADDING_DP = 0f
        const val CHROME_FONT_SIZE_SP = 12f
        const val CHROME_GAP_DP = 18f
        const val CHROME_ALPHA = 150
    }
}

data class ReaderPageRenderState(
    val text: String,
    val foregroundColor: Int,
    val pageBackgroundColor: Int,
    val chapterTitle: String? = null,
    val progressLabel: String? = null,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.55f,
    val paddingDp: Float = 28f,
)

data class ReaderPageLayout(
    val rawText: String,
    val bodyLines: List<String>,
)

fun buildReaderPageLayout(
    text: String,
    maxLineWidthPx: Float,
    textPaint: Paint?,
): ReaderPageLayout {
    if (text.isBlank() || maxLineWidthPx <= 0f) {
        return ReaderPageLayout(rawText = text, bodyLines = emptyList())
    }

    val lines = mutableListOf<String>()
    text.split('\n').forEach { paragraph ->
        if (paragraph.isBlank()) {
            lines += ""
        } else {
            appendWrappedParagraph(
                paragraph = paragraph.trim(),
                maxLineWidthPx = maxLineWidthPx,
                textPaint = textPaint,
                lines = lines,
            )
        }
    }
    return ReaderPageLayout(rawText = text, bodyLines = lines)
}

private fun appendWrappedParagraph(
    paragraph: String,
    maxLineWidthPx: Float,
    textPaint: Paint?,
    lines: MutableList<String>,
) {
    var start = 0
    while (start < paragraph.length) {
        val count = textPaint?.breakText(
            paragraph,
            start,
            paragraph.length,
            true,
            maxLineWidthPx,
            null,
        ) ?: fallbackBreakCount(paragraph, start, maxLineWidthPx)
        if (count <= 0) break

        val end = chooseReadableBreak(paragraph, start, start + count)
        lines += paragraph.substring(start, end).trimEnd()
        start = end
        while (start < paragraph.length && paragraph[start].isWhitespace()) {
            start += 1
        }
    }
}

private fun chooseReadableBreak(
    paragraph: String,
    start: Int,
    measuredEnd: Int,
): Int {
    val end = measuredEnd.coerceIn(start + 1, paragraph.length)
    if (end == paragraph.length || paragraph[end - 1].isWhitespace()) return end

    val boundary = paragraph.lastIndexOfAny(charArrayOf(' ', '\t'), startIndex = end - 1)
    return if (boundary > start) boundary + 1 else end
}

private fun fallbackBreakCount(
    paragraph: String,
    start: Int,
    maxLineWidthPx: Float,
): Int {
    val approximateCharWidthPx = 12f
    return max(1, (maxLineWidthPx / approximateCharWidthPx).toInt())
        .coerceAtMost(paragraph.length - start)
}
