package it.roma.metroa

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

data class Train(val id: String, val position: Float, val direction: Direction?)

data class UiState(
    val trains: List<Train> = emptyList(),
    val feedTimeMs: Long? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** route_id dei mezzi vicini alla linea: utile se METRO_A_ROUTE_IDS è sbagliato. */
    val routesNearLine: Map<String, Int> = emptyMap(),
)

class MetroViewModel : ViewModel() {
    private val repo = FeedRepository()
    private val lastPos = mutableMapOf<String, Float>()
    private val lastDir = mutableMapOf<String, Direction>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Aggiorna ogni 15 secondi finché l'app è in primo piano. */
    suspend fun poll() {
        while (true) {
            refresh()
            delay(15_000)
        }
    }

    private suspend fun refresh() {
        try {
            val feed = repo.fetch()
            val trains = mutableListOf<Train>()
            val nearby = mutableMapOf<String, Int>()

            for (entity in feed.entityList) {
                if (!entity.hasVehicle()) continue
                val v = entity.vehicle
                if (!v.hasPosition()) continue
                val routeId = v.trip.routeId
                val proj = projectOnLine(v.position.latitude.toDouble(), v.position.longitude.toDouble())
                if (proj.distanceM < 120) nearby[routeId] = (nearby[routeId] ?: 0) + 1
                if (routeId !in METRO_A_ROUTE_IDS || proj.distanceM > 400) continue

                val id = v.vehicle.id.ifEmpty { entity.id }
                val prev = lastPos[id]
                var dir = lastDir[id]
                if (prev != null && abs(proj.index - prev) > 0.05f) {
                    dir = if (proj.index > prev) Direction.TO_ANAGNINA else Direction.TO_BATTISTINI
                }
                if (dir == null && v.position.hasBearing()) {
                    dir = directionFromBearing(proj.index, v.position.bearing)
                }
                if (dir == null && proj.index < 0.1f) dir = Direction.TO_ANAGNINA
                if (dir == null && proj.index > LAST - 0.1f) dir = Direction.TO_BATTISTINI

                lastPos[id] = proj.index
                dir?.let { lastDir[id] = it }
                trains += Train(id, proj.index, dir)
            }

            val active = trains.map { it.id }.toSet()
            lastPos.keys.retainAll(active); lastDir.keys.retainAll(active)

            _state.value = UiState(
                trains = trains,
                feedTimeMs = if (feed.header.hasTimestamp()) feed.header.timestamp * 1000 else System.currentTimeMillis(),
                loading = false,
                routesNearLine = nearby,
            )
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Errore di rete") }
        }
    }
}

/** Minuti stimati all'arrivo in una stazione, dalla posizione reale del treno. */
fun etasFor(station: Int, dir: Direction, trains: List<Train>): List<Int> =
    trains.filter { it.direction == dir }
        .mapNotNull { t ->
            val gap = if (dir == Direction.TO_ANAGNINA) station - t.position else t.position - station
            if (gap >= -0.1f) Math.round(gap.coerceAtLeast(0f) * MIN_PER_SEGMENT) else null
        }
        .sorted()
        .take(3)
