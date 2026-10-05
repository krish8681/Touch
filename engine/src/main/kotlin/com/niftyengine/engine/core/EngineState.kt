package com.niftyengine.engine.core

import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.Regime

/**
 * Mutable memory carried between engine cycles: tick histories, the previous option chain,
 * futures OI history, driver score history (for persistence) and regime history (for transitions).
 */
class EngineState(private val maxTicks: Int = 2000) {
    data class Tick(val t: Long, val price: Double, val volume: Double = 0.0)
    data class FutTick(val t: Long, val price: Double, val oi: Double, val volume: Double, val spot: Double)

    val ticks = HashMap<String, ArrayDeque<Tick>>()
    val futures = ArrayDeque<FutTick>()
    var previousChain: OptionChain? = null
    var previousChainTime: Long = 0L
    /** Chain as of session open (or first seen today) for intraday OI migration. */
    var openingChain: OptionChain? = null
    val driverHistory = HashMap<Driver, ArrayDeque<Double>>()
    val regimeHistory = ArrayDeque<Pair<Long, Regime>>()
    val directionHistory = ArrayDeque<Pair<Long, Double>>()
    var sessionDay: Long = -1

    fun record(symbol: String, t: Long, price: Double, volume: Double = 0.0) {
        if (price.isNaN() || price <= 0) return
        val q = ticks.getOrPut(symbol) { ArrayDeque() }
        if (q.isNotEmpty() && q.last().t >= t) return
        q.addLast(Tick(t, price, volume))
        while (q.size > maxTicks) q.removeFirst()
    }

    fun history(symbol: String): List<Tick> = ticks[symbol]?.toList() ?: emptyList()

    fun pushDriver(d: Driver, score: Double, keep: Int = 12) {
        val q = driverHistory.getOrPut(d) { ArrayDeque() }
        q.addLast(score)
        while (q.size > keep) q.removeFirst()
    }

    fun pushRegime(t: Long, r: Regime, keep: Int = 30) {
        regimeHistory.addLast(t to r)
        while (regimeHistory.size > keep) regimeHistory.removeFirst()
    }

    fun pushDirection(t: Long, d: Double, keep: Int = 60) {
        directionHistory.addLast(t to d)
        while (directionHistory.size > keep) directionHistory.removeFirst()
    }

    /** Reset intraday memory when a new session day begins. */
    fun rollDay(now: Long) {
        val day = Session.zdt(now).toLocalDate().toEpochDay()
        if (day != sessionDay) {
            sessionDay = day
            ticks.clear(); futures.clear()
            previousChain = null; openingChain = null
            driverHistory.clear(); regimeHistory.clear(); directionHistory.clear()
        }
    }
}
