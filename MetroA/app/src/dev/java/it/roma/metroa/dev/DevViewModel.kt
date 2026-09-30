package it.roma.metroa.dev

import android.app.Application
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.roma.metroa.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

data class ReportRow(
    val id: String, val seq: Long, val timeMs: Long, val station: Int, val direction: Direction,
    val offsetS: Int, val source: String, val deviceId: String?, val tripId: String?,
)

/** Un viaggio "sono sul treno": le sue stazioni registrate, dalla prima all'ultima. */
data class TripRow(val tripId: String, val direction: Direction, val stations: List<Int>, val firstMs: Long, val lastMs: Long)

data class SegmentRow(val direction: Direction, val from: Int, val to: Int, val avgS: Int, val trips: Int)

/** Percorso GPS condiviso di un viaggio. */
data class PointTrip(val tripId: String, val count: Int, val firstMs: Long, val lastMs: Long, val deviceId: String?)

data class TrainNumberRow(
    val id: String, val timeMs: Long, val station: Int, val direction: Direction,
    val number: String, val label: String?, val note: String?,
)

/** Una posizione ricevuta, con quello che l'app ne ha dedotto: per capire perché sbaglia stazione. */
data class GpsFix(
    val receivedMs: Long, val fixMs: Long, val provider: String, val lat: Double, val lon: Double, val accuracyM: Float,
    val where: Where.Found, val accepted: Boolean,
)

data class DevState(
    val email: String? = null,
    /** null finché non si sa; false: l'account esiste ma non è nella tabella developers. */
    val isDeveloper: Boolean? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val scheduleReady: Boolean = false,
    val scheduleProgress: Progress? = null,
    val trainNumbers: List<TrainNumberRow> = emptyList(),
    val reports: List<ReportRow> = emptyList(),
    val trips: List<TripRow> = emptyList(),
    val segments: List<SegmentRow> = emptyList(),
    val pointTrips: List<PointTrip> = emptyList(),
    val gps: List<GpsFix> = emptyList(),
    val providers: Map<String, Boolean> = emptyMap(),
)

class DevViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("dev", 0)
    private val api = DevApi(BuildConfig.SUPABASE_URL.trim().trimEnd('/').removeSuffix("/rest/v1"), BuildConfig.SUPABASE_ANON_KEY)
    private val repo = ScheduleRepository(app.filesDir, app.cacheDir)
    @Volatile private var schedule: Schedule? = null

    private val _state = MutableStateFlow(DevState())
    val state: StateFlow<DevState> = _state

    init {
        api.restore(loadSession())
        api.onSessionChange = ::saveSession
        _state.update { it.copy(email = api.session?.email) }
        if (api.session != null) checkDeveloper()
        loadSchedule()
    }

    // ---- Accesso ----

    fun login(email: String, password: String) = io("Accesso…") {
        api.login(email, password)
        _state.update { it.copy(email = api.session?.email) }
        checkDeveloperNow()
    }

    fun logout() {
        api.logout()
        _state.value = DevState(scheduleReady = schedule != null)
    }

    private fun checkDeveloper() = io(null) { checkDeveloperNow() }

    private fun checkDeveloperNow() {
        val dev = api.isDeveloper()
        _state.update { it.copy(isDeveloper = dev) }
        if (dev) { loadTrainNumbersNow(); loadDataNow() }
    }

    private fun saveSession(s: Session?) {
        prefs.edit().apply {
            if (s == null) clear() else putString("session", JSONObject()
                .put("userId", s.userId).put("email", s.email).put("access", s.accessToken)
                .put("refresh", s.refreshToken).put("expires", s.expiresAtMs).toString())
        }.apply()
    }

    private fun loadSession(): Session? = runCatching {
        val o = JSONObject(prefs.getString("session", null) ?: return null)
        Session(o.getString("userId"), o.getString("email"), o.getString("access"), o.getString("refresh"), o.getLong("expires"))
    }.getOrNull()

    // ---- Orario (serve a riconoscere la corsa a cui dare il numero) ----

    fun loadSchedule() {
        viewModelScope.launch {
            val cached = repo.loadCached()
            if (cached != null && !repo.needsRefresh(cached, ZonedDateTime.now(ROME).toLocalDate())) {
                schedule = cached
                _state.update { it.copy(scheduleReady = true) }
                return@launch
            }
            runCatching { repo.download { p -> _state.update { it.copy(scheduleProgress = p) } } }
                .onSuccess { schedule = it }
                .onFailure { e -> if (cached != null) schedule = cached else message("Orario non disponibile: ${e.message}") }
            _state.update { it.copy(scheduleReady = schedule != null, scheduleProgress = null) }
        }
    }

    /** I treni dell'orario vicini alla stazione, dal più vicino ad adesso. */
    fun trainsAt(station: Int, dir: Direction): List<TrainAtStation> {
        val now = ZonedDateTime.now(ROME)
        val nowMs = now.toInstant().toEpochMilli()
        return schedule?.trainsAt(station, dir, now, beforeS = 600, afterS = 900).orEmpty()
            .sortedBy { kotlin.math.abs(it.atStationMs - nowMs) }
    }

    // ---- Numeri dei treni ----

    fun saveTrainNumber(station: Int, dir: Direction, train: TrainAtStation?, number: String, note: String) = io("Salvo…") {
        val row = JSONObject()
            .put("time_ms", System.currentTimeMillis()).put("station", station).put("direction", dir.name)
            .put("train_number", number.trim())
        train?.let { row.put("scheduled_trip", it.tripId).put("scheduled_label", it.label) }
        note.trim().takeIf { it.isNotEmpty() }?.let { row.put("note", it.take(200)) }
        api.insert("train_numbers", row)
        message("Treno ${number.trim()} salvato")
        loadTrainNumbersNow()
    }

    fun deleteTrainNumber(id: String) = io("Cancello…") {
        api.delete("train_numbers", mapOf("id" to "eq.$id"))
        loadTrainNumbersNow()
    }

    fun loadTrainNumbers() = io(null) { loadTrainNumbersNow() }

    private fun loadTrainNumbersNow() {
        val arr = api.select("train_numbers", mapOf("select" to "*", "order" to "time_ms.desc", "limit" to "200"))
        _state.update { s -> s.copy(trainNumbers = arr.objects().mapNotNull { o ->
            runCatching {
                TrainNumberRow(o.getString("id"), o.getLong("time_ms"), o.getInt("station"), Direction.valueOf(o.getString("direction")),
                    o.getString("train_number"), o.optStringOrNull("scheduled_label"), o.optStringOrNull("note"))
            }.getOrNull()
        }) }
    }

    // ---- Dati del database ----

    fun loadData() = io("Carico i dati…") { loadDataNow() }

    private fun loadDataNow() {
        val reports = api.select("reports", mapOf("select" to "*", "order" to "seq.desc", "limit" to "500")).objects().mapNotNull { o ->
            runCatching {
                ReportRow(o.getString("id"), o.getLong("seq"), o.getLong("time_ms"), o.getInt("station"),
                    Direction.valueOf(o.getString("direction")), o.getInt("offset_s"), o.getString("source"),
                    o.optStringOrNull("device_id"), o.optStringOrNull("trip_id"))
            }.getOrNull()
        }
        val trips = reports.filter { it.tripId != null }.groupBy { it.tripId!! }.map { (id, rs) ->
            val sorted = rs.sortedBy { it.timeMs }
            TripRow(id, sorted.first().direction, sorted.map { it.station }, sorted.first().timeMs, sorted.last().timeMs)
        }.sortedByDescending { it.lastMs }
        val segments = api.select("segment_times", mapOf("select" to "*")).objects().mapNotNull { o ->
            runCatching {
                SegmentRow(Direction.valueOf(o.getString("direction")), o.getInt("from_station"), o.getInt("to_station"),
                    o.getInt("avg_seconds"), o.getInt("trips"))
            }.getOrNull()
        }.sortedWith(compareBy({ it.direction }, { if (it.direction == Direction.TO_ANAGNINA) it.from else -it.from }))
        val points = api.select("trip_points", mapOf("select" to "trip_id,time_ms,device_id", "order" to "time_ms.desc", "limit" to "5000"))
            .objects().groupBy { it.getString("trip_id") }.map { (id, ps) ->
                PointTrip(id, ps.size, ps.minOf { it.getLong("time_ms") }, ps.maxOf { it.getLong("time_ms") }, ps.first().optStringOrNull("device_id"))
            }.sortedByDescending { it.lastMs }
        _state.update { it.copy(reports = reports, trips = trips, segments = segments, pointTrips = points) }
    }

    fun deleteReport(id: String) = io("Cancello…") {
        api.delete("reports", mapOf("id" to "eq.$id"))
        loadDataNow()
    }

    /** Tutte le segnalazioni di un telefono: contro lo spam. */
    fun deleteDevice(deviceId: String) = io("Cancello…") {
        api.delete("reports", mapOf("device_id" to "eq.$deviceId"))
        api.delete("trip_points", mapOf("device_id" to "eq.$deviceId"))
        loadDataNow()
    }

    /** Un viaggio intero: le sue stazioni e il suo percorso GPS. */
    fun deleteTrip(tripId: String) = io("Cancello…") {
        api.delete("reports", mapOf("trip_id" to "eq.$tripId"))
        api.delete("trip_points", mapOf("trip_id" to "eq.$tripId"))
        loadDataNow()
    }

    // ---- Diagnostica GPS ----

    private var gpsJob: Job? = null

    fun startGps() {
        if (gpsJob?.isActive == true) return
        val app = getApplication<Application>()
        val lm = app.getSystemService(LocationManager::class.java)
        _state.update { s -> s.copy(providers = lm.allProviders.associateWith { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }) }
        var best: GpsFix? = null
        gpsJob = viewModelScope.launch {
            locationUpdates(app).collect { loc ->
                val acc = if (loc.hasAccuracy()) loc.accuracy else 1000f
                val accepted = isBetterFix(loc.time, acc, best?.fixMs, best?.accuracyM)
                val fix = GpsFix(System.currentTimeMillis(), loc.time, loc.provider ?: "?", loc.latitude, loc.longitude, acc,
                    whereFrom(loc.latitude, loc.longitude, acc, hasPreciseLocation(app)), accepted)
                if (accepted) best = fix
                _state.update { it.copy(gps = (listOf(fix) + it.gps).take(50)) }
            }
        }
    }

    fun stopGps() { gpsJob?.cancel(); gpsJob = null }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun message(m: String) = _state.update { it.copy(message = m) }

    /** Lavoro di rete in background, con un messaggio d'errore leggibile se va male. */
    private fun io(busyLabel: String?, block: () -> Unit) {
        viewModelScope.launch {
            if (busyLabel != null) _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                message(e.message ?: "Errore")
                if ((e as? DevApiException)?.code == 401) logout()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
