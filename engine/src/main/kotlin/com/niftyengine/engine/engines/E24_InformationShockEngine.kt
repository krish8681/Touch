package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.GapState
import com.niftyengine.engine.model.GiftNiftyReport
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.ShockKind
import com.niftyengine.engine.model.ShockLevel
import com.niftyengine.engine.model.ShockSource
import com.niftyengine.engine.model.TrackedEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * 24 — Information Shock Engine (v5).
 *
 * Compares what the market expected with what actually happened — on events (expected 25 bp cut → no cut) and
 * on market data measured in units of normal (Nasdaq +1 % when ±0.25 % is normal = 4× normal; VIX jump; NIFTY
 * 15-min move; opening gap; a sudden repricing of the expected future). The shock score feeds straight into the
 * scenario engine (wider, skewed tails), the expected move, the regime (EVENT_DRIVEN / VOLATILITY_EXPANSION) and
 * the risk gate — so the system re-plans immediately instead of waiting for indicators to catch up.
 */
class InformationShockEngine {
    companion object {
        /** Typical |15-minute| relative change of NIFTY ATM IV, %. */
        const val IV_15M_NORMAL_PCT = 3.0
    }

    data class Inputs(
        val snapshot: MarketSnapshot,
        val sessionStart: Long,
        val norm: DataNormalizer,
        val events: List<TrackedEvent>,
        val rel: RelativeBaselines.Result,
        val gift: GiftNiftyReport?,
        val expectation: FutureExpectation,
        val niftyC15m: Double,
        val vixC15m: Double,
    )

