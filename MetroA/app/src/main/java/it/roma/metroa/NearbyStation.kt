package it.roma.metroa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Entro questa distanza si è "in" stazione; la posizione resta sul telefono se l'utente non sceglie di condividerla. */
private const val NEARBY_MAX_M = 350
/** Oltre questo errore dichiarato (tipico della sola rete cellulare in galleria) non si dice "sei a …". */
const val PRECISE_ENOUGH_M = 250

/** Dove si trova il telefono rispetto alla linea. */
sealed interface Where {
    /** L'utente ha spento l'uso della posizione nelle impostazioni dell'app. */
    data object Disabled : Where
    /** Permesso di localizzazione non dato. */
    data object NoPermission : Where
    /** Localizzazione spenta nelle impostazioni del telefono. */
    data object Off : Where
    /** In attesa della prima posizione. */
    data object Searching : Where

    /** Stazione più vicina [station] a [distanceM] metri; [precise] false se l'utente ha concesso solo la posizione approssimativa. */
    data class Found(val station: Int, val distanceM: Int, val accuracyM: Int, val precise: Boolean) : Where {
        val atStation: Boolean get() = distanceM <= NEARBY_MAX_M && accuracyM <= PRECISE_ENOUGH_M
    }
}

/** La stazione in cui ci si trova, se si è entro [NEARBY_MAX_M]. */
val Where.station: Int? get() = (this as? Where.Found)?.takeIf { it.atStation }?.station

fun whereFrom(lat: Double, lon: Double, accuracyM: Float, precise: Boolean): Where.Found {
    val i = nearestStation(lat, lon, maxM = Double.POSITIVE_INFINITY)!!
    return Where.Found(i, distanceToStation(lat, lon, i).toInt(), accuracyM.toInt(), precise)
}

/**
 * Se una nuova posizione è migliore di quella che si ha. Una più precisa vince sempre; una più recente
 * vince se non è molto più vaga, altrimenti solo quando la vecchia ha più di 2 minuti: in galleria una
 * posizione di rete sbagliata di centinaia di metri non deve sostituire un buon GPS di poco prima.
 */
fun isBetterFix(newTimeMs: Long, newAccM: Float, oldTimeMs: Long?, oldAccM: Float?): Boolean {
    if (oldTimeMs == null || oldAccM == null) return true
    val dt = newTimeMs - oldTimeMs
    return when {
        dt < -30_000 -> false
        newAccM <= oldAccM -> true
        dt > 120_000 -> true
        dt > 0 && newAccM <= maxOf(oldAccM * 2, oldAccM + 50) -> true
        else -> false
    }
}

fun hasLocationPermission(context: Context) = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
    hasPreciseLocation(context)

fun hasPreciseLocation(context: Context) = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermission(context: Context, p: String) =
    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

fun isLocationOn(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } == true

/**
 * Posizioni del telefono finché qualcuno le raccoglie: prima le ultime note (se recenti), poi gli
 * aggiornamenti da tutte le fonti attive (GPS, rete, "fused"), ogni 5 secondi o 10 metri.
 */
@SuppressLint("MissingPermission")
fun locationUpdates(context: Context): Flow<Location> = callbackFlow {
    val lm = context.getSystemService(LocationManager::class.java)
    if (lm == null || !hasLocationPermission(context)) {
        close()
        return@callbackFlow
    }
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }
    val providers = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    // Le ultime posizioni note danno subito un risultato, anche prima del primo aggiornamento
    providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        // Solo se recenti: su un treno in movimento una posizione di qualche minuto fa è già un'altra stazione
        .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
        .sortedBy { it.time }
        .forEach { trySend(it) }

    val listener = LocationListenerCompat { trySend(it) }
    val request = LocationRequestCompat.Builder(5_000)
        .setMinUpdateDistanceMeters(10f)
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        .build()
    for (p in providers) {
        runCatching {
            LocationManagerCompat.requestLocationUpdates(lm, p, request, ContextCompat.getMainExecutor(context), listener)
        }
    }
    awaitClose { LocationManagerCompat.removeUpdates(lm, listener) }
}
