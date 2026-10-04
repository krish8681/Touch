package com.niftyengine.app.data

import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.store.DataMode
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.Constituents
import com.niftyengine.engine.engines.SnapshotProvider
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.OptionChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 01 — Data Collector (live). Gathers every feed in parallel and merges by priority:
 *
 *  Kite mode:  Kite → NIFTY/Bank/VIX/sectors/50 stocks/futures/option chain (one quote call),
 *              Kite historical → 1-min NIFTY & VIX bars, futures bars with OI, 1-year daily history;
 *              NSE → free-float weights, ΔOI per strike, FII/DII (what Kite does not publish);
 *              Yahoo/RSS → global markets and news.
 *  Public mode (or Kite failure): NSE + Yahoo + RSS.
 *
 * Per-feed health is reported in [MarketSnapshot.feedStatus].
 */
class LiveSnapshotProvider(private val cacheDir: File, private val settings: () -> AppSettings) : SnapshotProvider {
    override val name = "Live"

    private fun today() = Session.sessionStart(System.currentTimeMillis())

    // ---- public feeds
    private val niftyDaily = Cached(6 * 3_600_000L) {
        runCatching { YahooClient.dailyHistory("^NSEI", today()) }.getOrElse { NseClient.indexDaily("NIFTY 50", today()) }
    }
    private val vixDaily = Cached(6 * 3_600_000L) {
        runCatching { YahooClient.dailyHistory("^INDIAVIX", today()) }.getOrElse { NseClient.indexDaily("INDIA VIX", today()) }
    }
    private val niftyIntra = Cached(55_000L) { YahooClient.chart("^NSEI", "1m", "1d", "NIFTY 50") }
    private val nseIntra = Cached(55_000L) { NseClient.indexIntraday("NIFTY 50") }
    private val vixIntra = Cached(55_000L) { YahooClient.chart("^INDIAVIX", "1m", "1d", "INDIA VIX") }
    private val bankIntra = Cached(55_000L) { YahooClient.chart("^NSEBANK", "5m", "1d", "NIFTY BANK") }
    private val board = Cached(10_000L) { NseClient.allIndices() }
    private val constituents = Cached(20_000L) { NseClient.nifty50Constituents() }
    /** Free-float caps change slowly; in Kite mode NSE is only polled for weights. */
    private val weights = Cached(6 * 3_600_000L) { NseClient.nifty50Constituents() }
    private val chain = Cached(45_000L) { NseClient.optionChain() }
    private val futures = Cached(20_000L) { NseClient.futures() }
    private val flows = Cached(30 * 60_000L) { NseClient.fiiDii() }
    private val gift = Cached(60_000L) { NseClient.giftNifty() }
    private val news = Cached(3 * 60_000L) { NewsClient.fetchAll() }
    private val global = YahooClient.GLOBAL_SYMBOLS.mapValues { (a, sym) ->
        Cached(if (a == GlobalAsset.USDINR || a == GlobalAsset.BRENT) 60_000L else 120_000L) { YahooClient.chart(sym, "5m", "1d", "G_${a.name}") }
    }
    /** v5: ~2 months of daily closes per global asset → its normal daily move ("Nasdaq +1 % = 4× normal"). */
    private val globalDaily = YahooClient.GLOBAL_SYMBOLS.mapValues { (_, sym) ->
        Cached(6 * 3_600_000L) { YahooClient.chart(sym, "1d", "2mo").daily.filter { it.t < today() - 6 * 3_600_000L } }
    }

    // ---- Kite feeds (rebuilt when credentials change)
    private var kiteCreds = ""
    private var kite: KiteMarketData? = null
    private var lastSpot = Double.NaN
    private lateinit var kQuotes: Cached<KiteMarketData.Bundle>
    private lateinit var kHistory: Cached<KiteHist>
    private lateinit var kDaily: Cached<Pair<List<com.niftyengine.engine.model.Candle>, List<com.niftyengine.engine.model.Candle>>>
    /** First OI seen today per "strike|CE/PE" — ΔOI fallback when NSE's chain is unavailable. */
    private val openingOi = HashMap<String, Double>()
    private var openingOiDay = 0L

