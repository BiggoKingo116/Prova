package it.roma.metroa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ScheduleTest {
    private fun stop(id: String, station: Int) = "$id,\"Fermata, $id\",${STATIONS[station].lat},${STATIONS[station].lon}"

    /** Due corse MEA (una per senso) e una di un autobus che va ignorata. */
    private fun sampleZip(): File {
        val files = mapOf(
            "trips.txt" to """
                route_id,service_id,trip_id,trip_headsign,direction_id
                MEA,S1,t1,"ANAGNINA (MA)",1
                MEA,S1,t2,"BATTISTINI, capolinea",0
                64,S1,bus,"TERMINI",0
            """,
            "stop_times.txt" to """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                t1,08:00:00,08:00:00,AD1,1
                t1,08:02:00,08:03:00,AD2,2
                t1,08:05:00,08:05:00,AD3,3
                t2,23:58:00,23:58:00,AP27,1
                t2,24:02:00,24:02:00,AP26,2
                bus,08:00:00,08:00:00,AD1,1
            """,
            "stops.txt" to """
                stop_id,stop_name,stop_lat,stop_lon
                ${stop("AD1", 0)}
                ${stop("AD2", 1)}
                ${stop("AD3", 2)}
                ${stop("AP27", LAST)}
                ${stop("AP26", LAST - 1)}
            """,
            "calendar_dates.txt" to """
                service_id,date,exception_type
                S1,20260929,1
                S2,20260929,1
            """,
        )
        val f = File.createTempFile("gtfs", ".zip").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, body) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(("\uFEFF" + body.trimIndent() + "\n").toByteArray())
                out.closeEntry()
            }
        }
        return f
    }

    private fun at(time: String, date: String = "2026-09-29") = ZonedDateTime.parse("${date}T$time+02:00[Europe/Rome]")

    @Test fun parsesOnlyMetroA() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        assertEquals(setOf("t1", "t2"), s.trips.map { it.id }.toSet())
        val t1 = s.trips.first { it.id == "t1" }
        assertEquals(Direction.TO_ANAGNINA, t1.direction)
        assertEquals(listOf(0, 1, 2), t1.stations.toList())
        assertEquals(Direction.TO_BATTISTINI, s.trips.first { it.id == "t2" }.direction)
        assertEquals(mapOf(20260929 to setOf("S1")), s.servicesByDate)
    }

    @Test fun positionsFollowTimetable() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        // In viaggio dalle 08:00:00 fino a 12 s prima dell'arrivo delle 08:02 (sosta simulata)
        assertEquals(60f / 108, s.trainsAt(at("08:01:00")).single().position, 0.001f)
        assertEquals(Train("t1", 1f, Direction.TO_ANAGNINA, stopped = true), s.trainsAt(at("08:02:30")).single())
        assertEquals(Train("t1", 1f, Direction.TO_ANAGNINA, stopped = true), s.trainsAt(at("08:01:50")).single())
        assertEquals(1f + 48f / 108, s.trainsAt(at("08:04:00")).single().position, 0.001f)
        assertEquals(false, s.trainsAt(at("08:04:00")).single().stopped)
        // Fermo ai capolinea poco prima della partenza e poco dopo l'arrivo
        assertEquals(Train("t1", 0f, Direction.TO_ANAGNINA, stopped = true), s.trainsAt(at("07:59:30")).single())
        assertEquals(Train("t1", 2f, Direction.TO_ANAGNINA, stopped = true), s.trainsAt(at("08:05:10")).single())
        assertTrue(s.trainsAt(at("08:10:00")).isEmpty())
        // Con un ritardo stimato di 1 minuto il treno è dove l'orario lo metteva un minuto prima
        assertEquals(60f / 108, s.trainsAt(at("08:02:00")) { 60 }.single().position, 0.001f)
        // La corsa delle 23:58 è ancora in viaggio dopo mezzanotte, il giorno dopo
        val night = s.trainsAt(at("00:00:00", "2026-09-30")).single()
        assertEquals("t2", night.id)
        assertEquals(LAST - 0.5f, night.position, 0.001f)
    }

    @Test fun passagesAndOffsets() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        assertEquals(listOf(300), s.passages(2, Direction.TO_ANAGNINA, at("08:00:00")).map { it.inS })
        assertEquals(listOf(-60), s.passages(2, Direction.TO_ANAGNINA, at("08:06:00")).map { it.inS })
        assertTrue(s.passages(2, Direction.TO_BATTISTINI, at("08:00:00")).isEmpty())
        assertEquals(listOf(180), s.passages(LAST - 1, Direction.TO_BATTISTINI, at("23:59:00")).map { it.inS })
        // Treno visto arrivare alle 08:06 dove l'orario diceva 08:05: un minuto di ritardo
        assertEquals(60, s.offsetFromNearest(2, Direction.TO_ANAGNINA, at("08:06:00")))
        assertEquals(-30, s.offsetFromNearest(2, Direction.TO_ANAGNINA, at("08:04:30")))
        assertEquals(null, s.offsetFromNearest(2, Direction.TO_ANAGNINA, at("09:00:00")))
    }

    @Test fun boardShowsSingleMinutes() {
        val mid = { s: Int -> Passage(s, 12, 12) }
        assertEquals(listOf(Arrival.InMinutes(4)), board(listOf(mid(240)), 0))
        assertEquals(listOf(Arrival.InMinutes(1)), board(listOf(mid(89)), 0))
        assertEquals(listOf(Arrival.Arriving), board(listOf(mid(30)), 0))
        assertEquals(listOf(Arrival.AtStation), board(listOf(mid(10)), 0))
        assertEquals(listOf(Arrival.AtStation), board(listOf(mid(-12)), 0))
        assertTrue(board(listOf(mid(-13)), 0).isEmpty()) // già ripartito
        // In ritardo di 90 s: un treno che per l'orario è passato 60 s fa deve ancora arrivare
        assertEquals(listOf(Arrival.Arriving), board(listOf(mid(-60)), 90))
        assertEquals(
            listOf(Arrival.InMinutes(2), Arrival.InMinutes(5)),
            board(listOf(mid(300), mid(120), mid(900)), 0, count = 2),
        )
    }

    /** Il pannello dice "In stazione" esattamente quando sulla linea il treno è fermo lì: mai "in stazione" e poi "in arrivo". */
    @Test fun boardAgreesWithLine() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        for (delay in listOf(0, 45, -30)) {
            var t = at("07:57:00")
            while (t.isBefore(at("08:08:00"))) {
                val trains = s.trainsAt(t) { delay }
                for (station in 0..2) {
                    val onLine = trains.any { it.stopped && it.position.toInt() == station }
                    val onBoard = board(s.passages(station, Direction.TO_ANAGNINA, t), delay).firstOrNull() == Arrival.AtStation
                    assertEquals("stazione $station alle $t, ritardo $delay", onLine, onBoard)
                }
                t = t.plusSeconds(1)
            }
        }
    }

    @Test fun delayModelPrefersLiveReports() {
        val nineMs = ZonedDateTime.parse("2026-09-29T09:10:00+02:00[Europe/Rome]").toInstant().toEpochMilli()
        fun rep(minAgo: Int, offset: Int, dir: Direction = Direction.TO_ANAGNINA, daysAgo: Int = 0) =
            Report("r$minAgo$offset$daysAgo", nineMs - minAgo * 60_000L - daysAgo * 86_400_000L, 5, dir, offset, ReportSource.MANUAL)
        val history = listOf(60, 90, 120).map { rep(0, it, daysAgo = 3) }
        val m = DelayModel(history)
        // Nessuna segnalazione recente: lo storico della stessa fascia oraria
        assertEquals(DelayEstimate(90, 3, live = false), m.forDirection(Direction.TO_ANAGNINA, nineMs, 9))
        // Due segnalazioni negli ultimi 20 minuti prevalgono sullo storico
        val live = DelayModel(history + rep(5, 300) + rep(10, 240))
        assertEquals(DelayEstimate(300, 2, live = true), live.forDirection(Direction.TO_ANAGNINA, nineMs, 9))
        // Una sola recente non basta; quelle di 25 minuti fa non sono più "adesso"
        assertEquals(false, DelayModel(history + rep(5, 300)).forDirection(Direction.TO_ANAGNINA, nineMs, 9).live)
        assertEquals(false, DelayModel(history + rep(25, 300) + rep(26, 300)).forDirection(Direction.TO_ANAGNINA, nineMs, 9).live)
        // Altra direzione, o meno di 3 segnalazioni storiche: nessuna correzione
        assertEquals(NO_DATA, m.forDirection(Direction.TO_BATTISTINI, nineMs, 9))
        assertEquals(NO_DATA, DelayModel(history.take(2)).forDirection(Direction.TO_ANAGNINA, nineMs, 9))
    }

    /** I minuti scritti accanto a ogni stazione coincidono col primo arrivo del pannello della stazione. */
    @Test fun nextAtEveryStationMatchesBoard() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        for (time in listOf("07:59:30", "08:01:00", "08:02:30", "08:04:50")) {
            for (delay in listOf(0, 60)) {
                val all = s.nextAtEveryStation(Direction.TO_ANAGNINA, at(time), delay)
                for (station in 0..2) {
                    assertEquals("$time +$delay stazione $station",
                        board(s.passages(station, Direction.TO_ANAGNINA, at(time)), delay).firstOrNull(), all[station])
                }
            }
        }
        // Tratte medie: 08:00→08:02 e 08:03→08:05 durano 2 minuti
        assertEquals(120f, s.segmentSeconds[0], 0.1f)
        assertEquals(120f, s.segmentSeconds[1], 0.1f)
    }

    /** Orari assoluti per widget e avvisi: solo treni non ancora arrivati, spostati del ritardo stimato. */
    @Test fun nextArrivalTimesAreClockTimes() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        val now = at("08:00:00")
        val ms = { t: String -> at(t).toInstant().toEpochMilli() }
        assertEquals(listOf(ms("08:05:00")), s.nextArrivalTimes(2, Direction.TO_ANAGNINA, now, 0))
        assertEquals(listOf(ms("08:06:00")), s.nextArrivalTimes(2, Direction.TO_ANAGNINA, now, 60))
        // Alle 08:06 il treno delle 08:05 è passato, a meno che non sia in ritardo di 2 minuti
        assertTrue(s.nextArrivalTimes(2, Direction.TO_ANAGNINA, at("08:06:00"), 0).isEmpty())
        assertEquals(listOf(ms("08:07:00")), s.nextArrivalTimes(2, Direction.TO_ANAGNINA, at("08:06:00"), 120))
    }

    /** "Sono sul treno": si riconosce la corsa più vicina all'orario, con l'ora prevista a ogni stazione. */
    @Test fun matchTripFindsTheScheduledTrain() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        val ms = { t: String -> at(t).toInstant().toEpochMilli() }
        val m = s.matchTrip(1, Direction.TO_ANAGNINA, ms("08:02:40"))!!
        assertEquals("t1", m.tripId)
        assertEquals(ms("08:00:00"), m.scheduledMs[0])
        assertEquals(ms("08:02:00"), m.scheduledMs[1])
        assertEquals(ms("08:05:00"), m.scheduledMs[2])
        assertNull(s.matchTrip(1, Direction.TO_ANAGNINA, ms("09:00:00")))
        assertNull(s.matchTrip(1, Direction.TO_BATTISTINI, ms("08:02:00")))
    }

    /** Per l'app sviluppatore: i treni vicini a una stazione, con un'etichetta leggibile. */
    @Test fun trainsAtStationHaveReadableLabels() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it) }
        val list = s.trainsAt(1, Direction.TO_ANAGNINA, at("08:01:00"))
        assertEquals(listOf("t1"), list.map { it.tripId })
        assertEquals("treno delle 08:00 da ${STATIONS[0].name}", list.single().label)
        assertEquals(at("08:02:00").toInstant().toEpochMilli(), list.single().atStationMs)
        // Passato da più di 5 minuti: non più in elenco
        assertTrue(s.trainsAt(1, Direction.TO_ANAGNINA, at("08:10:00")).isEmpty())
    }

    @Test fun alertNeedsSeveralBigDelaysAndFewDenials() {
        val now = ZonedDateTime.parse("2026-09-29T09:10:00+02:00[Europe/Rome]").toInstant().toEpochMilli()
        fun rep(minAgo: Int, offset: Int, station: Int = 5, dir: Direction = Direction.TO_ANAGNINA, src: ReportSource = ReportSource.MANUAL) =
            Report("r$minAgo-$offset-$station-$src", now - minAgo * 60_000L, station, dir, offset, src)
        val late = listOf(rep(2, 420, 5), rep(6, 360, 8), rep(12, 600, 8))
        val a = DelayModel(late).alerts(now).single()
        assertEquals(Direction.TO_ANAGNINA, a.direction)
        assertEquals(3, a.count)
        assertEquals(420, a.medianS)
        assertEquals(listOf(5, 8), a.stations)
        // Solo 2 ritardi forti, o segnalazioni vecchie di 20 minuti: nessun avviso
        assertTrue(DelayModel(late.take(2)).alerts(now).isEmpty())
        assertTrue(DelayModel(late.map { it.copy(timeMs = it.timeMs - 20 * 60_000L) }).alerts(now).isEmpty())
        // Tante smentite quanti ritardi: l'avviso sparisce
        val denied = late + listOf(rep(1, 0, src = ReportSource.DENY), rep(3, 0, src = ReportSource.DENY), rep(4, 30))
        assertTrue(DelayModel(denied).alerts(now).isEmpty())
        // L'altra direzione non è coinvolta
        assertTrue(DelayModel(late).alerts(now).none { it.direction == Direction.TO_BATTISTINI })
    }

    @Test fun cacheRoundTrip() {
        val s = ZipFile(sampleZip()).use { GtfsParser.parse(it, downloadedAtMs = 42) }
        val out = StringWriter().also { s.write(it) }.toString()
        val back = Schedule.read(StringReader(out))
        assertEquals(42L, back.downloadedAtMs)
        assertEquals(s.servicesByDate, back.servicesByDate)
        assertEquals(s.trainsAt(at("08:01:00")), back.trainsAt(at("08:01:00")))
        assertTrue(back.covers(LocalDate.of(2026, 9, 29)))
    }

    /** Con METROA_GTFS_ZIP=percorso/rome_static_gtfs.zip verifica il file reale di Roma Mobilità. */
    @Test fun realGtfs() {
        val path = System.getenv("METROA_GTFS_ZIP")
        assumeTrue(path != null && File(path).exists())
        val s = ZipFile(path).use { GtfsParser.parse(it) }
        assertTrue(s.trips.isNotEmpty())
        // Ogni corsa completa deve toccare tutte le stazioni, in ordine
        s.trips.filter { it.stations.size == STATIONS.size }.forEach { trip ->
            val expected = if (trip.direction == Direction.TO_ANAGNINA) (0..LAST).toList() else (LAST downTo 0).toList()
            assertEquals(trip.id, expected, trip.stations.toList())
        }
        println("Corse MEA: ${s.trips.size}, giorni coperti: ${s.servicesByDate.keys.min()}–${s.servicesByDate.keys.max()}")
    }
}
