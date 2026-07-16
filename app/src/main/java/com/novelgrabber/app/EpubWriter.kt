package com.novelgrabber.app

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB 2 writer — mimetype STORED first (spec), cover + NCX TOC. Port of the desktop writer. */
object EpubWriter {

    private fun ZipOutputStream.addText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    fun build(meta: NovelMeta, outFile: File? = null): File {
        val ordered = Library.ordered(meta).filter { File(meta.folder, it.file).exists() }
        if (ordered.isEmpty()) throw IllegalStateException("No chapter files to export.")

        val out = outFile ?: File(meta.folder, Library.sanitize(meta.title) + ".epub")

        val coverFile = if (meta.cover.isNotBlank()) File(meta.folder, meta.cover) else null
        val hasCover = coverFile != null && coverFile.exists()
        var coverExt = if (hasCover) coverFile!!.extension.lowercase() else "jpg"
        if (coverExt == "jpeg") coverExt = "jpg"
        val coverMime = when (coverExt) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

        ZipOutputStream(FileOutputStream(out)).use { zip ->
            // mimetype: first entry, STORED (needs precomputed size+crc)
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val e = ZipEntry("mimetype")
            e.method = ZipEntry.STORED
            e.size = mime.size.toLong()
            e.compressedSize = mime.size.toLong()
            e.crc = CRC32().apply { update(mime) }.value
            zip.putNextEntry(e)
            zip.write(mime)
            zip.closeEntry()

            zip.addText("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")

            zip.addText("OEBPS/style.css", """body{font-family:serif;line-height:1.6;margin:5%;}
h1,h2{font-family:sans-serif;text-align:center;}
.ch-title{margin:1em 0 1.5em;font-size:1.2em;}
p{margin:0 0 1em;text-indent:1.4em;}
.cover{margin:0;padding:0;text-align:center;}
.cover img{max-width:100%;height:auto;}""")

            if (hasCover) {
                zip.putNextEntry(ZipEntry("OEBPS/cover.$coverExt"))
                coverFile!!.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.addText("OEBPS/cover.xhtml", """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cover</title>
<link rel="stylesheet" type="text/css" href="style.css"/></head>
<body><div class="cover"><img src="cover.$coverExt" alt="cover"/></div></body></html>""")
            }

            for (c in ordered) {
                zip.putNextEntry(ZipEntry("OEBPS/" + c.file))
                File(meta.folder, c.file).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            val uid = "urn:uuid:novelgrabber-" + Library.sanitize(meta.title).replace(' ', '-') +
                    "-" + System.currentTimeMillis() / 1000

            val manifest = StringBuilder()
            val spine = StringBuilder()
            manifest.appendLine("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            manifest.appendLine("""    <item id="css" href="style.css" media-type="text/css"/>""")
            var coverMeta = ""
            if (hasCover) {
                coverMeta = """<meta name="cover" content="cover-img"/>"""
                manifest.appendLine("""    <item id="cover-img" href="cover.$coverExt" media-type="$coverMime"/>""")
                manifest.appendLine("""    <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>""")
                spine.appendLine("""    <itemref idref="cover-page"/>""")
            }

            val nav = StringBuilder()
            ordered.forEachIndexed { i, c ->
                val id = "ch" + String.format("%05d", i + 1)
                manifest.appendLine("""    <item id="$id" href="${c.file}" media-type="application/xhtml+xml"/>""")
                spine.appendLine("""    <itemref idref="$id"/>""")
                val label = c.title.ifBlank { if (c.num > 0) "Chapter ${c.num}" else "Chapter ${i + 1}" }
                nav.appendLine("""    <navPoint id="np${i + 1}" playOrder="${i + 1}">
      <navLabel><text>${Library.escape(label)}</text></navLabel>
      <content src="${c.file}"/>
    </navPoint>""")
            }

            zip.addText("OEBPS/content.opf", """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${Library.escape(meta.title)}</dc:title>
    <dc:creator opf:role="aut">${Library.escape(meta.author.ifBlank { "Unknown" })}</dc:creator>
    <dc:language>en</dc:language>
    <dc:identifier id="bookid">$uid</dc:identifier>
    <dc:source>${Library.escape(meta.source)}</dc:source>
    $coverMeta
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine toc="ncx">
$spine  </spine>
  ${if (hasCover) """<guide><reference type="cover" title="Cover" href="cover.xhtml"/></guide>""" else ""}
</package>""")

            zip.addText("OEBPS/toc.ncx", """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$uid"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${Library.escape(meta.title)}</text></docTitle>
  <navMap>
$nav  </navMap>
</ncx>""")
        }
        return out
    }
}
