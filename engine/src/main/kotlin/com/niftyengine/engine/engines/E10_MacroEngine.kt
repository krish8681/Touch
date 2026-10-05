package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.ExpectationChannel
import com.niftyengine.engine.model.ExpectationReport
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot

/**
 * 10 — India macro engine: USD/INR, crude, RBI / liquidity / rate expectations, growth, inflation, fiscal policy.
 *
 * Fast variables (USD/INR, crude) are read from live quotes on every horizon (1-day + live drift for H1, 5-session for
 * H2, 20-session for H3). Slow variables (repo, CPI, GDP, PMI, credit, liquidity — entered in Setup with release dates)
 * only feed H2/H3, and each one's surprise vs consensus arrives through the [ExpectationEngine] channel of the factor.
 * A slow factor with no inputs is reported missing (its weight is redistributed) rather than counted as neutral.
 */
class MacroEngine(private val norm: DataNormalizer) {
    data class Result(
        val signal: EngineSignal,
        val readings: Map<Pair<Factor, HorizonId>, FactorReading>,
        val usdinr1d: Double, val usdinr5d: Double, val crude1d: Double, val crude5d: Double,
        val liquidityCr: Double,
    ) {
        fun get(f: Factor, h: HorizonId) = readings[f to h] ?: FactorReading.missing(f, "not used for ${h.short}")
    }

