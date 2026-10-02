package com.niftyengine.app.data

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.FuturesBar
import com.niftyengine.engine.model.FuturesData
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.Sector
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Kite API error; [type] is Kite's `error_type` (e.g. TokenException when the daily token expired). */
class KiteException(val type: String, message: String) : IOException("$type: $message") {
    val tokenExpired get() = type == "TokenException"
}

/**
 * Zerodha Kite Connect v3.
 *
 * Login: open [loginUrl] → user logs in → Kite redirects to the app's redirect URL with `request_token`
 * → [createSession] exchanges it (checksum = SHA-256(api_key + request_token + api_secret)).
 * Access tokens expire every day (~06:00 IST), so a fresh login is needed each trading day.
 */
object KiteClient {
    const val API = "https://api.kite.trade"

    /** Well-known index instrument tokens. */
    const val TOKEN_NIFTY = 256265L
    const val TOKEN_BANKNIFTY = 260105L
    const val TOKEN_VIX = 264969L

    fun loginUrl(apiKey: String) = "https://kite.zerodha.com/connect/login?v=3&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"

    fun createSession(apiKey: String, apiSecret: String, requestToken: String): String {
        val checksum = MessageDigest.getInstance("SHA-256").digest((apiKey + requestToken + apiSecret).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val req = Request.Builder().url("$API/session/token")
            .header("X-Kite-Version", "3")
            .post(FormBody.Builder().add("api_key", apiKey).add("request_token", requestToken).add("checksum", checksum).build())
            .build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw parseError(r.code, body)
            return JSONObject(body).getJSONObject("data").getString("access_token")
        }
    }

    fun parseError(code: Int, body: String): KiteException = runCatching {
        val j = JSONObject(body)
        KiteException(j.optString("error_type", "HTTP$code"), j.optString("message", body.take(120)))
    }.getOrElse { KiteException("HTTP$code", body.take(120)) }
}

/** Authenticated Kite REST calls. */
class KiteApi(private val apiKey: String, private val accessToken: String, private val baseUrl: String = KiteClient.API) {
    private fun get(path: String): String {
        val req = Request.Builder().url(baseUrl + path)
            .header("X-Kite-Version", "3")
            .header("Authorization", "token $apiKey:$accessToken")
            .build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw KiteClient.parseError(r.code, body)
            return body
        }
    }

    /** Full quotes (OHLC, OI, depth). Kite allows up to 500 instruments per call; unknown keys are simply omitted. */
    fun quotes(keys: List<String>): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        keys.distinct().chunked(500).forEach { batch ->
            val q = batch.joinToString("&") { "i=" + URLEncoder.encode(it, "UTF-8") }
            val data = JSONObject(get("/quote?$q")).getJSONObject("data")
            data.keys().forEach { k -> out[k] = data.getJSONObject(k) }
        }
        return out
    }

    data class HistBar(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double, val oi: Double)

    private val histFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Historical candles (requires the historical-data entitlement of the Kite Connect plan). */
    fun historical(token: Long, interval: String, from: LocalDateTime, to: LocalDateTime, oi: Boolean = false): List<HistBar> {
        val path = "/instruments/historical/$token/$interval?from=" + URLEncoder.encode(from.format(histFmt), "UTF-8") +
            "&to=" + URLEncoder.encode(to.format(histFmt), "UTF-8") + if (oi) "&oi=1" else ""
        val arr = JSONObject(get(path)).getJSONObject("data").getJSONArray("candles")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONArray(i)
            HistBar(parseKiteTime(c.getString(0)),
                c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.optDouble(5, 0.0),
                if (c.length() > 6) c.optDouble(6, 0.0) else 0.0)
        }
    }

    fun instrumentsCsv(exchange: String): String = get("/instruments/$exchange")

    companion object {
        private val kiteTs = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
        /** Kite returns e.g. `2026-10-01T09:15:00+0530` (offset without colon); ISO form accepted too. */
        fun parseKiteTime(s: String): Long = runCatching { OffsetDateTime.parse(s, kiteTs) }
            .getOrElse { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.toInstant().toEpochMilli()
    }
}

