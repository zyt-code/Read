package com.example.read.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNavigationUiContractTest {

    @Test
    fun importActionIsBottomEndPlusFab() {
        val spec = homeImportActionSpec()

        assertEquals(HomeImportActionPlacement.BottomEndFloatingActionButton, spec.placement)
        assertEquals("Add", spec.iconName)
        assertEquals("Import book", spec.contentDescription)
    }

    @Test
    fun bottomNavigationUsesStandardMaterialIconNames() {
        assertEquals("Home", homeTabIconName(HomeTab.Home))
        assertEquals("Settings", homeTabIconName(HomeTab.Settings))
    }
}
