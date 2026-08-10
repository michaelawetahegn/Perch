package dev.mkiros.perch.data.parse

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns the date strings feeds actually publish into an [Instant], per SPEC.md §5.
 *
 * Real feeds emit RFC-822 with every deviation the spec permits (and several it does
 * not), RFC-3339 with or without fractions, and occasionally something else entirely.
 * Nothing here throws: an unrecognised or implausible string is `null`, which is the
 * caller's cue to fall back to the feed-level date and then to `fetchedAt`.
 *
 * @param clock supplies "now" for the future clamp; injected so tests are deterministic.
 *   **Deliberately UTC** (audited for issue #9, which fixed the container's clock to the
 *   device's zone): parsing a feed date computes no calendar boundary — the zone here is
 *   only ever used to compare two instants, and an offsetless string is read as UTC
 *   because that is what the format means, not because of where the reader is standing.
 */
class DateParser(private val clock: Clock = Clock.systemUTC()) {

    fun parse(raw: String?): Instant? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = normalize(text)
        val parsed = parseWithOffset(normalized)
            ?: parseAsUtc(normalized)
            ?: return null
        return plausible(parsed)
    }

    /**
     * Rewrites the deviations that are cheaper to fix textually than to teach a
     * formatter: stray whitespace, a space where RFC-3339 wants `T`, the leading
     * weekday (redundant, and often wrong), and the alphabetic time zones RFC-822
     * allows but [DateTimeFormatter.RFC_1123_DATE_TIME] does not accept.
     */
    private fun normalize(text: String): String {
        val collapsed = text.replace(WHITESPACE_RUN, " ")
        val undated = collapsed.replace(LEADING_WEEKDAY, "")
        val isoified = undated.replace(DATE_TIME_SPACE, "$1T$2")
        val zone = TRAILING_ALPHA_ZONE.find(isoified) ?: return isoified
        val name = zone.groupValues[1].uppercase()
        // An unrecognised abbreviation is "unknown local offset" (RFC 2822 §4.3): the
        // wall-clock reading is still worth more than discarding the date outright.
        val offset = ALPHA_ZONES[name] ?: "+0000"
        return isoified.substring(0, zone.range.first) + offset
    }

    private fun parseWithOffset(text: String): Instant? =
        OFFSET_FORMATS.firstNotNullOfOrNull { format ->
            runCatching { Instant.from(format.parse(text)) }.getOrNull()
        }

    /** A timestamp that names no zone is read as UTC rather than as device-local. */
    private fun parseAsUtc(text: String): Instant? {
        runCatching { LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
            .getOrNull()?.let { return it.toInstant(ZoneOffset.UTC) }
        runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE) }
            .getOrNull()?.let { return it.atStartOfDay().toInstant(ZoneOffset.UTC) }
        return null
    }

    /**
     * Rejects dates no RSS entry plausibly carries and clamps ones the publisher
     * post-dated. A 1970 timestamp is a parsing accident, not a publication date, and
     * a far-future one would pin the entry to the top of the list forever.
     */
    private fun plausible(instant: Instant): Instant? {
        if (instant.isBefore(FLOOR)) return null
        val now = clock.instant()
        return if (instant.isAfter(now.plus(FUTURE_SLACK))) now else instant
    }

    private companion object {
        val FLOOR: Instant = Instant.parse("2000-01-01T00:00:00Z")
        val FUTURE_SLACK: Duration = Duration.ofHours(24)

        val WHITESPACE_RUN = Regex("\\s+")
        val LEADING_WEEKDAY = Regex("^[A-Za-z]{3,9}, ")
        val DATE_TIME_SPACE = Regex("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2})")
        val TRAILING_ALPHA_ZONE = Regex("(?<=\\d[ ])([A-Za-z]{1,4})$")

        /** RFC-822 §5.1 zone names plus the obsolete spellings still seen in feeds. */
        val ALPHA_ZONES = mapOf(
            "UT" to "+0000", "GMT" to "+0000", "UTC" to "+0000", "Z" to "+0000",
            "EST" to "-0500", "EDT" to "-0400",
            "CST" to "-0600", "CDT" to "-0500",
            "MST" to "-0700", "MDT" to "-0600",
            "PST" to "-0800", "PDT" to "-0700",
        )

        /**
         * RFC_1123 already tolerates a missing weekday, a one-digit day and a missing
         * seconds field, which covers every RFC-822 shape in the corpus once
         * [normalize] has dropped the weekday and turned the zone name into an offset.
         */
        val OFFSET_FORMATS = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
        )
    }
}
