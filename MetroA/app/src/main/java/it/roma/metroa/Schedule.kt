package it.roma.metroa

import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Reader
import java.io.Writer
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/** route_id della Metro A nel GTFS statico di Roma. */
const val METRO_A_ROUTE_ID = "MEA"

val ROME: ZoneId = ZoneId.of("Europe/Rome")

data class Train(val id: String, val position: Float, val direction: Direction)

/** Una corsa programmata: stazioni toccate (indici in STATIONS) con orari in secondi dall'inizio del giorno di servizio. */
class ScheduledTrip(
    val id: String,
    val serviceId: String,
    val direction: Direction,
    val stations: IntArray,
    val arr: IntArray,
    val dep: IntArray,
)

class Schedule(
    val trips: List<ScheduledTrip>,
    /** yyyyMMdd → service_id attivi quel giorno. */
    val servicesByDate: Map<Int, Set<String>>,
    val downloadedAtMs: Long,
) {
    fun covers(date: LocalDate) = servicesByDate.containsKey(dateKey(date))

    /** Posizione (indice frazionario) di ogni treno in viaggio secondo l'orario. */
    fun trainsAt(now: ZonedDateTime): List<Train> = activeTrips(now).mapNotNull { (trip, t) ->
        val last = trip.stations.lastIndex
        if (t < trip.dep[0] || t > trip.arr[last]) return@mapNotNull null
        var pos = trip.stations[last].toFloat()
        for (k in 0 until last) {
            if (t <= trip.dep[k]) { pos = trip.stations[k].toFloat(); break }
            if (t < trip.arr[k + 1]) {
                val f = (t - trip.dep[k]).toFloat() / (trip.arr[k + 1] - trip.dep[k])
                pos = trip.stations[k] + (trip.stations[k + 1] - trip.stations[k]) * f
                break
            }
        }
        Train(trip.id, pos, trip.direction)
    }.toList()

    /** Minuti ai prossimi passaggi programmati nella stazione, nella direzione data. */
    fun arrivals(station: Int, dir: Direction, now: ZonedDateTime, count: Int = 3): List<Int> =
        activeTrips(now).filter { it.first.direction == dir }.mapNotNull { (trip, t) ->
            val k = trip.stations.indexOf(station)
            if (k < 0) return@mapNotNull null
            val time = if (k == 0) trip.dep[0] else trip.arr[k]
            if (time >= t) (time - t) / 60 else null
        }.sorted().take(count).toList()

    /** Corse dei giorni di servizio di oggi e di ieri (quelle dopo mezzanotte hanno orari oltre le 24:00). */
    private fun activeTrips(now: ZonedDateTime): Sequence<Pair<ScheduledTrip, Int>> {
        val today = now.withZoneSameInstant(ROME).toLocalDate()
        return sequenceOf(today, today.minusDays(1)).flatMap { day ->
            val services = servicesByDate[dateKey(day)] ?: emptySet()
            // GTFS: gli orari contano da "mezzogiorno meno 12 ore", che differisce dalla mezzanotte nei giorni del cambio d'ora
            val start = day.atTime(12, 0).atZone(ROME).minusHours(12)
            val t = (now.toEpochSecond() - start.toEpochSecond()).toInt()
            trips.asSequence().filter { it.serviceId in services }.map { it to t }
        }
    }

    fun write(out: Writer) {
        out.write("$HEADER\t$downloadedAtMs\n")
        for ((date, services) in servicesByDate) out.write("C\t$date\t${services.joinToString("\t")}\n")
        for (trip in trips) {
            out.write("T\t${trip.id}\t${trip.serviceId}\t${trip.direction.name}")
            for (k in trip.stations.indices) out.write("\t${trip.stations[k]},${trip.arr[k]},${trip.dep[k]}")
            out.write("\n")
        }
    }

    companion object {
        private const val HEADER = "METROA-SCHEDULE-1"
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE

        fun dateKey(date: LocalDate) = date.format(DATE).toInt()

        fun read(input: Reader): Schedule {
            val lines = BufferedReader(input).lineSequence().iterator()
            val head = lines.next().split('\t')
            require(head[0] == HEADER) { "Formato orario sconosciuto" }
            val trips = ArrayList<ScheduledTrip>()
            val calendar = HashMap<Int, Set<String>>()
            for (line in lines) {
                val f = line.split('\t')
                when (f[0]) {
                    "C" -> calendar[f[1].toInt()] = f.drop(2).toSet()
                    "T" -> {
                        val stops = f.drop(4).map { s -> s.split(',').map(String::toInt) }
                        trips += ScheduledTrip(
                            f[1], f[2], Direction.valueOf(f[3]),
                            IntArray(stops.size) { stops[it][0] },
                            IntArray(stops.size) { stops[it][1] },
                            IntArray(stops.size) { stops[it][2] },
                        )
                    }
                }
            }
            return Schedule(trips, calendar, head[1].toLong())
        }
    }
}

/** Estrae dal GTFS statico di Roma solo le corse della Metro A. */
object GtfsParser {
    private class StopTime(val seq: Int, val stopId: String, val arr: Int, val dep: Int)

