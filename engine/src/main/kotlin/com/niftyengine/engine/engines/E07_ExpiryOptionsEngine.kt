package com.niftyengine.engine.engines

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.ExpiryIntel
import com.niftyengine.engine.model.ExpiryKind
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.OiLevel
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.PinZone
import com.niftyengine.engine.model.StrikeOi
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 07 — Options / Expiry Intelligence Engine (§9, §11, §17), run separately for the next weekly and the next monthly chain.
 *
 *  • OI, ΔOI, call/put writing and unwinding near ATM, OI migration, PCR (one feature among several)
 *  • ATM IV, ~2 % OTM call/put IV, skew, IV change since the first chain of the day
 *  • Support: put OI + put OI addition − put unwinding + price reaction at the strike + futures positioning
 *    Resistance: the same with calls
 *  • Pin zone: OI and gamma concentration near spot, stronger close to expiry, weaker when IV rises
 *  • Expected move to expiry from ATM IV and from the ATM straddle
 *
 * Breakout probability and the expiry regime need the horizon distribution and are added later by [ExpiryRegimeEngine].
 */
class ExpiryOptionsEngine(private val state: EngineState) {
    data class Result(
        val intel: ExpiryIntel?,
        /** Positioning components (−1..1): writing, unwinding, migration, pcr, skew, walls. */
        val parts: Map<String, Double> = emptyMap(),
        val partText: Map<String, String> = emptyMap(),
        val asOf: Long = 0L,
    )

    data class Context(
        val spot: Double,
        val now: Long,
        val dayLow: Double,
        val dayHigh: Double,
        /** Futures positioning bias: +1 long buildup … −1 short buildup (0 if unknown). */
        val futuresBias: Double,
    )

