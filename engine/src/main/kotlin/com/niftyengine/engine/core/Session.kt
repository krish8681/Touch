package com.niftyengine.engine.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** NSE cash/F&O session helpers (IST 09:15 – 15:30). */
object Session {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    const val SESSION_MINUTES = 375
    const val TRADING_DAYS = 252.0

    fun zdt(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(IST)

    fun isOpen(millis: Long): Boolean {
        val z = zdt(millis)
        if (z.dayOfWeek.value >= 6) return false
        val t = z.toLocalTime()
        return !t.isBefore(OPEN) && t.isBefore(CLOSE)
    }

    fun minutesSinceOpen(millis: Long): Double {
        val z = zdt(millis)
        val open = z.with(OPEN)
        return ((millis - open.toInstant().toEpochMilli()) / 60000.0).coerceIn(0.0, SESSION_MINUTES.toDouble())
    }

    fun minutesToClose(millis: Long): Double = SESSION_MINUTES - minutesSinceOpen(millis)

    fun sessionStart(millis: Long): Long = zdt(millis).with(OPEN).toInstant().toEpochMilli()

    fun closeOf(date: LocalDate): Long = date.atTime(CLOSE).atZone(IST).toInstant().toEpochMilli()

    /**
     * Trading-time years between [from] and [to], counting only session minutes (simplified:
     * calendar days → trading days by 5/7, plus intraday remainder). Used for option time value.
     */
    fun yearsToExpiry(from: Long, expiryMillis: Long): Double {
        val ms = (expiryMillis - from).coerceAtLeast(0)
        val days = ms / 86_400_000.0
        // calendar-day convention (as used by India VIX), floor at 30 min to keep greeks finite
        return (days / 365.0).coerceAtLeast(30.0 / (365.0 * 24 * 60))
    }

    fun hhmm(millis: Long): String {
        val z = zdt(millis)
        return "%02d:%02d".format(z.hour, z.minute)
    }
}
