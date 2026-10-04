package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.GapState
import com.niftyengine.engine.model.GiftNiftyReport
import com.niftyengine.engine.model.MarketSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * 21 — GIFT Nifty / Opening Engine.
 *
 * GIFT Nifty (NSE IX) trades while NSE is shut, so it is the market's live estimate of where NIFTY opens.
 * It is used as an OPENING factor, not as an all-day predictor:
 *
 *  • Before 09:15: implied gap = GIFT last vs the NSE near-month futures close (same contract ⇒ no basis error),
 *    implied open = NIFTY previous close × (1 + gap). This is the factor's directional signal.
 *  • 09:15 → ~10:15: the gap is now in the price, so the signal becomes gap behaviour — extending / holding /
 *    fading / filled — and its confidence decays to zero by the end of the first hour.
 *  • After that the factor is spent (confidence 0): intraday direction comes from the other engines.
 *
 * It also feeds the event engine's pricing-in before the open (overnight news reaction = GIFT move) and
 * cross-checks the actual open against the implied open.
 */
class GiftNiftyEngine {
    /** Pre-open implied gap frozen for the day (GIFT keeps trading during the session, so a live recompute would show the day's move). */
    private var frozenDay = -1L
    private var frozenGapPct = Double.NaN
    private var frozenMethod = ""
    private var frozenAt = 0L

    data class Result(val signal: EngineSignal, val report: GiftNiftyReport?, val impliedOpenForPricing: Double, val warnings: List<String>)

