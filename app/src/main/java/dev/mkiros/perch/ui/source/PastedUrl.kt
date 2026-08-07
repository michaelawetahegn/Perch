package dev.mkiros.perch.ui.source

/** Anything shaped like `scheme:` at the front of an address. */
private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/**
 * Turns what a human pasted into something [dev.mkiros.perch.data.repo.FeedRepository]
 * can fetch.
 *
 * The repository deliberately does not do this — it treats its argument as an address and
 * reports `example.com` as unreachable, which is the honest answer to a question nobody
 * asked it. Readers paste bare hosts, and reader apps hand out `feed://` links; both mean
 * https. Anything that already names a scheme is left exactly as pasted, so a deliberate
 * `http://` intranet feed is not silently upgraded into a failure.
 */
fun normalizePastedUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() -> ""
        trimmed.startsWith("feed://", ignoreCase = true) ->
            "https://" + trimmed.removeRange(0, "feed://".length)
        trimmed.startsWith("feed:", ignoreCase = true) ->
            normalizePastedUrl(trimmed.removeRange(0, "feed:".length))
        SCHEME.containsMatchIn(trimmed) -> trimmed
        else -> "https://$trimmed"
    }
}
