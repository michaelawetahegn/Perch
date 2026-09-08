package dev.mkiros.perch.data.archive

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.support.MapPageFetcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Z02 — `robots.txt` (RFC 9309): the `Disallow:` rules, read for the `*` group only, and
 * (D17) the `Sitemap:` URLs, read wherever they appear, both out of one read of one file.
 */
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

    @Test
    fun `one read yields both the sitemaps and the disallowed paths`() {
        val rules = RobotsRules.parse(
            """
            Sitemap: https://example.com/sitemap-posts.xml
            User-agent: *
            Disallow: /private/
            Sitemap: https://example.com/sitemap-pages.xml
            """.trimIndent(),
        )

        assertThat(rules.sitemaps).containsExactly(
            "https://example.com/sitemap-posts.xml",
            "https://example.com/sitemap-pages.xml",
        ).inOrder()
        assertThat(rules.disallows("https://example.com/private/secret")).isTrue()
    }

    @Test
    fun `a Sitemap line outside the wildcard group still counts`() {
        val rules = RobotsRules.parse("User-agent: GPTBot\nDisallow: /\nSitemap: https://example.com/s.xml\n")

        assertThat(rules.sitemaps).containsExactly("https://example.com/s.xml")
    }

    @Test
    fun `a site with no robots-txt declares no sitemap and disallows nothing`() = runTest {
        val rules = RobotsRules.fetch(MapPageFetcher(), "https://example.com/blog/")

        assertThat(rules.sitemaps).isEmpty()
        assertThat(rules.disallows("https://example.com/anything")).isFalse()
    }

    @Test
    fun `fetch reads robots-txt from the host root, whatever page the caller names`() = runTest {
        val fetcher = MapPageFetcher(
            pages = mapOf(
                "https://example.com/robots.txt" to
                    FetchedPage("Sitemap: https://example.com/s.xml\n".toByteArray(), "text/plain", ""),
            ),
        )

        val rules = RobotsRules.fetch(fetcher, "https://example.com/blog/a-post")

        assertThat(fetcher.requested).containsExactly("https://example.com/robots.txt")
        assertThat(rules.sitemaps).containsExactly("https://example.com/s.xml")
    }
}