    private val dateFmts = listOf("dd-MMM-yyyy", "yyyy-MM-dd").map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    private fun date(s: String): LocalDate? = dateFmts.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(s.trim(), f) }.getOrNull() }

    companion object {
        /** Minutes after 09:15 during which the opening factor still carries weight. */
        const val OPENING_WINDOW_MIN = 60.0
        /** Gaps smaller than this (%) are noise. */
        const val MIN_GAP_PCT = 0.15

        /**
         * The close the NEXT/CURRENT open gaps from. Feeds disagree on what "previousClose" means before 09:15
         * (it can still be the day-before's close), so decide from the quote's own timestamp:
         * quote from an earlier day ⇒ `last` IS the latest close; after 15:30 today ⇒ `last` (today's close);
         * otherwise (pre-open auction / in session) ⇒ `prevClose`.
         */
        fun closeReference(last: Double, prevClose: Double, asOf: Long, wall: Long): Double {
            val z = Session.zdt(wall)
            val midnight = z.toLocalDate().atStartOfDay(Session.IST).toInstant().toEpochMilli()
            val todayClose = Session.closeOf(z.toLocalDate())
            val useLast = when {
                asOf in 1 until midnight -> true
                wall >= todayClose -> true
                asOf > 0 -> false
                else -> !Session.isOpen(wall)
            }
            return if (useLast) last.takeIf { it > 0 } ?: prevClose else prevClose.takeIf { it > 0 } ?: last
        }
    }

    fun analyze(s: MarketSnapshot, wall: Long): Result {
        val g = s.giftNifty ?: return Result(EngineSignal.unavailable("GIFT Nifty", "GIFT Nifty quote unavailable"), null, Double.NaN, emptyList())
        if (g.last <= 0 || g.last.isNaN()) return Result(EngineSignal.unavailable("GIFT Nifty", "invalid GIFT price"), null, Double.NaN, emptyList())
        val n = s.nifty
        val sessionStart = Session.sessionStart(wall)
        val open = Session.isOpen(wall)
        val preOpen = !open && wall < sessionStart && Session.zdt(wall).dayOfWeek.value <= 5
        val age = if (g.asOf > 0) (wall - g.asOf) / 60_000.0 else Double.NaN

        // Implied gap: compare like with like (GIFT and NSE futures are the same contract when expiries match).
        val f = s.futures
        val sameContract = f != null && date(f.expiry) != null && date(f.expiry) == date(g.expiry)
        val futRef = if (f == null) Double.NaN else closeReference(f.last, f.prevClose, f.asOf, wall)
        val (liveGap, liveMethod) = if (sameContract && futRef > 0) M.pctChange(futRef, g.last) to "GIFT vs NSE futures close (same expiry)"
        else g.changePct to "GIFT day change (expiry mismatch with NSE futures)"
        // Freeze the latest fresh pre-open reading (or a quote stamped before 09:15 seen on the first cycle).
        val dayNo = Session.zdt(wall).toLocalDate().toEpochDay()
        if (dayNo != frozenDay) { frozenDay = dayNo; frozenGapPct = Double.NaN; frozenMethod = ""; frozenAt = 0L }
        val quoteBeforeOpen = g.asOf in 1 until sessionStart && sessionStart - g.asOf < 6 * 3_600_000L
        if ((preOpen && !age.isNaN() && age <= 60) || (open && quoteBeforeOpen && g.asOf > frozenAt)) {
            frozenGapPct = liveGap; frozenMethod = liveMethod; frozenAt = g.asOf
        }
        val (gapPct, method) = when {
            !open -> liveGap to liveMethod
            !frozenGapPct.isNaN() -> frozenGapPct to "$frozenMethod · frozen at ${Session.hhmm(frozenAt)}"
            else -> Double.NaN to "not captured before 09:15 (app started after the open)"
        }
        val refClose = closeReference(n.last, n.prevClose, n.asOf, wall)
        val impliedOpen = if (gapPct.isNaN()) Double.NaN else refClose * (1 + gapPct / 100)

        val notes = ArrayList<String>()
        val warnings = ArrayList<String>()
        val stale = age.isNaN() || (preOpen && age > 60) || age > 18 * 60
        if (stale && preOpen) notes += "GIFT quote is %s old — not used as a live signal".format(if (age.isNaN()) "of unknown age" else "%.0f min".format(age))

        val minutesIn = (wall - sessionStart) / 60_000.0
        val actualGap = if (open && !n.open.isNaN() && n.open > 0 && refClose > 0) M.pctChange(refClose, n.open) else Double.NaN
        var state = GapState.PRE_OPEN
        var score = 0.0
        var conf = 0.0
        var realization = Double.NaN
        var retr = Double.NaN
        when {
            preOpen -> {
                state = if (abs(gapPct) < MIN_GAP_PCT) GapState.NO_GAP else GapState.PRE_OPEN
                score = M.squash(gapPct, 0.6)
                conf = if (stale) 0.0 else 0.8
            }
            open && !actualGap.isNaN() && minutesIn <= OPENING_WINDOW_MIN -> {
                realization = if (!gapPct.isNaN() && abs(gapPct) >= MIN_GAP_PCT) actualGap / gapPct else Double.NaN
                if (!gapPct.isNaN() && abs(gapPct) >= MIN_GAP_PCT && abs(actualGap - gapPct) > 0.75)
                    warnings += "NIFTY opened %+.2f%% vs GIFT-implied %+.2f%% — check feeds/news".format(actualGap, gapPct)
                if (abs(actualGap) < MIN_GAP_PCT) {
                    state = GapState.NO_GAP
                } else {
                    val sinceOpen = M.pctChange(n.open, n.last)
                    retr = -sinceOpen / actualGap // share of the gap given back
                    state = when {
                        retr >= 1.0 -> GapState.FILLED
                        retr >= 0.4 -> GapState.FADING
                        retr <= -0.25 -> GapState.EXTENDING
                        else -> GapState.HOLDING
                    }
                    // Direction of the opening factor: the gap's sign, scaled by how well it is holding.
                    val hold = M.clamp(1 - retr * 1.5, -1.0, 1.0)
                    score = M.squash(actualGap, 0.6) * hold
                }
                conf = 0.75 * (1 - minutesIn / OPENING_WINDOW_MIN).coerceIn(0.0, 1.0)
            }
            open -> { state = GapState.SPENT; conf = 0.0 }
            else -> { state = GapState.SPENT; conf = 0.0; notes += "Market closed; GIFT informs the next open" }
        }
        if (open && gapPct.isNaN()) notes += "Implied gap unknown: GIFT was not read before 09:15 today"
        if (!sameContract) notes += "Expiry mismatch: using GIFT's own day change (%.2f%%)".format(g.changePct)

        val report = GiftNiftyReport(
            last = g.last, changePct = g.changePct, asOf = g.asOf, ageMinutes = age,
            impliedGapPct = gapPct, impliedOpen = impliedOpen, method = method, state = state,
            actualGapPct = actualGap, gapRealization = realization, retracement = retr, notes = notes + warnings,
        )
        val tags = buildList {
            add("GAP_${state.name}")
            if (!gapPct.isNaN() && abs(gapPct) >= 1.0) add("LARGE_IMPLIED_GAP")
            if (warnings.isNotEmpty()) add("OPEN_VS_GIFT_MISMATCH")
        }
        val signal = EngineSignal("GIFT Nifty", M.clamp(score), conf, tags, listOfNotNull(
            Detail("GIFT Nifty", "%.1f (%+.2f%%)".format(g.last, g.changePct)),
            Detail("Implied gap / open", if (gapPct.isNaN()) "–" else "%+.2f%% → %,.0f".format(gapPct, impliedOpen)),
            Detail("Method", method),
            Detail("Quote age", if (age.isNaN()) "unknown" else "%.0f min".format(age)),
            if (!actualGap.isNaN()) Detail("Actual gap", "%+.2f%% (realised %s of implied)".format(actualGap,
                if (realization.isNaN()) "–" else "%.0f%%".format(realization * 100))) else null,
            if (!retr.isNaN()) Detail("Gap given back", "%.0f%%".format(retr * 100)) else null,
            Detail("State", state.label),
        ))
        // For pricing-in before the open: NIFTY isn't trading, so GIFT's implied open is the market's reaction.
        val pricingOpen = if (preOpen && !stale) impliedOpen else Double.NaN
        return Result(signal, report, pricingOpen, warnings)
    }
}
