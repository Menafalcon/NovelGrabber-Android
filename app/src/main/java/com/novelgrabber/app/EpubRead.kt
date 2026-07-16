package com.novelgrabber.app

import java.io.File
import java.util.zip.ZipFile

class EpubBook(
    var title: String = "",
    var author: String = "",
    var cover: ByteArray? = null,
    var coverExt: String = "jpg"
) {
    val chapters = mutableListOf<Pair<String, String>>()   // title -> body html
    /** Illustrations pulled from the epub, keyed by flattened zip path.
     *  Chapter bodies reference them as ngimg://<key> until inlineImages() turns them into data: URIs. */
    val images = LinkedHashMap<String, ByteArray>()
}

/** Parses an external .epub into reader-ready chapters (spine order, NCX titles). Port of desktop EpubRead. */
object EpubRead {

    fun load(epubFile: File): EpubBook {
        ZipFile(epubFile).use { zip ->
            fun read(name: String): String {
                val e = zip.getEntry(name) ?: zip.getEntry(name.replace('\\', '/')) ?: return ""
                return zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            val container = read("META-INF/container.xml")
            var opfPath = Regex("""full-path="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(container)?.groupValues?.get(1) ?: ""
            if (opfPath.isEmpty())
                opfPath = zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", true) }?.name ?: ""
            if (opfPath.isEmpty()) throw IllegalStateException("Not a valid EPUB (no OPF found).")
            val opf = read(opfPath)
            val baseDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""
            fun rel(href: String) = (if (baseDir.isEmpty()) "" else "$baseDir/") + java.net.URLDecoder.decode(href, "UTF-8")

            fun meta(tag: String): String =
                Regex("<$tag[^>]*>([^<]*)</$tag>", RegexOption.IGNORE_CASE).find(opf)
                    ?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it, 0).toString().trim() } ?: ""

            val book = EpubBook(
                title = meta("dc:title").ifBlank { epubFile.nameWithoutExtension },
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
            if (coverHref.isEmpty())
                coverHref = manifest.values.firstOrNull { it.second.startsWith("image") && it.first.contains("cover", true) }?.first ?: ""
            if (coverHref.isNotEmpty()) {
                zip.getEntry(rel(coverHref))?.let { ce ->
                    book.cover = zip.getInputStream(ce).use { it.readBytes() }
                    book.coverExt = when (coverHref.substringAfterLast('.', "jpg").lowercase()) {
                        "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpg"
                    }
                }
            }

            // NCX titles
            val titles = mutableMapOf<String, String>()
            val ncxHref = manifest.values.firstOrNull { it.second.contains("dtbncx") || it.first.endsWith(".ncx", true) }?.first
            if (!ncxHref.isNullOrEmpty()) {
                val ncx = read(rel(ncxHref))
                for (np in Regex("""<navPoint\b.*?</navPoint>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(ncx)) {
                    val lb = Regex("""<text>\s*([^<]*?)\s*</text>""", RegexOption.IGNORE_CASE).find(np.value)
                    val sr = Regex("""<content[^>]*src="([^"#]+)""", RegexOption.IGNORE_CASE).find(np.value)
                    if (lb != null && sr != null) {
                        val key = java.net.URLDecoder.decode(sr.groupValues[1], "UTF-8")
                        if (!titles.containsKey(key))
                            titles[key] = android.text.Html.fromHtml(lb.groupValues[1], 0).toString()
                    }
                }
            }

            // every image file in the zip, by normalized full path (for resolving chapter <img> refs)
            val zipImages = HashMap<String, java.util.zip.ZipEntry>()
            for (e in zip.entries()) {
                val nm = e.name.replace('\\', '/')
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
                    if (!book.images.containsKey(key))
                        book.images[key] = zip.getInputStream(entry).use { it.readBytes() }
                    "<img src=\"ngimg://$key\"/>"
                }

            var n = 0
            for (sp in Regex("""<itemref\b[^>]*?/?>""", RegexOption.IGNORE_CASE).findAll(opf)) {
                val idref = attr(sp.value, "idref")
                val item = manifest[idref] ?: continue
                val (href, mt) = item
                if (!(mt.contains("html") || href.endsWith(".xhtml", true) || href.endsWith(".html", true))) continue
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
                if (!hasImg && body.replace(Regex("<[^>]+>"), "").trim().length < 2) continue   // keep illustration-only pages
                val title = titles[href]?.takeIf { it.isNotBlank() }
                    ?: Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(xhtml)
                        ?.groupValues?.get(1)?.trim()
                    ?: "Chapter $n"
                book.chapters.add(title to body)
            }
            if (book.chapters.isEmpty()) throw IllegalStateException("EPUB had no readable chapters.")
            return book
        }
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
