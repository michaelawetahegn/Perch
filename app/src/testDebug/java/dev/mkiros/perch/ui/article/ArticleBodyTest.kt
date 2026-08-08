package dev.mkiros.perch.ui.article

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.RichSpan
import dev.mkiros.perch.data.parse.SpanStyle
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The block renderer (T25): one composable per `ArticleBlock`, no source-specific branch.
 *
 * Every variant the lowering can emit is composed here, because the promise of DESIGN.md
 * §8 is that the renderer is total — a shape it cannot draw is a hole in an article, and
 * the corpus will find it. The assertions are about *what a reader sees*: the text, the
 * marker, the caption, and whether a thing that must scroll can.
 *
 * Coil is pointed at a stub mapper for the duration: the image block collapses on a
 * failed load by design, so without a loader that succeeds the image test would be
 * asserting the collapse rather than the image.
 */
@RunWith(RobolectricTestRunner::class)
class ArticleBodyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(StubImages(IMAGE_URL, context)) }
                .dispatcher(Dispatchers.Main.immediate)
                .fetcherDispatcher(Dispatchers.Main.immediate)
                .decoderDispatcher(Dispatchers.Main.immediate)
                .transformationDispatcher(Dispatchers.Main.immediate)
                .build(),
        )
    }

    @After
    fun tearDown() {
        Coil.reset()
    }

    @Test
    fun `a paragraph renders its text`() {
        show(ArticleBlock.Paragraph(RichSpan("The compiler is not your adversary.")))

        compose.onNodeWithText("The compiler is not your adversary.").assertIsDisplayed()
    }

    @Test
    fun `both heading levels render`() {
        show(
            ArticleBlock.Heading(2, RichSpan("How it works")),
            ArticleBlock.Heading(3, RichSpan("The slow path")),
        )

        compose.onNodeWithText("How it works").assertIsDisplayed()
        compose.onNodeWithText("The slow path").assertIsDisplayed()
    }

    @Test
    fun `a code block keeps its whitespace and scrolls sideways instead of wrapping`() {
        val source = "int main(void) {\n    return 0;\n}"

        show(ArticleBlock.Code(source))

        compose.onNodeWithTag(ArticleTestTags.CODE).assert(hasScrollAction())
        compose.onNodeWithText(source).assertIsDisplayed()
    }

    @Test
    fun `an image renders with its alt text and its caption beneath`() {
        show(
            ArticleBlock.Image(
                url = IMAGE_URL,
                alt = "A phase diagram",
                caption = RichSpan("Figure 1: the two stable states."),
            ),
        )

        compose.onNodeWithContentDescription("A phase diagram").assertIsDisplayed()
        compose.onNodeWithText("Figure 1: the two stable states.").assertIsDisplayed()
    }

    @Test
    fun `an image that fails to load collapses rather than leaving a hole`() {
        show(
            ArticleBlock.Image(
                url = "https://example.com/gone.png",
                alt = "Missing",
                caption = RichSpan("This caption goes with it."),
            ),
        )

        compose.onNodeWithText("This caption goes with it.").assertDoesNotExist()
        compose.onNodeWithTag(ArticleTestTags.IMAGE).assertDoesNotExist()
    }

    @Test
    fun `a quote renders the blocks inside it`() {
        show(ArticleBlock.Quote(listOf(ArticleBlock.Paragraph(RichSpan("Premature optimization.")))))

        compose.onNodeWithTag(ArticleTestTags.QUOTE).assertIsDisplayed()
        compose.onNodeWithText("Premature optimization.").assertIsDisplayed()
    }

    @Test
    fun `an unordered list marks every item with a bullet`() {
        show(
            ArticleBlock.ListBlock(
                ordered = false,
                items = listOf(RichSpan("first"), RichSpan("second")),
            ),
        )

        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithText("second").assertIsDisplayed()
        val bullets = compose.onAllNodesWithText("•").fetchSemanticsNodes().size
        assertThat(bullets).isEqualTo(2)
    }

    @Test
    fun `an ordered list numbers its items from one`() {
        show(
            ArticleBlock.ListBlock(
                ordered = true,
                items = listOf(RichSpan("first"), RichSpan("second")),
            ),
        )

        compose.onNodeWithText("1.").assertIsDisplayed()
        compose.onNodeWithText("2.").assertIsDisplayed()
    }

    @Test
    fun `a table renders its header and cells and never widens the page`() {
        show(
            ArticleBlock.Table(
                header = listOf(RichSpan("Flag"), RichSpan("Effect")),
                rows = listOf(listOf(RichSpan("-O2"), RichSpan("optimize"))),
            ),
        )

        compose.onNodeWithTag(ArticleTestTags.TABLE).assert(hasScrollAction())
        compose.onNodeWithText("Flag").assertIsDisplayed()
        compose.onNodeWithText("optimize").assertIsDisplayed()
    }

    @Test
    fun `a rule renders as a short centred hairline`() {
        show(ArticleBlock.Rule)

        compose.onNodeWithTag(ArticleTestTags.RULE).assertIsDisplayed()
    }

    @Test
    fun `an unsupported block becomes a card that offers the web version`() {
        val opened = mutableListOf<String>()

        show(ArticleBlock.Unsupported("iframe"), onOpenLink = { opened += it })
        compose.onNodeWithTag(ArticleTestTags.EMBED).performClick()

        compose.onNodeWithText("Embedded content · open on the web").assertIsDisplayed()
        assertThat(opened).containsExactly(ARTICLE_URL)
    }

    @Test
    fun `blocks render in the order the lowering produced them`() {
        show(
            ArticleBlock.Heading(2, RichSpan("Above")),
            ArticleBlock.Paragraph(RichSpan("Below")),
        )

        assertThat(topOf("Above")).isLessThan(topOf("Below"))
    }

    @Test
    fun `a link inside a paragraph is rendered as part of the sentence`() {
        show(
            ArticleBlock.Paragraph(
                RichSpan(
                    text = "the spec says otherwise",
                    marks = listOf(RichSpan.Mark(SpanStyle.Link("https://example.com/spec"), 4, 8)),
                ),
            ),
        )

        compose.onNodeWithText("the spec says otherwise").assertIsDisplayed()
    }

    /**
     * U12's entry point. The figure in an article is often the whole point of the article —
     * a schematic, a flame graph, a screenshot of a stack trace — and at a phone's measure
     * it is illegible. Tapping it is the only affordance there is, so it has to be wired to
     * the block that was tapped and not merely to "an image was tapped somewhere".
     */
    @Test
    fun `tapping a figure asks for it to be opened full screen`() {
        val opened = mutableListOf<ArticleBlock.Image>()
        val figure = ArticleBlock.Image(url = IMAGE_URL, alt = "A flame graph", caption = null)

        show(figure, onOpenImage = { opened += it })
        compose.onNodeWithTag(ArticleTestTags.IMAGE).performClick()

        assertThat(opened).containsExactly(figure)
    }

    // ---- harness ---------------------------------------------------------------

    private fun show(
        vararg blocks: ArticleBlock,
        onOpenLink: (String) -> Unit = {},
        onOpenImage: (ArticleBlock.Image) -> Unit = {},
    ) {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                ArticleBody(
                    blocks = blocks.toList(),
                    articleLink = ARTICLE_URL,
                    onOpenLink = onOpenLink,
                    onOpenImage = onOpenImage,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** Maps exactly one URL to a real drawable; everything else falls through and errors. */
    private class StubImages(private val url: String, private val context: Context) :
        Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable? =
            if (data == url) {
                BitmapDrawable(
                    context.resources,
                    Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888),
                )
            } else {
                null
            }

        private companion object {
            const val WIDTH = 160
            const val HEIGHT = 90
        }
    }

    private companion object {
        const val IMAGE_URL = "https://example.com/figure.png"
        const val ARTICLE_URL = "https://example.com/post"
    }
}
