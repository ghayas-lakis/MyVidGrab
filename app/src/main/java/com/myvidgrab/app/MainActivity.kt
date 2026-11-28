package com.myvidgrab.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.URLDecoder
import java.util.Locale

class MainActivity : Activity() {
    private data class VideoSource(val url: String, val pageUrl: String, val width: Int, val height: Int, val mime: String, val title: String, val durationSec: Double, val estimatedBytes: Long)
    private data class DownloadTask(val id: Long, val title: String, var downloaded: Long = 0L, var total: Long = -1L, var speed: Long = 0L, var lastBytes: Long = 0L, var lastTime: Long = 0L, var status: String = "Queued")
    private lateinit var browser: WebView
    private lateinit var address: EditText
    private lateinit var content: LinearLayout
    private lateinit var browserPanel: LinearLayout
    private lateinit var detectedCount: TextView
    private val found = linkedMapOf<String, VideoSource>()
    private val downloads = linkedMapOf<Long, DownloadTask>()
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressPoll = object : Runnable { override fun run() { refreshDownloadProgress(); progressHandler.postDelayed(this, 1000L) } }
    private var privateMode = true
    private var showingBrowser = false

    private val bg = 0xFFFFFFFF.toInt()
    private val panel = 0xFFF5F5F5.toInt()
    private val muted = 0xFF777777.toInt()
    private val accent = 0xFFFFB900.toInt()
    private val white = 0xFF28221D.toInt()
    private val orange = 0xFFFF8A00.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        configureBrowser()
        showHome()
        progressHandler.post(progressPoll)
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
            text = "Downloader"
            textSize = 22f
            letterSpacing = 0.08f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        detectedCount = TextView(this).apply {
            text = "0 VIDEOS"
            textSize = 11f
            setTextColor(orange)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFF3D2.toInt()); cornerRadius = dp(20).toFloat() }
        }
        header.addView(detectedCount)
        root.addView(header)

        address = EditText(this).apply {
            hint = "Search or Enter URL"
            isSingleLine = true
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(panel); cornerRadius = dp(40).toFloat() }
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
        val promo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFB900.toInt()); cornerRadius = dp(22).toFloat() }
        }
        promo.addView(TextView(this).apply { text = "⬇"; textSize = 28f; setTextColor(white); setPadding(0, 0, dp(14), 0) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply { text = "PRIVATE BROWSING"; textSize = 11f; setTextColor(white) })
        copy.addView(TextView(this).apply { text = "Save videos in a few taps"; textSize = 16f; setTextColor(white); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        promo.addView(copy)
        content.addView(promo, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(14); bottomMargin = dp(24) })
        content.addView(TextView(this).apply { text = "Quick access"; textSize = 18f; setTextColor(white); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(12)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val links = listOf(Triple("◎","Instagram","https://www.instagram.com"),Triple("♪","TikTok","https://www.tiktok.com"),Triple("f","Facebook","https://www.facebook.com"),Triple("𝕏","X","https://x.com"),Triple("◉","WhatsApp","https://web.whatsapp.com"))
        links.forEach { (symbol,name,url) ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            item.addView(TextView(this).apply {
                text = symbol; textSize = 25f; gravity = Gravity.CENTER
                setTextColor(if (name == "TikTok" || name == "X") 0xFFFFFFFF.toInt() else white)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(if (name == "TikTok" || name == "X") 0xFF151515.toInt() else accent); shape = android.graphics.drawable.GradientDrawable.OVAL }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            item.addView(TextView(this).apply { text = name; textSize = 10f; setTextColor(white); gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
            item.setOnClickListener { address.setText(url); openAddress() }
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(row)
        val how = TextView(this).apply {
            text = "How to download?"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(orange)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = dp(20).toFloat(); setStroke(dp(2), accent) }
            setOnClickListener { android.app.AlertDialog.Builder(this@MainActivity).setTitle("How to download").setMessage("1. Open a video page in Browser.\n2. Play the video.\n3. Open Videos and choose a source.\n4. Track progress in Downloads.").setPositiveButton("OK", null).show() }
        }
        content.addView(how, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(22); bottomMargin = dp(22) })
        content.addView(sectionTitle("RECENTLY DETECTED"))
        if (found.isEmpty()) content.addView(emptyState("No videos detected yet", "Open a social video page in Browser and play a video. Detected sources will appear here.")) else addVideoCards(content)
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
                text = source.title.ifBlank { "VIDEO ${index + 1}" }
                maxLines = 2
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
                text = source.mime.ifBlank { fileType(source.url) } + "  •  " + formatDuration(source.durationSec) + "  •  " + formatBytes(source.estimatedBytes) + "\n" + shortUrl(source.url)
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
        setTextColor(orange)
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
        setTextColor(white)
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFC533.toInt()); cornerRadius = dp(16).toFloat() }
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
              function report(url, w, h, mime, title, duration) {
                if (!url || !/^https?:/i.test(url)) return;
                let size = 0;
                try {
                  const entries = performance.getEntriesByName(url);
                  if (entries.length) size = entries[entries.length - 1].encodedBodySize || entries[entries.length - 1].transferSize || 0;
                } catch(e) {}
                try { MyVidGrab.found(url, w || 0, h || 0, mime || '', title || document.title || '', duration || 0, size || 0, location.href); } catch(e) {}
              }
              function scan() {
                try {
                  document.querySelectorAll('video').forEach(function(v) {
                    const w = v.videoWidth || v.width || 0;
                    const h = v.videoHeight || v.height || 0;
                    const d = isFinite(v.duration) ? v.duration : 0;
                    const title = v.getAttribute('aria-label') || v.getAttribute('title') || document.title || '';
                    if (v.currentSrc) report(v.currentSrc, w, h, v.getAttribute('type') || '', title, d);
                    if (v.src) report(v.src, w, h, v.getAttribute('type') || '', title, d);
                    v.querySelectorAll('source').forEach(function(s) {
                      report(s.src, w, h, s.type || '', title, d);
                    });
                  });
                  document.querySelectorAll('a[href]').forEach(function(a) {
                    if (/[.](mp4|webm|ogv|mov)(?:[?#]|$)/i.test(a.href))
                      report(a.href, 0, 0, a.type || '', document.title || '', 0);
                  });
                  // Some players create media requests without adding a <video src>.
                  // Resource timing can expose direct media URLs requested by the page.
                  if (window.performance && performance.getEntriesByType) {
                    performance.getEntriesByType('resource').forEach(function(entry) {
                      if (/[.](mp4|webm|ogv|mov)(?:[?#]|$)/i.test(entry.name))
                        report(entry.name, 0, 0, '', document.title || '', 0);
                    });
                  }
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
        fun found(url: String, width: Int, height: Int, mime: String, title: String, duration: Double, estimatedBytes: Long, pageUrl: String) {
            runOnUiThread {
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    val previous = found[url]
                    val cleanedTitle = title.trim().take(140)
                    val next = VideoSource(url, pageUrl, width, height, mime, cleanedTitle, duration, estimatedBytes)
                    found[url] = if (previous == null) next else next.copy(
                        width = if (width > 0) width else previous.width,
                        height = if (height > 0) height else previous.height,
                        mime = mime.ifBlank { previous.mime },
                        title = cleanedTitle.ifBlank { previous.title },
                        durationSec = if (duration > 0) duration else previous.durationSec,
                        estimatedBytes = if (estimatedBytes > 0) estimatedBytes else if (duration > 0 && width > 0 && height > 0) estimateVideoBytes(width, height, duration) else previous.estimatedBytes
                    )
                    detectedCount.text = "${found.size} VIDEOS"
                    if (!showingBrowser) showVideos()
                }
            }
        }
    }

    private fun startDownload(source: VideoSource) {
        try {
            val uri = Uri.parse(source.url)
            val request = DownloadManager.Request(uri)
                .setTitle(source.title.ifBlank { "MyVidGrab video" })
                .setDescription("Downloading video")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val cookie = CookieManager.getInstance().getCookie(source.url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            request.addRequestHeader("User-Agent", browser.settings.userAgentString)
            // Some media hosts require the page that initiated the request as its referrer.
            // Only attach a referrer for a valid HTTP(S) page.
            val pageUri = Uri.parse(source.pageUrl)
            val pageScheme = pageUri.scheme?.lowercase(Locale.US)
            val pageHost = pageUri.host
            val mediaHost = uri.host
            if ((pageScheme == "http" || pageScheme == "https") && !pageHost.isNullOrBlank()) {
                request.addRequestHeader("Referer", source.pageUrl)
                if (!mediaHost.isNullOrBlank() && pageHost.equals(mediaHost, ignoreCase = true)) {
                    request.addRequestHeader("Origin", pageScheme + "://" + pageHost)
                }
            }
            val name = safeFileName(uri.lastPathSegment ?: "myvidgrab_video.mp4")
            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            val taskTitle = source.title.ifBlank { name }
            downloads[downloadId] = DownloadTask(downloadId, taskTitle, lastTime = SystemClock.elapsedRealtime())
            Toast.makeText(this, "Download started — progress is available in Downloads.", Toast.LENGTH_LONG).show()
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
        content.addView(body("Live progress, transfer speed and estimated time remaining are shown below. Total size and ETA appear when Android's download service reports them."))
        if (downloads.isEmpty()) content.addView(emptyState("No downloads started yet", "Start a download from the Videos tab."))
        downloads.values.toList().asReversed().forEach { task ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(panel) }
            card.addView(TextView(this).apply { text = task.title; textSize = 15f; setTextColor(white); maxLines = 2 })
            val percent = if (task.total > 0) ((task.downloaded * 100L) / task.total).toInt().coerceIn(0, 100) else 0
            card.addView(TextView(this).apply { text = "${formatBytes(task.downloaded)} / ${formatBytes(task.total)}   •   $percent%"; textSize = 12f; setTextColor(muted); setPadding(0, dp(7), 0, dp(4)) })
            card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = percent })
            card.addView(TextView(this).apply { text = "${formatBytes(task.speed)}/s   •   ETA: ${formatEta(task)}   •   ${task.status}"; textSize = 12f; setTextColor(accent); setPadding(0, dp(5), 0, 0) })
            content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        content.addView(actionButton("OPEN DOWNLOAD MANAGER") {
            try { startActivity(android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            catch (_: Exception) { Toast.makeText(this, "Open the Downloads app on your device.", Toast.LENGTH_SHORT).show() }
        })
    }

    private fun refreshDownloadProgress() {
        if (downloads.isEmpty()) return
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val now = SystemClock.elapsedRealtime()
        downloads.values.forEach { task ->
            try {
                val cursor = manager.query(DownloadManager.Query().setFilterById(task.id)) ?: return@forEach
                cursor.use {
                    if (it.moveToFirst()) {
                        val bytesCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytes = if (bytesCol >= 0) it.getLong(bytesCol) else 0L
                        val total = if (totalCol >= 0) it.getLong(totalCol) else -1L
                        val status = if (statusCol >= 0) it.getInt(statusCol) else -1
                        val elapsed = (now - task.lastTime).coerceAtLeast(1L)
                        if (bytes >= task.lastBytes) task.speed = ((bytes - task.lastBytes) * 1000L / elapsed).coerceAtLeast(0L)
                        task.downloaded = bytes
                        task.total = total
                        task.lastBytes = bytes
                        task.lastTime = now
                        task.status = when (status) {
                            DownloadManager.STATUS_PENDING -> "Queued"
                            DownloadManager.STATUS_RUNNING -> "Downloading"
                            DownloadManager.STATUS_PAUSED -> "Paused"
                            DownloadManager.STATUS_SUCCESSFUL -> "Completed"
                            DownloadManager.STATUS_FAILED -> "Failed"
                            else -> "Waiting"
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        if (!showingBrowser && content.childCount > 0 && content.getChildAt(0) is TextView && (content.getChildAt(0) as TextView).text.toString() == "Your downloads") showDownloads()
    }

    private fun estimateVideoBytes(width: Int, height: Int, duration: Double): Long {
        val bitrateBitsPerSecond = when {
            height >= 2000 -> 15_000_000L
            height >= 1400 -> 8_000_000L
            height >= 1000 -> 5_000_000L
            height >= 700 -> 2_500_000L
            height >= 400 -> 1_000_000L
            else -> 500_000L
        }
        return (bitrateBitsPerSecond.toDouble() * duration / 8.0).toLong().coerceAtLeast(0L)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "Size unknown"
        if (bytes == 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble(); var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun formatDuration(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return "Duration unknown"
        val s = seconds.toInt(); return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun formatEta(task: DownloadTask): String {
        if (task.total <= 0 || task.speed <= 0) return "—"
        val seconds = ((task.total - task.downloaded).coerceAtLeast(0) / task.speed).toInt()
        return if (seconds >= 3600) String.format(Locale.US, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
        else String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60)
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
        progressHandler.removeCallbacks(progressPoll)
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.removeJavascriptInterface("MyVidGrab")
            browser.destroy()
        }
        super.onDestroy()
    }
}
