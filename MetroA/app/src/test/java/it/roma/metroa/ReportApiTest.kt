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
    }

    @Test fun rejectedUploadIsNotRetried() {
        // Il trigger del database rifiuta la segnalazione (es. doppia): PostgREST risponde 400
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"segnalazione doppia"}"""))
        val r = api.upload(report, "device-1")
        assertFalse(r.ok)
        assertTrue(r.permanent)
        // Server irraggiungibile o in errore: si ritenta più tardi
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(api.upload(report, "device-1").permanent)
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
