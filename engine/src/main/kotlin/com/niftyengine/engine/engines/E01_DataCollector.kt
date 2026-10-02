package com.niftyengine.engine.engines

import com.niftyengine.engine.model.MarketSnapshot

/**
 * 01 — Data Collector.
 *
 * The engine itself never does network I/O. A [SnapshotProvider] (live NSE/Yahoo/Kite feeds in the
 * Android app, the simulator, or the replay engine) produces a complete [MarketSnapshot]; the rest of
 * the pipeline is deterministic given that snapshot plus the engine's accumulated state.
 */
interface SnapshotProvider {
    val name: String
    /** Blocking call: gather every feed and return one snapshot. Never throws for a single feed failure. */
    fun collect(now: Long): MarketSnapshot
}

/** Data-quality gate applied before analysis. */
object DataCollector {
    data class Health(val usable: Boolean, val coverage: Double, val missing: List<String>)

    fun health(s: MarketSnapshot): Health {
        val checks = linkedMapOf(
            "NIFTY spot" to (s.nifty.last > 0),
            "NIFTY futures" to (s.futures != null),
            "Option chain" to (s.optionChain?.rows?.isNotEmpty() == true),
            "India VIX" to (s.vix != null),
            "Bank Nifty" to (s.bankNifty != null),
            "Constituents" to (s.constituents.size >= 20),
            "Sectors" to (s.sectors.isNotEmpty()),
            "Global" to (s.global.size >= 4),
            "News" to (s.news.isNotEmpty()),
            "Flows" to (s.flows != null),
        )
        val missing = checks.filterValues { !it }.keys.toList()
        return Health(s.nifty.last > 0, checks.values.count { it }.toDouble() / checks.size, missing)
    }
}
