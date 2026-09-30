package it.roma.metroa

import kotlin.math.abs

/** Una stazione passata durante il viaggio; [manual] se l'ha segnata l'utente e non la posizione. */
data class TripPassage(val station: Int, val timeMs: Long, val manual: Boolean)

/**
 * Viaggio "sono sul treno": l'utente è salito in [boardStation] verso [direction]. [match] è la corsa
 * programmata riconosciuta, per confrontare i passaggi con l'orario. [endMs] non nullo: viaggio concluso.
 */
data class TripState(
    val id: String,
    val direction: Direction,
    val boardStation: Int,
    val startMs: Long,
    val match: TripMatch?,
    val passages: List<TripPassage> = emptyList(),
    val endMs: Long? = null,
    /** L'utente ha scelto di condividere anche il percorso GPS di questo viaggio. */
    val shareTrack: Boolean = false,
) {
    val active: Boolean get() = endMs == null

    /** L'ultima stazione raggiunta (all'inizio quella di salita). */
    val lastStation: Int get() = passages.lastOrNull()?.station ?: boardStation

    /** La prossima stazione, o null se il treno è al capolinea. */
    val nextStation: Int? get() = (lastStation + direction.step).takeIf { it in STATIONS.indices }

    /** Secondi di ritardo (positivi) o anticipo (negativi) del passaggio in [station] a [timeMs] rispetto all'orario. */
    fun offsetAt(station: Int, timeMs: Long): Int? =
        match?.scheduledMs?.get(station)?.let { ((timeMs - it) / 1000).toInt() }

    /** Il treno è ancora (o appena) nell'ultima stazione registrata: "sei a Flaminio" invece di "prossima: Lepanto". */
    fun atLastStation(nowMs: Long): Boolean = passages.lastOrNull()?.let { nowMs - it.timeMs < 40_000 } ?: false

    /** Scarto all'ultima stazione registrata: la situazione del treno adesso. */
    val currentOffsetS: Int? get() = passages.lastOrNull()?.let { offsetAt(it.station, it.timeMs) }
}

/** +1 verso Anagnina (indici crescenti), -1 verso Battistini. */
val Direction.step: Int get() = if (this == Direction.TO_ANAGNINA) 1 else -1

/** Posizioni meno precise di così (tipico sottoterra con la sola rete) non bastano a dire in che stazione si è. */
private const val MAX_ACCURACY_M = 250f
/** Fra due stazioni il treno impiega almeno ~50 s: passaggi più ravvicinati sono errori di posizione. */
private const val MIN_GAP_MS = 30_000L
/** Per saltare una stazione (tratta senza segnale) serve almeno questo tempo in più per ogni stazione saltata. */
private const val PER_SKIPPED_STATION_MS = 40_000L
/** Quante stazioni avanti cercare, se la posizione ne ha saltata qualcuna. */
private const val LOOK_AHEAD = 3

/**
 * La stazione che la posizione indica come raggiunta, fra le prossime [LOOK_AHEAD] nel verso di marcia,
 * o null. Il raggio è 80 m più metà dell'errore dichiarato, al massimo 180 m: così la stazione si registra
 * quando ci si arriva davvero, non già a qualche centinaio di metri. Saltare stazioni è possibile solo
 * se è passato abbastanza tempo per arrivarci.
 */
fun detectPassage(trip: TripState, lat: Double, lon: Double, accuracyM: Float, timeMs: Long): Int? {
    if (!trip.active || accuracyM > MAX_ACCURACY_M) return null
    val elapsed = timeMs - (trip.passages.lastOrNull()?.timeMs ?: trip.startMs)
    val radius = minOf(180.0, 80.0 + accuracyM / 2)
    return (1..LOOK_AHEAD)
        .filter { k -> elapsed >= MIN_GAP_MS + (k - 1) * PER_SKIPPED_STATION_MS }
        .map { k -> trip.lastStation + k * trip.direction.step }
        .filter { it in STATIONS.indices }
        .map { it to distanceToStation(lat, lon, it) }
        .filter { it.second <= radius }
        .minByOrNull { it.second }?.first
}

/**
 * Conferma dei passaggi: una stazione si registra solo quando due posizioni successive (entro un minuto)
 * la indicano entrambe; il passaggio prende l'ora della prima. Una sola posizione sbagliata non basta più.
 */
class PassageConfirmer {
    private var candidate: Pair<Int, Long>? = null

    /** Restituisce stazione e ora del passaggio confermato, o null. */
    fun offer(station: Int?, timeMs: Long): Pair<Int, Long>? {
        val c = candidate
        if (station == null) {
            if (c != null && timeMs - c.second > 60_000) candidate = null
            return null
        }
        if (c != null && c.first == station && timeMs - c.second <= 60_000) {
            candidate = null
            return c
        }
        candidate = station to timeMs
        return null
    }

    fun reset() { candidate = null }
}

/** Tempo impiegato fra due stazioni passate, e quello previsto dall'orario (se la corsa è stata riconosciuta). */
data class TripLeg(val from: Int, val to: Int, val actualS: Int, val scheduledS: Int?)

/** Riepilogo: dalla prima all'ultima stazione registrata, con i tempi di ogni tratto. */
data class TripSummary(val from: Int, val to: Int, val actualS: Int, val scheduledS: Int?, val legs: List<TripLeg>)

fun summarize(trip: TripState): TripSummary? {
    val p = trip.passages
    if (p.size < 2) return null
    val sched = trip.match?.scheduledMs
    fun scheduled(a: Int, b: Int) = sched?.get(a)?.let { sa -> sched[b]?.let { sb -> ((sb - sa) / 1000).toInt() } }
    val legs = p.zipWithNext { a, b -> TripLeg(a.station, b.station, ((b.timeMs - a.timeMs) / 1000).toInt(), scheduled(a.station, b.station)) }
    return TripSummary(p.first().station, p.last().station, ((p.last().timeMs - p.first().timeMs) / 1000).toInt(),
        scheduled(p.first().station, p.last().station), legs)
}

/** Offset plausibile per il database (il server accetta ±15 minuti); oltre, la corsa è stata riconosciuta male. */
fun plausibleOffset(s: Int?): Int? = s?.takeIf { abs(it) <= 900 }
