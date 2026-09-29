package it.roma.metroa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun uniformSpacing() {
        val c = stationCenters(AppSettings(stationSpacing = 50), null)
        assertEquals(0f, c[0], 0f)
        assertEquals(50f, c[1], 0f)
        assertEquals(50f * LAST, c[LAST], 0f)
    }

    @Test fun reversedPutsAnagninaOnTop() {
        val c = stationCenters(AppSettings(stationSpacing = 50, reversed = true), null)
        assertEquals(0f, c[LAST], 0f)
        assertEquals(50f * LAST, c[0], 0f)
        assertTrue(movesUp(Direction.TO_ANAGNINA, AppSettings(reversed = true)))
        assertFalse(movesUp(Direction.TO_ANAGNINA, AppSettings()))
    }

    @Test fun proportionalSpacingFollowsTravelTimeButStaysReadable() {
        // Una tratta lunga il doppio delle altre, una lunghissima e una brevissima
        val seconds = FloatArray(LAST) { 90f }.also { it[3] = 180f; it[10] = 30f }
        val c = stationCenters(AppSettings(stationSpacing = 60, proportionalSpacing = true), seconds)
        val gap = { i: Int -> c[i + 1] - c[i] }
        assertTrue(gap(3) > 1.9f * gap(0))
        // Mai sotto il minimo, perché i nomi non si sovrappongano
        assertEquals(AppSettings.MIN_SPACING.toFloat(), gap(10), 0.01f)
        // Con la spaziatura fissa l'orario non conta
        val fixed = stationCenters(AppSettings(stationSpacing = 60), seconds)
        assertEquals(60f, fixed[4] - fixed[3], 0f)
    }

    @Test fun favoritesKeepOrderAndToggle() {
        val s = AppSettings().toggleFavorite(11).toggleFavorite(3)
        assertEquals(listOf(11, 3), s.favorites)
        assertEquals(listOf(3), s.toggleFavorite(11).favorites)
    }

    @Test fun trainsSitBetweenStationCenters() {
        val c = stationCenters(AppSettings(stationSpacing = 40), null)
        assertEquals(40f * 2.5f, yAt(c, 2.5f), 0.001f)
        assertEquals(c[LAST], yAt(c, LAST.toFloat()), 0f)
        val r = stationCenters(AppSettings(stationSpacing = 40, reversed = true), null)
        assertEquals((r[2] + r[3]) / 2, yAt(r, 2.5f), 0.001f)
    }
}
