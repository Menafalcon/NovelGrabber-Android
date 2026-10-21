package com.novelgrabber.app

/**
 * Turns any chapter body into a flat list of <p>…</p>, one per real paragraph.
 *
 * Downloaded chapters already look like that, but EPUB bodies often don't: a whole chapter inside one
 * <p> separated by <br>, text sitting in <div>s, or XHTML-style self-closed <p/> tags (browsers read
 * those as OPEN tags and swallow what follows). The reader highlights one <p> at a time and TTS reads
 * one <p> at a time, so a giant <p> meant "highlight (and read) the whole chapter" and you couldn't
 * skip around. Both the reader and TTS get their paragraphs from this, so indexes always line up.
 */
object Paragraphs {
    private const val BR = "\uE000"    // soft line break marker
    private const val BLK = "\uE001"   // block boundary marker
    private val INLINE = listOf("b", "strong", "i", "em", "u", "s", "sup", "sub")

    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val SELF_CLOSED = Regex("""<(p|div|span|a|b|i|em|strong|u|s|section|h[1-6])\b[^>]*/>""", RegexOption.IGNORE_CASE)
    private val H_OPEN = Regex("""<h[1-6]\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val H_CLOSE = Regex("""</h[1-6]\s*>""", RegexOption.IGNORE_CASE)
    private val BR_TAG = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BLOCK_TAG = Regex(
        """</?(?:p|div|section|article|aside|blockquote|ul|ol|li|dl|dt|dd|table|thead|tbody|tr|td|th|hr|center|pre|figure|figcaption|header|footer|nav|main)\b[^>]*>""",
        RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("[$BR$BLK]+")
    private val OTHER_TAG = Regex("""<(?!/?(?:b|strong|i|em|u|s|sup|sub|img)\b)[^>]+>""", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("<[^>]+>")
    private val OTHER_H3 = Regex("""<(?!/?(?:h3|b|strong|i|em|u|s|sup|sub)\b)[^>]+>""", RegexOption.IGNORE_CASE)
    private val RTL_CHAR = Regex("""[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]""")
    private val LTR_CHAR = Regex("""[A-Za-z]""")

    fun isRtlText(s: String): Boolean {
        val rtl = RTL_CHAR.findAll(s).count()
        if (rtl == 0) return false
        val ltr = LTR_CHAR.findAll(s).count()
        return rtl * 2 >= ltr
    }

    fun normalize(htmlIn: String): String {
        var h = htmlIn.replace(COMMENT, "")
        h = h.replace(SELF_CLOSED, "")                        // empty <p/> etc. — never real content
        h = h.replace(H_OPEN, BLK + "<h3>").replace(H_CLOSE, "</h3>$BLK")      // headings stay headings (not read aloud, like before)
        h = h.replace(BR_TAG, BR)
        h = h.replace(BLOCK_TAG, BLK)

        val out = StringBuilder()
        for (seg in h.split(SPLIT)) {
            var s = seg.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("<h3>") && s.endsWith("</h3>")) {         // a whole heading block
                val plain = s.replace(ANY_TAG, "").replace("&nbsp;", " ").replace('\u00A0', ' ')
                if (plain.isBlank()) continue
                val clean = s.replace(OTHER_H3, "")
                val dir = if (isRtlText(plain)) "rtl" else "auto"
                out.append(clean.replaceFirst("<h3>", "<h3 dir=\"$dir\">")).append("\n"); continue
            }
            s = s.replace(OTHER_TAG, "")                       // links/spans/leftovers → plain text
            for (t in INLINE) {                                // a tag split across paragraphs would leak formatting → drop it
                val open = Regex("<$t\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(s).count()
                val close = Regex("</$t\\s*>", RegexOption.IGNORE_CASE).findAll(s).count()
                if (open != close) s = s.replace(Regex("</?$t\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            }
            val hasImg = s.contains("<img", true)
            val text = s.replace(ANY_TAG, "").replace("&nbsp;", " ").replace('\u00A0', ' ').trim()
            if (text.isEmpty() && !hasImg) continue
            val dir = if (isRtlText(text)) "rtl" else "auto"
            out.append("<p dir=\"$dir\">").append(s.trim()).append("</p>\n")
        }
        return out.toString()
    }
}
