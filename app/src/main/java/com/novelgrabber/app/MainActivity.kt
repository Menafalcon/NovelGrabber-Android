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
import android.os.SystemClock
import android.provider.DocumentsContract
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var rootView: FrameLayout
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
    private val REQ_IMPORT_DIR = 13

    // index in scrapeView's history where the CURRENT browse session began — the in-app
    // browser can only go back as far as that, never into pages the scraper loaded earlier
    private var browseBase = 0
    @Volatile private var importing = false
    @Volatile private var sorting = false
    @Volatile private var destroyed = false

    // true while the in-app browser overlay is open (set synchronously, unlike the bar's animated visibility)
    private var browsing = false
    private var queuedMode = false      // when ON, the browser button becomes "Add to Queue" instead of "⚡ Scrape"
    private var seriesOpen = false      // the library page is showing the inside of a series card (back = close it)
    // system back (swipe / button) handling — see syncBackCallback()
    private var backCb: Any? = null
    private var backCbOn = false

    companion object {
        var instance: MainActivity? = null
        @Volatile var foreground = false      // true while the app is on screen (decides if a "finished" notification is needed)
    }

    // ---- startup: the first frame is a tiny native splash; everything heavy is built right after it ----
    @Volatile private var initialized = false
    private lateinit var stage: FrameLayout
    private var splash: FrameLayout? = null
    private var splashShownAt = 0L
    private var pendingReturnToBook = false
    private var pendingResult: Triple<Int, Int, Intent?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Building three WebViews takes a few hundred ms on the UI thread. Before, nothing could be
        // drawn until that was finished (= the black screen). Now the very first frame is just this
        // logo; initApp() runs right after that frame is on screen.
        stage = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101217")) }
        val logo = android.widget.ImageView(this).apply { setImageResource(R.drawable.logo_splash) }
        val sp = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#101217"))
            isClickable = true                                   // swallow touches until the app is ready
            addView(logo, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        }
        splash = sp
        stage.addView(sp, FrameLayout.LayoutParams(-1, -1))
        setContentView(stage)
        stage.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                stage.viewTreeObserver.removeOnPreDrawListener(this)
                splashShownAt = SystemClock.uptimeMillis()
                stage.post { if (!isFinishing && !isDestroyed) initApp() }     // after the splash frame is drawn
                return true
            }
        })
    }

    /** Short exit animation: the logo grows slightly while the splash fades away, revealing the app. */
    private fun dismissSplash() {
        val s = splash ?: return
        splash = null
        val wait = (splashShownAt + 250 - SystemClock.uptimeMillis()).coerceAtLeast(0)     // never just a flash
        s.postDelayed({
            val logo = s.getChildAt(0)
            // 1) the logo winds up (dips slightly) and zooms past the camera
            logo?.animate()?.scaleX(1.5f)?.scaleY(1.5f)?.alpha(0f)?.setDuration(380)
                ?.setInterpolator(android.view.animation.AnticipateInterpolator(2f))?.start()
            // 2) the splash background fades out, 3) the app rises into place underneath
            s.animate().alpha(0f).setStartDelay(120).setDuration(300).withEndAction { stage.removeView(s) }.start()
            rootView.translationY = dp(28).toFloat()
            rootView.animate().translationY(0f).setStartDelay(120).setDuration(420)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }, wait)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initApp() {
        Library.root = File(getExternalFilesDir(null), "NovelGrabber").apply { mkdirs() }
        SiteRules.load(this)
        if (Build.VERSION.SDK_INT >= 33) try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 99) } catch (e: Exception) {}

        val root = FrameLayout(this).apply { setBackgroundColor(chromeColor(curTheme(), false)) }
        rootView = root

        // scraper sits full-size at the BOTTOM of the z-stack so SPA pages render correctly,
        // hidden behind the app UI until the user peeks to solve a captcha.
        scrapeView = WebView(this)
        readerView = WebView(this)
        appView = WebView(this)
        readerView.visibility = View.GONE
        root.addView(scrapeView, FrameLayout.LayoutParams(-1, -1))
        root.addView(readerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(appView, FrameLayout.LayoutParams(-1, -1))

        // compact browse bar pinned to the bottom edge: [⚡ Scrape] [✕ Close] [Ch count]  (back = system swipe/button)
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
                val rule = SiteRules.forHost(Uri.parse(u).host)
                val sels = JSONArray(rule.novelTitle).toString()
                val js = """JSON.stringify((function(){
                  var S=$sels, nt='';
                  for(var i=0;i<S.length;i++){try{var e=document.querySelector(S[i]);if(e&&(e.innerText||'').trim()){nt=e.innerText.trim();break;}}catch(x){}}
                  var og=document.querySelector('meta[property="og:title"]')||document.querySelector('meta[name="og:title"]');
                  return {nt:nt, og:og?(og.getAttribute('content')||''):'', dt:document.title||''};
                })())"""
                val fallback = resolveNovelTitle(u, "", "", scrapeView.title ?: "")
                scrapeView.evaluateJavascript(js) { raw ->
                    var title = fallback
                    try {
                        val decoded = org.json.JSONTokener(raw).nextValue() as? String
                        if (!decoded.isNullOrBlank()) {
                            val o = JSONObject(decoded)
                            title = resolveNovelTitle(u, o.optString("nt"), o.optString("og"), o.optString("dt"))
                        }
                    } catch (e: Exception) {}
                    setPeek(false)
                    post(appView, obj("type" to "scrapeFrom", "url" to u, "count" to cnt, "title" to title))
                }
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
            addView(barBtn("✕ Close") { closeBrowser() },
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            addView(browseScrapeWrap)
        }
        root.addView(browseBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        stage.addView(root, 0, FrameLayout.LayoutParams(-1, -1))     // under the splash overlay

        // keep content inside the status/nav bars (fixes "everything too high") AND above the
        // keyboard: on Android 15+ the window no longer resizes for the keyboard (adjustResize is
        // ignored), so without the IME inset the browse bar's chapter box ends up hidden behind it
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val kb = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            v.setPadding(s.left, s.top, s.right, maxOf(s.bottom, kb))
            insets
        }

        setupUiWebView(appView); setupUiWebView(readerView)
        sleekScrollbar(scrapeView); sleekScrollbar(appView); sleekScrollbar(readerView)
        applyChrome()
        appView.addJavascriptInterface(AppBridge(), "NG")
        readerView.addJavascriptInterface(ReaderBridge(), "NGR")

        engine = Engine(scrapeView,
            log = { post(appView, obj("type" to "log", "msg" to it)) },
            friendly = { ScrapeService.status(it); post(appView, obj("type" to "status", "msg" to it)) },
            progress = { d, t -> ScrapeService.progress(d, t); scheduleLibraryRefresh(); post(appView, obj("type" to "progress", "done" to d, "total" to t)) },
            finished = { how ->
                post(appView, obj("type" to "finished", "how" to how))
                ScrapeService.finish(this@MainActivity, how)      // drop the background notification / announce the result
                runOnUiThread {
                    showScraper(false)
                    // auto-file the new novel into a series if the user enabled it in Settings
                    if (how == "done" && prefs.getBoolean("autoSortOnAdd", false)) runAutoSort(false)
                    else sendLibrary()
                }
            })
        engine.setup()

        appView.loadUrl("file:///android_asset/app.html")
        readerView.loadUrl("file:///android_asset/reader.html")
        // the speech engine isn't needed to show the library — start it once the UI is up
        stage.postDelayed({ if (!isFinishing && !isDestroyed && tts == null) tts = TextToSpeech(this, this) }, 500)
        // safety net: never keep the splash up if the first library push is somehow missed
        stage.postDelayed({ dismissSplash() }, 2500)

        instance = this
        initialized = true
        pendingResult?.let { (rq, rs, d) -> pendingResult = null; handleActivityResult(rq, rs, d) }
        if (pendingReturnToBook) { pendingReturnToBook = false; returnToBook() }
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
        w.setBackgroundColor(chromeColor(curTheme(), w === readerView))
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
        sendRules()
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

    private val arabicRe = Regex("""[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]""")

    private fun applyVoice(sampleText: String? = null) {
        val t = tts ?: return
        val chosen = if (ttsVoice.isNotBlank()) t.voices?.firstOrNull { it.name == ttsVoice } else null
        val wantAr = sampleText != null && arabicRe.containsMatchIn(sampleText)
        if (wantAr && (chosen == null || !chosen.locale.language.equals("ar", true))) {
            val arVoice = t.voices?.filter { it.locale.language.equals("ar", true) }
                ?.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))?.firstOrNull()
            if (arVoice != null) t.voice = arVoice else try { t.language = Locale("ar") } catch (e: Exception) {}
        } else if (chosen != null) {
            t.voice = chosen
        }
        t.setSpeechRate(ttsRate.coerceIn(0.25f, 3.0f))
    }

    // ---------------- native TTS engine (survives backgrounding) ----------------

    private fun bodyParagraphs(html: String): List<String> {
        val cleaned = html.replace(Regex("""<h2 class="ch-title">.*?</h2>""", RegexOption.DOT_MATCHES_ALL), "")
        val noImg = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)   // fromHtml turns <img> into a placeholder char = fake paragraph
        val ps = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(cleaned).map { android.text.Html.fromHtml(it.groupValues[1].replace(noImg, ""), 0).toString().trim() }
            .filter { it.isNotEmpty() }.toList()
        if (ps.isNotEmpty()) return ps
        val txt = android.text.Html.fromHtml(cleaned.replace(Regex("""</p>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n"), 0).toString()
        return txt.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun ttsLoadChapter(ch: Int): Boolean {
        if (ch < 0 || ch >= bookChapters.size) return false
        ttsChapter = ch
        ttsChTitle = bookChapters[ch].first
        ttsBody = bodyParagraphs(chapterBody(ch))
        return true
    }
    private fun ttsParaCount() = ttsBody.size + 1
    private fun ttsTextForPara(): String? =
        (if (ttsPara == 0) ttsChTitle.ifBlank { null }
        else ttsBody.getOrNull(ttsPara - 1))?.let { ttsClean(it) }

    // ---- what TTS actually says: links skipped + the user's text rules (the page keeps showing the original) ----
    private val linkRe = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)   // only real links — "website.com" is left alone
    private var ttsRuleList: List<Pair<Regex, String>>? = null

    private fun loadRules(): List<Pair<Regex, String>> {
        ttsRuleList?.let { return it }
        val out = ArrayList<Pair<Regex, String>>()
        try {
            val arr = JSONArray(prefs.getString("ttsRules", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val find = o.optString("find").trim()
                if (find.isEmpty()) continue
                // spaces in the phrase match any run of whitespace; the rest is literal
                val body = find.split(Regex("""\s+""")).joinToString("""\s+""") { Regex.escape(it) }
                // "inside words" ticked → plain substring (Fang Yuan's, Fang Yuans…); otherwise only when not stuck to other letters/digits/apostrophes
                val pat = if (o.optBoolean("any", false)) body
                          else """(?<![\p{L}\p{N}'’])""" + body + """(?![\p{L}\p{N}'’])"""
                out.add(Regex(pat, RegexOption.IGNORE_CASE) to o.optString("repl"))
            }
        } catch (e: Exception) {}
        ttsRuleList = out
        return out
    }

    /** null = nothing left to say (the paragraph is skipped silently). */
    private fun ttsClean(text: String): String? {
        var s = text.replace(linkRe, " ")
        for ((re, rep) in loadRules()) s = re.replace(s) { rep }
        s = s.replace(Regex("""\s{2,}"""), " ").trim()
        return s.ifBlank { null }
    }

    private fun sendRules() {
        val arr = try { JSONArray(prefs.getString("ttsRules", "[]")) } catch (e: Exception) { JSONArray() }
        post(readerView, obj("type" to "rules", "list" to arr))
    }

    private fun splitChunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>(); var s = text.trim()
        while (s.length > max) {
            var cut = -1
            val re = Regex("""[.!?…"”؟؛،]\s""")
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
        applyVoice(text)
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
        if (ttsPaused) ttsResumeNative() else ttsPauseNative()
    }
    /** Idempotent: a second PAUSE (e.g. earbuds sending one on removal) must never resume. */
    private fun ttsPauseNative() {
        if (!ttsPlaying || ttsPaused) return
        ttsPaused = true
        try { tts?.stop() } catch (e: Exception) {}
        notifyState()
    }
    private fun ttsResumeNative() {
        if (!ttsPlaying || !ttsPaused) return
        ttsPaused = false
        speakChunk()
        notifyState()
    }
    private fun ttsStopNative() {
        ttsPlaying = false; ttsPaused = false
        try { tts?.stop() } catch (e: Exception) {}
        TtsService.stop(this)
        post(readerView, obj("type" to "ttsstate", "playing" to false, "stopped" to true))
        if (!isFinishing && !isDestroyed) sendLibrary()      // "Return to book" → "Read" again
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
        prefs.edit().putInt("ttsch_$bookKey", ttsChapter).putInt("ttspa_$bookKey", ttsPara)
            .putInt("resch_$bookKey", ttsChapter).putInt("respa_$bookKey", ttsPara - 1)
            .putFloat("resfr_$bookKey", (ttsPara.toFloat() / ttsParaCount()).coerceIn(0f, 1f)).apply()
    }

    private fun preview(text: String, voice: String, rate: Double) {
        if (ttsPlaying) ttsStopNative()
        ttsVoice = voice; ttsRate = rate.toFloat()
        applyVoice(text)
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

    private fun isTtsBook(folder: File) = ttsPlaying && bookFolder?.absolutePath == folder.absolutePath

    /** Back to the book that is reading aloud. The reader page kept its state and TTS is not touched — just re-sync the highlight. */
    private fun returnToBook() {
        if (bookChapters.isEmpty()) return
        showReader(true)
        readerView.post { revealReader() }                 // same book is already laid out — no need to wait for it
        if (ttsPlaying) { postReaderChapter(); postReaderPos(); notifyState() }
    }

    private fun openNovel(folderPath: String) {
        val folder = resolveFolder(folderPath)
        if (!Library.metaPath(folder).exists()) { toast("Novel not found"); return }
        val meta = Library.loadOrCreate(folder, "", "")
        if (meta.epubUri.isNotEmpty()) { openLinkedNovel(folder, meta); return }
        bookFolder = folder; bookBodies = null; bookImages = null; bookTitle = meta.title; bookKey = "novel:" + folder.name
        prefs.edit().putLong("lastread_$bookKey", System.currentTimeMillis()).apply()
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
    /** A linked novel: the book is read straight from the user's original epub (nothing was copied into the app). */
    private fun openLinkedNovel(folder: File, meta: NovelMeta) {
        toast("Opening…")
        ImportManager.submit(ImportManager.Lane.INTERACTIVE) {
            val book = try { loadBookFrom(Uri.parse(meta.epubUri), true, meta.title) }
                catch (e: Throwable) { toast("Can't open the original file — it was moved, renamed or deleted"); return@submit }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                bookFolder = folder; bookBodies = book.chapters.map { it.second }; bookImages = book.images
                bookChapters = book.chapters.map { it.first to null }
                bookTitle = meta.title; bookKey = "novel:" + folder.name        // same key as before → progress carries over
                prefs.edit().putLong("lastread_$bookKey", System.currentTimeMillis()).apply()
                var coverUrl = ""
                if (meta.cover.isNotBlank()) { val cf = File(folder, meta.cover); if (cf.exists()) coverUrl = Uri.fromFile(cf).toString() }
                ttsStopNative()
                showReader(true)
                sendBook(meta.author, coverUrl)
            }
        }
    }
    private fun openEpubFile(f: File) {
        try { openLoadedEpub(EpubRead.load(f), f.name) } catch (e: Exception) { toast("EPUB error: ${e.message}") }
    }
    private fun openLoadedEpub(book: EpubBook, fileName: String) {
        try {
            bookImages = book.images
            bookFolder = null; bookBodies = book.chapters.map { it.second }
            bookChapters = book.chapters.map { it.first to null }
            bookTitle = book.title; bookKey = "epub:" + fileName
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
            "resumePara" to prefs.getInt("respa_$bookKey", -1),
            "resumeFrac" to prefs.getFloat("resfr_$bookKey", 0f))
        lastBookMsg = msg
        post(readerView, msg)
        sendVoices()
    }
    /** Chapter body as clean one-<p>-per-paragraph HTML — shared by the reader and TTS so paragraph indexes always match. */
    private fun chapterBody(idx: Int): String {
        if (idx < 0 || idx >= bookChapters.size) return ""
        val raw = bookBodies?.let { it[idx] } ?: run {
            val rel = bookChapters[idx].second ?: return ""
            val f = File(bookFolder, rel)
            if (!f.exists()) return "<p>(chapter file missing)</p>"
            EpubRead.bodyInner(f.readText()).replace(Regex("""<h2 class="ch-title">.*?</h2>""", RegexOption.DOT_MATCHES_ALL), "")
        }
        return Paragraphs.normalize(raw)
    }
    private fun chapterHtml(idx: Int): String =
        // illustrations → data: URIs (epub ngimg:// refs or ../images/ files in the novel folder)
        EpubRead.inlineImages(chapterBody(idx), bookImages, bookFolder)
    // The reader sits UNDER the app page in the z-stack. Opening a book keeps the library on screen while the
    // reader loads and lays out the first chapter underneath; only then does the library fade away to reveal
    // a fully painted page. (It used to hide the library immediately and fade in an empty WebView = black flash.)
    private var revealPending = false

    private fun revealReader() {
        if (!revealPending) return
        revealPending = false
        if (readerView.visibility != View.VISIBLE) return
        appView.animate().cancel()
        appView.animate().alpha(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                if (readerView.visibility == View.VISIBLE) { appView.visibility = View.GONE; appView.alpha = 1f }
            }.start()
    }

    private fun showReader(show: Boolean) = runOnUiThread {
        if (show) {
            readerView.animate().cancel()
            appView.animate().cancel()
            readerView.alpha = 1f
            readerView.visibility = View.VISIBLE          // lays out + paints below the still-visible app page
            appView.alpha = 1f
            appView.visibility = View.VISIBLE
            revealPending = true
            readerView.postDelayed({ revealReader() }, 900)   // safety net: never leave the library stuck on screen
            showScraper(false)
        } else {
            revealPending = false
            appView.animate().cancel()
            appView.alpha = 0f
            appView.visibility = View.VISIBLE
            appView.animate().alpha(1f).setDuration(220).start()
            readerView.animate().alpha(0f).setDuration(180).withEndAction {
                if (appView.visibility == View.VISIBLE) { readerView.visibility = View.GONE; readerView.alpha = 1f }
            }.start()
            sendLibrary()        // progress % and the Recent order changed while reading
        }
        applyChrome()
        syncBackCallback()
    }

    // ---------------- scraper peek ----------------

    /** The in-app browser can step back only inside the current browse session, and never mid-download. */
    private fun browserCanGoBack(): Boolean =
        !engine.running && scrapeView.canGoBack() && scrapeView.copyBackForwardList().currentIndex > browseBase

    /** Back = previous page in the website; when there's none left, close the browser and return to the app. */
    private fun browserBackOrExit() {
        if (browserCanGoBack()) scrapeView.goBack() else closeBrowser()
    }

    /** Close = leave the browser for good: hide it AND throw the WebView away so nothing carries over. */
    private fun closeBrowser() {
        setPeek(false)
        resetScrapeView()
    }

    /**
     * Destroys the in-app browser WebView and swaps in a brand-new one, so the next site starts
     * from a blank page instead of flashing the previous one. Cookies live in the global
     * CookieManager, so logins (NovelUpdates etc.) survive. Skipped while a download is running —
     * the engine is using that WebView.
     */
    private fun resetScrapeView() {
        if (engine.running) return
        val old = scrapeView
        val parent = (old.parent as? android.view.ViewGroup) ?: return
        parent.removeView(old)
        try { old.stopLoading(); old.loadUrl("about:blank"); old.clearHistory(); old.removeAllViews(); old.destroy() } catch (e: Exception) {}
        val fresh = WebView(this)
        fresh.visibility = View.GONE
        sleekScrollbar(fresh)
        parent.addView(fresh, 0, FrameLayout.LayoutParams(-1, -1))   // bottom of the z-stack, like the original
        scrapeView = fresh
        browseBase = 0
        engine.attach(fresh)
    }

    // ---------------- system back (swipe / back button) ----------------

    /** Browser first (step back through the site, then close it), then the reader. Returns false when the system should handle it. */
    private fun handleBack(): Boolean {
        if (browsing) { browserBackOrExit(); return true }
        if (readerView.visibility == View.VISIBLE) {
            // the reader page closes its own open panel / text-rules page first; only if it had none do we leave the book
            readerView.evaluateJavascript("(function(){return window.ngBack?window.ngBack():false})()") { r ->
                if (r != "true") showReader(false)
            }
            return true
        }
        if (seriesOpen && appView.visibility == View.VISIBLE) {
            seriesOpen = false
            post(appView, obj("type" to "closeSeries"))
            syncBackCallback()
            return true
        }
        return false
    }

    /**
     * Targeting API 36, Android no longer calls onBackPressed() for the back gesture/button — apps
     * must register an OnBackInvokedCallback. It's registered only while the browser or reader is
     * open, so on the home screens the system's normal back behaviour (and animation) is untouched.
     */
    private fun syncBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = browsing || readerView.visibility == View.VISIBLE || seriesOpen
        if (want == backCbOn) return
        val dispatcher = onBackInvokedDispatcher
        if (want) {
            val cb = (backCb as? android.window.OnBackInvokedCallback)
                ?: android.window.OnBackInvokedCallback { handleBack() }.also { backCb = it }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else {
            (backCb as? android.window.OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
        }
        backCbOn = want
    }

    private fun resolveNovelTitle(url: String, nt: String, og: String, dt: String): String {
        try {
            val existing = Library.findByKey(Library.novelKey(url))
            if (existing != null) {
                val t = Library.loadOrCreate(existing, "", "").title
                if (t.isNotBlank()) return t
            }
        } catch (e: Exception) {}
        val cleaned = nt.trim().ifBlank { Library.cleanNovelTitle(og.ifBlank { dt }) }
        if (cleaned.isNotBlank()) return cleaned
        val slug = try { Library.novelKey(url).substringAfter('|', "") } catch (e: Exception) { "" }
        if (slug.isNotBlank()) {
            return slug.replace('-', ' ').replace('_', ' ').trim()
                .split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        return url
    }

    private fun showScraper(on: Boolean) = runOnUiThread {
        scrapeView.visibility = if (on || engine.running) View.VISIBLE else View.GONE
        if (!on && !engine.running) setPeek(false)
    }
    /** Smoothly swaps between the app UI and the scraper website (for captchas / site browsing). */
    private fun setPeek(on: Boolean) = runOnUiThread {
        browsing = on
        if (on) {
            scrapeView.visibility = View.VISIBLE
            appView.animate().alpha(0f).setDuration(220)
                .withEndAction { appView.visibility = View.INVISIBLE }.start()
            // count + Scrape only make sense while browsing (no download running)
            browseScrape.text = if (queuedMode) "Add to Queue" else "⚡ Scrape"
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
        syncBackCallback()
    }

    // ---------------- library / exports ----------------

    @Volatile private var libRefreshPending = false
    /** Chapters land in the library one by one during a download — push the list to the page every couple of seconds.
     *  Imports use a shorter delay so new books appear while the import is still running. Safe from any thread. */
    private fun scheduleLibraryRefresh(delayMs: Long = 2500) {
        if (libRefreshPending) return
        libRefreshPending = true
        appView.postDelayed({ libRefreshPending = false; sendLibrary() }, delayMs)
    }

    // The library list is built on its own thread ("ng-library"), never on the UI thread, and never on
    // the import thread. Requests that arrive while a build is already queued are merged into it.
    private val libPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val libExec = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 3, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "ng-library").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    private val thumbTried = HashSet<String>()       // library thread only

    /** Safe to call from any thread. */
    private fun sendLibrary() {
        val ttsFolder = if (ttsPlaying) (bookFolder?.absolutePath ?: "") else ""     // UI state read once, here
        if (libPending.compareAndSet(false, true)) {
            try { libExec.execute { libPending.set(false); buildAndPostLibrary(ttsFolder) } }
            catch (e: Exception) { libPending.set(false) }
        }
    }

    private fun buildAndPostLibrary(ttsFolder: String) {
        if (!initialized || destroyed) return
        val hist = Library.history()                       // scanned ONCE and shared below (was scanned 2-3x)
        val arr = JSONArray()
        // newest ADDED first (downloaded or imported); the Recent tab re-orders by "read" on the page
        val ordered = hist.sortedWith(
            compareByDescending<NovelMeta> { it.created.ifBlank { it.updated } }.thenBy { it.title.lowercase() })
        val needThumb = ArrayList<NovelMeta>()
        for (m in ordered) {
            var coverUrl = ""
            if (m.cover.isNotBlank()) {
                val cf = File(m.folder, m.cover)
                if (cf.exists()) {
                    // list cards use a small thumbnail instead of decoding the full-size cover for every row
                    val th = Library.thumbFile(m.folder)
                    if (th.exists() && th.lastModified() >= cf.lastModified()) coverUrl = Uri.fromFile(th).toString()
                    else { coverUrl = Uri.fromFile(cf).toString(); needThumb.add(m) }
                }
            }
            arr.put(JSONObject().apply {
                put("read", lastReadOf(m))
                put("title", m.title); put("folder", m.folder.absolutePath); put("chapters", m.chapters.size)
                put("site", try { Uri.parse(m.source).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" })
                put("updated", m.updated.take(10)); put("cover", coverUrl)
                put("cat", m.category); put("pct", progressPct(m)); put("completed", m.completed)
            })
        }
        val (epubs, pdfs) = libShelves()
        post(appView, obj("type" to "lib", "list" to arr,
            "cats" to JSONArray(Library.categories(hist)),
            "epubs" to epubs, "pdfs" to pdfs,
            "autoSort" to prefs.getBoolean("autoSortOnAdd", false),
            "lastFolder" to (prefs.getString("lastFolder", "") ?: ""),
            "ttsFolder" to ttsFolder))
        runOnUiThread { dismissSplash() }                  // first list is on its way → reveal the app

        // old covers without a thumbnail get one in the background; the list refreshes once afterwards
        if (needThumb.isNotEmpty()) {
            val made = java.util.concurrent.atomic.AtomicInteger()
            val todo = needThumb.filter { thumbTried.add(it.folder.path) }
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
            try {
                pool.invokeAll(todo.map { m -> java.util.concurrent.Callable {
                    // something newer was requested (or we're closing) — stop; don't hold the next refresh up
                    if (!libPending.get() && !destroyed && Library.makeThumb(m.folder, m.cover)) made.incrementAndGet()
                    Unit
                } })
            } finally { pool.shutdown() }
            if (made.get() > 0) sendLibrary()
        }
    }

    /** When the novel was last opened with Read (ms). Books opened before this existed but with a saved position count as "old" (1). */
    private fun lastReadOf(m: NovelMeta): Long {
        val key = "novel:" + m.folder.name
        val t = prefs.getLong("lastread_$key", 0L)
        return if (t > 0) t else if (prefs.contains("resch_$key")) 1L else 0L
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

    /** Exported epub/pdf files across the library (novel folders + root) for the Formats shelves — one directory walk. */
    private fun libShelves(): Pair<JSONArray, JSONArray> {
        val epubs = JSONArray(); val pdfs = JSONArray()
        fun add(f: File, sub: String) {
            val target = when {
                f.name.endsWith(".epub", true) -> epubs
                f.name.endsWith(".pdf", true) -> pdfs
                else -> return
            }
            target.put(JSONObject().apply { put("path", f.absolutePath); put("name", f.name); put("sub", sub) })
        }
        val root = Library.root
        if (root.exists()) {
            val kids = root.listFiles() ?: return epubs to pdfs
            kids.filter { it.isFile }.forEach { add(it, "Library") }
            kids.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { d ->
                d.listFiles()?.filter { it.isFile }?.forEach { add(it, d.name) }
            }
        }
        PdfLinks.all().forEach { l ->                       // PDFs that were linked (not copied) on import
            pdfs.put(JSONObject().apply { put("path", l.uri); put("name", l.name); put("sub", "Original file") })
        }
        return epubs to pdfs
    }

    // ---------------- themed dialogs (stock AlertDialog looks ancient) ----------------

    /** Colours of the native dialogs for the current app theme (mirrors --card/--border/--text/--sub/--accent/--danger in app.html). */
    private class DlgPal(val card: Int, val stroke: Int, val text: Int, val sub: Int, val input: Int,
                         val accent: Int, val danger: Int, val dangerBtn: Int, val onAccent: Int = Color.WHITE)

    private fun pal(): DlgPal = when (curTheme()) {
        "light"     -> DlgPal(hx("#FFFFFF"), hx("#E3E6EB"), hx("#20242C"), hx("#6B7280"), hx("#F2F4F8"), hx("#2563EB"), hx("#DC2626"), hx("#DC2626"))
        "sepia"     -> DlgPal(hx("#EDE2C8"), hx("#D5C5A2"), hx("#3D2F1E"), hx("#7A6A52"), hx("#F3EAD6"), hx("#9C6B2F"), hx("#C0392B"), hx("#C0392B"))
        "darksepia" -> DlgPal(hx("#2C251C"), hx("#453A2B"), hx("#E8DCC8"), hx("#A6957D"), hx("#1F1A13"), hx("#C89858"), hx("#E06B5A"), hx("#D0584A"))
        else        -> DlgPal(hx("#1C2029"), hx("#2A303C"), hx("#E7EAF0"), hx("#9099A9"), hx("#161920"), hx("#6C8CFF"), hx("#FF6B6B"), hx("#E5484D"))
    }
    private fun hx(hex: String) = Color.parseColor(hex)
    /** Same colour at ~14% opacity — the tint behind a danger row. */
    private fun tint(color: Int) = (color and 0x00FFFFFF) or 0x24000000

    private fun dlgBg() = android.graphics.drawable.GradientDrawable().apply {
        val p = pal()
        cornerRadius = dp(22).toFloat()
        setColor(p.card)
        setStroke(dp(1), p.stroke)
    }

    private fun dlgTitle(text: String) = android.widget.TextView(this).apply {
        this.text = text
        setTextColor(pal().text); textSize = 16.5f
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
            if (bg == 0x00000000) setStroke(dp(1), pal().stroke)
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
                setTextColor(if (danger) pal().danger else pal().text)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(if (danger) tint(pal().danger) else 0x00000000)
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
            setTextColor(pal().text); textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        lateinit var dlg: AlertDialog
        var answered = false
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
            addView(pillBtn("Cancel", 0x00000000, pal().sub) { answered = true; dlg.dismiss(); onNo?.invoke() })
            addView(pillBtn(yesLabel, if (danger) pal().dangerBtn else pal().accent, pal().onAccent)
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
        setTextColor(pal().text); textSize = 14.5f
        setHintTextColor(pal().sub)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(pal().input)
            setStroke(dp(1), pal().stroke)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // ---- categories ----

    // A "category" is a SERIES: its books are grouped into one card in the library.

    private fun moveCatDialog(folder: File) {
        val cur = if (Library.metaPath(folder).exists()) Library.loadOrCreate(folder, "", "").category else ""
        val cats = Library.categories()
        val items = cats.map { it to false } + ("New collection…" to false) +
            (if (cur.isNotEmpty()) listOf("Take out of “$cur”" to false) else emptyList())
        ngMenu("Add to collection", items) { w ->
            when {
                w < cats.size -> { Library.setCategory(folder, cats[w]); sendLibrary() }
                w == cats.size -> newCatDialog { name -> Library.setCategory(folder, name); sendLibrary() }
                else -> { Library.setCategory(folder, ""); sendLibrary() }
            }
        }
    }

    /** The ⋯ button on a series card. */
    private fun seriesMenuDialog(name: String) {
        if (name.isBlank()) return
        ngMenu(name, listOf("Rename collection" to false, "Ungroup books" to true)) { w ->
            when (w) {
                0 -> newCatDialog(title = "Rename collection", hint = "Collection name", initial = name, action = "Rename") { nn ->
                    Library.renameSeries(name, nn)
                    post(appView, obj("type" to "renamedSeries", "from" to name, "to" to nn.trim()))
                }
                1 -> ngConfirm("Ungroup “$name”?\nThe books stay in your library as separate cards.", "Ungroup", danger = true) {
                    Library.ungroupSeries(name); sendLibrary()
                }
            }
        }
    }

    private fun newCatDialog(title: String = "New collection", hint: String = "Collection name", initial: String = "",
                             action: String = "Create", then: ((String) -> Unit)? = null) {
        val input = ngEdit(hint).apply { if (initial.isNotEmpty()) { setText(initial); setSelection(initial.length) } }
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(dlgTitle(title))
            addView(input)
        }
        lateinit var dlg: AlertDialog
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
            addView(pillBtn("Cancel", 0x00000000, pal().sub) { dlg.dismiss() })
            addView(pillBtn(action, pal().accent, pal().onAccent) {
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { then?.invoke(name); sendLibrary() }
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
        runOnUiThread { applyChrome() }
    }

    /** Page background of each theme — must match --bg in app.html / reader.html (they differ slightly for sepia and light). */
    private fun chromeColor(theme: String, reader: Boolean): Int = Color.parseColor(when (theme) {
        "darksepia" -> "#1F1A13"
        "sepia" -> if (reader) "#F4ECDA" else "#F3EAD6"
        "light" -> if (reader) "#F7F8FA" else "#F2F4F8"
        else -> "#0F1116"
    })

    /**
     * Paints everything native that sits around the WebViews with the current theme: the window, the root view
     * (this is what shows behind the status/nav bars on Android 15+ edge-to-edge), the bar colors themselves
     * (Android 14 and older), and the bar icons (dark icons on the two light themes).
     */
    @Suppress("DEPRECATION")   // statusBarColor / navigationBarColor are ignored on Android 15+ but still drive Android 14 and older
    private fun applyChrome() {
        val theme = curTheme()
        val c = chromeColor(theme, readerView.visibility == View.VISIBLE)
        val light = theme == "sepia" || theme == "light"
        rootView.setBackgroundColor(c)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c))
        window.statusBarColor = c
        window.navigationBarColor = c
        appView.setBackgroundColor(chromeColor(theme, false))
        readerView.setBackgroundColor(chromeColor(theme, true))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** The catbar's ⋯ chip: file shelves + import tools (series are cards in the list now, not shelves). */
    private fun moreCatsDialog() {
        val items = listOf("EPUB files", "PDF files", "📥 Import folder…", "🔄 Convert loose EPUBs to novels")
        fun shelf(cat: String) = post(appView, obj("type" to "setShelf", "cat" to cat))
        ngMenu("More", items.map { it to false }) { w ->
            when {
                items[w] == "EPUB files" -> shelf("fmt:epub")
                items[w] == "PDF files" -> shelf("fmt:pdf")
                items[w].startsWith("📥") -> pickImportFolder()
                else -> convertLooseEpubs()
            }
        }
    }

    /** Full action sheet for a novel — the card's ⋯ button. */
    private fun novelMenuDialog(folder: File) {
        if (!Library.metaPath(folder).exists()) { toast("Novel not found"); return }
        val meta = Library.loadOrCreate(folder, "", "")
        val inCol = meta.category.isNotEmpty()
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val fwd = { cmd: String -> handleApp(JSONObject().put("cmd", cmd).put("folder", folder.absolutePath)) }
        actions.add("▶  Read" to { if (isTtsBook(folder)) returnToBook() else openNovel(folder.absolutePath) })
        actions.add("Export EPUB" to { toast("Working… saving to Downloads"); fwd("epub") })
        actions.add("Export PDF" to { toast("Working… saving to Downloads"); fwd("pdf") })
        actions.add("Share" to { fwd("share") })
        actions.add((if (inCol) "Move to another collection…" else "Add to collection…") to { moveCatDialog(folder) })
        if (inCol) {
            actions.add("Remove from “${meta.category}”" to {
                val old = Library.removeFromSeries(folder)
                toast(if (old.isNotEmpty()) "Removed from “$old”" else "Removed from collection")
                sendLibrary()
            })
        }
        actions.add((if (meta.completed) "Mark as not completed" else "Mark as completed") to {
            Library.setCompleted(folder, !meta.completed); sendLibrary()
        })
        actions.add("Delete" to {
            ngConfirm(if (meta.epubUri.isNotEmpty()) "Remove “${meta.title}” from your library?\nYour original EPUB file is not touched."
                      else "Delete “${meta.title}” and all its files?", if (meta.epubUri.isNotEmpty()) "Remove" else "Delete", danger = true)
                { folder.deleteRecursively(); sendLibrary() }
        })
        ngMenu(meta.title, actions.mapIndexed { idx, p -> p.first to (idx == actions.lastIndex) }) { w ->
            actions.getOrNull(w)?.second?.invoke()
        }
    }

    /** Runs auto-sort on a background thread while showing the "Sorting books…" popup so the UI never freezes. */
    private fun runAutoSort(announceWhenEmpty: Boolean = true) {
        if (sorting) return
        sorting = true
        post(appView, obj("type" to "sorting", "on" to true))
        val t0 = SystemClock.uptimeMillis()
        ImportManager.submit(ImportManager.Lane.INTERACTIVE) {
            var g = 0; var n = 0
            try {
                val r = Library.autoSort()
                g = r.first; n = r.second
            } catch (e: Throwable) {}
            val wait = (400L - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(0L)
            if (wait > 0L) try { Thread.sleep(wait) } catch (e: Exception) {}
            sorting = false
            post(appView, obj("type" to "sorting", "on" to false))
            if (g > 0) toast("Filed $n novels into $g collections")
            else if (announceWhenEmpty) toast("No similar titles found")
            sendLibrary()
        }
    }

    /** Paste-any-text TTS — opens the READER in input mode (not a popup). */
    private fun openSpeakMode() {
        showReader(true)
        post(readerView, obj("type" to "speakMode"))
    }

    private fun speakFreeText(textIn: String) {
        val text = ttsClean(textIn) ?: return
        if (ttsPlaying) ttsStopNative()
        applyVoice(text)
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
        if (m.completed) return
        ngConfirm("You reached the end of “${m.title}”.\nMove it to Completed?", "Move")
            { Library.setCompleted(folder, true); sendLibrary() }
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
                showScraper(true)
                if (!engine.running) ScrapeService.start(this)    // foreground service: keeps the download alive in the background
                engine.start(scope, url, m.optInt("count", 50))
            }
            "stop" -> engine.stop()
            "peek" -> { browseBase = scrapeView.copyBackForwardList().currentIndex; setPeek(true) }
            "openSite" -> {
                val u = m.optString("url")
                if (u.startsWith("http")) {
                    if (engine.running) { toast("Stop the current download before browsing another site"); return }
                    resetScrapeView()          // fresh WebView: no leftover page from the last site
                    browseBase = 0
                    scrapeView.loadUrl(u); setPeek(true)
                }
            }
            "lib" -> sendLibrary()
            "read" -> { val f = resolveFolder(m.optString("folder")); if (isTtsBook(f)) returnToBook() else openNovel(m.optString("folder")) }
            "returnBook" -> returnToBook()
            "importFolder" -> pickImportFolder()
            "openEpub" -> pickEpub(REQ_EPUB_READ)
            "epub2pdf" -> pickEpub(REQ_EPUB_PDF)
            "delete" -> { resolveFolder(m.optString("folder")).deleteRecursively(); sendLibrary() }
            "deleteAll" -> { Library.history().forEach { it.folder.deleteRecursively() }; sendLibrary() }
            "moveCat" -> moveCatDialog(resolveFolder(m.optString("folder")))
            "newCat" -> newCatDialog()
            "seriesMenu" -> seriesMenuDialog(m.optString("name"))
            "seriesOpen" -> { seriesOpen = m.optBoolean("open"); syncBackCallback() }
            "removeFromSeries" -> {
                val old = Library.removeFromSeries(resolveFolder(m.optString("folder")))
                toast(if (old.isNotEmpty()) "Removed from “$old”" else "Removed from collection")
                sendLibrary()
            }
            "autoSort" -> runAutoSort(true)
            "speakText" -> openSpeakMode()
            "setTheme" -> setTheme(m.optString("theme", "dark"))
            "setAutoSort" -> prefs.edit().putBoolean("autoSortOnAdd", m.optBoolean("on")).apply()
            "setQueuedMode" -> {
                queuedMode = m.optBoolean("on", false)
                browseScrape.text = if (queuedMode) "Add to Queue" else "⚡ Scrape"
            }
            "moreCats" -> moreCatsDialog()
            "openFile" -> {
                val rawPath = m.optString("path")
                if (rawPath.startsWith("content://")) {          // a linked PDF: open the original in the user's PDF viewer
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(rawPath), "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (e: Exception) { toast("Can't open it — no PDF viewer, or the original was moved or deleted") }
                    return
                }
                val f = File(rawPath)
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
            "epub" -> scope.launch(Dispatchers.IO) { try { val meta = Library.loadOrCreate(resolveFolder(m.optString("folder")), "", ""); val f = if (meta.epubUri.isNotEmpty()) copyOriginal(meta) else EpubWriter.build(meta); val w = saveToDownloads(f, "application/epub+zip"); if (meta.epubUri.isNotEmpty()) f.delete(); toast("EPUB saved to $w") } catch (e: Exception) { toast("EPUB failed: ${e.message}") } }
            "pdf" -> scope.launch(Dispatchers.IO) { try { val meta = Library.loadOrCreate(resolveFolder(m.optString("folder")), "", ""); val tmpRoot = if (meta.epubUri.isNotEmpty()) File(cacheDir, "pdfwork_${System.nanoTime()}") else null; try { val src = if (tmpRoot != null) Library.loadOrCreate(EpubImport.fromBook(loadBookFrom(Uri.parse(meta.epubUri), true, meta.title), "tmp", tmpRoot), "", "") else meta; val f = exportPdfOf(src); val w = saveToDownloads(f, "application/pdf"); toast("PDF saved to $w") } finally { tmpRoot?.deleteRecursively() } } catch (e: Exception) { toast("PDF failed: ${e.message}") } }
            "share" -> scope.launch(Dispatchers.IO) { try { val meta = Library.loadOrCreate(resolveFolder(m.optString("folder")), "", ""); val ex = meta.folder.listFiles()?.firstOrNull { it.extension == "epub" }; val f = if (meta.epubUri.isNotEmpty()) copyOriginal(meta) else (ex ?: EpubWriter.build(meta)); withContext(Dispatchers.Main) { shareFile(f, "application/epub+zip") } } catch (e: Exception) { toast("Share failed: ${e.message}") } }
        }
    }

    inner class ReaderBridge { @JavascriptInterface fun msg(json: String) { val m = try { JSONObject(json) } catch (e: Exception) { return }; runOnUiThread { handleReader(m) } } }

    private fun handleReader(m: JSONObject) {
        when (m.optString("cmd")) {
            "ready" -> { sendVoices(); lastBookMsg?.let { post(readerView, it) } }
            "saveRules" -> { prefs.edit().putString("ttsRules", (m.optJSONArray("rules") ?: JSONArray()).toString()).apply(); ttsRuleList = null }
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
            "savepos" -> { if (m.optString("key") == bookKey) prefs.edit().putInt("resch_$bookKey", m.optInt("idx", 0)).putInt("respa_$bookKey", m.optInt("p", -1)).putFloat("resfr_$bookKey", m.optDouble("r", 0.0).toFloat()).apply() }
            "close" -> { showReader(false) }
            "shown" -> revealReader()
        }
    }

    /** The notification's Stop button. */
    fun stopDownloadFromService() = runOnUiThread { engine.stop() }

    fun onTtsServiceReady() = runOnUiThread { if (ttsPlaying) notifyState() }

    fun onTtsControl(action: String) = runOnUiThread {
        when (action) {
            TtsService.ACTION_TOGGLE -> ttsToggleNative()
            TtsService.ACTION_PLAY -> ttsResumeNative()
            TtsService.ACTION_PAUSE -> ttsPauseNative()
            TtsService.ACTION_NEXT -> ttsSkipChapter(1)
            TtsService.ACTION_PREV -> ttsSkipChapter(-1)
            TtsService.ACTION_PARA_NEXT -> ttsSkipPara(1)
            TtsService.ACTION_PARA_PREV -> ttsSkipPara(-1)
            TtsService.ACTION_STOP -> ttsStopNative()
        }
    }

    private fun pickEpub(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        @Suppress("DEPRECATION") startActivityForResult(i, code)
    }

    // ---------------- batch import (folder of EPUB / PDF) ----------------

    private fun pickImportFolder() {
        if (importing) { toast("An import is already running"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMPORT_DIR) }
        catch (e: Exception) { toast("No folder picker available on this device") }
    }

    private class ImportItem(val uri: Uri, val name: String, val ext: String, val size: Long)

    /** Identity of an imported file (name + size) so the same file is never imported twice. */
    private fun importKey(name: String, size: Long) = "import|" + name.lowercase() + "|" + size

    private fun copyToFile(uri: Uri, dest: File) {
        contentResolver.openInputStream(uri)!!.use { i ->
            dest.outputStream().buffered(64 * 1024).use { o -> i.copyTo(o, 64 * 1024) }
        }
    }

    // ---------------- importing runs on the dedicated import thread (see ImportManager) ----------------
    // The UI thread only picks the file/folder and shows toasts; copying, parsing and writing happen on
    // "ng-import", which only exists while there is something to import. A single book is queued in the
    // INTERACTIVE lane (jumps ahead of a running folder import); folder imports use the BULK lane.

    /** Several workers update these at once. */
    private class BatchStats {
        val novelsN = java.util.concurrent.atomic.AtomicInteger(); val pdfsN = java.util.concurrent.atomic.AtomicInteger()
        val asFilesN = java.util.concurrent.atomic.AtomicInteger(); val dupesN = java.util.concurrent.atomic.AtomicInteger()
        val failedN = java.util.concurrent.atomic.AtomicInteger()
        val relinkedN = java.util.concurrent.atomic.AtomicInteger()          // old copies turned into links
        val freedN = java.util.concurrent.atomic.AtomicLong()                // bytes given back by deleting those copies
        val relinked get() = relinkedN.get()
        val novels get() = novelsN.get(); val pdfs get() = pdfsN.get(); val asFiles get() = asFilesN.get()
        val dupes get() = dupesN.get(); val failed get() = failedN.get()
    }

    /** Keys of everything already imported — touched by every import worker, so it's synchronized. */
    private val importKeys: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Novels an older version copied into the app, by import key — they are converted to links when their file is imported again. */
    private val copiedByKey = java.util.concurrent.ConcurrentHashMap<String, File>()

    /** A giant epub (hundreds of illustrations) is parsed alone so several of them can't exhaust the heap together. */
    private val heavyImport = java.util.concurrent.Semaphore(1)
    private val HEAVY_BYTES = 40L * 1024 * 1024

    /**
     * Walks the picked folder (sub-folders included).
     *  • every .epub becomes a REGULAR library novel — cover, author, chapters, progress, categories,
     *    the ⋯ menu — exactly like a downloaded one (see EpubImport);
     *  • .pdf files are copied in as files (a PDF has no chapters to split).
     */
    private fun importFolder(tree: Uri) {
        if (importing) return
        importing = true
        toast("Scanning folder…")
        ImportManager.submit(ImportManager.Lane.BULK) {
            try { scanAndQueue(tree) }
            catch (e: Throwable) { toast("Import failed: ${e.message}"); importing = false; sendLibrary() }
        }
    }

    private fun scanAndQueue(tree: Uri) {
        val found = ArrayList<ImportItem>()
        fun scan(docId: String, depth: Int) {
            if (depth > 8) return
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE)
            contentResolver.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { scan(id, depth + 1); continue }
                    val ext = when {
                        name.endsWith(".epub", true) || mime == "application/epub+zip" -> "epub"
                        name.endsWith(".pdf", true) || mime == "application/pdf" -> "pdf"
                        else -> continue
                    }
                    found.add(ImportItem(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, ext,
                        if (c.isNull(3)) -1L else c.getLong(3)))
                }
            }
        }
        scan(DocumentsContract.getTreeDocumentId(tree), 0)

        if (found.isEmpty()) { toast("No EPUB or PDF files found in that folder"); importing = false; return }
        toast("Importing ${found.size} file(s)…")

        val root = Library.root.apply { mkdirs() }
        copiedByKey.clear()
        Library.history().forEach {
            if (it.key.isNotEmpty()) importKeys.add(it.key)
            // a novel an OLD version copied into the app: re-importing its folder turns it into a link and frees the space
            if (it.key.startsWith("import|") && it.epubUri.isEmpty() && it.chapters.any { c -> c.file.isNotEmpty() })
                copiedByKey[it.key] = it.folder
        }
        val st = BatchStats()
        val total = found.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        val lastNote = java.util.concurrent.atomic.AtomicLong()
        // one queue entry per file, picked up by several workers at once; a single-book import can still slip in between two files.
        // The LAST file to finish (whichever worker that is) wraps the batch up — with a pool "queued last" no longer means "finished last".
        found.forEach { item ->
            ImportManager.submit(ImportManager.Lane.BULK) {
                try {
                    if (!destroyed) importOne(item, root, st)
                } finally {
                    val n = done.incrementAndGet()
                    scheduleLibraryRefresh(1500)                       // live: new books show up while the import runs
                    if (n >= total) finishBatch(st)
                    else {
                        val t = System.currentTimeMillis(); val prev = lastNote.get()
                        if (t - prev > 500 && lastNote.compareAndSet(prev, t))
                            post(appView, obj("type" to "toast", "msg" to "Importing $n / $total…"))
                    }
                }
            }
        }
    }

    /**
     * Nothing is copied: an EPUB becomes a "linked" library novel (title, author, cover, chapter list are read from the
     * original IN PLACE; the book itself stays where it is) and a PDF is remembered as a link.
     */
    private fun importOne(item: ImportItem, root: File, st: BatchStats) {
        val base = Library.sanitize(item.name.removeSuffix("." + item.name.substringAfterLast('.', "")))
        if (item.ext == "epub") {
            val key = importKey(item.name, item.size)
            // already in the library as a COPY made by an older version → make it a link and give the space back
            val copied = copiedByKey.remove(key)
            if (copied != null) {
                try {
                    val book = loadBookFrom(item.uri, withImages = false, fallbackTitle = base)
                    val freed = EpubImport.relink(copied, book, item.uri.toString())
                    if (freed >= 0) { st.relinkedN.incrementAndGet(); st.freedN.addAndGet(freed) } else st.dupesN.incrementAndGet()
                } catch (e: Throwable) { st.dupesN.incrementAndGet() }
                return
            }
            if (!importKeys.add(key)) { st.dupesN.incrementAndGet(); return }     // claim the key up front: two workers can't import the same file
            var imported = false
            try {
                val book = loadBookFrom(item.uri, withImages = false, fallbackTitle = base)
                EpubImport.linkNovel(book, key, item.uri.toString())
                imported = true; st.novelsN.incrementAndGet()
                val loose = File(root, "$base.epub")      // lumped copy left behind by an even older import
                if (loose.exists() && (item.size <= 0 || loose.length() == item.size)) loose.delete()
            } catch (e: Throwable) { st.failedN.incrementAndGet() }
            finally { if (!imported) importKeys.remove(key) }      // a later retry may try again
        } else {
            if (!PdfLinks.add(item.name, item.uri.toString(), item.size)) { st.dupesN.incrementAndGet(); return }
            val loose = File(root, "$base.pdf")           // copy made by an older import → the link replaces it
            if (loose.exists() && item.size > 0 && loose.length() == item.size) {
                st.freedN.addAndGet(loose.length()); loose.delete(); st.relinkedN.incrementAndGet()
            } else st.pdfsN.incrementAndGet()
        }
    }

    /** Opens the epub IN PLACE (no copy). Falls back to a temporary copy only if the provider can't seek. */
    private fun openEpubZip(uri: Uri): EpubZip {
        val pfd = contentResolver.openFileDescriptor(uri, "r")     // throws when the file is gone or access was lost
        if (pfd != null) {
            try { return ChannelZip(pfd) } catch (e: Throwable) { try { pfd.close() } catch (x: Exception) {} }
        }
        val tmp = File(cacheDir, "open_${System.nanoTime()}.epub")
        copyToFile(uri, tmp)
        return try { TempFileZip(tmp) } catch (e: Throwable) { tmp.delete(); throw e }
    }

    private fun loadBookFrom(uri: Uri, withImages: Boolean, fallbackTitle: String): EpubBook =
        openEpubZip(uri).use { EpubRead.load(it, withImages, fallbackTitle) }

    /** A fresh copy of a linked novel's original epub (for Export / Share), in cache/share. */
    private fun copyOriginal(meta: NovelMeta): File {
        val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val dest = File(dir, Library.sanitize(meta.title) + ".epub")
        copyToFile(Uri.parse(meta.epubUri), dest)
        return dest
    }

    private fun finishBatch(st: BatchStats) {
        // same as a download: file the new novels into series if the user enabled that in Settings
        if (st.novels > 0 && prefs.getBoolean("autoSortOnAdd", false)) {
            post(appView, obj("type" to "sorting", "on" to true))
            try { Library.autoSort() } catch (e: Exception) {}
            post(appView, obj("type" to "sorting", "on" to false))
        }
        importing = false
        sendLibrary()
        val parts = ArrayList<String>()
        if (st.novels > 0) parts.add(if (st.novels == 1) "1 novel" else "${st.novels} novels")
        if (st.pdfs > 0) parts.add("${st.pdfs} PDF")
        if (st.asFiles > 0) parts.add("${st.asFiles} unreadable EPUB kept as file")
        val notes = ArrayList<String>()
        if (parts.isNotEmpty()) notes.add("Imported " + parts.joinToString(" + "))
        if (st.relinked > 0) notes.add("Linked ${st.relinked} existing to the originals · freed " +
            android.text.format.Formatter.formatFileSize(this, st.freedN.get()))
        if (notes.isEmpty()) notes.add("Nothing new imported")
        if (st.dupes > 0) notes.add("${st.dupes} already in library")
        if (st.failed > 0) notes.add("${st.failed} failed")
        toast(notes.joinToString(" · "))
        when {
            st.novels > 0 -> post(appView, obj("type" to "setShelf", "cat" to "All"))
            st.pdfs > 0 -> post(appView, obj("type" to "setShelf", "cat" to "fmt:pdf"))
            st.asFiles > 0 -> post(appView, obj("type" to "setShelf", "cat" to "fmt:epub"))
        }
    }

    /**
     * One-tap fix for EPUBs imported by the OLD batch import (loose files under "EPUB files"):
     * converts each into a regular novel with cover + metadata, then removes the loose copy.
     */
    private fun convertLooseEpubs() {
        if (importing) { toast("An import is already running"); return }
        val loose = Library.root.listFiles()?.filter { it.isFile && it.name.endsWith(".epub", true) }.orEmpty()
        if (loose.isEmpty()) { toast("No loose EPUB files to convert"); return }
        importing = true
        toast("Converting ${loose.size} EPUB(s)…")
        val st = BatchStats()
        val total = loose.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        val lastNote = java.util.concurrent.atomic.AtomicLong()
        loose.forEach { f ->
            ImportManager.submit(ImportManager.Lane.BULK) {
                val heavy = f.length() > HEAVY_BYTES
                try {
                    if (!destroyed) {
                        if (heavy) heavyImport.acquireUninterruptibly()
                        try {
                            EpubImport.toNovel(f, importKey(f.name, f.length()))
                            f.delete(); st.novelsN.incrementAndGet()
                        } catch (e: Throwable) { st.failedN.incrementAndGet() }       // unreadable → left where it is
                        finally { if (heavy) heavyImport.release() }
                    }
                } finally {
                    val n = done.incrementAndGet()
                    if (n >= total) {
                        if (st.novels > 0 && prefs.getBoolean("autoSortOnAdd", false)) {
                            post(appView, obj("type" to "sorting", "on" to true))
                            try { Library.autoSort() } catch (e: Exception) {}
                            post(appView, obj("type" to "sorting", "on" to false))
                        }
                        importing = false
                        sendLibrary()
                        toast(if (st.novels == 0) "Nothing could be converted" else "Converted ${st.novels} EPUB(s) into novels" + (if (st.failed > 0) " · ${st.failed} couldn't be read" else ""))
                        if (st.novels > 0) post(appView, obj("type" to "setShelf", "cat" to "All"))
                    } else {
                        val t = System.currentTimeMillis(); val prev = lastNote.get()
                        if (t - prev > 500 && lastNote.compareAndSet(prev, t))
                            post(appView, obj("type" to "toast", "msg" to "Converting $n / $total…"))
                    }
                }
            }
        }
    }

    private fun queryNameSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) {}
        return name to size
    }

    /**
     * "Open EPUB": the book is added to the library as a LINKED novel (the file itself is not copied), then opened
     * from the library. Same file picked again = the existing entry, no duplicate. An old copy of the same book
     * is turned into a link (and its space freed). If it can't be linked it is simply read directly.
     *
     * Runs in the INTERACTIVE lane of the import pool: it never waits behind a folder import, and the UI thread does
     * none of the parsing. The reader opens as soon as the book is known — auto-sorting and the library refresh come after.
     */
    private fun addAndOpenEpub(uri: Uri, fallbackName: String) {
        toast("Opening…")
        ImportManager.submit(ImportManager.Lane.INTERACTIVE) {
            val nameSize = queryNameSize(uri)
            var size = nameSize.second
            if (size <= 0) size = try { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
            val key = importKey(nameSize.first, size)
            val title = nameSize.first.removeSuffix("." + nameSize.first.substringAfterLast('.', ""))
            var folder: File? = null
            var added = false
            try {
                val existing = Library.history().firstOrNull { it.key == key }
                if (existing != null) {
                    folder = existing.folder
                    if (existing.epubUri.isEmpty() && existing.chapters.any { it.file.isNotEmpty() }) {
                        try {                                     // old copy → link it, free the space
                            val freed = EpubImport.relink(existing.folder, loadBookFrom(uri, false, title), uri.toString())
                            if (freed > 0) toast("Freed " + android.text.format.Formatter.formatFileSize(this, freed))
                        } catch (e: Throwable) {}
                    } else if (existing.epubUri.isNotEmpty() && existing.epubUri != uri.toString()) {
                        existing.epubUri = uri.toString()         // same book picked from a new location → follow it
                        Library.save(existing, bumpUpdated = false)
                    }
                } else {
                    folder = EpubImport.linkNovel(loadBookFrom(uri, false, title), key, uri.toString())
                    importKeys.add(key)
                    added = true
                }
            } catch (e: Throwable) { folder = null }
            val f = folder
            val wasAdded = added
            val fallback: EpubBook? = if (f == null) try { loadBookFrom(uri, true, title) } catch (e: Throwable) { null } else null
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (f != null) {
                    if (wasAdded) toast("Added to your library")
                    openNovel(f.absolutePath)
                } else if (fallback != null) openLoadedEpub(fallback, nameSize.first)
                else toast("Couldn't read this EPUB")
            }
            sendLibrary()
            if (wasAdded && prefs.getBoolean("autoSortOnAdd", false)) {
                post(appView, obj("type" to "sorting", "on" to true))
                try { Library.autoSort() } catch (e: Exception) {}
                post(appView, obj("type" to "sorting", "on" to false))
                sendLibrary()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // a result can arrive before initApp() has run (activity recreated while the picker was open)
        if (!initialized) { pendingResult = Triple(requestCode, resultCode, data); return }
        handleActivityResult(requestCode, resultCode, data)
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // the library links to the ORIGINAL files, so access must survive restarts
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        if (requestCode == REQ_IMPORT_DIR) { importFolder(uri); return }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "book.epub"
        when (requestCode) {
            REQ_EPUB_READ -> addAndOpenEpub(uri, name)
            REQ_EPUB_PDF -> scope.launch(Dispatchers.IO) {
                val tmp = File(cacheDir, Library.sanitize(name).ifBlank { "book.epub" })
                try { copyToFile(uri, tmp) } catch (e: Exception) { toast("Couldn't read file: ${e.message}"); return@launch }
                try { val book = EpubRead.load(tmp); val out = File(cacheDir, Library.sanitize(book.title) + ".pdf"); PdfWriter.write(book.title, book.author, book.chapters.map { it.first to PdfWriter.htmlToText(it.second) }, out); val w = saveToDownloads(out, "application/pdf"); toast("PDF saved to $w") } catch (e: Exception) { toast("Convert failed: ${e.message}") }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // tapped the "reading aloud" notification → straight back into the book
        if (intent.getBooleanExtra("openReader", false)) { if (initialized) returnToBook() else pendingReturnToBook = true }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (!initialized) return
        if (appView.visibility == View.VISIBLE) sendLibrary()      // show whatever changed while we were away
        // returning from background: re-sync the reader to what TTS is currently reading
        if (ttsPlaying && readerView.visibility == View.VISIBLE) { postReaderChapter(); postReaderPos(); notifyState() }
    }

    override fun onStop() {
        super.onStop()
        if (!initialized) return
        // app left the screen mid-download: stop Chromium from throttling the scraper page's timers
        if (engine.running) { try { scrapeView.onResume(); scrapeView.resumeTimers() } catch (e: Exception) {} }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        // persist login cookies (NovelUpdates sessions survive app restarts)
        try { android.webkit.CookieManager.getInstance().flush() } catch (e: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // older Android (and fallback): same priority — browser, then reader, then the system default
        if (!initialized) return
        if (handleBack()) return
        // a download is running: go to the background instead of closing the app (which would kill it)
        if (engine.running) { moveTaskToBack(true); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        destroyed = true
        try { tts?.shutdown() } catch (e: Exception) {}
        TtsService.stop(this)
        ScrapeService.stop(this)
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}
