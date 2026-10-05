package com.niftyengine.app.store

import android.content.Context
import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.CalendarCategory
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.EarningsInputs
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MacroInputs
import com.niftyengine.engine.model.ScheduledEvent
import java.time.LocalDate

enum class DataMode(val label: String) {
    SIMULATED("Simulator (offline demo)"),
    LIVE_PUBLIC("Live · NSE + Yahoo + RSS"),
    LIVE_KITE("Live · Kite Connect (recommended)"),
}

const val CALENDAR_HELP = "# One event per line:  yyyy-MM-dd | CATEGORY | title | importance 1-3\n" +
    "# CATEGORY: RBI, FED, INFLATION, GROWTH, EARNINGS, GOVERNMENT, GLOBAL_DATA, GEOPOLITICS, OTHER\n" +
    "# Expiries, 2026 FOMC dates and estimated India CPI/GDP dates are built in. Add RBI MPC dates and big NIFTY results here.\n"

data class AppSettings(
    val mode: DataMode = DataMode.SIMULATED,
    val refreshSeconds: Int = 30,
    val simSecondsPerMinute: Int = 2,
    /** H1 horizon at which intraday option trades are exited / evaluated. */
    val intradayHorizon: HorizonId = HorizonId.M60,
    val minProbability: Double = 0.55,
    val minConfidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val minAlignment: Int = 2,
    val maxSpreadPct: Double = 3.0,
    val minOi: Double = 2000.0,
    val minVolume: Double = 500.0,
    val notifyOnTrade: Boolean = true,
    val recordSessions: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** Keep the engine running (foreground service + wake lock) when the app is minimised or the screen is off. */
    val runInBackground: Boolean = true,
    val kiteApiKey: String = "",
    val kiteApiSecret: String = "",
    val kiteAccessToken: String = "",
    val kiteTokenDate: String = "",
    val macro: MacroInputs = MacroInputs(repoRate = 5.50, lastPolicyChangeBps = 0.0),
    /** Release date (yyyy-MM-dd) per macro field — drives MANUAL/STALE status and freshness. */
    val macroDates: Map<String, String> = emptyMap(),
    val earnings: EarningsInputs = EarningsInputs(),
    val earningsDate: String = "",
    /** User event calendar, one event per line (see [CALENDAR_HELP]). */
    val calendarText: String = CALENDAR_HELP,
    // ---- risk / calibration / costs
    val requireCalibration: Boolean = true,
    val minCalibrationSamples: Int = 150,
    val confirmCycles: Int = 2,
    val minOptionProfitProb: Double = 0.45,
    val minReturnOnRisk: Double = 0.05,
    val minRiskReward: Double = 0.25,
    val eventThresholdBump: Double = 0.05,
    val minDataQuality: Double = 0.70,
    val capital: Double = 500_000.0,
    val riskPerTradePct: Double = 2.0,
    val maxLots: Int = 10,
    val brokeragePerOrder: Double = 20.0,
    val sttSellPct: Double = 0.1,
    val slippageTicks: Double = 1.0,
    val lotSize: Int = 65,
    val lots: Int = 1,
    // ---- valuation (H3)
    val fairPeLow: Double = 19.0,
    val fairPeHigh: Double = 23.0,
    val india10y: Double = Double.NaN,
    // ---- event intelligence (Gemini reads news; it never produces trade signals)
    val geminiEnabled: Boolean = true,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val geminiDailyBudget: Int = 200,
    val geminiMinIntervalSec: Int = 60,
) {
    val geminiActive get() = geminiEnabled && geminiApiKey.isNotBlank() && mode != DataMode.SIMULATED

    private fun dateMs(v: String): Long? = runCatching { LocalDate.parse(v.trim()).atTime(12, 0).atZone(Session.IST).toInstant().toEpochMilli() }.getOrNull()

    fun macroInputs(): MacroInputs = macro.copy(
        source = "manual (Setup)",
        releasedAt = macroDates.mapNotNull { (k, v) -> dateMs(v)?.let { k to it } }.toMap(),
    )

    fun earningsInputs(): EarningsInputs = earnings.copy(asOf = dateMs(earningsDate) ?: 0L, source = "manual (Setup)")

    /** Parsed user calendar; malformed lines are skipped. */
    fun calendar(): List<ScheduledEvent> = parseCalendar(calendarText)

    /** Kite access tokens expire daily around 06:00 IST. */
    fun kiteLoginNeeded(now: Long = System.currentTimeMillis()): Boolean {
        if (mode != DataMode.LIVE_KITE) return false
        if (kiteAccessToken.isBlank() || kiteTokenDate.isBlank()) return true
        val z = Session.zdt(now)
        val issued = runCatching { LocalDate.parse(kiteTokenDate) }.getOrNull() ?: return true
        val validFrom = if (z.hour < 6) z.toLocalDate().minusDays(1) else z.toLocalDate()
        return issued.isBefore(validFrom)
    }

    fun engineConfig() = EngineConfig(
        intradayHorizon = intradayHorizon, minProbability = minProbability, minConfidence = minConfidence, minAlignment = minAlignment,
        requireMarketOpen = mode != DataMode.SIMULATED, maxSpreadPct = maxSpreadPct, minOi = minOi, minVolume = minVolume,
        costs = com.niftyengine.engine.core.TransactionCosts(brokeragePerOrder = brokeragePerOrder, sttSellPct = sttSellPct,
            slippageTicks = slippageTicks, lotSize = lotSize, lots = lots),
        minOptionProfitProb = minOptionProfitProb, minReturnOnRisk = minReturnOnRisk, minRiskReward = minRiskReward,
        eventThresholdBump = eventThresholdBump, minDataQuality = minDataQuality, confirmCycles = confirmCycles,
        requireCalibration = requireCalibration, capital = capital, riskPerTradePct = riskPerTradePct, maxLots = maxLots,
        fairPeLow = fairPeLow, fairPeHigh = fairPeHigh, india10y = india10y,
    )

    /** Key settings copied into each logged prediction (audit trail). */
    fun auditConfig(): Map<String, String> = mapOf(
        "mode" to mode.name, "intradayHorizon" to intradayHorizon.name, "minProb" to "$minProbability", "minConf" to minConfidence.name,
        "minAlignment" to "$minAlignment", "maxSpread" to "$maxSpreadPct", "minOptionProb" to "$minOptionProfitProb",
        "minReturnOnRisk" to "$minReturnOnRisk", "minRiskReward" to "$minRiskReward", "eventBump" to "$eventThresholdBump",
        "minDataQuality" to "$minDataQuality", "confirmCycles" to "$confirmCycles", "requireCalibration" to "$requireCalibration",
        "capital" to "$capital", "riskPct" to "$riskPerTradePct", "maxLots" to "$maxLots", "lotSize" to "$lotSize",
        "brokerage" to "$brokeragePerOrder", "stt" to "$sttSellPct", "slippageTicks" to "$slippageTicks",
        "fairPe" to "$fairPeLow-$fairPeHigh", "eventAnalyst" to if (geminiActive) "gemini:$geminiModel" else "rules",
    )
}

