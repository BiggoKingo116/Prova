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

/** [stopped]: il treno è fermo in banchina alla stazione [position] (che allora è un intero). */
data class Train(val id: String, val position: Float, val direction: Direction, val stopped: Boolean = false)

/** Nel GTFS arrivo e partenza coincidono: si assume una sosta di 2 × [DWELL_HALF_S] secondi attorno all'orario. */
private const val DWELL_HALF_S = 12
/** Quanto prima della partenza un treno è mostrato fermo al capolinea, e quanto resta dopo l'arrivo. */
private const val TERMINUS_BEFORE_S = 60
private const val TERMINUS_AFTER_S = 30
/** Quanto indietro guardare per gli arrivi: un treno in ritardo può passare dopo l'orario. */
private const val LOOKBACK_S = 600

/**
 * Un passaggio programmato fra [inS] secondi. Il treno è fermo in banchina da [beforeS] secondi prima
 * a [afterS] secondi dopo: le stesse finestre usate da [Schedule.trainsAt].
 */
data class Passage(val inS: Int, val beforeS: Int, val afterS: Int)

/** Corsa programmata riconosciuta: [scheduledMs] è l'orario previsto (ms) per ogni stazione che tocca. */
data class TripMatch(val tripId: String, val scheduledMs: Map<Int, Long>)

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

    /** Posizione di ogni treno in viaggio. [delayS]: ritardo stimato per direzione, in secondi (positivo = in ritardo). */
    fun trainsAt(now: ZonedDateTime, delayS: (Direction) -> Int = { 0 }): List<Train> =
        activeTrips(now).mapNotNull { (trip, now0) ->
            val t = now0 - delayS(trip.direction)
            val last = trip.stations.lastIndex
            if (t < trip.dep[0] - TERMINUS_BEFORE_S || t > trip.arr[last] + TERMINUS_AFTER_S) return@mapNotNull null
            fun stoppedAt(k: Int) = Train(trip.id, trip.stations[k].toFloat(), trip.direction, stopped = true)
            if (t <= trip.dep[0]) return@mapNotNull stoppedAt(0)
            if (t >= trip.arr[last]) return@mapNotNull stoppedAt(last)
            for (k in 0 until last) {
                // Sosta alla stazione k (esclusa la partenza, già gestita sopra)
                val leave = if (k == 0) trip.dep[0] else trip.dep[k] + DWELL_HALF_S
                if (k > 0 && t <= leave) return@mapNotNull stoppedAt(k)
                val reach = trip.arr[k + 1] - if (k + 1 == last) 0 else DWELL_HALF_S
                if (t < reach) {
                    val f = ((t - leave).toFloat() / (reach - leave)).coerceIn(0f, 1f)
                    val pos = trip.stations[k] + (trip.stations[k + 1] - trip.stations[k]) * f
                    return@mapNotNull Train(trip.id, pos, trip.direction)
                }
            }
            stoppedAt(last)
        }.toList()

    /** Passaggi programmati nella stazione (anche già passati, fino a -[LOOKBACK_S]), in ordine. */
    fun passages(station: Int, dir: Direction, now: ZonedDateTime): List<Passage> =
        activeTrips(now).filter { it.first.direction == dir }.mapNotNull { (trip, t) ->
            val k = trip.stations.indexOf(station)
            if (k < 0) null else passageAt(trip, k, t).takeIf { it.inS >= -LOOKBACK_S }
        }.sortedBy { it.inS }.toList()

    /** Il prossimo passaggio in ogni stazione per [dir], con un solo giro sulle corse (per i minuti sulla linea). */
    fun nextAtEveryStation(dir: Direction, now: ZonedDateTime, delayS: Int): List<Arrival?> {
        val best = arrayOfNulls<Pair<Int, Arrival>>(STATIONS.size)
        for ((trip, t) in activeTrips(now)) {
            if (trip.direction != dir) continue
            for (k in trip.stations.indices) {
                val c = classify(passageAt(trip, k, t), delayS) ?: continue
                val s = trip.stations[k]
                if (best[s].let { it == null || c.first < it.first }) best[s] = c
            }
        }
        return best.map { it?.second }
    }

    /** Durata media in secondi di ogni tratta (stazione i → i+1, nei due sensi), per disegnare la linea in proporzione. */
    val segmentSeconds: FloatArray by lazy {
        val sum = DoubleArray(LAST); val n = IntArray(LAST)
        for (trip in trips) for (k in 0 until trip.stations.lastIndex) {
            val a = trip.stations[k]; val b = trip.stations[k + 1]
            if (kotlin.math.abs(a - b) != 1) continue
            val seg = minOf(a, b)
            sum[seg] += (trip.arr[k + 1] - trip.dep[k]).toDouble(); n[seg]++
        }
        FloatArray(LAST) { if (n[it] > 0) (sum[it] / n[it]).toFloat() else 90f }
    }

    private fun passageAt(trip: ScheduledTrip, k: Int, t: Int) = when (k) {
        0 -> Passage(trip.dep[0] - t, TERMINUS_BEFORE_S, 0)
        trip.stations.lastIndex -> Passage(trip.arr[k] - t, 0, TERMINUS_AFTER_S)
        else -> Passage(trip.arr[k] - t, DWELL_HALF_S, trip.dep[k] - trip.arr[k] + DWELL_HALF_S)
    }

    /**
     * Istanti (ms) dei prossimi [count] arrivi in [station] verso [dir] non ancora avvenuti,
     * spostati di [delayS]; per il widget e l'avviso "il treno sta arrivando".
     */
    fun nextArrivalTimes(station: Int, dir: Direction, now: ZonedDateTime, delayS: Int, count: Int = 3): List<Long> {
        val nowMs = now.toInstant().toEpochMilli()
        return passages(station, dir, now).map { it.inS + delayS }.filter { it >= 0 }.sorted().take(count)
            .map { nowMs + it * 1000L }
    }

    /**
     * La corsa programmata che passa in [station] verso [dir] più vicino all'istante [atMs], con l'orario
     * (ms) di ogni sua stazione: serve a riconoscere su quale treno è salito l'utente. Null se nessuna entro 10 minuti.
     */
    fun matchTrip(station: Int, dir: Direction, atMs: Long): TripMatch? {
        val now = java.time.Instant.ofEpochMilli(atMs).atZone(ROME)
        var best: Pair<ScheduledTrip, Int>? = null
        var bestGap = Int.MAX_VALUE
        for ((trip, t) in activeTrips(now)) {
            if (trip.direction != dir) continue
            val k = trip.stations.indexOf(station)
            if (k < 0) continue
            val gap = kotlin.math.abs((if (k == 0) trip.dep[0] else trip.arr[k]) - t)
            if (gap < bestGap) { bestGap = gap; best = trip to t }
        }
        val (trip, t) = best?.takeIf { bestGap <= LOOKBACK_S } ?: return null
        val times = trip.stations.indices.associate { k ->
            trip.stations[k] to atMs + ((if (k == 0) trip.dep[0] else trip.arr[k]) - t) * 1000L
        }
        return TripMatch(trip.id, times)
    }

    /** Scarto (secondi, positivo = in ritardo) fra adesso e il passaggio programmato più vicino, entro 10 minuti. */
    fun offsetFromNearest(station: Int, dir: Direction, now: ZonedDateTime): Int? =
        passages(station, dir, now).minByOrNull { kotlin.math.abs(it.inS) }
            ?.takeIf { kotlin.math.abs(it.inS) <= LOOKBACK_S }?.let { -it.inS }

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
            val header = splitCsv(reader.readLine().removePrefix("\uFEFF")).map { it.trim() }
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
