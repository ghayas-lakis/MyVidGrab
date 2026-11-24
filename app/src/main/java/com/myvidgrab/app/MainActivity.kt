package com.myvidgrab.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URLDecoder
import java.util.Locale

class MainActivity : Activity() {
    private data class VideoSource(val url: String, val width: Int, val height: Int, val mime: String)
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var content: LinearLayout
    private lateinit var browserPanel: LinearLayout
    private lateinit var detectedCount: TextView
    private val found = linkedMapOf<String, VideoSource>()
    private var privateMode = true
    private var showingBrowser = false

    private val bg = 0xFF0B1220.toInt()
    private val panel = 0xFF172235.toInt()
    private val muted = 0xFF9AAAC0.toInt()
    private val accent = 0xFF27D3B2.toInt()
    private val white = 0xFFF4F7FB.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        configureBrowser()
        showHome()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "MYVIDGRAB"
            textSize = 22f
            letterSpacing = 0.08f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        detectedCount = TextView(this).apply {
            text = "0 VIDEOS"
            textSize = 11f
            setTextColor(accent)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(panel)
        }
        header.addView(detectedCount)
        root.addView(header)

        address = EditText(this).apply {
            hint = "Paste a video page URL"
            isSingleLine = true
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(panel)
            setSelectAllOnFocus(false)
            setOnEditorActionListener { _, _, _ -> openAddress(); true }
        }
        root.addView(address, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(smallButton("‹") { if (browser.canGoBack()) browser.goBack() }, weighted())
        controls.addView(smallButton("›") { if (browser.canGoForward()) browser.goForward() }, weighted())
        controls.addView(smallButton("GO") { openAddress() }, weighted(1.2f))
        controls.addView(smallButton(if (privateMode) "PRIVATE ON" else "PRIVATE OFF") { togglePrivate() }, weighted(1.5f))
        root.addView(controls)

        browserPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        browser = WebView(this)
        browser.setBackgroundColor(bg)
        browserPanel.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(browserPanel, LinearLayout.LayoutParams(-1, 0, 1f))

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        nav.addView(navButton("HOME") { showHome() }, weighted())
        nav.addView(navButton("BROWSER") { showBrowser() }, weighted())
        nav.addView(navButton("VIDEOS") { showVideos() }, weighted())
        nav.addView(navButton("DOWNLOADS") { showDownloads() }, weighted())
        root.addView(nav)
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun weighted(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, dp(44), weight).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) }

    private fun smallButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(white)
        setOnClickListener { click() }
    }

    private fun navButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setTextColor(accent)
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
        if (!url.contains("://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://www.google.com/search?q=" + Uri.encode(url)
        }
        showBrowser()
        browser.loadUrl(url)
    }

    private fun showHome() {
        showingBrowser = false
        browserPanel.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()
        content.addView(heading("Your videos, ready to save"))
        content.addView(body("Browse to a page with a video. When MyVidGrab finds a downloadable video source, it will appear in Videos with its available quality and a Download button."))
        content.addView(actionButton("OPEN BROWSER") {
            if (browser.url == null) browser.loadUrl("https://www.google.com")
            showBrowser()
        })
        content.addView(sectionTitle("DETECTED VIDEOS"))
        addVideoCards(content)
        content.addView(body("Only direct video sources and quality variants exposed by the page can be listed. Protected or segmented streams may not be downloadable here."))
    }

    private fun showBrowser() {
        showingBrowser = true
        content.visibility = View.GONE
        browserPanel.visibility = View.VISIBLE
        if (browser.url == null) browser.loadUrl("https://www.google.com")
    }

    private fun showVideos() {
        showingBrowser = false
        browserPanel.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()
        content.addView(heading("Detected videos"))
        content.addView(body("Choose a source below. Resolution is shown when the webpage exposes video dimensions."))
        if (found.isEmpty()) {
            content.addView(emptyState("No videos detected yet", "Open a video page in Browser and start playback. Then return here."))
        } else addVideoCards(content)
    }

    private fun addVideoCards(parent: LinearLayout) {
        if (found.isEmpty()) {
            parent.addView(emptyState("Nothing detected yet", "Open a video page in the browser."))
            return
        }
        found.values.toList().forEachIndexed { index, source ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(panel)
            }
            val resolution = if (source.width > 0 && source.height > 0)
                "${source.height}p  •  ${source.width} × ${source.height}"
            else "Quality not reported by page"
            card.addView(TextView(this).apply {
                text = "VIDEO ${index + 1}"
                textSize = 11f
                setTextColor(accent)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = resolution
                textSize = 16f
                setTextColor(white)
                setPadding(0, dp(5), 0, dp(3))
            })
            card.addView(TextView(this).apply {
                text = source.mime.ifBlank { fileType(source.url) } + "  •  " + shortUrl(source.url)
                textSize = 11f
                setTextColor(muted)
            })
            card.addView(actionButton("DOWNLOAD THIS VERSION") { startDownload(source) })
            parent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun heading(value: String) = TextView(this).apply {
        text = value
        textSize = 23f
        setTextColor(white)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(accent)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(muted)
        setPadding(0, dp(4), 0, dp(10))
    }

    private fun emptyState(title: String, message: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        setBackgroundColor(panel)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 16f
            setTextColor(white)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        addView(body(message))
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(bg)
        setBackgroundColor(accent)
        setOnClickListener { click() }
    }

    private fun shortUrl(url: String): String =
        try { Uri.parse(url).lastPathSegment?.take(48) ?: Uri.parse(url).host.orEmpty() }
        catch (_: Exception) { "Video source" }

    private fun fileType(url: String): String {
        val path = Uri.parse(url).path.orEmpty().lowercase(Locale.US)
        return when {
            path.endsWith(".mp4") -> "MP4"
            path.endsWith(".webm") -> "WEBM"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".ogv") -> "OGV"
            else -> "VIDEO"
        }
    }

    private fun injectDetector() {
        val js = """
            (function() {
              if (window.__mvgObserver) return;
              window.__mvgObserver = true;
              const seen = new Set();
              function report(url, w, h, mime) {
                if (!url || !/^https?:/i.test(url) || seen.has(url)) return;
                seen.add(url);
                try { MyVidGrab.found(url, w || 0, h || 0, mime || ''); } catch(e) {}
              }
              function scan() {
                try {
                  document.querySelectorAll('video').forEach(function(v) {
                    const w = v.videoWidth || v.width || 0;
                    const h = v.videoHeight || v.height || 0;
                    if (v.currentSrc) report(v.currentSrc, w, h, v.getAttribute('type') || '');
                    if (v.src) report(v.src, w, h, v.getAttribute('type') || '');
                    v.querySelectorAll('source').forEach(function(s) {
                      report(s.src, w, h, s.type || '');
                    });
                  });
                  document.querySelectorAll('a[href]').forEach(function(a) {
                    if (/.(mp4|webm|ogv|mov)(?|#|$)/i.test(a.href))
                      report(a.href, 0, 0, a.type || '');
                  });
                } catch(e) {}
              }
              scan();
              new MutationObserver(scan).observe(document.documentElement,
                {childList:true,subtree:true,attributes:true});
              setInterval(scan, 2500);
            })();
        """.trimIndent()
        browser.evaluateJavascript(js, null)
    }

    inner class VideoBridge {
        @JavascriptInterface
        fun found(url: String, width: Int, height: Int, mime: String) {
            runOnUiThread {
                if ((url.startsWith("https://") || url.startsWith("http://")) && !found.containsKey(url)) {
                    found[url] = VideoSource(url, width, height, mime)
                    detectedCount.text = "${found.size} VIDEOS"
                    if (!showingBrowser) showVideos()
                    Toast.makeText(this@MainActivity, "Video detected — open VIDEOS to download", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startDownload(source: VideoSource) {
        try {
            val uri = Uri.parse(source.url)
            val request = DownloadManager.Request(uri)
                .setTitle("MyVidGrab video")
                .setDescription("Downloading video")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val cookie = CookieManager.getInstance().getCookie(source.url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            request.addRequestHeader("User-Agent", browser.settings.userAgentString)
            val name = safeFileName(uri.lastPathSegment ?: "myvidgrab_video.mp4")
            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Download started. Check Downloads for progress.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start download: ${e.localizedMessage ?: "unsupported source"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun safeFileName(raw: String): String {
        val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
        return decoded.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "myvidgrab_video.mp4" }
    }

    private fun showDownloads() {
        showingBrowser = false
        browserPanel.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.removeAllViews()
        content.addView(heading("Your downloads"))
        content.addView(body("Android Download Manager handles the transfer and shows progress and completion notifications. Files are saved in your device's Downloads folder."))
        content.addView(actionButton("OPEN DOWNLOAD MANAGER") {
            try { startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            catch (_: Exception) { Toast.makeText(this, "Open the Downloads app on your device.", Toast.LENGTH_SHORT).show() }
        })
    }

    private fun togglePrivate() {
        privateMode = !privateMode
        if (privateMode) {
            browser.clearHistory()
            browser.clearCache(true)
            browser.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            found.clear()
            detectedCount.text = "0 VIDEOS"
        }
        Toast.makeText(this, if (privateMode) "Private mode enabled; local browser data cleared" else "Private mode disabled", Toast.LENGTH_SHORT).show()
        if (!showingBrowser) showHome()
    }

    override fun onBackPressed() {
        if (showingBrowser && browser.canGoBack()) browser.goBack() else showHome()
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
