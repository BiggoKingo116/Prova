package it.roma.metroa

import org.junit.Assert.assertEquals
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
                out.write(("﻿" + body.trimIndent() + "\n").toByteArray())
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
        assertEquals(listOf(300), s.passages(2, Direction.TO_ANAGNINA, at("08:00:00")))
        assertEquals(listOf(-60), s.passages(2, Direction.TO_ANAGNINA, at("08:06:00")))
        assertTrue(s.passages(2, Direction.TO_BATTISTINI, at("08:00:00")).isEmpty())
        assertEquals(listOf(180), s.passages(LAST - 1, Direction.TO_BATTISTINI, at("23:59:00")))
        // Treno visto arrivare alle 08:06 dove l'orario diceva 08:05: un minuto di ritardo
        assertEquals(60, s.offsetFromNearest(2, Direction.TO_ANAGNINA, at("08:06:00")))
        assertEquals(-30, s.offsetFromNearest(2, Direction.TO_ANAGNINA, at("08:04:30")))
        assertEquals(null, s.offsetFromNearest(2, Direction.TO_ANAGNINA, at("09:00:00")))
    }

    @Test fun etaIntervals() {
        assertEquals(listOf(Eta(3, 5)), etas(listOf(240), NO_DATA))
        assertEquals(listOf(Eta(0, 2)), etas(listOf(30), NO_DATA))
        assertTrue(etas(listOf(-90), NO_DATA).isEmpty()) // già passato anche col margine
        // In ritardo di 90 s: un treno che per l'orario è passato 60 s fa deve ancora arrivare
        assertEquals(listOf(Eta(0, 2)), etas(listOf(-60), DelayEstimate(90, 60, 5)))
        assertEquals(listOf(Eta(1, 3), Eta(4, 6)), etas(listOf(300, 120, 900), NO_DATA, count = 2))
    }

    @Test fun delayModelPrefersSimilarObservations() {
        val nine = ZonedDateTime.parse("2026-09-29T09:10:00+02:00[Europe/Rome]").toInstant().toEpochMilli()
        val obs = listOf(60, 90, 120).map { Observation(nine, 5, Direction.TO_ANAGNINA, it) } +
            listOf(-30, -30, -30).map { Observation(nine, 20, Direction.TO_ANAGNINA, it) }
        val m = DelayModel(obs)
        assertEquals(DelayEstimate(90, 60, 3), m.forStation(5, Direction.TO_ANAGNINA, 9))
        assertEquals(DelayEstimate(-30, 60, 3), m.forStation(20, Direction.TO_ANAGNINA, 10))
        // Stazione senza dati: si usa tutta la direzione nella stessa fascia oraria
        assertEquals(6, m.forStation(12, Direction.TO_ANAGNINA, 9).count)
        // Altra direzione, o meno di 3 passaggi: nessuna correzione
        assertEquals(NO_DATA, m.forLine(Direction.TO_BATTISTINI, 9))
        assertEquals(NO_DATA, DelayModel(obs.take(2)).forLine(Direction.TO_ANAGNINA, 9))
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
