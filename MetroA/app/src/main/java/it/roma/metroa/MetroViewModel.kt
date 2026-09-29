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
import java.time.ZonedDateTime

data class UiState(
    val trains: List<Train> = emptyList(),
    /** Scaricamento/elaborazione dell'orario in corso. */
    val progress: Progress? = null,
    val error: String? = null,
    val scheduleDownloadedAtMs: Long? = null,
    /** Passaggi reali registrati (ultimi 60 giorni). */
    val observationCount: Int = 0,
)

/** Arrivi di una stazione in una direzione, con la stima di ritardo usata per calcolarli. */
data class StationArrivals(val etas: List<Eta>, val delay: DelayEstimate, val trainAtStation: Boolean)

class MetroViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ScheduleRepository(app.filesDir, app.cacheDir)
    private val db = ObservationDb(app)
    @Volatile private var schedule: Schedule? = null
    @Volatile private var delays = DelayModel(emptyList())
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        load()
        viewModelScope.launch(Dispatchers.IO) { setDelays(DelayModel(db.recent())) }
    }

    private fun setDelays(model: DelayModel) {
        delays = model
        _state.update { it.copy(observationCount = model.observations.size) }
        refresh()
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

    /** Ricalcola le posizioni ogni 3 secondi finché l'app è in primo piano (le soste durano ~25 s). */
    suspend fun poll() {
        while (true) {
            refresh()
            delay(3_000)
        }
    }

    private fun refresh() {
        val s = schedule ?: return
        val now = ZonedDateTime.now(ROME)
        val model = delays
        _state.update { it.copy(trains = s.trainsAt(now) { dir -> model.forLine(dir, now.hour).medianS }) }
    }

    fun arrivals(station: Int, dir: Direction): StationArrivals {
        val now = ZonedDateTime.now(ROME)
        val delay = delays.forStation(station, dir, now.hour)
        val passages = schedule?.passages(station, dir, now) ?: emptyList()
        val atStation = _state.value.trains.any { it.stopped && it.direction == dir && it.position.toInt() == station }
        return StationArrivals(etas(passages, delay), delay, atStation)
    }

    /**
     * Segna che un treno è passato adesso in [station] verso [dir]: salva lo scarto dal passaggio programmato
     * più vicino. Restituisce lo scarto in secondi, o null se non c'è un passaggio programmato entro 10 minuti.
     */
    fun recordArrival(station: Int, dir: Direction): Int? {
        val now = ZonedDateTime.now(ROME)
        val offset = schedule?.offsetFromNearest(station, dir, now) ?: return null
        val o = Observation(now.toInstant().toEpochMilli(), station, dir, offset)
        // Doppio tocco sullo stesso treno: non contarlo due volte
        val prev = delays.observations.lastOrNull()
        if (prev != null && prev.station == station && prev.direction == dir && o.timeMs - prev.timeMs < 60_000) return prev.offsetS
        setDelays(delays + o)
        viewModelScope.launch(Dispatchers.IO) { db.add(o) }
        return offset
    }

    fun clearObservations() {
        setDelays(DelayModel(emptyList()))
        viewModelScope.launch(Dispatchers.IO) { db.clear() }
    }

    override fun onCleared() = db.close()
}
