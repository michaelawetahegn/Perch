package dev.mkiros.perch.data.extract

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Test

/**
 * W06/#17: the pages the extractor is structurally blind to, pinned with the measurement
 * that explains each one.
 *
 * These fixtures live in [ArticleFixtures.pending] rather than [ArticleFixtures.all]
 * precisely because they fail: `ArticleExtractorTest` requires every fixture in `all` to
 * yield prose, so a known-broken page in that list is a red suite and a diagnosis nobody
 * can commit. This file is the other half — it asserts **what happens today, and why**, so
 * the defect is a fact in the tree instead of a sentence on an issue.
 *
 * **Every assertion here is a defect W07 flips.** When the mechanism is fixed, these tests
 * fail, and that failure is the signal to promote the fixture into [ArticleFixtures.other]
 * and delete the test that pinned it.
 *
 * The mechanism, for the Hugging Face blog:
 *
 * `strip()`'s unlikely-candidate sweep asks [ArticleExtractor]'s `NEGATIVE` regex whether a
 * container *names itself* chrome, and that regex matches an unanchored substring anywhere
 * in the class attribute. Tailwind writes layout as utility classes, so the article's own
 * wrapper reads `class="max-lg:overflow-hidden"` — which contains `hidden` — and nothing in
 * it matches `POSITIVE`. The wrapper is removed before a single paragraph is scored, taking
 * 7389 of the page's 7419 prose characters with it, including the child that *does* name
 * itself the article (`class="blog-content … prose …"`). Scoring then runs over an empty
 * body, `scores` is empty, and `extract` returns null at its `maxByOrNull` — neither
 * give-up gate (`MIN_CANDIDATE_SCORE`, `MIN_PROSE_CHARS`) is ever reached.
 *
 * Everything else planning suspected is ruled out below by measurement: the body is plain
 * `<p>` prose in the HTML the app receives (not client-rendered, not a list), and the
 * `LANDMARKS` sweep touches none of it.
 */
class ArticleExtractorBlindSpotTest {

    private val huggingFace = ArticleFixtures.pending.single {
        it.slug == "huggingface-efficient-knowledge-distillation"
    }

    @Test
    fun `the Hugging Face page yields no article at all today`() {
        assertThat(ArticleExtractor.extract(huggingFace.html(), huggingFace.url)).isNull()
        assertThat(ArticleExtractor.proseLength(huggingFace.html(), huggingFace.url)).isEqualTo(0)
    }

    /**
     * The finding that decides what W07 can be: the prose *is* in the bytes the app was
     * served. If it were behind a JSON payload no scoring change could ever reach it, and
     * the honest fix would be to say so in the UI instead.
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
    fun `the chrome sweep removes the wrapper holding the whole article body`() {
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
     * The counterfactual, which is what makes the diagnosis a cause rather than a
     * correlation: spare that one class and the *unchanged* extractor recovers the article
     * whole, middle and end. No scoring constant needs to move.
     */
    @Test
    fun `sparing that one utility class recovers the whole article from the unchanged extractor`() {
        val doc = Jsoup.parse(huggingFace.html(), huggingFace.url)
        doc.select("div.max-lg\\:overflow-hidden").forEach { it.removeClass("max-lg:overflow-hidden") }

        val extracted = requireNotNull(ArticleExtractor.extract(doc.outerHtml(), huggingFace.url))
        val text = Jsoup.parse(extracted, huggingFace.url).text()

        assertThat(text.length).isAtLeast(9000)
        assertThat(text).contains(huggingFace.mid)
        assertThat(text).contains(huggingFace.last)
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
