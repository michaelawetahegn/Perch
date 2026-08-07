package dev.mkiros.perch.data.parse

import java.nio.charset.Charset
import kotlin.math.min

/**
 * The one entry point into parsing, per SPEC.md §5.
 *
 * Dispatch is on the **root element** and nothing else — not the URL, not the
 * `Content-Type`, both of which real servers get wrong routinely. The root is found by
 * scanning a short prefix of the bytes rather than by building a tree, so a 5 MB HTML
 * page or 5 MB of noise is refused after a few kilobytes instead of being parsed first.
 *
 * Nothing here throws: every disappointment becomes [ParseResult.Failure].
 */
class FeedParser(
    private val rss: RssParser = RssParser(),
    private val atom: AtomParser = AtomParser(),
    private val rdf: RdfParser = RdfParser(),
) {

    fun parse(
        bytes: ByteArray,
        contentType: String? = null,
        requestUrl: String? = null,
    ): ParseResult {
        if (bytes.isEmpty()) return ParseResult.Failure("The server returned an empty document.")

        val charset = declaredCharset(bytes) ?: contentTypeCharset(contentType) ?: Charsets.UTF_8
        val prefix = String(bytes, 0, min(bytes.size, SNIFF_BYTES), charset)
        val root = rootElementName(prefix)
            ?: return ParseResult.Failure("That address did not return a feed.")

        val parser = when (root.substringAfterLast(':').lowercase()) {
            "rss" -> rss::parse
            "feed" -> atom::parse
            "rdf" -> rdf::parse
            "html" -> return ParseResult.Failure("That address is a web page, not a feed.")
            else -> return ParseResult.Failure("That address did not return a feed (its root element is <$root>).")
        }

        val document = runCatching { parseFeedXml(String(bytes, charset), requestUrl) }
            .getOrNull()
            ?: return ParseResult.Failure("The feed could not be read.")
        val feed = runCatching { parser(document, requestUrl) }.getOrNull()
            ?: return ParseResult.Failure("The feed is a <$root> document but is missing its contents.")

        return ParseResult.Success(feed)
    }

    /**
     * The name of the first element in [text], stepping over the XML declaration,
     * processing instructions, comments and the doctype that legitimately precede it.
     * Null when the prefix holds no element at all — which is the answer for plain text,
     * and for random bytes that happen to contain no `<` followed by a name.
     */
    private fun rootElementName(text: String): String? {
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf('<', cursor)
            if (open < 0) return null

            val closer = when {
                text.startsWith("<?", open) -> "?>"
                text.startsWith("<!--", open) -> "-->"
                text.startsWith("<!", open) -> ">"
                else -> null
            }
            if (closer != null) {
                val end = text.indexOf(closer, open)
                if (end < 0) return null
                cursor = end + closer.length
                continue
            }

            val name = buildString {
                var i = open + 1
                while (i < text.length && text[i].isNameChar()) append(text[i++])
            }
            if (name.isNotEmpty() && (name.first().isLetter() || name.first() == '_')) return name
            cursor = open + 1
        }
        return null
    }

    /**
     * The `encoding` of the XML declaration, which SPEC.md §5 ranks above the HTTP
     * charset. The declaration is ASCII by construction, so reading the head as
     * ISO-8859-1 is safe whatever the document turns out to be encoded in.
     */
    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, min(bytes.size, DECLARATION_BYTES), Charsets.ISO_8859_1)
        val declaration = head.substringAfter("<?xml", "").substringBefore("?>", "")
        return charsetOrNull(ENCODING.find(declaration)?.groupValues?.get(1))
    }

    private fun contentTypeCharset(contentType: String?): Charset? =
        charsetOrNull(CHARSET.find(contentType.orEmpty())?.groupValues?.get(1))

    /** An unknown or malformed charset name is no reason to reject a feed; UTF-8 follows. */
    private fun charsetOrNull(name: String?): Charset? {
        val trimmed = name?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Charset.forName(trimmed) }.getOrNull()
    }

    private fun Char.isNameChar(): Boolean =
        isLetterOrDigit() || this == ':' || this == '_' || this == '-' || this == '.'

    private companion object {
        /** Enough prefix for any preamble a real feed puts before its root element. */
        const val SNIFF_BYTES = 8 * 1024
        const val DECLARATION_BYTES = 256

        val ENCODING = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val CHARSET = Regex("""charset\s*=\s*["']?([^;"'\s]+)""", RegexOption.IGNORE_CASE)
    }
}
