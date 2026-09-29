package it.roma.metroa

import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Feed pubblico di Roma Servizi per la Mobilità (dati ATAC, licenza CC-BY). */
const val VEHICLE_POSITIONS_URL =
    "https://romamobilita.it/sites/default/files/rome_rtgtfs_vehicle_positions_feed.pb"

/** route_id della Metro A nel GTFS di Roma. Se l'app non mostra treni,
 *  guarda il pannello diagnostico in basso e correggi questo valore. */
val METRO_A_ROUTE_IDS = setOf("MEA")

class FeedRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): GtfsRealtime.FeedMessage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$VEHICLE_POSITIONS_URL?t=${System.currentTimeMillis() / 1000}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Il server ha risposto ${response.code}")
            val body = response.body ?: throw IOException("Risposta vuota")
            GtfsRealtime.FeedMessage.parseFrom(body.byteStream())
        }
    }
}
