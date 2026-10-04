package com.niftyengine.app.data

import android.util.Xml
import com.niftyengine.engine.model.NewsItem
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RSS news collector. Items are raw; clustering/dedup/scoring happens in the engine's news module. */
object NewsClient {
    data class Feed(val name: String, val url: String)

    val FEEDS = listOf(
        Feed("Economic Times", "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms"),
        Feed("Moneycontrol", "https://www.moneycontrol.com/rss/marketreports.xml"),
        Feed("Livemint", "https://www.livemint.com/rss/markets"),
        Feed("Business Standard", "https://www.business-standard.com/rss/markets-106.rss"),
        Feed("Google News", "https://news.google.com/rss/search?q=" +
            java.net.URLEncoder.encode("Nifty OR Sensex OR RBI OR \"repo rate\" OR FPI OR \"crude oil\" when:1d", "UTF-8") +
            "&hl=en-IN&gl=IN&ceid=IN:en"),
    )

    data class Result(val items: List<NewsItem>, val errors: List<String>)

    fun fetchAll(): Result {
        val items = ArrayList<NewsItem>()
        val errors = ArrayList<String>()
        for (f in FEEDS) {
            try { items += parse(Http.get(f.url, mapOf("Accept" to "application/rss+xml, application/xml, text/xml")), f.name) }
            catch (e: Exception) { errors += "${f.name}: ${e.message}" }
        }
        return Result(items.distinctBy { it.id }, errors)
    }

    private val rfc = DateTimeFormatter.RFC_1123_DATE_TIME
    private val alt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)

    private fun parseDate(s: String): Long? = listOf(rfc, alt).firstNotNullOfOrNull {
        runCatching { ZonedDateTime.parse(s.trim(), it).toInstant().toEpochMilli() }.getOrNull()
    } ?: runCatching { ZonedDateTime.parse(s.trim()).toInstant().toEpochMilli() }.getOrNull()

    fun parse(xml: String, feedName: String): List<NewsItem> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(xml))
        val out = ArrayList<NewsItem>()
        var inItem = false
        var title = ""; var link = ""; var date = ""; var desc = ""; var src = ""
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item" || tag == "entry") { inItem = true; title = ""; link = ""; date = ""; desc = ""; src = "" }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inItem) {
                    val t = p.text ?: ""
                    when (tag) {
                        "title" -> title += t
                        "link" -> link += t
                        "pubDate", "published", "updated", "dc:date" -> date += t
                        "description", "summary" -> desc += t
                        "source" -> src += t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item" || p.name == "entry") {
                        inItem = false
                        val ts = parseDate(date)
                        val clean = title.replace(Regex("\\s+"), " ").trim()
                        if (clean.isNotEmpty() && ts != null) {
                            val source = src.trim().ifBlank { feedName }
                            // Google News appends " - Publisher" to titles.
                            val t2 = if (feedName == "Google News" && clean.contains(" - ")) clean.substringBeforeLast(" - ") else clean
                            out += NewsItem(
                                id = Integer.toHexString((t2 + source).hashCode()) + "-" + ts / 60000,
                                title = t2, source = source, publishedAt = ts,
                                summary = desc.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(300),
                                url = link.trim(),
                            )
                        }
                    }
                    tag = ""
                }
            }
        }
        return out
    }
}
