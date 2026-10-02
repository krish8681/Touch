package com.niftyengine.app.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

/** In-memory cookie jar (NSE requires the session cookies set by its HTML pages). */
class MemoryCookieJar : CookieJar {
    private val store = HashMap<String, MutableMap<String, Cookie>>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = store.getOrPut(url.host) { HashMap() }
        cookies.forEach { m[it.name] = it }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.entries.filter { url.host.endsWith(it.key.removePrefix("www.")) || it.key == url.host }
            .flatMap { it.value.values }.filter { it.expiresAt > now }
    }
    @Synchronized fun clear() = store.clear()
}

object Http {
    val cookies = MemoryCookieJar()
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Accept-Language", "en-US,en;q=0.9")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${url.substringBefore('?').takeLast(60)}")
            return r.body?.string() ?: throw IOException("empty body")
        }
    }
}

/** Small time-based cache so slow-moving feeds are not refetched every cycle. */
class Cached<T>(private val ttlMs: Long, private val loader: () -> T) {
    private var value: T? = null
    private var at = 0L
    var lastError: String? = null; private set

    @Synchronized fun get(force: Boolean = false): T? {
        val now = System.currentTimeMillis()
        if (force || now - at > ttlMs) {
            try { value = loader(); at = now; lastError = null } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                at = now - ttlMs + minOf(20_000L, ttlMs) // retry failed loads after ~20s
            }
        }
        return value
    }
}