    private data class KiteHist(val nifty: List<com.niftyengine.engine.model.Candle>, val vix: List<com.niftyengine.engine.model.Candle>,
                                val fut: List<com.niftyengine.engine.model.FuturesBar>, val futPrevOi: Double)

    private fun kiteFor(s: AppSettings): KiteMarketData? {
        if (s.mode != DataMode.LIVE_KITE || s.kiteApiKey.isBlank() || s.kiteAccessToken.isBlank()) return null
        val creds = s.kiteApiKey + ":" + s.kiteAccessToken
        if (creds != kiteCreds || kite == null) {
            kiteCreds = creds
            val k = KiteMarketData(KiteApi(s.kiteApiKey, s.kiteAccessToken), cacheDir)
            kite = k
            kQuotes = Cached(2_000L) {
                val syms = weights.get()?.keys?.takeIf { it.size >= 40 } ?: Constituents.DEFAULT.map { it.symbol }
                k.collect(System.currentTimeMillis(), syms, 20, lastSpot)
            }
            kHistory = Cached(60_000L) {
                val now = System.currentTimeMillis()
                // Sequential on purpose: Kite allows ~3 historical requests/second.
                val n = k.intraday(KiteClient.TOKEN_NIFTY, now)
                val v = runCatching { k.intraday(KiteClient.TOKEN_VIX, now) }.getOrDefault(emptyList())
                val f = k.nearFuture(Session.zdt(now).toLocalDate())?.let { runCatching { k.futuresHistory(it.token, now) }.getOrNull() }
                KiteHist(n, v, f?.first ?: emptyList(), f?.second ?: Double.NaN)
            }
            kDaily = Cached(6 * 3_600_000L) {
                val now = System.currentTimeMillis()
                k.daily(KiteClient.TOKEN_NIFTY, now) to runCatching { k.daily(KiteClient.TOKEN_VIX, now) }.getOrDefault(emptyList())
            }
        }
        return kite
    }

