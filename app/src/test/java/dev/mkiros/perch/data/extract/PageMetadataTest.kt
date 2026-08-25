package dev.mkiros.perch.data.extract

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One test per rung of §0.2's fallback chains, isolated from the corpus so a failure names
 * the *rule* rather than a publisher. Robolectric for JSON-LD (`org.json`, U14).
 */
@RunWith(RobolectricTestRunner::class)
class PageMetadataTest {

    @Test
    fun `og title wins over twitter title and the title tag`() {
        val doc = parse(
            """
            <html><head>
              <title>Ignored | Some Blog</title>
              <meta name="twitter:title" content="Twitter title">
              <meta property="og:title" content="The real title">
            </head><body></body></html>
            """.trimIndent(),
        )

        assertThat(PageMetadataExtractor.extract(doc, null).title).isEqualTo("The real title")
    }

    @Test
    fun `twitter title answers when there is no og title`() {
        val doc = parse(
            """
            <html><head>
              <meta name="twitter:title" content="Twitter-only title">
            </head><body></body></html>
            """.trimIndent(),
        )

        assertThat(PageMetadataExtractor.extract(doc, null).title).isEqualTo("Twitter-only title")
    }

    @Test
    fun `a JSON-LD headline is found inside an @graph array`() {
        val doc = parse(
            """
            <html><head>
              <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[
                  {"@type":"WebSite","name":"Not this"},
                  {"@type":"Article","headline":"Headline from the graph"}
                ]}
              </script>
            </head><body></body></html>
            """.trimIndent(),
        )

        assertThat(PageMetadataExtractor.extract(doc, null).title).isEqualTo("Headline from the graph")
    }

    @Test
    fun `a title tag is trimmed on its last separator, keeping an earlier one intact`() {
        val doc = parse("<html><head><title>C++ - Move Semantics | My Blog</title></head><body></body></html>")

        assertThat(PageMetadataExtractor.extract(doc, null).title).isEqualTo("C++ - Move Semantics")
    }

    @Test
    fun `an h1 inside the extracted body stands in when the head names no title`() {
        val prose = "Long enough paragraph text to score as an article body. ".repeat(10)
        val doc = parse(
            """
            <html><body><article><h1>Body heading</h1><p>$prose</p></article></body></html>
            """.trimIndent(),
        )

        assertThat(PageMetadataExtractor.extract(doc, null).title).isEqualTo("Body heading")
    }

    @Test
    fun `article published time outranks a JSON-LD date`() {
        val doc = parse(
            """
            <html><head>
              <meta property="article:published_time" content="2026-03-01T00:00:00Z">
              <script type="application/ld+json">{"datePublished":"2020-01-01T00:00:00Z"}</script>
            </head><body></body></html>
            """.trimIndent(),
        )

        assertThat(PageMetadataExtractor.extract(doc, null).publishedAt.toString())
            .isEqualTo("2026-03-01T00:00:00Z")
    }

    @Test
    fun `a time tag with itemprop datePublished is read, one with neither marker is not`() {
        val marked = parse(
            """<html><body><time datetime="2026-05-04T00:00:00Z" itemprop="datePublished">
                May 4</time></body></html>""",
        )
        val bare = parse("""<html><body><time datetime="2026-05-04T00:00:00Z">May 4</time></body></html>""")

        assertThat(PageMetadataExtractor.extract(marked, null).publishedAt).isNotNull()
        assertThat(PageMetadataExtractor.extract(bare, null).publishedAt).isNull()
    }

    @Test
    fun `Dublin Core date answers when nothing more standard does`() {
        val doc = parse("""<html><head><meta name="DC.date" content="2026-02-14"></head><body></body></html>""")

        assertThat(PageMetadataExtractor.extract(doc, null).publishedAt.toString())
            .isEqualTo("2026-02-14T00:00:00Z")
    }

    @Test
    fun `a date in the URL path is the last resort, and only when nothing else answered`() {
        val doc = parse("<html><head></head><body></body></html>")

        val metadata = PageMetadataExtractor.extract(doc, "https://fzakaria.com/2026/07/27/the-mean-means-nothing")

        assertThat(metadata.publishedAt.toString()).isEqualTo("2026-07-27T00:00:00Z")
    }

    @Test
    fun `a page with none of these signals yields no title and no date, not an exception`() {
        val doc = parse("<html><body><p>Nothing standard here.</p></body></html>")

        val metadata = PageMetadataExtractor.extract(doc, "https://example.com/no-date-here")

        assertThat(metadata.title).isNull()
        assertThat(metadata.publishedAt).isNull()
    }

    @Test
    fun `malformed JSON-LD is skipped rather than thrown`() {
        val doc = parse(
            """
            <html><head>
              <script type="application/ld+json">{ not valid json </script>
              <meta property="og:title" content="Still gets the title">
            </head><body></body></html>
            """.trimIndent(),
        )

        assertThat(PageMetadataExtractor.extract(doc, null).title).isEqualTo("Still gets the title")
    }

    private fun parse(html: String) = Jsoup.parse(html, "https://example.com/")
}
