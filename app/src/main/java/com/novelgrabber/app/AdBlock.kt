package com.novelgrabber.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight ad/tracker blocker for the built-in browser. Host-suffix matching against the
 * networks novel sites actually use (adsterra/monetag/propeller/google ads/native widgets)
 * plus a couple of URL patterns for popunders. Deliberately conservative so Cloudflare
 * challenges and site functionality never break.
 */
object AdBlock {

    private val HOSTS = hashSetOf(
        // google ads & tracking
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adsense.google.com",
        // big exchanges / SSPs
        "adnxs.com", "criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com",
        "openx.net", "casalemedia.com", "33across.com", "gumgum.com", "sharethrough.com",
        "smartadserver.com", "smaato.net", "inmobi.com", "amazon-adsystem.com",
        "adsafeprotected.com", "adroll.com", "yieldmo.com", "sonobi.com", "indexww.com",
        // native "around the web" widgets
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com", "zergnet.com",
        // the popunder/push networks novel sites love
        "propellerads.com", "propellerclick.com", "adsterra.com", "adsterratech.com",
        "highperformanceformat.com", "effectiveratecpm.com", "profitableratecpm.com",
        "monetag.com", "onclickalgo.com", "onclasrv.com", "popads.net", "popcash.net",
        "poptm.com", "hilltopads.net", "clickadu.com", "adcash.com", "exoclick.com",
        "exosrv.com", "juicyads.com", "trafficjunky.com", "tsyndicate.com",
        "galaksion.com", "richads.com", "bidvertiser.com", "yllix.com",
        "creative-sb.com", "aclickads.com", "adskeeper.com",
        // crypto/casino banners (BC.GAME etc.)
        "bc.game", "bcgame.link", "a-ads.com", "coinzilla.io", "cointraffic.io", "adshares.net",
        // analytics/heatmaps that just slow pages down
        "scorecardresearch.com", "quantserve.com", "hotjar.com", "mouseflow.com",
        "an.yandex.ru", "mc.yandex.ru",
    )

    private val URL_PAT = Regex("""pop(under|up)|adsbygoogle|/ads/""", RegexOption.IGNORE_CASE)

    /** mainFrame: page navigations only block on HOST matches (an ad-network navigation is a
     *  popunder) — URL patterns could false-positive a legit chapter URL. */
    fun shouldBlock(url: String, mainFrame: Boolean): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() ?: return false } catch (e: Exception) { return false }
        // suffix match: x.y.doubleclick.net hits "doubleclick.net"
        val parts = host.split('.')
        for (i in 0 until parts.size - 1) {
            if (parts.subList(i, parts.size).joinToString(".") in HOSTS) return true
        }
        return !mainFrame && URL_PAT.containsMatchIn(url)
    }

    /** Empty response WebView accepts as "blocked". */
    fun blockedResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
