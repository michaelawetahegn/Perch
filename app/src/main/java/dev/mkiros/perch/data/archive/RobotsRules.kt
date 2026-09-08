package dev.mkiros.perch.data.archive

import dev.mkiros.perch.data.parse.PageFetcher
import dev.mkiros.perch.data.parse.hostRoot
import dev.mkiros.perch.data.parse.pathOf

/**
 * One site's `robots.txt`, read once and yielding both directives Perch cares about
 * (RFC 9309): the `*` group's `Disallow:` rules, which
 * [dev.mkiros.perch.data.repo.BackfillRepository] uses to skip a candidate URL politely
 * rather than fetch it, and the [sitemaps] [ArchiveDiscovery] starts discovery from.
 * They come out of the same file, so they come out of the same fetch.
 *
 * Only the wildcard group is read for `Disallow:`. RFC 9309 §2.2.1's product-token
 * matching (a group addressed to `Perch` specifically) would need a second pass over the
 * same lines for a case no real robots.txt in the corpus exercises; `*` is the group every
 * site actually writes, and it is what every well-behaved crawler falls back to regardless.
 * `Sitemap:` is not a group member at all (§2.2.3) — it is read wherever it appears.
 */
class RobotsRules(
    private val disallowedPaths: List<String>,
    /** Every `Sitemap:` URL the file declared, in the order it declared them. */
    val sitemaps: List<String> = emptyList(),
) {

    /** True if [url]'s path starts with a disallowed prefix — RFC 9309 §2.2.2's match rule. */
    fun disallows(url: String): Boolean {
        val path = pathOf(url) ?: return false
        return disallowedPaths.any { it.isNotEmpty() && path.startsWith(it) }
    }

    companion object {
        val NONE = RobotsRules(emptyList())

        /**
         * The one place a `robots.txt` is fetched: `$root/robots.txt` for [siteUrl]'s host,
         * or [NONE] when there is no host or the file does not answer — a site without one
         * disallows nothing and declares no sitemap.
         */
        suspend fun fetch(fetcher: PageFetcher, siteUrl: String): RobotsRules {
            val root = hostRoot(siteUrl) ?: return NONE
            val page = fetcher.fetch("$root/robots.txt") ?: return NONE
            return parse(String(page.bytes, Charsets.UTF_8))
        }

        fun parse(body: String): RobotsRules {
            var inWildcardGroup = false
            val disallow = mutableListOf<String>()
            val sitemaps = mutableListOf<String>()
            for (raw in body.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                val sep = line.indexOf(':')
                if (sep < 0) continue
                val key = line.substring(0, sep).trim().lowercase()
                val value = line.substring(sep + 1).trim()
                when (key) {
                    // A new group starts at every user-agent line; only the ones naming
                    // `*` are ours, so re-entering a named group turns this back off.
                    "user-agent" -> inWildcardGroup = value == "*"
                    "disallow" -> if (inWildcardGroup && value.isNotEmpty()) disallow += value
                    "sitemap" -> if (value.isNotEmpty()) sitemaps += value
                }
            }
            return RobotsRules(disallow, sitemaps)
        }
    }
}
