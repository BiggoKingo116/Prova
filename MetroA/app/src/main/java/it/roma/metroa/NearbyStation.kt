package it.roma.metroa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Stazione vicina: [distanceM] metri dalla posizione del telefono. */
data class Nearby(val station: Int, val distanceM: Int)

/** Oltre questa distanza non si è "in" una stazione; la posizione resta sul telefono e non viene mai inviata. */
const val NEARBY_MAX_M = 500.0

fun hasLocationPermission(context: Context) = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Stazione più vicina entro [NEARBY_MAX_M], o null. */
fun nearbyStation(loc: Location): Nearby? {
    if (loc.hasAccuracy() && loc.accuracy > 1000) return null // posizione troppo vaga
    val i = nearestStation(loc.latitude, loc.longitude, NEARBY_MAX_M) ?: return null
    val d = FloatArray(1)
    Location.distanceBetween(loc.latitude, loc.longitude, STATIONS[i].lat, STATIONS[i].lon, d)
    return Nearby(i, d[0].toInt())
}

/** Posizione attuale: fresca se arriva entro 10 secondi, altrimenti l'ultima nota se ha meno di 5 minuti. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    if (!hasLocationPermission(context)) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull() ?: return null

    val fresh = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, provider, cancel, ContextCompat.getMainExecutor(context)) {
                if (cont.isActive) cont.resume(it)
            }
        }
    }
    if (fresh != null) return fresh
    return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
        .maxByOrNull { it.time }
}
