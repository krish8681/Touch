package com.niftyengine.engine.sim

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.Constituents
import com.niftyengine.engine.engines.SnapshotProvider
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.FlowData
import com.niftyengine.engine.model.FuturesData
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MacroInputs
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.Sector
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic but internally consistent NIFTY market (constituents → index, futures OI, option chain,
 * VIX, sectors, global markets, flows and duplicated news) with hidden regime switches.
 * Used for offline demo mode, replay demos and tests. NOT real market data.
 */
class SimulatedMarket(seed: Long = 7L, startDate: LocalDate = LocalDate.now(Session.IST), private val startLevel: Double = 25_000.0) : SnapshotProvider {
    override val name = "Simulator"
    private val rnd = Random(seed)
    private enum class Hidden(val driftPerMin: Double, val volPerMin: Double) {
        BULL(0.0045, 0.014), BEAR(-0.0045, 0.015), RANGE(0.0, 0.010), EVENT_UP(0.012, 0.035), EVENT_DOWN(-0.012, 0.035)
    }

    private var date: LocalDate = tradingDay(startDate)
    private var minute = 0
    private var hidden = Hidden.RANGE
    private var hiddenLeft = 0
    private var shortCoverMode = false

    private val cons = Constituents.DEFAULT
    private val betas = cons.associate { it.symbol to 0.7 + rnd.nextDouble() * 0.6 }
    private val stockPrev = HashMap<String, Double>()
    private val stockPx = HashMap<String, Double>()
    private val stockOpen = HashMap<String, Double>()
    private val stockBars = HashMap<String, MutableList<Candle>>()
    private val sectorFactor = HashMap<Sector, Double>()
    private var niftyPrev = startLevel
    private var nifty = startLevel
    private val niftyBars = mutableListOf<Candle>()
    private val niftyDaily = mutableListOf<Candle>()
    private var vixPrev = 13.5
    private var vix = 13.5
    private val vixBars = mutableListOf<Candle>()
    private val vixDaily = mutableListOf<Candle>()
    private var bankPrev = 0.0
    private var futOiPrev = 1.25e7
    private var futOi = 1.25e7
    private var futVol = 0.0
    private var basisPct = 0.35
    private val globalPrev = HashMap<GlobalAsset, Double>()
    private val globalPx = HashMap<GlobalAsset, Double>()
    private val globalDayChg = HashMap<GlobalAsset, Double>()
    private val baseCallOi = HashMap<Double, Double>()
    private val basePutOi = HashMap<Double, Double>()
    private val news = mutableListOf<NewsItem>()
    private var flows = FlowData()
    private var dayBias = 0.0
    private val bullStories = listOf(
        "RBI cuts repo rate by 50 bps vs 25 bps expected, signals liquidity support",
        "US inflation cools more than expected, Wall Street rallies to record high",
        "Crude oil prices fall sharply as OPEC signals output increase",
        "FPIs turn net buyers, inflows surge on strong earnings optimism",
        "HDFC Bank Q2 net profit beats estimates, asset quality improves",
    )
    private val bearStories = listOf(
        "Missile attack escalates Middle East conflict, crude surges",
        "US Fed signals hawkish stance, Treasury yields jump",
        "India CPI inflation rises to 6.2% against estimate of 5.6%",
        "FPIs pull out ₹8,000 crore as rupee hits record low",
        "Reliance Q2 results miss estimates, margins under pressure",
    )
    private val outlets = listOf("Reuters", "Economic Times", "Moneycontrol", "Livemint", "Business Standard", "CNBC-TV18")
    /** Scheduled story beats: (minute, headline, copies, hidden regime forced from that minute, for how long). */
    private data class Beat(val minute: Int, val title: String, val copies: Int, val force: Hidden? = null, val forMinutes: Int = 0)
    private val beats = ArrayList<Beat>()

