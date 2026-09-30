package it.roma.metroa.dev

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevApiTest {
    private val server = MockWebServer().apply { start() }
    private var clock = 1_000_000L
    private val api = DevApi(server.url("/").toString().trimEnd('/'), "chiave-anon", now = { clock })
    private val saved = ArrayList<Session?>()

    init { api.onSessionChange = { saved += it } }

    @After fun stop() = server.shutdown()

    private fun tokenResponse(access: String, refresh: String) = MockResponse().setBody(
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"user":{"id":"u1","email":"dev@x.it"}}"""
    )

    @Test fun loginStoresSession() {
        server.enqueue(tokenResponse("a1", "r1"))
        val s = api.login(" dev@x.it ", "segreta")
        assertEquals("a1", s.accessToken)
        assertEquals(clock + 3_600_000, s.expiresAtMs)
        assertEquals(s, saved.single())
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("chiave-anon", req.getHeader("apikey"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("dev@x.it", body.getString("email")) // spazi tolti
    }

    @Test fun wrongPasswordIsReadable() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
        val e = runCatching { api.login("dev@x.it", "no") }.exceptionOrNull()
        assertEquals("Email o password sbagliate", e?.message)
        assertNull(api.session)
    }

    @Test fun requestsUseUserTokenAndDeveloperCheck() {
        server.enqueue(tokenResponse("a1", "r1"))
        api.login("dev@x.it", "x")
        server.takeRequest()
        server.enqueue(MockResponse().setBody("true"))
        assertTrue(api.isDeveloper())
        val req = server.takeRequest()
        assertEquals("/rest/v1/rpc/is_developer", req.path)
        assertEquals("Bearer a1", req.getHeader("Authorization"))
        server.enqueue(MockResponse().setBody("false"))
        assertFalse(api.isDeveloper())
    }

    @Test fun expiringTokenIsRefreshedFirst() {
        server.enqueue(tokenResponse("a1", "r1"))
        api.login("dev@x.it", "x")
        server.takeRequest()
        clock += 3_590_000 // a 10 secondi dalla scadenza
        server.enqueue(tokenResponse("a2", "r2"))
        server.enqueue(MockResponse().setBody("[]"))
        api.select("reports", mapOf("select" to "*"))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer a2", server.takeRequest().getHeader("Authorization"))
        assertEquals("a2", saved.last()?.accessToken)
    }

    @Test fun rejectedTokenIsRefreshedOnce() {
        server.enqueue(tokenResponse("a1", "r1"))
        api.login("dev@x.it", "x")
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        server.enqueue(tokenResponse("a2", "r2"))
        server.enqueue(MockResponse().setBody("""[{"id":"x"}]"""))
        val rows = api.select("train_numbers", mapOf("select" to "*"))
        assertEquals(1, rows.length())
        assertEquals("Bearer a1", server.takeRequest().getHeader("Authorization"))
        server.takeRequest() // rinnovo
        assertEquals("Bearer a2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun insertAndDelete() {
        server.enqueue(tokenResponse("a1", "r1"))
        api.login("dev@x.it", "x")
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(201))
        api.insert("train_numbers", JSONObject().put("train_number", "312"))
        val ins = server.takeRequest()
        assertEquals("POST", ins.method)
        assertEquals("312", JSONObject(ins.body.readUtf8()).getString("train_number"))
        server.enqueue(MockResponse().setResponseCode(204))
        api.delete("reports", mapOf("device_id" to "eq.abc"))
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("eq.abc", del.requestUrl!!.queryParameter("device_id"))
        // Senza filtro cancellerebbe tutta la tabella: vietato già nell'app
        assertTrue(runCatching { api.delete("reports", emptyMap()) }.isFailure)
    }

    @Test fun serverErrorMessageIsShown() {
        server.enqueue(tokenResponse("a1", "r1"))
        api.login("dev@x.it", "x")
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"message":"new row violates check constraint \"train_numbers_train_number_check\""}"""))
        val e = runCatching { api.insert("train_numbers", JSONObject()) }.exceptionOrNull() as DevApiException
        assertTrue(e.message!!.contains("train_number_check"))
        assertEquals(400, e.code)
    }

    @Test fun trainNumberFormat() {
        assertTrue(TRAIN_NUMBER.matches("312"))
        assertTrue(TRAIN_NUMBER.matches("MA-300"))
        assertFalse(TRAIN_NUMBER.matches("3 12"))
        assertFalse(TRAIN_NUMBER.matches(""))
        assertFalse(TRAIN_NUMBER.matches("1234567890123"))
    }
}
