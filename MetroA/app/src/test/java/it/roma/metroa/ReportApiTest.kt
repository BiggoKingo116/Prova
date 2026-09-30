package it.roma.metroa

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = ReportApi(server.url("/").toString().trimEnd('/'), "chiave-anon")

    @After fun stop() = server.shutdown()

    private val report = Report("11111111-1111-1111-1111-111111111111", 1_790_000_000_000, 11,
        Direction.TO_ANAGNINA, 120, ReportSource.ARRIVAL)

    @Test fun uploadSendsReportWithKey() {
        server.enqueue(MockResponse().setResponseCode(201))
        assertTrue(api.upload(report, "device-1").ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/rest/v1/reports", req.path)
        assertEquals("chiave-anon", req.getHeader("apikey"))
        assertEquals("Bearer chiave-anon", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Prefer")!!.contains("ignore-duplicates"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals(report.id, body.getString("id"))
        assertEquals(120, body.getInt("offset_s"))
        assertEquals("TO_ANAGNINA", body.getString("direction"))
        assertEquals("ARRIVAL", body.getString("source"))
        assertEquals("device-1", body.getString("device_id"))
        assertFalse(body.has("trip_id")) // segnalazione normale: niente viaggio
    }

    @Test fun tripPassageSendsTripId() {
        server.enqueue(MockResponse().setResponseCode(201))
        api.upload(report.copy(source = ReportSource.TRIP, tripId = "22222222-2222-2222-2222-222222222222"), "device-1")
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("TRIP", body.getString("source"))
        assertEquals("22222222-2222-2222-2222-222222222222", body.getString("trip_id"))
    }

    @Test fun rejectedUploadIsNotRetried() {
        // Il trigger del database rifiuta la segnalazione (es. doppia): PostgREST risponde 400
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"segnalazione doppia"}"""))
        val r = api.upload(report, "device-1")
        assertFalse(r.ok)
        assertTrue(r.permanent)
        assertEquals("segnalazione doppia", r.message)
        assertTrue(rejectionText(r.message).contains("30 secondi"))
        // Server irraggiungibile o in errore: si ritenta più tardi
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(api.upload(report, "device-1").permanent)
    }

    @Test fun gpsPointsGoToThePrivateTable() {
        server.enqueue(MockResponse().setResponseCode(201))
        val p = TrackPoint("p1", "22222222-2222-2222-2222-222222222222", 1_790_000_000_000, 41.9127, 12.4764, 15f)
        assertTrue(api.uploadPoints(listOf(p), "device-1"))
        val req = server.takeRequest()
        assertEquals("/rest/v1/trip_points", req.path)
        val arr = org.json.JSONArray(req.body.readUtf8())
        assertEquals(1, arr.length())
        assertEquals(41.9127, arr.getJSONObject(0).getDouble("lat"), 1e-9)
        assertEquals("device-1", arr.getJSONObject(0).getString("device_id"))
    }

    @Test fun fetchIdsReadsAllPages() {
        server.enqueue(MockResponse().setBody("""[{"id":"a","seq":1},{"id":"b","seq":4}]"""))
        server.enqueue(MockResponse().setBody("""[{"id":"c","seq":9}]"""))
        assertEquals(setOf("a", "b", "c"), api.fetchIds(sinceMs = 1_000, pageSize = 2))
        assertEquals("gt.0", server.takeRequest().requestUrl!!.queryParameter("seq"))
        val second = server.takeRequest().requestUrl!!
        assertEquals("gt.4", second.queryParameter("seq"))
        assertEquals("gte.1000", second.queryParameter("time_ms"))
    }

    @Test fun fetchIdsFailsInsteadOfReturningPartialList() {
        // Una lista incompleta farebbe cancellare dal telefono segnalazioni ancora valide
        server.enqueue(MockResponse().setBody("""[{"id":"a","seq":1},{"id":"b","seq":2}]"""))
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(runCatching { api.fetchIds(sinceMs = 0, pageSize = 2) }.isFailure)
    }

    @Test fun fetchParsesReportsAndSkipsInvalid() {
        server.enqueue(MockResponse().setBody("""[
            {"id":"a","seq":7,"time_ms":1790000000000,"station":3,"direction":"TO_BATTISTINI","offset_s":-60,"source":"MANUAL"},
            {"id":"b","seq":9,"time_ms":1790000060000,"station":3,"direction":"SBAGLIATA","offset_s":0,"source":"MANUAL"}
        ]"""))
        val (reports, maxSeq) = api.fetch(afterSeq = 5, sinceMs = 1_000)
        assertEquals(listOf(Report("a", 1_790_000_000_000, 3, Direction.TO_BATTISTINI, -60, ReportSource.MANUAL)), reports)
        assertEquals(9L, maxSeq)
        val path = server.takeRequest().requestUrl!!
        assertEquals("gt.5", path.queryParameter("seq"))
        assertEquals("gte.1000", path.queryParameter("time_ms"))
        assertEquals("seq.asc", path.queryParameter("order"))
        assertFalse(path.queryParameter("select")!!.contains("device_id"))
    }
}
