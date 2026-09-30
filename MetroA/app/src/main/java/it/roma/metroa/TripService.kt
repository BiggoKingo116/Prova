package it.roma.metroa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Stato del viaggio "sono sul treno", condiviso fra l'app e il servizio che lo segue a schermo spento.
 * Salvato sul telefono, così sopravvive se Android chiude l'app. Ogni stazione passata diventa una
 * segnalazione (tipo TRIP, con l'id del viaggio) che va nel database online: le coordinate no.
 */
object TripTracker {
    private val _state = MutableStateFlow<TripState?>(null)
    val state: StateFlow<TripState?> = _state
    private var loaded = false
    private var schedule: Schedule? = null

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _state.value = runCatching { decode(prefs(context).getString("trip", null)) }.getOrNull()
    }

    fun start(context: Context, dir: Direction, boardStation: Int, shareTrack: Boolean) {
        val now = System.currentTimeMillis()
        val sched = schedule(context)
        confirmer.reset()
        lastPointMs = 0
        save(context, TripState(UUID.randomUUID().toString(), dir, boardStation, now, sched?.matchTrip(boardStation, dir, now),
            shareTrack = shareTrack))
        // Senza permesso di posizione niente servizio (Android 14 non lo consente): si segnano le stazioni a mano
        if (hasLocationPermission(context)) {
            ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
        }
    }

    /** L'utente dice "siamo a …": registra la prossima stazione adesso. */
    fun markNextStation(context: Context) {
        val t = _state.value?.takeIf { it.active } ?: return
        t.nextStation?.let { record(context, it, System.currentTimeMillis(), manual = true) }
    }

    private val confirmer = PassageConfirmer()
    private var lastPointMs = 0L

    fun onLocation(context: Context, lat: Double, lon: Double, accuracyM: Float, timeMs: Long) {
        val t = _state.value ?: return
        // Percorso GPS: solo se l'utente l'ha scelto, un punto ogni 10 secondi, non quelli troppo vaghi
        if (t.active && t.shareTrack && accuracyM <= 500 && timeMs - lastPointMs >= 10_000) {
            lastPointMs = timeMs
            val point = TrackPoint(UUID.randomUUID().toString(), t.id, timeMs, lat, lon, accuracyM)
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch { ReportDb(app).use { it.savePoint(point) } }
        }
        confirmer.offer(detectPassage(t, lat, lon, accuracyM, timeMs), timeMs)
            ?.let { (station, at) -> record(context, station, at, manual = false) }
    }

    /** "Sono sceso": il viaggio si chiude e resta il riepilogo finché l'utente non lo chiude. */
    fun finish(context: Context) {
        val t = _state.value?.takeIf { it.active } ?: return
        save(context, t.copy(endMs = System.currentTimeMillis()))
        context.stopService(Intent(context, TripService::class.java))
        uploadNow(context) // gli ultimi punti del percorso, se condiviso
    }

    fun dismiss(context: Context) {
        finish(context)
        save(context, null)
    }

    private fun record(context: Context, station: Int, timeMs: Long, manual: Boolean) {
        var t = _state.value ?: return
        confirmer.reset()
        // Al primo passaggio si ricontrolla la corsa: l'ora in cui si preme "sono sul treno" è approssimativa
        if (t.passages.isEmpty()) {
            schedule(context)?.matchTrip(station, t.direction, timeMs)?.let { t = t.copy(match = it) }
        }
        t = t.copy(passages = t.passages + TripPassage(station, timeMs, manual))
        val offset = plausibleOffset(t.offsetAt(station, timeMs))
        if (offset != null) {
            saveAndUpload(context, Report(UUID.randomUUID().toString(), timeMs, station, t.direction, offset, ReportSource.TRIP, t.id))
        }
        // Al capolinea il viaggio finisce da solo
        if (isEndOfLine(station, t.direction)) t = t.copy(endMs = timeMs)
        save(context, t)
        if (!t.active) context.stopService(Intent(context, TripService::class.java))
    }

    /** Salva il passaggio sul telefono e prova subito a mandarlo online; senza rete partirà più tardi con gli altri. */
    private fun saveAndUpload(context: Context, r: Report) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            ReportDb(app).use { db ->
                db.save(listOf(r), uploaded = false)
                val api = reportApiOrNull() ?: return@use
                runCatching { uploadPending(db, api, deviceId(app)) }
            }
        }
    }

    private fun uploadNow(context: Context) {
        val app = context.applicationContext
        val api = reportApiOrNull() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            ReportDb(app).use { db -> runCatching { uploadPending(db, api, deviceId(app)) } }
        }
    }

    private fun schedule(context: Context): Schedule? =
        schedule ?: readCachedSchedule(context.filesDir).also { schedule = it }

    private fun prefs(context: Context) = context.getSharedPreferences("trip", 0)

    private fun save(context: Context, t: TripState?) {
        _state.value = t
        prefs(context).edit().putString("trip", t?.let(::encode)).apply()
        TripService.refreshNotification(context)
    }

    private fun encode(t: TripState) = JSONObject()
        .put("id", t.id).put("direction", t.direction.name).put("board", t.boardStation).put("start", t.startMs)
        .put("end", t.endMs ?: -1)
        .put("shareTrack", t.shareTrack)
        .put("tripId", t.match?.tripId)
        .put("sched", JSONObject().apply { t.match?.scheduledMs?.forEach { (k, v) -> put(k.toString(), v) } })
        .put("passages", JSONArray().apply {
            t.passages.forEach { put(JSONObject().put("s", it.station).put("t", it.timeMs).put("m", it.manual)) }
        })
        .toString()

    private fun decode(json: String?): TripState? {
        val o = JSONObject(json ?: return null)
        val sched = o.getJSONObject("sched")
        val match = o.optString("tripId").takeIf { it.isNotEmpty() && o.has("tripId") }
            ?.let { id -> TripMatch(id, sched.keys().asSequence().associate { it.toInt() to sched.getLong(it) }) }
        val ps = o.getJSONArray("passages")
        return TripState(
            o.getString("id"), Direction.valueOf(o.getString("direction")), o.getInt("board"), o.getLong("start"), match,
            (0 until ps.length()).map { ps.getJSONObject(it).let { p -> TripPassage(p.getInt("s"), p.getLong("t"), p.getBoolean("m")) } },
            o.getLong("end").takeIf { it >= 0 },
            shareTrack = o.optBoolean("shareTrack", false),
        )
    }
}