/** One row of Kite's instruments dump. */
data class KiteInstrument(
    val token: Long, val tradingSymbol: String, val name: String, val expiry: LocalDate?, val strike: Double,
    val lotSize: Int, val type: String, val segment: String, val exchange: String,
) { val key get() = "$exchange:$tradingSymbol" }

object KiteInstruments {
    /** Parses the CSV dump (instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange). */
    fun parse(csv: String, filter: (name: String, segment: String) -> Boolean = { _, _ -> true }): List<KiteInstrument> {
        val lines = csv.lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()
        val header = lines.next().split(',')
        fun idx(n: String) = header.indexOf(n)
        val iTok = idx("instrument_token"); val iSym = idx("tradingsymbol"); val iName = idx("name"); val iExp = idx("expiry")
        val iStrike = idx("strike"); val iLot = idx("lot_size"); val iType = idx("instrument_type"); val iSeg = idx("segment"); val iEx = idx("exchange")
        val out = ArrayList<KiteInstrument>()
        for (line in lines) {
            if (line.isBlank()) continue
            val f = splitCsv(line)
            if (f.size < header.size) continue
            val name = f[iName]; val seg = f[iSeg]
            if (!filter(name, seg)) continue
            out += KiteInstrument(
                f[iTok].toLongOrNull() ?: continue, f[iSym], name,
                f[iExp].takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                f[iStrike].toDoubleOrNull() ?: 0.0, f[iLot].toIntOrNull() ?: 0, f[iType], seg, f[iEx],
            )
        }
        return out
    }

    private fun splitCsv(line: String): List<String> {
        if ('"' !in line) return line.split(',')
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when {
            ch == '"' -> q = !q
            ch == ',' && !q -> { out += sb.toString(); sb.setLength(0) }
            else -> sb.append(ch)
        }
        out += sb.toString()
        return out
    }
}

/**
 * Builds engine inputs from Kite: index/sector/stock quotes, near-month futures (+ OI history),
 * and the nearest-expiry NIFTY option chain with IV implied from traded prices.
 */
class KiteMarketData(private val api: KiteApi, private val cacheDir: File) {
    /** Kite trading symbols of NSE sector indices (unknown ones are just absent from the quote response). */
    private val sectorKeys = mapOf(
        "NSE:NIFTY BANK" to Sector.BANK, "NSE:NIFTY FIN SERVICE" to Sector.FIN_SERVICES, "NSE:NIFTY IT" to Sector.IT,
        "NSE:NIFTY ENERGY" to Sector.ENERGY, "NSE:NIFTY AUTO" to Sector.AUTO, "NSE:NIFTY FMCG" to Sector.FMCG,
        "NSE:NIFTY PHARMA" to Sector.PHARMA, "NSE:NIFTY METAL" to Sector.METALS, "NSE:NIFTY INFRA" to Sector.INFRA,
        "NSE:NIFTY CONSR DURBL" to Sector.CONSUMER,
    )

    private var nfo: List<KiteInstrument> = emptyList()
    private var nfoDay: LocalDate? = null

    /** NIFTY futures + options from the NFO dump, cached on disk once per day (~5 MB download). */
    @Synchronized fun niftyDerivatives(today: LocalDate): List<KiteInstrument> {
        if (nfoDay == today && nfo.isNotEmpty()) return nfo
        cacheDir.mkdirs()
        val f = File(cacheDir, "kite-nfo-$today.csv")
        val csv = if (f.exists() && f.length() > 1000) f.readText() else api.instrumentsCsv("NFO").also { body ->
            cacheDir.listFiles { x -> x.name.startsWith("kite-nfo-") }?.forEach { it.delete() }
            f.writeText(body)
        }
        nfo = KiteInstruments.parse(csv) { name, seg -> name == "NIFTY" && (seg == "NFO-OPT" || seg == "NFO-FUT") }
        nfoDay = today
        return nfo
    }

