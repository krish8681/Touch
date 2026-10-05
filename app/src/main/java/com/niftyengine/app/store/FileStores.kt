package com.niftyengine.app.store

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.PredictionStore
import com.niftyengine.engine.model.FiiDerivDay
import com.niftyengine.engine.model.FlowData
import com.niftyengine.engine.model.FlowDay
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

val AppJson = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    encodeDefaults = false
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

/** Writes [text] atomically (temp file + rename). */
private fun File.writeAtomic(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { tmp.copyTo(this, overwrite = true); tmp.delete() }
}

/** Daily FPI/DII cash flows kept on disk so 5- and 20-day sums exist (NSE only publishes the latest session). */
class FlowHistoryStore(private val file: File, private val keep: Int = 40) {
    private val days: MutableList<FlowDay> = runCatching {
        AppJson.decodeFromString(ListSerializer(FlowDay.serializer()), file.readText()).toMutableList()
    }.getOrDefault(mutableListOf())

    /** Adds the latest session (date normalised to yyyy-MM-dd) and returns the history, oldest first. */
    @Synchronized fun add(f: FlowData): List<FlowDay> {
        val d = f.asOf.takeIf { it > 0 }?.let { Session.zdt(it).toLocalDate().toString() } ?: return days.toList()
        val row = FlowDay(d, f.fpiNetCr, f.diiNetCr)
        if (days.none { it == row }) {
            days.removeAll { it.date == d }
            days += row
            days.sortBy { it.date }
            while (days.size > keep) days.removeAt(0)
            runCatching { file.writeAtomic(AppJson.encodeToString(ListSerializer(FlowDay.serializer()), days)) }
        }
        return days.toList()
    }

    @Synchronized fun all(): List<FlowDay> = days.toList()
}

/** NSE participant-wise OI (FII rows) cached per trading day on disk; holidays are remembered so they aren't refetched. */
class ParticipantOiStore(private val file: File) {
    @kotlinx.serialization.Serializable
    private data class Disk(val days: List<FiiDerivDay> = emptyList(), val missing: List<String> = emptyList())

    private var disk: Disk = runCatching { AppJson.decodeFromString(Disk.serializer(), file.readText()) }.getOrDefault(Disk())

    @Synchronized fun has(d: LocalDate) = disk.days.any { it.date == d.toString() } || d.toString() in disk.missing

    @Synchronized fun put(d: LocalDate, row: FiiDerivDay?) {
        disk = if (row == null) disk.copy(missing = (disk.missing + d.toString()).distinct().takeLast(120))
        else disk.copy(days = (disk.days.filter { it.date != row.date } + row).sortedBy { it.date }.takeLast(60))
        runCatching { file.writeAtomic(AppJson.encodeToString(Disk.serializer(), disk)) }
    }

    @Synchronized fun last(n: Int): List<FiiDerivDay> = disk.days.takeLast(n)
}
