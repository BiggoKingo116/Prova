package it.roma.metroa

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime
import java.util.UUID

/** Stato del collegamento col database condiviso. */
data class SyncStatus(
    /** false se l'app è stata compilata senza indirizzo del server: le segnalazioni restano sul telefono. */
    val configured: Boolean,
    val pending: Int = 0,
    val lastSyncMs: Long? = null,
    val error: String? = null,
)

data class UiState(
    val trains: List<Train> = emptyList(),
    /** Scaricamento/elaborazione dell'orario in corso. */
    val progress: Progress? = null,
    val error: String? = null,
    val scheduleDownloadedAtMs: Long? = null,
    val delays: Map<Direction, DelayEstimate> = emptyMap(),
    /** Segnalazioni degli ultimi 20 minuti, di tutti, dalla più recente. */
    val recentReports: List<Report> = emptyList(),
    val sync: SyncStatus = SyncStatus(configured = false),
)

class MetroViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ScheduleRepository(app.filesDir, app.cacheDir)
    private val db = ReportDb(app)
    private val prefs = app.getSharedPreferences("metroa", 0)
    private val api = BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() }
        ?.let { ReportApi(it.trimEnd('/'), BuildConfig.SUPABASE_ANON_KEY) }

    /** Identificativo anonimo del telefono, usato dal server solo per limitare lo spam. */
    private val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    @Volatile private var schedule: Schedule? = null
    @Volatile private var model = DelayModel(emptyList())
    private var loadJob: Job? = null
    private val syncLock = Mutex()

    private val _state = MutableStateFlow(UiState(sync = SyncStatus(configured = api != null)))
    val state: StateFlow<UiState> = _state

    init {
        load()
        viewModelScope.launch { reloadReports() }
    }

    /** Carica l'orario salvato e, se è vecchio o assente, riscarica il GTFS. */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(error = null) }
            val cached = schedule ?: repo.loadCached()
            if (cached != null) use(cached)
            if (!repo.needsRefresh(cached, ZonedDateTime.now(ROME).toLocalDate())) return@launch
            try {
                use(repo.download { p -> _state.update { it.copy(progress = p) } })
            } catch (e: Exception) {
                // Con un orario già salvato si continua a usare quello
                _state.update {
                    it.copy(error = if (cached == null) e.message ?: "Errore di rete" else null)
                }
            } finally {
                _state.update { it.copy(progress = null) }
            }
        }
    }

    private fun use(s: Schedule) {
        schedule = s
        _state.update { it.copy(scheduleDownloadedAtMs = s.downloadedAtMs) }
        refresh()
    }

    /** Finché l'app è in primo piano: posizioni ogni 3 secondi (le soste durano ~25 s), server ogni minuto. */
    suspend fun poll() {
        var tick = 0
        while (true) {
            if (tick++ % 20 == 0) sync()
            refresh()
            delay(3_000)
        }
    }

    private fun delayFor(dir: Direction, now: ZonedDateTime) =
        model.forDirection(dir, now.toInstant().toEpochMilli(), now.hour)

    private fun refresh() {
        val now = ZonedDateTime.now(ROME)
        val nowMs = now.toInstant().toEpochMilli()
        val delays = Direction.entries.associateWith { delayFor(it, now) }
        val trains = schedule?.trainsAt(now) { delays.getValue(it).medianS } ?: emptyList()
        val recent = model.recent(nowMs).sortedByDescending { it.timeMs }
        _state.update { it.copy(trains = trains, delays = delays, recentReports = recent) }
    }

    fun arrivals(station: Int, dir: Direction): List<Arrival> {
        val now = ZonedDateTime.now(ROME)
        val passages = schedule?.passages(station, dir, now) ?: return emptyList()
        return board(passages, delayFor(dir, now).medianS)
    }

    /**
     * "Il treno è arrivato adesso": lo scarto si calcola dal passaggio programmato più vicino.
     * Restituisce lo scarto in secondi, o null se entro 10 minuti non c'è un passaggio in orario.
     */
    fun reportArrivedNow(station: Int, dir: Direction): Int? {
        val offset = schedule?.offsetFromNearest(station, dir, ZonedDateTime.now(ROME)) ?: return null
        submit(station, dir, offset, ReportSource.ARRIVAL)
        return offset
    }

    /** Segnalazione a mano: [offsetS] positivo = treni in ritardo, negativo = in anticipo, 0 = in orario. */
    fun reportManual(station: Int, dir: Direction, offsetS: Int) = submit(station, dir, offsetS, ReportSource.MANUAL)

    private var lastSubmitted: Report? = null

    private fun submit(station: Int, dir: Direction, offsetS: Int, source: ReportSource) {
        val r = Report(UUID.randomUUID().toString(), System.currentTimeMillis(), station, dir, offsetS, source)
        // Doppio tocco sullo stesso treno: il server lo rifiuterebbe comunque (30 s per stazione e direzione)
        val prev = lastSubmitted
        if (prev != null && prev.station == station && prev.direction == dir && r.timeMs - prev.timeMs < 30_000) return
        lastSubmitted = r
        model = DelayModel(model.reports + r)
        refresh()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.save(listOf(r), uploaded = false) }
            sync()
        }
    }

    /** Invia le segnalazioni in attesa e scarica quelle nuove degli altri (una sincronizzazione alla volta). */
    fun sync() {
        if (!syncLock.tryLock()) return
        viewModelScope.launch {
            try {
                syncNow()
            } finally {
                syncLock.unlock()
            }
        }
    }

    private suspend fun syncNow() = withContext(Dispatchers.IO) {
        val api = api
        if (api != null) try {
            for (r in db.pending()) {
                val res = api.upload(r, deviceId)
                // Rifiutata dal server (doppia, fuori limiti): resta sul telefono ma non si ritenta
                if (res.ok || res.permanent) db.markUploaded(listOf(r.id)) else throw IOException("Invio non riuscito")
            }
            val since = System.currentTimeMillis() - 60L * 24 * 3600 * 1000
            var seq = prefs.getLong("last_seq", 0)
            while (true) {
                val (page, maxSeq) = api.fetch(seq, since)
                db.save(page, uploaded = true)
                seq = maxSeq
                if (page.size < 1000) break
            }
            prefs.edit().putLong("last_seq", seq).apply()
            _state.update { it.copy(sync = it.sync.copy(lastSyncMs = System.currentTimeMillis(), error = null)) }
        } catch (e: Exception) {
            _state.update { it.copy(sync = it.sync.copy(error = e.message ?: "Server non raggiungibile")) }
        }
        reloadReports()
    }

    private suspend fun reloadReports() = withContext(Dispatchers.IO) {
        val reports = db.recent()
        val pending = db.pending().size
        model = DelayModel(reports)
        _state.update { it.copy(sync = it.sync.copy(pending = pending)) }
        refresh()
    }

    override fun onCleared() = db.close()
}
