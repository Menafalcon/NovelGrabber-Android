package com.novelgrabber.app

import java.io.File

/**
 * Turns an external .epub into a regular library novel — the exact same on-disk shape a download
 * produces (meta.json + chapters/cXXXXX.xhtml + cover.*), plus an images/ folder for illustrations.
 * That's what gives imported books their cover, author, chapter count, progress bar, categories,
 * "Read" / export / share menu — i.e. they behave like downloaded novels instead of loose files.
 */
object EpubImport {

    private val IMG_REF = Regex("""ngimg://([^"'\s>]+)""")

    /** Imports run on several threads — picking a free folder name and creating it must be one atomic step. */
    private val reserveLock = Any()

    /** Returns the new novel folder. Throws if the epub can't be read (nothing is left behind). */
    fun toNovel(epub: File, key: String, root: File = Library.root): File = fromBook(EpubRead.load(epub), key, root)

    /** Full COPY of an already parsed book (chapters + illustrations written to disk). */
    fun fromBook(book: EpubBook, key: String, rootIn: File = Library.root): File {
        val root = rootIn.apply { mkdirs() }

        val base = Library.sanitize(book.title)
        var folder = File(root, base)
        val meta: NovelMeta
        synchronized(reserveLock) {
            var k = 2
            while (folder.exists()) { folder = File(root, "$base ($k)"); k++ }
            meta = Library.loadOrCreate(folder, book.title, "")       // creates folder + chapters/ (reserves the name)
        }

        try {
            meta.key = key
            meta.author = book.author

            // ---- illustrations: save once under images/, chapters point at ../images/<file> ----
            val imgDir = File(folder, "images")
            val diskNames = HashMap<String, String>()
            fun diskName(imgKey: String): String = diskNames.getOrPut(imgKey) {
                var n = imgKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (n.length > 100) n = n.takeLast(100)
                var cand = n
                var i = 2
                while (diskNames.containsValue(cand)) { cand = "${i}_$n"; i++ }
                cand
            }

            // ---- chapters ----
            book.chapters.forEachIndexed { i, ch ->
                val title = ch.first
                val html = IMG_REF.replace(ch.second) { m ->
                    val imgKey = m.groupValues[1]
                    val bytes = book.images[imgKey]
                    if (bytes == null) m.value
                    else {
                        val dn = diskName(imgKey)
                        val f = File(imgDir, dn)
                        if (!f.exists()) { imgDir.mkdirs(); f.writeBytes(bytes) }
                        "../images/$dn"
                    }
                }
                val num = i + 1
                val file = "chapters/c" + String.format("%05d", num) + ".xhtml"
                File(folder, file).writeText(Library.chapterXhtmlHtml(title, html))
                meta.chapters.add(ChapterMeta(num, num, title, file, ""))
            }

            writeCover(folder, meta, book)

            Library.save(meta)
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
        return folder
    }

    /** The epub's declared cover, else an illustration-only first page → cover.* (+ small thumbnail) in the novel folder. */
    private fun writeCover(folder: File, meta: NovelMeta, book: EpubBook) {
        // ---- cover: the epub's declared cover, else an illustration-only first page ----
        var coverBytes: ByteArray? = book.cover
        var coverExt = book.coverExt
        if (coverBytes == null) {
            val imgKey = book.leadImageKey          // set by EpubRead when the first page is illustration-only
            val ext = imgKey?.substringAfterLast('.', "jpg")?.lowercase() ?: "jpg"
            if (imgKey != null && ext != "svg") { coverBytes = book.images[imgKey]; coverExt = ext }
        }
        val cb = coverBytes
        if (cb != null && cb.size > 512) {
            var ext = if (coverExt == "png" || coverExt == "webp" || coverExt == "gif") coverExt else "jpg"
            var data = checkNotNull(cb)
            // a 3-5 MB cover only slows the library and the reader down — keep a sharp but reasonable copy
            if (cb.size > 400_000) Library.scaledJpeg(cb, 1200)?.let { data = it; ext = "jpg" }
            File(folder, "cover.$ext").writeBytes(data)
            meta.cover = "cover.$ext"
            // small thumbnail for the library list, made while the bytes are already in memory
            Library.scaledJpeg(cb, 320)?.let { Library.thumbFile(folder).writeBytes(it) }
        }
    }

    /**
     * LINKED import: nothing of the book is copied. The novel folder only holds meta.json (title, author, chapter titles,
     * the original's URI) plus a small cover + thumbnail, so the library shows it like any other novel while the
     * reader opens the original epub in place.
     */
    fun linkNovel(book: EpubBook, key: String, uri: String, rootIn: File = Library.root): File {
        val root = rootIn.apply { mkdirs() }
        val base = Library.sanitize(book.title)
        var folder = File(root, base)
        val meta: NovelMeta
        synchronized(reserveLock) {
            var k = 2
            while (folder.exists()) { folder = File(root, "$base ($k)"); k++ }
            meta = Library.loadOrCreate(folder, book.title, "")
        }
        try {
            meta.key = key
            meta.author = book.author
            meta.epubUri = uri
            File(folder, "chapters").deleteRecursively()          // a linked novel has no chapter files
            book.chapters.forEachIndexed { i, ch -> meta.chapters.add(ChapterMeta(i + 1, i + 1, ch.first, "", "")) }
            writeCover(folder, meta, book)
            Library.save(meta)
        } catch (e: Throwable) {
            folder.deleteRecursively()
            throw e
        }
        return folder
    }

    /**
     * Turns a novel that an older version COPIED into the app into a linked one and deletes the copy.
     * Reading progress is kept (it is keyed by the folder name). Returns the bytes freed, or -1 when the
     * epub does not match the stored copy (then nothing is touched).
     */
    fun relink(folder: File, book: EpubBook, uri: String): Long {
        val meta = Library.loadOrCreate(folder, "", "")
        if (meta.epubUri.isNotEmpty()) return 0L
        if (meta.chapters.size != book.chapters.size) return -1L
        val dirs = listOf(File(folder, "chapters"), File(folder, "images"))
        val freed = dirs.sumOf { sizeOf(it) }
        meta.epubUri = uri
        meta.chapters.forEach { it.file = "" }
        Library.save(meta, bumpUpdated = false)                  // write the link FIRST, only then delete the copy
        dirs.forEach { it.deleteRecursively() }
        return freed
    }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)
}