    override fun collect(now: Long): MarketSnapshot = runBlocking(Dispatchers.IO) {
        val s = settings()
        val status = LinkedHashMap<String, String>()
        val k = kiteFor(s)

        // ---------------- Kite path
        var kb: KiteMarketData.Bundle? = null
        var kh: KiteHist? = null
        var kd: Pair<List<com.niftyengine.engine.model.Candle>, List<com.niftyengine.engine.model.Candle>>? = null
        if (k != null) {
            val jobs = listOf(async { weights.get() }, async { kQuotes.get() }, async { kHistory.get() }, async { kDaily.get() })
            jobs.awaitAll()
            kb = kQuotes.get(); kh = kHistory.get(); kd = kDaily.get()
            val err = kQuotes.lastError
            status["Kite quotes"] = when {
                err == null && kb != null -> "✓ ${kb.constituents.size} stocks · ${kb.sectors.size} sectors · ${kb.chain?.rows?.size ?: 0} strikes" +
                    (kb.chain?.let { " exp ${it.expiry}" } ?: "")
                err != null && err.contains("TokenException") -> "✗ session expired — Setup → Login to Kite"
                else -> "✗ $err (falling back to NSE)"
            }
            status["Kite history"] = kHistory.lastError?.let { "✗ $it" }
                ?: "✓ ${kh?.nifty?.size ?: 0} NIFTY bars · ${kh?.fut?.size ?: 0} futures OI bars"
            if (kDaily.lastError != null) status["Kite daily"] = "✗ ${kDaily.lastError}"
            if (kQuotes.lastError != null) kb = null // stale bundle must not masquerade as live
        } else if (s.mode == DataMode.LIVE_KITE) status["Kite"] = "✗ not logged in (Setup → Login to Kite)"
        val kiteLive = kb?.nifty != null

        // ---------------- public feeds (full set when Kite is absent, gap-fillers otherwise)
        val jobs = buildList {
            add(async { flows.get() }); add(async { news.get() }); add(async { chain.get() }); add(async { gift.get() })
            global.values.forEach { c -> add(async { c.get() }) }
            globalDaily.values.forEach { c -> add(async { c.get() }) }
            if (!kiteLive) {
                add(async { board.get() }); add(async { constituents.get() }); add(async { futures.get() })
                add(async { niftyIntra.get() }); add(async { vixIntra.get() }); add(async { bankIntra.get() })
                add(async { if (niftyIntra.get() == null) nseIntra.get() else null })
            }
            if (kd?.first.isNullOrEmpty()) add(async { niftyDaily.get(); vixDaily.get() })
        }
        jobs.awaitAll()
        fun st(name: String, c: Cached<*>, ok: String) { status[name] = c.lastError?.let { "✗ $it" } ?: ok }

        val nseChain = chain.get()
        val dailyN = kd?.first?.takeIf { it.isNotEmpty() } ?: niftyDaily.get() ?: emptyList()
        val dailyV = kd?.second?.takeIf { it.isNotEmpty() } ?: vixDaily.get() ?: emptyList()

        val nifty: InstrumentData
        val vix: InstrumentData?
        val bank: InstrumentData?
        val stocks: Map<String, InstrumentData>
        val sectors: Map<com.niftyengine.engine.model.Sector, InstrumentData>
        val fut: com.niftyengine.engine.model.FuturesData?
        val optChain: OptionChain?
        if (kiteLive) {
            val b = kb!!
            nifty = b.nifty!!.copy(intraday = kh?.nifty ?: emptyList(), daily = dailyN)
            vix = b.vix?.copy(intraday = kh?.vix ?: emptyList(), daily = dailyV)
            bank = b.bank
            val ffmc = weights.get()
            stocks = b.constituents.mapValues { (sym, d) -> d.copy(freeFloatMcap = ffmc?.get(sym)?.freeFloatMcap ?: Double.NaN) }
            sectors = b.sectors
            fut = b.futures?.copy(prevOpenInterest = kh?.futPrevOi ?: Double.NaN, intraday = kh?.fut ?: emptyList())
            optChain = b.chain?.let { mergeDeltaOi(it, nseChain, now) }
            status["Weights"] = if (ffmc != null) "✓ NSE free-float" else "✗ ${weights.lastError} (static weights)"
            status["ΔOI"] = if (nseChain != null && nseChain.expiry.equals(b.chain?.expiry, true)) "✓ NSE change-in-OI"
            else "~ since first fetch today (NSE chain ✗ ${chain.lastError ?: "expiry mismatch"})"
        } else {
            val b = board.get()
            val yN = niftyIntra.get()
            val base = b?.nifty ?: yN ?: throw IllegalStateException(
                "No NIFTY price from any feed (Kite: ${if (k == null) "off" else kQuotes.lastError}, NSE: ${board.lastError}, Yahoo: ${niftyIntra.lastError})")
            nifty = base.copy(
                symbol = "NIFTY 50",
                prevClose = base.prevClose.takeIf { !it.isNaN() && it > 0 } ?: yN?.prevClose ?: Double.NaN,
                // Latest session's bars (today's, or the last trading day's when the market is closed).
                intraday = (yN?.intraday?.takeIf { it.isNotEmpty() } ?: nseIntra.get() ?: emptyList()).let { bars ->
                    val last = bars.lastOrNull()?.t ?: return@let bars
                    bars.filter { it.t >= Session.sessionStart(last) }
                },
                daily = dailyN,
            )
            vix = (b?.vix ?: vixIntra.get())?.copy(symbol = "INDIA VIX", intraday = vixIntra.get()?.intraday ?: emptyList(), daily = dailyV)
            bank = (b?.bank ?: bankIntra.get())?.copy(symbol = "NIFTY BANK", intraday = bankIntra.get()?.intraday ?: emptyList())
            stocks = constituents.get() ?: emptyMap()
            sectors = b?.sectors ?: emptyMap()
            fut = futures.get()
            optChain = nseChain
            st("NSE indices", board, "✓ ${b?.sectors?.size ?: 0} sectors")
            st("NSE constituents", constituents, "✓ ${stocks.size} stocks")
            st("NSE futures", futures, "✓ ${fut?.expiry ?: ""}")
            status["NIFTY bars"] = if (yN != null) "✓ Yahoo ${nifty.intraday.size} bars" else if (nseIntra.get() != null) "✓ NSE ${nifty.intraday.size} pts"
                else "✗ Yahoo: ${niftyIntra.lastError} · NSE: ${nseIntra.lastError} (using own ticks)"
        }
        lastSpot = nifty.last
        st("NSE option chain", chain, "✓ ${nseChain?.rows?.size ?: 0} strikes" + if (kiteLive) " (ΔOI only)" else ", exp ${nseChain?.expiry ?: "-"}")
        st("NSE FII/DII", flows, "✓ ${flows.get()?.date ?: ""}")
        st("GIFT Nifty", gift, gift.get()?.let { "✓ %.1f (%+.2f%%) @ %s".format(it.last, it.changePct, Session.hhmm(it.asOf)) } ?: "–")
        if (dailyN.isEmpty()) status["Daily history"] = "✗ ${kDaily.takeIf { k != null }?.lastError ?: niftyDaily.lastError}"
        val glob = global.mapNotNull { (a, c) -> c.get()?.let { a to it.copy(daily = globalDaily[a]?.get() ?: emptyList()) } }.toMap()
        val n = news.get()
        status["Global"] = "✓ ${glob.size}/${global.size}" + (global.entries.firstOrNull { it.value.lastError != null }?.let { " (✗ ${it.key.label}: ${it.value.lastError})" } ?: "")
        status["News"] = (news.lastError?.let { "✗ $it" } ?: "✓ ${n?.items?.size ?: 0} items") +
            (n?.errors?.takeIf { it.isNotEmpty() }?.let { " · ${it.size} feed(s) failed" } ?: "")

        MarketSnapshot(
            timestamp = now, nifty = nifty, bankNifty = bank, vix = vix, futures = fut, optionChain = optChain,
            constituents = stocks, sectors = sectors, global = glob, macro = s.macroInputs(), flows = flows.get(),
            giftNifty = gift.get(),
            news = n?.items ?: emptyList(),
            source = if (kiteLive) "LIVE · Kite (+NSE/Yahoo/RSS)" else "LIVE · NSE+Yahoo",
            feedStatus = status,
        )
    }

