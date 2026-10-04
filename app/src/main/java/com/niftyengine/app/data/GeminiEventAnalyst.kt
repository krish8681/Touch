package com.niftyengine.app.data

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.AnalysisRequest
import com.niftyengine.engine.model.EventAnalysis
import com.niftyengine.engine.model.EventDuration
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.MarketChannel
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.Sector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GeminiException(val code: Int, message: String, val retryAfterSec: Long = 0) : IOException(message)

/**
 * Gemini Event Intelligence — reads news and returns a STRUCTURED DESCRIPTION of each event
 * (type, stage, severity, expectation vs outcome, surprise, channels, horizons…).
 *
 * It is deliberately not a predictor: the schema has no buy/sell/call/put or NIFTY-level fields, the prompt
 * forbids them, and any extra fields in the reply are dropped. Direction probabilities remain the job of the
 * deterministic engine, which also checks Gemini's reading against the market's actual reaction.
 * v5: Gemini also reports how the expected outcome's probability changed and an `expectationShift`, which feeds the
 * Future Expectation Engine (what the market expects, not what it is doing).
 */
class GeminiEventAnalyst(
    private val apiKey: String,
    val model: String = "gemini-2.5-flash",
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) {
    fun analyze(requests: List<AnalysisRequest>, active: List<Triple<String, String, EventStage>>, now: Long): List<EventAnalysis> {
        if (requests.isEmpty()) return emptyList()
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt(requests, active, now))))))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("responseMimeType", "application/json"))
        val req = Request.Builder().url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                val err = runCatching { JSONObject(txt).getJSONObject("error") }.getOrNull()
                val retry = err?.optJSONArray("details")?.let { d ->
                    (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("retryDelay")?.takeIf { s -> s.isNotBlank() } }
                        .firstOrNull()?.trimEnd('s')?.toDoubleOrNull()?.toLong()
                } ?: if (r.code == 429) 60L else 0L
                throw GeminiException(r.code, "Gemini ${r.code}: ${err?.optString("message")?.take(140) ?: txt.take(140)}", retry)
            }
            val text = JSONObject(txt).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text")
            return parse(text, requests.map { it.eventId }.toSet(), active.map { it.first }.toSet(), System.currentTimeMillis(), "gemini:$model")
        }
    }

    companion object {
        private fun ist(t: Long) = Session.zdt(t).let { "%s %02d:%02d".format(it.toLocalDate(), it.hour, it.minute) }

        fun prompt(requests: List<AnalysisRequest>, active: List<Triple<String, String, EventStage>>, now: Long): String = buildString {
            appendLine("You are a news-event analyst for Indian equities (NIFTY 50). Current time: ${ist(now)} IST.")
            appendLine("Your job is ONLY to understand events. You must NOT give trading advice: no buy/sell, no call/put, no price targets, no NIFTY forecasts.")
            appendLine("Separate what the market EXPECTED from what ACTUALLY happened. Expected news is not new news.")
            appendLine("Take expectations from the articles (polls, consensus, 'expected to', market pricing). If none is stated, estimate cautiously and lower confidence.")
            appendLine("Do not invent facts beyond the articles. Rumours stay rumours until confirmed by an authoritative source.")
            appendLine("Markets trade the FUTURE. For each event answer: what was the market expecting, how likely was it (market pricing),")
            appendLine("what is the new information, and has the probability of the expected outcome changed? Report the updated probability")
            appendLine("in expectedProbability and how much the market's expected future moved in expectationShift.")
            appendLine()
            appendLine("Lifecycle stages: RUMOUR (unverified reports), POSSIBLE (being considered), LIKELY (probable, not done), EXPECTED (scheduled/consensus outcome awaited),")
            appendLine("CONFIRMED (outcome is fact), DEVELOPING (ongoing, new facts), ESCALATING (getting bigger/worse), RESOLVING (de-escalating/being settled), RESOLVED (over).")
            appendLine()
            appendLine("Active events (id | stage | title) — if an event below is the SAME real-world event as one of these, list that id in mergeWith:")
            active.forEach { (id, title, st) -> appendLine("$id | $st | ${title.take(110)}") }
            appendLine()
            appendLine("Events to analyse:")
            requests.forEach { r ->
                appendLine("### eventId=${r.eventId}  (tracked stage: ${r.currentStage}" +
                    (r.previousExpectation?.let { p -> "; last tracked expectation: '${p.expectedOutcome}' p=${if (p.probability.isNaN()) "?" else "%.2f".format(p.probability)}" } ?: "") + ")")
                r.articles.forEach { a -> appendLine("- [${ist(a.publishedAt)}] ${a.source}: ${a.title}${if (a.summary.isNotBlank()) " — " + a.summary.take(280) else ""}") }
            }
            appendLine()
            appendLine("Return ONLY a JSON array, one object per eventId above, with exactly these keys:")
            appendLine("""{"eventId": string, "title": short neutral title, "eventType": one of [${EventType.values().joinToString(",")}],
 "stage": one of [${EventStage.values().joinToString(",")}],
 "severity": 0..1 (market significance if the information were entirely new),
 "direction": -1..1 (effect on Indian equities of the information as currently known; negative = harmful),
 "affectedSectors": subset of [${Sector.values().joinToString(",")}], "affectedStocks": NSE symbols,
 "channels": subset of [${MarketChannel.values().joinToString(",")}],
 "expectedOutcome": what was expected beforehand, "expectedProbability": 0..1 consensus probability of that outcome (null if unknown),
 "actualOutcome": what happened ("" if not yet known), "actualMatchesExpectation": true/false/null,
 "surprise": -1..1 (actual vs expected for Indian equities; 0 if as expected or unknown),
 "duration": one of [SHORT,MEDIUM,LONG], "persistence": 0..1, "escalationRisk": 0..1, "confidence": 0..1,
 "horizonWeights": {"M5_15":0..1,"M30_120":0..1,"EOD":0..1,"D1_3":0..1,"W1_2":0..1} (relative relevance per horizon),
 "expectationShift": -1..1 (how much THIS new information moved the market's expected future for Indian equities vs what was expected before it; 0 = nothing new, negative = expectations deteriorated),
 "mergeWith": [ids from the active list that are the same event], "rationale": one sentence}""")
        }

        private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E {
            val k = s?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_') ?: return def
            return enumValues<E>().firstOrNull { it.name == k } ?: def
        }

        private fun num(o: JSONObject, k: String, def: Double, lo: Double, hi: Double): Double =
            if (!o.has(k) || o.isNull(k)) def else o.optDouble(k, def).let { if (it.isNaN()) def else it.coerceIn(lo, hi) }

        /** Strict, defensive parsing: unknown ids/enums/fields are dropped; values are clamped to their ranges. */
        fun parse(text: String, requested: Set<String>, activeIds: Set<String>, analyzedAt: Long, source: String): List<EventAnalysis> {
            val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = if (clean.startsWith("{")) JSONArray().put(JSONObject(clean)) else JSONArray(clean)
            val out = ArrayList<EventAnalysis>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("eventId")
                if (id !in requested) continue
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val hw = o.optJSONObject("horizonWeights")?.let { h ->
                    NewsHorizon.values().mapNotNull { hz -> if (h.has(hz.name)) hz to h.optDouble(hz.name, 0.0).coerceIn(0.0, 1.0) else null }.toMap()
                } ?: emptyMap()
                val sectors = list("affectedSectors").mapNotNull { s ->
                    Sector.values().firstOrNull { it.name.equals(s.trim().replace(' ', '_'), true) || it.label.equals(s.trim(), true) }
                }
                out += EventAnalysis(
                    eventId = id, analyzedAt = analyzedAt, source = source,
                    title = o.optString("title").take(160),
                    eventType = enumOf(o.optString("eventType"), EventType.OTHER),
                    stage = enumOf(o.optString("stage"), EventStage.CONFIRMED),
                    severity = num(o, "severity", 0.3, 0.0, 1.0),
                    direction = num(o, "direction", 0.0, -1.0, 1.0),
                    affectedSectors = sectors.distinct(),
                    affectedStocks = list("affectedStocks").map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().take(10),
                    channels = list("channels").mapNotNull { c -> MarketChannel.values().firstOrNull { it.name.equals(c.trim(), true) } }.distinct(),
                    expectedOutcome = o.optString("expectedOutcome").take(160),
                    expectedProbability = num(o, "expectedProbability", Double.NaN, 0.0, 1.0),
                    actualOutcome = o.optString("actualOutcome").take(160),
                    actualMatchesExpectation = if (!o.has("actualMatchesExpectation") || o.isNull("actualMatchesExpectation")) null
                        else o.optBoolean("actualMatchesExpectation"),
                    surprise = num(o, "surprise", 0.0, -1.0, 1.0),
                    duration = enumOf(o.optString("duration"), EventDuration.MEDIUM),
                    persistence = num(o, "persistence", 0.5, 0.0, 1.0),
                    escalationRisk = num(o, "escalationRisk", 0.0, 0.0, 1.0),
                    confidence = num(o, "confidence", 0.5, 0.0, 1.0),
                    horizonWeights = hw,
                    mergeWith = list("mergeWith").filter { it in activeIds && it != id },
                    rationale = o.optString("rationale").take(240),
                    expectationShift = num(o, "expectationShift", Double.NaN, -1.0, 1.0),
                )
            }
            return out
        }
    }
}
