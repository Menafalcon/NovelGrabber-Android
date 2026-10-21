package com.novelgrabber.app

import java.io.File

class EpubBook(
    var title: String = "",
    var author: String = "",
    var cover: ByteArray? = null,
    var coverExt: String = "jpg"
) {
    /** ngimg:// key of an illustration-only first page — EpubImport uses it as the cover when the epub declares none. */
    var leadImageKey: String? = null
    val chapters = mutableListOf<Pair<String, String>>()   // title -> body html
    /** Illustrations pulled from the epub, keyed by flattened zip path.
     *  Chapter bodies reference them as ngimg://<key> until inlineImages() turns them into data: URIs. */
    val images = LinkedHashMap<String, ByteArray>()
}

/** Parses an external .epub into reader-ready chapters (spine order, NCX titles). Port of desktop EpubRead. */
object EpubRead {

    fun load(epubFile: File, withImages: Boolean = true): EpubBook =
        FileZip(epubFile).use { load(it, withImages, epubFile.nameWithoutExtension) }

    /**
     * @param withImages false = chapters are parsed exactly the same (same chapter list) but illustration bytes are not read —
     *                   used to LINK a book into the library without loading its pictures (only a text-less cover is kept).
     */
    fun load(zipIn: EpubZip, withImages: Boolean = true, fallbackTitle: String = "Book"): EpubBook {
        zipIn.let { zip ->
            fun read(name: String): String {
                val st = zip.open(name) ?: zip.open(name.replace('\\', '/')) ?: return ""
                return st.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val container = read("META-INF/container.xml")
            var opfPath = Regex("""full-path="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(container)?.groupValues?.get(1) ?: ""
            if (opfPath.isEmpty())
                opfPath = zip.names().firstOrNull { it.endsWith(".opf", true) } ?: ""
            if (opfPath.isEmpty()) throw IllegalStateException("Not a valid EPUB (no OPF found).")
            val opf = read(opfPath)
            val baseDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
            fun rel(href: String) = (if (baseDir.isEmpty()) "" else "$baseDir/") + java.net.URLDecoder.decode(href, "UTF-8")

            fun meta(tag: String): String =
                Regex("<$tag[^>]*>([^<]*)</$tag>", RegexOption.IGNORE_CASE).find(opf)
                    ?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, 0).toString().trim() } ?: ""

            val book = EpubBook(
                title = meta("dc:title").ifBlank { fallbackTitle },
                author = meta("dc:creator"))

            fun attr(tag: String, name: String): String =
                Regex("""$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: ""

            val manifest = mutableMapOf<String, Pair<String, String>>()   // id -> (href, type)
            for (m in Regex("""<item\b[^>]*?/?>""", RegexOption.IGNORE_CASE).findAll(opf)) {
                val id = attr(m.value, "id"); val href = attr(m.value, "href"); val mt = attr(m.value, "media-type")
                if (id.isNotEmpty() && href.isNotEmpty()) manifest[id] = href to mt
            }

            // cover
            var coverHref = ""
            Regex("""<meta[^>]*name="cover"[^>]*content="([^"]+)"""", RegexOption.IGNORE_CASE).find(opf)?.let {
                manifest[it.groupValues[1]]?.let { c -> coverHref = c.first }
            }
            if (coverHref.isEmpty()) {   // EPUB3: <item properties="cover-image" …/>
                for (m in Regex("""<item\b[^>]*?/?>""", RegexOption.IGNORE_CASE).findAll(opf)) {
                    if (attr(m.value, "properties").split(' ').contains("cover-image")) { coverHref = attr(m.value, "href"); break }
                }
            }
            if (coverHref.isEmpty())
                coverHref = manifest.values.firstOrNull { it.second.startsWith("image") && it.first.contains("cover", true) }?.first ?: ""
            if (coverHref.isNotEmpty()) {
                zip.open(rel(coverHref))?.let { ce ->
                    book.cover = ce.use { it.readBytes() }
                    book.coverExt = when (coverHref.substringAfterLast('.', "jpg").lowercase()) {
                        "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpg"
                    }
                }
            }

            // The book's own table of contents (EPUB2 NCX + EPUB3 nav): spine file -> title. A file that is listed here
            // starts a chapter; a file that isn't is just a continuation (e.g. the text after an illustration page).
            val titles = mutableMapOf<String, String>()   // normalized path relative to the OPF -> title
            fun tocAdd(tocHref: String, src: String, label: String) {
                val dir = if (tocHref.contains('/')) tocHref.substringBeforeLast('/') else ""
                val t = android.text.Html.fromHtml(label.replace(Regex("<[^>]+>"), ""), 0).toString().trim()
                val key = normPath(dir, src)
                if (t.isNotEmpty() && key.isNotEmpty() && !titles.containsKey(key)) titles[key] = t
            }
            val ncxHref = manifest.values.firstOrNull { it.second.contains("dtbncx") || it.first.endsWith(".ncx", true) }?.first
            if (!ncxHref.isNullOrEmpty()) {
                val ncx = read(rel(ncxHref))
                // every navLabel/content pair, in order — nesting-agnostic, and entries with #fragments count too
                for (m in Regex("""<navLabel>\s*<text>(.*?)</text>\s*</navLabel>\s*<content[^>]*?\bsrc\s*=\s*"([^"]+)"""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(ncx))
                    tocAdd(ncxHref, m.groupValues[2], m.groupValues[1])
            }
            var navHref = ""
            for (m in Regex("""<item\b[^>]*?/?>""", RegexOption.IGNORE_CASE).findAll(opf))
                if (attr(m.value, "properties").split(' ').contains("nav")) { navHref = attr(m.value, "href"); break }
            if (navHref.isNotEmpty()) {
                val nav = read(rel(navHref))
                val tocBlock = Regex("""<nav\b[^>]*epub:type\s*=\s*"[^"]*\btoc\b[^"]*"[^>]*>(.*?)</nav>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(nav)?.groupValues?.get(1) ?: nav
                for (a in Regex("""<a\b[^>]*?\bhref\s*=\s*"([^"]+)"[^>]*>(.*?)</a>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(tocBlock))
                    tocAdd(navHref, a.groupValues[1], a.groupValues[2])
            }

            // every image file in the zip, by normalized full path (for resolving chapter <img> refs)
            val zipImages = HashMap<String, String>()           // lower-case path -> real entry name
            val entryByKey = HashMap<String, String>()          // ngimg key -> entry name (for the lazily read lead image)
            for (e in zip.names()) {
                val nm = e.replace('\\', '/')
                if (Regex("""\.(jpe?g|png|gif|webp|bmp|svg)$""", RegexOption.IGNORE_CASE).containsMatchIn(nm))
                    zipImages[nm.lowercase()] = e
            }
            fun collectImages(html: String, chapDir: String): String =
                Regex("""<img\b[^>]*?\bsrc\s*=\s*"([^"]+)"[^>]*/?>""", RegexOption.IGNORE_CASE).replace(html) { m ->
                    val src = m.groupValues[1].trim()
                    if (src.startsWith("data:", true) || src.startsWith("http", true)) return@replace m.value
                    var norm = normPath(if (src.startsWith("/")) "" else chapDir, src.trimStart('/'))
                    var entry = zipImages[norm.lowercase()]
                    if (entry == null) {   // odd relative bases — fall back to filename match
                        val name = norm.substringAfterLast('/')
                        val k = zipImages.keys.firstOrNull { it.endsWith("/" + name.lowercase()) || it == name.lowercase() }
                            ?: return@replace ""
                        entry = zipImages[k]!!; norm = k
                    }
                    var key = norm.replace('/', '_')
                    key = key.replace(Regex("""[\\/:*?"<>|]"""), "_")   // key doubles as a filename
                    entryByKey[key] = entry
                    if (withImages && !book.images.containsKey(key))
                        book.images[key] = (zip.open(entry) ?: return@replace "").use { it.readBytes() }
                    "<img src=\"ngimg://$key\"/>"
                }

            val secs = mutableListOf<Sec>()
            var n = 0
            for (sp in Regex("""<itemref\b[^>]*?/?>""", RegexOption.IGNORE_CASE).findAll(opf)) {
                val idref = attr(sp.value, "idref")
                val item = manifest[idref] ?: continue
                val (href, mt) = item
                if (!(mt.contains("html") || href.endsWith(".xhtml", true) || href.endsWith(".html", true))) continue
                if (navHref.isNotEmpty() && href == navHref) continue            // the EPUB3 contents page is not a chapter
                val chapPath = rel(href)
                val xhtml = read(chapPath)
                if (xhtml.isEmpty()) continue
                n++
                val chapDir = if (chapPath.contains('/')) chapPath.substringBeforeLast('/') else ""
                var raw = bodyInner(xhtml)
                raw = svgToImg(raw)
                raw = collectImages(raw, chapDir)
                val body = sanitize(raw)
                val hasImg = body.contains("<img", true)
                val textLen = plainText(body).length
                if (!hasImg && textLen < 2) continue                              // empty page
                val listed = titles[normPath("", href)]
                val title = listed?.takeIf { it.isNotBlank() }
                    ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(xhtml)
                        ?.groupValues?.get(1)?.trim()
                    ?: "Chapter $n"
                secs.add(Sec(title, body, hasImg && textLen <= PICTURE_PAGE_TEXT, listed != null, STARTS_WITH_HEADING.containsMatchIn(body)))
            }
            secs.firstOrNull()?.takeIf { it.pictureOnly }?.let { f ->
                Regex("""ngimg://([^"'\s>]+)""").find(f.body)?.let { book.leadImageKey = it.groupValues[1] }
            }
            if (!withImages) {      // the only picture still needed: an illustration-only first page serves as the cover
                val lk = book.leadImageKey
                if (lk != null && book.cover == null)
                    entryByKey[lk]?.let { en -> zip.open(en)?.use { st -> book.images[lk] = st.readBytes() } }
            }
            book.chapters.addAll(mergeSections(secs))
            if (book.chapters.isEmpty()) throw IllegalStateException("EPUB had no readable chapters.")
            return book
        }
    }

    // ---------------- one reader chapter per real chapter (not per file) ----------------

    /** A picture page has at most this much text (a caption, "Illustration 3"…). */
    private const val PICTURE_PAGE_TEXT = 30

    private val STARTS_WITH_HEADING = Regex("""^\s*(?:<(?!/?h[1-6]\b)[^>]*>\s*)*<h[1-6]\b""", RegexOption.IGNORE_CASE)

    private fun plainText(html: String) =
        html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace('\u00A0', ' ').trim()

    /** One spine file of the epub. */
    class Sec(val title: String, val body: String, val pictureOnly: Boolean, val listed: Boolean, val heading: Boolean)

    /**
     * Light-novel epubs put each illustration on its own page and often split the chapter around it:
     * [text] [picture] [rest of the text]. Each file used to become its own reader chapter, so one chapter took three
     * Next presses. Now illustrations stay inside the chapter they belong to:
     *  - a picture page followed by a chapter opener (listed in the book's contents under a new title, or starting with a heading) is that
     *    chapter's opening illustration; leading pictures (before any chapter) go to the first chapter the same way;
     *  - any other picture page is a mid-chapter illustration and joins the chapter before it;
     *  - text right after an illustration that the contents doesn't list (or lists under the same title) and that has no heading
     *    of its own is the rest of that chapter.
     * Chapters, prologues, bonus stories etc. that the book lists (or that start with a heading) stay separate, so Prev/Next
     * move between those only. A book made only of picture pages (a manga epub) is left page-per-chapter.
     */
    fun mergeSections(secs: List<Sec>): List<Pair<String, String>> {
        if (secs.all { it.pictureOnly }) return secs.map { it.title to it.body }
        val out = ArrayList<Pair<String, String>>()
        var pending = ""          // illustrations waiting for the chapter that follows
        var afterPicture = false  // the last thing merged was a mid-chapter illustration
        for ((i, s) in secs.withIndex()) {
            if (s.pictureOnly) {
                if (s.listed) { out.add(s.title to (pending + s.body)); pending = ""; afterPicture = false; continue }   // cover, "Illustrations"…
                val next = secs.drop(i + 1).firstOrNull { !(it.pictureOnly && !it.listed) }
                val opensChapter = next != null && ((next.listed && next.title != out.lastOrNull()?.first) || next.heading)
                if (out.isEmpty() || opensChapter) pending += s.body + "\n"
                else { out[out.lastIndex] = out.last().first to (out.last().second + "\n" + s.body); afterPicture = true }
                continue
            }
            val continues = out.isNotEmpty() && afterPicture && pending.isEmpty() &&
                ((!s.listed && !s.heading) || s.title == out.last().first)
            if (continues) out[out.lastIndex] = out.last().first to (out.last().second + "\n" + s.body)
            else out.add(s.title to (pending + s.body))
            pending = ""; afterPicture = false
        }
        return out
    }

    fun bodyInner(xhtml: String): String =
        Regex("""<body[^>]*>(.*)</body>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xhtml)?.groupValues?.get(1) ?: xhtml

    /** Images are kept — collectImages has already rewritten their srcs to ngimg:// keys. */
    fun sanitize(htmlIn: String): String {
        var html = htmlIn
        html = html.replace(Regex("""<script\b.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        html = html.replace(Regex("""<style\b.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        html = html.replace(Regex("""<(iframe|object|embed|video|audio|svg)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        html = html.replace(Regex("""\son\w+\s*=\s*("[^"]*"|'[^']*'|\S+)""", RegexOption.IGNORE_CASE), "")
        html = html.replace(Regex("""\s(style|class|id)\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE), "")
        return html
    }

    /** Full-page LN illustrations wrapped as <svg><image xlink:href…></svg> → plain img (before sanitize strips svg). */
    private fun svgToImg(html: String): String =
        Regex("""<svg\b[^>]*>.*?<image\b[^>]*?(?:xlink:href|href)\s*=\s*"([^"]+)"[^>]*/?>.*?</svg>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .replace(html) { "<img src=\"${it.groupValues[1]}\"/>" }

    private fun normPath(dir: String, srcIn: String): String {
        val src = java.net.URLDecoder.decode(srcIn.substringBefore('#').substringBefore('?'), "UTF-8").replace('\\', '/')
        val parts = if (dir.isNotEmpty()) dir.split('/').toMutableList() else mutableListOf()
        for (p in src.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    /** Replaces ngimg:// refs (open epub) and ../images/ refs (novel folder) with data: URIs. */
    fun inlineImages(html: String, images: Map<String, ByteArray>?, folder: File?): String =
        Regex("""<img\b[^>]*?\bsrc\s*=\s*"([^"]+)"[^>]*/?>""", RegexOption.IGNORE_CASE).replace(html) { m ->
            val src = m.groupValues[1]
            val bytes: ByteArray? = when {
                src.startsWith("ngimg://", true) -> images?.get(src.removePrefix("ngimg://"))
                src.startsWith("../images/", true) || src.startsWith("images/", true) ->
                    folder?.let { f ->
                        val file = File(File(f, "images"), java.net.URLDecoder.decode(src, "UTF-8").substringAfterLast('/'))
                        if (file.exists()) file.readBytes() else null
                    }
                else -> return@replace m.value                // data:/http srcs pass through
            }
            if (bytes == null) "" else "<img src=\"${dataUrl(bytes, src.substringAfterLast('.', "jpg"))}\"/>"
        }

    fun dataUrl(bytes: ByteArray, ext: String): String {
        val mime = when (ext.lowercase()) {
            "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "svg" -> "image/svg+xml"; "bmp" -> "image/bmp"; else -> "image/jpeg"
        }
        return "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}
