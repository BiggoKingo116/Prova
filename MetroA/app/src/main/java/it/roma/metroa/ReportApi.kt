package it.roma.metroa

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Database condiviso delle segnalazioni: tabella `reports` su Supabase (Postgres + API REST).
 * Lo schema, con i controlli e i permessi, è in supabase/schema.sql.
 * La chiave "anon" è pubblica per costruzione: cosa si può fare lo decidono i permessi nel database.
 */
class ReportApi(
    private val baseUrl: String,
    private val anonKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    /**
     * Esito dell'invio di una segnalazione: [permanent] se il server l'ha rifiutata e non va ritentata;
     * [message] è il motivo dato dal database (per esempio "segnalazione doppia").
     */
    class UploadResult(val ok: Boolean, val permanent: Boolean, val message: String? = null)

    fun upload(r: Report, deviceId: String): UploadResult {
        val body = JSONObject()
            .put("id", r.id).put("time_ms", r.timeMs).put("station", r.station)
            .put("direction", r.direction.name).put("offset_s", r.offsetS)
            .put("source", r.source.name).put("device_id", deviceId)
        r.tripId?.let { body.put("trip_id", it) }
        val request = request("reports")
            .header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            // 4xx: dati rifiutati dai controlli del server (fuori limiti, troppe segnalazioni di fila…)
            val message = if (resp.isSuccessful) null
                else runCatching { JSONObject(resp.body?.string().orEmpty()).optString("message") }.getOrNull()
            return UploadResult(resp.isSuccessful, permanent = resp.code in 400..499 && resp.code != 401 && resp.code != 429,
                message = message?.ifBlank { null })
        }
    }

    /** Segnalazioni con numero progressivo > [afterSeq] e ora ≥ [sinceMs]. Restituisce anche l'ultimo numero visto. */
    fun fetch(afterSeq: Long, sinceMs: Long, limit: Int = 1000): Pair<List<Report>, Long> {
        val url = "$baseUrl/rest/v1/reports".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,seq,time_ms,station,direction,offset_s,source")
            .addQueryParameter("seq", "gt.$afterSeq")
            .addQueryParameter("time_ms", "gte.$sinceMs")
            .addQueryParameter("order", "seq.asc")
            .addQueryParameter("limit", "$limit")
            .build()
        val request = request("").url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Il server ha risposto ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: "[]")
            var maxSeq = afterSeq
            val reports = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                maxSeq = maxOf(maxSeq, o.getLong("seq"))
                runCatching {
                    Report(
                        o.getString("id"), o.getLong("time_ms"), o.getInt("station"),
                        Direction.valueOf(o.getString("direction")), o.getInt("offset_s"),
                        ReportSource.valueOf(o.getString("source")),
                    )
                }.getOrNull()
            }
            return reports to maxSeq
        }
    }

    /**
     * Invia un gruppo di punti GPS di viaggio (tabella privata `trip_points`: si scrive, non si legge).
     * Restituisce true se il server li ha presi o rifiutati per sempre (in entrambi i casi non si ritentano).
     */
    fun uploadPoints(points: List<TrackPoint>, deviceId: String): Boolean {
        val body = JSONArray()
        for (p in points) body.put(JSONObject()
            .put("id", p.id).put("trip_id", p.tripId).put("time_ms", p.timeMs)
            .put("lat", p.lat).put("lon", p.lon).put("accuracy_m", p.accuracyM.toDouble()).put("device_id", deviceId))
        val request = request("trip_points")
            .header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful || resp.code in 400..499 && resp.code != 401 && resp.code != 429) return true
            throw IOException("Il server ha risposto ${resp.code}")
        }
    }

    /** Tutti gli id presenti sul server con ora ≥ [sinceMs], a pagine, per riconoscere le cancellazioni. */
    fun fetchIds(sinceMs: Long, pageSize: Int = 1000): Set<String> {
        val ids = HashSet<String>()
        var seq = 0L
        while (true) {
            val url = "$baseUrl/rest/v1/reports".toHttpUrl().newBuilder()
                .addQueryParameter("select", "id,seq")
                .addQueryParameter("seq", "gt.$seq")
                .addQueryParameter("time_ms", "gte.$sinceMs")
                .addQueryParameter("order", "seq.asc")
                .addQueryParameter("limit", "$pageSize")
                .build()
            val arr = client.newCall(request("").url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Il server ha risposto ${resp.code}")
                JSONArray(resp.body?.string() ?: "[]")
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                ids += o.getString("id")
                seq = maxOf(seq, o.getLong("seq"))
            }
            if (arr.length() < pageSize) return ids
        }
    }

    private fun request(path: String) = Request.Builder()
        .url("$baseUrl/rest/v1/$path")
        .header("apikey", anonKey)
        .header("Authorization", "Bearer $anonKey")

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** Il server delle segnalazioni, o null se l'app è stata compilata senza indirizzo e chiave. */
fun reportApiOrNull(): ReportApi? =
    BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() }
        ?.let { ReportApi(it.trim().trimEnd('/').removeSuffix("/rest/v1"), BuildConfig.SUPABASE_ANON_KEY) }

/** Identificativo anonimo del telefono, usato dal server solo per limitare lo spam. */
fun deviceId(context: android.content.Context): String {
    val prefs = context.getSharedPreferences("metroa", 0)
    return prefs.getString("device_id", null)
        ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
}

/** Una segnalazione che il server non ha accettato, con il motivo. */
data class Rejection(val report: Report, val reason: String?)

/** Un solo invio alla volta in tutta l'app: l'app e il servizio del viaggio non mandano due volte la stessa segnalazione. */
private val uploadLock = Any()

/**
 * Invia le segnalazioni in attesa, una alla volta. Quelle rifiutate dal server (doppie, fuori limiti)
 * restano sul telefono segnate come rifiutate e vengono restituite, per dirlo all'utente; al primo errore
 * di rete si smette e si riprova più tardi.
 */
fun uploadPending(db: ReportDb, api: ReportApi, deviceId: String): List<Rejection> = synchronized(uploadLock) {
    val rejected = ArrayList<Rejection>()
    for (r in db.pending()) {
        val res = api.upload(r, deviceId)
        when {
            res.ok -> db.markUploaded(r.id)
            res.permanent -> { db.markRejected(r.id); rejected += Rejection(r, res.message) }
            else -> throw IOException("Invio non riuscito")
        }
    }
    // Punti GPS dei viaggi, solo di chi ha scelto di condividerli: a gruppi di 200
    while (true) {
        val points = db.pendingPoints()
        if (points.isEmpty()) break
        api.uploadPoints(points, deviceId)
        db.deletePoints(points.map { it.id })
        if (points.size < 200) break
    }
    rejected
}

/** Il motivo di un rifiuto del server, detto in modo comprensibile. */
fun rejectionText(reason: String?): String = when {
    reason == null -> "dati non accettati"
    "doppia" in reason -> "c'era già una tua segnalazione per la stessa stazione e direzione meno di 30 secondi prima"
    "troppe" in reason -> "troppe segnalazioni nell'ultima ora"
    "time_ms" in reason -> "l'ora del telefono non è giusta"
    else -> reason
}
