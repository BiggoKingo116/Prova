package it.roma.metroa

import kotlin.math.*

data class Station(val name: String, val lat: Double, val lon: Double, val interchange: String? = null)

/** Stazioni della linea A, da Battistini (indice 0) ad Anagnina (indice 26).
 *  Coordinate dal GTFS di Roma Mobilità (media delle due banchine). */
val STATIONS = listOf(
    Station("Battistini", 41.9063, 12.4149),
    Station("Cornelia", 41.9006, 12.4264),
    Station("Baldo degli Ubaldi", 41.8992, 12.4345),
    Station("Valle Aurelia", 41.9028, 12.4413, "FL3"),
    Station("Cipro", 41.9075, 12.4474),
    Station("Ottaviano", 41.9094, 12.4581),
    Station("Lepanto", 41.9114, 12.4661),
    Station("Flaminio", 41.9127, 12.4764, "Roma–Viterbo"),
    Station("Spagna", 41.9079, 12.4824),
    Station("Barberini", 41.9037, 12.4887),
    Station("Repubblica", 41.9027, 12.4963),
    Station("Termini", 41.9010, 12.5000, "B"),
    Station("Vittorio Emanuele", 41.8945, 12.5044),
    Station("Manzoni", 41.8906, 12.5065),
    Station("San Giovanni", 41.8856, 12.5095, "C"),
    Station("Re di Roma", 41.8818, 12.5141),
    Station("Ponte Lungo", 41.8777, 12.5190, "FL1"),
    Station("Furio Camillo", 41.8747, 12.5230),
    Station("Colli Albani", 41.8697, 12.5294),
    Station("Arco di Travertino", 41.8660, 12.5360),
    Station("Porta Furba", 41.8638, 12.5481),
    Station("Numidio Quadrato", 41.8620, 12.5526),
    Station("Lucio Sestio", 41.8597, 12.5572),
    Station("Giulio Agricola", 41.8566, 12.5627),
    Station("Subaugusta", 41.8536, 12.5681),
    Station("Cinecittà", 41.8496, 12.5740),
    Station("Anagnina", 41.8429, 12.5858),
)

val LAST = STATIONS.lastIndex

enum class Direction { TO_BATTISTINI, TO_ANAGNINA }

/** Indice della stazione più vicina al punto, o null se è più lontana di [maxM] metri. */
fun nearestStation(lat: Double, lon: Double, maxM: Double = 300.0): Int? {
    val kx = cos(Math.toRadians(lat)) * 111_320.0
    return STATIONS.indices
        .map { it to hypot((lon - STATIONS[it].lon) * kx, (lat - STATIONS[it].lat) * 110_540.0) }
        .minBy { it.second }
        .takeIf { it.second <= maxM }?.first
}
