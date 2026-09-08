package social.tbone.util

/**
 * Strips tracking parameters from URLs so pasted/shared links stay clean.
 *
 * Removes:
 *  - any `utm_*` marketing parameter (UTM family),
 *  - a known list of tracker IDs (fbclid, gclid, msclkid, twclid, ttclid,
 *    igshid, yclid, mc_cid, mc_eid, dclid, gbraid, wbraid, li_fat_id, …),
 *  - common referral/redirect helpers (ref_src, ref_url, mkt_tok, …).
 *
 * The host, path and fragment are preserved untouched — only the tracking
 * query parameters go away, so links still work.
 */
object LinkCleaner {

    private val TRACKER_PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "msclkid", "twclid", "ttclid", "igshid",
        "igsh", "yclid", "dclid", "gbraid", "wbraid", "li_fat_id", "mc_cid",
        "mc_eid", "vero_id", "vero_conv", "_hsenc", "_hsmi", "hscctracking",
        "mkt_tok", "ref_src", "ref_url", "spm", "scm", "cmp", "epik", "fbp",
        "fb_action_ids", "fb_action_types", "rb_clickid", "sccid", "btid",
        "wickedid", "ml_subscriber", "ml_subscriber_hash", "hmb_campaign",
        "hmb_medium", "hmb_source", "hmb_content", "tracking_id", "tracker",
        "s_cid", "irclickid", "ranmid", "s_kwcid",
    )

    private val UTM_PREFIXES = listOf("utm_", "ga_", "_ga", "_gl")

    private val URL_REGEX = Regex("""https?://[^\s<>"')\]]+""")

    /** Returns [url] with tracking query parameters removed. */
    fun stripTrackingParams(url: String): String {
        val clean = url.trim()
        if (!clean.contains('?')) return clean
        val qIndex = clean.indexOf('?')
        val base = clean.substring(0, qIndex)
        val rest = clean.substring(qIndex + 1)
        val fragmentIndex = rest.indexOf('#')
        val queryPart = if (fragmentIndex >= 0) rest.substring(0, fragmentIndex) else rest
        val fragment = if (fragmentIndex >= 0) rest.substring(fragmentIndex) else ""

        if (queryPart.isBlank()) return base + fragment

        val kept = queryPart.split('&').filter { pair ->
            val name = pair.substringBefore('=').lowercase()
            !TRACKER_PARAMS.contains(name) && !UTM_PREFIXES.any { name.startsWith(it) }
        }
        if (kept.isEmpty()) return base + fragment
        return "$base?${kept.joinToString("&")}$fragment"
    }

    /** Strips tracking params from every http(s) URL found in [text]. */
    fun cleanUrlsInText(text: String): String =
        URL_REGEX.replace(text) { m -> stripTrackingParams(m.value) }
}
