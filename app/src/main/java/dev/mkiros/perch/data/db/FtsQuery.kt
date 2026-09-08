package dev.mkiros.perch.data.db

/**
 * Turns what the reader typed into something SQLite's full-text parser will accept
 * (S09, #28).
 *
 * FTS4's `MATCH` operand is not SQL. It is a small query language of its own — phrases,
 * `NEAR`, `AND`/`OR`/`NOT`, prefix `*`, column filters — parsed *after* the statement is
 * bound, which is why parameterising the query protects against injection but not against
 * a syntax error. A reader half-way through typing `"strava` has bound a perfectly safe
 * string that opens a phrase and never closes it, and the result is
 * `SQLiteException: fts: syntax error` thrown from the list the reader is watching.
 *
 * So none of that language is exposed. Everything that is not a letter or a digit is a
 * separator, the surviving tokens are joined with a **space** — every word has to appear
 * somewhere in the article, which is what a reader means by typing two words — and the
 * last token gets a `*` so that results narrow with each keystroke instead of appearing
 * only when a word is finished.
 *
 * **A space, and deliberately not the word `AND`.** SQLite has two FTS query syntaxes and
 * only the *enhanced* one — a compile-time option — reads `AND` as an operator; under the
 * standard syntax it is an ordinary term, so `creepy AND crawlies*` silently asks for
 * articles containing the word "and" as well. Both syntaxes treat whitespace between terms
 * as an implicit AND, so a space is the join that means the same thing wherever the query
 * lands. This is not theoretical: PLAN-9 S12's live gate 13 found “Creepy crawlies” by one
 * of its own words and lost it under two, because that article never says "and" —
 * `EntrySearchTest` pins the case, on a fixture with no "and" anywhere in it.
 *
 * Tokens are lowercased for one reason beyond tidiness: `AND`, `OR`, `NOT` and `NEAR` are
 * keywords **only in uppercase** where they are keywords at all, so a reader searching for
 * the band NEAR or for the word AND gets an article rather than a parse error or, worse,
 * an operator.
 *
 * Prefix matching is on the last token only. `str* fit*` would ask the index for every
 * article starting with either stem and then intersect two large sets; the finished words
 * a reader has already typed are exact, and only the one under the cursor is a guess.
 */
object FtsQuery {

    /**
     * The `MATCH` expression for [raw], or null when there is nothing to ask.
     *
     * Null is not an empty result set — it is the absence of a question, and the caller
     * renders "type something" rather than "nothing found". A query of pure punctuation or
     * pure emoji reduces to no tokens and is null for the same reason.
     */
    fun from(raw: String): String? {
        val tokens = raw
            .map { if (it.isLetterOrDigit()) it.lowercaseChar() else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens
            .mapIndexed { i, token -> if (i == tokens.lastIndex) "$token*" else token }
            .joinToString(separator = " ")
    }
}
