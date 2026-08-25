package dev.mkiros.perch.data.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Z02 — the `Disallow:` half of `robots.txt` (RFC 9309), read for the `*` group only. */
class RobotsRulesTest {

    @Test
    fun `a path under a disallowed prefix is disallowed`() {
        val rules = RobotsRules.parse("User-agent: *\nDisallow: /private/\n")

        assertThat(rules.disallows("https://example.com/private/secret")).isTrue()
        assertThat(rules.disallows("https://example.com/public/page")).isFalse()
    }

    @Test
    fun `a rule under a named group other than the wildcard is ignored`() {
        val rules = RobotsRules.parse("User-agent: GPTBot\nDisallow: /blog/\n")

        assertThat(rules.disallows("https://example.com/blog/post")).isFalse()
    }

    @Test
    fun `no robots-txt means nothing is disallowed`() {
        assertThat(RobotsRules.NONE.disallows("https://example.com/anything")).isFalse()
    }

    @Test
    fun `an empty Disallow value means the whole site is allowed`() {
        val rules = RobotsRules.parse("User-agent: *\nDisallow:\n")

        assertThat(rules.disallows("https://example.com/anything")).isFalse()
    }
}
