package ru.protonmod.next.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {

    @Test
    fun `light and dark keep their names`() {
        assertEquals(AppTheme.LIGHT, AppTheme.fromStoredName("LIGHT", systemDark = true))
        assertEquals(AppTheme.DARK, AppTheme.fromStoredName("DARK", systemDark = false))
    }

    @Test
    fun `removed themes fall back to the palette they were based on`() {
        listOf("GOLD_LIGHT", "SURFSHARK", "NORD").forEach {
            assertEquals(it, AppTheme.LIGHT, AppTheme.fromStoredName(it, systemDark = true))
        }
        listOf("AMOLED", "GOLD_DARK", "GOLD_AMOLED", "IPVANISH", "PUREVPN", "MULLVAD", "WINDSCRIBE", "NOTHING").forEach {
            assertEquals(it, AppTheme.DARK, AppTheme.fromStoredName(it, systemDark = false))
        }
    }

    @Test
    fun `system or no choice follows the phone`() {
        assertEquals(AppTheme.DARK, AppTheme.fromStoredName("SYSTEM", systemDark = true))
        assertEquals(AppTheme.LIGHT, AppTheme.fromStoredName("SYSTEM", systemDark = false))
        assertEquals(AppTheme.LIGHT, AppTheme.fromStoredName(null, systemDark = false))
        assertEquals(AppTheme.DARK, AppTheme.fromStoredName(null, systemDark = true))
    }
}
