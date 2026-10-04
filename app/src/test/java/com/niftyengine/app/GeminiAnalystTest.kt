package com.niftyengine.app

import com.niftyengine.app.data.GeminiEventAnalyst
import com.niftyengine.app.data.GeminiException
import com.niftyengine.engine.engines.EventIntelligenceEngine
import com.niftyengine.engine.model.AnalysisRequest
import com.niftyengine.engine.model.EventAnalysis
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.MarketBaseline
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.Sector
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GeminiAnalystTest {
    private val server = MockWebServer()
    private val now = 1_790_830_000_000L

    @Before fun up() = server.start()
    @After fun down() = server.shutdown()

    private fun req(id: String) = AnalysisRequest(
        id, listOf(NewsItem("n1", "RBI cuts repo rate by 50 bps vs 25 bps expected", "Reuters", now - 60_000)), EventStage.EXPECTED, null,
        EventAnalysis(id, now, "rules", "RBI", EventType.RBI_POLICY, EventStage.CONFIRMED, 0.9, 0.7),
    )

    private val modelReply = JSONArray()
        .put(JSONObject()
            .put("eventId", "E1").put("title", "RBI cuts repo 50 bps vs 25 expected").put("eventType", "rbi_policy").put("stage", "CONFIRMED")
            .put("severity", 1.7).put("direction", 0.8).put("affectedSectors", JSONArray().put("BANK").put("Fin Services").put("Spaceships"))
            .put("affectedStocks", JSONArray().put("hdfcbank").put("SBIN")).put("channels", JSONArray().put("RATES").put("FLOWS").put("MAGIC"))
            .put("expectedOutcome", "25 bps cut").put("expectedProbability", 0.8).put("actualOutcome", "50 bps cut")
            .put("actualMatchesExpectation", false).put("surprise", 0.7).put("duration", "MEDIUM").put("persistence", 0.6)
            .put("escalationRisk", 0.0).put("confidence", 0.85)
            .put("horizonWeights", JSONObject().put("M5_15", 1.0).put("EOD", 0.8).put("W1_2", 0.3))
            .put("mergeWith", JSONArray().put("E0").put("NOT_ACTIVE"))
            .put("action", "BUY CALL 24600 CE")          // must be ignored
            .put("niftyTarget", 25500))                  // must be ignored
        .put(JSONObject().put("eventId", "UNREQUESTED").put("stage", "RUMOUR"))

    @Test fun requestAndStrictParsing() {
        val envelope = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "```json\n$modelReply\n```"))))))
        server.enqueue(MockResponse().setBody(envelope.toString()))
        val g = GeminiEventAnalyst("KEY123", "gemini-2.5-flash", server.url("").toString().trimEnd('/'))
        val out = g.analyze(listOf(req("E1")), listOf(Triple("E0", "RBI policy preview", EventStage.EXPECTED)), now)

        val rec = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", rec.path)
        assertEquals("KEY123", rec.getHeader("x-goog-api-key"))
        val body = JSONObject(rec.body.readUtf8())
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
        val prompt = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("must NOT give trading advice"))
        assertTrue(prompt.contains("Expected news is not new news"))
        assertTrue(prompt.contains("E0 | EXPECTED | RBI policy preview"))

        assertEquals(1, out.size)
        val a = out[0]
        assertEquals(EventType.RBI_POLICY, a.eventType)
        assertEquals(1.0, a.severity, 1e-9)                         // clamped
        assertEquals(listOf(Sector.BANK, Sector.FIN_SERVICES), a.affectedSectors)
        assertEquals(listOf("HDFCBANK", "SBIN"), a.affectedStocks)
        assertEquals(2, a.channels.size)
        assertEquals(false, a.actualMatchesExpectation)
        assertEquals(listOf("E0"), a.mergeWith)                     // only ids that were offered
        assertEquals(setOf(NewsHorizon.M5_15, NewsHorizon.EOD, NewsHorizon.W1_2), a.horizonWeights.keys)
        assertTrue(a.source.startsWith("gemini"))
        val fields = EventAnalysis::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse("schema must not carry trade fields", fields.any { it.contains("action") || it.contains("target") || it.contains("buy") })
    }

    @Test fun rateLimitSurfacesRetryDelay() {
        server.enqueue(MockResponse().setResponseCode(429).setBody(JSONObject().put("error", JSONObject().put("code", 429)
            .put("message", "Resource exhausted").put("status", "RESOURCE_EXHAUSTED")
            .put("details", JSONArray().put(JSONObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo").put("retryDelay", "37s")))).toString()))
        try {
            GeminiEventAnalyst("K", "m", server.url("").toString().trimEnd('/')).analyze(listOf(req("E1")), emptyList(), now)
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertEquals(429, e.code); assertEquals(37L, e.retryAfterSec)
        }
    }

    @Test fun parsedGeminiReadingDrivesTheEngine() {
        val a = GeminiEventAnalyst.parse(modelReply.toString(), setOf("E1"), setOf("E0"), now, "gemini:test")[0]
        val eng = EventIntelligenceEngine()
        val base = MarketBaseline(now - 1, 25000.0, bank = 56000.0, vix = 14.0)
        val news = listOf(NewsItem("n1", "RBI cuts repo rate by 50 bps vs 25 bps expected", "Reuters", now - 60_000))
        val r0 = eng.process(news, emptyList(), now, base.copy(t = now), base, emptyList(), NewsHorizon.M30_120)
        val id = r0.events.single().id
        val r = eng.process(news, listOf(a.copy(eventId = id, mergeWith = emptyList())), now + 60_000, base.copy(t = now + 60_000), base, emptyList(), NewsHorizon.M30_120)
        val e = r.events.single()
        assertEquals("gemini:test", e.analysis!!.source)
        assertTrue(e.surpriseMagnitude >= 0.7)          // 50 bps vs 25 expected at 80 % → big surprise
        assertTrue(e.effectiveImpact > 0.2)
        assertTrue(r.horizons.getValue(NewsHorizon.M5_15) > 0)
    }
}
