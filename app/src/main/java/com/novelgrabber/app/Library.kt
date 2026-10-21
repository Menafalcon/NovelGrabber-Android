package com.novelgrabber.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChapterMeta(
    var num: Int = -1,
    var seq: Int = 0,
    var title: String = "",
    var file: String = "",
    var url: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("Num", num); put("Seq", seq); put("Title", title); put("File", file); put("Url", url)
    }
    companion object {
        fun fromJson(o: JSONObject) = ChapterMeta(
            o.optInt("Num", -1), o.optInt("Seq", 0),
            o.optString("Title"), o.optString("File"), o.optString("Url"))
    }
}

class NovelMeta(
    var title: String = "",
    var key: String = "",
    var author: String = "",
    var category: String = "",   // "" = standalone book; otherwise the name of the SERIES (shown as one grouped card)
    var source: String = "",
    var cover: String = "",
    var coverUrl: String = "",
    var created: String = "",
    var updated: String = ""
) {
    val chapters = mutableListOf<ChapterMeta>()
    var folder: File = File("")   // runtime only
    /** Non-empty = a LINKED novel: the book is read straight from this original epub (content:// URI); no chapters are stored here. */
    var epubUri: String = ""
    /** Finished reading. Independent of the series, so a book can be completed AND still belong to its series card. */
    var completed: Boolean = false
    /** User explicitly removed this book from a collection -> auto-sort must leave it standalone. */
    var noAutoSort: Boolean = false
    /** User manually assigned or renamed this book's collection -> auto-sort must not dissolve it. */
    var manualCat: Boolean = false

    fun toJson() = JSONObject().apply {
        put("Title", title); put("Key", key); put("Author", author); put("Category", category); put("Source", source)
        if (completed) put("Completed", true)
        if (noAutoSort) put("NoAutoSort", true)
        if (manualCat && category.isNotEmpty()) put("ManualCat", true)
        put("Cover", cover); put("CoverUrl", coverUrl); put("Created", created); put("Updated", updated)
        if (epubUri.isNotEmpty()) put("EpubUri", epubUri)
        put("Chapters", JSONArray().also { a -> chapters.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun fromJson(o: JSONObject) = NovelMeta(
            o.optString("Title"), o.optString("Key"), o.optString("Author"), o.optString("Category"),
            o.optString("Source"), o.optString("Cover"), o.optString("CoverUrl"),
            o.optString("Created"), o.optString("Updated")
        ).also { m ->
            m.epubUri = o.optString("EpubUri")
            m.completed = o.optBoolean("Completed", false)
            m.noAutoSort = o.optBoolean("NoAutoSort", false)
            m.manualCat = o.optBoolean("ManualCat", false)
            // older versions stored "Completed" as a category — it is a flag now
            if (m.category.equals("Completed", true)) { m.category = ""; m.completed = true }
            if (m.category.equals("General", true) || m.category.equals("Series", true) || m.category.equals("The", true)) {
                m.category = ""; m.manualCat = false
            }
            val arr = o.optJSONArray("Chapters") ?: JSONArray()
            for (i in 0 until arr.length()) m.chapters.add(ChapterMeta.fromJson(arr.getJSONObject(i)))
        }
    }
}

/**
 * Same on-disk layout as the desktop app: <root>/<Novel Title>/meta.json + chapters/cXXXXX.xhtml.
 * Chapter numbering is volume-aware (vol*100000+ch) and de-dup is URL-first — ports of the
 * fixes that made 50-chapter batches stitch correctly.
 */
object Library {
    lateinit var root: File

    private fun now(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())

    fun sanitize(nameIn: String): String {
        var name = nameIn.ifBlank { "Novel" }
        name = name.replace(Regex("""[\\/:*?"<>|]"""), " ")
        name = name.replace(Regex("""\s+"""), " ").trim().trim('.')
        if (name.length > 120) name = name.substring(0, 120).trim()
        return name.ifEmpty { "Novel" }
    }

    fun cleanNovelTitle(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw
        t = t.replace(Regex("""\s*[-|–:]\s*(read online|read free|free online|novel|light novel|novellunar|novelbin|novelfire|ranobes|novelhall|novelfull).*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s*[-|–]\s*chapter\s*\d.*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s*chapter\s*\d.*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+novel\s*$""", RegexOption.IGNORE_CASE), "")
        return t.trim().trim('-', '|', '–', ':').trim()
    }

    /** Stable "host|slug" identity so batches of the same novel merge into one folder. */
    fun novelKey(url: String): String {
        val u = Uri.parse(url)
        var host = (u.host ?: "").lowercase()
        if (host.startsWith("www.")) host = host.substring(4)
        var path = (u.path ?: "").lowercase()
        path = path.replace(Regex("""/chapter[-/_]?\d+.*$"""), "")
        path = path.replace(Regex("""-chapter-\d+.*$"""), "")
        path = path.replace(Regex("""/\d+\.html?$"""), "")
        path = path.replace(Regex("""/\d+/?$"""), "")
        path = path.trim('/')
        val skip = setOf("novel", "book", "b", "series", "read", "chapter", "chapters")
        val slug = path.split('/').firstOrNull { it.isNotEmpty() && it !in skip }
            ?: path.ifEmpty { host }
        return "$host|$slug"
    }

    fun findByKey(key: String): File? {
        for (m in history()) {
            val k = if (m.key.isNotEmpty()) m.key
                    else try { novelKey(m.source) } catch (e: Exception) { "" }
            if (k == key) return m.folder
        }
        return null
    }

    /** Volume-aware: "Volume 2 Chapter 1" must NOT collide with "Volume 1 Chapter 1". */
    fun parseChapterNumber(title: String?, url: String?): Int {
        val both = (title ?: "") + " " + (url ?: "")
        var vol = 0
        Regex("""\bvol(?:ume)?[\s\-_.]*?(\d{1,4})""", RegexOption.IGNORE_CASE).find(both)?.let {
            vol = it.groupValues[1].toIntOrNull() ?: 0
        }
        var ch = -1
        for (s in listOf(title ?: "", url ?: "")) {
            val m = Regex("""chapter[\s\-_/]*?(\d{1,6})""", RegexOption.IGNORE_CASE).find(s)
            if (m != null) { ch = m.groupValues[1].toIntOrNull() ?: -1; if (ch >= 0) break }
        }
        if (ch < 0) {
            val m = Regex("""(\d{1,6})(?!.*\d)""").find(url ?: "")
            ch = m?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
        if (ch < 0) return -1
        return if (vol > 0) vol * 100000 + ch else ch
    }

    fun metaPath(folder: File) = File(folder, "meta.json")

    fun loadOrCreate(folder: File, title: String, source: String): NovelMeta {
        folder.mkdirs()
        File(folder, "chapters").mkdirs()
        val p = metaPath(folder)
        val meta = if (p.exists()) {
            try { NovelMeta.fromJson(JSONObject(p.readText())) } catch (e: Exception) { NovelMeta() }
        } else NovelMeta(title = title, source = source, created = now())
        if (meta.title.isBlank()) meta.title = title
        if (meta.source.isBlank()) meta.source = source
        meta.folder = folder
        return meta
    }

    /** bumpUpdated=false for metadata-only edits (category moves) so recency sort is untouched. */
    fun save(meta: NovelMeta, bumpUpdated: Boolean = true) {
        if (bumpUpdated) meta.updated = now()
        metaPath(meta.folder).writeText(meta.toJson().toString(2))
    }


    // ---------------- cover thumbnails ----------------
    // The library list shows covers at ~56x78dp. Decoding a multi-MB cover for every row is what makes a
    // big library stutter, so every novel gets a small cover_s.jpg next to its full cover.

    fun thumbFile(folder: File) = File(folder, "cover_s.jpg")

    /** Decodes with inSampleSize (a huge image never fully inflates in memory) and re-encodes as JPEG with the long side <= maxSide. */
    fun scaledJpeg(bytes: ByteArray, maxSide: Int, quality: Int = 85): ByteArray? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (longSide <= 0) return null
            var sample = 1
            while (longSide / (sample * 2) >= maxSide) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(
                bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true) else bmp
            val bos = java.io.ByteArrayOutputStream()
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
            if (out !== bmp) bmp.recycle()
            out.recycle()
            bos.toByteArray()
        } catch (e: Throwable) { null }
    }

    /** Builds cover_s.jpg from the novel's cover file. False if the cover can't be decoded. */
    fun makeThumb(folder: File, coverName: String): Boolean {
        return try {
            val t = scaledJpeg(File(folder, coverName).readBytes(), 320) ?: return false
            thumbFile(folder).writeBytes(t)
            true
        } catch (e: Throwable) { false }
    }

    // ---------------- categories ----------------

    /** A "category" is a SERIES: the app shows all of its books as ONE card (see app.html) instead of a shelf.
     *  Only series that really contain books exist — there is no separate list to go stale. */
    fun categories(hist: List<NovelMeta> = history()): List<String> {
        val list = mutableListOf<String>()
        for (m in hist)
            if (m.category.isNotEmpty() && list.none { it.equals(m.category, true) }) list.add(m.category)
        return list.sortedBy { it.lowercase() }
    }

    /** "" / "General" = take the book out of any collection and keep auto-sort from re-grouping it. */
    fun setCategory(folder: File, cat: String) {
        if (!metaPath(folder).exists()) return
        val m = loadOrCreate(folder, "", "")
        val old = m.category
        val next = if (cat.isBlank() || cat.equals("General", true)) "" else cat.trim()
        m.category = next
        m.noAutoSort = next.isEmpty()
        m.manualCat = next.isNotEmpty()
        save(m, bumpUpdated = false)
        // if the old collection now has only 1 book left, clear its category tag too
        if (old.isNotEmpty() && !old.equals(next, true)) {
            val rem = history().filter { it.category.equals(old, true) }
            if (rem.size == 1) { rem[0].category = ""; rem[0].manualCat = false; save(rem[0], bumpUpdated = false) }
        }
    }

    /** Removes one book from its collection and prevents auto-sort from putting it back. */
    fun removeFromSeries(folder: File): String {
        if (!metaPath(folder).exists()) return ""
        val m = loadOrCreate(folder, "", "")
        val old = m.category
        setCategory(folder, "")
        return old
    }

    fun setCompleted(folder: File, done: Boolean) {
        if (!metaPath(folder).exists()) return
        val m = loadOrCreate(folder, "", "")
        m.completed = done
        save(m, bumpUpdated = false)
    }

    /** Renames a series (merges into another series when the new name already exists). Returns books touched. */
    fun renameSeries(old: String, newNameIn: String): Int {
        val newName = newNameIn.trim()
        if (newName.isEmpty()) return 0
        val hist = history()
        // reuse the exact spelling of an existing series so "lotm" joins "LOTM" instead of making a twin
        val target = hist.firstOrNull { it.category.equals(newName, true) && !it.category.equals(old, true) }?.category ?: newName
        var n = 0
        for (m in hist) if (m.category.equals(old, true)) { m.category = target; m.manualCat = true; m.noAutoSort = false; save(m, bumpUpdated = false); n++ }
        return n
    }

    /** Dissolves a series: its books go back to being separate cards. Returns books touched. */
    fun ungroupSeries(name: String): Int {
        var n = 0
        for (m in history()) if (m.category.equals(name, true)) { m.category = ""; m.manualCat = false; save(m, bumpUpdated = false); n++ }
        return n
    }

    // ---------------- auto sort (multi-volume series → one collection card) ----------------
    //
    // Titles are cleaned to a base (volume/book/part markers, chapter ranges like "1-299", "[epub]", "[END]" and
    // trailing volume numbers removed). A shorter/canonical ANCHOR title then collects every other novel whose base:
    //   • is identical, or
    //   • contains the anchor's words in exact contiguous order ("X Y vol 2" ↔ "A B X Y vol 3"), or
    //   • has a window of words that is ≥ 70% similar to the anchor with matching meaningful words in order.
    // Anchors must have ≥ 2 meaningful words (not stopwords) and ≥ 6 meaningful characters to use rules 2 & 3,
    // and anchors never chain into transitive snowballs.

    private val STOP = setOf(
        "the", "a", "an", "of", "and", "in", "on", "to", "is", "for", "with", "as", "at", "by",
        "from", "into", "my", "i", "im", "am", "was", "be", "been", "are", "were", "me", "you",
        "your", "his", "her", "our", "their", "this", "that", "it", "not", "no", "but", "or",
        "so", "if", "when", "who", "what", "how", "why", "can", "will", "just", "only", "still",
        "even", "after", "before", "up", "out", "all", "one", "new", "novel", "novels", "story",
        "series", "edition", "complete", "completed", "end", "epub", "pdf", "chapter", "chapters",
        "vol", "volume", "volumes", "book", "books", "part", "parts", "tome", "season", "arc"
    )
    private val BRACKET_TAG_RE = Regex(
        """[\[(]\s*(?:epub|pdf|end|complete|completed|wn|ln|w|l|mtl|raw|official|(?:light|web)\s+novel|(?:vol(?:ume)?s?|books?|parts?|tomes?|seasons?|arcs?|v|pt|bk)\.?\s*#?\d+(?:\.\d+)?)\s*[\])]""",
        RegexOption.IGNORE_CASE
    )
    private val VOL_TAIL_RE = Regex(
        """^(.{3,}?)(?:[\s\-–—:,]+|\b)(?:vol(?:ume)?s?|books?|parts?|tomes?|seasons?|v|pt|bk)\.?\s*#?\d+(?:\.\d+)?(?:\s*[-–—~]\s*\d+(?:\.\d+)?)?(?:\s*[:\-–—].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val CHAP_RANGE_RE = Regex("""\b(?:novel\s+)?(?:chapters?|ch\.?|c)?\s*\d+\s*[-–—~]\s*\d+\b""", RegexOption.IGNORE_CASE)
    private val FORMAT_TAG_RE = Regex("""\b(?:light|web)\s+novel\b|\bnovel\s+chapters?\b|\bepub\b""", RegexOption.IGNORE_CASE)
    private val TRAIL_NUM_RE = Regex("""^(\S+.*?)[\s\-–—:,#]+\d+(?:\.\d+)?\s*$""")
    private val PUNCT_TAIL_RE = Regex("""[\s\-–—:,(\[_]+$""")
    private val NON_WORD_RE = Regex("""[^\p{L}\p{N}\s]""")
    private val SPACE_RE = Regex("""\s+""")
    private const val MAX_GROUP = 35       // an anchor matching more books than this is too generic to trust

    /** Removes volume/chapter/tag noise while keeping the human-readable series title. */
    private fun stripMeta(title: String): String {
        var s = title.replace('_', ' ')
        s = BRACKET_TAG_RE.replace(s, " ")
        s = VOL_TAIL_RE.replace(s, "$1")
        s = CHAP_RANGE_RE.replace(s, " ")
        s = FORMAT_TAG_RE.replace(s, " ")
        while (true) {
            val m = TRAIL_NUM_RE.find(s) ?: break
            s = m.groupValues[1]
        }
        return s.replace(PUNCT_TAIL_RE, "").replace(SPACE_RE, " ").trim()
    }

    private fun baseTokens(title: String): List<String> {
        var s = stripMeta(title).lowercase().replace("'", "").replace("’", "")
        s = NON_WORD_RE.replace(s, " ").replace(SPACE_RE, " ").trim()
        return if (s.isEmpty()) emptyList() else s.split(' ').filter { it.isNotEmpty() }
    }

    /** The title as it will be shown on the collection card: original spelling, volume bits removed. */
    private fun displayName(title: String): String = stripMeta(title)

    private fun junkName(name: String): Boolean {
        val t = name.trim()
        if (t.length < 3 || t.equals("Series", true) || t.equals("General", true) || t.equals("The", true)) return true
        val sig = baseTokens(t).filter { it !in STOP }
        return sig.isEmpty()
    }

    private class Prepped(val meta: NovelMeta?, val toks: List<String>) {
        val sig: List<String> = toks.filter { it !in STOP }
        val sigChars: Int = sig.sumOf { it.length }
        val joined: String = toks.joinToString(" ")
    }

    private fun matches(anchor: Prepped, book: Prepped): Boolean {
        if (anchor.toks.isEmpty() || book.toks.isEmpty()) return false
        if (anchor.joined == book.joined) return true
        if (anchor.sig.size < 2 || anchor.sigChars < 6) return false
        val n = anchor.toks.size
        // 1) exact contiguous word-order match ("X Y" inside "A B X Y C")
        if (book.toks.size >= n) {
            for (i in 0..book.toks.size - n) {
                var same = true
                for (k in 0 until n) if (book.toks[i + k] != anchor.toks[k]) { same = false; break }
                if (same) return true
            }
        }
        // 2) 70%+ fuzzy window match where every meaningful word matches in order
        val a = anchor.joined
        val wMin = maxOf(2, n - 1)
        val wMax = minOf(book.toks.size, n + 1)
        if (wMin > wMax) return false
        for (w in wMin..wMax) {
            for (i in 0..book.toks.size - w) {
                val win = book.toks.subList(i, i + w)
                val winSig = win.filter { it !in STOP }
                if (winSig.size != anchor.sig.size) continue
                var wordsOk = true
                for (k in anchor.sig.indices) {
                    val sw = anchor.sig[k]; val tw = winSig[k]
                    if (sw[0] != tw[0]) { wordsOk = false; break }
                    if (sw != tw && 1.0 - lev(sw, tw).toDouble() / maxOf(sw.length, tw.length) < 0.75) { wordsOk = false; break }
                }
                if (!wordsOk) continue
                val t = win.joinToString(" ")
                val maxL = maxOf(a.length, t.length); val minL = minOf(a.length, t.length)
                if (minL.toDouble() / maxL < 0.70) continue
                if (1.0 - lev(a, t).toDouble() / maxL >= 0.70) return true
            }
        }
        return false
    }

    /** Files standalone novels into collections and cleans up any bogus older auto-sort groups.
     *  Books the user manually removed (noAutoSort) stay standalone; books the user manually placed (manualCat) stay put.
     *  Returns (collections touched, novels moved). */
    fun autoSort(): Pair<Int, Int> {
        val hist = history()
        val oldCat = HashMap<NovelMeta, String>(hist.size)
        for (m in hist) {
            oldCat[m] = m.category
            if (m.noAutoSort) {
                m.category = ""
            } else if (!m.manualCat || junkName(m.category)) {
                m.category = ""
                m.manualCat = false
            }
        }

        val free = hist.filter { !it.noAutoSort && it.category.isEmpty() }.map { Prepped(it, baseTokens(it.title)) }.toMutableList()
        val touched = HashSet<String>()
        var moved = 0

        // 1) join manually-kept collections first
        for (name in categories(hist)) {
            val cPrep = Prepped(null, baseTokens(name))
            val it1 = free.iterator()
            while (it1.hasNext()) {
                val p = it1.next()
                if (matches(cPrep, p)) {
                    p.meta!!.category = name
                    it1.remove()
                }
            }
        }

        // 2) form collections from the remaining books: shortest base titles first, no transitive chaining
        val order = free.sortedWith(compareBy({ it.toks.size }, { it.meta!!.title.lowercase() }))
        val used = HashSet<NovelMeta>()
        for (anchor in order) {
            val aMeta = anchor.meta!!
            if (aMeta in used || anchor.toks.isEmpty()) continue
            val members = order.filter { it.meta !== aMeta && it.meta !in used && matches(anchor, it) }
            if (members.isEmpty() || members.size + 1 > MAX_GROUP) continue
            val name = displayName(aMeta.title)
            if (junkName(name)) continue
            val target = categories(hist).firstOrNull { it.equals(name, true) } ?: name
            aMeta.category = target; used.add(aMeta)
            for (p in members) { p.meta!!.category = target; used.add(p.meta) }
        }

        // save only books whose category changed
        for (m in hist) {
            val prev = oldCat[m] ?: ""
            if (m.category != prev) {
                save(m, bumpUpdated = false)
                if (m.category.isNotEmpty()) { moved++; touched.add(m.category.lowercase()) }
            }
        }
        return touched.size to moved
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length)
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun normUrl(u: String?) = (u ?: "").trimEnd('/').lowercase()

    fun hasChapter(meta: NovelMeta, num: Int, url: String): Boolean {
        val nu = normUrl(url)
        if (nu.isNotEmpty() && meta.chapters.any { normUrl(it.url) == nu }) return true
        if (num > 0 && meta.chapters.any { it.num == num }) return true
        return false
    }

    fun addChapter(meta: NovelMeta, num: Int, title: String, url: String, text: String): ChapterMeta {
        val seq = (meta.chapters.maxOfOrNull { it.seq } ?: 0) + 1
        val key = if (num > 0) String.format("%05d", num) else "s" + String.format("%05d", seq)
        val file = "chapters/c$key.xhtml"
        val full = File(meta.folder, file)
        full.parentFile?.mkdirs()
        full.writeText(chapterXhtml(title, text))
        val cm = ChapterMeta(num, seq, title, file, url)
        meta.chapters.add(cm)
        return cm
    }

    fun ordered(meta: NovelMeta): List<ChapterMeta> =
        meta.chapters.sortedWith(compareBy({ if (it.num > 0) it.num else Int.MAX_VALUE }, { it.seq }))

    // long novels list every chapter in meta.json — re-parsing all of them on every library
    // visit is the main lag with 60+ books. Cache per folder by write stamp.
    private val metaCache = HashMap<String, Pair<Long, NovelMeta>>()

    fun history(): List<NovelMeta> {
        if (!::root.isInitialized || !root.exists()) return mutableListOf()
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return mutableListOf()

        // 1) cheap pass: which folders are new or changed since the cache was filled?
        val result = ArrayList<NovelMeta>(dirs.size)
        val stale = ArrayList<Pair<File, Long>>()
        synchronized(metaCache) {
            for (dir in dirs) {
                val p = metaPath(dir)
                val len = p.length()
                if (len == 0L && !p.exists()) continue
                val stamp = p.lastModified() + len          // length too: two saves inside one timestamp tick must not look unchanged
                val e = metaCache[dir.path]
                if (e != null && e.first == stamp) result.add(e.second) else stale.add(dir to stamp)
            }
        }

        // 2) the expensive pass (reading + parsing every chapter list) runs in parallel, outside the lock
        if (stale.isNotEmpty()) {
            fun parse(dir: File, stamp: Long): Pair<File, Pair<Long, NovelMeta>>? = try {
                val m = NovelMeta.fromJson(JSONObject(metaPath(dir).readText()))
                m.folder = dir
                dir to (stamp to m)
            } catch (ex: Exception) { null }

            val parsed: List<Pair<File, Pair<Long, NovelMeta>>?> =
                if (stale.size < 6) stale.map { parse(it.first, it.second) }
                else {
                    val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
                    try { pool.invokeAll(stale.map { s -> java.util.concurrent.Callable { parse(s.first, s.second) } }).map { it.get() } }
                    finally { pool.shutdown() }
                }
            synchronized(metaCache) {
                for (r in parsed) if (r != null) { metaCache[r.first.path] = r.second; result.add(r.second.second) }
            }
        }
        return result.sortedByDescending { it.updated }
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun paragraphs(text: String): String {
        val sb = StringBuilder()
        for (line in text.replace("\r", "").split('\n')) {
            val t = line.trim()
            if (t.isNotEmpty()) sb.append("<p>").append(escape(t)).append("</p>\n")
        }
        return sb.toString()
    }

    /** Same wrapper as chapterXhtml, but the body is already HTML (imported EPUB chapters keep their markup + illustrations). */
    fun chapterXhtmlHtml(title: String, bodyHtml: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escape(title)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body><h2 class="ch-title">${escape(title)}</h2>
$bodyHtml</body></html>"""

    fun chapterXhtml(title: String, text: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escape(title)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body><h2 class="ch-title">${escape(title)}</h2>
${paragraphs(text)}</body></html>"""
}
