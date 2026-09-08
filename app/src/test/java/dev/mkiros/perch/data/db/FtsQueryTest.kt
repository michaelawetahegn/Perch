package dev.mkiros.perch.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one thing standing between a reader's typing and SQLite's query parser (S09, #28).
 *
 * Every case here is a string a reader can produce with the on-screen keyboard, and every
 * one of them reaches `MATCH` unaltered if this function does not exist. FTS4's query
 * language is not SQL — it is parsed separately, after binding — so a lone `"` is an
 * unterminated phrase and a lone `*` is a syntax error, and neither is caught by
 * parameterising the statement. They arrive as a crash while the reader is still typing.
 */
class FtsQueryTest {

    @Test
    fun `a single word becomes a prefix match`() {
        assertThat(FtsQuery.from("strava")).isEqualTo("strava*")
    }

    @Test
    fun `several words must all appear, and only the last is a prefix`() {
        assertThat(FtsQuery.from("strava fitness inv")).isEqualTo("strava AND fitness AND inv*")
    }

    @Test
    fun `surrounding and repeated whitespace is not a token`() {
        assertThat(FtsQuery.from("  strava   app  ")).isEqualTo("strava AND app*")
    }

    @Test
    fun `an empty question has no answer`() {
        assertThat(FtsQuery.from("")).isNull()
        assertThat(FtsQuery.from("   ")).isNull()
    }

    // ---- input that would otherwise reach the FTS parser ------------------------

    @Test
    fun `a bare quote is not a query`() {
        assertThat(FtsQuery.from("\"")).isNull()
    }

    @Test
    fun `a bare star is not a query`() {
        assertThat(FtsQuery.from("*")).isNull()
    }

    @Test
    fun `a half-typed phrase loses its quotes rather than opening one`() {
        assertThat(FtsQuery.from("\"strava app")).isEqualTo("strava AND app*")
    }

    @Test
    fun `punctuation between words separates them instead of steering the parser`() {
        assertThat(FtsQuery.from("strava-app: (heatmap)")).isEqualTo(
            "strava AND app AND heatmap*",
        )
    }

    @Test
    fun `the operators are searched for, not obeyed`() {
        assertThat(FtsQuery.from("AND")).isEqualTo("and*")
        assertThat(FtsQuery.from("fitness OR app")).isEqualTo("fitness AND or AND app*")
        assertThat(FtsQuery.from("app NOT strava")).isEqualTo("app AND not AND strava*")
    }

    @Test
    fun `emoji are not words`() {
        assertThat(FtsQuery.from("🚴")).isNull()
        assertThat(FtsQuery.from("strava 🚴 app")).isEqualTo("strava AND app*")
    }

    @Test
    fun `a word is a word in any script`() {
        assertThat(FtsQuery.from("Ferrán café")).isEqualTo("ferrán AND café*")
        assertThat(FtsQuery.from("日本語")).isEqualTo("日本語*")
    }

    @Test
    fun `digits are searchable`() {
        assertThat(FtsQuery.from("gijn 2018")).isEqualTo("gijn AND 2018*")
    }
}