    fun analyze(chain: OptionChain?, kind: ExpiryKind, ctx: Context): Result {
        if (chain == null || chain.rows.size < 5) return Result(null)
        val spot = if (chain.underlying > 0) chain.underlying else ctx.spot
        if (spot <= 0) return Result(null)
        val rows = chain.rows.filter { abs(it.strike - spot) / spot < 0.08 }.sortedBy { it.strike }
        if (rows.size < 5) return Result(null)
        val open = state.openingChain(chain)
        val step = rows.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: chain.strikeStep
        val tYears = Session.yearsToExpiry(ctx.now, chain.expiryMillis)
        val dte = tYears * 365
        val expDate = Session.zdt(chain.expiryMillis).toLocalDate()
        val tdte = Fresh.tradingDaysBetween(Session.zdt(ctx.now).toLocalDate(), expDate) + if (Session.isOpen(ctx.now)) 1 else 0

        fun mid(l: OptionLeg) = if (l.bid > 0 && l.ask > 0) (l.bid + l.ask) / 2 else l.ltp
        fun ivOf(l: OptionLeg, isCall: Boolean, k: Double): Double {
            if (!l.iv.isNaN() && l.iv > 0) return l.iv
            val m = mid(l)
            return if (m > 0) BlackScholes.impliedVol(isCall, spot, k, tYears, m) * 100 else Double.NaN
        }
        val atm = rows.minBy { abs(it.strike - spot) }
        val atmIv = listOf(ivOf(atm.call, true, atm.strike), ivOf(atm.put, false, atm.strike)).filter { !it.isNaN() && it > 0 }
            .let { if (it.isEmpty()) Double.NaN else it.average() }
        val otmPut = rows.minBy { abs(it.strike - spot * 0.98) }
        val otmCall = rows.minBy { abs(it.strike - spot * 1.02) }
        val putIv = ivOf(otmPut.put, false, otmPut.strike)
        val callIv = ivOf(otmCall.call, true, otmCall.strike)
        val skew = putIv - callIv
        val openAtm = open.rows.firstOrNull { it.strike == atm.strike }
        val openIv = openAtm?.let { r -> listOf(ivOf(r.call, true, r.strike), ivOf(r.put, false, r.strike)).filter { !it.isNaN() && it > 0 } }
            ?.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN
        val ivChange = if (open === chain || openIv.isNaN() || atmIv.isNaN()) 0.0 else atmIv - openIv
        val straddle = mid(atm.call) + mid(atm.put)
        val emIv = if (atmIv.isNaN()) Double.NaN else spot * atmIv / 100 * sqrt(tYears)
        val emStraddle = if (straddle > 0) straddle / 0.798 else Double.NaN
        val sigma = listOf(emIv, emStraddle).filter { !it.isNaN() && it > 0 }.let { if (it.isEmpty()) spot * 0.01 * sqrt(dte.coerceAtLeast(0.3)) else it.average() }

        // ---- OI totals and flows
        val ceOi = rows.sumOf { it.call.oi }; val peOi = rows.sumOf { it.put.oi }
        val pcr = if (ceOi > 0) peOi / ceOi else Double.NaN
        val ceAdd = rows.sumOf { it.call.changeOi }; val peAdd = rows.sumOf { it.put.changeOi }
        val pcrChange = if (ceAdd > 0 && peAdd > 0) peAdd / ceAdd else Double.NaN
        val atmIdx = rows.indexOf(atm)
        val near = rows.subList((atmIdx - 5).coerceAtLeast(0), (atmIdx + 6).coerceAtMost(rows.size))
        val callWriting = near.sumOf { it.call.changeOi.coerceAtLeast(0.0) }
        val callUnwinding = near.sumOf { (-it.call.changeOi).coerceAtLeast(0.0) }
        val putWriting = near.sumOf { it.put.changeOi.coerceAtLeast(0.0) }
        val putUnwinding = near.sumOf { (-it.put.changeOi).coerceAtLeast(0.0) }

        // ---- support / resistance (§9)
        val maxPut = rows.maxOf { it.put.oi }.coerceAtLeast(1.0)
        val maxCall = rows.maxOf { it.call.oi }.coerceAtLeast(1.0)
        val maxPutAdd = rows.maxOf { it.put.changeOi }.coerceAtLeast(1.0)
        val maxCallAdd = rows.maxOf { it.call.changeOi }.coerceAtLeast(1.0)
        fun defended(k: Double, below: Boolean): Boolean {
            if (ctx.dayLow.isNaN() || ctx.dayHigh.isNaN()) return false
            return if (below) abs(ctx.dayLow - k) / k < 0.0025 && spot > k * 1.001 else abs(ctx.dayHigh - k) / k < 0.0025 && spot < k * 0.999
        }
        fun level(r: OptionStrikeRow, put: Boolean): OiLevel {
            val leg = if (put) r.put else r.call
            val maxOi = if (put) maxPut else maxCall; val maxAdd = if (put) maxPutAdd else maxCallAdd
            val unwindShare = if (leg.oi > 0) (-leg.changeOi).coerceAtLeast(0.0) / leg.oi else 0.0
            val reaction = if (defended(r.strike, put)) 0.15 else 0.0
            // Long buildup strengthens put support; short buildup strengthens call resistance.
            val fut = 0.1 * (if (put) ctx.futuresBias else -ctx.futuresBias)
            val st = M.clamp(0.5 * leg.oi / maxOi + 0.3 * leg.changeOi.coerceAtLeast(0.0) / maxAdd - 0.3 * unwindShare + reaction + fut, 0.0, 1.0)
            val note = buildList {
                if (leg.changeOi > 0) add(if (put) "put writing" else "call writing")
                if (leg.changeOi < 0) add(if (put) "put unwinding" else "call unwinding")
                if (reaction > 0) add("defended today")
            }.joinToString()
            return OiLevel(r.strike, st, leg.oi, leg.changeOi, note)
        }
        val belowRows = rows.filter { it.strike <= spot && it.strike >= spot - 2.5 * sigma }
        val aboveRows = rows.filter { it.strike >= spot && it.strike <= spot + 2.5 * sigma }
        val support = belowRows.map { level(it, true) }.maxByOrNull { it.strength }
        val resistance = aboveRows.map { level(it, false) }.maxByOrNull { it.strength }
        val majorSupport = rows.filter { it.strike <= spot }.maxByOrNull { it.put.oi }?.let { level(it, true) }
        val majorResistance = rows.filter { it.strike >= spot }.maxByOrNull { it.call.oi }?.let { level(it, false) }

        // ---- pin zone: OI + gamma concentration near spot
        val window = rows.filter { abs(it.strike - spot) <= sigma.coerceAtLeast(2 * step) }
        val pin: PinZone? = if (window.size < 2 || atmIv.isNaN()) null else {
            val vol = atmIv / 100
            val comb = window.associateWith { it.call.oi + it.put.oi }
            val gex = window.associateWith { r -> comb.getValue(r) * BlackScholes.price(true, spot, r.strike, tYears, vol).gamma }
            val maxComb = comb.values.max().coerceAtLeast(1.0); val maxGex = gex.values.max().coerceAtLeast(1e-12)
            val best = window.maxBy { 0.5 * comb.getValue(it) / maxComb + 0.5 * gex.getValue(it) / maxGex }
            val share = comb.getValue(best) / comb.values.average().coerceAtLeast(1.0)
            val concentration = M.clamp((share - 1) / 2, 0.0, 1.0)
            val time = when { dte <= 1 -> 1.0; dte <= 2 -> 0.8; dte <= 4 -> 0.55; dte <= 7 -> 0.35; else -> 0.2 }
            val ivF = when { ivChange <= -0.5 -> 1.1; ivChange >= 1.0 -> 0.6; else -> 0.9 }
            val proximity = exp(-0.5 * ((best.strike - spot) / (0.5 * sigma)).let { it * it })
            val strength = M.clamp(concentration * time * ivF * proximity * 1.3, 0.0, 1.0)
            PinZone(best.strike, best.strike - step, best.strike + step, strength, listOf(
                "OI %,.0f (%.1f× window avg)".format(comb.getValue(best), share),
                "%.1f days to expiry".format(dte), "IV %+.1f pts since open".format(ivChange),
                "%.0f pts from spot".format(best.strike - spot),
            ))
        }
        val maxPain = rows.minBy { k ->
            rows.sumOf { r -> r.call.oi * (k.strike - r.strike).coerceAtLeast(0.0) + r.put.oi * (r.strike - k.strike).coerceAtLeast(0.0) }
        }.strike
        val nearAll = rows.filter { abs(it.strike - spot) <= 10 * step }.map { it.call.oi + it.put.oi }
        val concentration = nearAll.sortedDescending().take(3).sum() / nearAll.sum().coerceAtLeast(1.0)

        // ---- positioning direction components
        val nearCe = near.sumOf { it.call.changeOi }; val nearPe = near.sumOf { it.put.changeOi }
        val writing = (nearPe - nearCe) / (abs(nearPe) + abs(nearCe)).coerceAtLeast(1.0)
        val callUnwindAbove = near.filter { it.strike >= spot }.sumOf { (-it.call.changeOi).coerceAtLeast(0.0) }
        val putUnwindBelow = near.filter { it.strike <= spot }.sumOf { (-it.put.changeOi).coerceAtLeast(0.0) }
        val unwinding = if (callUnwindAbove + putUnwindBelow <= 0) Double.NaN else (callUnwindAbove - putUnwindBelow) / (callUnwindAbove + putUnwindBelow)
        fun com(rs: List<OptionStrikeRow>, call: Boolean, base: Boolean): Double {
            var num = 0.0; var den = 0.0
            for (r in rs) {
                val leg = if (call) r.call else r.put
                val w = if (base) (leg.oi - leg.changeOi).coerceAtLeast(0.0) else leg.oi
                num += r.strike * w; den += w
            }
            return if (den > 0) num / den else Double.NaN
        }
        val baseRows = if (open !== chain) open.rows.filter { abs(it.strike - spot) / spot < 0.08 } else null
        val ceShift = if (baseRows != null) com(rows, true, false) - com(baseRows, true, false) else com(rows, true, false) - com(rows, true, true)
        val peShift = if (baseRows != null) com(rows, false, false) - com(baseRows, false, false) else com(rows, false, false) - com(rows, false, true)
        val migration = M.squash((DataNormalizer.nz(ceShift) + DataNormalizer.nz(peShift)) / 2 / step, 1.5)
        val pcrScore = if (pcr.isNaN()) Double.NaN else M.squash(pcr - 1.0, 0.35)
        val skewScore = if (skew.isNaN()) Double.NaN else -M.squash(skew - 2.0, 3.0) // typical NIFTY put skew ≈ +2 vol pts
        val distS = support?.let { (spot - it.strike) / sigma } ?: Double.NaN
        val distR = resistance?.let { (it.strike - spot) / sigma } ?: Double.NaN
        val walls = if (distS.isNaN() || distR.isNaN()) Double.NaN else
            M.clamp((distR - distS) / 1.5) * 0.6 + 0.4 * M.clamp((support!!.strength - resistance!!.strength) * 2)
        val parts = linkedMapOf("writing" to writing, "unwinding" to unwinding, "migration" to migration,
            "pcr" to pcrScore, "skew" to skewScore, "walls" to walls)
        val partText = mapOf(
            "writing" to "PE ΔOI %+,.0f vs CE ΔOI %+,.0f".format(nearPe, nearCe),
            "unwinding" to "CE unwind↑ %,.0f vs PE unwind↓ %,.0f".format(callUnwindAbove, putUnwindBelow),
            "migration" to "CE %+.0f / PE %+.0f pts".format(DataNormalizer.nz(ceShift), DataNormalizer.nz(peShift)),
            "pcr" to "PCR %.2f".format(pcr), "skew" to "skew %+.1f".format(skew),
            "walls" to "S %s / R %s".format(support?.strike?.toInt() ?: "–", resistance?.strike?.toInt() ?: "–"),
        )

        val strikes = rows.filter { abs(it.strike - atm.strike) <= 8 * step }.map { r ->
            StrikeOi(r.strike, r.call.oi, r.call.changeOi, r.put.oi, r.put.changeOi, ivOf(r.call, true, r.strike), ivOf(r.put, false, r.strike), mid(r.call), mid(r.put))
        }
        val tags = buildList {
            if (writing > 0.3) add("PUT_WRITING") else if (writing < -0.3) add("CALL_WRITING")
            if (!unwinding.isNaN() && unwinding > 0.4) add("CALL_UNWINDING") else if (!unwinding.isNaN() && unwinding < -0.4) add("PUT_UNWINDING")
            if (migration > 0.3) add("OI_MIGRATING_UP") else if (migration < -0.3) add("OI_MIGRATING_DOWN")
            if ((pin?.strength ?: 0.0) > 0.4) add("PIN_RISK")
            if (ivChange > 1.0) add("IV_RISING") else if (ivChange < -0.7) add("IV_FALLING")
            if (skew > 4) add("PUT_SKEW_ELEVATED")
        }
        val dirComposite = Composite.of(parts.map { (k, v) -> Part(k, v, 1.0) })
        val sig = EngineSignal("Options · ${kind.label.lowercase()} ${chain.expiry}", (dirComposite?.direction ?: 0.0) / 100, 0.85, tags, listOf(
            Detail("Days to expiry", "%.2f (%d sessions)".format(dte, tdte)),
            Detail("ATM IV / skew / ΔIV", "%.1f%% / %+.1f / %+.1f".format(atmIv, skew, ivChange)),
            Detail("Expected move (IV / straddle)", "±%.0f / ±%.0f pts".format(emIv, emStraddle)),
            Detail("PCR (OI / ΔOI)", "%.2f / %s".format(pcr, if (pcrChange.isNaN()) "–" else "%.2f".format(pcrChange))),
            Detail("Call writing / unwinding", "%,.0f / %,.0f".format(callWriting, callUnwinding)),
            Detail("Put writing / unwinding", "%,.0f / %,.0f".format(putWriting, putUnwinding)),
            Detail("Support / resistance", "%s / %s".format(support?.let { "%.0f (%.2f)".format(it.strike, it.strength) } ?: "–",
                resistance?.let { "%.0f (%.2f)".format(it.strike, it.strength) } ?: "–")),
            Detail("Pin zone", pin?.let { "%.0f (strength %.2f)".format(it.strike, it.strength) } ?: "–"),
            Detail("Max pain / OI concentration", "%.0f / %.0f%%".format(maxPain, concentration * 100)),
        ) + parts.map { (k, v) -> Detail("· $k", "${partText[k]} → ${if (v.isNaN()) "–" else "%+.2f".format(v)}") })

        val intel = ExpiryIntel(
            kind = kind, expiry = chain.expiry, expiryMillis = chain.expiryMillis, daysToExpiry = dte, tradingDaysToExpiry = tdte,
            spot = spot, atmStrike = atm.strike, atmIv = atmIv, callIv = callIv, putIv = putIv, skew = skew, ivChange = ivChange,
            straddle = straddle, expectedMoveIv = emIv, expectedMoveStraddle = emStraddle, pcr = pcr, pcrChange = pcrChange,
            totalCallOi = ceOi, totalPutOi = peOi, callWriting = callWriting, callUnwinding = callUnwinding,
            putWriting = putWriting, putUnwinding = putUnwinding, support = support, majorSupport = majorSupport,
            resistance = resistance, majorResistance = majorResistance, pin = pin, maxPain = maxPain, oiConcentration = concentration,
            strikes = strikes, signal = sig,
            notes = buildList { if (dte < 0.3) add("Expiry day: theta and gamma are extreme") },
        )
        return Result(intel, parts, partText, chain.asOf)
    }

