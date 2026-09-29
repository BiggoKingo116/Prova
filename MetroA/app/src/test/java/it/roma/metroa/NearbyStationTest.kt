package it.roma.metroa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyStationTest {
    @Test fun atStationWithin500m() {
        val termini = STATIONS[11]
        val w = whereFrom(termini.lat, termini.lon, 15f, precise = true)
        assertEquals(11, w.station)
        assertTrue(w.atStation)
        assertEquals(11, (w as Where).station)
        // Circa 1 km oltre il capolinea Anagnina: si mostra la stazione più vicina, ma non "sei qui"
        val anagnina = STATIONS[LAST]
        val far = whereFrom(anagnina.lat, anagnina.lon + 0.012, 15f, precise = true)
        assertEquals(LAST, far.station)
        assertFalse(far.atStation)
        assertTrue(far.distanceM.toString(), far.distanceM in 900..1100)
        assertNull((far as Where).station)
    }

    @Test fun everyStationIsItsOwnNearest() {
        STATIONS.forEachIndexed { i, s -> assertEquals(s.name, i, whereFrom(s.lat, s.lon, 10f, true).station) }
    }

    @Test fun terminusByDirection() {
        assertTrue(isEndOfLine(LAST, Direction.TO_ANAGNINA))
        assertTrue(isEndOfLine(0, Direction.TO_BATTISTINI))
        assertTrue(isStartOfLine(0, Direction.TO_ANAGNINA))
        assertTrue(isStartOfLine(LAST, Direction.TO_BATTISTINI))
        assertFalse(isEndOfLine(11, Direction.TO_ANAGNINA) || isStartOfLine(11, Direction.TO_ANAGNINA))
    }

    @Test fun keepsTheBetterFix() {
        assertTrue(isBetterFix(1_000, 50f, null, null))
        // Più precisa: meglio, anche se di poco più vecchia
        assertTrue(isBetterFix(1_000, 20f, 5_000, 200f))
        // Meno precisa e non più recente: peggio
        assertFalse(isBetterFix(5_000, 900f, 5_000, 30f))
        // Poco più recente e precisione simile: meglio (ci si sta muovendo)
        assertTrue(isBetterFix(8_000, 60f, 5_000, 30f))
        // Molto più recente vince comunque: la vecchia non dice più dove si è
        assertTrue(isBetterFix(100_000, 900f, 5_000, 10f))
        // Molto più vecchia perde comunque
        assertFalse(isBetterFix(5_000, 5f, 100_000, 900f))
    }
}
