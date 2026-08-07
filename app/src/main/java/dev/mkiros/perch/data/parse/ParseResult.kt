package dev.mkiros.perch.data.parse

/**
 * The outcome of [FeedParser.parse]. SPEC.md §5 forbids an exception escaping the parser,
 * so every way a document can disappoint us arrives here instead.
 *
 * @param reason on [Failure], a sentence fit to show the user — this is what the
 *   add-source sheet (T23) and the per-source error banner (T26) put on screen.
 */
sealed interface ParseResult {

    data class Success(val feed: ParsedFeed) : ParseResult

    data class Failure(val reason: String) : ParseResult
}
