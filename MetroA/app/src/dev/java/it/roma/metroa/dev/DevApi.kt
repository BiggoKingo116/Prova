package it.roma.metroa.dev

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sessione di uno sviluppatore: i token di Supabase Auth, rinnovati quando scadono. */
data class Session(val userId: String, val email: String, val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

class DevApiException(message: String, val code: Int) : IOException(message)

/**
 * Accesso da sviluppatore al database: login con email e password (Supabase Auth) e richieste con il
 * token dell'utente. Cosa può fare lo decidono i permessi nel database (funzione is_developer()).
 */
class DevApi(
    private val baseUrl: String,
    private val anonKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Sessione corrente; [onSessionChange] la salva quando cambia (login, rinnovo, uscita). */
    var session: Session? = null
        private set
    var onSessionChange: (Session?) -> Unit = {}

    fun restore(s: Session?) { session = s }

    fun login(email: String, password: String): Session =
        token("password", JSONObject().put("email", email.trim()).put("password", password))

    fun logout() {
        session = null
        onSessionChange(null)
    }

    private fun refresh(): Session {
        val s = session ?: throw DevApiException("Non hai fatto l'accesso", 401)
        return token("refresh_token", JSONObject().put("refresh_token", s.refreshToken))
    }

    private fun token(grant: String, body: JSONObject): Session {
        val req = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=$grant")
            .header("apikey", anonKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val o = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DevApiException(authError(text), resp.code)
            JSONObject(text)
        }
        val user = o.getJSONObject("user")
        val s = Session(user.getString("id"), user.optString("email"), o.getString("access_token"),
            o.getString("refresh_token"), now() + o.optLong("expires_in", 3600) * 1000)
        session = s
        onSessionChange(s)
        return s
    }

    /** true se l'account è nella tabella developers. */
    fun isDeveloper(): Boolean = authed { token ->
        Request.Builder().url("$baseUrl/rest/v1/rpc/is_developer").withAuth(token)
            .post("{}".toRequestBody(JSON)).build()
    }.trim() == "true"

    /** Righe di [table] con i parametri PostgREST dati (select, filtri, order, limit). */
    fun select(table: String, params: Map<String, String>): JSONArray {
        val url = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return JSONArray(authed { token -> Request.Builder().url(url).withAuth(token).get().build() }.ifBlank { "[]" })
    }

    fun insert(table: String, row: JSONObject) {
        authed { token ->
            Request.Builder().url("$baseUrl/rest/v1/$table").withAuth(token)
                .header("Prefer", "return=minimal").post(row.toString().toRequestBody(JSON)).build()
        }
    }

    /** Cancella le righe di [table] che rispettano [filter] (es. "id" to "eq.…"). */
    fun delete(table: String, filter: Map<String, String>) {
        require(filter.isNotEmpty()) { "Una cancellazione senza filtro toglierebbe tutto" }
        val url = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder()
            .apply { filter.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        authed { token -> Request.Builder().url(url).withAuth(token).header("Prefer", "return=minimal").delete().build() }
    }

    /** Esegue una richiesta col token, rinnovandolo prima se sta per scadere e una volta sola se il server lo rifiuta. */
    private fun authed(build: (String) -> Request): String {
        var s = session ?: throw DevApiException("Non hai fatto l'accesso", 401)
        if (now() > s.expiresAtMs - 60_000) s = refresh()
        repeat(2) { attempt ->
            client.newCall(build(s.accessToken)).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return text
                if (resp.code == 401 && attempt == 0) { s = refresh(); return@repeat }
                throw DevApiException(restError(text, resp.code), resp.code)
            }
        }
        throw DevApiException("Accesso scaduto: rientra", 401)
    }

    private fun Request.Builder.withAuth(token: String) =
        header("apikey", anonKey).header("Authorization", "Bearer $token")

    private fun authError(text: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val msg = o?.optString("error_description")?.ifBlank { null } ?: o?.optString("msg")?.ifBlank { null }
        return when {
            msg == null -> "Accesso non riuscito"
            "Invalid login" in msg -> "Email o password sbagliate"
            else -> msg
        }
    }

    private fun restError(text: String, code: Int): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()?.ifBlank { null } ?: "Il server ha risposto $code"

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