    /** Kite publishes OI but not change-in-OI: take NSE's ΔOI for the same expiry, else change since first fetch today. */
    private fun mergeDeltaOi(k: OptionChain, nse: OptionChain?, now: Long): OptionChain {
        val day = Session.sessionStart(now)
        if (day != openingOiDay) { openingOi.clear(); openingOiDay = day }
        val nseRows = nse?.takeIf { it.expiry.equals(k.expiry, true) }?.rows?.associateBy { it.strike }
        return k.copy(rows = k.rows.map { r ->
            val n = nseRows?.get(r.strike)
            val ceBase = openingOi.getOrPut("${r.strike}|CE") { r.call.oi }
            val peBase = openingOi.getOrPut("${r.strike}|PE") { r.put.oi }
            r.copy(
                call = r.call.copy(changeOi = n?.call?.changeOi ?: (r.call.oi - ceBase),
                    iv = r.call.iv.takeIf { !it.isNaN() && it > 0 } ?: n?.call?.iv ?: Double.NaN),
                put = r.put.copy(changeOi = n?.put?.changeOi ?: (r.put.oi - peBase),
                    iv = r.put.iv.takeIf { !it.isNaN() && it > 0 } ?: n?.put?.iv ?: Double.NaN),
            )
        })
    }
}
