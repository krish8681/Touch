package com.niftyengine.app.store

import android.content.Context
import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.MacroInputs

enum class DataMode(val label: String) {
    SIMULATED("Simulator (offline demo)"),
    LIVE_PUBLIC("Live · NSE + Yahoo + RSS"),
    LIVE_KITE("Live · Kite + NSE + Yahoo + RSS"),
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
    val kiteApiKey: String = "",
    val kiteApiSecret: String = "",
    val kiteAccessToken: String = "",
    val kiteTokenDate: String = "",
    val macro: MacroInputs = MacroInputs(
        repoRate = 5.50, lastPolicyChangeBps = 0.0, cpiYoY = Double.NaN, cpiPrevYoY = Double.NaN,
        gdpGrowth = Double.NaN, gdpPrevGrowth = Double.NaN, pmiManufacturing = Double.NaN,
        creditGrowth = Double.NaN, liquidityCr = Double.NaN,
    ),
) {
    fun engineConfig() = EngineConfig(
        horizonMinutes = horizonMinutes, minProbability = minProbability, minConfidence = minConfidence,
        minExpectedMovePts = minExpectedMovePts, requireMarketOpen = mode != DataMode.SIMULATED,
        maxSpreadPct = maxSpreadPct, minOi = minOi, minVolume = minVolume,
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
            kiteApiKey = p.getString("kiteKey", "")!!,
            kiteApiSecret = p.getString("kiteSecret", "")!!,
            kiteAccessToken = p.getString("kiteToken", "")!!,
            kiteTokenDate = p.getString("kiteTokenDate", "")!!,
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
            .putBoolean("record", s.recordSessions).putBoolean("screenOn", s.keepScreenOn)
            .putString("kiteKey", s.kiteApiKey.trim()).putString("kiteSecret", s.kiteApiSecret.trim())
            .putString("kiteToken", s.kiteAccessToken.trim()).putString("kiteTokenDate", s.kiteTokenDate)
            .putString("m_repo", n(s.macro.repoRate)).putString("m_policy", n(s.macro.lastPolicyChangeBps))
            .putString("m_cpi", n(s.macro.cpiYoY)).putString("m_cpiPrev", n(s.macro.cpiPrevYoY))
            .putString("m_gdp", n(s.macro.gdpGrowth)).putString("m_gdpPrev", n(s.macro.gdpPrevGrowth))
            .putString("m_pmi", n(s.macro.pmiManufacturing)).putString("m_credit", n(s.macro.creditGrowth))
            .putString("m_liq", n(s.macro.liquidityCr))
            .apply()
    }
}
