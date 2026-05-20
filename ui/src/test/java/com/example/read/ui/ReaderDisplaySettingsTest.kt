package com.example.read.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDisplaySettingsTest {

    @Test
    fun normalizedClampsReadingSettingsToReadableBounds() {
        val settings = ReaderDisplaySettings(
            fontSizeSp = 42f,
            lineHeightMultiplier = 0.8f,
            horizontalPaddingDp = 4f,
        ).normalized()

        assertEquals(24f, settings.fontSizeSp, 0.01f)
        assertEquals(1.35f, settings.lineHeightMultiplier, 0.01f)
        assertEquals(18f, settings.horizontalPaddingDp, 0.01f)
    }

    @Test
    fun readerProgressLabelCombinesPageAndPercent() {
        assertEquals("Page 1 of 1 · 12%", readerProgressLabel("Page 1 of 1", 0.124f))
    }

    @Test
    fun saverRestoresNormalizedSettings() {
        val restored = ReaderDisplaySettingsSaver.restore(listOf(14f, 2f, 60f))

        assertEquals(16f, restored?.fontSizeSp ?: 0f, 0.01f)
        assertEquals(1.75f, restored?.lineHeightMultiplier ?: 0f, 0.01f)
        assertEquals(36f, restored?.horizontalPaddingDp ?: 0f, 0.01f)
    }
}
