package com.niftyengine.app.data

import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.store.DataMode
import com.niftyengine.app.store.FlowHistoryStore
import com.niftyengine.app.store.ParticipantOiStore
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.Constituents
import com.niftyengine.engine.engines.SnapshotProvider
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.FiiDerivatives
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.Sector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.DayOfWeek

/**
 * 01 — Data Collector (live). Gathers every feed in parallel and merges by priority (spec §30 data tiers):
 *
 *  Tier 1  NIFTY, futures, weekly + monthly option chains, FII positioning, India VIX, GIFT Nifty, global indices,
 *          USD/INR, crude, news
 *  Tier 2  constituents, sector indices (+ daily history), RBI / economic calendar (Setup), earnings (Setup)
 *  Tier 3  gold, bond yields, DII flows, other macro (Setup)
 *
 *  Kite mode:  Kite → NIFTY/Bank/VIX/sectors/50 stocks/futures/weekly + monthly chains (one quote call),
 *              Kite historical → 1-min NIFTY & VIX bars, futures bars with OI, 1-year daily history;
 *              NSE → free-float weights, ΔOI per strike, FII/DII cash, FII participant OI, valuation, sector history;
 *              Yahoo/RSS → global markets (+ daily history) and news.
 *  Public mode (or Kite failure): NSE + Yahoo + RSS.
 *
 * A missing Tier-2/3 feed never stops the engine: the factor it powers is reported missing and its weight is
 * redistributed (§31). Per-feed health is reported in [MarketSnapshot.feedStatus].
 */
class LiveSnapshotProvider(private val cacheDir: File, private val dataDir: File, private val settings: () -> AppSettings) : SnapshotProvider {
    override val name = "Live"

    private fun today() = Session.sessionStart(System.currentTimeMillis())

    private val flowStore = FlowHistoryStore(File(dataDir, "flows-history.json"))
    private val poiStore = ParticipantOiStore(File(dataDir, "fii-participant-oi.json"))

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
    /** Kite mode: NSE board only for NIFTY P/E, P/B, dividend yield. */
    private val boardSlow = Cached(30 * 60_000L) { NseClient.allIndices() }
    private val constituents = Cached(20_000L) { NseClient.nifty50Constituents() }
    /** Free-float caps change slowly; in Kite mode NSE is only polled for weights. */
    private val weights = Cached(6 * 3_600_000L) { NseClient.nifty50Constituents() }
    private val chain = Cached(45_000L) { NseClient.optionChain() }
    private val expiries = Cached(30 * 60_000L) { NseClient.optionExpiries() }
    /** NSE monthly chain; null value (without error) = the nearest expiry IS the monthly one. */
    private val monthlyNse = Cached(60_000L) { expiries.get()?.let { NseClient.monthlyExpiryOf(it) }?.let { NseClient.optionChain(it) } }
    private val futures = Cached(20_000L) { NseClient.futures() }
    private val flows = Cached(30 * 60_000L) { NseClient.fiiDii() }
    private val fiiDeriv = Cached(2 * 3_600_000L) { loadParticipantOi() }
    private val gift = Cached(60_000L) { NseClient.giftNifty() }
    private val news = Cached(3 * 60_000L) { NewsClient.fetchAll() }
    private val global = YahooClient.GLOBAL_SYMBOLS.mapValues { (a, sym) ->
        Cached(if (a == GlobalAsset.USDINR || a == GlobalAsset.BRENT) 60_000L else 120_000L) { YahooClient.chart(sym, "5m", "1d", "G_${a.name}") }
    }
    /** ~1 year of daily closes per global asset (H2 5-session and H3 20-session views). */
    private val globalDaily = YahooClient.GLOBAL_SYMBOLS.mapValues { (_, sym) -> Cached(6 * 3_600_000L) { YahooClient.dailyHistory(sym, today()) } }
    /** Daily history per sector index (H2 sector leadership). */
    private val sectorDaily = NseClient.SECTOR_INDICES.entries.associate { (name, sec) -> sec to Cached(6 * 3_600_000L) { NseClient.indexDaily(name, today()) } }

    // ---- Kite feeds (rebuilt when credentials change)
    private var kiteCreds = ""
    private var kite: KiteMarketData? = null
    private var lastSpot = Double.NaN
    private lateinit var kQuotes: Cached<KiteMarketData.Bundle>
    private lateinit var kHistory: Cached<KiteHist>
    private lateinit var kDaily: Cached<Pair<List<Candle>, List<Candle>>>
    /** First OI seen today per "expiry|strike|CE/PE" — ΔOI fallback when NSE's chain is unavailable. */
    private val openingOi = HashMap<String, Double>()
    private var openingOiDay = 0L

    private data class KiteHist(val nifty: List<Candle>, val vix: List<Candle>, val fut: List<com.niftyengine.engine.model.FuturesBar>, val futPrevOi: Double)

