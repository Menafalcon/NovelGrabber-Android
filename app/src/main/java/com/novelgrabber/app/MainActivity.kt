package com.novelgrabber.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var appView: WebView
    private lateinit var readerView: WebView
    private lateinit var scrapeView: WebView
    private lateinit var browseBar: android.widget.LinearLayout
    private lateinit var browseScrape: Button
    private lateinit var browseScrapeWrap: android.widget.LinearLayout
    private lateinit var browseCount: android.widget.EditText
    private lateinit var engine: Engine
    private val scope = MainScope()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ---- reader book ----
    private var bookChapters: List<Pair<String, String?>> = emptyList()
    private var bookBodies: List<String>? = null
    private var bookImages: Map<String, ByteArray>? = null   // open epub's illustrations (ngimg:// keys)
    private var bookFolder: File? = null
    private var bookTitle = ""
    private var bookKey = ""

    // ---- native TTS reading state ----
    private var ttsPlaying = false
    private var ttsPaused = false
    private var ttsChapter = 0
    private var ttsPara = 0                    // 0 = chapter title; 1..N = body paragraph (para-1)
    private var ttsBody: List<String> = emptyList()
    private var ttsChTitle = ""
    private var ttsChunks: List<String> = emptyList()
    private var ttsChunkIdx = 0
    private var ttsVoice = ""
    private var ttsRate = 1.0f

    private val prefs by lazy { getSharedPreferences("ng", MODE_PRIVATE) }

    private val REQ_EPUB_READ = 11
    private val REQ_EPUB_PDF = 12

    companion object { var instance: MainActivity? = null }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Library.root = File(getExternalFilesDir(null), "NovelGrabber").apply { mkdirs() }
        SiteRules.load(this)
        if (Build.VERSION.SDK_INT >= 33) try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99) } catch (e: Exception) {}

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101217")) }

        // scraper sits full-size at the BOTTOM of the z-stack so SPA pages render correctly,
        // hidden behind the app UI until the user peeks to solve a captcha.
        scrapeView = WebView(this)
        readerView = WebView(this)
        appView = WebView(this)
        readerView.visibility = View.GONE
        root.addView(scrapeView, FrameLayout.LayoutParams(-1, -1))
        root.addView(readerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(appView, FrameLayout.LayoutParams(-1, -1))

        // compact browse bar pinned to the bottom edge: [⚡ Scrape] [← Back] [Ch count]
        fun pill(hex: String, stroke: Boolean = false) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat(); setColor(Color.parseColor(hex))
            if (stroke) setStroke(dp(1), Color.parseColor("#3A4150"))
        }
        fun barBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13.5f
            setTextColor(Color.parseColor("#E7EAF0"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = pill("#1B2438", stroke = true)   // deep dark blue, dim grey border
            minHeight = 0; minimumHeight = 0
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { onClick() }
        }
        browseCount = android.widget.EditText(this).apply {
            setText("100")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); textSize = 13.5f
            setHintTextColor(Color.parseColor("#9099A9"))
            background = pill("#161D2C", stroke = true)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(9))
        }
        browseScrape = barBtn("⚡ Scrape") {
            val u = scrapeView.url ?: ""
            if (!u.startsWith("http")) toast("Open a chapter page first")
            else {
                val cnt = browseCount.text.toString().toIntOrNull() ?: 0
                setPeek(false)
                post(appView, obj("type" to "scrapeFrom", "url" to u, "count" to cnt))
            }
        }
        browseScrapeWrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "Ch"; setTextColor(Color.parseColor("#9099A9")); textSize = 12.5f
                setPadding(dp(8), 0, dp(6), 0)
            })
            addView(browseCount, android.widget.LinearLayout.LayoutParams(dp(62), -2))
        }
        browseBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F2141820"))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            elevation = 20f
            addView(browseScrape, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(barBtn("← Back") { setPeek(false) },
                android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            addView(browseScrapeWrap)
        }
        root.addView(browseBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        // keep content inside the status/nav bars (fixes "everything too high")
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }

        setupUiWebView(appView); setupUiWebView(readerView)
        sleekScrollbar(scrapeView); sleekScrollbar(appView); sleekScrollbar(readerView)
        appView.addJavascriptInterface(AppBridge(), "NG")
        readerView.addJavascriptInterface(ReaderBridge(), "NGR")

        engine = Engine(scrapeView,
            log = { post(appView, obj("type" to "log", "msg" to it)) },
            friendly = { post(appView, obj("type" to "status", "msg" to it)) },
            progress = { d, t -> post(appView, obj("type" to "progress", "done" to d, "total" to t)) },
            finished = { how ->
                post(appView, obj("type" to "finished", "how" to how))
                runOnUiThread {
                    showScraper(false)
                    // auto-file the new novel into a series if the user enabled it in Settings
                    if (how == "done" && prefs.getBoolean("autoSortOnAdd", false)) Library.autoSort()
                    sendLibrary()
                }
            })
        engine.setup()

        appView.loadUrl("file:///android_asset/app.html")
        readerView.loadUrl("file:///android_asset/reader.html")
        tts = TextToSpeech(this, this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The stock WebView scrollbar is a chunky grey slab — swap in a thin rounded accent thumb. */
    private fun sleekScrollbar(w: WebView) {
        w.isVerticalScrollBarEnabled = true
        w.isHorizontalScrollBarEnabled = false
        w.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        w.scrollBarSize = dp(3)
        w.isScrollbarFadingEnabled = true
        if (Build.VERSION.SDK_INT >= 29) {
            w.verticalScrollbarThumbDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xAA6C8CFF.toInt())
                cornerRadius = dp(2).toFloat()
                setSize(dp(3), dp(56))
            }
            w.verticalScrollbarTrackDrawable = null
        }
    }

    private fun setupUiWebView(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        w.setBackgroundColor(Color.parseColor("#101217"))
        // push the app-wide theme once the page's JS is ready (both app.html and reader.html)
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pushTheme(w) }
        }
        // JS confirm()/alert() need a WebChromeClient or they silently return false —
        // this is what made Delete / Delete-all do nothing.
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                ngConfirm(message ?: "Are you sure?", onNo = { result?.cancel() }) { result?.confirm() }
                return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                ngConfirm(message ?: "", "OK", onNo = { result?.confirm() }) { result?.confirm() }
                return true
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { runOnUiThread { onUttDone(id ?: "") } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { runOnUiThread { onUttError(id ?: "") } }
            override fun onError(id: String?, code: Int) { runOnUiThread { onUttError(id ?: "") } }
        })
        sendVoices()
    }

    // ---------------- helpers ----------------

    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    private fun post(w: WebView, o: JSONObject) {
        // U+2028/U+2029 are legal in JSON strings but are line terminators in JS source, so
        // they'd break evaluateJavascript. Escape those two chars only — never touch spaces.
        val js = o.toString().replace(" ", "\\u2028").replace(" ", "\\u2029")
        runOnUiThread { w.evaluateJavascript("ngOnMessage($js)", null) }
    }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    // ---------------- voices ----------------

    private fun sendVoices() {
        if (!ttsReady) return
        val arr = JSONArray()
        try {
            (tts?.voices ?: emptySet<Voice>())
                .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name })).forEach { v ->
                    arr.put(JSONObject().apply {
                        put("name", v.name); put("lang", v.locale.toLanguageTag())
                        put("network", v.isNetworkConnectionRequired); put("quality", v.quality)
                    })
                }
        } catch (e: Exception) {}
        post(readerView, obj("type" to "voices", "ok" to true, "list" to arr))
    }

    private fun applyVoice() {
        val t = tts ?: return
        if (ttsVoice.isNotBlank()) t.voices?.firstOrNull { it.name == ttsVoice }?.let { t.voice = it }
        t.setSpeechRate(ttsRate.coerceIn(0.25f, 3.0f))
    }

    // ---------------- native TTS engine (survives backgrounding) ----------------

    private fun bodyParagraphs(html: String): List<String> {
        val cleaned = html.replace(Regex("""<h2 class="ch-title">.*?</h2>""", RegexOption.DOT_MATCHES_ALL), "")
        val ps = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(cleaned).map { android.text.Html.fromHtml(it.groupValues[1], 0).toString().trim() }
            .filter { it.isNotEmpty() }.toList()
        if (ps.isNotEmpty()) return ps
        val txt = android.text.Html.fromHtml(cleaned.replace(Regex("""</p>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n"), 0).toString()
        return txt.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun ttsLoadChapter(ch: Int): Boolean {
        if (ch < 0 || ch >= bookChapters.size) return false
        ttsChapter = ch
        ttsChTitle = bookChapters[ch].first
        ttsBody = bodyParagraphs(chapterHtml(ch))
        return true
    }
    private fun ttsParaCount() = ttsBody.size + 1
    private fun ttsTextForPara(): String? =
        if (ttsPara == 0) ttsChTitle.ifBlank { null }
        else ttsBody.getOrNull(ttsPara - 1)

    private fun splitChunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(); var s = text.trim()
        while (s.length > max) {
            var cut = -1
            val re = Regex("""[.!?…"”]\s""")
            for (mm in re.findAll(s.substring(0, max))) cut = mm.range.last
            if (cut < 40) cut = s.lastIndexOf(' ', max); if (cut < 40) cut = max
            out.add(s.substring(0, cut).trim()); s = s.substring(cut).trim()
        }
        if (s.isNotEmpty()) out.add(s)
        return out
    }

    private fun ttsStartNative(ch: Int, para: Int, voice: String, rate: Double) {
        ttsVoice = voice; ttsRate = rate.toFloat()
        if (!ttsReady) { post(readerView, obj("type" to "ttserr", "id" to "", "err" to "Voice engine not ready")); return }
        if (!ttsLoadChapter(ch)) return
        ttsPara = para.coerceIn(0, ttsParaCount() - 1)
        ttsPlaying = true; ttsPaused = false
        TtsService.start(this)
        applyVoice()
        speakPara()
        notifyState()
    }
    private fun speakPara() {
        if (!ttsPlaying) return
        val text = ttsTextForPara()
        if (text == null) { ttsAdvancePara(); return }
        val max = TextToSpeech.getMaxSpeechInputLength()
        ttsChunks = splitChunks(text, max - 1)
        ttsChunkIdx = 0
        postReaderPos(); saveTtsPos()
        speakChunk()
    }
    private fun speakChunk() {
        if (!ttsPlaying || ttsPaused) return
        if (ttsChunkIdx >= ttsChunks.size) { ttsAdvancePara(); return }
        val id = "c${ttsChapter}_p${ttsPara}_k${ttsChunkIdx}"
        tts?.speak(ttsChunks[ttsChunkIdx], TextToSpeech.QUEUE_FLUSH, null, id)
    }
    private fun onUttDone(id: String) {
        if (id == "preview") return
        if (!ttsPlaying || ttsPaused) return
        if (id != "c${ttsChapter}_p${ttsPara}_k${ttsChunkIdx}") return
        ttsChunkIdx++
        speakChunk()
    }
    private fun onUttError(id: String) {
        if (id == "preview") { post(readerView, obj("type" to "ttserr", "id" to "preview", "err" to "voice error")); return }
        if (ttsPlaying && !ttsPaused) speakChunk()   // skip a bad chunk rather than stall
    }
    private fun ttsAdvancePara() {
        ttsPara++
        if (ttsPara >= ttsParaCount()) { ttsNextChapter(); return }
        speakPara()
    }
    private fun ttsNextChapter() {
        if (ttsChapter + 1 >= bookChapters.size) { post(readerView, obj("type" to "ttsend")); ttsStopNative(); return }
        ttsLoadChapter(ttsChapter + 1); ttsPara = 0
        postReaderChapter()
        speakPara(); notifyState()
    }
    private fun ttsToggleNative() {
        if (!ttsPlaying) return
        if (ttsPaused) { ttsPaused = false; speakChunk() }
        else { ttsPaused = true; try { tts?.stop() } catch (e: Exception) {} }
        notifyState()
    }
    private fun ttsStopNative() {
        ttsPlaying = false; ttsPaused = false
        try { tts?.stop() } catch (e: Exception) {}
        TtsService.stop(this)
        post(readerView, obj("type" to "ttsstate", "playing" to false, "stopped" to true))
    }
    private fun ttsSkipPara(dir: Int) {
        if (!ttsPlaying) return
        val n = (ttsPara + dir).coerceIn(0, ttsParaCount() - 1)
        ttsPara = n; ttsPaused = false; try { tts?.stop() } catch (e: Exception) {}
        speakPara(); notifyState()
    }
    private fun ttsSkipChapter(dir: Int) {
        val target = (ttsChapter + dir)
        if (target < 0 || target >= bookChapters.size) return
        ttsLoadChapter(target); ttsPara = 0; ttsPaused = false; try { tts?.stop() } catch (e: Exception) {}
        postReaderChapter(); speakPara(); notifyState()
    }
    private fun ttsJumpTo(ch: Int, para: Int) {
        if (!ttsLoadChapter(ch)) return
        ttsPara = para.coerceIn(0, ttsParaCount() - 1); ttsPaused = false; ttsPlaying = true
        try { tts?.stop() } catch (e: Exception) {}
        speakPara(); notifyState()
    }

    private fun notifyState() {
        TtsService.instance?.update(bookTitle, ttsChTitle, ttsPlaying && !ttsPaused)
        post(readerView, obj("type" to "ttsstate", "playing" to (ttsPlaying && !ttsPaused)))
    }
    private fun postReaderPos() { post(readerView, obj("type" to "ttspos", "chapter" to ttsChapter, "para" to ttsPara)) }
    private fun postReaderChapter() { post(readerView, obj("type" to "ttschapter", "idx" to ttsChapter)) }
    private fun saveTtsPos() {
        if (bookKey.isBlank()) return
        prefs.edit().putInt("ttsch_$bookKey", ttsChapter).putInt("ttspa_$bookKey", ttsPara).apply()
    }

    private fun preview(text: String, voice: String, rate: Double) {
        if (ttsPlaying) ttsStopNative()
        ttsVoice = voice; ttsRate = rate.toFloat()
        applyVoice()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    // ---------------- reader book plumbing ----------------

    /**
     * A folder path round-tripped through the WebView can fail direct File access on some
     * Android builds (Android/data is FUSE-protected; raw-path stat behaves differently from
     * the File descended from getExternalFilesDir()). history() builds each folder by walking
     * the live root, and those File objects are known-readable, so re-resolve by name against
     * it. Falls back to the raw path so nothing regresses on devices where it already worked.
     */
    private fun resolveFolder(s: String): File {
        val direct = File(s)
        if (Library.metaPath(direct).exists()) return direct
        val name = direct.name
        Library.history().firstOrNull { it.folder.name == name }?.let { return it.folder }
        val byName = File(Library.root, name)
        if (Library.metaPath(byName).exists()) return byName
        return direct
    }

    private fun openNovel(folderPath: String) {
        val folder = resolveFolder(folderPath)
        if (!Library.metaPath(folder).exists()) { toast("Novel not found"); return }
        val meta = Library.loadOrCreate(folder, "", "")
        bookFolder = folder; bookBodies = null; bookImages = null; bookTitle = meta.title; bookKey = "novel:" + folder.name
        bookChapters = Library.ordered(meta).map { c ->
            val t = c.title.ifBlank { if (c.num > 0) "Chapter " + (if (c.num > 100000) c.num % 100000 else c.num) else "Chapter" }
            t to c.file
        }
        var coverUrl = ""
        if (meta.cover.isNotBlank()) { val cf = File(folder, meta.cover); if (cf.exists()) coverUrl = Uri.fromFile(cf).toString() }
        ttsStopNative()
        showReader(true)
        sendBook(meta.author, coverUrl)
    }
    private fun openEpubFile(f: File) {
        try {
            val book = EpubRead.load(f)
            bookImages = book.images
            bookFolder = null; bookBodies = book.chapters.map { it.second }
            bookChapters = book.chapters.map { it.first to null }
            bookTitle = book.title; bookKey = "epub:" + f.name
            var coverUrl = ""
            book.cover?.let { bytes -> val cf = File(cacheDir, "epub_cover." + book.coverExt); cf.writeBytes(bytes); coverUrl = Uri.fromFile(cf).toString() }
            ttsStopNative()
            showReader(true)
            sendBook(book.author, coverUrl)
        } catch (e: Exception) { toast("EPUB error: ${e.message}") }
    }
    private var lastBookMsg: JSONObject? = null   // replayed on reader "ready" (page may load after us)
    private fun sendBook(author: String, coverUrl: String) {
        prefs.edit().putString("lastBook", bookKey).putString("lastFolder", bookFolder?.absolutePath ?: "").apply()
        val msg = obj("type" to "book", "title" to bookTitle, "author" to author,
            "cover" to coverUrl, "key" to bookKey,
            "chapters" to JSONArray().also { a -> bookChapters.forEach { a.put(it.first) } },
            "resumeIdx" to prefs.getInt("resch_$bookKey", 0),
            "resumeFrac" to prefs.getFloat("resfr_$bookKey", 0f))
        lastBookMsg = msg
        post(readerView, msg)
        sendVoices()
    }
    private fun chapterHtml(idx: Int): String {
        if (idx < 0 || idx >= bookChapters.size) return ""
        val raw = bookBodies?.let { it[idx] } ?: run {
            val rel = bookChapters[idx].second ?: return ""
            val f = File(bookFolder, rel)
            if (!f.exists()) return "<p>(chapter file missing)</p>"
            EpubRead.bodyInner(f.readText()).replace(Regex("""<h2 class="ch-title">.*?</h2>""", RegexOption.DOT_MATCHES_ALL), "")
        }
        // illustrations → data: URIs (epub ngimg:// refs or ../images/ files in the novel folder)
        return EpubRead.inlineImages(raw, bookImages, bookFolder)
    }
    private fun showReader(show: Boolean) = runOnUiThread {
        if (show) {
            readerView.alpha = 0f
            readerView.visibility = View.VISIBLE
            readerView.animate().alpha(1f).setDuration(240).start()
            appView.visibility = View.GONE
            showScraper(false)
        } else {
            readerView.visibility = View.GONE
            appView.alpha = 1f
            appView.visibility = View.VISIBLE
        }
    }

    // ---------------- scraper peek ----------------

    private fun showScraper(on: Boolean) = runOnUiThread {
        scrapeView.visibility = if (on || engine.running) View.VISIBLE else View.GONE
        if (!on && !engine.running) setPeek(false)
    }
    /** Smoothly swaps between the app UI and the scraper website (for captchas / site browsing). */
    private fun setPeek(on: Boolean) = runOnUiThread {
        if (on) {
            scrapeView.visibility = View.VISIBLE
            appView.animate().alpha(0f).setDuration(220)
                .withEndAction { appView.visibility = View.INVISIBLE }.start()
            // count + Scrape only make sense while browsing (no download running)
            browseScrape.visibility = if (engine.running) View.GONE else View.VISIBLE
            browseScrapeWrap.visibility = browseScrape.visibility
            browseBar.visibility = View.VISIBLE
            browseBar.alpha = 0f; browseBar.translationY = dp(56).toFloat()
            browseBar.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            if (browseBar.visibility == View.VISIBLE)
                browseBar.animate().alpha(0f).translationY(dp(56).toFloat()).setDuration(200)
                    .withEndAction { browseBar.visibility = View.GONE }.start()
            // don't resurrect the app UI over an open reader
            if (readerView.visibility != View.VISIBLE) {
                appView.visibility = View.VISIBLE
                appView.animate().alpha(1f).setDuration(220).start()
            }
        }
    }

    // ---------------- library / exports ----------------

    private fun sendLibrary() {
        val arr = JSONArray()
        for (m in Library.history()) {
            var coverUrl = ""
            if (m.cover.isNotBlank()) { val cf = File(m.folder, m.cover); if (cf.exists()) coverUrl = Uri.fromFile(cf).toString() }
            arr.put(JSONObject().apply {
                put("title", m.title); put("folder", m.folder.absolutePath); put("chapters", m.chapters.size)
                put("site", try { Uri.parse(m.source).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" })
                put("updated", m.updated.take(10)); put("cover", coverUrl)
                put("cat", m.category); put("pct", progressPct(m))
            })
        }
        post(appView, obj("type" to "lib", "list" to arr,
            "cats" to JSONArray(Library.categories()),
            "epubs" to libFiles(".epub"), "pdfs" to libFiles(".pdf"),
            "autoSort" to prefs.getBoolean("autoSortOnAdd", false),
            "lastFolder" to (prefs.getString("lastFolder", "") ?: "")))
    }

    /** Reading progress from the saved resume position (chapter idx + in-chapter fraction). */
    private fun progressPct(m: NovelMeta): Int {
        if (m.chapters.isEmpty()) return 0
        val key = "novel:" + m.folder.name
        val idx = prefs.getInt("resch_$key", -1)
        if (idx < 0) return 0
        val fr = prefs.getFloat("resfr_$key", 0f)
        return ((idx + fr) / m.chapters.size * 100).toInt().coerceIn(0, 100)
    }

    /** Exported epub/pdf files across the library (novel folders + root) for the Formats shelves. */
    private fun libFiles(ext: String): JSONArray {
        val arr = JSONArray()
        fun add(f: File, sub: String) =
            arr.put(JSONObject().apply { put("path", f.absolutePath); put("name", f.name); put("sub", sub) })
        val root = Library.root
        if (root.exists()) {
            root.listFiles()?.filter { it.isFile && it.name.endsWith(ext, true) }?.forEach { add(it, "Library") }
            root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }?.forEach { d ->
                d.listFiles()?.filter { it.isFile && it.name.endsWith(ext, true) }?.forEach { add(it, d.name) }
            }
        }
        return arr
    }

    // ---------------- themed dialogs (stock AlertDialog looks ancient) ----------------

    private fun dlgBg() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(Color.parseColor("#1C2029"))
        setStroke(dp(1), Color.parseColor("#2A303C"))
    }

    private fun dlgTitle(text: String) = android.widget.TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#E7EAF0")); textSize = 16.5f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setPadding(dp(14), dp(6), dp(14), dp(12))
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun pillBtn(label: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13.5f
        setTextColor(fg)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(11).toFloat(); setColor(bg)
            if (bg == 0x00000000) setStroke(dp(1), Color.parseColor("#2A303C"))
        }
        minHeight = 0; minimumHeight = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    /** App-themed action sheet: rounded dark card, accent-free rows, red-tinted danger rows. */
    private fun ngMenu(title: String, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        wrap.addView(dlgTitle(title))
        val dlg = AlertDialog.Builder(this)
            .setView(android.widget.ScrollView(this).apply { addView(wrap) }).create()
        items.forEachIndexed { i, (label, danger) ->
            wrap.addView(android.widget.TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor(if (danger) "#FF6B6B" else "#E7EAF0"))
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (danger) 0x24FF6B6B else 0x00000000)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
                    .apply { if (danger) topMargin = dp(6) }
                setOnClickListener { dlg.dismiss(); onPick(i) }
            })
        }
        dlg.window?.setBackgroundDrawable(dlgBg())
        dlg.show()
    }

    /** App-themed yes/no confirm. */
    private fun ngConfirm(msg: String, yesLabel: String = "Yes", danger: Boolean = false,
                          onNo: (() -> Unit)? = null, onYes: () -> Unit) {
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
        }
        wrap.addView(android.widget.TextView(this).apply {
            text = msg
            setTextColor(Color.parseColor("#E7EAF0")); textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        lateinit var dlg: AlertDialog
        var answered = false
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
            addView(pillBtn("Cancel", 0x00000000, Color.parseColor("#9099A9")) { answered = true; dlg.dismiss(); onNo?.invoke() })
            addView(pillBtn(yesLabel, Color.parseColor(if (danger) "#E5484D" else "#6C8CFF"), Color.WHITE)
                { answered = true; dlg.dismiss(); onYes() },
            )
            (getChildAt(1) as Button).layoutParams =
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) }
        }
        wrap.addView(row)
        dlg = AlertDialog.Builder(this).setView(wrap).create()
        dlg.setOnDismissListener { if (!answered) onNo?.invoke() }
        dlg.window?.setBackgroundDrawable(dlgBg())
        dlg.show()
    }

    private fun ngEdit(hint: String) = android.widget.EditText(this).apply {
        this.hint = hint
        setTextColor(Color.parseColor("#E7EAF0")); textSize = 14.5f
        setHintTextColor(Color.parseColor("#9099A9"))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#161920"))
            setStroke(dp(1), Color.parseColor("#2A303C"))
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // ---- categories ----

    private fun moveCatDialog(folder: File) {
        val cats = Library.categories()
        ngMenu("Move to", cats.map { it to false } + ("New category…" to false)) { w ->
            if (w < cats.size) { Library.setCategory(folder, cats[w]); sendLibrary() }
            else newCatDialog { name -> Library.setCategory(folder, name); sendLibrary() }
        }
    }

    private fun newCatDialog(then: ((String) -> Unit)? = null) {
        val input = ngEdit("Category name")
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(dlgTitle("New category"))
            addView(input)
        }
        lateinit var dlg: AlertDialog
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
            addView(pillBtn("Cancel", 0x00000000, Color.parseColor("#9099A9")) { dlg.dismiss() })
            addView(pillBtn("Create", Color.parseColor("#6C8CFF"), Color.WHITE) {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { Library.addCategory(name); then?.invoke(name); sendLibrary() }
                dlg.dismiss()
            })
            (getChildAt(1) as Button).layoutParams =
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) }
        }
        wrap.addView(row)
        dlg = AlertDialog.Builder(this).setView(wrap).create()
        dlg.window?.setBackgroundDrawable(dlgBg())
        dlg.show()
    }

    // ---- app-wide theme (dark / dark sepia / sepia / light); the picker lives on the ⚙ settings page ----

    private fun curTheme(): String = prefs.getString("appTheme", "dark") ?: "dark"

    private fun pushTheme(w: WebView) = post(w, obj("type" to "theme", "theme" to curTheme()))

    private fun setTheme(name: String) {
        prefs.edit().putString("appTheme", name).apply()
        pushTheme(appView); pushTheme(readerView)
    }

    /** The catbar's ⋯ chip: custom shelves + formats + new category. */
    private fun moreCatsDialog() {
        val customs = Library.categories().filter { it != "General" && it != Library.COMPLETED }
        val items = customs + listOf("EPUB files", "PDF files", "＋ New category")
        fun shelf(cat: String) = post(appView, obj("type" to "setShelf", "cat" to cat))
        ngMenu("Shelves", items.map { it to false }) { w ->
            when {
                w < customs.size -> shelf(customs[w])
                items[w] == "EPUB files" -> shelf("fmt:epub")
                items[w] == "PDF files" -> shelf("fmt:pdf")
                else -> newCatDialog { name -> shelf(name) }
            }
        }
    }

    /** Full action sheet for a novel — the card's ⋯ button. */
    private fun novelMenuDialog(folder: File) {
        if (!Library.metaPath(folder).exists()) { toast("Novel not found"); return }
        val meta = Library.loadOrCreate(folder, "", "")
        ngMenu(meta.title, listOf(
            "▶  Read" to false, "Export EPUB" to false, "Export PDF" to false,
            "Share" to false, "Move to…" to false, "Delete" to true)) { w ->
            val fwd = { cmd: String -> handleApp(JSONObject().put("cmd", cmd).put("folder", folder.absolutePath)) }
            when (w) {
                0 -> openNovel(folder.absolutePath)
                1 -> { toast("Working… saving to Downloads"); fwd("epub") }
                2 -> { toast("Working… saving to Downloads"); fwd("pdf") }
                3 -> fwd("share")
                4 -> moveCatDialog(folder)
                5 -> ngConfirm("Delete “${meta.title}” and all its files?", "Delete", danger = true)
                        { folder.deleteRecursively(); sendLibrary() }
            }
        }
    }

    /** Paste-any-text TTS — opens the READER in input mode (not a popup). */
    private fun openSpeakMode() {
        showReader(true)
        post(readerView, obj("type" to "speakMode"))
    }

    private fun speakFreeText(text: String) {
        if (ttsPlaying) ttsStopNative()
        applyVoice()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtMost(3500)
        var rest = text
        var first = true
        var i = 0
        while (rest.isNotEmpty()) {
            val piece = rest.take(max); rest = rest.drop(max)
            tts?.speak(piece, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "free_${i++}")
            first = false
        }
    }

    /** Reader hit "The End" — offer to file the novel under Completed. */
    private fun onBookFinished() {
        val folder = bookFolder ?: return
        if (!Library.metaPath(folder).exists()) return
        val m = Library.loadOrCreate(folder, "", "")
        if (m.category.equals(Library.COMPLETED, true)) return
        ngConfirm("You reached the end of “${m.title}”.\nMove it to Completed?", "Move")
            { Library.setCategory(folder, Library.COMPLETED); sendLibrary() }
    }

    private fun saveToDownloads(src: File, mime: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val vals = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, src.name); put(MediaStore.Downloads.MIME_TYPE, mime) }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, vals) ?: throw Exception("Couldn't open Downloads")
            contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            "Downloads/${src.name}"
        } else {
            @Suppress("DEPRECATION") val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dst = File(dl, src.name); src.copyTo(dst, overwrite = true); dst.absolutePath
        }
    }
    private fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "com.novelgrabber.app.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(i, "Share ${f.name}"))
    }
    private fun exportPdfOf(meta: NovelMeta): File {
        val chapters = Library.ordered(meta).mapNotNull { c ->
            val f = File(meta.folder, c.file)
            if (!f.exists()) null else c.title.ifBlank { "Chapter" } to
                PdfWriter.htmlToText(EpubRead.bodyInner(f.readText()).replace(Regex("""<h2 class="ch-title">.*?</h2>""", RegexOption.DOT_MATCHES_ALL), ""))
        }
        val out = File(meta.folder, Library.sanitize(meta.title) + ".pdf")
        PdfWriter.write(meta.title, meta.author, chapters, out); return out
    }

    // ---------------- JS bridges ----------------

    inner class AppBridge { @JavascriptInterface fun msg(json: String) { val m = try { JSONObject(json) } catch (e: Exception) { return }; runOnUiThread { handleApp(m) } } }

    private fun handleApp(m: JSONObject) {
        when (m.optString("cmd")) {
            "start" -> {
                val url = m.optString("url").trim()
                if (!url.startsWith("http")) { post(appView, obj("type" to "status", "msg" to "Paste a full http(s):// link")); return }
                showScraper(true); engine.start(scope, url, m.optInt("count", 50))
            }
            "stop" -> engine.stop()
            "peek" -> setPeek(true)
            "openSite" -> { val u = m.optString("url"); if (u.startsWith("http")) { scrapeView.loadUrl(u); setPeek(true) } }
            "lib" -> sendLibrary()
            "read" -> openNovel(m.optString("folder"))
            "openEpub" -> pickEpub(REQ_EPUB_READ)
            "epub2pdf" -> pickEpub(REQ_EPUB_PDF)
            "delete" -> { resolveFolder(m.optString("folder")).deleteRecursively(); sendLibrary() }
            "deleteAll" -> { Library.history().forEach { it.folder.deleteRecursively() }; sendLibrary() }
            "moveCat" -> moveCatDialog(resolveFolder(m.optString("folder")))
            "newCat" -> newCatDialog()
            "autoSort" -> { val (g, n) = Library.autoSort(); toast(if (g > 0) "Filed $n novels into $g series" else "No similar titles found"); sendLibrary() }
            "speakText" -> openSpeakMode()
            "setTheme" -> setTheme(m.optString("theme", "dark"))
            "setAutoSort" -> prefs.edit().putBoolean("autoSortOnAdd", m.optBoolean("on")).apply()
            "moreCats" -> moreCatsDialog()
            "openFile" -> {
                val f = File(m.optString("path"))
                when {
                    !f.exists() -> toast("File not found")
                    f.extension.equals("epub", true) -> openEpubFile(f)
                    else -> try {
                        val uri = FileProvider.getUriForFile(this, "com.novelgrabber.app.fileprovider", f)
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (e: Exception) { toast("No PDF viewer installed") }
                }
            }
            "novelMenu" -> novelMenuDialog(resolveFolder(m.optString("folder")))
            "epub" -> scope.launch(Dispatchers.IO) { try { val meta = Library.loadOrCreate(resolveFolder(m.optString("folder")), "", ""); val f = EpubWriter.build(meta); val w = saveToDownloads(f, "application/epub+zip"); toast("EPUB saved to $w") } catch (e: Exception) { toast("EPUB failed: ${e.message}") } }
            "pdf" -> scope.launch(Dispatchers.IO) { try { val meta = Library.loadOrCreate(resolveFolder(m.optString("folder")), "", ""); val f = exportPdfOf(meta); val w = saveToDownloads(f, "application/pdf"); toast("PDF saved to $w") } catch (e: Exception) { toast("PDF failed: ${e.message}") } }
            "share" -> scope.launch(Dispatchers.IO) { try { val meta = Library.loadOrCreate(resolveFolder(m.optString("folder")), "", ""); val ex = meta.folder.listFiles()?.firstOrNull { it.extension == "epub" }; val f = ex ?: EpubWriter.build(meta); withContext(Dispatchers.Main) { shareFile(f, "application/epub+zip") } } catch (e: Exception) { toast("Share failed: ${e.message}") } }
        }
    }

    inner class ReaderBridge { @JavascriptInterface fun msg(json: String) { val m = try { JSONObject(json) } catch (e: Exception) { return }; runOnUiThread { handleReader(m) } } }

    private fun handleReader(m: JSONObject) {
        when (m.optString("cmd")) {
            "ready" -> { sendVoices(); lastBookMsg?.let { post(readerView, it) } }
            "finished" -> onBookFinished()
            "speakFree" -> speakFreeText(m.optString("text"))
            "freeStop" -> tts?.stop()
            "get" -> { val idx = m.optInt("idx", -1); post(readerView, obj("type" to "chapter", "idx" to idx, "title" to (bookChapters.getOrNull(idx)?.first ?: ""), "html" to chapterHtml(idx))) }
            "voices" -> sendVoices()
            "ttsPlay" -> ttsStartNative(m.optInt("chapter", 0), m.optInt("para", 0), m.optString("voice"), m.optDouble("rate", 1.0))
            "ttsToggle" -> ttsToggleNative()
            "ttsStop" -> ttsStopNative()
            "ttsSkip" -> ttsSkipPara(m.optInt("dir", 1))
            "ttsJump" -> ttsJumpTo(m.optInt("chapter", ttsChapter), m.optInt("para", 0))
            "preview" -> preview(m.optString("text"), m.optString("voice"), m.optDouble("rate", 1.0))
            "savepos" -> { if (m.optString("key") == bookKey) prefs.edit().putInt("resch_$bookKey", m.optInt("idx", 0)).putFloat("resfr_$bookKey", m.optDouble("r", 0.0).toFloat()).apply() }
            "close" -> { showReader(false) }
        }
    }

    fun onTtsServiceReady() = runOnUiThread { if (ttsPlaying) notifyState() }

    fun onTtsControl(action: String) = runOnUiThread {
        when (action) {
            TtsService.ACTION_TOGGLE -> ttsToggleNative()
            TtsService.ACTION_NEXT -> ttsSkipChapter(1)
            TtsService.ACTION_PREV -> ttsSkipChapter(-1)
            TtsService.ACTION_PARA_NEXT -> ttsSkipPara(1)
            TtsService.ACTION_PARA_PREV -> ttsSkipPara(-1)
            TtsService.ACTION_STOP -> ttsStopNative()
        }
    }

    private fun pickEpub(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream")) }
        @Suppress("DEPRECATION") startActivityForResult(i, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
        val tmp = File(cacheDir, Library.sanitize(name).ifBlank { "book.epub" })
        try { contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } } catch (e: Exception) { toast("Couldn't read file: ${e.message}"); return }
        when (requestCode) {
            REQ_EPUB_READ -> openEpubFile(tmp)
            REQ_EPUB_PDF -> scope.launch(Dispatchers.IO) { try { val book = EpubRead.load(tmp); val out = File(cacheDir, Library.sanitize(book.title) + ".pdf"); PdfWriter.write(book.title, book.author, book.chapters.map { it.first to PdfWriter.htmlToText(it.second) }, out); val w = saveToDownloads(out, "application/pdf"); toast("PDF saved to $w") } catch (e: Exception) { toast("Convert failed: ${e.message}") } }
        }
    }

    override fun onResume() {
        super.onResume()
        // returning from background: re-sync the reader to what TTS is currently reading
        if (ttsPlaying && readerView.visibility == View.VISIBLE) { postReaderChapter(); postReaderPos(); notifyState() }
    }

    override fun onPause() {
        super.onPause()
        // persist login cookies (NovelUpdates sessions survive app restarts)
        try { android.webkit.CookieManager.getInstance().flush() } catch (e: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            browseBar.visibility == View.VISIBLE -> setPeek(false)
            readerView.visibility == View.VISIBLE -> showReader(false)
            engine.running && scrapeView.canGoBack() -> scrapeView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (e: Exception) {}
        TtsService.stop(this)
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}
