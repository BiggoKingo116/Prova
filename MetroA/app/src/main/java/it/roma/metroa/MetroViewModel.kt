package it.roma.metroa

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
)

class MetroViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ScheduleRepository(app.filesDir, app.cacheDir)
    @Volatile private var schedule: Schedule? = null
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load() }

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

    /** Ricalcola le posizioni ogni 5 secondi finché l'app è in primo piano. */
    suspend fun poll() {
        while (true) {
            refresh()
            delay(5_000)
        }
    }

    private fun refresh() {
        val s = schedule ?: return
        _state.update { it.copy(trains = s.trainsAt(ZonedDateTime.now(ROME))) }
    }

    fun arrivals(station: Int, dir: Direction): List<Int> =
        schedule?.arrivals(station, dir, ZonedDateTime.now(ROME)) ?: emptyList()
}