    /** Last ~20 trading days of FII participant OI: fetches only days not already on disk (holidays remembered). */
    private fun loadParticipantOi(): FiiDerivatives {
        val now = Session.zdt(System.currentTimeMillis())
        // Today's file appears after the close (~18:30 IST).
        var d = if (now.hour >= 19) now.toLocalDate() else now.toLocalDate().minusDays(1)
        var fetched = 0
        var checked = 0
        while (checked < 30 && fetched < 25) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && !poiStore.has(d)) {
                poiStore.put(d, NseClient.participantOi(d))
                fetched++
            }
            d = d.minusDays(1); checked++
        }
        val days = poiStore.last(20)
        if (days.isEmpty()) throw IllegalStateException("no participant OI files")
        return FiiDerivatives(days, TimeParse.istDate(days.last().date, 19), "NSE participant-wise OI")
    }

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
        var kd: Pair<List<Candle>, List<Candle>>? = null
        if (k != null) {
            val jobs = listOf(async { weights.get() }, async { kQuotes.get() }, async { kHistory.get() }, async { kDaily.get() })
            jobs.awaitAll()
            kb = kQuotes.get(); kh = kHistory.get(); kd = kDaily.get()
            val err = kQuotes.lastError
            status["Kite quotes"] = when {
                err == null && kb != null -> "✓ ${kb.constituents.size} stocks · ${kb.sectors.size} sectors · ${kb.chain?.rows?.size ?: 0} strikes" +
                    (kb.chain?.let { " exp ${it.expiry}" } ?: "") + (kb.monthlyChain?.let { " · monthly ${it.expiry}" } ?: "")
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
            add(async { fiiDeriv.get() }); add(async { expiries.get(); monthlyNse.get() })
            global.values.forEach { c -> add(async { c.get() }) }
            globalDaily.values.forEach { c -> add(async { c.get() }) }
            sectorDaily.values.forEach { c -> add(async { c.get() }) }
            if (!kiteLive) {
                add(async { board.get() }); add(async { constituents.get() }); add(async { futures.get() })
                add(async { niftyIntra.get() }); add(async { vixIntra.get() }); add(async { bankIntra.get() })
                add(async { if (niftyIntra.get() == null) nseIntra.get() else null })
            } else add(async { boardSlow.get() })
            if (kd?.first.isNullOrEmpty()) add(async { niftyDaily.get(); vixDaily.get() })
        }
        jobs.awaitAll()
        fun st(name: String, c: Cached<*>, ok: String) { status[name] = c.lastError?.let { "✗ $it" } ?: ok }

        val nseChain = chain.get()
        val nseMonthly = monthlyNse.get()
        val dailyN = kd?.first?.takeIf { it.isNotEmpty() } ?: niftyDaily.get() ?: emptyList()
        val dailyV = kd?.second?.takeIf { it.isNotEmpty() } ?: vixDaily.get() ?: emptyList()
        fun withSectorHistory(m: Map<Sector, InstrumentData>) = m.mapValues { (sec, d) -> d.copy(daily = sectorDaily[sec]?.get() ?: emptyList()) }

        val nifty: InstrumentData
        val vix: InstrumentData?
        val bank: InstrumentData?
        val stocks: Map<String, InstrumentData>
        val sectors: Map<Sector, InstrumentData>
        val fut: com.niftyengine.engine.model.FuturesData?
        val optChain: OptionChain?
        val monthlyChain: OptionChain?
        val valuation: com.niftyengine.engine.model.ValuationData?
        if (kiteLive) {
            val b = kb!!
            nifty = b.nifty!!.copy(intraday = kh?.nifty ?: emptyList(), daily = dailyN)
            vix = b.vix?.copy(intraday = kh?.vix ?: emptyList(), daily = dailyV)
            bank = b.bank
            val ffmc = weights.get()
            stocks = b.constituents.mapValues { (sym, d) -> d.copy(freeFloatMcap = ffmc?.get(sym)?.freeFloatMcap ?: Double.NaN) }
            sectors = withSectorHistory(b.sectors)
            fut = b.futures?.copy(prevOpenInterest = kh?.futPrevOi ?: Double.NaN, intraday = kh?.fut ?: emptyList())
            optChain = b.chain?.let { mergeDeltaOi(it, nseChain, now) }
            monthlyChain = b.monthlyChain?.let { mergeDeltaOi(it, nseMonthly, now) }
            valuation = boardSlow.get()?.valuation
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
            sectors = withSectorHistory(b?.sectors ?: emptyMap())
            fut = futures.get()
            optChain = nseChain
            monthlyChain = nseMonthly
            valuation = b?.valuation
            st("NSE indices", board, "✓ ${b?.sectors?.size ?: 0} sectors")
            st("NSE constituents", constituents, "✓ ${stocks.size} stocks")
            st("NSE futures", futures, "✓ ${fut?.expiry ?: ""}")
            status["NIFTY bars"] = if (yN != null) "✓ Yahoo ${nifty.intraday.size} bars" else if (nseIntra.get() != null) "✓ NSE ${nifty.intraday.size} pts"
                else "✗ Yahoo: ${niftyIntra.lastError} · NSE: ${nseIntra.lastError} (using own ticks)"
        }
        lastSpot = nifty.last
        st("NSE option chain", chain, "✓ ${nseChain?.rows?.size ?: 0} strikes" + if (kiteLive) " (ΔOI only)" else ", exp ${nseChain?.expiry ?: "-"}")
        status["Monthly chain"] = when {
            monthlyChain != null -> "✓ exp ${monthlyChain.expiry} · ${monthlyChain.rows.size} strikes"
            monthlyNse.lastError != null && !kiteLive -> "✗ ${monthlyNse.lastError}"
            expiries.lastError != null && !kiteLive -> "✗ expiry list: ${expiries.lastError}"
            else -> "= weekly expiry is the monthly expiry"
        }
        val fl = flows.get()?.let { f -> f.copy(history = flowStore.add(f)) }
        st("NSE FII/DII", flows, "✓ ${fl?.date ?: ""} · ${fl?.history?.size ?: 0} days kept")
        val fd = fiiDeriv.get()
        st("FII participant OI", fiiDeriv, "✓ ${fd?.days?.size ?: 0} days · latest ${fd?.days?.lastOrNull()?.date ?: "–"}")
        status["Valuation"] = valuation?.let { "✓ P/E %.2f · P/B %.2f · DY %.2f".format(it.pe, it.pb, it.divYield) } ?: "✗ unavailable"
        st("GIFT Nifty", gift, gift.get()?.let { "✓ %.1f (%+.2f%%) @ %s".format(it.last, it.changePct, Session.hhmm(it.asOf)) } ?: "–")
        if (dailyN.isEmpty()) status["Daily history"] = "✗ ${kDaily.takeIf { k != null }?.lastError ?: niftyDaily.lastError}"
        val glob = global.mapNotNull { (a, c) -> c.get()?.let { a to it.copy(daily = globalDaily[a]?.get() ?: emptyList()) } }.toMap()
        val n = news.get()
        status["Global"] = "✓ ${glob.size}/${global.size} · daily ${globalDaily.count { it.value.get() != null }}" +
            (global.entries.firstOrNull { it.value.lastError != null }?.let { " (✗ ${it.key.label}: ${it.value.lastError})" } ?: "")
        status["Sector history"] = "${sectorDaily.count { it.value.get() != null }}/${sectorDaily.size} sector indices"
        status["News"] = (news.lastError?.let { "✗ $it" } ?: "✓ ${n?.items?.size ?: 0} items") +
            (n?.errors?.takeIf { it.isNotEmpty() }?.let { " · ${it.size} feed(s) failed" } ?: "")

        MarketSnapshot(
            timestamp = now, nifty = nifty, bankNifty = bank, vix = vix, futures = fut, optionChain = optChain,
            constituents = stocks, sectors = sectors, global = glob, macro = s.macroInputs(), flows = fl,
            giftNifty = gift.get(),
            news = n?.items ?: emptyList(),
            source = if (kiteLive) "LIVE · Kite (+NSE/Yahoo/RSS)" else "LIVE · NSE+Yahoo",
            feedStatus = status,
            monthlyChain = monthlyChain, fiiDerivatives = fd, valuation = valuation, earnings = s.earningsInputs(), calendar = s.calendar(),
        )
    }

    /** Kite publishes OI but not change-in-OI: take NSE's ΔOI for the same expiry, else change since first fetch today. */
    private fun mergeDeltaOi(k: OptionChain, nse: OptionChain?, now: Long): OptionChain {
        val day = Session.sessionStart(now)
        if (day != openingOiDay) { openingOi.clear(); openingOiDay = day }
        val nseRows = nse?.takeIf { it.expiry.equals(k.expiry, true) }?.rows?.associateBy { it.strike }
        return k.copy(rows = k.rows.map { r ->
            val n = nseRows?.get(r.strike)
            val ceBase = openingOi.getOrPut("${k.expiry}|${r.strike}|CE") { r.call.oi }
            val peBase = openingOi.getOrPut("${k.expiry}|${r.strike}|PE") { r.put.oi }
            r.copy(
                call = r.call.copy(changeOi = n?.call?.changeOi ?: (r.call.oi - ceBase),
                    iv = r.call.iv.takeIf { !it.isNaN() && it > 0 } ?: n?.call?.iv ?: Double.NaN),
                put = r.put.copy(changeOi = n?.put?.changeOi ?: (r.put.oi - peBase),
                    iv = r.put.iv.takeIf { !it.isNaN() && it > 0 } ?: n?.put?.iv ?: Double.NaN),
            )
        })
    }
}
