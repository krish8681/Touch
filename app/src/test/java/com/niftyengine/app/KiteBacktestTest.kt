package com.niftyengine.app

import com.niftyengine.app.data.KiteApi
import com.niftyengine.app.data.KiteClient
import com.niftyengine.app.data.KiteHistoricalSource
import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.Constituents
import com.niftyengine.engine.engines.HistoricalBacktest
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.Sector
import com.niftyengine.engine.sim.SimulatedMarket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Kite historical client + market-only backtest against a mock Kite server serving simulator history. */
class KiteBacktestTest {
    private val server = MockWebServer()
    private val bars = HashMap<Long, MutableList<Candle>>() // token → 1-minute bars
    private val oi = HashMap<Long, Double>()
    private val daily = ArrayList<Candle>()
    private val days = ArrayList<LocalDate>()
    private val requests = mutableListOf<String>()
    private val futToken = 12468226L // NIFTY26OCTFUT in the real NFO fixture
    private val stockTokens = Constituents.DEFAULT.mapIndexed { i, c -> c.symbol to 1000L + i }.toMap()
    private val sectorNames = mapOf(Sector.BANK to "NIFTY BANK", Sector.IT to "NIFTY IT", Sector.AUTO to "NIFTY AUTO", Sector.FMCG to "NIFTY FMCG")
    private val sectorTokens = sectorNames.keys.mapIndexed { i, s -> s to 3000L + i }.toMap()
    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

    @Before fun up() {
        val sim = SimulatedMarket(seed = 21, startDate = LocalDate.of(2026, 6, 1))
        var cur: LocalDate? = null
        repeat(12 * (Session.SESSION_MINUTES - 1)) {
            val s = sim.collect(0)
            val d = Session.zdt(s.timestamp).toLocalDate()
            if (d != cur) { cur = d; days += d }
            val t = s.timestamp - 60_000L
            fun add(tok: Long, x: Double) { bars.getOrPut(tok) { ArrayList() } += Candle(t, x, x, x, x, 10.0) }
            add(KiteClient.TOKEN_NIFTY, s.nifty.last); s.vix?.let { add(KiteClient.TOKEN_VIX, it.last) }
            s.bankNifty?.let { add(KiteClient.TOKEN_BANKNIFTY, it.last) }
            s.futures?.let { add(futToken, it.last); oi[t] = it.openInterest }
            s.constituents.forEach { (k, v) -> stockTokens[k]?.let { add(it, v.last) } }
            s.sectors.forEach { (k, v) -> sectorTokens[k]?.let { add(it, v.last) } }
            if (daily.isEmpty()) daily += s.nifty.daily.map { c -> c.copy(t = Session.zdt(c.t).toLocalDate().atStartOfDay(Session.IST).toInstant().toEpochMilli()) }
        }
        // the replayed days also appear as daily candles (stamped 00:00 like Kite)
        days.forEach { d ->
            val dayBars = bars.getValue(KiteClient.TOKEN_NIFTY).filter { Session.zdt(it.t).toLocalDate() == d }
            daily += Candle(d.atStartOfDay(Session.IST).toInstant().toEpochMilli(), dayBars.first().o, dayBars.maxOf { it.h }, dayBars.minOf { it.l }, dayBars.last().c)
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                requests += path
                return when {
                    path.startsWith("/instruments/NSE") -> MockResponse().setBody(buildString {
                        appendLine("instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange")
                        stockTokens.forEach { (sym, tok) -> appendLine("$tok,1,$sym,\"$sym\",0,,0,0.05,1,EQ,NSE,NSE") }
                        sectorTokens.forEach { (sec, tok) -> appendLine("$tok,1,${sectorNames.getValue(sec)},\"${sectorNames.getValue(sec)}\",0,,0,0,0,EQ,INDICES,NSE") }
                    })
                    path.startsWith("/instruments/NFO") -> MockResponse().setBody(javaClass.getResource("/kite-nfo-nifty.csv")!!.readText())
                    path.startsWith("/instruments/historical/") -> historical(path)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun down() = server.shutdown()

    private fun historical(path: String): MockResponse {
        val parts = path.substringBefore('?').split('/')
        val token = parts[3].toLong(); val interval = parts[4]
        val q = path.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val from = LocalDateTime.parse(q.getValue("from"), f).atZone(Session.IST).toInstant().toEpochMilli()
        val to = LocalDateTime.parse(q.getValue("to"), f).atZone(Session.IST).toInstant().toEpochMilli()
        val arr = JSONArray()
        fun row(c: Candle, withOi: Boolean) = JSONArray().put(java.time.Instant.ofEpochMilli(c.t).atZone(Session.IST).format(tsFmt))
            .put(c.o).put(c.h).put(c.l).put(c.c).put(c.v).also { if (withOi) it.put(oi[c.t] ?: 0.0) }
        val src: List<Candle> = when (interval) {
            "day" -> if (token == KiteClient.TOKEN_NIFTY || token == KiteClient.TOKEN_VIX) daily else emptyList()
            "minute" -> bars[token].orEmpty()
            "5minute" -> bars[token].orEmpty().groupBy { it.t / 300_000L }.map { (k, g) -> Candle(k * 300_000L, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v }) }
            else -> emptyList()
        }
        src.filter { it.t in from..to }.forEach { arr.put(row(it, q["oi"] == "1")) }
        return MockResponse().setBody(JSONObject().put("status", "success").put("data", JSONObject().put("candles", arr)).toString())
    }

    @Test fun downloadsCachesAndBacktests() {
        val cache = Files.createTempDirectory("kh").toFile()
        val api = KiteApi("key", "token", server.url("").toString().trimEnd('/'))
        val src = KiteHistoricalSource(api, cache)
        val bt = HistoricalBacktest(EngineConfig(), HistoricalBacktest.Config(chunkDays = 6, recalibrateEveryDays = 3, minCalibrationSamples = 60))
        val run = bt.run(src, days.first(), days.last())
        val r = run.report
        println("mock-Kite backtest: ${r.days} days, ${r.predictions} predictions, notes=${src.notes}")
        r.baselines.forEach { println("  ${it.horizon}m model %.2f momentum %.2f skill %+.3f".format(it.modelAccuracy, it.momentumAccuracy, it.brierSkill)) }

        assertEquals(12, r.days)
        assertTrue(r.predictions > 12 * 70)
        val hist = requests.filter { it.startsWith("/instruments/historical/") }
        assertTrue(hist.any { it.contains("/${KiteClient.TOKEN_NIFTY}/minute?") })
        assertTrue(hist.any { it.contains("/${stockTokens.getValue("RELIANCE")}/5minute?") })
        assertTrue(hist.any { it.contains("/$futToken/minute?") && it.contains("oi=1") && it.contains("continuous=1") })
        assertTrue("futures OI history reached the engine", run.records.any { (it.driverScores["DERIVATIVES"] ?: 0.0) != 0.0 })
        assertTrue(src.notes.none { it.contains("futures driver absent") })

        // Second run over the same (past) range: served entirely from the disk cache.
        val before = requests.count { it.startsWith("/instruments/historical/") && !it.contains("/day?") }
        HistoricalBacktest(EngineConfig(), HistoricalBacktest.Config(chunkDays = 6)).run(KiteHistoricalSource(api, cache), days.first(), days.last())
        val after = requests.count { it.startsWith("/instruments/historical/") && !it.contains("/day?") }
        assertEquals("cached ranges must not be downloaded again", before, after)
    }
}
