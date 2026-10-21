package com.novelgrabber.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** PDFs from an imported folder are not copied into the app — only a link to the original is remembered (root/pdf_links.json). */
object PdfLinks {
    class Link(val name: String, val uri: String, val size: Long)

    private fun file() = File(Library.root, "pdf_links.json")

    @Synchronized fun all(): List<Link> {
        val out = ArrayList<Link>()
        try {
            val f = file()
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Link(o.optString("name"), o.optString("uri"), o.optLong("size", -1L)))
            }
        } catch (e: Exception) {}
        return out
    }

    /** False when a link to the same file (same name + size) is already there. */
    @Synchronized fun add(name: String, uri: String, size: Long): Boolean {
        val list = all().toMutableList()
        if (list.any { it.name.equals(name, true) && it.size == size }) return false
        list.add(Link(name, uri, size))
        try {
            Library.root.mkdirs()
            file().writeText(JSONArray().also { a -> list.forEach { l ->
                a.put(JSONObject().put("name", l.name).put("uri", l.uri).put("size", l.size)) } }.toString())
        } catch (e: Exception) { return false }
        return true
    }
}
