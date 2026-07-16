package com.novelgrabber.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/**
 * Fully local text → paginated PDF (A4) using PdfDocument + StaticLayout.
 * Replaces the desktop's Chromium print path, which doesn't exist on Android.
 */
object PdfWriter {

    private const val PAGE_W = 595   // A4 @72dpi
    private const val PAGE_H = 842
    private const val MARGIN = 52

    private fun layoutFor(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, 1.35f)
            .setIncludePad(false)
            .build()

    /** chapters: title -> plain text (paragraphs separated by \n\n) */
    fun write(bookTitle: String, author: String, chapters: List<Pair<String, String>>, out: File) {
        val doc = PdfDocument()
        val contentW = PAGE_W - MARGIN * 2
        val contentH = PAGE_H - MARGIN * 2

        val bodyPaint = TextPaint().apply { color = Color.BLACK; textSize = 11.5f; typeface = Typeface.SERIF; isAntiAlias = true }
        val headPaint = TextPaint().apply { color = Color.BLACK; textSize = 15f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); isAntiAlias = true }
        val titlePaint = TextPaint().apply { color = Color.BLACK; textSize = 24f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); isAntiAlias = true }
        val subPaint = TextPaint().apply { color = Color.DKGRAY; textSize = 13f; typeface = Typeface.SERIF; isAntiAlias = true }

        var pageNum = 1

        // ---- title page ----
        run {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create())
            val c = page.canvas
            val tl = layoutFor(bookTitle, titlePaint, contentW, Layout.Alignment.ALIGN_CENTER)
            c.save(); c.translate(MARGIN.toFloat(), PAGE_H * 0.38f); tl.draw(c); c.restore()
            if (author.isNotBlank()) {
                val al = layoutFor("by $author", subPaint, contentW, Layout.Alignment.ALIGN_CENTER)
                c.save(); c.translate(MARGIN.toFloat(), PAGE_H * 0.38f + tl.height + 24); al.draw(c); c.restore()
            }
            doc.finishPage(page)
        }

        // ---- chapters ----
        for ((chTitle, text) in chapters) {
            var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create())
            var canvas = page.canvas
            var y = MARGIN

            val head = layoutFor(chTitle, headPaint, contentW)
            canvas.save(); canvas.translate(MARGIN.toFloat(), y.toFloat()); head.draw(canvas); canvas.restore()
            y += head.height + 20

            val body = layoutFor(text, bodyPaint, contentW)
            var line = 0
            while (line < body.lineCount) {
                val lineTop = body.getLineTop(line)
                // how many lines fit on the remaining space of this page?
                var end = line
                while (end < body.lineCount && body.getLineBottom(end) - lineTop <= (PAGE_H - MARGIN) - y) end++
                if (end == line) {   // nothing fits → new page
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create())
                    canvas = page.canvas
                    y = MARGIN
                    continue
                }
                canvas.save()
                canvas.clipRect(MARGIN.toFloat(), y.toFloat(), (PAGE_W - MARGIN).toFloat(),
                    (y + (body.getLineBottom(end - 1) - lineTop)).toFloat())
                canvas.translate(MARGIN.toFloat(), (y - lineTop).toFloat())
                body.draw(canvas)
                canvas.restore()
                y += body.getLineBottom(end - 1) - lineTop
                line = end
                if (line < body.lineCount) {   // more lines → next page
                    doc.finishPage(page)
                    page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum++).create())
                    canvas = page.canvas
                    y = MARGIN
                }
            }
            doc.finishPage(page)
        }

        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    /** Strip a chapter xhtml/html body down to plain text with paragraph breaks. */
    fun htmlToText(html: String): String {
        var t = html
        t = t.replace(Regex("""<script\b.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        t = t.replace(Regex("""<style\b.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        t = t.replace(Regex("""</p>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n\n")
        t = t.replace(Regex("<[^>]+>"), "")
        t = android.text.Html.fromHtml(t, 0).toString()
        t = t.replace(Regex("""\n{3,}"""), "\n\n")
        return t.trim()
    }
}