    fun nearFuture(today: LocalDate): KiteInstrument? = niftyDerivatives(today)
        .filter { it.segment == "NFO-FUT" && it.expiry != null && !it.expiry.isBefore(today) }.minByOrNull { it.expiry!! }

    data class Bundle(
        val nifty: InstrumentData?, val bank: InstrumentData?, val vix: InstrumentData?,
        val sectors: Map<Sector, InstrumentData>, val constituents: Map<String, InstrumentData>,
        val futures: FuturesData?, val chain: OptionChain?,
    )

    /** Kite quote timestamp ("yyyy-MM-dd HH:mm:ss" IST); falls back to last_trade_time. */
    private fun ts(q: JSONObject): Long = TimeParse.ist(q.optString("timestamp")).takeIf { it > 0 } ?: TimeParse.ist(q.optString("last_trade_time"))

    private fun inst(q: JSONObject?, name: String): InstrumentData? = q?.let {
        val ohlc = it.optJSONObject("ohlc") ?: JSONObject()
        InstrumentData(name, it.optDouble("last_price"), ohlc.optDouble("close"), ohlc.optDouble("open"),
            ohlc.optDouble("high"), ohlc.optDouble("low"), it.optDouble("volume", 0.0), asOf = ts(it))
    }

    private fun leg(q: JSONObject?, isCall: Boolean, spot: Double, k: Double, tYears: Double, now: Long): OptionLeg {
        if (q == null) return OptionLeg()
        val depth = q.optJSONObject("depth")
        val bid = depth?.optJSONArray("buy")?.optJSONObject(0)?.optDouble("price", 0.0) ?: 0.0
        val ask = depth?.optJSONArray("sell")?.optJSONObject(0)?.optDouble("price", 0.0) ?: 0.0
        val ltp = q.optDouble("last_price", 0.0)
        val mid = if (bid > 0 && ask > 0) (bid + ask) / 2 else ltp
        val iv = if (mid > 0) BlackScholes.impliedVol(isCall, spot, k, tYears, mid) * 100 else Double.NaN
        val lastTrade = TimeParse.ist(q.optString("last_trade_time"))
        return OptionLeg(oi = q.optDouble("oi", 0.0), changeOi = 0.0, volume = q.optDouble("volume", 0.0),
            iv = iv, ltp = ltp, bid = bid, ask = ask,
            lastTradeAgeSec = if (lastTrade > 0) ((now - lastTrade) / 1000.0).coerceAtLeast(0.0) else Double.NaN)
    }

