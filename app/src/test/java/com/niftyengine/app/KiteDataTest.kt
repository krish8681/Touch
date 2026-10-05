package com.niftyengine.app

import com.niftyengine.app.data.KiteApi
import com.niftyengine.app.data.KiteClient
import com.niftyengine.app.data.KiteException
import com.niftyengine.app.data.KiteMarketData
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.Constituents
import com.niftyengine.engine.model.MarketSnapshot
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Kite integration against a mock server speaking Kite's response formats, using the REAL NIFTY
 * NFO instrument dump (test resource) so symbols, strikes and expiries are genuine.
 */
class KiteDataTest {
    private val server = MockWebServer()
    private val spot = 22421.95
    private val day = LocalDate.of(2026, 10, 1)
    private val now = day.atTime(14, 0).atZone(Session.IST).toInstant().toEpochMilli()
    private val nfoCsv = javaClass.getResource("/kite-nfo-nifty.csv")!!.readText()
    private val requests = mutableListOf<String>()

    private fun candleTime(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(Session.IST).toOffsetDateTime()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))

    private fun quoteFor(key: String): JSONObject? {
        fun q(last: Double, close: Double, oi: Double = 0.0, bid: Double = 0.0, ask: Double = 0.0) = JSONObject()
            .put("last_price", last).put("volume", 123456).put("oi", oi)
            .put("ohlc", JSONObject().put("open", close).put("high", maxOf(last, close)).put("low", minOf(last, close)).put("close", close))
            .put("depth", JSONObject().put("buy", JSONArray().put(JSONObject().put("price", bid).put("quantity", 75)))
                .put("sell", JSONArray().put(JSONObject().put("price", ask).put("quantity", 75))))
        return when {
            key == "NSE:NIFTY 50" -> q(spot, 22620.45)
            key == "NSE:INDIA VIX" -> q(14.44, 13.5)
            key == "NSE:NIFTY BANK" -> q(50000.0, 50300.0)
            key.startsWith("NSE:NIFTY ") -> q(10000.0, 10080.0)
            key == "NFO:NIFTY26OCTFUT" -> q(22520.0, 22707.3, oi = 294707.0)
            key.startsWith("NFO:NIFTY") && (key.endsWith("CE") || key.endsWith("PE")) -> {
                val m = Regex("(\\d{5})(CE|PE)$").find(key)!!
                val k = m.groupValues[1].toDouble(); val call = m.groupValues[2] == "CE"
                val t = Session.yearsToExpiry(now, Session.closeOf(LocalDate.of(2026, 10, 6)))
                val px = BlackScholes.price(call, spot, k, t, 0.13).price.coerceAtLeast(0.05)
                q(px, px * 1.1, oi = 50_000.0 + k % 500 * 10, bid = px * 0.995, ask = px * 1.005)
            }
            key.startsWith("NSE:") -> q(1000.0, 1010.0) // constituents
            else -> null
        }
    }

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                requests += path
                if (request.getHeader("Authorization") != "token key:token") return MockResponse().setResponseCode(403)
                    .setBody("""{"status":"error","message":"Incorrect `api_key` or `access_token`.","data":null,"error_type":"TokenException"}""")
                return when {
                    path.startsWith("/instruments/NFO") -> MockResponse().setBody(nfoCsv)
                    path.startsWith("/quote") -> {
                        val keys = path.substringAfter('?').split('&').map { URLDecoder.decode(it.removePrefix("i="), "UTF-8") }
                        val data = JSONObject()
                        keys.forEach { k -> quoteFor(k)?.let { data.put(k, it) } }
                        MockResponse().setBody(JSONObject().put("status", "success").put("data", data).toString())
                    }
                    path.startsWith("/instruments/historical/") -> {
                        val withOi = path.contains("oi=1")
                        val daily = path.contains("/day?")
                        val arr = JSONArray()
                        if (daily) for (i in 300 downTo 1) {
                            val d = day.minusDays(i.toLong()); if (d.dayOfWeek.value >= 6) continue
                            arr.put(JSONArray().put(candleTime(d.atTime(0, 0).atZone(Session.IST).toInstant().toEpochMilli()))
                                .put(22000.0).put(22100.0).put(21900.0).put(22000.0 + i).put(1))
                        } else {
                            // previous session tail (for prev OI) + today until 14:00
                            val prev = day.minusDays(1).atTime(15, 29).atZone(Session.IST).toInstant().toEpochMilli()
                            arr.put(JSONArray().put(candleTime(prev)).put(22700.0).put(22710.0).put(22690.0).put(22707.0).put(10).put(277155.0))
                            var t = Session.sessionStart(now); var px = 22600.0
                            while (t <= now) {
                                px -= 0.6
                                val row = JSONArray().put(candleTime(t)).put(px).put(px + 2).put(px - 2).put(px).put(100)
                                if (withOi) row.put(277155.0 + (t - Session.sessionStart(now)) / 60_000.0 * 60)
                                arr.put(row); t += 60_000L
                            }
                        }
                        MockResponse().setBody(JSONObject().put("status", "success").put("data", JSONObject().put("candles", arr)).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun cache(): File = Files.createTempDirectory("kite").toFile()

    @Test fun collectsQuotesChainFuturesAndHistory() {
        val md = KiteMarketData(KiteApi("key", "token", server.url("").toString().trimEnd('/')), cache())
        val b = md.collect(now, Constituents.DEFAULT.map { it.symbol }, 20, spot)
        assertEquals(spot, b.nifty!!.last, 1e-9)
        assertEquals(22620.45, b.nifty!!.prevClose, 1e-9)
        assertEquals("NIFTY26OCTFUT", b.futures!!.symbol)
        assertEquals(294707.0, b.futures!!.openInterest, 1e-9)
        val chain = b.chain!!
        assertEquals("06-Oct-2026", chain.expiry)
        assertEquals(41, chain.rows.size)
        val atm = chain.rows.minBy { kotlin.math.abs(it.strike - spot) }
        assertEquals(13.0, atm.call.iv, 0.3) // IV implied back from Kite prices
        assertTrue(atm.call.bid > 0 && atm.call.ask > atm.call.bid)
        assertTrue(b.constituents.size >= 45)
        assertTrue(b.sectors.isNotEmpty())
        // one quote round-trip when a spot hint is given (respects Kite's 1 req/s quote limit)
        assertEquals(1, requests.count { it.startsWith("/quote") })

        val (futBars, prevOi) = md.futuresHistory(md.nearFuture(day)!!.token, now)
        assertEquals(277155.0, prevOi, 1e-9)
        assertTrue(futBars.size > 200 && futBars.last().oi > futBars.first().oi)
        val intra = md.intraday(KiteClient.TOKEN_NIFTY, now)
        assertTrue(intra.all { it.t >= Session.sessionStart(now) } && intra.size > 200)
        val daily = md.daily(KiteClient.TOKEN_NIFTY, now)
        assertTrue(daily.size > 150 && daily.all { it.t < Session.sessionStart(now) })

        // Engine runs on the Kite bundle, with OI history seeding the futures engine immediately.
        val snap = MarketSnapshot(now, b.nifty!!.copy(intraday = intra, daily = daily), b.bank, b.vix,
            b.futures!!.copy(prevOpenInterest = prevOi, intraday = futBars), chain, b.constituents, b.sectors, source = "kite-test")
        val out = NiftyDirectionEngine().process(snap)
        val fut = out.signals.getValue("Futures")
        println("Futures: ${fut.tags} ${fut.details}")
        assertTrue(fut.tags.contains("DAY_SHORT_BUILDUP"))
        assertTrue(fut.tags.any { it.startsWith("30M_") }) // intraday window available on first cycle
        assertEquals(1.0, out.direction.pBull + out.direction.pBear + out.direction.pRange, 1e-9)
        println("Kite-fed engine: bull %.2f bear %.2f regime %s best %s".format(out.direction.pBull, out.direction.pBear,
            out.regime.regime, out.options.best?.let { "${it.strike.toInt()}${it.type} ₹%.1f".format(it.premium) }))
    }

    @Test fun expiredTokenSurfacesAsTokenException() {
        val md = KiteMarketData(KiteApi("key", "stale", server.url("").toString().trimEnd('/')), cache())
        try { md.collect(now, listOf("RELIANCE"), 5, spot); fail("expected exception") } catch (e: KiteException) {
            assertTrue(e.tokenExpired)
        }
    }

    @Suppress("unused") private val utc = ZoneOffset.UTC
}
