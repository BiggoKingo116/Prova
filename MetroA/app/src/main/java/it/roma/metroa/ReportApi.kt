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
    /** Esito dell'invio di una segnalazione: [permanent] se il server l'ha rifiutata e non va ritentata. */
    class UploadResult(val ok: Boolean, val permanent: Boolean)

    fun upload(r: Report, deviceId: String): UploadResult {
        val body = JSONObject()
            .put("id", r.id).put("time_ms", r.timeMs).put("station", r.station)
            .put("direction", r.direction.name).put("offset_s", r.offsetS)
            .put("source", r.source.name).put("device_id", deviceId)
        val request = request("reports")
            .header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { resp ->
            // 4xx: dati rifiutati dai controlli del server (fuori limiti, troppe segnalazioni di fila…)
            return UploadResult(resp.isSuccessful, permanent = resp.code in 400..499 && resp.code != 401 && resp.code != 429)
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
