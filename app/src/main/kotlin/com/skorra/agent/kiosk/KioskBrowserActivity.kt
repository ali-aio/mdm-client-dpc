package com.skorra.agent.kiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.skorra.agent.AgentConfig
import java.net.URI

/**
 * Browser kiosk: a full-screen WebView locked to [AgentConfig.kioskUrl] and the
 * [AgentConfig.kioskUrlAllow] prefix allowlist. Runs inside the lock task started by
 * [KioskHostActivity] (same package, so it stays pinned). Navigation to a URL outside
 * the allowlist is silently dropped; an empty allowlist restricts to the start URL's origin.
 */
class KioskBrowserActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var startUrl: String
    private var allow: List<String> = emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AgentConfig.get(this)
        startUrl = cfg.kioskUrl
        allow = cfg.kioskUrlAllow()

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val ok = allowed(url)
                if (!ok) Log.i(TAG, "blocked navigation: $url")
                return !ok
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // Redirect chains bypass shouldOverrideUrlLoading in some engines; recheck.
                if (!allowed(url)) {
                    Log.i(TAG, "blocked redirect: $url")
                    view.stopLoading()
                    view.loadUrl(startUrl)
                }
            }
        }
        setContentView(web)
        web.loadUrl(startUrl)
    }

    private fun allowed(url: String): Boolean {
        if (url == startUrl || url == "about:blank") return true
        if (allow.isEmpty()) return sameOrigin(url, startUrl)
        return allow.any { url.startsWith(it) } || sameOrigin(url, startUrl)
    }

    private fun sameOrigin(a: String, b: String): Boolean = try {
        val ua = URI(a); val ub = URI(b)
        ua.scheme == ub.scheme && ua.host == ub.host
    } catch (e: Exception) {
        false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
        // else: swallow — back must not escape the kiosk
    }

    companion object {
        private const val TAG = "KioskBrowser"
    }
}
