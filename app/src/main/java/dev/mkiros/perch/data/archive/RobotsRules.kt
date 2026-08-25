package dev.mkiros.perch.data.archive

import java.net.URI

/**
 * A `robots.txt`'s `Disallow:` rules for the `*` group (RFC 9309), used by
 * [dev.mkiros.perch.data.repo.BackfillRepository] to skip a candidate URL politely rather
 * than fetch it — the same standard [ArchiveDiscovery] already reads for `Sitemap:`, just
 * the other directive from the same file.
 *
 * Only the wildcard group is read. RFC 9309 §2.2.1's product-token matching (a group
 * addressed to `Perch` specifically) would need a second pass over the same lines for a
 * case no real robots.txt in the corpus exercises; `*` is the group every site actually
 * writes, and it is what every well-behaved crawler falls back to regardless.
 */
class RobotsRules(private val disallowedPaths: List<String>) {

    /** True if [url]'s path starts with a disallowed prefix — RFC 9309 §2.2.2's match rule. */
    fun disallows(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return disallowedPaths.any { it.isNotEmpty() && path.startsWith(it) }
    }

    companion object {
        val NONE = RobotsRules(emptyList())

        fun parse(body: String): RobotsRules {
            var inWildcardGroup = false
            val disallow = mutableListOf<String>()
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
                }
            }
            return RobotsRules(disallow)
        }
    }
}