    /** [onProgress] riceve la frazione (0–1) di stop_times.txt letta: è il file più grande (~240 MB). */
    fun parse(
        zip: ZipFile,
        routeId: String = METRO_A_ROUTE_ID,
        downloadedAtMs: Long = System.currentTimeMillis(),
        onProgress: (Float) -> Unit = {},
    ): Schedule {
        val tripService = HashMap<String, String>()
        readCsv(zip, "trips.txt") { col, line ->
            val f = splitCsv(line)
            if (f[col("route_id")] == routeId) tripService[f[col("trip_id")]] = f[col("service_id")]
        }
        require(tripService.isNotEmpty()) { "Nessuna corsa $routeId nel GTFS" }

        val stopTimes = HashMap<String, MutableList<StopTime>>()
        readCsv(zip, "stop_times.txt", onProgress) { col, line ->
            // Filtro veloce sul trip_id prima di spezzare tutta la riga: il file ha milioni di righe
            val tripCol = col("trip_id")
            val tripId = if (tripCol == 0) line.substringBefore(',').trim('"') else splitCsv(line)[tripCol]
            if (tripId !in tripService) return@readCsv
            val f = splitCsv(line)
            stopTimes.getOrPut(tripId) { ArrayList() } += StopTime(
                f[col("stop_sequence")].toInt(), f[col("stop_id")],
                parseTime(f[col("arrival_time")]), parseTime(f[col("departure_time")]),
            )
        }

        val wanted = stopTimes.values.flatten().map { it.stopId }.toSet()
        val stationOf = HashMap<String, Int>()
        readCsv(zip, "stops.txt") { col, line ->
            val f = splitCsv(line)
            val id = f[col("stop_id")]
            if (id in wanted) {
                nearestStation(f[col("stop_lat")].toDouble(), f[col("stop_lon")].toDouble())?.let { stationOf[id] = it }
            }
        }

        val trips = stopTimes.mapNotNull { (tripId, rows) ->
            val stops = rows.sortedBy { it.seq }.filter { it.stopId in stationOf }
            if (stops.size < 2) return@mapNotNull null
            val stations = IntArray(stops.size) { stationOf.getValue(stops[it].stopId) }
            val dir = if (stations.last() > stations.first()) Direction.TO_ANAGNINA else Direction.TO_BATTISTINI
            ScheduledTrip(tripId, tripService.getValue(tripId), dir, stations,
                IntArray(stops.size) { stops[it].arr }, IntArray(stops.size) { stops[it].dep })
        }

        val services = tripService.values.toSet()
        return Schedule(trips, readCalendar(zip, services), downloadedAtMs)
    }

    /** Giorni di servizio da calendar.txt (se c'è) più le eccezioni di calendar_dates.txt. */
    private fun readCalendar(zip: ZipFile, services: Set<String>): Map<Int, Set<String>> {
        val byDate = HashMap<Int, MutableSet<String>>()
        val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        if (zip.getEntry("calendar.txt") != null) readCsv(zip, "calendar.txt") { col, line ->
            val f = splitCsv(line)
            val id = f[col("service_id")]
            if (id !in services) return@readCsv
            var d = LocalDate.parse(f[col("start_date")], DateTimeFormatter.BASIC_ISO_DATE)
            val end = LocalDate.parse(f[col("end_date")], DateTimeFormatter.BASIC_ISO_DATE)
            while (!d.isAfter(end)) {
                if (f[col(days[d.dayOfWeek.value - 1])] == "1") byDate.getOrPut(Schedule.dateKey(d)) { HashSet() } += id
                d = d.plusDays(1)
            }
        }
        if (zip.getEntry("calendar_dates.txt") != null) readCsv(zip, "calendar_dates.txt") { col, line ->
            val f = splitCsv(line)
            val id = f[col("service_id")]
            if (id !in services) return@readCsv
            val date = f[col("date")].toInt()
            when (f[col("exception_type")]) {
                "1" -> byDate.getOrPut(date) { HashSet() } += id
                "2" -> byDate[date]?.remove(id)
            }
        }
        return byDate
    }

    private fun readCsv(
        zip: ZipFile, name: String, onProgress: (Float) -> Unit = {},
        onRow: (col: (String) -> Int, line: String) -> Unit,
    ) {
        val entry = zip.getEntry(name) ?: error("$name mancante nel GTFS")
        val counting = CountingStream(zip.getInputStream(entry))
        counting.bufferedReader().use { reader ->
            val header = splitCsv(reader.readLine().removePrefix("﻿")).map { it.trim() }
            val index = header.withIndex().associate { it.value to it.index }
            val col = { c: String -> index[c] ?: error("Colonna $c mancante in $name") }
            var n = 0
            reader.forEachLine { line ->
                if (line.isNotBlank()) onRow(col, line)
                if (++n % 50_000 == 0 && entry.size > 0) onProgress(counting.count.toFloat() / entry.size)
            }
        }
        onProgress(1f)
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /** "25:04:30" → secondi; nel GTFS le ore possono superare 24. */
    private fun parseTime(s: String): Int {
        val (h, m, sec) = s.trim().split(':').map(String::toInt)
        return h * 3600 + m * 60 + sec
    }

    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
