package dev.mkiros.perch.data.extract

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The standing contract for [PageMetadataExtractor], held to **every** file in
 * `fixtures/articles/` — all 23, not a hand-picked sample (PLAN-6 §0, Y01) — the way
 * `FeedCorpusTest` holds `FeedParser` to every harvested feed.
 *
 * Several of these files are not full pages at all: the five `zdi-*-update-review.html`
 * fixtures are feed-item *bodies* harvested for `TableCorpusTest`, and `gpuopen-feed.xml`
 * is a feed, not an article. Neither carries a `<head>`, so both are expected to yield no
 * metadata — that is evidence about the shape of the corpus, not a special case to filter
 * out before counting (§0.1: "fixtures that carry no metadata at all... record them").
 *
 * Robolectric because JSON-LD is read through `org.json`, which stubs on a bare JVM (U14).
 */
@RunWith(RobolectricTestRunner::class)
class PageMetadataCorpusTest {

    @Test
    fun `every page in the corpus is measured for title and date, at or above the floor`() {
        val files = ArticleFixtures.dir().listFiles()?.sortedBy { it.name }.orEmpty()
        assertThat(files).isNotEmpty()

        val report = mutableListOf<String>()
        var titled = 0
        var dated = 0

        for (file in files) {
            val doc = Jsoup.parse(file.readText(), FIXTURE_URLS[file.name] ?: "")
            val metadata = PageMetadataExtractor.extract(doc, FIXTURE_URLS[file.name])
            if (metadata.title != null) titled++
            if (metadata.publishedAt != null) dated++
            report += "${file.name}: title=${metadata.title != null} date=${metadata.publishedAt}"
        }

        println(report.joinToString("\n"))
        val titleRate = titled.toDouble() / files.size
        val dateRate = dated.toDouble() / files.size
        println("title: $titled/${files.size} (${"%.0f".format(titleRate * 100)}%), " +
            "date: $dated/${files.size} (${"%.0f".format(dateRate * 100)}%)")

        assertThat(titleRate).isAtLeast(MIN_TITLE_RATE)
        assertThat(dateRate).isAtLeast(MIN_DATE_RATE)
    }

    /**
     * A date in the metadata wins even when the URL's own path disagrees with it — §0.2's
     * "a page whose metadata disagrees with its URL trusts the metadata", pinned directly
     * rather than left to fall out of the corpus by accident.
     */
    @Test
    fun `a URL date never overrides a stronger source`() {
        val html = """
            <html><head>
              <meta property="article:published_time" content="2026-01-05T10:00:00Z">
            </head><body><article><p>${"prose ".repeat(30)}</p></article></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/2020/12/25/wrong-year/")

        val metadata = PageMetadataExtractor.extract(doc, "https://example.com/2020/12/25/wrong-year/")

        assertThat(metadata.publishedAt.toString()).isEqualTo("2026-01-05T10:00:00Z")
    }

    private companion object {
        /**
         * The real address of each fixture, so `og:title` suffix-trimming and the URL-date
         * fallback are exercised against the page's actual path rather than an empty base.
         */
        val FIXTURE_URLS: Map<String, String> = ArticleFixtures.all.associate {
            "${it.slug}.html" to it.url
        }

        // Measured by this task (Y01), standards-only: title 17/23 (74%), date 5/23 (22%).
        // The 6 untitled files are feed-body fragments with no `<head>` at all (the five
        // `zdi-*-update-review.html` TableCorpusTest fixtures) or a feed, not a page
        // (`gpuopen-feed.xml`) — evidence about the corpus, not something to chase further.
        // Every gpuopen.com page carries an "Originally posted" `<time>` with neither
        // `pubdate` nor `itemprop="datePublished"`, so it legitimately yields no date; §0.2
        // asks for exactly those two markers and no more, to keep an unrelated `<time>`
        // (a comment, an "updated" stamp) from being read as the publish date.
        const val MIN_TITLE_RATE = 0.73
        const val MIN_DATE_RATE = 0.21
    }
}
