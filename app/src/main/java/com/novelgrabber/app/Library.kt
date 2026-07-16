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
    var category: String = "",   // "" = General; "Completed"; or a custom name
    var source: String = "",
    var cover: String = "",
    var coverUrl: String = "",
    var created: String = "",
    var updated: String = ""
) {
    val chapters = mutableListOf<ChapterMeta>()
    var folder: File = File("")   // runtime only

    fun toJson() = JSONObject().apply {
        put("Title", title); put("Key", key); put("Author", author); put("Category", category); put("Source", source)
        put("Cover", cover); put("CoverUrl", coverUrl); put("Created", created); put("Updated", updated)
        put("Chapters", JSONArray().also { a -> chapters.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun fromJson(o: JSONObject) = NovelMeta(
            o.optString("Title"), o.optString("Key"), o.optString("Author"), o.optString("Category"),
            o.optString("Source"), o.optString("Cover"), o.optString("CoverUrl"),
            o.optString("Created"), o.optString("Updated")
        ).also { m ->
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

    // ---------------- categories ----------------

    const val COMPLETED = "Completed"
    private fun catPath() = File(root, "categories.json")

    fun categories(): List<String> {
        val list = mutableListOf("General", COMPLETED)
        try {
            if (catPath().exists()) {
                val arr = JSONArray(catPath().readText())
                for (i in 0 until arr.length()) {
                    val c = arr.optString(i)
                    if (c.isNotBlank() && list.none { it.equals(c, true) }) list.add(c)
                }
            }
        } catch (e: Exception) { }
        for (m in history())
            if (m.category.isNotEmpty() && list.none { it.equals(m.category, true) }) list.add(m.category)
        return list
    }

    fun addCategory(nameIn: String) {
        val name = nameIn.trim()
        if (name.isEmpty() || name.equals("General", true) || name.equals(COMPLETED, true)) return
        val custom = mutableListOf<String>()
        try {
            if (catPath().exists()) {
                val arr = JSONArray(catPath().readText())
                for (i in 0 until arr.length()) custom.add(arr.optString(i))
            }
        } catch (e: Exception) { }
        if (custom.any { it.equals(name, true) }) return
        custom.add(name)
        try { catPath().writeText(JSONArray(custom).toString(2)) } catch (e: Exception) { }
    }

    fun setCategory(folder: File, cat: String) {
        if (!metaPath(folder).exists()) return
        val m = loadOrCreate(folder, "", "")
        m.category = if (cat.equals("General", true)) "" else cat
        save(m, bumpUpdated = false)
    }

    // ---------------- auto sort (multi-volume LN series → one category) ----------------

    /** Groups General novels whose titles are ≥50% similar under a category named after the
     *  common part of the titles. Returns (groups, novels moved). */
    fun autoSort(): Pair<Int, Int> {
        val pool = history().filter { it.category.isEmpty() }
        val used = HashSet<Int>()
        var groups = 0; var moved = 0
        for (i in pool.indices) {
            if (i in used) continue
            val group = mutableListOf(pool[i])
            for (j in i + 1 until pool.size)
                if (j !in used && titleSim(pool[i].title, pool[j].title) >= 0.5) { group.add(pool[j]); used.add(j) }
            if (group.size < 2) continue
            used.add(i)
            val name = commonName(group.map { it.title })
            addCategory(name)
            for (m in group) { m.category = name; save(m, bumpUpdated = false); moved++ }
            groups++
        }
        return groups to moved
    }

    fun titleSim(aIn: String, bIn: String): Double {
        val a = normTitle(aIn); val b = normTitle(bIn)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    private fun normTitle(t: String) =
        t.lowercase().replace(Regex("""[^\w\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

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

    private fun commonName(titles: List<String>): String {
        var p = titles[0]
        for (t in titles.drop(1)) {
            var n = 0
            while (n < p.length && n < t.length && p[n].lowercaseChar() == t[n].lowercaseChar()) n++
            p = p.substring(0, n)
        }
        p = p.replace(Regex("""\s*\(?\s*(light\s+novel|vol(ume)?|part|book|v)\.?\s*\d*\s*\)?\s*$""", RegexOption.IGNORE_CASE), "")
        p = p.replace(Regex("""\s*\d+$"""), "")               // partial volume number, e.g. "… [W] 0"
        p = p.replace(Regex("""[\s\-–—:,(\[]+$"""), "").trim()
        return if (p.length >= 3) p else "Series"
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
        val list = mutableListOf<NovelMeta>()
        if (!::root.isInitialized || !root.exists()) return list
        synchronized(metaCache) {
            root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val p = metaPath(dir)
                if (!p.exists()) return@forEach
                val stamp = p.lastModified()
                var e = metaCache[dir.path]
                if (e == null || e.first != stamp) {
                    try {
                        val m = NovelMeta.fromJson(JSONObject(p.readText()))
                        m.folder = dir
                        e = stamp to m
                        metaCache[dir.path] = e
                    } catch (ex: Exception) { return@forEach }
                }
                list.add(e.second)
            }
        }
        return list.sortedByDescending { it.updated }
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

    fun chapterXhtml(title: String, text: String) = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>${escape(title)}</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body><h2 class="ch-title">${escape(title)}</h2>
${paragraphs(text)}</body></html>"""
}
