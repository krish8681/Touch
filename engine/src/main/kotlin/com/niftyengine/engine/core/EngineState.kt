package com.niftyengine.engine.core

import com.niftyengine.engine.model.MarketRegime
import com.niftyengine.engine.model.OptionChain

/**
 * Mutable memory carried between engine cycles: tick histories, futures OI history, the first option chain seen
 * today per expiry (for OI migration / IV change) and the regime history.
 */
class EngineState(private val maxTicks: Int = 2000) {
    data class Tick(val t: Long, val price: Double, val volume: Double = 0.0)
    data class FutTick(val t: Long, val price: Double, val oi: Double, val volume: Double, val spot: Double)

    val ticks = HashMap<String, ArrayDeque<Tick>>()
    val futures = ArrayDeque<FutTick>()
    /** Chain as of session open (or first seen today), keyed by expiry. */
    val openingChains = HashMap<String, OptionChain>()
    val regimeHistory = ArrayDeque<Pair<Long, MarketRegime>>()
    var sessionDay: Long = -1

    fun record(symbol: String, t: Long, price: Double, volume: Double = 0.0) {
        if (price.isNaN() || price <= 0) return
        val q = ticks.getOrPut(symbol) { ArrayDeque() }
        if (q.isNotEmpty() && q.last().t >= t) return
        q.addLast(Tick(t, price, volume))
        while (q.size > maxTicks) q.removeFirst()
    }

    fun history(symbol: String): List<Tick> = ticks[symbol]?.toList() ?: emptyList()

    fun openingChain(c: OptionChain): OptionChain = openingChains.getOrPut(c.expiry) { c }

    fun pushRegime(t: Long, r: MarketRegime, keep: Int = 60) {
        regimeHistory.addLast(t to r)
        while (regimeHistory.size > keep) regimeHistory.removeFirst()
    }

    /** Reset intraday memory when a new session day begins. */
    fun rollDay(now: Long) {
        val day = Session.zdt(now).toLocalDate().toEpochDay()
        if (day != sessionDay) {
            sessionDay = day
            ticks.clear(); futures.clear(); openingChains.clear(); regimeHistory.clear()
        }
    }
}
