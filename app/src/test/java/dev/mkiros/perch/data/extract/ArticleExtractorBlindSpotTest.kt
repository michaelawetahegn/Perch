package dev.mkiros.perch.data.extract

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Test

/**
 * W06/#17 diagnosed a page the extractor was structurally blind to; W07 flipped it. This
 * file is both halves — the mechanism, still measured, and the rule that reaches past it.
 *
 * The mechanism, for the Hugging Face blog:
 *
 * `strip()`'s unlikely-candidate sweep asks [ArticleExtractor]'s `NEGATIVE` regex whether a
 * container *names itself* chrome, and that regex matches an unanchored substring anywhere
 * in the class attribute. Tailwind writes layout as utility classes, so the article's own
 * wrapper reads `class="max-lg:overflow-hidden"` — which contains `hidden` — and nothing in
 * it matches `POSITIVE`. The wrapper was removed before a single paragraph was scored,
 * taking 7389 of the page's 7419 prose characters with it, including the child that *does*
 * name itself the article (`class="blog-content … prose …"`). Scoring then ran over an
 * empty body, `scores` was empty, and `extract` returned null at its `maxByOrNull` —
 * neither give-up gate (`MIN_CANDIDATE_SCORE`, `MIN_PROSE_CHARS`) was ever reached.
 *
 * The rule W07 added names no host and no framework (§0): **a guess that removes the whole
 * article has to be able to be wrong.** The name sweep is a guess — it reads what markup
 * calls itself, not what it holds — so a page that yields nothing is read a second time
 * with that sweep off, and scoring, link density and the landmark sweep decide alone. A
 * page that already yielded an article never reaches the second pass, which is why this
 * change cannot cost the corpus anything: by construction it only ever runs where the
 * answer was null.
 *
 * The mechanism tests below still pass, and are meant to: the first pass still loses this
 * page. That is the point — they are the reason the second pass exists, so a future change
 * that "tidies away" the retry fails here with the measurement that put it there.
 */
class ArticleExtractorBlindSpotTest {

    private val huggingFace = ArticleFixtures.all.single {
        it.slug == "huggingface-efficient-knowledge-distillation"
    }

    /** The defect W06 pinned, flipped: the page now yields its article, middle and end. */
    @Test
    fun `the Hugging Face page yields its article rather than nothing`() {
        val extracted = requireNotNull(ArticleExtractor.extract(huggingFace.html(), huggingFace.url))
        val text = Jsoup.parse(extracted, huggingFace.url).text()

        assertThat(text.length).isAtLeast(9000)
        assertThat(text).contains(huggingFace.mid)
        assertThat(text).contains(huggingFace.last)
        assertThat(ArticleExtractor.proseLength(huggingFace.html(), huggingFace.url))
            .isEqualTo(text.length)
    }

    /**
     * The rule, stated on markup that names no site: prose inside a container whose class
     * happens to contain a chrome word, and nothing else on the page. The first pass throws
     * the container away and finds nothing; the second pass has no name sweep to throw it
     * away with, so the article survives.
     */
    @Test
    fun `an article inside a container the name sweep drops is still recovered`() {
        val body =
            "The rule is a general one, stated on markup naming no site, and long enough to score. "
                .repeat(12)
        val html = """
            <html><body>
              <div class="overflow-hidden"><p>$body</p></div>
            </body></html>
        """.trimIndent()

        val extracted = requireNotNull(ArticleExtractor.extract(html, "https://example.com/post/"))

        assertThat(Jsoup.parse(extracted, "https://example.com/post/").text()).contains(body.trim())
    }

    /**
     * The second pass is a fallback and not a replacement: a page that yields an article on
     * the first pass keeps that article, chrome sweep and all. Here the comments block would
     * outweigh the post if the name sweep ever stopped running, so its absence is proof the
     * first pass's answer was the one returned.
     */
    @Test
    fun `a page that extracts on the first pass keeps the chrome sweep's answer`() {
        val post = "The post itself, which is short but perfectly well formed prose. ".repeat(6)
        val comments = "A reader writes at considerable length about something else entirely. ".repeat(40)
        val html = """
            <html><body>
              <div class="post-content"><p>$post</p></div>
              <div class="comments"><p>$comments</p></div>
            </body></html>
        """.trimIndent()

        val text = Jsoup.parse(
            requireNotNull(ArticleExtractor.extract(html, "https://example.com/post/")),
            "https://example.com/post/",
        ).text()

        assertThat(text).contains(post.trim())
        assertThat(text).doesNotContain("A reader writes")
    }

