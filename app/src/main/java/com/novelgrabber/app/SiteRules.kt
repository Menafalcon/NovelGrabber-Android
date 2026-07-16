package com.novelgrabber.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Same recipe format as the desktop sites.json (bundled in assets). */
class SiteRule(
    val content: List<String> = emptyList(),
    val novelTitle: List<String> = emptyList(),
    val chapterTitle: List<String> = emptyList(),
    val next: List<String> = emptyList(),
    val list: List<String> = emptyList(),
    val first: List<String> = emptyList(),
    val increment: Boolean = true,
    val preferIncrement: Boolean = false,
    val tocFrom: List<String> = emptyList(),
    val tabClick: List<String> = emptyList(),
    val nextClick: List<String> = emptyList()
) {
    companion object {
        private fun arr(o: JSONObject, vararg names: String): List<String> {
            for (n in names) {
                val a = o.optJSONArray(n) ?: continue
                return (0 until a.length()).map { a.getString(it) }
            }
            return emptyList()
        }
        fun fromJson(o: JSONObject) = SiteRule(
            content = arr(o, "Content", "content"),
            novelTitle = arr(o, "NovelTitle", "novelTitle"),
            chapterTitle = arr(o, "ChapterTitle", "chapterTitle"),
            next = arr(o, "Next", "next"),
            list = arr(o, "List", "list"),
            first = arr(o, "First", "first"),
            increment = o.optBoolean("Increment", o.optBoolean("increment", true)),
            preferIncrement = o.optBoolean("PreferIncrement", o.optBoolean("preferIncrement", false)),
            tocFrom = arr(o, "TocFrom", "tocFrom"),
            tabClick = arr(o, "TabClick", "tabClick"),
            nextClick = arr(o, "NextClick", "nextClick")
        )
    }
}

object SiteRules {
    private var rules: Map<String, SiteRule>? = null

    fun load(ctx: Context) {
        if (rules != null) return
        try {
            val json = ctx.assets.open("sites.json").bufferedReader().use { it.readText() }
            val o = JSONObject(json)
            val map = mutableMapOf<String, SiteRule>()
            for (k in o.keys()) map[k.lowercase()] = SiteRule.fromJson(o.getJSONObject(k))
            rules = map
        } catch (e: Exception) {
            rules = emptyMap()
        }
    }

    fun forHost(hostIn: String?): SiteRule {
        val host = (hostIn ?: "").lowercase()
        rules?.forEach { (k, v) ->
            if (host == k || host.endsWith(".$k") || host.endsWith(k)) return v
        }
        return SiteRule()
    }
}
