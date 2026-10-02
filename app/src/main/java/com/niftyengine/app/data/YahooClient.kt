package com.niftyengine.app.data

import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.InstrumentData
import org.json.JSONObject
import java.net.URLEncoder

/** Yahoo Finance chart API: intraday bars for NIFTY/VIX/Bank Nifty and global markets, plus daily history. */
object YahooClient {
    /** Yahoo symbols per global asset. US index futures are used so the value moves during Indian hours. */
    val GLOBAL_SYMBOLS: Map<GlobalAsset, String> = mapOf(
        GlobalAsset.SP500 to "ES=F", GlobalAsset.NASDAQ to "NQ=F", GlobalAsset.DOW to "YM=F",
        GlobalAsset.NIKKEI to "^N225", GlobalAsset.HANGSENG to "^HSI", GlobalAsset.SHANGHAI to "000001.SS",
        GlobalAsset.DAX to "^GDAXI", GlobalAsset.US_VIX to "^VIX", GlobalAsset.US10Y to "^TNX",
        GlobalAsset.DXY to "DX-Y.NYB", GlobalAsset.BRENT to "BZ=F", GlobalAsset.WTI to "CL=F",
        GlobalAsset.GOLD to "GC=F", GlobalAsset.USDINR to "INR=X",
    )

    private val hosts = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")

    fun chart(symbol: String, interval: String, range: String, name: String = symbol): InstrumentData {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        var last: Exception? = null
        for (h in hosts) {
            try {
                val body = Http.get("$h/v8/finance/chart/$enc?interval=$interval&range=$range&includePrePost=false")
                return parse(body, name, interval == "1d")
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("yahoo failed")
    }

    private fun parse(body: String, name: String, daily: Boolean): InstrumentData {
        val res = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = res.getJSONObject("meta")
        val ts = res.optJSONArray("timestamp")
        val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.optJSONArray("open"); val h = q.optJSONArray("high"); val l = q.optJSONArray("low")
        val c = q.optJSONArray("close"); val v = q.optJSONArray("volume")
        val candles = ArrayList<Candle>()
        if (ts != null && c != null) for (i in 0 until ts.length()) {
            if (c.isNull(i)) continue
            val close = c.getDouble(i)
            candles += Candle(
                ts.getLong(i) * 1000,
                o?.takeUnless { it.isNull(i) }?.getDouble(i) ?: close,
                h?.takeUnless { it.isNull(i) }?.getDouble(i) ?: close,
                l?.takeUnless { it.isNull(i) }?.getDouble(i) ?: close,
                close,
                v?.takeUnless { it.isNull(i) }?.optDouble(i, 0.0) ?: 0.0,
            )
        }
        val lastPx = meta.optDouble("regularMarketPrice", candles.lastOrNull()?.c ?: Double.NaN)
        val prev = meta.optDouble("previousClose", meta.optDouble("chartPreviousClose", Double.NaN))
        return InstrumentData(
            symbol = name, last = lastPx, prevClose = prev,
            open = candles.firstOrNull()?.o ?: Double.NaN,
            high = meta.optDouble("regularMarketDayHigh", Double.NaN), low = meta.optDouble("regularMarketDayLow", Double.NaN),
            volume = meta.optDouble("regularMarketVolume", 0.0),
            intraday = if (daily) emptyList() else candles,
            daily = if (daily) candles else emptyList(),
        )
    }

    /** Daily bars for the last year, excluding today's (possibly partial) bar. */
    fun dailyHistory(symbol: String, todayStartMs: Long): List<Candle> =
        chart(symbol, "1d", "1y").daily.filter { it.t < todayStartMs - 6 * 3_600_000L }
}