/**
 * Servizio in primo piano che segue la posizione durante il viaggio anche a schermo spento, con una
 * notifica fissa ("prossima: Termini") e i pulsanti "Siamo arrivati" e "Sono sceso".
 */
class TripService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TripTracker.load(this)
        when (intent?.action) {
            ACTION_MARK -> TripTracker.markNextStation(this)
            ACTION_FINISH -> { TripTracker.finish(this); return START_NOT_STICKY }
        }
        val trip = TripTracker.state.value
        if (trip == null || !trip.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, trip), type)
        } catch (e: Exception) {
            // Permesso di posizione tolto nel frattempo: il viaggio continua con le stazioni segnate a mano
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
                s.launch {
                    locationUpdates(this@TripService).collect { loc ->
                        TripTracker.onLocation(this@TripService, loc.latitude, loc.longitude,
                            if (loc.hasAccuracy()) loc.accuracy else 1000f, loc.time)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "viaggio"
        private const val NOTIFICATION_ID = 2
        const val ACTION_MARK = "it.roma.metroa.MARK"
        const val ACTION_FINISH = "it.roma.metroa.FINISH"
        @Volatile private var running = false

        fun refreshNotification(context: Context) {
            val t = TripTracker.state.value ?: return
            if (!running || !t.active || !TrainAlerts.canNotify(context)) return
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(context, t)) }
        }

        private fun notification(context: Context, t: TripState): Notification {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Viaggio in corso", NotificationManager.IMPORTANCE_LOW))
            fun action(a: String, code: Int) = PendingIntent.getService(
                context, code, Intent(context, TripService::class.java).setAction(a),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val next = t.nextStation
            val offset = t.currentOffsetS?.let { " · ${offsetText(it)}" }.orEmpty()
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Sul treno verso ${STATIONS[t.direction.terminus].name}")
                .setContentText(
                    listOfNotNull(
                        t.passages.lastOrNull()?.let { "Ultima: ${STATIONS[it.station].name}" },
                        next?.let { "prossima: ${STATIONS[it].name}" } ?: "capolinea",
                    ).joinToString(" · ").replaceFirstChar { it.uppercase() } + offset
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .apply { if (next != null) addAction(0, "Siamo a ${STATIONS[next].name}", action(ACTION_MARK, 1)) }
                .addAction(0, "Sono sceso", action(ACTION_FINISH, 2))
                .build()
        }

        private fun offsetText(s: Int): String {
            val m = (kotlin.math.abs(s) + 30) / 60
            return if (m == 0) "in orario" else if (s > 0) "+$m min" else "−$m min"
        }
    }
}
