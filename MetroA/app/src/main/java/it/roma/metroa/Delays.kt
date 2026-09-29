package it.roma.metroa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/** Un passaggio reale segnato dall'utente: [offsetS] secondi rispetto all'orario (positivo = in ritardo). */
data class Observation(val timeMs: Long, val station: Int, val direction: Direction, val offsetS: Int) {
    val hour: Int get() = Instant.ofEpochMilli(timeMs).atZone(ROME).hour
}

/** Ritardo stimato: [medianS] al centro, ± [halfWidthS] di incertezza, da [count] rilevamenti. */
data class DelayEstimate(val medianS: Int, val halfWidthS: Int, val count: Int)

/** Senza rilevamenti: orario così com'è, ±1 minuto. */
val NO_DATA = DelayEstimate(0, 60, 0)

private const val MIN_SAMPLES = 3

/**
 * Stima il ritardo dai passaggi registrati, preferendo quelli più simili alla situazione attuale:
 * stessa stazione, direzione e fascia oraria (±1 ora); poi solo direzione e fascia oraria; poi solo direzione.
 */
class DelayModel(val observations: List<Observation>) {
    fun forStation(station: Int, dir: Direction, hour: Int): DelayEstimate = firstWithEnough(
        observations.filter { it.direction == dir && it.station == station && nearHour(it.hour, hour) },
        observations.filter { it.direction == dir && nearHour(it.hour, hour) },
        observations.filter { it.direction == dir },
    )

    fun forLine(dir: Direction, hour: Int): DelayEstimate = firstWithEnough(
        observations.filter { it.direction == dir && nearHour(it.hour, hour) },
        observations.filter { it.direction == dir },
    )

    operator fun plus(o: Observation) = DelayModel(observations + o)

    private fun firstWithEnough(vararg groups: List<Observation>): DelayEstimate {
        val g = groups.firstOrNull { it.size >= MIN_SAMPLES } ?: return NO_DATA
        val sorted = g.map { it.offsetS }.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        // Metà dell'intervallo interquartile, più mezzo minuto di margine; mai meno di ±1 minuto
        val half = max(60, (pct(0.75) - pct(0.25)) / 2 + 30)
        return DelayEstimate(pct(0.5), half, g.size)
    }

    private fun nearHour(a: Int, b: Int) = abs(a - b).let { minOf(it, 24 - it) } <= 1
}

/** Intervallo di arrivo in minuti, già corretto col ritardo stimato. */
data class Eta(val lowMin: Int, val highMin: Int)

/** Trasforma i secondi all'orario in intervalli corretti; esclude i treni ormai passati anche col margine. */
fun etas(passagesS: List<Int>, delay: DelayEstimate, count: Int = 3): List<Eta> =
    passagesS.map { it + delay.medianS }
        .filter { it + delay.halfWidthS >= 0 }
        .sorted().take(count)
        .map { est ->
            val low = max(0, Math.floorDiv(est - delay.halfWidthS, 60))
            val high = max(low + 1, Math.floorDiv(est + delay.halfWidthS + 59, 60))
            Eta(low, high)
        }

/** Registro dei passaggi reali, salvato solo sul telefono. */
class ObservationDb(context: Context) : SQLiteOpenHelper(context, "observations.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE observation (id INTEGER PRIMARY KEY AUTOINCREMENT, time_ms INTEGER NOT NULL, " +
                "station INTEGER NOT NULL, direction TEXT NOT NULL, offset_s INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun add(o: Observation) {
        writableDatabase.insert("observation", null, ContentValues().apply {
            put("time_ms", o.timeMs); put("station", o.station)
            put("direction", o.direction.name); put("offset_s", o.offsetS)
        })
    }

    /** Solo gli ultimi [days] giorni: l'orario e il servizio cambiano nel tempo. */
    fun recent(days: Int = 60): List<Observation> {
        val since = System.currentTimeMillis() - days * 24L * 3600 * 1000
        return readableDatabase.rawQuery(
            "SELECT time_ms, station, direction, offset_s FROM observation WHERE time_ms >= ? ORDER BY time_ms",
            arrayOf(since.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Observation(c.getLong(0), c.getInt(1), Direction.valueOf(c.getString(2)), c.getInt(3)))
            }
        }
    }

    fun clear() {
        writableDatabase.delete("observation", null, null)
    }
}
