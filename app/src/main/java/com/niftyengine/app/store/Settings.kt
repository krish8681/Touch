package com.niftyengine.app.store

import android.content.Context
import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.MacroInputs

enum class DataMode(val label: String) {
    SIMULATED("Simulator (offline demo)"),
    LIVE_PUBLIC("Live · NSE + Yahoo + RSS"),
    LIVE_KITE("Live · Kite Connect (recommended)"),
}

data class AppSettings(
    val mode: DataMode = DataMode.SIMULATED,
    val refreshSeconds: Int = 30,
    val simSecondsPerMinute: Int = 2,
    val horizonMinutes: Int = 60,
    val minProbability: Double = 0.65,
    val minConfidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val minExpectedMovePts: Double = 40.0,
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
    val macro: MacroInputs = MacroInputs(
        repoRate = 5.50, lastPolicyChangeBps = 0.0, cpiYoY = Double.NaN, cpiPrevYoY = Double.NaN,
        gdpGrowth = Double.NaN, gdpPrevGrowth = Double.NaN, pmiManufacturing = Double.NaN,
        creditGrowth = Double.NaN, liquidityCr = Double.NaN,
    ),
    /** Release date (yyyy-MM-dd) per macro field — drives MANUAL/STALE status. */
    val macroDates: Map<String, String> = emptyMap(),
    // ---- v3.2 integrity / calibration / costs
    val requireCalibration: Boolean = true,
    val minCalibrationSamples: Int = 150,
    val confirmCycles: Int = 2,
    val minOptionProfitProb: Double = 0.50,
    val eventThresholdBump: Double = 0.08,
    val minDataQuality: Double = 0.70,
    val brokeragePerOrder: Double = 20.0,
    val sttSellPct: Double = 0.1,
    val slippageTicks: Double = 1.0,
    val lotSize: Int = 65,
    val lots: Int = 1,
    // ---- v4 event intelligence (Gemini reads news; it never produces trade signals)
    val geminiEnabled: Boolean = true,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val geminiDailyBudget: Int = 200,
    val geminiMinIntervalSec: Int = 60,
    // ---- v5 strategy, risk and shadow execution (deterministic; the app never places real orders)
    val shadowMode: Boolean = true,
    val capital: Double = 200_000.0,
    val maxRiskPerTradePct: Double = 2.0,
    val maxDailyLossPct: Double = 5.0,
    val maxOpenPositions: Int = 1,
    val maxTradesPerDay: Int = 4,
    val maxIvPct: Double = 35.0,
    val longStopPct: Double = 35.0,
    val longTargetPct: Double = 60.0,
    val minTradeQuality: Double = 0.65,
    val enableLongOptions: Boolean = true,
    val enableSpreads: Boolean = true,
    val enableCondor: Boolean = true,
    // ---- v5.1 model health gate (score 0–100)
    val healthEligible: Double = 75.0,
    val healthShadow: Double = 60.0,
    // ---- v5.1 point-in-time: clock-skew allowance and max gap between the critical inputs' timestamps
    val futureToleranceSec: Double = 10.0,
    val maxCriticalSkewSec: Double = 180.0,
) {
    val geminiActive get() = geminiEnabled && geminiApiKey.isNotBlank() && mode != DataMode.SIMULATED

    fun macroInputs(): MacroInputs = macro.copy(
        source = "manual (Setup)",
        releasedAt = macroDates.mapNotNull { (k, v) ->
            runCatching { java.time.LocalDate.parse(v.trim()).atTime(12, 0).atZone(com.niftyengine.engine.core.Session.IST).toInstant().toEpochMilli() }
                .getOrNull()?.let { k to it }
        }.toMap(),
    )

    /** Kite access tokens expire daily around 06:00 IST. */
    fun kiteLoginNeeded(now: Long = System.currentTimeMillis()): Boolean {
        if (mode != DataMode.LIVE_KITE) return false
        if (kiteAccessToken.isBlank() || kiteTokenDate.isBlank()) return true
        val z = com.niftyengine.engine.core.Session.zdt(now)
        val issued = runCatching { java.time.LocalDate.parse(kiteTokenDate) }.getOrNull() ?: return true
        val validFrom = if (z.hour < 6) z.toLocalDate().minusDays(1) else z.toLocalDate()
        return issued.isBefore(validFrom)
    }

    fun engineConfig() = EngineConfig(
        horizonMinutes = horizonMinutes, minProbability = minProbability, minConfidence = minConfidence,
        minExpectedMovePts = minExpectedMovePts, requireMarketOpen = mode != DataMode.SIMULATED,
        maxSpreadPct = maxSpreadPct, minOi = minOi, minVolume = minVolume,
        costs = com.niftyengine.engine.core.TransactionCosts(brokeragePerOrder = brokeragePerOrder, sttSellPct = sttSellPct,
            slippageTicks = slippageTicks, lotSize = lotSize, lots = lots),
        minOptionProfitProb = minOptionProfitProb, eventThresholdBump = eventThresholdBump, minDataQuality = minDataQuality,
        confirmCycles = confirmCycles, requireCalibration = requireCalibration,
        risk = com.niftyengine.engine.model.RiskConfig(
            capital = capital, maxRiskPerTradePct = maxRiskPerTradePct, maxDailyLossPct = maxDailyLossPct,
            maxOpenPositions = maxOpenPositions, maxTradesPerDay = maxTradesPerDay, maxSpreadPct = maxSpreadPct,
            maxIvPct = maxIvPct, longStopPct = longStopPct, longTargetPct = longTargetPct,
        ),
        shadowMode = shadowMode, minTradeQuality = minTradeQuality,
        enableLongOptions = enableLongOptions, enableSpreads = enableSpreads, enableCondor = enableCondor,
        healthEligible = healthEligible, healthShadow = healthShadow,
        futureToleranceSec = futureToleranceSec, maxCriticalSkewSec = maxCriticalSkewSec,
    )

    /** Key settings copied into each logged prediction (audit trail). */
    fun auditConfig(): Map<String, String> = mapOf(
        "mode" to mode.name, "horizon" to "$horizonMinutes", "minProb" to "$minProbability", "minConf" to minConfidence.name,
        "minMove" to "$minExpectedMovePts", "maxSpread" to "$maxSpreadPct", "minOptionProb" to "$minOptionProfitProb",
        "eventBump" to "$eventThresholdBump", "minDataQuality" to "$minDataQuality", "confirmCycles" to "$confirmCycles",
        "requireCalibration" to "$requireCalibration", "lotSize" to "$lotSize", "lots" to "$lots",
        "brokerage" to "$brokeragePerOrder", "stt" to "$sttSellPct", "slippageTicks" to "$slippageTicks",
        "eventAnalyst" to if (geminiActive) "gemini:$geminiModel" else "rules",
        "shadow" to "$shadowMode", "capital" to "$capital", "riskPct" to "$maxRiskPerTradePct", "dailyLossPct" to "$maxDailyLossPct",
        "maxPositions" to "$maxOpenPositions", "maxTrades" to "$maxTradesPerDay", "maxIv" to "$maxIvPct",
        "stopPct" to "$longStopPct", "targetPct" to "$longTargetPct", "minQuality" to "$minTradeQuality",
        "health" to "$healthShadow/$healthEligible",
        "pitTolerance" to "$futureToleranceSec", "maxCriticalSkew" to "$maxCriticalSkewSec",
        "strategies" to listOfNotNull("long".takeIf { enableLongOptions }, "spread".takeIf { enableSpreads }, "condor".takeIf { enableCondor }).joinToString("+"),
    )
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun d(key: String, def: Double) = p.getString(key, null)?.toDoubleOrNull() ?: def

    fun load(): AppSettings {
        val def = AppSettings()
        return AppSettings(
            mode = runCatching { DataMode.valueOf(p.getString("mode", def.mode.name)!!) }.getOrDefault(def.mode),
            refreshSeconds = p.getInt("refresh", def.refreshSeconds),
            simSecondsPerMinute = p.getInt("simSpeed", def.simSecondsPerMinute),
            horizonMinutes = p.getInt("horizon", def.horizonMinutes),
            minProbability = d("minProb", def.minProbability),
            minConfidence = runCatching { ConfidenceLevel.valueOf(p.getString("minConf", def.minConfidence.name)!!) }.getOrDefault(def.minConfidence),
            minExpectedMovePts = d("minMove", def.minExpectedMovePts),
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
            minOptionProfitProb = d("minOptP", def.minOptionProfitProb),
            eventThresholdBump = d("eventBump", def.eventThresholdBump),
            minDataQuality = d("minDQ", def.minDataQuality),
            brokeragePerOrder = d("brok", def.brokeragePerOrder),
            sttSellPct = d("stt", def.sttSellPct),
            slippageTicks = d("slip", def.slippageTicks),
            lotSize = p.getInt("lotSize", def.lotSize),
            lots = p.getInt("lots", def.lots),
            geminiEnabled = p.getBoolean("gemOn", def.geminiEnabled),
            geminiApiKey = p.getString("gemKey", "")!!,
            geminiModel = p.getString("gemModel", def.geminiModel)!!.ifBlank { def.geminiModel },
            geminiDailyBudget = p.getInt("gemBudget", def.geminiDailyBudget),
            geminiMinIntervalSec = p.getInt("gemInterval", def.geminiMinIntervalSec),
            shadowMode = p.getBoolean("shadow", def.shadowMode),
            capital = d("capital", def.capital),
            maxRiskPerTradePct = d("riskPct", def.maxRiskPerTradePct),
            maxDailyLossPct = d("dailyLossPct", def.maxDailyLossPct),
            maxOpenPositions = p.getInt("maxPos", def.maxOpenPositions),
            maxTradesPerDay = p.getInt("maxTrades", def.maxTradesPerDay),
            maxIvPct = d("maxIv", def.maxIvPct),
            longStopPct = d("stopPct", def.longStopPct),
            longTargetPct = d("targetPct", def.longTargetPct),
            minTradeQuality = d("minQuality", def.minTradeQuality),
            enableLongOptions = p.getBoolean("stratLong", def.enableLongOptions),
            enableSpreads = p.getBoolean("stratSpread", def.enableSpreads),
            enableCondor = p.getBoolean("stratCondor", def.enableCondor),
            healthEligible = d("healthElig", def.healthEligible),
            healthShadow = d("healthShadow", def.healthShadow),
            futureToleranceSec = d("pitTol", def.futureToleranceSec),
            maxCriticalSkewSec = d("critSkew", def.maxCriticalSkewSec),
            macro = MacroInputs(
                repoRate = d("m_repo", def.macro.repoRate),
                lastPolicyChangeBps = d("m_policy", def.macro.lastPolicyChangeBps),
                cpiYoY = d("m_cpi", Double.NaN), cpiPrevYoY = d("m_cpiPrev", Double.NaN),
                gdpGrowth = d("m_gdp", Double.NaN), gdpPrevGrowth = d("m_gdpPrev", Double.NaN),
                pmiManufacturing = d("m_pmi", Double.NaN), creditGrowth = d("m_credit", Double.NaN),
                liquidityCr = d("m_liq", Double.NaN),
            ),
        )
    }

    fun save(s: AppSettings) {
        fun n(x: Double) = if (x.isNaN()) null else x.toString()
        p.edit()
            .putString("mode", s.mode.name).putInt("refresh", s.refreshSeconds).putInt("simSpeed", s.simSecondsPerMinute)
            .putInt("horizon", s.horizonMinutes).putString("minProb", s.minProbability.toString())
            .putString("minConf", s.minConfidence.name).putString("minMove", s.minExpectedMovePts.toString())
            .putString("maxSpread", s.maxSpreadPct.toString()).putString("minOi", s.minOi.toString())
            .putString("minVol", s.minVolume.toString()).putBoolean("notify", s.notifyOnTrade)
            .putBoolean("record", s.recordSessions).putBoolean("screenOn", s.keepScreenOn).putBoolean("background", s.runInBackground)
            .putString("kiteKey", s.kiteApiKey.trim()).putString("kiteSecret", s.kiteApiSecret.trim())
            .putString("kiteToken", s.kiteAccessToken.trim()).putString("kiteTokenDate", s.kiteTokenDate)
            .putString("m_repo", n(s.macro.repoRate)).putString("m_policy", n(s.macro.lastPolicyChangeBps))
            .putString("m_cpi", n(s.macro.cpiYoY)).putString("m_cpiPrev", n(s.macro.cpiPrevYoY))
            .putString("m_gdp", n(s.macro.gdpGrowth)).putString("m_gdpPrev", n(s.macro.gdpPrevGrowth))
            .putString("m_pmi", n(s.macro.pmiManufacturing)).putString("m_credit", n(s.macro.creditGrowth))
            .putString("m_liq", n(s.macro.liquidityCr))
            .putBoolean("reqCal", s.requireCalibration).putInt("minCalN", s.minCalibrationSamples).putInt("confirm", s.confirmCycles)
            .putString("minOptP", s.minOptionProfitProb.toString()).putString("eventBump", s.eventThresholdBump.toString())
            .putString("minDQ", s.minDataQuality.toString()).putString("brok", s.brokeragePerOrder.toString())
            .putString("stt", s.sttSellPct.toString()).putString("slip", s.slippageTicks.toString())
            .putInt("lotSize", s.lotSize).putInt("lots", s.lots)
            .putBoolean("gemOn", s.geminiEnabled).putString("gemKey", s.geminiApiKey.trim()).putString("gemModel", s.geminiModel.trim())
            .putInt("gemBudget", s.geminiDailyBudget).putInt("gemInterval", s.geminiMinIntervalSec)
            .putBoolean("shadow", s.shadowMode).putString("capital", s.capital.toString())
            .putString("riskPct", s.maxRiskPerTradePct.toString()).putString("dailyLossPct", s.maxDailyLossPct.toString())
            .putInt("maxPos", s.maxOpenPositions).putInt("maxTrades", s.maxTradesPerDay).putString("maxIv", s.maxIvPct.toString())
            .putString("stopPct", s.longStopPct.toString()).putString("targetPct", s.longTargetPct.toString())
            .putString("minQuality", s.minTradeQuality.toString())
            .putBoolean("stratLong", s.enableLongOptions).putBoolean("stratSpread", s.enableSpreads).putBoolean("stratCondor", s.enableCondor)
            .putString("healthElig", s.healthEligible.toString()).putString("healthShadow", s.healthShadow.toString())
            .putString("pitTol", s.futureToleranceSec.toString()).putString("critSkew", s.maxCriticalSkewSec.toString())
            .also { e -> MACRO_DATE_KEYS.forEach { k -> e.putString("md_$k", s.macroDates[k] ?: "") } }
            .apply()
    }
}

val MACRO_DATE_KEYS = listOf("repoRate", "cpiYoY", "gdpGrowth", "pmiManufacturing", "creditGrowth", "liquidityCr")
