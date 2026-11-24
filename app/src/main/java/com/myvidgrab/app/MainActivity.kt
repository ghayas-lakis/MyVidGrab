package com.myvidgrab.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var results: LinearLayout
    private val found = linkedSetOf<String>()
    private var privateMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        configureBrowser()
        showHome()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 8)
            setBackgroundColor(0xFF101820.toInt())
        }
        root.addView(TextView(this).apply {
            text = "MYVIDGRAB"
            textSize = 21f
            setTextColor(0xFF20C9B2.toInt())
            setPadding(4, 8, 4, 12)
        })
        address = EditText(this).apply {
            hint = "Paste a video page or URL"
            isSingleLine = true
            textSize = 14f
            setTextColor(-1)
            setHintTextColor(0xFF9EAEB9.toInt())
            setPadding(12, 4, 12, 4)
            setBackgroundColor(0xFF1B2935.toInt())
            setOnEditorActionListener { _, _, _ -> openAddress(); true }
        }
        root.addView(address, LinearLayout.LayoutParams(-1, 48))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(button("Back") { if (browser.canGoBack()) browser.goBack() }, weighted())
        controls.addView(button("Forward") { if (browser.canGoForward()) browser.goForward() }, weighted())
        controls.addView(button("Go") { openAddress() }, weighted(1.2f))
        controls.addView(button("Private") { togglePrivate() }, weighted(1.2f))
        root.addView(controls)
        root.addView(TextView(this).apply {
            text = "Private browsing is ON • Downloads are saved on this device"
            textSize = 11f
            setTextColor(0xFF9EAEB9.toInt())
            setPadding(2, 8, 2, 8)
        })
        browser = WebView(this)
        browser.visibility = android.view.View.GONE
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf("Home", "Browser", "Downloads", "VPN").forEach { label ->
            nav.addView(button(label) {
                when (label) {
                    "Home" -> showHome()
                    "Browser" -> showBrowser()
                    "Downloads" -> showDownloads()
                    "VPN" -> showVpn()
                }
            }, weighted())
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun weighted(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, 44, weight).apply { setMargins(3, 6, 3, 6) }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun configureBrowser() {
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.mediaPlaybackRequiresUserGesture = true
        browser.addJavascriptInterface(VideoBridge(), "MyVidGrab")
        browser.webChromeClient = WebChromeClient()
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                address.setText(url)
                injectDetector()
            }
        }
    }

    private fun openAddress() {
        var url = address.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.contains("://")) url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://www.google.com/search?q=" + Uri.encode(url)
        showBrowser()
        browser.loadUrl(url)
    }

    private fun showPage(view: android.view.View) {
        browser.visibility = android.view.View.GONE
        results.visibility = android.view.View.VISIBLE
        results.removeAllViews()
        results.addView(view)
    }

    private fun showHome() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = "Save videos for offline viewing"
            textSize = 19f
            setTextColor(-1)
        })
        box.addView(TextView(this).apply {
            text = "Open a page in the built-in browser. MyVidGrab looks for standard video elements and direct video links that the page makes available."
            textSize = 14f
            setTextColor(0xFF9EAEB9.toInt())
        })
        box.addView(button("Open browser") {
            showBrowser()
            if (browser.url == null) browser.loadUrl("https://www.google.com")
        })
        box.addView(TextView(this).apply { text = "Detected videos"; textSize = 18f; setTextColor(-1) })
        refreshDetected()
        showPage(box)
    }

    private fun showBrowser() {
        results.visibility = android.view.View.GONE
        browser.visibility = android.view.View.VISIBLE
    }

    private fun refreshDetected() {
        if (!::results.isInitialized) return
        // The home screen is rebuilt when selected; detected URLs remain in memory only.
    }

    private fun injectDetector() {
        val js = """
            (function() {
              if (window.__mvgObserver) return;
              window.__mvgObserver = true;
              function scan() {
                try {
                  document.querySelectorAll('video, video source').forEach(function(e) {
                    var u = e.currentSrc || e.src;
                    if (u && /^https?:/i.test(u)) MyVidGrab.found(u);
                  });
                  document.querySelectorAll('a[href]').forEach(function(a) {
                    if (/\.(mp4|webm|ogv|mov)(\?|#|$)/i.test(a.href)) MyVidGrab.found(a.href);
                  });
                } catch(e) {}
              }
              scan();
              new MutationObserver(scan).observe(document.documentElement,
                {childList:true,subtree:true,attributes:true});
              setInterval(scan, 3000);
            })();
        """.trimIndent()
        browser.evaluateJavascript(js, null)
    }

    inner class VideoBridge {
        @JavascriptInterface
        fun found(url: String) {
            runOnUiThread {
                if ((url.startsWith("https://") || url.startsWith("http://")) && found.add(url)) {
                    Toast.makeText(this@MainActivity, "Video detected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showDownloads() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = "Downloads"
            textSize = 19f
            setTextColor(-1)
        })
        box.addView(TextView(this).apply {
            text = "Downloads are handled by Android Download Manager and appear in your device Downloads folder."
            setTextColor(0xFF9EAEB9.toInt())
        })
        box.addView(button("Open device downloads") {
            try {
                startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (_: Exception) {
                Toast.makeText(this, "Open the Downloads app on your device.", Toast.LENGTH_SHORT).show()
            }
        })
        showPage(box)
    }

    private fun showVpn() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply { text = "VPN"; textSize = 19f; setTextColor(-1) })
        box.addView(TextView(this).apply {
            text = "VPN is not connected or included in this build. A real VPN requires a trusted provider, server infrastructure, and Android VPN service integration. This screen does not route or disguise your traffic."
            setTextColor(0xFF9EAEB9.toInt())
        })
        showPage(box)
    }

    private fun togglePrivate() {
        privateMode = !privateMode
        if (privateMode) {
            browser.clearHistory()
            browser.clearCache(true)
            browser.clearFormData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            found.clear()
        }
        Toast.makeText(this, if (privateMode) "Private mode enabled; local browser data cleared" else "Private mode disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (::browser.isInitialized && browser.visibility == android.view.View.VISIBLE && browser.canGoBack()) {
            browser.goBack()
        } else showHome()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.removeJavascriptInterface("MyVidGrab")
            browser.destroy()
        }
        super.onDestroy()
    }
}