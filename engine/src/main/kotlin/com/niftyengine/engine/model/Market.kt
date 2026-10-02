package com.niftyengine.engine.model

import kotlinx.serialization.Serializable

/** One OHLCV bar. Times are epoch millis (UTC). */
@Serializable
data class Candle(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double = 0.0,
)

/**
 * Raw data for a single instrument (index, stock, global asset...).
 * [intraday] holds today's bars (ascending), [daily] holds completed daily bars (ascending, excluding today).
 * Either list may be empty; the engine also accumulates its own tick history across snapshots.
 */
@Serializable
data class InstrumentData(
    val symbol: String,
    val last: Double,
    val prevClose: Double,
    val open: Double = Double.NaN,
    val high: Double = Double.NaN,
    val low: Double = Double.NaN,
    val volume: Double = 0.0,
    val intraday: List<Candle> = emptyList(),
    val daily: List<Candle> = emptyList(),
    /** Free-float market cap if the source publishes it (NSE `ffmc`). Used to derive live index weights. */
    val freeFloatMcap: Double = Double.NaN,
    /** Source timestamp of [last] (epoch ms). 0 = unknown (treated as degraded by the data-quality engine). */
    val asOf: Long = 0L,
) {
    val changePct: Double get() = if (prevClose > 0) (last - prevClose) / prevClose * 100.0 else 0.0
}

@Serializable
data class FuturesData(
    val symbol: String,
    val expiry: String,
    val last: Double,
    val prevClose: Double,
    val openInterest: Double,
    /** OI at previous day close. NaN if unknown (engine then uses intraday OI history). */
    val prevOpenInterest: Double = Double.NaN,
    val volume: Double = 0.0,
    /** Today's futures bars with OI (e.g. Kite historical `oi=1`), ascending. Optional. */
    val intraday: List<FuturesBar> = emptyList(),
    val asOf: Long = 0L,
)

@Serializable
data class FuturesBar(val t: Long, val price: Double, val oi: Double, val volume: Double = 0.0)

@Serializable
data class OptionLeg(
    val oi: Double = 0.0,
    val changeOi: Double = 0.0,
    val volume: Double = 0.0,
    /** Implied volatility in percent (e.g. 13.5). NaN/0 if not published. */
    val iv: Double = Double.NaN,
    val ltp: Double = 0.0,
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    /** Seconds since this contract last traded (Kite `last_trade_time`); NaN if the feed doesn't say. */
    val lastTradeAgeSec: Double = Double.NaN,
)

@Serializable
data class OptionStrikeRow(
    val strike: Double,
    val call: OptionLeg,
    val put: OptionLeg,
)

@Serializable
data class OptionChain(
    val underlying: Double,
    val expiry: String,
    /** Expiry instant in epoch millis (15:30 IST of expiry day). */
    val expiryMillis: Long,
    val rows: List<OptionStrikeRow>,
    val strikeStep: Double = 50.0,
    val asOf: Long = 0L,
)

@Serializable
enum class Sector(val label: String) {
    BANK("Bank"), FIN_SERVICES("Fin Services"), IT("IT"), ENERGY("Energy"), AUTO("Auto"),
    FMCG("FMCG"), PHARMA("Pharma/Health"), METALS("Metals"), TELECOM("Telecom"),
    INFRA("Infra/Capital Goods"), CONSUMER("Consumer"), OTHER("Other");
}

@Serializable
enum class GlobalAsset(val label: String, val riskSign: Double, val typicalDailyMovePct: Double) {
    SP500("S&P 500", +1.0, 1.0),
    NASDAQ("Nasdaq", +1.0, 1.3),
    DOW("Dow", +1.0, 0.9),
    NIKKEI("Nikkei", +1.0, 1.2),
    HANGSENG("Hang Seng", +1.0, 1.5),
    SHANGHAI("Shanghai", +1.0, 1.1),
    DAX("DAX", +1.0, 1.1),
    US_VIX("US VIX", -1.0, 6.0),
    US10Y("US 10Y", -1.0, 1.5),
    US2Y("US 2Y", -1.0, 1.8),
    DXY("DXY", -1.0, 0.4),
    BRENT("Brent", -1.0, 1.8),
    WTI("WTI", -1.0, 2.0),
    GOLD("Gold", -0.5, 1.0),
    USDINR("USD/INR", -1.0, 0.25),
    INDIA10Y("India 10Y", -1.0, 0.6);
}

/** Slow + fast Indian macro inputs. Slow values are usually entered/updated manually. */
@Serializable
data class MacroInputs(
    val repoRate: Double = Double.NaN,
    /** bps change at the last policy (negative = cut). */
    val lastPolicyChangeBps: Double = 0.0,
    val cpiYoY: Double = Double.NaN,
    val cpiPrevYoY: Double = Double.NaN,
    val wpiYoY: Double = Double.NaN,
    val gdpGrowth: Double = Double.NaN,
    val gdpPrevGrowth: Double = Double.NaN,
    val pmiManufacturing: Double = Double.NaN,
    val iipYoY: Double = Double.NaN,
    val creditGrowth: Double = Double.NaN,
    /** Banking system liquidity, ₹ crore. Positive = surplus. */
    val liquidityCr: Double = Double.NaN,
    val tradeBalanceBn: Double = Double.NaN,
    /** Release/as-of date per field name (e.g. "cpiYoY" → epoch ms). Values without a date are treated as undated MANUAL inputs. */
    val releasedAt: Map<String, Long> = emptyMap(),
    val source: String = "manual",
)

@Serializable
data class FlowData(
    /** Net FPI cash-market flow, ₹ crore, latest session. */
    val fpiNetCr: Double = 0.0,
    val diiNetCr: Double = 0.0,
    /** Rolling 5-session sums, ₹ crore (NaN if unknown). */
    val fpi5dCr: Double = Double.NaN,
    val dii5dCr: Double = Double.NaN,
    val date: String = "",
    val asOf: Long = 0L,
)

@Serializable
data class NewsItem(
    val id: String,
    val title: String,
    val source: String,
    val publishedAt: Long,
    val summary: String = "",
    val url: String = "",
)

/** Everything the engine sees at one instant. */
@Serializable
data class MarketSnapshot(
    val timestamp: Long,
    val nifty: InstrumentData,
    val bankNifty: InstrumentData? = null,
    val vix: InstrumentData? = null,
    val futures: FuturesData? = null,
    val optionChain: OptionChain? = null,
    val constituents: Map<String, InstrumentData> = emptyMap(),
    val sectors: Map<Sector, InstrumentData> = emptyMap(),
    val global: Map<GlobalAsset, InstrumentData> = emptyMap(),
    val macro: MacroInputs = MacroInputs(),
    val flows: FlowData? = null,
    val news: List<NewsItem> = emptyList(),
    val source: String = "unknown",
    /** Event analyses (Gemini or rules) delivered up to this snapshot — recorded so replays stay point-in-time. */
    val eventAnalyses: List<EventAnalysis> = emptyList(),
    /** Per-feed health messages from the collector (feed -> status). */
    val feedStatus: Map<String, String> = emptyMap(),
)
