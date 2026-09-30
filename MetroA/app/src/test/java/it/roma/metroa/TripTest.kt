package it.roma.metroa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTest {
    private val start = 1_790_000_000_000L
    private fun trip(board: Int = 9, dir: Direction = Direction.TO_ANAGNINA, passages: List<TripPassage> = emptyList(),
                     match: TripMatch? = null) = TripState("t", dir, board, start, match, passages)
    private fun at(i: Int) = STATIONS[i]

    @Test fun detectsTheNextStationNearby() {
        val t = trip()
        // Vicino a Repubblica (10), la prossima dopo Barberini (9)
        assertEquals(10, detectPassage(t, at(10).lat, at(10).lon, 30f, start + 90_000))
        // Ancora a Barberini: non è un passaggio
        assertNull(detectPassage(t, at(9).lat, at(9).lon, 30f, start + 90_000))
        // Stazione già passata nell'altro verso: ignorata
        assertNull(detectPassage(t, at(8).lat, at(8).lon, 30f, start + 90_000))
    }

    @Test fun skipsStationsWithoutSignal() {
        // Nessuna posizione fra Barberini e Vittorio Emanuele (12): si registra direttamente quella
        assertEquals(12, detectPassage(trip(), at(12).lat, at(12).lon, 30f, start + 200_000))
        // Ma non oltre 3 stazioni avanti: troppo incerto
        assertNull(detectPassage(trip(), at(13).lat, at(13).lon, 30f, start + 300_000))
    }

    @Test fun ignoresVagueOrTooCloseFixes() {
        assertNull(detectPassage(trip(), at(10).lat, at(10).lon, 900f, start + 90_000)) // sottoterra, solo rete
        assertNull(detectPassage(trip(), at(10).lat, at(10).lon, 30f, start + 10_000))  // 10 s dopo la salita
        val done = trip().copy(endMs = start)
        assertNull(detectPassage(done, at(10).lat, at(10).lon, 30f, start + 90_000))
    }

    @Test fun skippingNeedsTimeToGetThere() {
        // Posizione su Vittorio Emanuele (3 stazioni avanti) appena 60 s dopo la salita: impossibile, ignorata
        assertNull(detectPassage(trip(), at(12).lat, at(12).lon, 30f, start + 60_000))
        // Una stazione avanti dopo 60 s: normale
        assertEquals(10, detectPassage(trip(), at(10).lat, at(10).lon, 30f, start + 60_000))
    }

    @Test fun registersOnlyCloseToTheStation() {
        // 300 m prima di Repubblica, verso Barberini: ancora troppo lontano per dire "arrivati"
        val approaching = detectPassage(trip(), at(10).lat, at(10).lon - 0.0036, 20f, start + 90_000)
        assertNull(approaching)
    }

    @Test fun passageNeedsTwoAgreeingFixes() {
        val c = PassageConfirmer()
        assertNull(c.offer(10, 1_000))                 // prima posizione: solo candidata
        assertEquals(10 to 1_000L, c.offer(10, 6_000)) // seconda uguale: confermata, con l'ora della prima
        assertNull(c.offer(11, 10_000))
        assertNull(c.offer(12, 15_000))                // posizioni in disaccordo: niente
        assertNull(c.offer(12, 90_000))                // troppo distanti nel tempo: si riparte
        assertEquals(12 to 90_000L, c.offer(12, 95_000))
    }

    @Test fun staysAtStationRightAfterPassing() {
        val t = trip(passages = listOf(TripPassage(10, start + 60_000, false)))
        assertTrue(t.atLastStation(start + 80_000))
        assertFalse(t.atLastStation(start + 120_000))
    }

    @Test fun towardBattistiniGoesDown() {
        val t = trip(board = 11, dir = Direction.TO_BATTISTINI)
        assertEquals(10, t.nextStation)
        assertEquals(10, detectPassage(t, at(10).lat, at(10).lon, 30f, start + 60_000))
        assertNull(trip(board = 0, dir = Direction.TO_BATTISTINI).nextStation)
    }

    @Test fun offsetsAndSummaryAgainstTheTimetable() {
        val match = TripMatch("corsa", mapOf(9 to start, 10 to start + 90_000, 11 to start + 140_000))
        val t = trip(match = match, passages = listOf(
            TripPassage(9, start + 10_000, false), TripPassage(10, start + 130_000, false), TripPassage(11, start + 200_000, true),
        ))
        assertEquals(60, t.currentOffsetS) // a Termini un minuto dopo l'orario
        val sum = summarize(t)!!
        assertEquals(9, sum.from); assertEquals(11, sum.to)
        assertEquals(190, sum.actualS); assertEquals(140, sum.scheduledS)
        assertEquals(listOf(TripLeg(9, 10, 120, 90), TripLeg(10, 11, 70, 50)), sum.legs)
        assertNull(summarize(trip(passages = listOf(TripPassage(9, start, false)))))
    }

    @Test fun implausibleOffsetsAreNotShared() {
        assertEquals(120, plausibleOffset(120))
        assertNull(plausibleOffset(1200))
        assertNull(plausibleOffset(null))
    }

    @Test fun tripEndsOnlyWhenMarked() {
        assertTrue(trip().active)
        assertFalse(trip().copy(endMs = start).active)
    }
}