    private fun asset(s: MarketSnapshot, a: GlobalAsset): InstrumentData? = s.global[a]?.takeIf { it.last > 0 && (it.prevClose > 0 || it.daily.isNotEmpty()) }

    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long, ref: Long, exp: ExpectationReport, reliability: Double): Result {
        val out = HashMap<Pair<Factor, HorizonId>, FactorReading>()
        val inr = asset(s, GlobalAsset.USDINR)
        val crude = asset(s, GlobalAsset.BRENT) ?: asset(s, GlobalAsset.WTI)
        val m = s.macro
        fun ch(c: ExpectationChannel, g: HorizonGroup) = exp.channels[c]?.get(g)?.takeIf { kotlin.math.abs(it) > 1e-6 } ?: Double.NaN

        // ---------------- fast: USD/INR and crude, every horizon
        fun fast(f: Factor, d: InstrumentData?, typ1d: Double, h: HorizonId, chan: ExpectationChannel): FactorReading {
            if (d == null) return FactorReading.missing(f, "${f.label} quote unavailable")
            val feat = norm.features(d, now, sessionStart, typ1d)
            val c1d = feat.c1d; val c1h = feat.c1h
            val c5 = DataNormalizer.sessionsChange(d, 5); val c20 = DataNormalizer.sessionsChange(d, 20)
            val parts = when (h.group) {
                HorizonGroup.H1 -> listOf(
                    Part("1-day", -M.squash(c1d / typ1d, 1.2), if (h == HorizonId.M30) 0.4 else 0.6, "%+.2f%%".format(c1d)),
                    Part("Last hour", if (c1h.isNaN()) Double.NaN else -M.squash(c1h / (typ1d * 0.45), 1.2), if (h == HorizonId.M30) 0.6 else 0.4, "%+.2f%%".format(c1h)),
                    Part("Surprise channel", ch(chan, HorizonGroup.H1), 0.15),
                )
                HorizonGroup.H2 -> listOf(
                    Part("5-session", if (c5.isNaN()) Double.NaN else -M.squash(c5 / (typ1d * 2.2), 1.2), 0.7, "%+.2f%%".format(c5)),
                    Part("1-day", -M.squash(c1d / typ1d, 1.2), 0.3, "%+.2f%%".format(c1d)),
                    Part("Surprise channel", ch(chan, HorizonGroup.H2), 0.2),
                )
                HorizonGroup.H3 -> listOf(
                    Part("20-session", if (c20.isNaN()) Double.NaN else -M.squash(c20 / (typ1d * 4.5), 1.2), 0.7, "%+.2f%%".format(c20)),
                    Part("5-session", if (c5.isNaN()) Double.NaN else -M.squash(c5 / (typ1d * 2.2), 1.2), 0.3, "%+.2f%%".format(c5)),
                    Part("Surprise channel", ch(chan, HorizonGroup.H3), 0.2),
                )
            }
            val age = if (d.asOf > 0) ((ref - d.asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
            val fresh = Fresh.of(age, Fresh.Cadence.GLOBAL, h).let { if (h.intraday) it else maxOf(it, 0.5) }
            return Composite.reading(f, parts, fresh, reliability * 0.9, d.asOf, age, "Yahoo",
                summary = { "%.2f (%+.2f%% 1d, %s 5d)".format(d.last, c1d, if (c5.isNaN()) "–" else "%+.1f%%".format(c5)) })
        }
        for (h in HorizonId.values()) {
            val fx = fast(Factor.USDINR, inr, GlobalAsset.USDINR.typicalDailyMovePct, h, ExpectationChannel.CURRENCY)
            val oil = fast(Factor.CRUDE, crude, GlobalAsset.BRENT.typicalDailyMovePct, h, ExpectationChannel.CRUDE)
            out[Factor.USDINR to h] = fx
            out[Factor.CRUDE to h] = oil
            out[Factor.USDINR_CRUDE to h] = Composite.blend(Factor.USDINR_CRUDE, fx, 0.5, oil, 0.5, "INR %+.0f · crude %+.0f".format(fx.direction, oil.direction))
                .let { if (!fx.available && !oil.available) FactorReading.missing(Factor.USDINR_CRUDE, "USD/INR and crude unavailable") else it }
        }

        // ---------------- slow: manual macro + expectation channels (H2, H3)
        fun dated(field: String): Pair<Long, Double> {
            val at = m.releasedAt[field] ?: 0L
            return at to if (at > 0) ((ref - at) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        }
        fun slowReading(f: Factor, h: HorizonId, parts: List<Part>, fields: List<String>, cadence: Fresh.Cadence, why: String, summary: (Composite.Result) -> String): FactorReading {
            val manualOk = parts.any { !it.score.isNaN() && !it.name.startsWith("Surprise") && !it.name.startsWith("News") }
            val ages = fields.filter { field -> m.releasedAt.containsKey(field) }.map { dated(it) }
            val fresh = if (!manualOk) 1.0 else if (ages.isEmpty()) Fresh.of(Double.NaN, cadence, h) else ages.maxOf { Fresh.of(it.second, cadence, h) }
            val rel = reliability * (if (!manualOk) 0.6 else if (ages.isEmpty()) 0.55 else 0.75)
            val asOf = ages.maxOfOrNull { it.first } ?: 0L
            return Composite.reading(f, parts, fresh, rel, asOf, ages.minOfOrNull { it.second } ?: Double.NaN, "manual + expectations",
                summary = summary, missingWhy = why)
        }
        fun nn(x: Double) = !x.isNaN()
        val liq = m.liquidityCr
        for (h in listOf(HorizonId.WEEKLY, HorizonId.MONTHLY)) {
            val g = h.group
            // RBI / liquidity / rate expectations
            val policy = if (nn(m.lastPolicyChangeBps) && (m.releasedAt.containsKey("lastPolicyChangeBps") || m.releasedAt.containsKey("repoRate") || nn(m.repoRate)))
                -M.squash(m.lastPolicyChangeBps / 25.0, 1.0) else Double.NaN
            out[Factor.RBI_RATES to h] = slowReading(Factor.RBI_RATES, h, listOf(
                Part("Surprise channel (RBI / rates)", ch(ExpectationChannel.RBI_RATES, g), if (g == HorizonGroup.H2) 0.35 else 0.25),
                Part("Last policy action", policy, if (g == HorizonGroup.H2) 0.25 else 0.3, if (nn(m.lastPolicyChangeBps)) "%+.0f bps, repo %.2f%%".format(m.lastPolicyChangeBps, m.repoRate) else ""),
                Part("Liquidity", if (nn(liq)) M.squash(liq / 100_000.0, 1.0) else Double.NaN, if (g == HorizonGroup.H2) 0.25 else 0.25, if (nn(liq)) "₹%,.0f cr".format(liq) else ""),
                Part("Credit growth", if (nn(m.creditGrowth)) M.squash((m.creditGrowth - 12) / 4, 1.0) else Double.NaN, if (g == HorizonGroup.H2) 0.15 else 0.2,
                    if (nn(m.creditGrowth)) "%.1f%%".format(m.creditGrowth) else ""),
            ), listOf("repoRate", "lastPolicyChangeBps", "liquidityCr", "creditGrowth"), Fresh.Cadence.MONTHLY, "no RBI / liquidity inputs (Setup)") { r ->
                when { r.direction > 15 -> "supportive"; r.direction < -15 -> "restrictive"; else -> "neutral" } + if (nn(m.repoRate)) " · repo %.2f%%".format(m.repoRate) else ""
            }
        }
        // Growth (H3) and Indian macro composite (H2)
        val gdpTrend = if (nn(m.gdpGrowth) && nn(m.gdpPrevGrowth)) M.squash((m.gdpGrowth - m.gdpPrevGrowth) / 0.8, 1.0) else Double.NaN
        val pmi = if (nn(m.pmiManufacturing)) M.squash((m.pmiManufacturing - 54) / 3, 1.0) else Double.NaN
        val iip = if (nn(m.iipYoY)) M.squash((m.iipYoY - 4) / 3, 1.0) else Double.NaN
        val gdpLevel = if (nn(m.gdpGrowth)) M.squash((m.gdpGrowth - 6.5) / 1.0, 1.0) else Double.NaN
        out[Factor.INDIA_GROWTH to HorizonId.MONTHLY] = slowReading(Factor.INDIA_GROWTH, HorizonId.MONTHLY, listOf(
            Part("Surprise channel (growth)", ch(ExpectationChannel.GROWTH, HorizonGroup.H3), 0.25),
            Part("GDP momentum", gdpTrend, 0.2, if (nn(m.gdpGrowth)) "%.1f%% vs %.1f%%".format(m.gdpGrowth, m.gdpPrevGrowth) else ""),
            Part("GDP level", gdpLevel, 0.1),
            Part("PMI manufacturing", pmi, 0.25, if (nn(m.pmiManufacturing)) "%.1f".format(m.pmiManufacturing) else ""),
            Part("IIP", iip, 0.2, if (nn(m.iipYoY)) "%.1f%%".format(m.iipYoY) else ""),
        ), listOf("gdpGrowth", "pmiManufacturing", "iipYoY"), Fresh.Cadence.QUARTERLY, "no growth inputs (Setup)") { r ->
            if (r.direction > 15) "improving" else if (r.direction < -15) "slowing" else "steady"
        }
        val cpiTrend = if (nn(m.cpiYoY) && nn(m.cpiPrevYoY)) -M.squash((m.cpiYoY - m.cpiPrevYoY) / 0.5, 1.0) else Double.NaN
        val cpiTarget = if (nn(m.cpiYoY)) -M.squash((m.cpiYoY - 4.0) / 2.0, 1.0) else Double.NaN
        val wpi = if (nn(m.wpiYoY)) -M.squash((m.wpiYoY - 2.0) / 3.0, 1.0) else Double.NaN
        out[Factor.INFLATION to HorizonId.MONTHLY] = slowReading(Factor.INFLATION, HorizonId.MONTHLY, listOf(
            Part("Surprise channel (inflation)", ch(ExpectationChannel.INFLATION, HorizonGroup.H3), 0.3),
            Part("CPI trend", cpiTrend, 0.3, if (nn(m.cpiYoY)) "%.2f%% vs %.2f%%".format(m.cpiYoY, m.cpiPrevYoY) else ""),
            Part("CPI vs 4% target", cpiTarget, 0.25),
            Part("WPI", wpi, 0.15, if (nn(m.wpiYoY)) "%.1f%%".format(m.wpiYoY) else ""),
        ), listOf("cpiYoY", "wpiYoY"), Fresh.Cadence.MONTHLY, "no inflation inputs (Setup)") { r ->
            if (r.direction > 15) "easing (supportive)" else if (r.direction < -15) "rising (headwind)" else "stable"
        }
        out[Factor.INDIA_MACRO to HorizonId.WEEKLY] = slowReading(Factor.INDIA_MACRO, HorizonId.WEEKLY, listOf(
            Part("Surprise channel (growth)", ch(ExpectationChannel.GROWTH, HorizonGroup.H2), 0.2),
            Part("Surprise channel (inflation)", ch(ExpectationChannel.INFLATION, HorizonGroup.H2), 0.2),
            Part("GDP momentum", gdpTrend, 0.15), Part("PMI", pmi, 0.15), Part("CPI trend", cpiTrend, 0.15), Part("CPI vs target", cpiTarget, 0.15),
        ), listOf("gdpGrowth", "pmiManufacturing", "cpiYoY"), Fresh.Cadence.MONTHLY, "no Indian macro inputs (Setup)") { r ->
            if (r.direction > 15) "supportive" else if (r.direction < -15) "headwind" else "neutral"
        }
        // Fiscal / government policy (H3)
        out[Factor.FISCAL to HorizonId.MONTHLY] = slowReading(Factor.FISCAL, HorizonId.MONTHLY, listOf(
            Part("News (government / budget)", ch(ExpectationChannel.FISCAL, HorizonGroup.H3), 0.5),
            Part("Manual fiscal stance", if (nn(m.fiscalStance)) M.clamp(m.fiscalStance) else Double.NaN, 0.5, if (nn(m.fiscalStance)) "%+.2f".format(m.fiscalStance) else ""),
        ), listOf("fiscalStance"), Fresh.Cadence.MONTHLY, "no fiscal inputs or government news") { r ->
            if (r.direction > 15) "supportive" else if (r.direction < -15) "adverse" else "neutral"
        }

        val c = inr?.changePct ?: Double.NaN; val o = crude?.changePct ?: Double.NaN
        val c5 = DataNormalizer.sessionsChange(inr, 5); val o5 = DataNormalizer.sessionsChange(crude, 5)
        val sig = EngineSignal("INR / crude / RBI / macro", out.getValue(Factor.USDINR_CRUDE to HorizonId.M60).direction / 100, 0.8, buildList {
            if (!c.isNaN() && c > 0.3) add("INR_WEAK") else if (!c.isNaN() && c < -0.3) add("INR_STRONG")
            if (!o.isNaN() && o > 2) add("CRUDE_SPIKE") else if (!o.isNaN() && o < -2) add("CRUDE_FALLING")
        }, listOf(
            Detail("USD/INR", inr?.let { "%.2f (%+.2f%% 1d, %s 5d)".format(it.last, c, if (c5.isNaN()) "–" else "%+.2f%%".format(c5)) } ?: "–"),
            Detail("Crude", crude?.let { "%.2f (%+.2f%% 1d, %s 5d)".format(it.last, o, if (o5.isNaN()) "–" else "%+.1f%%".format(o5)) } ?: "–"),
            Detail("Repo / last action", if (nn(m.repoRate)) "%.2f%% / %+.0f bps".format(m.repoRate, m.lastPolicyChangeBps) else "–"),
            Detail("Liquidity", if (nn(liq)) "₹%,.0f cr".format(liq) else "–"),
            Detail("CPI / GDP / PMI", "%s / %s / %s".format(fmt(m.cpiYoY), fmt(m.gdpGrowth), fmt(m.pmiManufacturing))),
            Detail("RBI factor (H2 / H3)", "%+.0f / %+.0f".format(out.getValue(Factor.RBI_RATES to HorizonId.WEEKLY).direction, out.getValue(Factor.RBI_RATES to HorizonId.MONTHLY).direction)),
            Detail("Growth / inflation / fiscal (H3)", "%+.0f / %+.0f / %+.0f".format(out.getValue(Factor.INDIA_GROWTH to HorizonId.MONTHLY).direction,
                out.getValue(Factor.INFLATION to HorizonId.MONTHLY).direction, out.getValue(Factor.FISCAL to HorizonId.MONTHLY).direction)),
        ))
        return Result(sig, out, c, c5, o, o5, liq)
    }

    private fun fmt(x: Double) = if (x.isNaN()) "–" else "%.1f".format(x)
}
