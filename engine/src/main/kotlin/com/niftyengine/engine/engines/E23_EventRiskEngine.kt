package com.niftyengine.engine.engines

import com.niftyengine.engine.core.ExpiryCalendar
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.CalendarCategory
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.EventRiskReport
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.ScheduledEvent
import com.niftyengine.engine.model.TrackedEvent
import com.niftyengine.engine.model.UpcomingEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 23 — Event Risk Engine (§23). Looks 30 days ahead:
 *  • built-in: weekly and monthly NIFTY expiries, US FOMC decisions (2026 schedule), India CPI (~12th) and GDP
 *    (last working day of Feb/May/Aug/Nov) — CPI/GDP dates are estimates, flagged as such;
 *  • the user's calendar from Setup (RBI MPC, Budget, major NIFTY results, elections …);
 *  • news events whose outcome is still pending (e.g. "RBI expected to cut today").
 *
 * Each horizon window (H1 today, H2 to the weekly expiry, H3 to the monthly expiry) gets LOW / MEDIUM / HIGH / EXTREME.
 * Event risk REDUCES CONFIDENCE; it never reverses a prediction.
 */
object EventRiskEngine {
    /** FOMC decision days, 2026 (US date; the reaction reaches NSE the next IST session). Verify against federalreserve.gov. */
    val FOMC_2026 = listOf("2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09")

    fun builtIn(today: LocalDate, now: Long, days: Int = 35): List<ScheduledEvent> {
        val out = ArrayList<ScheduledEvent>()
        val end = today.plusDays(days.toLong())
        // expiries
        var w = ExpiryCalendar.nextWeekly(now)
        while (!w.isAfter(end)) {
            val monthly = ExpiryCalendar.isMonthlyExpiry(w)
            out += ScheduledEvent(w.toString(), if (monthly) "NIFTY monthly expiry" else "NIFTY weekly expiry", CalendarCategory.EXPIRY,
                if (monthly) 2 else 1, "built-in")
            w = w.plusWeeks(1)
        }
        // FOMC (impact on the next IST session)
        FOMC_2026.map { LocalDate.parse(it) }.filter { !it.isBefore(today.minusDays(1)) && !it.isAfter(end) }.forEach {
            out += ScheduledEvent(it.plusDays(1).toString(), "US FOMC decision (announced ${it})", CalendarCategory.FED, 3, "built-in (verify)")
        }
        // India CPI ~12th of each month, GDP last working day of Feb/May/Aug/Nov (estimates)
        var m = today.withDayOfMonth(1)
        while (!m.isAfter(end)) {
            val cpi = m.withDayOfMonth(12)
            if (!cpi.isBefore(today) && !cpi.isAfter(end)) out += ScheduledEvent(cpi.toString(), "India CPI inflation (estimated date)", CalendarCategory.INFLATION, 2, "built-in (estimate)")
            if (m.monthValue in listOf(2, 5, 8, 11)) {
                var g = m.withDayOfMonth(m.lengthOfMonth())
                while (g.dayOfWeek == DayOfWeek.SATURDAY || g.dayOfWeek == DayOfWeek.SUNDAY) g = g.minusDays(1)
                if (!g.isBefore(today) && !g.isAfter(end)) out += ScheduledEvent(g.toString(), "India GDP (estimated date)", CalendarCategory.GROWTH, 2, "built-in (estimate)")
            }
            m = m.plusMonths(1)
        }
        return out
    }

    private fun categoryOf(t: EventType) = when (t) {
        EventType.RBI_POLICY -> CalendarCategory.RBI; EventType.FED -> CalendarCategory.FED
        EventType.INFLATION -> CalendarCategory.INFLATION; EventType.GROWTH -> CalendarCategory.GROWTH
        EventType.EARNINGS, EventType.CORPORATE -> CalendarCategory.EARNINGS; EventType.GOVERNMENT -> CalendarCategory.GOVERNMENT
        EventType.US_DATA -> CalendarCategory.GLOBAL_DATA; EventType.GEOPOLITICS -> CalendarCategory.GEOPOLITICS
        else -> CalendarCategory.OTHER
    }

    fun analyze(
        now: Long, user: List<ScheduledEvent>, pendingNews: List<TrackedEvent>, weeklyExpiry: LocalDate, monthlyExpiry: LocalDate,
        activeShock: Boolean,
    ): EventRiskReport {
        val today = Session.zdt(now).toLocalDate()
        val news = pendingNews.map { e ->
            val sev = e.analysis?.severity ?: 0.5
            ScheduledEvent(today.toString(), "${e.stage.label}: ${e.title.take(70)}", categoryOf(e.type), if (sev >= 0.7) 3 else 2, "news")
        }
        val all = (builtIn(today, now) + user + news)
            .mapNotNull { ev -> runCatching { LocalDate.parse(ev.date.trim()) }.getOrNull()?.let { it to ev } }
            .filter { (d, _) -> !d.isBefore(today) && !d.isAfter(today.plusDays(31)) }
            .distinctBy { (d, ev) -> d.toString() + ev.title.lowercase() }
            .sortedBy { it.first }
        val windows = mapOf(HorizonGroup.H1 to today, HorizonGroup.H2 to weeklyExpiry, HorizonGroup.H3 to monthlyExpiry)
        val points = HashMap<HorizonGroup, Double>()
        val upcoming = all.map { (d, ev) ->
            val daysAway = ChronoUnit.DAYS.between(today, d).toDouble()
            val inW = windows.filter { (_, end) -> !d.isAfter(end) }.keys.toList()
            for (g in inW) {
                val base = when (ev.importance.coerceIn(1, 3)) { 3 -> 3.0; 2 -> 1.5; else -> 0.5 }
                // Expiries are the targets themselves; only an expiry happening today adds intraday risk.
                val p = if (ev.category == CalendarCategory.EXPIRY) (if (daysAway < 1) 0.5 else 0.0) else base
                val proximity = when { daysAway < 1 -> 1.5; daysAway <= 5 -> 1.0; daysAway <= 15 -> 0.6; else -> 0.35 }
                points[g] = (points[g] ?: 0.0) + p * proximity
            }
            UpcomingEvent(d.toString(), ev.title, ev.category, ev.importance, ev.source, daysAway, inW)
        }
        val levels = HorizonGroup.values().associateWith { g ->
            val p = points[g] ?: 0.0
            var lv = when { p < 1.5 -> 0; p < 3.0 -> 1; p < 6.0 -> 2; else -> 3 }
            if (activeShock && g != HorizonGroup.H3) lv = (lv + 1).coerceAtMost(3)
            EventRiskLevel.values()[lv]
        }
        val notes = buildList {
            if (activeShock) add("A fresh, unpriced high-severity event is in play — risk raised one level for H1/H2")
            if (user.none { it.category == CalendarCategory.RBI }) add("No RBI MPC date in your calendar — add it in Setup → Event calendar")
        }
        return EventRiskReport(levels, HorizonGroup.values().associateWith { points[it] ?: 0.0 }, upcoming, notes)
    }
}
