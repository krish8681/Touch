package com.niftyengine.app.store

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.PredictionStore
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

val AppJson = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    encodeDefaults = false
}

/** Human-readable export (decision object): spec keys, defaults included. */
val PrettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
    allowSpecialFloatingPointValues = true
}

/** 17 — prediction log persisted as JSON lines (one record per line). */
class JsonlPredictionStore(private val file: File, private val maxRecords: Int = 5000) : PredictionStore {
    private val cache: MutableList<PredictionRecord> = load()

    private fun load(): MutableList<PredictionRecord> = if (!file.exists()) mutableListOf() else
        file.readLines().mapNotNull { runCatching { AppJson.decodeFromString<PredictionRecord>(it) }.getOrNull() }.toMutableList()

    @Synchronized override fun append(r: PredictionRecord) {
        cache += r
        if (cache.size > maxRecords) { repeat(cache.size - maxRecords) { cache.removeAt(0) }; rewrite() }
        else file.appendText(AppJson.encodeToString(r) + "\n")
    }

    @Synchronized override fun update(r: PredictionRecord) {
        val i = cache.indexOfFirst { it.id == r.id }
        if (i >= 0) { cache[i] = r; dirty = true }
    }

    private var dirty = false

    /** Updates are batched; call after an evaluation pass. */
    @Synchronized fun flush() { if (dirty) { rewrite(); dirty = false } }

    private fun rewrite() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(cache.joinToString("\n", postfix = if (cache.isEmpty()) "" else "\n") { AppJson.encodeToString(it) })
        tmp.renameTo(file)
    }

    @Synchronized override fun all(): List<PredictionRecord> = cache.toList()

    @Synchronized fun clear() { cache.clear(); file.delete() }

    fun exportFile(): File = file
}

/**
 * Records live sessions for the Historical Replay Engine (18). To keep files small:
 * intraday bars and news are stripped (the engine rebuilds intraday paths from successive snapshots,
 * news goes to a separate time-stamped archive), daily history is kept only in the first line of a day,
 * and the option chain is trimmed to ±15 strikes.
 */
class SessionRecorder(private val dir: File) {
    init { dir.mkdirs() }
    private val newsFile = File(dir, "news.jsonl")
    private val seenNews = HashSet<String>().apply {
        if (newsFile.exists()) newsFile.forEachLine { l -> runCatching { add(AppJson.decodeFromString<NewsItem>(l).id) } }
    }

    fun record(s: MarketSnapshot) {
        val day = Session.zdt(s.timestamp).toLocalDate().toString()
        val f = File(dir, "session-$day.jsonl")
        val first = !f.exists()
        val spot = s.nifty.last
        val slim = s.copy(
            nifty = s.nifty.copy(intraday = emptyList(), daily = if (first) s.nifty.daily else emptyList()),
            vix = s.vix?.let { v -> v.copy(intraday = emptyList(), daily = if (first) v.daily else emptyList()) },
            bankNifty = s.bankNifty?.copy(intraday = emptyList(), daily = emptyList()),
            constituents = s.constituents.mapValues { it.value.copy(intraday = emptyList(), daily = emptyList()) },
            sectors = s.sectors.mapValues { it.value.copy(intraday = emptyList(), daily = emptyList()) },
            global = s.global.mapValues { it.value.copy(intraday = emptyList(), daily = emptyList()) },
            optionChain = s.optionChain?.let { c -> c.copy(rows = c.rows.filter { abs(it.strike - spot) <= 15 * c.strikeStep }) },
            news = emptyList(),
            feedStatus = emptyMap(),
        )
        f.appendText(AppJson.encodeToString(slim) + "\n")
        val fresh = s.news.filter { seenNews.add(it.id) }
        if (fresh.isNotEmpty()) newsFile.appendText(fresh.joinToString("") { AppJson.encodeToString(it) + "\n" })
    }

    fun sessions(): List<File> = dir.listFiles { f -> f.name.startsWith("session-") }?.sortedByDescending { it.name } ?: emptyList()

    fun load(f: File): List<MarketSnapshot> {
        val list = f.readLines().mapNotNull { runCatching { AppJson.decodeFromString<MarketSnapshot>(it) }.getOrNull() }
        if (list.isEmpty()) return list
        val nDaily = list.first().nifty.daily
        val vDaily = list.first().vix?.daily ?: emptyList()
        return list.map { s -> s.copy(nifty = s.nifty.copy(daily = nDaily), vix = s.vix?.copy(daily = vDaily)) }
    }

    fun newsArchive(): List<NewsItem> = if (!newsFile.exists()) emptyList() else
        newsFile.readLines().mapNotNull { runCatching { AppJson.decodeFromString<NewsItem>(it) }.getOrNull() }

    fun delete(f: File) { f.delete() }
}
