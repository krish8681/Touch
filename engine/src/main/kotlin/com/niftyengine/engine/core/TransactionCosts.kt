package com.niftyengine.engine.core

import kotlinx.serialization.Serializable

/**
 * Round-trip cost of a long index-option trade (buy, then sell), Zerodha-style F&O charges.
 * Defaults are editable in Setup because statutory rates change (check your broker's charge sheet).
 */
@Serializable
data class TransactionCosts(
    val brokeragePerOrder: Double = 20.0,
    /** STT on the sell side, % of premium turnover. */
    val sttSellPct: Double = 0.1,
    /** NSE transaction charge, % of premium turnover, both sides. */
    val exchangePct: Double = 0.03503,
    val sebiPerCrore: Double = 10.0,
    val gstPct: Double = 18.0,
    /** Stamp duty on the buy side, % of premium turnover. */
    val stampBuyPct: Double = 0.003,
    /** Extra slippage beyond the quoted bid/ask, in ticks per side. */
    val slippageTicks: Double = 1.0,
    val tickSize: Double = 0.05,
    val lotSize: Int = 65,
    val lots: Int = 1,
) {
    data class Breakdown(val brokerage: Double, val stt: Double, val exchange: Double, val sebi: Double, val gst: Double,
                         val stamp: Double, val slippage: Double) {
        val total get() = brokerage + stt + exchange + sebi + gst + stamp + slippage
    }

    /** Charges in rupees for buying at [buyPx] and selling at [sellPx] (per unit prices). */
    fun roundTrip(buyPx: Double, sellPx: Double): Breakdown {
        val qty = (lotSize * lots).toDouble().coerceAtLeast(1.0)
        val buyT = buyPx * qty; val sellT = sellPx.coerceAtLeast(0.0) * qty
        val brokerage = 2 * brokeragePerOrder
        val exchange = (buyT + sellT) * exchangePct / 100
        val sebi = (buyT + sellT) * sebiPerCrore / 1e7
        return Breakdown(
            brokerage = brokerage,
            stt = sellT * sttSellPct / 100,
            exchange = exchange,
            sebi = sebi,
            gst = (brokerage + exchange + sebi) * gstPct / 100,
            stamp = buyT * stampBuyPct / 100,
            slippage = 2 * slippageTicks * tickSize * qty,
        )
    }

    /** Same, expressed in premium points per unit (directly comparable with option P&L per unit). */
    fun perUnit(buyPx: Double, sellPx: Double): Double = roundTrip(buyPx, sellPx).total / (lotSize * lots).coerceAtLeast(1)
}
