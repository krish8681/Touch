package com.niftyengine.app.data

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.FiiDerivDay
import com.niftyengine.engine.model.FlowData
import com.niftyengine.engine.model.FuturesData
import com.niftyengine.engine.model.GiftNiftyData
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.Sector
import com.niftyengine.engine.model.ValuationData
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * NSE public JSON endpoints (the same ones nseindia.com pages use). They require browser-like headers
 * and session cookies, obtained by first loading an HTML page. NSE may rate-limit or change these
 * endpoints; every call is defensive and failures are reported per feed.
 */
object NseClient {
    private const val BASE = "https://www.nseindia.com"
    private var warmedAt = 0L
    private val dateFmt = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)

    /** NSE index name → engine sector. */
    val SECTOR_INDICES: Map<String, Sector> = mapOf(
        "NIFTY BANK" to Sector.BANK, "NIFTY FINANCIAL SERVICES" to Sector.FIN_SERVICES, "NIFTY IT" to Sector.IT,
        "NIFTY ENERGY" to Sector.ENERGY, "NIFTY AUTO" to Sector.AUTO, "NIFTY FMCG" to Sector.FMCG,
        "NIFTY PHARMA" to Sector.PHARMA, "NIFTY METAL" to Sector.METALS, "NIFTY INFRASTRUCTURE" to Sector.INFRA,
        "NIFTY CONSUMER DURABLES" to Sector.CONSUMER,
    )

    @Synchronized private fun warm(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - warmedAt < 4 * 60_000L) return
        runCatching { Http.get("$BASE/", mapOf("Accept" to "text/html")) }
        runCatching { Http.get("$BASE/option-chain", mapOf("Accept" to "text/html")) }
        warmedAt = System.currentTimeMillis()
    }

    private fun api(path: String): String {
        warm()
        val headers = mapOf("Accept" to "application/json, text/plain, */*", "Referer" to "$BASE/option-chain", "X-Requested-With" to "XMLHttpRequest")
        return try { Http.get("$BASE$path", headers) } catch (e: Exception) {
            warm(force = true)
            Http.get("$BASE$path", headers)
        }
    }

    private fun JSONObject.num(vararg keys: String): Double {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)
            when (v) {
                is Number -> return v.toDouble()
                is String -> v.replace(",", "").trim().toDoubleOrNull()?.let { return it }
            }
        }
        return Double.NaN
    }

    data class IndexBoard(
        val nifty: InstrumentData?, val bank: InstrumentData?, val vix: InstrumentData?, val sectors: Map<Sector, InstrumentData>,
        /** NIFTY 50 P/E, P/B and dividend yield as published with the index. */
        val valuation: ValuationData? = null,
    )

    fun allIndices(): IndexBoard {
        val root = JSONObject(api("/api/allIndices"))
        val data = root.getJSONArray("data")
        val asOf = TimeParse.ist(root.optString("timestamp"))
        val map = HashMap<String, InstrumentData>()
        var valuation: ValuationData? = null
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val name = o.optString("index", o.optString("indexSymbol"))
            map[name] = InstrumentData(name, o.num("last"), o.num("previousClose"), o.num("open"), o.num("high"), o.num("low"), asOf = asOf)
            if (name == "NIFTY 50") o.num("pe").takeIf { !it.isNaN() && it > 0 }?.let { pe ->
                valuation = ValuationData(pe, o.num("pb"), o.num("dy"), asOf, "NSE")
            }
        }
        return IndexBoard(map["NIFTY 50"], map["NIFTY BANK"], map["INDIA VIX"],
            SECTOR_INDICES.mapNotNull { (n, s) -> map[n]?.let { s to it } }.toMap(), valuation)
    }

    /**
     * NIFTY 50 constituents with live price and free-float market cap (→ live index weights).
     * Current site: NextApi `getIndicesData`; legacy `/api/equity-stockIndices` kept as a fallback.
     */
    fun nifty50Constituents(): Map<String, InstrumentData> {
        val idx = URLEncoder.encode("NIFTY 50", "UTF-8")
        val data: JSONArray = runCatching {
            JSONObject(api("/api/NextApi/apiClient/marketWatchApi?functionName=getIndicesData&symbol=$idx"))
                .getJSONObject("data").getJSONArray("data")
        }.getOrElse { JSONObject(api("/api/equity-stockIndices?index=$idx")).getJSONArray("data") }
        val out = HashMap<String, InstrumentData>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val sym = o.optString("symbol")
            if (sym.isBlank() || sym == "NIFTY 50" || o.optInt("priority", 0) == 1) continue
            out[sym] = InstrumentData(sym, o.num("lastPrice"), o.num("previousClose"), o.num("open"), o.num("dayHigh"), o.num("dayLow"),
                o.num("totalTradedVolume").let { if (it.isNaN()) 0.0 else it }, freeFloatMcap = o.num("ffmc"),
                asOf = TimeParse.ist(o.optString("lastUpdateTime")))
        }
        if (out.isEmpty()) throw IllegalStateException("no constituents in response")
        return out
    }

    /**
     * Intraday index path from NSE (`getIndexChart`). NSE encodes IST wall-clock time as if it were UTC,
     * so 5h30m is subtracted. Used when Yahoo bars are unavailable.
     */
    fun indexIntraday(index: String = "NIFTY 50"): List<Candle> = indexChart(index, "1D")

    /** Daily closes for ~1 year (close-only candles), fallback when Yahoo daily history is unavailable. */
    fun indexDaily(index: String, todayStartMs: Long): List<Candle> = indexChart(index, "1Y")
        .groupBy { Session.zdt(it.t).toLocalDate() }.toSortedMap()
        .map { (d, l) -> l.last().copy(t = d.atTime(Session.OPEN).atZone(Session.IST).toInstant().toEpochMilli()) }
        .filter { it.t < todayStartMs }

    private fun indexChart(index: String, flag: String): List<Candle> {
        val j = JSONObject(api("/api/NextApi/apiClient/marketWatchApi?functionName=getIndexChart&&identifier=" +
            URLEncoder.encode(index, "UTF-8") + "&flag=$flag"))
        val arr = j.optJSONArray("grapthData") ?: j.optJSONObject("data")?.optJSONArray("grapthData") ?: return emptyList()
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.getJSONArray(i)
            val t = p.getLong(0) - 19_800_000L
            val px = p.getDouble(1)
            if (out.isNotEmpty() && out.last().t >= t) continue
            out += Candle(t, px, px, px, px)
        }
        return out
    }

    private fun expiryMillis(s: String): Long = Session.closeOf(LocalDate.parse(s.trim(), dateFmt))

    /** Listed NIFTY option expiries (dd-MMM-yyyy), nearest first. */
    fun optionExpiries(): List<String> {
        val info = JSONObject(api("/api/option-chain-contract-info?symbol=NIFTY")).getJSONArray("expiryDates")
        return (0 until info.length()).map { info.getString(it) }
    }

    /**
     * The next MONTHLY expiry from the listed expiries: the last listed expiry in the month of the nearest one
     * (this also handles holiday-shifted expiries). Null when the nearest expiry is itself the monthly one.
     */
    fun monthlyExpiryOf(expiries: List<String>): String? {
        val dates = expiries.mapNotNull { e -> runCatching { LocalDate.parse(e.trim(), dateFmt) to e }.getOrNull() }.sortedBy { it.first }
        val first = dates.firstOrNull() ?: return null
        val monthly = dates.filter { it.first.year == first.first.year && it.first.month == first.first.month }.maxBy { it.first }
        return if (monthly.first == first.first) null else monthly.second
    }

    /** Option chain for [expiry] (nearest when null). v3 endpoint first, legacy endpoint as fallback. */
    fun optionChain(expiry: String? = null): OptionChain {
        val raw = runCatching {
            val exp = expiry ?: optionExpiries().first()
            JSONObject(api("/api/option-chain-v3?type=Indices&symbol=NIFTY&expiry=" + URLEncoder.encode(exp, "UTF-8"))) to exp
        }.getOrElse {
            val j = JSONObject(api("/api/option-chain-indices?symbol=NIFTY"))
            j to (expiry ?: j.getJSONObject("records").getJSONArray("expiryDates").getString(0))
        }
        val (json, expiry) = raw
        val records = json.getJSONObject("records")
        val underlying = records.num("underlyingValue")
        val data = records.getJSONArray("data")
        val rows = ArrayList<OptionStrikeRow>()
        fun leg(o: JSONObject?): OptionLeg = if (o == null) OptionLeg() else OptionLeg(
            oi = o.num("openInterest").nz(), changeOi = o.num("changeinOpenInterest").nz(),
            volume = o.num("totalTradedVolume").nz(), iv = o.num("impliedVolatility"),
            ltp = o.num("lastPrice").nz(), bid = o.num("buyPrice1", "bidprice", "bidPrice").nz(),
            ask = o.num("sellPrice1", "askPrice", "askprice").nz(),
        )
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val e = o.optString("expiryDate", o.optString("expiryDates", expiry))
            if (e.isNotBlank() && !e.equals(expiry, true)) continue
            rows += OptionStrikeRow(o.num("strikePrice"), leg(o.optJSONObject("CE")), leg(o.optJSONObject("PE")))
        }
        rows.sortBy { it.strike }
        val step = rows.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: 50.0
        return OptionChain(underlying, expiry, expiryMillis(expiry), rows, step, asOf = TimeParse.ist(records.optString("timestamp")))
    }

    /** Near-month NIFTY futures (price, OI, ΔOI, volume). Current NextApi endpoint first, legacy fallback. */
    fun futures(): FuturesData = runCatching { futuresNextApi() }.getOrElse { futuresLegacy() }

    private fun futuresNextApi(): FuturesData {
        val data = JSONObject(api("/api/NextApi/apiClient/GetQuoteApi?functionName=getSymbolDerivativesData&symbol=NIFTY")).getJSONArray("data")
        var best: JSONObject? = null
        var bestExp = Long.MAX_VALUE
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            if (o.optString("instrumentType") != "FUTIDX") continue
            val exp = expiryMillis(o.getString("expiryDate"))
            if (exp < bestExp) { bestExp = exp; best = o }
        }
        val o = best ?: throw IllegalStateException("no FUTIDX rows")
        val oi = o.num("openInterest"); val chg = o.num("changeinOpenInterest")
        return FuturesData(
            symbol = o.optString("identifier", "NIFTY FUT"), expiry = o.getString("expiryDate"),
            last = o.num("lastPrice"), prevClose = o.num("prevClose"),
            openInterest = oi.nz(), prevOpenInterest = if (oi.isNaN() || chg.isNaN()) Double.NaN else oi - chg,
            volume = o.num("totalTradedVolume").nz(),
        )
    }

    private fun futuresLegacy(): FuturesData {
        val stocks = JSONObject(api("/api/quote-derivative?symbol=NIFTY")).getJSONArray("stocks")
        var best: FuturesData? = null
        var bestExp = Long.MAX_VALUE
        for (i in 0 until stocks.length()) {
            val s = stocks.getJSONObject(i)
            val meta = s.getJSONObject("metadata")
            if (!meta.optString("instrumentType").contains("Futures", true)) continue
            val exp = expiryMillis(meta.getString("expiryDate"))
            if (exp >= bestExp) continue
            val ti = s.optJSONObject("marketDeptOrderBook")?.optJSONObject("tradeInfo") ?: JSONObject()
            val oi = ti.num("openInterest")
            val chg = ti.num("changeinOpenInterest")
            best = FuturesData(
                symbol = meta.optString("identifier", "NIFTY FUT"), expiry = meta.getString("expiryDate"),
                last = meta.num("lastPrice"), prevClose = meta.num("prevClose"),
                openInterest = oi.nz(), prevOpenInterest = if (oi.isNaN() || chg.isNaN()) Double.NaN else oi - chg,
                volume = meta.num("numberOfContractsTraded").nz(),
            )
            bestExp = exp
        }
        return best ?: throw IllegalStateException("no futures in response")
    }

    /** GIFT Nifty (NSE IX near-month NIFTY futures) — trades while NSE is closed. NextApi `getGiftNifty`. */
    fun giftNifty(): GiftNiftyData {
        val g = JSONObject(api("/api/NextApi/apiClient?functionName=getGiftNifty")).getJSONObject("data").getJSONObject("giftNifty")
        val last = g.num("lastprice")
        if (last.isNaN() || last <= 0) throw IllegalStateException("no GIFT Nifty price")
        return GiftNiftyData(
            last = last, change = g.num("daychange").nz(), changePct = g.num("perchange").nz(),
            expiry = g.optString("expirydate"), contracts = g.num("contractstraded").nz(),
            asOf = TimeParse.ist(g.optString("timestmp")),
        )
    }

    private val archiveFmt = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ENGLISH)

    /**
     * FII positioning in index derivatives for [day] from NSE's participant-wise open interest file
     * (`fao_participant_oi_DDMMYYYY.csv`, published after the close). Null when NSE has no file for that day (holiday).
     */
    fun participantOi(day: LocalDate): FiiDerivDay? {
        val body = try {
            Http.get("https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_${day.format(archiveFmt)}.csv", mapOf("Accept" to "text/csv,*/*"))
        } catch (e: java.io.IOException) {
            if (e.message?.contains("HTTP 404") == true) return null else throw e
        }
        return parseParticipantOi(body, day)
    }

    fun parseParticipantOi(csv: String, day: LocalDate): FiiDerivDay? {
        val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val hi = lines.indexOfFirst { it.startsWith("Client Type", ignoreCase = true) }
        if (hi < 0) return null
        val header = lines[hi].split(",").map { it.trim().lowercase() }
        val fii = lines.drop(hi + 1).map { l -> l.split(",").map { it.trim().trim('"') } }.firstOrNull { it.firstOrNull()?.equals("FII", true) == true } ?: return null
        fun col(name: String): Double = header.indexOf(name).takeIf { it >= 0 }?.let { fii.getOrNull(it)?.replace(",", "")?.toDoubleOrNull() } ?: Double.NaN
        val d = FiiDerivDay(day.toString(), col("future index long"), col("future index short"), col("option index call long"),
            col("option index call short"), col("option index put long"), col("option index put short"))
        return if (d.futIndexLong.isNaN() || d.futIndexShort.isNaN()) null else d
    }

    fun fiiDii(): FlowData {
        val arr = JSONArray(api("/api/fiidiiTradeReact"))
        var fpi = 0.0; var dii = 0.0; var date = ""
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cat = o.optString("category").uppercase()
            val net = o.num("netValue").nz()
            if (cat.startsWith("FII") || cat.contains("FPI")) { fpi = net; date = o.optString("date") }
            if (cat.startsWith("DII")) dii = net
        }
        return FlowData(fpiNetCr = fpi, diiNetCr = dii, date = date, asOf = TimeParse.istDate(date))
    }

    private fun Double.nz() = if (isNaN()) 0.0 else this
}