    /**
     * One quote round-trip for everything. [constituentSymbols] are NSE symbols; [strikesEachSide] around ATM.
     * Kite has no "change in OI" field, so ΔOI is left 0 here and merged from NSE by the caller when available.
     */
    fun collect(now: Long, constituentSymbols: Collection<String>, strikesEachSide: Int = 20, spotHint: Double = Double.NaN): Bundle {
        val today = Session.zdt(now).toLocalDate()
        val fut = runCatching { nearFuture(today) }.getOrNull()
        val derivs = runCatching { niftyDerivatives(today) }.getOrDefault(emptyList())
        val opts = derivs.filter { it.segment == "NFO-OPT" && it.expiry != null && !it.expiry.isBefore(today) }
        val expiry = opts.minOfOrNull { it.expiry!! }
        val expiryOpts = opts.filter { it.expiry == expiry }

        // Pass 1 (cheap) only if no spot hint: need spot to choose strikes.
        val spot0 = if (!spotHint.isNaN() && spotHint > 0) spotHint
        else api.quotes(listOf("NSE:NIFTY 50"))["NSE:NIFTY 50"]?.optDouble("last_price") ?: Double.NaN
        val strikes = expiryOpts.map { it.strike }.distinct().sorted()
        val chosen = if (spot0.isNaN() || strikes.isEmpty()) emptyList() else {
            val atmIdx = strikes.indices.minBy { abs(strikes[it] - spot0) }
            strikes.subList((atmIdx - strikesEachSide).coerceAtLeast(0), (atmIdx + strikesEachSide + 1).coerceAtMost(strikes.size))
        }.toSet()
        val optKeys = expiryOpts.filter { it.strike in chosen }

        val keys = buildList {
            add("NSE:NIFTY 50"); add("NSE:INDIA VIX"); addAll(sectorKeys.keys)
            constituentSymbols.forEach { add("NSE:$it") }
            fut?.let { add(it.key) }
            optKeys.forEach { add(it.key) }
        }
        val q = api.quotes(keys)
        val nifty = inst(q["NSE:NIFTY 50"], "NIFTY 50")
        val spot = nifty?.last ?: spot0

        val futures = fut?.let { f ->
            q[f.key]?.let { fq ->
                FuturesData(f.tradingSymbol, f.expiry.toString(), fq.optDouble("last_price"),
                    fq.optJSONObject("ohlc")?.optDouble("close") ?: Double.NaN, fq.optDouble("oi", 0.0), Double.NaN, fq.optDouble("volume", 0.0),
                    asOf = ts(fq))
            }
        }
        val chain = if (expiry == null || optKeys.isEmpty() || spot.isNaN()) null else {
            val expMs = Session.closeOf(expiry)
            val tY = Session.yearsToExpiry(now, expMs)
            val byStrike = optKeys.groupBy { it.strike }
            val rows = byStrike.keys.sorted().map { k ->
                val ce = byStrike[k]!!.firstOrNull { it.type == "CE" }
                val pe = byStrike[k]!!.firstOrNull { it.type == "PE" }
                OptionStrikeRow(k, leg(ce?.let { q[it.key] }, true, spot, k, tY, now), leg(pe?.let { q[it.key] }, false, spot, k, tY, now))
            }
            val step = rows.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: 50.0
            val chainTs = optKeys.mapNotNull { q[it.key]?.let(::ts) }.filter { it > 0 }.maxOrNull() ?: 0L
            OptionChain(spot, expiry.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)), expMs, rows, step, asOf = chainTs)
        }
        return Bundle(
            nifty = nifty,
            bank = inst(q["NSE:NIFTY BANK"], "NIFTY BANK"),
            vix = inst(q["NSE:INDIA VIX"], "INDIA VIX"),
            sectors = sectorKeys.mapNotNull { (k, s) -> inst(q[k], "SECTOR_${s.name}")?.let { s to it } }.toMap(),
            constituents = constituentSymbols.mapNotNull { sym -> inst(q["NSE:$sym"], sym)?.let { sym to it } }.toMap(),
            futures = futures,
            chain = chain,
        )
    }

    /** Today's (or last session's) 1-minute bars. */
    fun intraday(token: Long, now: Long): List<Candle> {
        val to = Session.zdt(now).toLocalDateTime()
        return api.historical(token, "minute", to.minusDays(4), to).let { bars ->
            val last = bars.lastOrNull()?.t ?: return emptyList()
            bars.filter { it.t >= Session.sessionStart(last) }.map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
        }
    }

    /** ~1 year of daily bars ending before today's session. */
    fun daily(token: Long, now: Long): List<Candle> {
        val to = Session.zdt(now).toLocalDateTime()
        val start = Session.sessionStart(now)
        return api.historical(token, "day", to.minusDays(370), to).filter { it.t < start }
            .map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
    }

    /** Futures 1-minute bars with OI for the latest session, plus previous-session closing OI. */
    fun futuresHistory(token: Long, now: Long): Pair<List<FuturesBar>, Double> {
        val to = Session.zdt(now).toLocalDateTime()
        val bars = api.historical(token, "minute", to.minusDays(4), to, oi = true)
        val last = bars.lastOrNull()?.t ?: return emptyList<FuturesBar>() to Double.NaN
        val start = Session.sessionStart(last)
        val prevOi = bars.lastOrNull { it.t < start && it.oi > 0 }?.oi ?: Double.NaN
        return bars.filter { it.t >= start }.map { FuturesBar(it.t, it.c, it.oi, it.v) } to prevOi
    }
}
