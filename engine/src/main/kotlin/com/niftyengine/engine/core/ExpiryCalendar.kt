package com.niftyengine.engine.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * NIFTY expiry rules used when the option chain does not tell us (backtests, missing feeds): weekly contracts expire on
 * Tuesday, the monthly contract on the last Tuesday of the month (NSE schedule since September 2025). Exchange holidays
 * move an expiry to the previous trading day; the live chain's own expiry date always wins over these rules.
 */
object ExpiryCalendar {
    var weeklyDay: DayOfWeek = DayOfWeek.TUESDAY

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** Next weekly expiry date on/after [now]'s date (the same day counts until 15:30). */
    fun nextWeekly(now: Long): LocalDate {
        val z = Session.zdt(now)
        var d = z.toLocalDate().with(TemporalAdjusters.nextOrSame(weeklyDay))
        if (d == z.toLocalDate() && !z.toLocalTime().isBefore(Session.CLOSE)) d = d.plusWeeks(1)
        return d
    }

    fun monthlyOf(year: Int, month: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(weeklyDay))

    /** Next monthly expiry on/after [now]'s date (the same day counts until 15:30). */
    fun nextMonthly(now: Long): LocalDate {
        val z = Session.zdt(now)
        val today = z.toLocalDate()
        var m = monthlyOf(today.year, today.monthValue)
        if (m.isBefore(today) || (m == today && !z.toLocalTime().isBefore(Session.CLOSE))) {
            val nm = today.plusMonths(1)
            m = monthlyOf(nm.year, nm.monthValue)
        }
        return m
    }

    fun isMonthlyExpiry(d: LocalDate) = d == monthlyOf(d.year, d.monthValue)

    /** Session minutes (09:15–15:30, weekdays) between two instants. */
    fun tradingMinutesBetween(from: Long, to: Long): Double {
        if (to <= from) return 0.0
        var total = 0.0
        var d = Session.zdt(from).toLocalDate()
        val end = Session.zdt(to).toLocalDate()
        while (!d.isAfter(end)) {
            if (!isWeekend(d)) {
                val open = d.atTime(Session.OPEN).atZone(Session.IST).toInstant().toEpochMilli()
                val close = d.atTime(Session.CLOSE).atZone(Session.IST).toInstant().toEpochMilli()
                val a = maxOf(open, from); val b = minOf(close, to)
                if (b > a) total += (b - a) / 60_000.0
            }
            d = d.plusDays(1)
        }
        return total
    }

    /** The instant [minutes] session-minutes after [from] (skipping nights and weekends). */
    fun addTradingMinutes(from: Long, minutes: Double): Long {
        var left = minutes
        var t = from
        var d = Session.zdt(from).toLocalDate()
        repeat(30) {
            if (!isWeekend(d)) {
                val open = d.atTime(Session.OPEN).atZone(Session.IST).toInstant().toEpochMilli()
                val close = d.atTime(Session.CLOSE).atZone(Session.IST).toInstant().toEpochMilli()
                val start = maxOf(open, t)
                if (start < close) {
                    val avail = (close - start) / 60_000.0
                    if (left <= avail) return start + (left * 60_000).toLong()
                    left -= avail
                }
            }
            d = d.plusDays(1)
            t = d.atTime(LocalTime.MIDNIGHT).atZone(Session.IST).toInstant().toEpochMilli()
        }
        return t
    }

    /** Close (15:30) of the next trading session at or after [now] (today's if the session hasn't closed yet). */
    fun nextSessionClose(now: Long): Long {
        var d = Session.zdt(now).toLocalDate()
        while (true) {
            if (!isWeekend(d)) {
                val close = Session.closeOf(d)
                if (now < close) return close
            }
            d = d.plusDays(1)
        }
    }

    /** Close of the n-th trading day after [now]'s date (n ≥ 1). */
    fun closeAfterTradingDays(now: Long, n: Int): Long {
        var d = Session.zdt(now).toLocalDate()
        var k = 0
        while (k < n) {
            d = d.plusDays(1)
            if (!isWeekend(d)) k++
        }
        return Session.closeOf(d)
    }
}
