package com.niftyengine.app.data

import com.niftyengine.engine.model.FuturesData
import com.niftyengine.engine.model.InstrumentData
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Zerodha Kite Connect v3 (optional). Supplies exchange-grade NIFTY spot, India VIX, Bank Nifty and
 * near-month NIFTY futures (price + OI + volume). Option chain/constituents still come from NSE.
 *
 * Login: open [loginUrl] → user logs in → Kite redirects to your app's redirect URL with
 * `request_token` → [createSession] exchanges it (checksum = SHA-256(api_key + request_token + api_secret)).
 * Access tokens expire daily (~6 AM IST).
 */
object KiteClient {
    private const val API = "https://api.kite.trade"

    fun loginUrl(apiKey: String) = "https://kite.zerodha.com/connect/login?v=3&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"

    fun createSession(apiKey: String, apiSecret: String, requestToken: String): String {
        val checksum = MessageDigest.getInstance("SHA-256").digest((apiKey + requestToken + apiSecret).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val req = Request.Builder().url("$API/session/token")
            .header("X-Kite-Version", "3")
            .post(FormBody.Builder().add("api_key", apiKey).add("request_token", requestToken).add("checksum", checksum).build())
            .build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException("Kite session failed: HTTP ${r.code} ${body.take(160)}")
            return JSONObject(body).getJSONObject("data").getString("access_token")
        }
    }

    /** Monthly NIFTY futures expire on the last Tuesday of the month (NSE schedule from Sep 2025). */
    fun nearMonthFutSymbol(today: LocalDate): String {
        var d = today
        var exp = d.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY))
        if (today.isAfter(exp)) { d = today.plusMonths(1).withDayOfMonth(1); exp = d.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY)) }
        val mon = exp.month.name.take(3)
        return "NFO:NIFTY%02d%sFUT".format(exp.year % 100, mon)
    }

    data class KiteQuotes(val nifty: InstrumentData?, val bank: InstrumentData?, val vix: InstrumentData?, val futures: FuturesData?)

    fun quotes(apiKey: String, accessToken: String, today: LocalDate): KiteQuotes {
        val fut = nearMonthFutSymbol(today)
        val keys = listOf("NSE:NIFTY 50", "NSE:NIFTY BANK", "NSE:INDIA VIX", fut)
        val url = "$API/quote?" + keys.joinToString("&") { "i=" + URLEncoder.encode(it, "UTF-8") }
        val body = Http.get(url, mapOf("X-Kite-Version" to "3", "Authorization" to "token $apiKey:$accessToken"))
        val data = JSONObject(body).getJSONObject("data")
        fun inst(k: String, name: String): InstrumentData? = data.optJSONObject(k)?.let { q ->
            val ohlc = q.optJSONObject("ohlc") ?: JSONObject()
            InstrumentData(name, q.optDouble("last_price"), ohlc.optDouble("close"), ohlc.optDouble("open"),
                ohlc.optDouble("high"), ohlc.optDouble("low"), q.optDouble("volume", 0.0))
        }
        val f = data.optJSONObject(fut)?.let { q ->
            FuturesData(fut, fut.removePrefix("NFO:"), q.optDouble("last_price"), q.optJSONObject("ohlc")?.optDouble("close") ?: Double.NaN,
                q.optDouble("oi", 0.0), Double.NaN, q.optDouble("volume", 0.0))
        }
        return KiteQuotes(inst("NSE:NIFTY 50", "NIFTY 50"), inst("NSE:NIFTY BANK", "NIFTY BANK"), inst("NSE:INDIA VIX", "INDIA VIX"), f)
    }
}
