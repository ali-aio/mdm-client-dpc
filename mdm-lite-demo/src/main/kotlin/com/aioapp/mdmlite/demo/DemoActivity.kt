package com.aioapp.mdmlite.demo

import android.app.Activity
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aioapp.mdmlite.AioMdm

/**
 * A WebView wired the way android-menu-board's MainScreen is. Test triggers:
 *   adb shell am start -n com.aioapp.mdmlite.demo/.DemoActivity --es trigger crash|anr|jserror|badurl
 */
class DemoActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = newWebView().also { setContentView(it) }
        handleTrigger(intent?.getStringExtra("trigger"))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleTrigger(intent.getStringExtra("trigger"))
    }

    private fun handleTrigger(t: String?) {
        when (t) {
            "crash" -> throw IllegalStateException("mdm-lite demo: test crash")
            "anr" -> web?.postDelayed({ Thread.sleep(30_000) }, 500) // main thread blocked; input times out
            "jserror" -> web?.evaluateJavascript("setTimeout(function(){ undefinedFn() }, 0)", null)
            "badurl" -> web?.loadUrl("https://does-not-exist.invalid/")
        }
    }

    private fun newWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                AioMdm.reportPageError(request.url.toString(), error.errorCode, error.description.toString(), request.isForMainFrame)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                AioMdm.reportPageError(request.url.toString(), response.statusCode, "HTTP ${response.statusCode}", request.isForMainFrame)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AioMdm.reportRendererGone(detail, view.url)
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                web = newWebView().also { setContentView(it) }
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    AioMdm.reportJsError(m.message(), m.sourceId(), m.lineNumber())
                }
                return true
            }
        }
        loadUrl(BuildConfig.DEMO_URL)
    }
}
