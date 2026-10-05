package com.niftyengine.app.data

import com.niftyengine.engine.core.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parses the various IST wall-clock timestamp formats used by NSE and Kite into epoch ms (0 if unparseable). */
object TimeParse {
    private val dateTimes = listOf(
        "dd-MMM-yyyy HH:mm:ss", "dd-MMM-yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    private val dates = listOf("dd-MMM-yyyy", "yyyy-MM-dd").map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    fun ist(s: String?): Long {
        val t = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return 0L
        for (f in dateTimes) runCatching { return LocalDateTime.parse(t, f).atZone(Session.IST).toInstant().toEpochMilli() }
        return 0L
    }

    /** A date-only value (e.g. FII/DII "01-Oct-2026") stamped at [hour]:00 IST that day. */
    fun istDate(s: String?, hour: Int = 18): Long {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        for (f in dates) runCatching { return LocalDate.parse(t, f).atTime(hour, 0).atZone(Session.IST).toInstant().toEpochMilli() }
        return 0L
    }
}
