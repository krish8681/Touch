package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorContribution
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketRegime
import kotlin.math.abs

/**
 * 13 — Three-Horizon Engine: factor weights per horizon and the horizon score (§3–§11, §27, §28, §31).
 *
 *   Effective factor score = Direction × Strength × Freshness × Reliability × Horizon weight
 *   Horizon score          = Σ effective factor scores  (−100 … +100)
 *
 * Base weights are the spec tables. The market regime adapts them within hard bounds (0.6× … 1.5× base) and the table
 * is renormalised (§28). A factor whose data is unavailable is REMOVED and the remaining weights are renormalised —
 * missing data never silently becomes "neutral" (§31); the lost share is reported as reduced coverage, which lowers
 * confidence.
 */
object HorizonEngine {
    /** Spec base weights (%), in display order. */
    val BASE: Map<HorizonId, LinkedHashMap<Factor, Double>> = mapOf(
        HorizonId.M30 to linkedMapOf(
            Factor.PRICE_STRUCTURE to 20.0, Factor.HEAVYWEIGHTS to 15.0, Factor.OPTIONS to 15.0, Factor.FUTURES to 10.0,
            Factor.VIX_IV to 10.0, Factor.GLOBAL to 10.0, Factor.FII to 8.0, Factor.NEWS to 8.0, Factor.USDINR_CRUDE to 4.0,
        ),
        HorizonId.M60 to linkedMapOf(
            Factor.PRICE_STRUCTURE to 17.0, Factor.HEAVYWEIGHTS to 15.0, Factor.OPTIONS to 15.0, Factor.FII_FUTURES to 12.0,
            Factor.GLOBAL to 12.0, Factor.VIX_IV to 10.0, Factor.NEWS to 8.0, Factor.USDINR to 6.0, Factor.CRUDE to 5.0,
        ),
        HorizonId.M180 to linkedMapOf(
            Factor.PRICE_STRUCTURE to 15.0, Factor.HEAVYWEIGHTS to 15.0, Factor.FII to 15.0, Factor.OPTIONS to 13.0,
            Factor.GLOBAL to 12.0, Factor.NEWS to 10.0, Factor.VIX_IV to 8.0, Factor.USDINR to 6.0, Factor.CRUDE to 6.0,
        ),
        // §6 lists the inputs for the close without weights; these are the engineering starting values.
        HorizonId.CLOSE to linkedMapOf(
            Factor.PRICE_STRUCTURE to 16.0, Factor.HEAVYWEIGHTS to 14.0, Factor.FII to 12.0, Factor.OPTIONS to 12.0,
            Factor.GLOBAL to 11.0, Factor.BREADTH to 10.0, Factor.NEWS to 8.0, Factor.VIX_IV to 7.0, Factor.USDINR to 5.0, Factor.CRUDE to 5.0,
        ),
        HorizonId.WEEKLY to linkedMapOf(
            Factor.FII to 18.0, Factor.OPTIONS to 18.0, Factor.GLOBAL to 12.0, Factor.EARNINGS to 12.0, Factor.RBI_RATES to 10.0,
            Factor.SECTOR_LEADERSHIP to 8.0, Factor.USDINR to 6.0, Factor.CRUDE to 6.0, Factor.INDIA_MACRO to 5.0, Factor.NEWS to 5.0,
        ),
        HorizonId.MONTHLY to linkedMapOf(
            Factor.EARNINGS to 20.0, Factor.FII to 15.0, Factor.RBI_RATES to 15.0, Factor.INDIA_GROWTH to 12.0, Factor.GLOBAL to 10.0,
            Factor.VALUATION to 10.0, Factor.USDINR_CRUDE to 7.0, Factor.INFLATION to 6.0, Factor.FISCAL to 5.0,
        ),
    )

    const val MIN_MULT = 0.6
    const val MAX_MULT = 1.5
    /** H3: the monthly options structure may move the score by at most this many points (never dominates, §11). */
    const val MONTHLY_OPTIONS_CAP = 8.0