    /**
     * The finding that decided what W07 could be: the prose *is* in the bytes the app was
     * served. If it were behind a JSON payload no scoring change could ever reach it, and
     * the honest fix would have been to say so in the UI instead.
     */
    @Test
    fun `the article body is present in the HTML the app receives, as ordinary paragraphs`() {
        val doc = Jsoup.parse(huggingFace.html(), huggingFace.url)

        assertThat(doc.select("p").sumOf { it.text().length }).isAtLeast(7000)
        assertThat(doc.text()).contains(huggingFace.mid)
        assertThat(doc.text()).contains(huggingFace.last)
    }

    /**
     * The mechanism itself, stage by stage, so a future change that moves the loss to a
     * different sweep cannot quietly keep this test passing.
     */
    @Test
    fun `the chrome sweep still removes the wrapper holding the whole article body`() {
        val doc = Jsoup.parse(huggingFace.html(), huggingFace.url)
        val onPage = prose(doc)
        assertThat(onPage).isEqualTo(7419)

        dropWholesale(doc)
        assertThat(prose(doc)).isEqualTo(onPage)

        val landmarks = doc.select(LANDMARKS)
        assertThat(landmarks).isNotEmpty()
        assertThat(landmarks.sumOf { prose(it) }).isEqualTo(0)
        landmarks.remove()
        doc.select("[aria-hidden=true], [hidden]").remove()
        assertThat(prose(doc)).isEqualTo(onPage)

        val chrome = doc.body().select("div, section, ul, ol, aside, span, table").filter { it.namesChrome() }
        val wrapper = chrome.maxByOrNull { prose(it) }!!
        assertThat(prose(wrapper)).isEqualTo(7389)
        assertThat(wrapper.className()).isEqualTo("max-lg:overflow-hidden")
        assertThat(NEGATIVE.findAll(wrapper.className()).map { it.value }.toList()).containsExactly("hidden")

        chrome.forEach { it.remove() }
        assertThat(prose(doc)).isEqualTo(0)
    }

    /**
     * A wrapper is not allowed to outvote its own child. The removed div's only child names
     * itself an article twice over, which is why "a container that says chrome and says
     * nothing that sounds like an article" is the rule that broke: it was only ever asked
     * about the container.
     */
    @Test
    fun `the removed wrapper contains the element that names itself the article`() {
        val doc = Jsoup.parse(huggingFace.html(), huggingFace.url)
        val wrapper = doc.selectFirst("div.max-lg\\:overflow-hidden")!!

        assertThat(wrapper.namesChrome()).isTrue()
        val body = wrapper.selectFirst("div.blog-content")!!
        assertThat(body.namesChrome()).isFalse()
        assertThat(POSITIVE.containsMatchIn(body.className())).isTrue()
        assertThat(prose(body)).isAtLeast(7000)
    }

    /**
     * W10: [ArticleFixtures.pending] said this file measured it, and nothing read it. It
     * does now — a page parked there must still fail, so the slot cannot quietly hold a
     * page that has since been fixed. Vacuous while the list is empty, which is the state
     * the corpus should normally be in; the moment a harvest parks a page it starts
     * biting, and the task that fixes that page promotes it into [ArticleFixtures.other]
     * rather than deleting a red test.
     */
    @Test
    fun `a pending fixture is one that genuinely still fails to extract`() {
        ArticleFixtures.pending.forEach { fixture ->
            assertThat(ArticleExtractor.extract(fixture.html(), fixture.url)).isNull()
        }
    }

    // ---- the extractor's own constants, restated so this test measures *it* -------

    private fun prose(element: Element): Int =
        element.select(PROSE_TAGS).sumOf { it.text().length }

    private fun dropWholesale(doc: Document) = doc.select(DROP_WHOLESALE).remove()

    private fun Element.namesChrome(): Boolean {
        val name = "${className()} ${id()}"
        if (name.isBlank()) return false
        return NEGATIVE.containsMatchIn(name) && !POSITIVE.containsMatchIn(name)
    }

    private companion object {
        const val DROP_WHOLESALE =
            "script, style, noscript, iframe, frame, object, embed, applet, svg, math, canvas, " +
                "form, input, button, select, textarea, link, meta, base, dialog, template"

        const val LANDMARKS =
            "nav, header, footer, aside, [role=navigation], [role=banner], [role=contentinfo], " +
                "[role=complementary], [role=search]"

        const val PROSE_TAGS = "p, pre, blockquote, td"

        val POSITIVE = Regex(
            "article|body|content|entry|hentry|main|page|post|text|blog|story|column|prose",
            RegexOption.IGNORE_CASE,
        )

        val NEGATIVE = Regex(
            "combx|comment|contact|foot|footer|footnote|masthead|media|meta|outbrain|promo|" +
                "related|scroll|share|shoutbox|sidebar|sponsor|shopping|tags|tool|widget|nav|" +
                "menu|banner|cookie|consent|newsletter|subscribe|social|breadcrumb|pagination|" +
                "pager|popup|modal|skip|toolbar|byline|author-bio|disqus|hidden|sr-only",
            RegexOption.IGNORE_CASE,
        )
    }
}
