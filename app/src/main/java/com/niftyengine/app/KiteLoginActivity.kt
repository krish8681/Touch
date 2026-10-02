package com.niftyengine.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.niftyengine.app.data.KiteClient

/**
 * Hosts the Kite Connect login page and captures the `request_token` from the redirect
 * (whatever redirect URL is configured for the app in the Kite developer console).
 */
class KiteLoginActivity : Activity() {
    companion object {
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_REQUEST_TOKEN = "request_token"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = check(request.url)
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { check(Uri.parse(url)) }
        }
        setContentView(web)
        web.loadUrl(KiteClient.loginUrl(key))
    }

    private fun check(uri: Uri): Boolean {
        val token = uri.getQueryParameter("request_token") ?: return false
        if (uri.getQueryParameter("status") == "cancelled") { finish(); return true }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_REQUEST_TOKEN, token))
        finish()
        return true
    }
}
