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
    val alerts: List<Alert> = emptyList(),
    /** Direzioni per cui chiedere "è così?": c'è una stima dal vivo e l'utente non ha già risposto. */
    val askConfirm: List<Direction> = emptyList(),
    val location: Where = Where.NoPermission,
    val sync: SyncStatus = SyncStatus(configured = false),
    /** Durata media delle tratte, per la linea "a distanze reali"; null finché l'orario non è caricato. */
    val segmentSeconds: FloatArray? = null,
    /** Avviso "il treno sta arrivando" impostato e non ancora passato. */
    val trainAlert: TrainAlert? = null,
)

class MetroViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ScheduleRepository(app.filesDir, app.cacheDir)
    private val db = ReportDb(app)
    private val prefs = app.getSharedPreferences("metroa", 0)
    private val api = reportApiOrNull()
    private val deviceId: String = deviceId(app)

    @Volatile private var schedule: Schedule? = null
    @Volatile private var model = DelayModel(emptyList())
    private var loadJob: Job? = null
    private val syncLock = Mutex()

    /** Quando l'utente ha segnalato o risposto per ultimo, per direzione: non richiedere conferma subito dopo. */
    private val answeredAt = java.util.concurrent.ConcurrentHashMap<Direction, Long>()

    private val _state = MutableStateFlow(UiState(sync = SyncStatus(configured = api != null)))
    val state: StateFlow<UiState> = _state

    private val settingsStore = SettingsStore(app.getSharedPreferences("settings", 0))
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        val old = _settings.value
        val new = change(old)
        _settings.value = new
        settingsStore.save(new)
        if (new.useLocation != old.useLocation) startLocation()
        if (new.favorites != old.favorites) updateWidget()
    }

    /** Viaggio "sono sul treno" in corso o appena concluso (riepilogo). */
    val trip: StateFlow<TripState?> = TripTracker.state

    init {
        app.deleteDatabase("observations.db") // registro locale delle versioni precedenti, sostituito da reports.db
        TripTracker.load(app)
        load()
        viewModelScope.launch { reloadReports() }
        // Ogni stazione registrata nel viaggio è una segnalazione: aggiorna le stime e sincronizza
        viewModelScope.launch {
            var passages = -1
            trip.collect { t ->
                val n = t?.passages?.size ?: 0
                if (n != passages) { passages = n; reloadReports(); sync() }
            }
        }
    }

    fun startTrip(dir: Direction, boardStation: Int) = TripTracker.start(getApplication(), dir, boardStation)
    fun markNextStation() = TripTracker.markNextStation(getApplication())
    fun finishTrip() = TripTracker.finish(getApplication())
    fun dismissTrip() = TripTracker.dismiss(getApplication())

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
        _state.update { it.copy(scheduleDownloadedAtMs = s.downloadedAtMs, segmentSeconds = s.segmentSeconds) }
        refresh()
    }

    /**
     * Finché l'app è in primo piano: posizioni dei treni ogni secondo (scorrono in modo continuo),
     * server ogni minuto, posizione del telefono di continuo.
     */
    suspend fun poll() {
        startLocation()
        try {
            var tick = 0
            while (true) {
                if (tick++ % 60 == 0) { sync(); updateWidget() }
                refresh()
                delay(1_000)
            }
        } finally {
            locationJob?.cancel() // app in background: niente GPS
        }
    }

    private var locationJob: Job? = null

    /** (Ri)avvia la ricerca della stazione vicina: all'apertura dell'app e dopo la risposta sul permesso. */
    fun startLocation() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch { trackLocation() }
    }

    private suspend fun trackLocation() {
        val app = getApplication<Application>()
        when {
            !_settings.value.useLocation -> return _state.update { it.copy(location = Where.Disabled) }
            !hasLocationPermission(app) -> return _state.update { it.copy(location = Where.NoPermission) }
            !isLocationOn(app) -> return _state.update { it.copy(location = Where.Off) }
        }
        val precise = hasPreciseLocation(app)
        _state.update { if (it.location is Where.Found) it else it.copy(location = Where.Searching) }
        var best: android.location.Location? = null
        locationUpdates(app).collect { loc ->
            val acc = if (loc.hasAccuracy()) loc.accuracy else 100f
            if (!isBetterFix(loc.time, acc, best?.time, best?.let { if (it.hasAccuracy()) it.accuracy else 100f })) return@collect
            best = loc
            _state.update { it.copy(location = whereFrom(loc.latitude, loc.longitude, acc, precise)) }
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
        val ask = Direction.entries.filter { d ->
            delays.getValue(d).live && (answeredAt[d]?.let { nowMs - it > LIVE_WINDOW_MS } ?: true)
        }
        val alert = TrainAlerts.current(getApplication())
        _state.update {
            it.copy(
                trains = trains, delays = delays, recentReports = recent, alerts = model.alerts(nowMs),
                askConfirm = ask, trainAlert = alert,
            )
        }
    }

    /** Prossimo treno per [dir] in ogni stazione, per i minuti scritti accanto ai nomi sulla linea. */
    fun nextAtEveryStation(dir: Direction): List<Arrival?> {
        val now = ZonedDateTime.now(ROME)
        return schedule?.nextAtEveryStation(dir, now, delayFor(dir, now).medianS) ?: List(STATIONS.size) { null }
    }

    private fun updateWidget() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { NextTrainsWidget.updateAll(getApplication()) } }
    }

    /**
     * Imposta l'avviso per il primo treno verso [dir] in [station] che arriva fra più di
     * [AppSettings.alertLeadMinutes] minuti; suona quei minuti prima. Null se non ci sono treni adatti.
     */
    fun setTrainAlert(station: Int, dir: Direction): TrainAlert? {
        val s = schedule ?: return null
        val now = ZonedDateTime.now(ROME)
        val leadMs = _settings.value.alertLeadMinutes * 60_000L
        val nowMs = now.toInstant().toEpochMilli()
        val trainAt = s.nextArrivalTimes(station, dir, now, delayFor(dir, now).medianS, count = 10)
            .firstOrNull { it - nowMs >= leadMs + 30_000 } ?: return null
        val alert = TrainAlert(station, dir, trainAt, trainAt - leadMs)
        TrainAlerts.set(getApplication(), alert)
        _state.update { it.copy(trainAlert = alert) }
        return alert
    }

    fun cancelTrainAlert() {
        TrainAlerts.cancel(getApplication())
        _state.update { it.copy(trainAlert = null) }
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

    /**
     * Conferma con un tocco (o smentita, che vale "in orario") della stima dal vivo di [dir].
     * La stazione è quella vicina se nota, altrimenti quella della segnalazione più recente.
     */
    fun answerLive(dir: Direction, confirm: Boolean) {
        val now = System.currentTimeMillis()
        val estimate = model.forDirection(dir, now, ZonedDateTime.now(ROME).hour)
        val station = _state.value.location.station
            ?: model.recent(now).filter { it.direction == dir }.maxByOrNull { it.timeMs }?.station
            ?: return
        if (confirm) submit(station, dir, estimate.medianS, ReportSource.CONFIRM)
        else submit(station, dir, 0, ReportSource.DENY)
    }

    private var lastSubmitted: Report? = null

    private fun submit(station: Int, dir: Direction, offsetS: Int, source: ReportSource) {
        val r = Report(UUID.randomUUID().toString(), System.currentTimeMillis(), station, dir, offsetS, source)
        // Doppio tocco sullo stesso treno: il server lo rifiuterebbe comunque (30 s per stazione e direzione)
        val prev = lastSubmitted
        if (prev != null && prev.station == station && prev.direction == dir && r.timeMs - prev.timeMs < 30_000) return
        lastSubmitted = r
        answeredAt[dir] = r.timeMs
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
            uploadPending(db, api, deviceId)
            val since = System.currentTimeMillis() - 60L * 24 * 3600 * 1000
            var seq = prefs.getLong("last_seq", 0)
            while (true) {
                val (page, maxSeq) = api.fetch(seq, since)
                db.save(page, uploaded = true)
                seq = maxSeq
                if (page.size < 1000) break
            }
            prefs.edit().putLong("last_seq", seq).apply()
            // Ogni 10 minuti: togliere dal telefono le segnalazioni cancellate sul server
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_reconcile", 0) > 10 * 60_000) {
                db.deleteMissing(api.fetchIds(since), since)
                prefs.edit().putLong("last_reconcile", now).apply()
            }
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
