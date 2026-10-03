package com.niftyengine.app.data

import com.niftyengine.app.store.AppJson
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.Constituents
import com.niftyengine.engine.engines.HistoricalChunk
import com.niftyengine.engine.engines.HistoricalDataSource
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.FuturesBar
import com.niftyengine.engine.model.Sector
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Historical data for the market-only backtest, from Kite Connect's historical API:
 * NIFTY 50 / India VIX / Bank Nifty one-minute bars, sector indices and the 50 constituents (5-minute bars),
 * continuous near-month NIFTY futures with OI (if Kite serves it), and daily candles for history features.
 *
 * Respects Kite limits (≈3 historical requests/s; minute data ≤ 60 days per request) and caches every fully
 * past range on disk, so re-running a backtest does not download it again.
 */
class KiteHistoricalSource(
    private val api: KiteApi,
    private val cacheDir: File,
    private val status: (String) -> Unit = {},
) : HistoricalDataSource {
    override val description = "Kite historical"
    val notes = ArrayList<String>()

    private val sectorSymbols = mapOf(
        "NIFTY BANK" to Sector.BANK, "NIFTY FIN SERVICE" to Sector.FIN_SERVICES, "NIFTY IT" to Sector.IT,
        "NIFTY ENERGY" to Sector.ENERGY, "NIFTY AUTO" to Sector.AUTO, "NIFTY FMCG" to Sector.FMCG,
        "NIFTY PHARMA" to Sector.PHARMA, "NIFTY METAL" to Sector.METALS, "NIFTY INFRA" to Sector.INFRA,
        "NIFTY CONSR DURBL" to Sector.CONSUMER,
    )

    private var lastCall = 0L
    @Synchronized private fun <T> throttled(what: String, block: () -> T): T {
        var attempt = 0
        while (true) {
            val wait = 360 - (System.currentTimeMillis() - lastCall)
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
            try { return block() } catch (e: Exception) {
                val retryable = (e is KiteException && (e.type == "NetworkException" || e.type == "HTTP429" || e.type == "HTTP502" || e.type == "HTTP503")) ||
                    (e is IOException && e !is KiteException)
                if (!retryable || ++attempt > 3) throw if (e is KiteException) e else IOException("$what: ${e.message}", e)
                Thread.sleep(1000L * attempt)
            }
        }
    }

    // ---------------------------------------------------------------- instruments
    private data class Tokens(val sectors: Map<Sector, Long>, val stocks: Map<String, Long>, val futures: Long?)

    private val tokens: Tokens by lazy {
        status("Loading instrument lists…")
        val today = Session.zdt(System.currentTimeMillis()).toLocalDate()
        cacheDir.mkdirs()
        val f = File(cacheDir, "kite-nse-$today.csv")
        val csv = if (f.exists() && f.length() > 1000) f.readText() else throttled("instruments NSE") { api.instrumentsCsv("NSE") }.also { f.writeText(it) }
        val all = KiteInstruments.parse(csv)
        val wanted = Constituents.DEFAULT.map { it.symbol }.toSet()
        val sectors = all.filter { it.segment == "INDICES" && it.tradingSymbol in sectorSymbols }
            .associate { sectorSymbols.getValue(it.tradingSymbol) to it.token }
        val stocks = all.filter { it.segment == "NSE" && it.type == "EQ" && it.tradingSymbol in wanted }.associate { it.tradingSymbol to it.token }
        val missing = wanted - stocks.keys
        if (missing.isNotEmpty()) notes += "Constituents not found on Kite (skipped): ${missing.joinToString()}"
        val fut = runCatching { KiteMarketData(api, cacheDir).nearFuture(today)?.token }.getOrNull()
        Tokens(sectors, stocks, fut)
    }

    // ---------------------------------------------------------------- cached history
    private fun cacheFile(token: Long, interval: String, from: LocalDate, to: LocalDate, kind: String = "c") =
        File(File(cacheDir, "hist").apply { mkdirs() }, "${kind}_${token}_${interval}_${from}_$to.json")

    private fun isPast(to: LocalDate) = to.isBefore(Session.zdt(System.currentTimeMillis()).toLocalDate())

    private fun candles(token: Long, interval: String, from: LocalDate, to: LocalDate, label: String): List<Candle> {
        val f = cacheFile(token, interval, from, to)
        if (f.exists()) runCatching { return AppJson.decodeFromString(ListSerializer(Candle.serializer()), f.readText()) }
        val bars = throttled("$label $interval") {
            api.historical(token, interval, from.atStartOfDay(), to.atTime(LocalTime.of(23, 59, 59)))
        }.map { Candle(it.t, it.o, it.h, it.l, it.c, it.v) }
        if (isPast(to) && bars.isNotEmpty()) f.writeText(AppJson.encodeToString(ListSerializer(Candle.serializer()), bars))
        return bars
    }

    private var futuresUnavailable = false

    private fun futures(token: Long, from: LocalDate, to: LocalDate): List<FuturesBar> {
        if (futuresUnavailable) return emptyList()
        val f = cacheFile(token, "minute", from, to, "f")
        if (f.exists()) runCatching { return AppJson.decodeFromString(ListSerializer(FuturesBar.serializer()), f.readText()) }
        return try {
            val bars = throttled("futures") {
                api.historical(token, "minute", from.atStartOfDay(), to.atTime(LocalTime.of(23, 59, 59)), oi = true, continuous = true)
            }.map { FuturesBar(it.t, it.c, it.oi, it.v) }
            if (isPast(to) && bars.isNotEmpty()) f.writeText(AppJson.encodeToString(ListSerializer(FuturesBar.serializer()), bars))
            bars
        } catch (e: Exception) {
            futuresUnavailable = true
            notes += "Continuous futures (minute + OI) unavailable from Kite (${e.message?.take(80)}); futures driver absent."
            emptyList()
        }
    }

    // ---------------------------------------------------------------- daily
    private var dailyCache: Pair<List<Candle>, List<Candle>>? = null
    private var dailyRange: Pair<LocalDate, LocalDate>? = null

    private fun ensureDaily(from: LocalDate, to: LocalDate) {
        val r = dailyRange
        if (r != null && !from.isBefore(r.first) && !to.isAfter(r.second)) return
        val lo = from.minusDays(400); val hi = to
        status("Loading daily history…")
        val n = candles(KiteClient.TOKEN_NIFTY, "day", lo, hi, "NIFTY")
        val v = runCatching { candles(KiteClient.TOKEN_VIX, "day", lo, hi, "INDIA VIX") }.getOrDefault(emptyList())
        dailyCache = n to v
        dailyRange = from to to
    }

    private fun day(c: Candle) = Session.zdt(c.t).toLocalDate()

    override fun tradingDays(from: LocalDate, to: LocalDate): List<LocalDate> {
        ensureDaily(from, to)
        return dailyCache!!.first.map(::day).filter { it in from..to }.distinct().sorted()
    }

    override fun dailyBefore(day: LocalDate): Pair<List<Candle>, List<Candle>> {
        ensureDaily(day, day)
        val (n, v) = dailyCache!!
        // Daily candles are stamped at 00:00 / 09:15 IST of their day; keep strictly earlier days, ~1 year.
        return n.filter { day(it) < day }.takeLast(260) to v.filter { day(it) < day }.takeLast(260)
    }

    override fun chunk(from: LocalDate, to: LocalDate): HistoricalChunk {
        val lo = dailyCache?.first?.map(::day)?.lastOrNull { it < from } ?: from.minusDays(5) // previous trading day for prev closes
        require(java.time.temporal.ChronoUnit.DAYS.between(lo, to) <= 59) { "chunk too long for minute data (Kite: ≤ 60 days)" }
        val t = tokens
        status("Downloading NIFTY/VIX/Bank Nifty minute bars $from → $to")
        val nifty = candles(KiteClient.TOKEN_NIFTY, "minute", lo, to, "NIFTY")
        val vix = runCatching { candles(KiteClient.TOKEN_VIX, "minute", lo, to, "INDIA VIX") }.getOrDefault(emptyList())
        val bank = runCatching { candles(KiteClient.TOKEN_BANKNIFTY, "minute", lo, to, "NIFTY BANK") }.getOrDefault(emptyList())
        val fut = t.futures?.let { futures(it, lo, to) } ?: emptyList<FuturesBar>().also {
            if (!futuresUnavailable) { futuresUnavailable = true; notes += "No NIFTY futures instrument found; futures driver absent." }
        }
        val sectors = LinkedHashMap<Sector, List<Candle>>()
        t.sectors.forEach { (sec, tok) ->
            status("Downloading sector ${sec.label} $from → $to")
            runCatching { candles(tok, "5minute", lo, to, sec.label) }.onSuccess { sectors[sec] = it }
        }
        val stocks = LinkedHashMap<String, List<Candle>>()
        t.stocks.entries.forEachIndexed { i, (sym, tok) ->
            status("Downloading $sym (${i + 1}/${t.stocks.size}) $from → $to")
            runCatching { candles(tok, "5minute", lo, to, sym) }.onSuccess { stocks[sym] = it }
        }
        return HistoricalChunk(nifty, vix, bank, fut, sectors, stocks, stockBarMinutes = 5)
    }
}
