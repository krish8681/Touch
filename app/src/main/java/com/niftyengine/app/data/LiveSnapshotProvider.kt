package com.niftyengine.app.data

import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.store.DataMode
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.SnapshotProvider
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.MarketSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * 01 — Data Collector (live). Gathers every feed in parallel, merges sources by priority
 * (Kite > NSE > Yahoo), caches slow feeds, and reports per-feed health in [MarketSnapshot.feedStatus].
 */
class LiveSnapshotProvider(private val settings: () -> AppSettings) : SnapshotProvider {
    override val name = "Live"

    private fun today() = Session.sessionStart(System.currentTimeMillis())

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
    private val chain = Cached(45_000L) { NseClient.optionChain() }
    private val futures = Cached(20_000L) { NseClient.futures() }
    private val flows = Cached(30 * 60_000L) { NseClient.fiiDii() }
    private val news = Cached(3 * 60_000L) { NewsClient.fetchAll() }
    private val global = YahooClient.GLOBAL_SYMBOLS.mapValues { (a, sym) ->
        Cached(if (a == GlobalAsset.USDINR || a == GlobalAsset.BRENT) 60_000L else 120_000L) { YahooClient.chart(sym, "5m", "1d", "G_${a.name}") }
    }
    private val kite = Cached(5_000L) {
        val s = settings()
        KiteClient.quotes(s.kiteApiKey, s.kiteAccessToken, Session.zdt(System.currentTimeMillis()).toLocalDate())
    }

    override fun collect(now: Long): MarketSnapshot = runBlocking(Dispatchers.IO) {
        val s = settings()
        val status = LinkedHashMap<String, String>()
        val useKite = s.mode == DataMode.LIVE_KITE && s.kiteApiKey.isNotBlank() && s.kiteAccessToken.isNotBlank()

        val jobs = listOf(
            async { niftyDaily.get(); vixDaily.get() }, async { niftyIntra.get() }, async { vixIntra.get() }, async { bankIntra.get() },
            async { board.get() }, async { if (niftyIntra.get() == null) nseIntra.get() else null }, async { constituents.get() }, async { chain.get() }, async { futures.get() },
            async { flows.get() }, async { news.get() }, async { if (useKite) kite.get() else null },
        ) + global.values.map { c -> async { c.get() } }
        jobs.awaitAll()

        fun st(name: String, c: Cached<*>, ok: String) { status[name] = c.lastError?.let { "✗ $it" } ?: ok }
        val b = board.get()
        val k = if (useKite) kite.get() else null
        val yN = niftyIntra.get()

        // NIFTY spot: Kite > NSE > Yahoo, enriched with Yahoo intraday bars + daily history.
        val base = k?.nifty ?: b?.nifty ?: yN ?: throw IllegalStateException(
            "No NIFTY price from any feed (NSE: ${board.lastError}, Yahoo: ${niftyIntra.lastError})")
        val nifty = base.copy(
            symbol = "NIFTY 50",
            prevClose = base.prevClose.takeIf { !it.isNaN() && it > 0 } ?: yN?.prevClose ?: Double.NaN,
            // Latest session's bars (today's, or the last trading day's when the market is closed).
            intraday = (yN?.intraday?.takeIf { it.isNotEmpty() } ?: nseIntra.get() ?: emptyList()).let { bars ->
                val last = bars.lastOrNull()?.t ?: return@let bars
                bars.filter { it.t >= Session.sessionStart(last) }
            },
            daily = niftyDaily.get() ?: emptyList(),
        )
        val vixBase = k?.vix ?: b?.vix ?: vixIntra.get()
        val vix = vixBase?.copy(symbol = "INDIA VIX", intraday = vixIntra.get()?.intraday ?: emptyList(), daily = vixDaily.get() ?: emptyList())
        if (nifty.daily.isEmpty()) status["Daily history"] = "✗ ${niftyDaily.lastError}"
        val bank = (k?.bank ?: b?.bank ?: bankIntra.get())?.copy(symbol = "NIFTY BANK", intraday = bankIntra.get()?.intraday ?: emptyList())

        val nseFut = futures.get()
        val fut = when {
            k?.futures != null -> k.futures!!.copy(prevOpenInterest = nseFut?.prevOpenInterest ?: Double.NaN,
                prevClose = k.futures!!.prevClose.takeIf { !it.isNaN() } ?: nseFut?.prevClose ?: Double.NaN)
            else -> nseFut
        }
        val glob = global.mapNotNull { (a, c) -> c.get()?.let { a to it } }.toMap()
        val n = news.get()

        if (useKite) st("Kite", kite, "✓ spot/VIX/futures") else if (s.mode == DataMode.LIVE_KITE) status["Kite"] = "✗ not logged in (Settings → Kite)"
        st("NSE indices", board, "✓ ${b?.sectors?.size ?: 0} sectors")
        st("NSE constituents", constituents, "✓ ${constituents.get()?.size ?: 0} stocks")
        st("NSE option chain", chain, "✓ ${chain.get()?.rows?.size ?: 0} strikes, exp ${chain.get()?.expiry ?: "-"}")
        st("NSE futures", futures, "✓ ${nseFut?.expiry ?: ""}")
        st("NSE FII/DII", flows, "✓ ${flows.get()?.date ?: ""}")
        status["NIFTY bars"] = if (yN != null) "✓ Yahoo ${nifty.intraday.size} bars" else if (nseIntra.get() != null) "✓ NSE ${nifty.intraday.size} pts (Yahoo ✗ ${niftyIntra.lastError})"
            else "✗ Yahoo: ${niftyIntra.lastError} · NSE: ${nseIntra.lastError} (using own ticks)"
        status["Global"] = "✓ ${glob.size}/${global.size}" + (global.entries.firstOrNull { it.value.lastError != null }?.let { " (✗ ${it.key.label}: ${it.value.lastError})" } ?: "")
        status["News"] = (news.lastError?.let { "✗ $it" } ?: "✓ ${n?.items?.size ?: 0} items") +
            (n?.errors?.takeIf { it.isNotEmpty() }?.let { " · ${it.size} feed(s) failed" } ?: "")

        MarketSnapshot(
            timestamp = now,
            nifty = nifty,
            bankNifty = bank,
            vix = vix,
            futures = fut,
            optionChain = chain.get(),
            constituents = constituents.get() ?: emptyMap(),
            sectors = b?.sectors ?: emptyMap(),
            global = glob,
            macro = s.macro,
            flows = flows.get(),
            news = n?.items ?: emptyList(),
            source = if (useKite) "LIVE · Kite+NSE+Yahoo" else "LIVE · NSE+Yahoo",
            feedStatus = status,
        )
    }
}