    /** Options-positioning factor for horizon [h] (H1 horizons lean on fresh ΔOI flows, H2/H3 on the OI structure). */
    fun reading(r: Result, h: HorizonId, ref: Long, reliability: Double, source: String): FactorReading {
        val intel = r.intel ?: return FactorReading.missing(Factor.OPTIONS, "option chain unavailable")
        val w = when (h.group) {
            com.niftyengine.engine.model.HorizonGroup.H1 -> mapOf("writing" to 0.30, "unwinding" to 0.15, "migration" to 0.25, "pcr" to 0.10, "skew" to 0.10, "walls" to 0.10)
            com.niftyengine.engine.model.HorizonGroup.H2 -> mapOf("writing" to 0.20, "unwinding" to 0.15, "migration" to 0.15, "pcr" to 0.20, "skew" to 0.10, "walls" to 0.20)
            com.niftyengine.engine.model.HorizonGroup.H3 -> mapOf("writing" to 0.20, "unwinding" to 0.10, "migration" to 0.10, "pcr" to 0.30, "skew" to 0.15, "walls" to 0.15)
        }
        val age = if (r.asOf > 0) ((ref - r.asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        return Composite.reading(Factor.OPTIONS, r.parts.map { (k, v) -> Part(k, v, w[k] ?: 0.0, r.partText[k] ?: "") },
            Fresh.of(age, Fresh.Cadence.LIVE, h), reliability, r.asOf, age, source,
            summary = { "${intel.kind.label} ${intel.expiry}: S ${intel.support?.strike?.toInt() ?: "–"} / R ${intel.resistance?.strike?.toInt() ?: "–"} · PCR %.2f".format(intel.pcr) })
    }
}