    init {
        // 250 days of history ending at the start level.
        var lvl = startLevel * 0.88
        var v = 14.0
        var d = date.minusDays(365)
        while (niftyDaily.size < 250) {
            d = d.plusDays(1)
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) continue
            val r = 0.0005 + rnd.nextGaussian() * 0.009
            val o = lvl; lvl *= 1 + r
            val t = d.atTime(9, 15).atZone(Session.IST).toInstant().toEpochMilli()
            niftyDaily += Candle(t, o, max(o, lvl) * (1 + abs(rnd.nextGaussian()) * 0.003), minOf(o, lvl) * (1 - abs(rnd.nextGaussian()) * 0.003), lvl)
            v = (v + 0.15 * (14 - v) - r * 300 + rnd.nextGaussian() * 0.6).coerceIn(9.0, 30.0)
            vixDaily += Candle(t, v, v, v, v)
        }
        val scale = startLevel / niftyDaily.last().c
        niftyDaily.replaceAll { it.copy(o = it.o * scale, h = it.h * scale, l = it.l * scale, c = it.c * scale) }
        niftyPrev = niftyDaily.last().c
        vixPrev = vixDaily.last().c
        cons.forEach { stockPrev[it.symbol] = 200.0 + rnd.nextDouble() * 3000 }
        GlobalAsset.values().forEach { globalPrev[it] = baseLevel(it) }
        newDay()
    }

    private fun Random.nextGaussian(): Double {
        var u = 0.0; var v = 0.0
        while (u == 0.0) u = nextDouble()
        while (v == 0.0) v = nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    private fun baseLevel(a: GlobalAsset) = when (a) {
        GlobalAsset.SP500 -> 6400.0; GlobalAsset.NASDAQ -> 21500.0; GlobalAsset.DOW -> 45000.0; GlobalAsset.NIKKEI -> 42000.0
        GlobalAsset.HANGSENG -> 25000.0; GlobalAsset.SHANGHAI -> 3700.0; GlobalAsset.DAX -> 24000.0; GlobalAsset.US_VIX -> 16.0
        GlobalAsset.US10Y -> 4.2; GlobalAsset.US2Y -> 3.7; GlobalAsset.DXY -> 98.0; GlobalAsset.BRENT -> 68.0
        GlobalAsset.WTI -> 64.0; GlobalAsset.GOLD -> 3600.0; GlobalAsset.USDINR -> 88.0; GlobalAsset.INDIA10Y -> 6.5
    }

    private fun tradingDay(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1); DayOfWeek.SUNDAY -> d.minusDays(2); else -> d
    }

    private fun t(min: Int): Long = date.atTime(Session.OPEN).plusMinutes(min.toLong()).atZone(Session.IST).toInstant().toEpochMilli()

    private fun newDay() {
        minute = 0
        dayBias = rnd.nextGaussian() * 0.6
        niftyBars.clear(); vixBars.clear(); stockBars.clear(); news.clear()
        // Overnight global moves loosely aligned with today's bias.
        for (a in GlobalAsset.values()) {
            val chg = (a.riskSign * dayBias * 0.6 + rnd.nextGaussian() * 0.8) * a.typicalDailyMovePct
            globalDayChg[a] = chg
            globalPx[a] = globalPrev.getValue(a) * (1 + chg / 100)
        }
        val gap = dayBias * 0.25 + rnd.nextGaussian() * 0.15
        nifty = niftyPrev * (1 + gap / 100)
        vix = vixPrev * (1 - gap * 0.04)
        cons.forEach { c ->
            val p = stockPrev.getValue(c.symbol) * (1 + (gap * betas.getValue(c.symbol) + rnd.nextGaussian() * 0.3) / 100)
            stockPx[c.symbol] = p; stockOpen[c.symbol] = p
        }
        Sector.values().forEach { sectorFactor[it] = 0.0 }
        bankPrev = cons.filter { it.sector == Sector.BANK }.sumOf { stockPrev.getValue(it.symbol) * it.weight }
        futOiPrev = futOi
        futVol = 0.0
        hiddenLeft = 0
        flows = FlowData(
            fpiNetCr = round(dayBias * 2500 + rnd.nextGaussian() * 1500), diiNetCr = round(-dayBias * 800 + 1500 + rnd.nextGaussian() * 1200),
            fpi5dCr = round(dayBias * 6000 + rnd.nextGaussian() * 5000), dii5dCr = round(6000 + rnd.nextGaussian() * 4000),
            date = date.minusDays(1).toString(),
        )
        baseCallOi.clear(); basePutOi.clear()
        val atm = round(nifty / 50) * 50
        for (i in -30..30) {
            val k = atm + i * 50
            val round100 = if (k % 100 == 0.0) 1.6 else 1.0
            val round500 = if (k % 500 == 0.0) 2.2 else 1.0
            baseCallOi[k] = if (i >= -4) (60_000 + 90_000 * exp(-((i - 6) * (i - 6)) / 40.0)) * round100 * round500 * (0.8 + rnd.nextDouble() * 0.4) else 20_000.0
            basePutOi[k] = if (i <= 4) (60_000 + 90_000 * exp(-((i + 6) * (i + 6)) / 40.0)) * round100 * round500 * (0.8 + rnd.nextDouble() * 0.4) else 20_000.0
        }
        if (abs(dayBias) > 0.7) addNews(0, if (dayBias > 0) bullStories.random(rnd) else bearStories.random(rnd), 3, t(0) - 3_600_000L)
        planStorylines()
    }



    /**
     * Lifecycle storylines so expectation/pricing logic can be exercised:
     *  • a scheduled RBI decision that is EXPECTED at the open and confirmed "in line" mid-morning (little new info);
     *  • on some days a geopolitical rumour → likely → confirmed sequence where the market sells off at the
     *    rumour stage (so the confirmation is largely priced in).
     */
    private fun planStorylines() {
        beats.clear()
        if (rnd.nextDouble() < 0.5) {
            beats += Beat(1, "RBI expected to cut repo rate by 25 bps today, economists poll shows", 3)
            beats += Beat(46, "RBI cuts repo rate by 25 bps, in line with expectations", 4)
        }
        if (rnd.nextDouble() < 0.5) {
            val r = 80 + rnd.nextInt(80)
            beats += Beat(r, "Border clash reportedly under way, sources said, as tensions flare", 2, Hidden.EVENT_DOWN, 25)
            beats += Beat(r + 20, "Military escalation likely as border clash widens, officials say", 3, Hidden.BEAR, 20)
            beats += Beat(r + 50, "Government confirms border clash; troops attacked", 4, Hidden.RANGE, 40)
        }
    }

    private fun playBeats() {
        beats.filter { it.minute == minute }.forEach { b ->
            addNews(minute, b.title, b.copies)
            b.force?.let { hidden = it; hiddenLeft = b.forMinutes }
        }
    }

    private fun addNews(min: Int, story: String, copies: Int, at: Long = t(min)) {
        repeat(copies) { i ->
            val title = if (i == 0) story else story.replaceFirst(" ", " — ").let { if (i % 2 == 0) "$it: report" else "Update: $it" }
            news += NewsItem("sim-$at-$i", title, outlets[(i + min) % outlets.size], at + i * 4 * 60_000L)
        }
    }

    private fun switchHidden() {
        val r = rnd.nextDouble()
        hidden = when {
            r < 0.05 -> if (rnd.nextBoolean()) Hidden.EVENT_UP else Hidden.EVENT_DOWN
            r < 0.35 + 0.25 * dayBias.coerceIn(-1.0, 1.0) -> Hidden.BULL
            r < 0.70 -> Hidden.BEAR
            else -> Hidden.RANGE
        }
        hiddenLeft = if (hidden == Hidden.EVENT_UP || hidden == Hidden.EVENT_DOWN) 15 + rnd.nextInt(20) else 45 + rnd.nextInt(90)
        shortCoverMode = rnd.nextDouble() < 0.3
        when (hidden) {
            Hidden.EVENT_UP -> addNews(minute, bullStories.random(rnd), 4)
            Hidden.EVENT_DOWN -> { addNews(minute, bearStories.random(rnd), 4); vix *= 1.08 }
            else -> if (rnd.nextDouble() < 0.25) addNews(minute, (if (hidden == Hidden.BULL) bullStories else bearStories).random(rnd), 2)
        }
    }

    /** Advance one simulated minute. */
    fun step() {
        if (minute >= Session.SESSION_MINUTES - 1) {
            // close the day
            niftyDaily += Candle(t(0), niftyBars.first().o, niftyBars.maxOf { it.h }, niftyBars.minOf { it.l }, nifty)
            vixDaily += Candle(t(0), vixBars.first().o, vixBars.maxOf { it.h }, vixBars.minOf { it.l }, vix)
            niftyPrev = nifty; vixPrev = vix
            stockPrev.putAll(stockPx)
            GlobalAsset.values().forEach { globalPrev[it] = globalPx.getValue(it) }
            date = tradingDay(date.plusDays(1).let { if (it.dayOfWeek == DayOfWeek.SATURDAY) it.plusDays(2) else if (it.dayOfWeek == DayOfWeek.SUNDAY) it.plusDays(1) else it })
            newDay()
            return
        }
        if (hiddenLeft-- <= 0) switchHidden()
        minute++
        playBeats()
        val market = hidden.driftPerMin + rnd.nextGaussian() * hidden.volPerMin
        Sector.values().forEach { sectorFactor[it] = sectorFactor.getValue(it) * 0.98 + rnd.nextGaussian() * 0.006 }
        var idxRet = 0.0; var wsum = 0.0
        cons.forEach { c ->
            val r = betas.getValue(c.symbol) * market + sectorFactor.getValue(c.sector) + rnd.nextGaussian() * 0.025
            stockPx[c.symbol] = stockPx.getValue(c.symbol) * (1 + r / 100)
            idxRet += c.weight * (stockPx.getValue(c.symbol) / stockPrev.getValue(c.symbol) - 1); wsum += c.weight
        }
        val prevNifty = nifty
        nifty = niftyPrev * (1 + idxRet / wsum)
        val ret = (nifty - prevNifty) / prevNifty * 100
        vix = (vix * (1 - ret * 0.06) + 0.002 * (14 - vix) + rnd.nextGaussian() * 0.02).coerceIn(8.0, 45.0)
        // Futures OI: trend legs build OI unless in short-covering / long-unwinding mode.
        val oiChange = when (hidden) {
            Hidden.BULL, Hidden.BEAR -> if (shortCoverMode) -0.0012 else 0.0015
            Hidden.EVENT_UP, Hidden.EVENT_DOWN -> 0.002
            Hidden.RANGE -> rnd.nextGaussian() * 0.0005
        }
        futOi *= 1 + oiChange + rnd.nextGaussian() * 0.0003
        futVol += 4000 + rnd.nextDouble() * 6000 * (1 + abs(ret) * 20)
        basisPct = (basisPct + ret * 0.01 + rnd.nextGaussian() * 0.002).coerceIn(0.05, 0.8)
        // Global live assets drift intraday.
        listOf(GlobalAsset.NIKKEI, GlobalAsset.HANGSENG, GlobalAsset.SHANGHAI, GlobalAsset.DXY, GlobalAsset.BRENT,
            GlobalAsset.WTI, GlobalAsset.GOLD, GlobalAsset.USDINR, GlobalAsset.US10Y, GlobalAsset.INDIA10Y, GlobalAsset.SP500, GlobalAsset.NASDAQ).forEach { a ->
            globalPx[a] = globalPx.getValue(a) * (1 + (a.riskSign * market * 0.15 + rnd.nextGaussian() * 0.01) * a.typicalDailyMovePct / 100)
        }
        if (rnd.nextDouble() < 0.01) addNews(minute, listOf("Sensex, Nifty trade higher; banks lead", "Nifty slips as IT stocks drag", "Stock market today: Nifty flat ahead of expiry").random(rnd), 2)
        val bt = t(minute)
        niftyBars += Candle(bt, prevNifty, max(prevNifty, nifty), minOf(prevNifty, nifty), nifty)
        vixBars += Candle(bt, vixBars.lastOrNull()?.c ?: vix, vix, vix, vix)
        cons.forEach { c -> stockBars.getOrPut(c.symbol) { mutableListOf() } += Candle(bt, stockPx.getValue(c.symbol), stockPx.getValue(c.symbol), stockPx.getValue(c.symbol), stockPx.getValue(c.symbol)) }
    }

    override fun collect(now: Long): MarketSnapshot {
        step()
        return snapshot()
    }

    fun snapshot(): MarketSnapshot = stamp(rawSnapshot())

    /** Simulated feeds are always "fresh": stamp every input with the snapshot time. */
    private fun stamp(s: MarketSnapshot): MarketSnapshot {
        val ts = s.timestamp
        val day = 86_400_000L
        return s.copy(
            nifty = s.nifty.copy(asOf = ts), bankNifty = s.bankNifty?.copy(asOf = ts), vix = s.vix?.copy(asOf = ts),
            futures = s.futures?.copy(asOf = ts), optionChain = s.optionChain?.copy(asOf = ts),
            constituents = s.constituents.mapValues { it.value.copy(asOf = ts) },
            sectors = s.sectors.mapValues { it.value.copy(asOf = ts) },
            global = s.global.mapValues { it.value.copy(asOf = ts) },
            flows = s.flows?.copy(asOf = ts - day),
            macro = s.macro.copy(source = "simulated", releasedAt = mapOf(
                "repoRate" to ts - 20 * day, "cpiYoY" to ts - 18 * day, "gdpGrowth" to ts - 30 * day,
                "pmiManufacturing" to ts - 2 * day, "creditGrowth" to ts - 10 * day, "liquidityCr" to ts - day)),
        )
    }

    private fun rawSnapshot(): MarketSnapshot {
        val ts = t(minute)
        val niftyOpen = niftyBars.firstOrNull()?.o ?: nifty
        val niftyData = InstrumentData("NIFTY 50", nifty, niftyPrev, niftyOpen, niftyBars.maxOfOrNull { it.h } ?: nifty,
            niftyBars.minOfOrNull { it.l } ?: nifty, 0.0, niftyBars.toList(), niftyDaily.takeLast(250))
        val stocks = cons.associate { c ->
            c.symbol to InstrumentData(c.symbol, stockPx.getValue(c.symbol), stockPrev.getValue(c.symbol), stockOpen.getValue(c.symbol),
                intraday = stockBars[c.symbol]?.toList() ?: emptyList())
        }
        val sectors = cons.groupBy { it.sector }.mapValues { (s, l) ->
            val now = l.sumOf { stockPx.getValue(it.symbol) / stockPrev.getValue(it.symbol) * it.weight } / l.sumOf { it.weight }
            InstrumentData("SECTOR_${s.name}", 10_000 * now, 10_000.0)
        }
        val bankNow = cons.filter { it.sector == Sector.BANK }.sumOf { stockPx.getValue(it.symbol) * it.weight }
        val global = GlobalAsset.values().associateWith { a -> InstrumentData("G_${a.name}", globalPx.getValue(a), globalPrev.getValue(a)) }
        val futPx = nifty * (1 + basisPct / 100)
        return MarketSnapshot(
            timestamp = ts,
            nifty = niftyData,
            bankNifty = InstrumentData("NIFTY BANK", 56_000 * bankNow / bankPrev, 56_000.0),
            vix = InstrumentData("INDIA VIX", vix, vixPrev, intraday = vixBars.toList(), daily = vixDaily.takeLast(250)),
            futures = FuturesData("NIFTY FUT", expiry().toString(), futPx, niftyPrev * (1 + 0.35 / 100), futOi, futOiPrev, futVol),
            optionChain = chain(ts),
            constituents = stocks,
            sectors = sectors,
            global = global,
            macro = MacroInputs(repoRate = 5.5, lastPolicyChangeBps = 0.0, cpiYoY = 2.1, cpiPrevYoY = 1.6, gdpGrowth = 7.8,
                gdpPrevGrowth = 7.4, pmiManufacturing = 58.5, creditGrowth = 10.5, liquidityCr = 150_000.0),
            flows = flows,
            news = news.filter { it.publishedAt <= ts },
            source = "SIMULATED",
            feedStatus = mapOf("Simulator" to "${date} ${Session.hhmm(ts)} hidden=${hidden.name.lowercase()}"),
        )
    }

    /** Weekly NIFTY expiry: Tuesday on/after the session date. */
    private fun expiry(): LocalDate = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY))

    private fun chain(ts: Long): OptionChain {
        val exp = expiry()
        val expMs = Session.closeOf(exp)
        val tY = Session.yearsToExpiry(ts, expMs)
        val atmIv = vix / 100 * 0.92
        val progress = minute / Session.SESSION_MINUTES.toDouble()
        val dayMove = (nifty - niftyPrev) / niftyPrev * 100
        val atm = round(nifty / 50) * 50
        val rows = (-20..20).map { i ->
            val k = atm + i * 50
            val m = (nifty - k) / nifty * 100
            val iv = (atmIv + 0.006 * m.coerceIn(-6.0, 6.0) + 0.0015 * m * m).coerceAtLeast(0.05)
            val c = BlackScholes.price(true, nifty, k, tY, iv).price
            val p = BlackScholes.price(false, nifty, k, tY, iv).price
            val dist = (k - nifty) / 50
            // Intraday writing: rallies bring put writing below spot + call unwinding; falls the opposite.
            val peAdd = progress * 60_000 * (dayMove * 1.5).coerceIn(-1.0, 1.5) * exp(-((dist + 2) * (dist + 2)) / 18)
            val ceAdd = progress * 60_000 * (-dayMove * 1.5).coerceIn(-1.0, 1.5) * exp(-((dist - 2) * (dist - 2)) / 18)
            val baseC = baseCallOi[k] ?: 20_000.0; val baseP = basePutOi[k] ?: 20_000.0
            val ceOi = max(1000.0, baseC + ceAdd + progress * 15_000)
            val peOi = max(1000.0, baseP + peAdd + progress * 15_000)
            fun leg(px: Double, oi: Double, add: Double, v: Double) = OptionLeg(
                oi = round(oi), changeOi = round(add + progress * 15_000), volume = round(oi * (0.5 + progress) * (0.6 + 0.8 * exp(-dist * dist / 30))),
                iv = v * 100, ltp = round(px * 20) / 20, bid = round(max(0.05, px * 0.997 - 0.1) * 20) / 20, ask = round((px * 1.003 + 0.1) * 20) / 20,
            )
            OptionStrikeRow(k, leg(c, ceOi, ceAdd, iv), leg(p, peOi, peAdd, iv))
        }
        return OptionChain(nifty, exp.toString(), expMs, rows, 50.0)
    }
}
