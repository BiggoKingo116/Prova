package it.roma.metroa

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import kotlin.math.abs

/**
 * Come è nata la segnalazione: pulsante "arrivato ora" (scarto calcolato dall'app), scelta a mano,
 * oppure conferma / smentita con un tocco di quanto segnalato da altri (la smentita vale "in orario").
 */
enum class ReportSource { ARRIVAL, MANUAL, CONFIRM, DENY }

/**
 * Segnalazione di un utente: in [station], verso [direction], i treni erano [offsetS] secondi
 * rispetto all'orario (positivo = in ritardo, negativo = in anticipo).
 */
data class Report(
    val id: String,
    val timeMs: Long,
    val station: Int,
    val direction: Direction,
    val offsetS: Int,
    val source: ReportSource,
) {
    val hour: Int get() = Instant.ofEpochMilli(timeMs).atZone(ROME).hour
}

/** Ritardo stimato per una direzione: [live] se viene da segnalazioni degli ultimi minuti. */
data class DelayEstimate(val medianS: Int, val count: Int, val live: Boolean)

val NO_DATA = DelayEstimate(0, 0, false)

/** Segnalazioni recenti: bastano 2 negli ultimi 20 minuti per descrivere la situazione di adesso. */
const val LIVE_WINDOW_MS = 20 * 60 * 1000L
private const val MIN_LIVE = 2
/** Storico: almeno 3 segnalazioni nella stessa fascia oraria (±1 h), o nella direzione in generale. */
private const val MIN_HISTORY = 3

/** Possibile guasto: in [direction] almeno [count] segnalazioni di ritardo forte negli ultimi 15 minuti. */
data class Alert(val direction: Direction, val count: Int, val medianS: Int, val stations: List<Int>, val lastMs: Long)

const val ALERT_WINDOW_MS = 15 * 60 * 1000L
/** Oltre 5 minuti di ritardo non è più normale variabilità. */
const val ALERT_MIN_DELAY_S = 300
private const val ALERT_MIN_REPORTS = 3

class DelayModel(val reports: List<Report>) {
    /**
     * Ritardo della direzione adesso. Prima le segnalazioni recenti (un guasto o un rallentamento
     * di oggi contano più della media), poi lo storico nella stessa fascia oraria, poi tutto lo storico.
     */
    fun forDirection(dir: Direction, nowMs: Long, hour: Int): DelayEstimate {
        val mine = reports.filter { it.direction == dir && it.timeMs <= nowMs }
        val live = mine.filter { nowMs - it.timeMs <= LIVE_WINDOW_MS }
        if (live.size >= MIN_LIVE) return estimate(live, live = true)
        val sameHour = mine.filter { nearHour(it.hour, hour) }
        if (sameHour.size >= MIN_HISTORY) return estimate(sameHour, live = false)
        if (mine.size >= MIN_HISTORY) return estimate(mine, live = false)
        return NO_DATA
    }

    fun recent(nowMs: Long): List<Report> = reports.filter { it.timeMs <= nowMs && nowMs - it.timeMs <= LIVE_WINDOW_MS }

    /**
     * Avviso guasti per direzione: almeno 3 segnalazioni con più di 5 minuti di ritardo negli ultimi
     * 15 minuti, e più di quelle che dicono il contrario (in orario o smentite) nello stesso periodo.
     */
    fun alerts(nowMs: Long): List<Alert> = Direction.entries.mapNotNull { dir ->
        val window = reports.filter { it.direction == dir && it.timeMs <= nowMs && nowMs - it.timeMs <= ALERT_WINDOW_MS }
        val late = window.filter { it.offsetS >= ALERT_MIN_DELAY_S }
        val against = window.count { it.offsetS < 120 }
        if (late.size < ALERT_MIN_REPORTS || against >= late.size) return@mapNotNull null
        val sorted = late.map { it.offsetS }.sorted()
        Alert(dir, late.size, sorted[sorted.size / 2], late.map { it.station }.distinct().sorted(), late.maxOf { it.timeMs })
    }

    private fun estimate(g: List<Report>, live: Boolean): DelayEstimate {
        val sorted = g.map { it.offsetS }.sorted()
        return DelayEstimate(sorted[sorted.size / 2], g.size, live)
    }

    private fun nearHour(a: Int, b: Int) = abs(a - b).let { minOf(it, 24 - it) } <= 1
}

/** Cosa mostrare per un passaggio nel pannello della stazione. */
sealed interface Arrival {
    data object AtStation : Arrival
    data object Arriving : Arrival
    data class InMinutes(val minutes: Int) : Arrival
}

/**
 * Prossimi passaggi, corretti col ritardo. Le soglie sono le stesse usate per disegnare i treni
 * fermi in banchina, così il pannello e la linea dicono sempre la stessa cosa.
 */
fun board(passages: List<Passage>, delayS: Int, count: Int = 3): List<Arrival> =
    passages.mapNotNull { p ->
        val est = p.inS + delayS
        when {
            est < -p.afterS -> null // già ripartito
            est <= p.beforeS -> est to Arrival.AtStation
            est < 60 -> est to Arrival.Arriving
            else -> est to Arrival.InMinutes((est + 30) / 60)
        }
    }.sortedBy { it.first }.take(count).map { it.second }

/**
 * Copia locale di tutte le segnalazioni (proprie e scaricate dal server). Le proprie restano con
 * uploaded = 0 finché il server non le ha ricevute, così non si perdono senza rete.
 */
class ReportDb(context: Context) : SQLiteOpenHelper(context, "reports.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE report (id TEXT PRIMARY KEY, time_ms INTEGER NOT NULL, station INTEGER NOT NULL, " +
                "direction TEXT NOT NULL, offset_s INTEGER NOT NULL, source TEXT NOT NULL, uploaded INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX report_time ON report(time_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun save(reports: List<Report>, uploaded: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in reports) {
                db.insertWithOnConflict("report", null, ContentValues().apply {
                    put("id", r.id); put("time_ms", r.timeMs); put("station", r.station)
                    put("direction", r.direction.name); put("offset_s", r.offsetS)
                    put("source", r.source.name); put("uploaded", if (uploaded) 1 else 0)
                }, if (uploaded) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recent(days: Int = 60): List<Report> = query(
        "time_ms >= ?", (System.currentTimeMillis() - days * 24L * 3600 * 1000).toString()
    )

    fun pending(): List<Report> = query("uploaded = 0")

    /**
     * Cancella le segnalazioni già sul server (dal [sinceMs] in poi) che il server non ha più:
     * così una cancellazione fatta dalla dashboard arriva anche sui telefoni. Quelle da inviare restano.
     */
    fun deleteMissing(serverIds: Set<String>, sinceMs: Long): Int {
        val db = writableDatabase
        val local = db.rawQuery("SELECT id FROM report WHERE uploaded = 1 AND time_ms >= ?", arrayOf(sinceMs.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val gone = local.filter { it !in serverIds }
        db.beginTransaction()
        try {
            for (id in gone) db.delete("report", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return gone.size
    }

    fun markUploaded(ids: List<String>) {
        val db = writableDatabase
        for (id in ids) db.update("report", ContentValues().apply { put("uploaded", 1) }, "id = ?", arrayOf(id))
    }

    private fun query(where: String, vararg args: String): List<Report> =
        readableDatabase.rawQuery(
            "SELECT id, time_ms, station, direction, offset_s, source FROM report WHERE $where ORDER BY time_ms",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Report(c.getString(0), c.getLong(1), c.getInt(2),
                    Direction.valueOf(c.getString(3)), c.getInt(4), ReportSource.valueOf(c.getString(5))))
            }
        }
}
