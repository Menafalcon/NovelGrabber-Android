package com.novelgrabber.app

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * The download engine — a 1:1 port of the desktop flow that was live-verified per site:
 * chapter page → follow Next/increment; contents page → jump to first chapter then mop up
 * the list; novelarrow → click the in-page Next button; kat volumes → volume-aware numbers;
 * SPA stale-content guard so the same chapter is never saved twice.
 */
class Engine(
    private val web: WebView,
    private val log: (String) -> Unit,
    private val friendly: (String) -> Unit,
    private val progress: (Int, Int) -> Unit,
    private val finished: (String) -> Unit
) {
    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }

    @Volatile private var pageFinished = false
    private var job: Job? = null
    val running get() = job?.isActive == true

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // a desktop UA on a mobile WebView makes Cloudflare Turnstile LOOP (fingerprint
            // mismatch). Use the device's real UA with only the "; wv" webview marker removed.
            userAgentString = android.webkit.WebSettings.getDefaultUserAgent(web.context).replace("; wv", "")
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
        }
        // keep login sessions (NovelUpdates etc.) across app restarts
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { pageFinished = true }
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                return if (AdBlock.shouldBlock(u, request.isForMainFrame)) AdBlock.blockedResponse() else null
            }
        }
    }

    fun stop() { job?.cancel() }

    fun start(scope: CoroutineScope, url: String, limitIn: Int) {
        if (running) return
        job = scope.launch(Dispatchers.Main) {
            try { run(url, limitIn) }
            catch (e: CancellationException) { friendly("Stopped"); log("Cancelled by user."); finished("stopped") }
            catch (e: Exception) { friendly("Something went wrong — open details"); log("Error: ${e.message}"); finished("error") }
        }
    }

    // ---------------- main flow ----------------

    private suspend fun run(start: String, limitIn: Int) {
        val uri = Uri.parse(start)
        var limit = limitIn
        if (limit <= 0) limit = 1_000_000
        val rule = SiteRules.forHost(uri.host)
        val key = Library.novelKey(start)
        val novelSlug = key.substringAfter('|', "")

        friendly("Opening the page…")
        log("Opening $start")
        val first = navExtract(start, rule, null)
        if (first == null) { friendly("Couldn't read that page"); log("No response from page."); finished("error"); return }

        var novelTitle = first.optString("novelTitle").ifBlank {
            Library.cleanNovelTitle(first.optString("ogTitle").ifBlank { first.optString("docTitle") })
        }
        if (novelTitle.isBlank()) novelTitle = slugFromUrl(uri)

        val existing = Library.findByKey(key)
        val folder = existing ?: File(Library.root, Library.sanitize(novelTitle))
        val meta = Library.loadOrCreate(folder, novelTitle, start)
        if (meta.key.isEmpty()) meta.key = key
        friendly("Reading “${meta.title}”")
        log(if (existing != null) "Merging into existing entry (${meta.chapters.size} chapters already saved)." else "New novel.")
        if (meta.cover.isBlank() && first.optString("ogImage").isNotBlank())
            tryDownloadCover(meta, first.optString("ogImage"))

        var added = 0
        val contentLen = first.optString("content").trim().length

        if (rule.nextClick.isNotEmpty()) {
            // SPA that redirects direct chapter URLs to a saved position (novelarrow)
            var startChap = start
            if (!start.contains("/chapter/", true)) {
                friendly("Opening the chapter list…")
                log("Tabbed site → finding the first chapter on $start")
                val tr = navExtractList(start, rule)
                if (tr != null && meta.cover.isBlank()) tryDownloadCover(meta, tr.optString("ogImage"))
                val list = filterToNovel(chapterLinks(tr), novelSlug)
                if (list.isEmpty()) { friendly("Couldn't load the chapter list"); log("No chapters found."); Library.save(meta); finished("done"); return }
                startChap = sortAscending(list).first().second
            }
            friendly("Downloading… (clicking through chapters)")
            log("Click-to-advance from $startChap")
            added = clickFollow(meta, startChap, rule, limit)
        } else if (contentLen >= 200 && rule.tocFrom.size == 2) {
            val toc = deriveToc(start, rule)
            log("Reading page → fetching the chapter list" + if (toc.isNotEmpty()) " → $toc" else "")
            val tr = if (toc.isNotEmpty()) navExtract(toc, rule, null) else null
            if (tr != null && meta.cover.isBlank()) tryDownloadCover(meta, tr.optString("ogImage"))
            val more = filterToNovel(chapterLinks(tr), novelSlug)
            if (more.isNotEmpty()) added = walk(meta, more, rule, limit)
            else { added = if (saveIfNew(meta, first, start)) 1 else 0; log("Couldn't read the chapter list — saved just this page.") }
        } else if (contentLen >= 200) {
            log("Chapter page → following Next/▶.")
            added = follow(meta, start, first, rule, limit)
        } else {
            val chapters = filterToNovel(chapterLinks(first), novelSlug)
            var entry = first.optString("firstUrl")
            if (entry.isBlank() && chapters.isNotEmpty()) entry = sortAscending(chapters).first().second
            if (entry.isNotBlank()) {
                log("Contents page → jumping to the first chapter.")
                val er = navExtract(entry, rule, null)
                if (er != null) tryDownloadCover(meta, er.optString("ogImage"))
                if (er != null && er.optString("content").trim().length >= 200) {
                    added = follow(meta, entry, er, rule, limit)
                    if (chapters.isNotEmpty() && added < limit)
                        added += walk(meta, chapters, rule, limit - added)
                } else if (chapters.isNotEmpty()) { log("Walking the chapter list."); added = walk(meta, chapters, rule, limit) }
                else log("Could not open the first chapter.")
            } else if (chapters.isNotEmpty()) { log("Walking the chapter list."); added = walk(meta, chapters, rule, limit) }
            else { friendly("No chapters found on this page"); log("Tip: paste the Chapter-1 reading URL.") }
        }

        Library.save(meta)
        friendly(if (added > 0) "Finished — $added new chapter(s) saved ✓" else "Nothing new to download")
        log("Done. +$added new, ${meta.chapters.size} total.")
        finished("done")
    }

    // ---------------- modes ----------------

    private suspend fun follow(meta: NovelMeta, startUrl: String, firstResult: JSONObject, rule: SiteRule, limit: Int): Int {
        var cur = startUrl
        var cr: JSONObject? = firstResult
        var prev = ""
        var added = 0; var visited = 0; var empty = 0
        val maxVisited = limit + 300
        while (added < limit && visited < maxVisited) {
            currentCoroutineContext().ensureActive()
            if (cr == null) cr = navExtract(cur, rule, prev.ifEmpty { null })
            visited++
            val content = cr?.optString("content")?.trim() ?: ""
            if (content.isNotEmpty()) {
                if (saveIfNew(meta, cr!!, cur)) { added++; prev = sig(content); progress(added, if (limit >= 1_000_000) added + 1 else limit) }
                empty = 0
            } else { empty++; if (empty >= 3) { log("3 empty pages — stopping."); break } }

            var next = ""
            val nu = cr?.optString("nextUrl") ?: ""
            if (cr != null && !rule.preferIncrement && nu.isNotBlank() && sameHost(nu, cur)) next = nu
            if (next.isBlank() && rule.increment) next = incrementUrl(cur)
            if (next.isBlank() || next == cur) { log("No further chapter — stopping."); break }
            cur = next; cr = null
        }
        return added
    }

    private suspend fun walk(meta: NovelMeta, links: List<Pair<String, String>>, rule: SiteRule, limit: Int): Int {
        val ordered = sortAscending(links)
        var added = 0
        val total = minOf(limit, ordered.size)
        var prev = ""
        for ((title, url) in ordered) {
            currentCoroutineContext().ensureActive()
            if (added >= limit) break
            val num = Library.parseChapterNumber(title, url)
            if (Library.hasChapter(meta, num, url)) continue
            val cr = navExtract(url, rule, prev.ifEmpty { null }) ?: continue
            val content = cr.optString("content").trim()
            if (content.isNotEmpty() && saveIfNew(meta, cr, url)) {
                added++; prev = sig(content); progress(added, total)
            }
        }
        return added
    }

    private suspend fun clickFollow(meta: NovelMeta, startUrl: String, rule: SiteRule, limit: Int): Int {
        pageFinished = false
        web.loadUrl(startUrl)
        waitLoad(30000)
        delay(600)
        val nextJs = ExtractorJs.nextClick(rule.nextClick)
        var added = 0; var stall = 0; var prev = ""
        var guard = 0
        while (added < limit && guard < limit + 600) {
            currentCoroutineContext().ensureActive()
            guard++
            val r = pollExtract(rule, prev.ifEmpty { null })
            val content = r?.optString("content")?.trim() ?: ""
            if (content.isNotEmpty()) {
                val url = r!!.optString("url").ifBlank { startUrl }
                if (saveIfNew(meta, r, url)) { added++; progress(added, if (limit >= 1_000_000) added + 1 else limit) }
                prev = sig(content); stall = 0
            } else { stall++; if (stall >= 2) { log("Content stopped changing — reached the end."); break } }
            if (added >= limit) break
            val clicked = js(nextJs)
            if (clicked.contains("NONE")) { log("No Next button — reached the end."); break }
            delay(700)
        }
        return added
    }

    private suspend fun navExtractList(url: String, rule: SiteRule): JSONObject? {
        pageFinished = false
        web.loadUrl(url)
        waitLoad(30000)
        delay(600)
        val clickJs = if (rule.tabClick.isNotEmpty()) ExtractorJs.tabClick(rule.tabClick) else null
        val script = ExtractorJs.build(rule)
        var best: JSONObject? = null
        var lastCount = -1; var stable = 0
        repeat(45) {
            currentCoroutineContext().ensureActive()
            if (clickJs != null) js(clickJs)
            js("window.scrollTo(0,document.documentElement.scrollHeight);")
            val r = parse(js(script))
            if (r != null) {
                best = r
                val c = r.optJSONArray("chapters")?.length() ?: 0
                if (c >= 3) {
                    if (c == lastCount) { if (++stable >= 3) return r } else stable = 0
                    lastCount = c
                    friendly("Loading chapter list… $c found")
                }
            }
            delay(500)
        }
        return best
    }

    // ---------------- extraction plumbing ----------------

    private suspend fun waitLoad(ms: Long) {
        var waited = 0L
        while (!pageFinished && waited < ms) { delay(250); waited += 250 }
    }

    suspend fun navExtract(url: String, rule: SiteRule, avoid: String?): JSONObject? {
        pageFinished = false
        web.loadUrl(url)
        waitLoad(30000)
        if (avoid != null) delay(250)
        return pollExtract(rule, avoid)
    }

    private suspend fun pollExtract(rule: SiteRule, avoid: String?, tries: Int = 30): JSONObject? {
        val script = ExtractorJs.build(rule)
        var last: JSONObject? = null
        repeat(tries) {
            currentCoroutineContext().ensureActive()
            val r = parse(js(script))
            if (r != null) {
                last = r
                val content = r.optString("content").trim()
                val hasContent = content.length > 40
                val fresh = avoid == null || !hasContent || sig(content) != avoid
                if ((hasContent && fresh) ||
                    (r.optJSONArray("chapters")?.length() ?: 0) >= 3 ||
                    r.optString("firstUrl").isNotBlank()) return r
            }
            delay(600)
        }
        val lc = last?.optString("content")?.trim() ?: ""
        if (avoid != null && lc.length > 40 && sig(lc) == avoid) return null
        return last
    }

    private suspend fun js(script: String): String = suspendCancellableCoroutine { cont ->
        try { web.evaluateJavascript(script) { v -> if (cont.isActive) cont.resume(v ?: "null") } }
        catch (e: Exception) { if (cont.isActive) cont.resume("null") }
    }

    /** evaluateJavascript returns our JSON.stringify result as a quoted JSON string → unquote then parse. */
    private fun parse(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val v = JSONTokener(raw).nextValue()
            when (v) {
                is String -> if (v.isBlank() || v == "null") null else JSONObject(v)
                is JSONObject -> v
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun chapterLinks(r: JSONObject?): List<Pair<String, String>> {
        val arr = r?.optJSONArray("chapters") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isNotBlank()) out.add(o.optString("title") to url)
        }
        return out
    }

    private fun saveIfNew(meta: NovelMeta, r: JSONObject, url: String): Boolean {
        val num = Library.parseChapterNumber(r.optString("chapterTitle"), url)
        if (Library.hasChapter(meta, num, url)) return false
        val title = r.optString("chapterTitle").ifBlank { if (num > 0) "Chapter $num" else "Chapter" }
        Library.addChapter(meta, num, title, url, r.optString("content"))
        Library.save(meta)
        log("  + $title  (${r.optString("content").length} chars)")
        return true
    }

    private suspend fun tryDownloadCover(meta: NovelMeta, coverUrl: String) {
        if (coverUrl.isBlank() || meta.cover.isNotBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(coverUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", UA)
                try {
                    val su = Uri.parse(meta.source)
                    conn.setRequestProperty("Referer", "${su.scheme}://${su.host}/")
                } catch (e: Exception) { }
                if (conn.responseCode !in 200..299) return@withContext
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 512) return@withContext
                val ctype = conn.contentType ?: ""
                val ext = when {
                    ctype.contains("png") || coverUrl.contains(".png", true) -> "png"
                    ctype.contains("webp") || coverUrl.contains(".webp", true) -> "webp"
                    else -> "jpg"
                }
                File(meta.folder, "cover.$ext").writeBytes(bytes)
                meta.cover = "cover.$ext"; meta.coverUrl = coverUrl
                withContext(Dispatchers.Main) { Library.save(meta); log("Cover saved.") }
            } catch (e: Exception) { }
        }
    }

    // ---------------- helpers ----------------

    private fun sig(s: String): String {
        val t = s.replace(Regex("""\s+"""), "")
        return if (t.length > 160) t.substring(0, 160) else t
    }

    private fun sortAscending(links: List<Pair<String, String>>): List<Pair<String, String>> =
        links.withIndex().sortedWith(compareBy(
            { (_, l) -> Library.parseChapterNumber(l.first, l.second).let { if (it > 0) it else Int.MAX_VALUE } },
            { it.index }
        )).map { it.value }

    private fun filterToNovel(links: List<Pair<String, String>>, slug: String): List<Pair<String, String>> {
        if (slug.isBlank() || slug.length < 4) return links
        val f = links.filter { it.second.contains(slug, true) }
        return f.ifEmpty { links }
    }

    private fun deriveToc(url: String, rule: SiteRule): String {
        if (rule.tocFrom.size != 2) return ""
        return try {
            // sites.json replacement strings use "$1" — Kotlin/Java Regex supports that natively
            val r = url.replace(Regex(rule.tocFrom[0]), rule.tocFrom[1])
            if (r != url && (r.startsWith("http://") || r.startsWith("https://"))) r else ""
        } catch (e: Exception) { "" }
    }

    private fun incrementUrl(url: String): String {
        val m = Regex("""\d+(?=[^\d]*$)""").find(url) ?: return ""
        val n = m.value.toLongOrNull() ?: return ""
        return url.substring(0, m.range.first) + (n + 1) + url.substring(m.range.last + 1)
    }

    private fun sameHost(a: String, b: String): Boolean = try {
        Uri.parse(a).host.equals(Uri.parse(b).host, true)
    } catch (e: Exception) { false }

    private fun slugFromUrl(uri: Uri): String {
        val skip = setOf("novel", "book", "b", "chapter")
        val segs = (uri.path ?: "").trim('/').split('/').filter { it.isNotEmpty() }
        val seg = segs.firstOrNull { it.length > 3 && it.toIntOrNull() == null && it.lowercase() !in skip }
            ?: segs.firstOrNull() ?: (uri.host ?: "novel")
        return Library.sanitize(seg.replace('-', ' ').replace('_', ' '))
    }
}
