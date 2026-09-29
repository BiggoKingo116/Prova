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
) {
    val active: Boolean get() = endMs == null

    /** L'ultima stazione raggiunta (all'inizio quella di salita). */
    val lastStation: Int get() = passages.lastOrNull()?.station ?: boardStation

    /** La prossima stazione, o null se il treno è al capolinea. */
    val nextStation: Int? get() = (lastStation + direction.step).takeIf { it in STATIONS.indices }

    /** Secondi di ritardo (positivi) o anticipo (negativi) del passaggio in [station] a [timeMs] rispetto all'orario. */
    fun offsetAt(station: Int, timeMs: Long): Int? =
        match?.scheduledMs?.get(station)?.let { ((timeMs - it) / 1000).toInt() }

    /** Scarto all'ultima stazione registrata: la situazione del treno adesso. */
    val currentOffsetS: Int? get() = passages.lastOrNull()?.let { offsetAt(it.station, it.timeMs) }
}

/** +1 verso Anagnina (indici crescenti), -1 verso Battistini. */
val Direction.step: Int get() = if (this == Direction.TO_ANAGNINA) 1 else -1

/** Posizioni meno precise di così (tipico sottoterra con la sola rete) non bastano a dire in che stazione si è. */
private const val MAX_ACCURACY_M = 400f
/** Fra due stazioni il treno impiega almeno ~50 s: passaggi più ravvicinati sono errori di posizione. */
private const val MIN_GAP_MS = 30_000L
/** Quante stazioni avanti cercare, se la posizione ne ha saltata qualcuna (tratte senza segnale). */
private const val LOOK_AHEAD = 3

/**
 * La stazione che la posizione indica come appena raggiunta, fra le prossime [LOOK_AHEAD] nel verso di
 * marcia, o null. Il raggio cresce con l'imprecisione della posizione: 120 m più metà dell'errore dichiarato.
 */
fun detectPassage(trip: TripState, lat: Double, lon: Double, accuracyM: Float, timeMs: Long): Int? {
    if (!trip.active || accuracyM > MAX_ACCURACY_M) return null
    val last = trip.passages.lastOrNull()?.timeMs ?: trip.startMs
    if (timeMs - last < MIN_GAP_MS) return null
    val radius = 120.0 + accuracyM / 2
    return (1..LOOK_AHEAD).map { trip.lastStation + it * trip.direction.step }
        .filter { it in STATIONS.indices }
        .map { it to distanceToStation(lat, lon, it) }
        .filter { it.second <= radius }
        .minByOrNull { it.second }?.first
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
