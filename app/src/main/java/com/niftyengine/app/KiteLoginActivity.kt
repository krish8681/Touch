package com.niftyengine.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.niftyengine.app.data.KiteClient

/**
 * Hosts the Kite Connect login page and captures the `request_token` from the redirect
 * (whatever redirect URL is configured for the app in the Kite developer console).
 *
 * Never crashes the app: odd URLs (intent:, about:blank, data:) are ignored, a dead WebView renderer is handled,
 * and if the WebView can't be used at all the login opens in the browser with a box to paste the redirected URL.
 */
class KiteLoginActivity : Activity() {
    companion object {
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_REQUEST_TOKEN = "request_token"

        /** `request_token` from a redirect URL (or a pasted bare token); null if absent. Safe for any string. */
        fun tokenFrom(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (Regex("^[A-Za-z0-9]{16,64}$").matches(t)) return t
            return Regex("[?&#]request_token=([A-Za-z0-9]+)").find(t)?.groupValues?.get(1)
        }

        fun cancelled(text: String) = Regex("[?&#]status=cancelled").containsMatchIn(text)
    }

    private var web: WebView? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val url = KiteClient.loginUrl(key)
        runCatching { showWebView(url) }.onFailure { showManual(url, "In-app browser unavailable (${it.javaClass.simpleName}).") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView(url: String) {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url?.toString().orEmpty()
                if (check(u)) return true
                // Non-web links (intent:, market:, kite app deep links…) can't load in a WebView — hand them to Android.
                if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    runCatching { startActivity(Intent.parseUri(u, Intent.URI_INTENT_SCHEME).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    return true
                }
                return false
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { check(url.orEmpty()) }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
                // The WebView renderer died (often low memory). Returning true keeps the app alive.
                runCatching { (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
                web = null
                if (!done) showManual(url, "The login page crashed (low memory?).")
                return true
            }
        }
        web = w
        setContentView(w)
        w.loadUrl(url)
    }

    /** Fallback: log in in the phone's browser, then paste the redirected URL (or just the request_token). */
    private fun showManual(url: String, reason: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = EditText(this).apply { hint = "Paste the redirected URL or request_token"; setSingleLine(false); minLines = 2 }
        val msg = TextView(this).apply {
            text = "$reason\n\n1. Tap “Open Kite login” and log in.\n2. After login the browser goes to your redirect URL " +
                "(it may show an error page — that's fine). Copy that whole address.\n3. Paste it below and tap Continue."
            setTextColor(Color.WHITE)
        }
        val open = Button(this).apply { text = "Open Kite login"; setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }
        val go = Button(this).apply {
            text = "Continue"
            setOnClickListener { if (!check(box.text.toString())) box.error = "No request_token found" }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP; setPadding(pad, pad * 2, pad, pad); setBackgroundColor(Color.rgb(11, 15, 20))
            addView(msg); addView(open); addView(box); addView(go)
        })
    }

    private fun check(url: String): Boolean {
        if (done) return true
        if (cancelled(url)) { done = true; finish(); return true }
        val token = tokenFrom(url).takeIf { url.contains("request_token") || !url.contains("://") } ?: return false
        done = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_REQUEST_TOKEN, token))
        finish()
        return true
    }

    override fun onDestroy() {
        runCatching { web?.apply { stopLoading(); (parent as? ViewGroup)?.removeView(this); destroy() } }
        web = null
        super.onDestroy()
    }
}
