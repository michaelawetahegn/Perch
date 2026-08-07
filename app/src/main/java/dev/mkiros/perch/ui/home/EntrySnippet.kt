package dev.mkiros.perch.ui.home

/**
 * The `Snippet (≤2 lines)` line of a row (DESIGN.md §5), once the headline above it has
 * had its say.
 *
 * Feeds derive a summary by truncating the body, and a great many bodies open by
 * restating their own title — link blogs almost always do. Rendered literally, the row
 * spends its widest two lines repeating the line above them, which is the difference
 * between a list that looks dense and one that is.
 *
 * The mirror of the rule `ArticleViewModel.standfirst` applies on the reading surface,
 * and deliberately the narrow version of it: only a summary that *opens* with the
 * headline is trimmed. A title that merely occurs somewhere later is load-bearing — cut
 * it and `Release: datasette 1.0a38 fixes …` becomes `Release: fixes …`.
 */
object EntrySnippet {

    /** [summary] with a restated [title] taken off the front, or null if nothing is left. */
    fun forTitle(title: String, summary: String?): String? {
        val text = summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val headline = title.trim()
        if (headline.isEmpty() || !text.startsWith(headline, ignoreCase = true)) return text
        return text.drop(headline.length)
            .trimStart(*LEAD_IN)
            .takeIf { it.length >= MIN_LENGTH }
    }

    /** Whatever joined the headline to the sentence after it, now joining nothing. */
    private val LEAD_IN = charArrayOf(
        ' ', ' ', '.', ',', ':', ';', '-', '–', '—', '·', '…',
    )

    /** Below this a remainder is a fragment, and a fragment reads worse than no line. */
    private const val MIN_LENGTH = 8
}