    /** Regime multipliers (§28). Factors not listed keep ×1. */
    val REGIME_MULT: Map<MarketRegime, Map<Factor, Double>> = mapOf(
        MarketRegime.RISK_ON to mapOf(Factor.GLOBAL to 1.15, Factor.FII to 1.10, Factor.FII_FUTURES to 1.10, Factor.VIX_IV to 0.90),
        MarketRegime.RISK_OFF to mapOf(
            Factor.GLOBAL to 1.25, Factor.FII to 1.22, Factor.FII_FUTURES to 1.22, Factor.VIX_IV to 1.30, Factor.USDINR to 1.20,
            Factor.CRUDE to 1.20, Factor.USDINR_CRUDE to 1.20, Factor.EARNINGS to 0.80, Factor.VALUATION to 0.80,
        ),
        MarketRegime.DOMESTIC_BULLISH to mapOf(
            Factor.RBI_RATES to 1.25, Factor.INDIA_MACRO to 1.20, Factor.INDIA_GROWTH to 1.20, Factor.SECTOR_LEADERSHIP to 1.20,
            Factor.BREADTH to 1.20, Factor.GLOBAL to 0.80,
        ),
        MarketRegime.EARNINGS_EXPANSION to mapOf(
            Factor.EARNINGS to 1.30, Factor.SECTOR_LEADERSHIP to 1.20, Factor.HEAVYWEIGHTS to 1.10, Factor.FII to 0.67, Factor.GLOBAL to 0.85,
        ),
        MarketRegime.EVENT_SHOCK to mapOf(
            Factor.NEWS to 1.50, Factor.VIX_IV to 1.40, Factor.GLOBAL to 1.20, Factor.PRICE_STRUCTURE to 1.10,
            Factor.VALUATION to 0.70, Factor.EARNINGS to 0.80,
        ),
        MarketRegime.RANGE_COMPRESSION to mapOf(
            Factor.OPTIONS to 1.40, Factor.PRICE_STRUCTURE to 1.10, Factor.FII to 0.83, Factor.FII_FUTURES to 0.83, Factor.NEWS to 0.80, Factor.GLOBAL to 0.85,
        ),
        MarketRegime.MIXED to emptyMap(),
    )

    /** Regime-adjusted weights for [h]: multiplied, clamped to [MIN_MULT, MAX_MULT] × base, renormalised to 100. */
    fun adjustedWeights(h: HorizonId, regime: MarketRegime): LinkedHashMap<Factor, Double> {
        val base = BASE.getValue(h)
        val mult = REGIME_MULT[regime].orEmpty()
        val w = LinkedHashMap(base.mapValues { (f, b) -> b * (mult[f] ?: 1.0) })
        repeat(4) {
            val sum = w.values.sum()
            for (f in w.keys) w[f] = (w.getValue(f) * 100 / sum).coerceIn(base.getValue(f) * MIN_MULT, base.getValue(f) * MAX_MULT)
        }
        val sum = w.values.sum()
        for (f in w.keys) w[f] = w.getValue(f) * 100 / sum
        return w
    }

    fun weightChanges(regime: MarketRegime): List<Detail> {
        val mult = REGIME_MULT[regime].orEmpty()
        if (mult.isEmpty()) return listOf(Detail("Weights", "base weights (no regime adjustment)"))
        return HorizonId.values().flatMap { h ->
            val base = BASE.getValue(h); val adj = adjustedWeights(h, regime)
            base.keys.filter { abs(adj.getValue(it) - base.getValue(it)) >= 0.5 }.map { f ->
                Detail("${h.short} · ${f.label}", "%.0f%% → %.1f%%".format(base.getValue(f), adj.getValue(f)))
            }
        }
    }

    data class Scored(
        val score: Double,
        val coverage: Double,
        val contributions: List<FactorContribution>,
        val missing: List<String>,
        val overlay: Double = 0.0,
        val overlayNote: String = "",
    )

    fun score(h: HorizonId, readings: Map<Factor, FactorReading>, regime: MarketRegime, overlay: FactorReading? = null): Scored {
        val base = BASE.getValue(h)
        val adj = adjustedWeights(h, regime)
        val avail = base.keys.filter { readings[it]?.available == true }
        val availSum = avail.sumOf { adj.getValue(it) }
        val contributions = base.keys.map { f ->
            val r = readings[f] ?: FactorReading.missing(f, "no reading")
            val used = if (r.available && availSum > 0) adj.getValue(f) / availSum * 100 else 0.0
            val eff = if (r.available) M.clamp(r.direction, -100.0, 100.0) * r.strength * r.freshness * r.reliability * used / 100 else 0.0
            FactorContribution(f, base.getValue(f), adj.getValue(f), used, r, eff)
        }
        var score = contributions.sumOf { it.effective }
        var ov = 0.0; var note = ""
        if (overlay != null && overlay.available) {
            ov = M.clamp(overlay.direction * overlay.strength * overlay.freshness * overlay.reliability * 0.10, -MONTHLY_OPTIONS_CAP, MONTHLY_OPTIONS_CAP)
            note = "Monthly options structure %+.1f pts (capped at ±%.0f): %s".format(ov, MONTHLY_OPTIONS_CAP, overlay.summary)
            score += ov
        }
        val missing = contributions.filter { !it.reading.available }.map { "${it.factor.label} (${it.baseWeight.toInt()}%): ${it.reading.summary.ifBlank { "unavailable" }}" }
        return Scored(M.clamp(score, -100.0, 100.0), availSum / 100, contributions, missing, ov, note)
    }
}
