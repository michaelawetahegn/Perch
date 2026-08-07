package dev.mkiros.perch.data.opml

import dev.mkiros.perch.data.parse.attrNamed
import dev.mkiros.perch.data.parse.childElementsNamed
import dev.mkiros.perch.data.parse.localName
import dev.mkiros.perch.data.parse.parseFeedXml
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jsoup.nodes.Element

/** One source in an OPML document: what SPEC.md §9 keeps of a subscription, and no more. */
data class OpmlOutline(val title: String, val xmlUrl: String, val siteUrl: String? = null)

/** What a file offered as OPML turned out to be. */
sealed interface OpmlParse {

    /**
     * A readable document. [invalid] counts outlines that claimed to be sources but named
     * no usable address — they are reported to the user, not silently dropped.
     */
    data class Success(val outlines: List<OpmlOutline>, val invalid: Int) : OpmlParse

    /** Not an OPML document at all; [message] is already phrased for the user. */
    data class Malformed(val message: String) : OpmlParse
}

/**
 * The OPML 2.0 document format — the one thing every other reader agrees to speak.
 *
 * Writing is deliberately rigid and hand-rolled: a flat body of `type="rss"` outlines,
 * byte-predictable, so the export is diffable and the round-trip test asserts on the real
 * text rather than on a serializer's mood.
 *
 * Reading is the opposite. Exports in the wild nest sources in folders several deep,
 * disagree about whether the label lives in `text` or `title`, and carry outlines that
 * point nowhere. None of that is a reason to reject a file the user chose, so folders are
 * flattened, labels fall back, dead outlines are counted, and the only error is a document
 * that is not OPML — returned as a value, never thrown.
 */
object Opml {

    private val RFC_822 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    /** `perch-20260807.opml` — what the SAF create-document dialog is pre-filled with. */
    fun fileName(date: LocalDate): String = "perch-${DateTimeFormatter.BASIC_ISO_DATE.format(date)}.opml"

    /** Serializes [outlines] as OPML 2.0. Flat — v1 has no folders (SPEC.md §9). */
    fun write(outlines: List<OpmlOutline>, createdAt: Instant? = null): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<opml version="2.0">""")
        appendLine("  <head>")
        appendLine("    <title>Perch subscriptions</title>")
        if (createdAt != null) appendLine("    <dateCreated>${RFC_822.format(createdAt)}</dateCreated>")
        appendLine("  </head>")
        appendLine("  <body>")
        outlines.forEach { appendLine("    ${it.asTag()}") }
        appendLine("  </body>")
        appendLine("</opml>")
    }

    /** Parses [text] as OPML, flattening folders. Total: malformed input is a value. */
    fun read(text: String): OpmlParse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return OpmlParse.Malformed("The file is empty.")

        val root = parseFeedXml(trimmed).children()
            .firstOrNull { it.localName().equals("opml", ignoreCase = true) }
            ?: return OpmlParse.Malformed("That file is not an OPML subscription list.")

        val found = mutableListOf<OpmlOutline>()
        var invalid = 0
        // The body, or the root itself: a `<body>` is required by the spec and omitted in
        // the wild often enough that refusing the file over it would be pedantry.
        val body = root.childElementsNamed("body").firstOrNull() ?: root
        fun walk(parent: Element) {
            for (outline in parent.childElementsNamed("outline")) {
                val address = outline.attrNamed("xmlUrl")
                when {
                    address != null && address.isFeedAddress() ->
                        found += OpmlOutline(outline.label(address), address, outline.attrNamed("htmlUrl"))
                    // A source claim we cannot use: an address that is not one, or none at
                    // all under an explicit `type`. A bare `<outline text="Folder">` claims
                    // nothing, so it is a container even when it holds nothing.
                    address != null || outline.claimsToBeAFeed() -> invalid++
                }
                walk(outline)
            }
        }
        walk(body)
        return OpmlParse.Success(found, invalid)
    }

    private fun OpmlOutline.asTag(): String = buildString {
        append("""<outline type="rss" text="${escape(title)}" title="${escape(title)}"""")
        append(""" xmlUrl="${escape(xmlUrl)}"""")
        siteUrl?.let { append(""" htmlUrl="${escape(it)}"""") }
        append(" />")
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (char in value) when {
            char == '&' -> append("&amp;")
            char == '<' -> append("&lt;")
            char == '>' -> append("&gt;")
            char == '"' -> append("&quot;")
            // Control characters are not representable in XML 1.0 at all — a title
            // carrying one must not be able to produce a document that will not re-read.
            char.code < 0x20 && char != '\t' -> Unit
            else -> append(char)
        }
    }

    /** `text` is what the spec requires, `title` is what several readers write instead. */
    private fun Element.label(address: String): String =
        attrNamed("text") ?: attrNamed("title") ?: runCatching { URI(address).host }.getOrNull() ?: address

    private fun Element.claimsToBeAFeed(): Boolean =
        attrNamed("type")?.lowercase() in setOf("rss", "atom", "feed")

    /**
     * Absolute http(s) only. An OPML file is user-supplied input that becomes something the
     * app fetches unattended, so a relative path or a `javascript:` address is counted
     * invalid rather than stored and later handed to the fetcher.
     */
    private fun String.isFeedAddress(): Boolean {
        val scheme = runCatching { URI(this).scheme }.getOrNull()?.lowercase()
        return (scheme == "http" || scheme == "https") &&
            runCatching { URI(this).host }.getOrNull()?.isNotEmpty() == true
    }
}
