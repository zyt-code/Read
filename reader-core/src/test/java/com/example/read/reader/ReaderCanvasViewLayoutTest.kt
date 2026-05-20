package com.example.read.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCanvasViewLayoutTest {

    @Test
    fun emptyTextProducesNoBodyLines() {
        val layout = buildReaderPageLayout(
            text = "",
            maxLineWidthPx = 240f,
            textPaint = null,
        )

        assertTrue(layout.bodyLines.isEmpty())
    }

    @Test
    fun mixedEnglishAndChineseTextWrapsIntoMultipleLines() {
        val layout = buildReaderPageLayout(
            text = "Reading 中文 mixed text for wrapping",
            maxLineWidthPx = 120f,
            textPaint = null,
        )

        assertTrue(layout.bodyLines.size > 1)
        assertEquals("Reading 中文 mixed text for wrapping", layout.rawText)
    }
}