    fun analyze(i: Inputs, now: Long): InformationShock {
        val src = ArrayList<ShockSource>()
        fun mag(multiple: Double, start: Double, span: Double) = M.clamp((multiple - start) / span, 0.0, 1.0)

        // 1. event surprises (fresh, unpriced new information)
        for (e in i.events) {
            val a = e.analysis ?: continue
            val ageMin = (now - e.lastInfoAt) / 60_000.0
            if (ageMin < 0 || ageMin > 90 || abs(a.direction) < 0.1) continue
            val m = M.clamp(e.surpriseMagnitude * a.severity * (0.4 + 0.6 * e.unpriced) * minOf(1.0, e.newsConfidence / 0.5), 0.0, 1.0) *
                exp(-ageMin / 60.0)
            if (m < 0.05) continue
            val dir = if (e.surprise != 0.0) sign(e.surprise) else sign(a.direction)
            val exp = a.expectedOutcome.takeIf { it.isNotBlank() }?.let { o ->
                "expected '$o'" + (e.expectations.lastOrNull { !it.probability.isNaN() }?.let { " (%.0f%%)".format(it.probability * 100) } ?: "")
            }
            val act = a.actualOutcome.takeIf { it.isNotBlank() }?.let { "actual '$it'" }
            src += ShockSource(ShockKind.EVENT_SURPRISE, e.type.label, m, Double.NaN, dir,
                listOfNotNull(e.title.take(60), exp, act, "surprise %.0f%% · unpriced %.0f%%".format(e.surpriseMagnitude * 100, e.unpriced * 100)).joinToString(" · "))
        }

        // 2. global markets: overnight move vs normal (fresh before/at the open), live hourly move vs normal
        val s = i.snapshot
        val minsSinceOpen = (now - i.sessionStart) / 60_000.0
        val overnightFresh = when {
            minsSinceOpen <= 0 -> 1.0
            minsSinceOpen >= 60 -> 0.25
            else -> 1.0 - 0.75 * minsSinceOpen / 60
        }
        val key = listOf(GlobalAsset.SP500, GlobalAsset.NASDAQ, GlobalAsset.US_VIX, GlobalAsset.US10Y, GlobalAsset.DXY,
            GlobalAsset.BRENT, GlobalAsset.USDINR, GlobalAsset.NIKKEI, GlobalAsset.HANGSENG)
        val globalSrc = ArrayList<ShockSource>()
        for (a in key) {
            val d = s.global[a] ?: continue
            if (d.prevClose <= 0) continue
            val normal = i.rel.globalNormalAbs[a] ?: (a.typicalDailyMovePct * 0.8)
            val dayMult = abs(d.changePct) / normal.coerceAtLeast(1e-6)
            val c1h = i.norm.features(d, now, i.sessionStart).c1h
            val hourMult = if (c1h.isNaN()) 0.0 else abs(c1h) / (normal * 0.4).coerceAtLeast(1e-6)
            val m = maxOf(mag(dayMult, 2.0, 3.0) * overnightFresh, mag(hourMult, 2.5, 3.0))
            if (m < 0.05) continue
            val useHour = mag(hourMult, 2.5, 3.0) > mag(dayMult, 2.0, 3.0) * overnightFresh
            val move = if (useHour) c1h else d.changePct
            globalSrc += ShockSource(ShockKind.GLOBAL, a.label, m, if (useHour) hourMult else dayMult, a.riskSign * sign(move),
                "%s %+.2f%% = %.1f× normal%s".format(a.label, move, if (useHour) hourMult else dayMult, if (useHour) " (last hour)" else ""))
        }
        // correlated assets (S&P and Nasdaq) are one shock, not two: keep the two largest, the second at half weight
        globalSrc.sortedByDescending { it.magnitude }.take(2).forEachIndexed { k, g -> src += if (k == 0) g else g.copy(magnitude = g.magnitude * 0.5) }

        // 3. volatility: India VIX 15-min jump and ATM IV since the open, vs normal
        val vixMult = i.rel.vixMove15Multiple
        if (!vixMult.isNaN()) {
            val m = mag(vixMult, 2.0, 3.0)
            if (m >= 0.05) src += ShockSource(ShockKind.VOLATILITY, "India VIX", m, vixMult, -sign(i.vixC15m),
                "India VIX %+.1f%% in 15 min = %.1f× normal".format(i.vixC15m, vixMult))
        }
        val iv15 = i.rel.ivChange15Pct
        if (!iv15.isNaN()) {
            // A sudden IV jump is new risk being priced; an IV crush (event resolved) counts at half weight.
            val mult = abs(iv15) / IV_15M_NORMAL_PCT
            val m = mag(mult, 2.0, 3.0) * if (iv15 < 0) 0.5 else 1.0
            if (m >= 0.05) src += ShockSource(ShockKind.VOLATILITY, "ATM IV", m, mult, -sign(iv15),
                "ATM IV %+.1f%% in 15 min = %.1f× normal".format(iv15, mult))
        }

        // 4. NIFTY's own 15-minute move vs its normal σ
        val pxMult = i.rel.niftyMove15Multiple
        if (!pxMult.isNaN()) {
            val m = mag(pxMult, 2.5, 3.0)
            if (m >= 0.05) src += ShockSource(ShockKind.PRICE, "NIFTY 15m", m, pxMult, sign(i.niftyC15m),
                "NIFTY %+.2f%% in 15 min = %.1f× normal".format(i.niftyC15m, pxMult))
        }

        // 5. opening gap vs the normal gap (GIFT-implied before the open, actual in the first 30 min)
        val g = i.gift
        if (g != null) {
            val gap = when {
                g.state == GapState.PRE_OPEN && g.ageMinutes <= 90 -> g.impliedGapPct
                minsSinceOpen in 0.0..30.0 && !g.actualGapPct.isNaN() -> g.actualGapPct
                else -> Double.NaN
            }
            if (!gap.isNaN()) {
                val mult = abs(gap) / i.rel.normalGapPct.coerceAtLeast(0.05)
                val m = mag(mult, 2.0, 3.0)
                if (m >= 0.05) src += ShockSource(ShockKind.GAP, "Opening gap", m, mult, sign(gap),
                    "%s gap %+.2f%% = %.1f× normal (%.2f%%)".format(if (g.state == GapState.PRE_OPEN) "GIFT-implied" else "Actual", gap, mult, i.rel.normalGapPct))
            }
        }

        // 6. sudden repricing of the expected future
        val dE = i.expectation.changeShort
        if (abs(dE) >= 0.3) src += ShockSource(ShockKind.EXPECTATION, "Expected future", M.clamp((abs(dE) - 0.25) / 0.5, 0.0, 1.0), Double.NaN,
            sign(dE), "expected-future score moved %+.2f in 10 min".format(dE))

        if (src.isEmpty()) return InformationShock()
        val score = 1 - src.fold(1.0) { acc, x -> acc * (1 - x.magnitude) }
        val dir = M.clamp(src.sumOf { it.magnitude * it.direction } / src.sumOf { it.magnitude })
        val level = when { score >= 0.6 -> ShockLevel.MAJOR; score >= 0.35 -> ShockLevel.SIGNIFICANT; score >= 0.15 -> ShockLevel.MINOR; else -> ShockLevel.NONE }
        val top = src.maxBy { it.magnitude }
        return InformationShock(score, dir, level, src.sortedByDescending { it.magnitude },
            if (level == ShockLevel.NONE) "No material information shock" else "${level.name.lowercase().replaceFirstChar { it.uppercase() }} shock · ${top.detail}")
    }
}
