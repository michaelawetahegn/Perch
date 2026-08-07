package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

/**
 * The date fallback chain of SPEC.md §5. Feeds in the wild emit RFC-822 with every
 * possible deviation, RFC-3339, and a little junk; none of it may throw and none of
 * it may produce 1970 or a date far in the future.
 */
class DateParserTest {

    /** Fixed "now" so the future-clamp cases are deterministic. */
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val parser = DateParser(Clock.fixed(now, ZoneOffset.UTC))

    private fun parse(raw: String?) = parser.parse(raw)

    private fun assertParses(raw: String, expected: String) {
        assertThat(parse(raw)).isEqualTo(Instant.parse(expected))
    }

    // ---- RFC-822 / RFC-1123, the shapes the corpus actually contains ----

    @Test
    fun `parses canonical rfc822 with numeric offset`() {
        assertParses("Mon, 01 Apr 2019 00:00:00 +0000", "2019-04-01T00:00:00Z")
    }

    @Test
    fun `parses rfc822 with a single-digit day and no leading zero`() {
        assertParses("Fri, 1 May 2020 00:00:00 +0000", "2020-05-01T00:00:00Z")
    }

    @Test
    fun `parses rfc822 with the weekday omitted entirely`() {
        assertParses("01 Jun 2026 00:00:00 +0000", "2026-06-01T00:00:00Z")
    }

    @Test
    fun `parses rfc822 with a non-zero numeric offset`() {
        assertParses("Thu, 07 Nov 2023 09:30:00 -0500", "2023-11-07T14:30:00Z")
    }

    @Test
    fun `parses rfc822 with the GMT zone name`() {
        assertParses("Wed, 12 Mar 2014 01:08:45 GMT", "2014-03-12T01:08:45Z")
    }

    @Test
    fun `parses rfc822 with the obsolete UT zone name`() {
        assertParses("Wed, 12 Mar 2014 01:08:45 UT", "2014-03-12T01:08:45Z")
    }

    @Test
    fun `parses rfc822 with a US zone abbreviation`() {
        assertParses("Tue, 08 Oct 2023 06:00:00 EST", "2023-10-08T11:00:00Z")
    }

    @Test
    fun `parses rfc822 with a daylight-saving zone abbreviation`() {
        assertParses("Tue, 08 Oct 2023 06:00:00 PDT", "2023-10-08T13:00:00Z")
    }

    @Test
    fun `parses rfc822 with the seconds field missing`() {
        assertParses("Mon, 01 Apr 2019 06:30 +0000", "2019-04-01T06:30:00Z")
    }

    @Test
    fun `ignores a weekday that contradicts the calendar date`() {
        // 01 Apr 2019 was a Monday; the feed claims Sunday. The date wins.
        assertParses("Sun, 01 Apr 2019 00:00:00 +0000", "2019-04-01T00:00:00Z")
    }

    @Test
    fun `parses rfc822 written in lower case`() {
        assertParses("mon, 01 apr 2019 00:00:00 gmt", "2019-04-01T00:00:00Z")
    }

    @Test
    fun `trims surrounding whitespace and newlines`() {
        assertParses("\n  Mon, 01 Apr 2019 00:00:00 +0000\t ", "2019-04-01T00:00:00Z")
    }

    // ---- ISO-8601 / RFC-3339 ----

    @Test
    fun `parses rfc3339 with a Z designator`() {
        assertParses("2015-07-18T00:00:00Z", "2015-07-18T00:00:00Z")
    }

    @Test
    fun `parses rfc3339 with an explicit zero offset`() {
        assertParses("2021-08-04T11:14:00+00:00", "2021-08-04T11:14:00Z")
    }

    @Test
    fun `parses rfc3339 with milliseconds and a negative offset`() {
        assertParses("2018-01-04T06:58:00.000-05:00", "2018-01-04T11:58:00Z")
    }

    @Test
    fun `parses rfc3339 with milliseconds and a positive offset`() {
        assertParses("2025-04-14T19:56:19.724+01:00", "2025-04-14T18:56:19.724Z")
    }

    @Test
    fun `treats an offsetless iso timestamp as UTC`() {
        assertParses("2024-03-06T18:00:00", "2024-03-06T18:00:00Z")
    }

    @Test
    fun `parses an iso timestamp separated by a space instead of T`() {
        assertParses("2024-03-06 18:00:00", "2024-03-06T18:00:00Z")
    }

    @Test
    fun `parses a bare calendar date as midnight UTC`() {
        assertParses("2024-03-06", "2024-03-06T00:00:00Z")
    }

    // ---- Junk, absence, and implausible values ----

    @Test
    fun `returns null for a null input`() {
        assertThat(parse(null)).isNull()
    }

    @Test
    fun `returns null for an empty string`() {
        assertThat(parse("")).isNull()
    }

    @Test
    fun `returns null for a whitespace-only string`() {
        assertThat(parse("   \n ")).isNull()
    }

    @Test
    fun `returns null for prose that is not a date`() {
        assertThat(parse("last Tuesday, probably")).isNull()
    }

    @Test
    fun `returns null for a date with an impossible month`() {
        assertThat(parse("2024-13-45T00:00:00Z")).isNull()
    }

    @Test
    fun `returns null for the unix epoch rather than reporting 1970`() {
        assertThat(parse("Thu, 01 Jan 1970 00:00:00 +0000")).isNull()
    }

    @Test
    fun `returns null for any timestamp before the year 2000`() {
        assertThat(parse("1998-06-01T00:00:00Z")).isNull()
    }

    @Test
    fun `clamps a date more than 24 hours in the future to now`() {
        assertThat(parse("2027-01-01T00:00:00Z")).isEqualTo(now)
    }

    @Test
    fun `keeps a date less than 24 hours in the future untouched`() {
        assertParses("2026-08-08T06:00:00Z", "2026-08-08T06:00:00Z")
    }

    // ---- The corpus is the real contract ----

    @Test
    fun `parses every date string present in the harvested corpus`() {
        val dates = corpusDateStrings()
        assertThat(dates.size).isAtLeast(500)

        val unparseable = dates.filter { parse(it) == null }
        assertThat(unparseable).isEmpty()
    }

    private fun corpusDateStrings(): List<String> {
        val snapshots = File("../fixtures/snapshots")
        assertThat(snapshots.isDirectory).isTrue()
        val tag = Regex(
            "<(?:pubDate|updated|published|dc:date|lastBuildDate)>([^<]+)</",
            RegexOption.IGNORE_CASE,
        )
        return snapshots.listFiles { f -> f.extension == "xml" }.orEmpty()
            .flatMap { file -> tag.findAll(file.readText()).map { it.groupValues[1] } }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
