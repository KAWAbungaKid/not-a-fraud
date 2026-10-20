package com.maven.notafraud.core.guard

/** Website blocking inside phone browsers (the address bar is read by the accessibility guard). */
object Sites {
    /** Subject key prefix: "site:instagram.com". */
    const val PREFIX = "site:"

    /** Browser package -> view ids of its address bar (several, because browsers rename them between versions). */
    val URL_BAR_IDS: Map<String, List<String>> = mapOf(
        "com.android.chrome" to listOf("com.android.chrome:id/url_bar", "com.android.chrome:id/search_box_text"),
        "com.chrome.beta" to listOf("com.chrome.beta:id/url_bar"),
        "com.sec.android.app.sbrowser" to listOf(
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/sbrowser_url_bar",
            "com.sec.android.app.sbrowser:id/url_bar",
        ),
        "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
        "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
        "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "org.mozilla.firefox:id/url_bar_title"),
        "com.opera.browser" to listOf("com.opera.browser:id/url_field"),
        "com.duckduckgo.mobile.android" to listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
    )

    val BROWSERS: Set<String> = URL_BAR_IDS.keys

    /** Host from address-bar text ("instagram.com/reel/…", "https://m.facebook.com", "www.youtube.com"). */
    fun host(text: String): String? {
        val t = text.trim().lowercase()
        if (t.isEmpty() || ' ' in t) return null
        val noScheme = t.substringAfter("://", t)
        val h = noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return h.takeIf { '.' in it }
    }

    /** The blocked domain this address falls under (subdomains included), or null. */
    fun match(text: String, blocked: Set<String>): String? {
        val h = host(text) ?: return null
        return blocked.firstOrNull { d -> h == d || h.endsWith(".$d") }
    }

    fun label(subject: String) = subject.removePrefix(PREFIX)
}
