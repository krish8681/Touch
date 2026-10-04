package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeClass
import com.niftyengine.engine.model.RegimeResult
import kotlin.math.abs
import kotlin.math.sign

/**
 * 12 — Market Regime Engine.
 * R1 Strong bull · R2 Bull trend · R3 Bull short covering · R4 Range · R5 Bear long unwinding ·
 * R6 Bear trend · R7 Strong bear · R8 Event shock · R9 Divergence · R10 Transition.
 *
 * Markets rarely flip Bull → Bear in one step; the history of regimes is used to detect
 * momentum weakening → divergence → transition.
 */
class MarketRegimeEngine(private val state: EngineState) {
    data class Inputs(
        val preliminaryScore: Double,
        val structure: MarketStructureEngine.Result,
        val futures: FuturesPositionEngine.Result,
        val options: OptionsPositionEngine.Result,
        val vix: VIXEngine.Result,
        val breadthScore: Double,
        val derivativesScore: Double,
        val fakeBreadth: Boolean,
        /** Reason text when a fresh, unpriced, high-severity event is in play. */
        val newsShock: String? = null,
        /** A major event whose interpretation the market is contradicting. */
        val newsContradicted: Boolean = false,
    )

    fun classify(i: Inputs, now: Long): RegimeResult {
        val reasons = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val d = i.preliminaryScore
        val st = i.structure
        val adx = st.adx
        val priceDir = st.signal.score

        // R8 — event shock
        val bigMove = abs(st.c15m) > 0.6
        val shock = i.vix.state == VixState.SPIKING || i.newsShock != null || bigMove
        if (i.vix.state == VixState.SPIKING) reasons += "India VIX spiking"
        i.newsShock?.let { reasons += it }
        if (bigMove) reasons += "Abrupt %.2f%% move in 15m".format(st.c15m)

        // R9 — divergence between price and the drivers that should confirm it
        val divReasons = mutableListOf<String>()
        if (abs(priceDir) > 0.3 && abs(i.derivativesScore) > 0.3 && sign(priceDir) != sign(i.derivativesScore))
            divReasons += "Price and derivatives positioning disagree"
        if (i.fakeBreadth) divReasons += "Index move carried by heavyweights against breadth"
        if (abs(priceDir) > 0.3 && abs(i.breadthScore) > 0.3 && sign(priceDir) != sign(i.breadthScore))
            divReasons += "Price and breadth disagree"
        if (i.newsContradicted) divReasons += "Market reaction contradicts major news"

        val fs = i.futures
        val shortCovering = fs.dayState == FuturesState.SHORT_COVERING || fs.intradayState == FuturesState.SHORT_COVERING
        val longUnwinding = fs.dayState == FuturesState.LONG_UNWINDING || fs.intradayState == FuturesState.LONG_UNWINDING
        val trending = !adx.isNaN() && adx >= 25

        var candidate = when {
            d > 0.45 && trending && i.breadthScore > 0.15 && fs.dayState != FuturesState.SHORT_BUILDUP -> Regime.STRONG_BULL
            d > 0.12 && shortCovering && st.pressure > 0 -> Regime.BULL_SHORT_COVERING
            d > 0.18 -> Regime.BULL_TREND
            d < -0.45 && trending && i.breadthScore < -0.15 && fs.dayState != FuturesState.LONG_BUILDUP -> Regime.STRONG_BEAR
            d < -0.12 && longUnwinding && st.pressure < 0 -> Regime.BEAR_LONG_UNWINDING
            d < -0.18 -> Regime.BEAR_TREND
            else -> Regime.RANGE
        }

        // R10 — transition: the established trend is being contradicted by fresh momentum.
        val recent = state.regimeHistory.toList().takeLast(8).map { it.second }
        val establishedBias = recent.map { it.bias.toDouble() }.let { if (it.size < 4) 0.0 else sign(it.average()) * if (abs(it.average()) >= 0.6) 1 else 0 }
        val momentumAgainst = establishedBias != 0.0 && sign(st.pressure) == -establishedBias && abs(st.pressure) > 0.25
        val transition = establishedBias != 0.0 &&
            (candidate.bias.toDouble() * establishedBias <= 0) && momentumAgainst

        val regime = when {
            shock -> Regime.EVENT_SHOCK
            divReasons.size >= 2 || (divReasons.isNotEmpty() && abs(d) < 0.3) -> { reasons += divReasons; Regime.DIVERGENCE }
            transition -> {
                reasons += "Established ${if (establishedBias > 0) "bull" else "bear"} regime losing momentum"
                Regime.TRANSITION
            }
            else -> {
                reasons += divReasons
                candidate
            }
        }
        if (regime == candidate && regime != Regime.RANGE) {
            reasons += "Composite score %+.2f".format(d)
            if (trending) reasons += "ADX %.0f (trending)".format(adx)
            if (shortCovering && regime == Regime.BULL_SHORT_COVERING) reasons += "Futures short covering"
            if (longUnwinding && regime == Regime.BEAR_LONG_UNWINDING) reasons += "Futures long unwinding"
        }
        if (regime == Regime.RANGE) {
            reasons += "No directional dominance (score %+.2f)".format(d)
            if (!adx.isNaN() && adx < 20) reasons += "ADX %.0f (non-trending)".format(adx)
            if (i.options.rangeEvidence > 0.5) reasons += "Call/put walls bracket spot"
        }
        if (divReasons.isNotEmpty()) tags += "DIVERGENCE_SIGNALS_${divReasons.size}"
        candidate = regime
        state.pushRegime(now, candidate)

        val cls = when (regime) {
            Regime.EVENT_SHOCK -> RegimeClass.EVENT
            Regime.RANGE -> RegimeClass.RANGE
            Regime.STRONG_BULL, Regime.BULL_TREND, Regime.BULL_SHORT_COVERING,
            Regime.STRONG_BEAR, Regime.BEAR_TREND, Regime.BEAR_LONG_UNWINDING -> RegimeClass.TREND
            Regime.DIVERGENCE, Regime.TRANSITION -> RegimeClass.NORMAL
        }
        return RegimeResult(regime, cls, reasons.distinct(), tags)
    }
}
