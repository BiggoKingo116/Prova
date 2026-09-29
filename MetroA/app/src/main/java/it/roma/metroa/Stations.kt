package it.roma.metroa

import kotlin.math.*

data class Station(val name: String, val lat: Double, val lon: Double, val interchange: String? = null)

/** Stazioni della linea A, da Battistini (indice 0) ad Anagnina (indice 26).
 *  Coordinate approssimate: servono solo ad agganciare i treni alla linea. */
val STATIONS = listOf(
    Station("Battistini", 41.9064, 12.4148),
    Station("Cornelia", 41.9036, 12.4253),
    Station("Baldo degli Ubaldi", 41.9031, 12.4331),
    Station("Valle Aurelia", 41.9030, 12.4424, "FL3"),
    Station("Cipro", 41.9074, 12.4475),
    Station("Ottaviano", 41.9095, 12.4583),
    Station("Lepanto", 41.9122, 12.4661),
    Station("Flaminio", 41.9115, 12.4757, "Roma–Viterbo"),
    Station("Spagna", 41.9063, 12.4827),
    Station("Barberini", 41.9036, 12.4887),
    Station("Repubblica", 41.9023, 12.4962),
    Station("Termini", 41.9009, 12.5014, "B"),
    Station("Vittorio Emanuele", 41.8950, 12.5061),
    Station("Manzoni", 41.8894, 12.5094),
    Station("San Giovanni", 41.8858, 12.5132, "C"),
    Station("Re di Roma", 41.8820, 12.5190),
    Station("Ponte Lungo", 41.8786, 12.5232, "FL1"),
    Station("Furio Camillo", 41.8742, 12.5273),
    Station("Colli Albani", 41.8696, 12.5327),
    Station("Arco di Travertino", 41.8637, 12.5397),
    Station("Porta Furba", 41.8593, 12.5463),
    Station("Numidio Quadrato", 41.8558, 12.5527),
    Station("Lucio Sestio", 41.8527, 12.5611),
    Station("Giulio Agricola", 41.8490, 12.5673),
    Station("Subaugusta", 41.8467, 12.5727),
    Station("Cinecittà", 41.8430, 12.5795),
    Station("Anagnina", 41.8416, 12.5862),
)

val LAST = STATIONS.lastIndex

/** Minuti medi tra due stazioni (incluse le soste): ~39 min capolinea–capolinea. */
const val MIN_PER_SEGMENT = 1.5f

enum class Direction { TO_BATTISTINI, TO_ANAGNINA }

data class Projection(val index: Float, val distanceM: Double)

private const val LAT0 = 41.88
private fun xy(lat: Double, lon: Double) =
    Pair((lon - 12.5) * cos(Math.toRadians(LAT0)) * 111_320.0, (lat - LAT0) * 110_540.0)

/** Proietta un punto sulla linea: indice frazionario (es. 11.4 = tra Termini e Vittorio) e distanza in metri. */
fun projectOnLine(lat: Double, lon: Double): Projection {
    val (px, py) = xy(lat, lon)
    var best = Projection(0f, Double.MAX_VALUE)
    for (i in 0 until LAST) {
        val (ax, ay) = xy(STATIONS[i].lat, STATIONS[i].lon)
        val (bx, by) = xy(STATIONS[i + 1].lat, STATIONS[i + 1].lon)
        val dx = bx - ax; val dy = by - ay
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val d = hypot(px - (ax + t * dx), py - (ay + t * dy))
        if (d < best.distanceM) best = Projection((i + t).toFloat(), d)
    }
    return best
}

/** Direzione dedotta dalla bussola del treno rispetto al tratto di linea in cui si trova. */
fun directionFromBearing(index: Float, bearingDeg: Float): Direction {
    val i = index.toInt().coerceIn(0, LAST - 1)
    val a = STATIONS[i]; val b = STATIONS[i + 1]
    val segBearing = Math.toDegrees(atan2(
        (b.lon - a.lon) * cos(Math.toRadians(a.lat)), b.lat - a.lat)).toFloat()
    var diff = abs(bearingDeg - segBearing) % 360f
    if (diff > 180f) diff = 360f - diff
    return if (diff < 90f) Direction.TO_ANAGNINA else Direction.TO_BATTISTINI
}