/** "yyyy-MM-dd | CATEGORY | title | importance" per line; '#' lines are comments. */
fun parseCalendar(text: String): List<ScheduledEvent> = text.lines().mapNotNull { line ->
    val l = line.trim()
    if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
    val f = l.split("|").map { it.trim() }
    val date = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
    val cat = f.getOrNull(1)?.let { c -> CalendarCategory.values().firstOrNull { it.name.equals(c, true) || it.label.startsWith(c, true) } } ?: CalendarCategory.OTHER
    val title = f.getOrNull(2)?.ifBlank { null } ?: cat.label
    val imp = f.getOrNull(3)?.toIntOrNull()?.coerceIn(1, 3) ?: 2
    ScheduledEvent(date.toString(), title, cat, imp, "user")
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun d(key: String, def: Double) = p.getString(key, null)?.toDoubleOrNull() ?: def
    private fun dn(key: String) = p.getString(key, null)?.toDoubleOrNull() ?: Double.NaN

    fun load(): AppSettings {
        val def = AppSettings()
        return AppSettings(
            mode = runCatching { DataMode.valueOf(p.getString("mode", def.mode.name)!!) }.getOrDefault(def.mode),
            refreshSeconds = p.getInt("refresh", def.refreshSeconds),
            simSecondsPerMinute = p.getInt("simSpeed", def.simSecondsPerMinute),
            intradayHorizon = runCatching { HorizonId.valueOf(p.getString("intraH", def.intradayHorizon.name)!!) }.getOrDefault(def.intradayHorizon)
                .takeIf { it.intraday } ?: def.intradayHorizon,
            minProbability = d("minProb5", def.minProbability),
            minConfidence = runCatching { ConfidenceLevel.valueOf(p.getString("minConf5", def.minConfidence.name)!!) }.getOrDefault(def.minConfidence),
            minAlignment = p.getInt("minAlign", def.minAlignment),
            maxSpreadPct = d("maxSpread", def.maxSpreadPct),
            minOi = d("minOi", def.minOi),
            minVolume = d("minVol", def.minVolume),
            notifyOnTrade = p.getBoolean("notify", def.notifyOnTrade),
            recordSessions = p.getBoolean("record", def.recordSessions),
            keepScreenOn = p.getBoolean("screenOn", def.keepScreenOn),
            runInBackground = p.getBoolean("background", def.runInBackground),
            kiteApiKey = p.getString("kiteKey", "")!!,
            kiteApiSecret = p.getString("kiteSecret", "")!!,
            kiteAccessToken = p.getString("kiteToken", "")!!,
            kiteTokenDate = p.getString("kiteTokenDate", "")!!,
            macroDates = MACRO_DATE_KEYS.mapNotNull { k -> p.getString("md_$k", null)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap(),
            requireCalibration = p.getBoolean("reqCal", def.requireCalibration),
            minCalibrationSamples = p.getInt("minCalN", def.minCalibrationSamples),
            confirmCycles = p.getInt("confirm", def.confirmCycles),
            minOptionProfitProb = d("minOptP5", def.minOptionProfitProb),
            minReturnOnRisk = d("minRoR", def.minReturnOnRisk),
            minRiskReward = d("minRR", def.minRiskReward),
            eventThresholdBump = d("eventBump5", def.eventThresholdBump),
            minDataQuality = d("minDQ", def.minDataQuality),
            capital = d("capital", def.capital),
            riskPerTradePct = d("riskPct", def.riskPerTradePct),
            maxLots = p.getInt("maxLots", def.maxLots),
            brokeragePerOrder = d("brok", def.brokeragePerOrder),
            sttSellPct = d("stt", def.sttSellPct),
            slippageTicks = d("slip", def.slippageTicks),
            lotSize = p.getInt("lotSize", def.lotSize),
            lots = p.getInt("lots", def.lots),
            fairPeLow = d("peLow", def.fairPeLow),
            fairPeHigh = d("peHigh", def.fairPeHigh),
            india10y = dn("in10y"),
            geminiEnabled = p.getBoolean("gemOn", def.geminiEnabled),
            geminiApiKey = p.getString("gemKey", "")!!,
            geminiModel = p.getString("gemModel", def.geminiModel)!!.ifBlank { def.geminiModel },
            geminiDailyBudget = p.getInt("gemBudget", def.geminiDailyBudget),
            geminiMinIntervalSec = p.getInt("gemInterval", def.geminiMinIntervalSec),
            macro = MacroInputs(
                repoRate = d("m_repo", def.macro.repoRate),
                lastPolicyChangeBps = d("m_policy", def.macro.lastPolicyChangeBps),
                policyExpectedChangeBps = dn("m_policyExp"),
                cpiYoY = dn("m_cpi"), cpiPrevYoY = dn("m_cpiPrev"), cpiConsensus = dn("m_cpiCons"),
                gdpGrowth = dn("m_gdp"), gdpPrevGrowth = dn("m_gdpPrev"), gdpConsensus = dn("m_gdpCons"),
                pmiManufacturing = dn("m_pmi"), iipYoY = dn("m_iip"), wpiYoY = dn("m_wpi"), creditGrowth = dn("m_credit"),
                liquidityCr = dn("m_liq"), fiscalStance = dn("m_fiscal"),
            ),
            earnings = EarningsInputs(
                forwardEps = dn("e_fwd"), forwardEpsPrev = dn("e_fwdPrev"), epsGrowthExpected = dn("e_exp"),
                epsGrowthActual = dn("e_act"), beatRatio = dn("e_beat"),
            ),
            earningsDate = p.getString("e_date", "")!!,
            calendarText = p.getString("calendar", def.calendarText)!!,
        )
    }

    fun save(s: AppSettings) {
        fun n(x: Double) = if (x.isNaN()) null else x.toString()
        p.edit()
            .putString("mode", s.mode.name).putInt("refresh", s.refreshSeconds).putInt("simSpeed", s.simSecondsPerMinute)
            .putString("intraH", s.intradayHorizon.name).putString("minProb5", s.minProbability.toString())
            .putString("minConf5", s.minConfidence.name).putInt("minAlign", s.minAlignment)
            .putString("maxSpread", s.maxSpreadPct.toString()).putString("minOi", s.minOi.toString())
            .putString("minVol", s.minVolume.toString()).putBoolean("notify", s.notifyOnTrade)
            .putBoolean("record", s.recordSessions).putBoolean("screenOn", s.keepScreenOn).putBoolean("background", s.runInBackground)
            .putString("kiteKey", s.kiteApiKey.trim()).putString("kiteSecret", s.kiteApiSecret.trim())
            .putString("kiteToken", s.kiteAccessToken.trim()).putString("kiteTokenDate", s.kiteTokenDate)
            .putString("m_repo", n(s.macro.repoRate)).putString("m_policy", n(s.macro.lastPolicyChangeBps))
            .putString("m_policyExp", n(s.macro.policyExpectedChangeBps))
            .putString("m_cpi", n(s.macro.cpiYoY)).putString("m_cpiPrev", n(s.macro.cpiPrevYoY)).putString("m_cpiCons", n(s.macro.cpiConsensus))
            .putString("m_gdp", n(s.macro.gdpGrowth)).putString("m_gdpPrev", n(s.macro.gdpPrevGrowth)).putString("m_gdpCons", n(s.macro.gdpConsensus))
            .putString("m_pmi", n(s.macro.pmiManufacturing)).putString("m_iip", n(s.macro.iipYoY)).putString("m_wpi", n(s.macro.wpiYoY))
            .putString("m_credit", n(s.macro.creditGrowth)).putString("m_liq", n(s.macro.liquidityCr)).putString("m_fiscal", n(s.macro.fiscalStance))
            .putString("e_fwd", n(s.earnings.forwardEps)).putString("e_fwdPrev", n(s.earnings.forwardEpsPrev))
            .putString("e_exp", n(s.earnings.epsGrowthExpected)).putString("e_act", n(s.earnings.epsGrowthActual))
            .putString("e_beat", n(s.earnings.beatRatio)).putString("e_date", s.earningsDate.trim())
            .putString("calendar", s.calendarText)
            .putBoolean("reqCal", s.requireCalibration).putInt("minCalN", s.minCalibrationSamples).putInt("confirm", s.confirmCycles)
            .putString("minOptP5", s.minOptionProfitProb.toString()).putString("minRoR", s.minReturnOnRisk.toString())
            .putString("minRR", s.minRiskReward.toString()).putString("eventBump5", s.eventThresholdBump.toString())
            .putString("minDQ", s.minDataQuality.toString()).putString("capital", s.capital.toString())
            .putString("riskPct", s.riskPerTradePct.toString()).putInt("maxLots", s.maxLots)
            .putString("brok", s.brokeragePerOrder.toString())
            .putString("stt", s.sttSellPct.toString()).putString("slip", s.slippageTicks.toString())
            .putInt("lotSize", s.lotSize).putInt("lots", s.lots)
            .putString("peLow", s.fairPeLow.toString()).putString("peHigh", s.fairPeHigh.toString()).putString("in10y", n(s.india10y))
            .putBoolean("gemOn", s.geminiEnabled).putString("gemKey", s.geminiApiKey.trim()).putString("gemModel", s.geminiModel.trim())
            .putInt("gemBudget", s.geminiDailyBudget).putInt("gemInterval", s.geminiMinIntervalSec)
            .also { e -> MACRO_DATE_KEYS.forEach { k -> e.putString("md_$k", s.macroDates[k] ?: "") } }
            .apply()
    }
}

val MACRO_DATE_KEYS = listOf("repoRate", "lastPolicyChangeBps", "cpiYoY", "gdpGrowth", "pmiManufacturing", "iipYoY", "wpiYoY",
    "creditGrowth", "liquidityCr", "fiscalStance")
