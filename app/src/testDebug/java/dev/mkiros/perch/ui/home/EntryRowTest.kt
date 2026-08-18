package dev.mkiros.perch.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.ImageLoader.Builder
import coil.intercept.Interceptor
import coil.map.Mapper
import coil.request.ErrorResult
import coil.request.ImageResult
import coil.request.Options
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.EntryListItem
import dev.mkiros.perch.data.db.entity.FolderEntity
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.PerchTheme
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The redesigned row (U08), against `design/reference/feed-row-reference.jpg`.
 *
 * The thumbnail is the whole reason this test exists. A reading list scrolls past
 * hundreds of rows and most of them have **no usable image** — absent, still loading, or
 * a 404 — so the placeholder is the common case, not the edge case. Every state has to
 * occupy the identical footprint, or the list jitters as images arrive and the reader's
 * thumb lands on the wrong article. That is asserted here as a dimension rather than
 * eyeballed in a screenshot.
 *
 * Each Coil state is produced by its own stub loader rather than by a real request: a
 * mapper returning a drawable succeeds, an interceptor returning [ErrorResult] fails, and
 * one that never returns stays loading. No network, no timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class EntryRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Coil.reset()
    }

    // ---- the row ---------------------------------------------------------------

    @Test
    fun `a row shows its title over its source and a compact time`() {
        show(item(title = "An Async Runtime in C", sourceTitle = "Null Program"))

        compose.onNodeWithText("An Async Runtime in C").assertIsDisplayed()
        meta().assertTextEquals("Null Program")
        date().assertTextEquals("5h")
    }

    /** W04, issue #20: the reader asked to see what a post is filed under, on the row. */
    @Test
    fun `a row filed in a folder names that folder beside its source`() {
        show(item(sourceTitle = "Null Program", folderId = 4, folderName = "Systems"))

        meta().assertTextEquals("Null Program \u00b7 Systems")
    }

    /**
     * W04: Uncategorized is where a source lands when nobody has filed it, so printing it
     * would label most of the list with the absence of a label.
     */
    @Test
    fun `a row nobody has filed shows no category beside its source`() {
        show(item(sourceTitle = "Null Program"))

        meta().assertTextEquals("Null Program")
    }

    @Test
    fun `the time a row was published sits on its own line beneath the source`() {
        show(item(sourceTitle = "Null Program", folderId = 4, folderName = "Systems"))

        val source = meta().fetchSemanticsNode().boundsInRoot
        val published = date().fetchSemanticsNode().boundsInRoot
        assertThat(published.top).isAtLeast(source.bottom)
        assertThat(published.left).isEqualTo(source.left)
    }

    /**
     * The meta lines are the one part of the row that grows with the reader's text size,
     * and the thumbnail is the part that must not move when it does.
     */
    @Test
    fun `a long source and a long category leave the thumbnail its square at a large font scale`() {
        showAll(
            listOf(
                item(
                    sourceTitle = "The Journal of Extremely Long Publication Names Quarterly",
                    folderId = 4,
                    folderName = "Programming Languages and Compiler Implementation",
                ),
            ),
            fontScale = 1.3f,
        )

        thumbnail(EntryRowTestTags.THUMBNAIL)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(Dimens.thumbnail)
            .assertHeightIsEqualTo(Dimens.thumbnail)
    }

    @Test
    fun `each relative-time band reads the way the reference does`() {
        val bands = listOf(
            "47min" to 47 * MINUTE,
            "5h" to 5 * HOUR,
            "1d" to DAY,
            "3d" to 3 * DAY,
            "30 Jul" to 8 * DAY,
        )
        showAll(
            bands.mapIndexed { index, (label, elapsed) ->
                item(id = index + 1L, title = "Entry $label", publishedAt = NOW - elapsed)
            },
        )

        // Each label on the date line of its *own* row: before W04 split the meta in two
        // this read "Simon Willison / 47min", and a bare text lookup would now pass on a
        // row that printed some other entry's time.
        val dates = compose.onAllNodesWithTag(EntryRowTestTags.DATE, useUnmergedTree = true)
        dates.assertCountEquals(bands.size)
        bands.forEachIndexed { index, (label, _) ->
            dates[index].assertIsDisplayed().assertTextEquals(label)
        }
    }

    @Test
    fun `a title longer than three lines is clamped rather than pushing the row taller`() {
        val sentence = "Everything you ever wanted to know about the compiler you use"
        showAll(
            listOf(
                item(id = 1, title = List(3) { sentence }.joinToString(" ")),
                item(id = 2, title = List(12) { sentence }.joinToString(" ")),
            ),
        )

        // titleMedium is 16sp on a 24sp line, so three lines is 72dp at font scale 1.
        val limit = with(compose.density) { THREE_LINES.roundToPx() }
        assertThat(titleHeights().max()).isAtMost(limit)
        // Four times the text, the same box: the clamp is doing the work, not luck.
        assertThat(titleHeights()).hasSize(1)
    }

    // ---- the thumbnail, in every one of its states ------------------------------

    @Test
    fun `an entry with no image draws the placeholder, not a broken glyph`() {
        show(item(imageUrl = null))

        assertPlaceholder()
    }

    @Test
    fun `an image that is still loading draws the placeholder`() {
        install(PendingForever)
        show(item(imageUrl = IMAGE_URL))

        assertPlaceholder()
    }

    @Test
    fun `an image whose load fails draws the placeholder`() {
        install(AlwaysFails)
        show(item(imageUrl = IMAGE_URL))

        assertPlaceholder()
    }

    /**
     * V07: absent and failed are *finished* states, so they carry the mark. Loading is
     * not — an image in flight keeps the plain frame, because a placeholder that looks
     * identical whether the image is coming or never coming is the complaint issue #13
     * was filed about, in reverse.
     */
    @Test
    fun `an entry with no image rests under the brand mark`() {
        show(item(imageUrl = null))

        thumbnail(EntryRowTestTags.THUMBNAIL_MARK).assertIsDisplayed()
    }

    @Test
    fun `an image whose load failed rests under the same mark`() {
        install(AlwaysFails)
        show(item(imageUrl = IMAGE_URL))

        thumbnail(EntryRowTestTags.THUMBNAIL_MARK).assertIsDisplayed()
    }

    @Test
    fun `an image still in flight keeps the bare frame and does not wear the mark`() {
        install(PendingForever)
        show(item(imageUrl = IMAGE_URL))

        thumbnail(EntryRowTestTags.THUMBNAIL_MARK).assertDoesNotExist()
    }

    /**
     * The tag says the mark is composed; only pixels say the square stopped looking
     * empty. Drawn for real under `NATIVE` graphics, then read back: the square must not
     * be the row showing through, and it must carry ink of its own.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the placeholder is a filled square with the mark on it, not an empty frame`() {
        show(item(imageUrl = null))

        val square = thumbnail(EntryRowTestTags.THUMBNAIL_PLACEHOLDER)
            .fetchSemanticsNode().boundsInRoot
        val screen = Screenshots.rasterize(compose, compose.activity)
        val justOutside = with(compose.density) { Dimens.xs.roundToPx() }
        val row = screen.getPixel((square.left - justOutside).toInt(), square.center.y.toInt())

        // Inset past the hairline border and the corner radius: this is the fill itself.
        val inside = coloursIn(screen, square, inset = square.width / CORNER_INSET)
        assertThat(inside).doesNotContain(row)
        assertThat(inside.size).isAtLeast(2)
    }

    @Test
    fun `an image that loads replaces the placeholder in the same footprint`() {
        install(StubImages(IMAGE_URL, context))
        show(item(imageUrl = IMAGE_URL))

        thumbnail(EntryRowTestTags.THUMBNAIL_IMAGE).assertIsDisplayed()
        thumbnail(EntryRowTestTags.THUMBNAIL_PLACEHOLDER).assertDoesNotExist()
        assertThumbnailFootprint()
    }

    @Test
    fun `the row is the same height whether its image is absent, loaded or failed`() {
        install(StubImages(IMAGE_URL, context))
        showAll(
            listOf(
                item(id = 1, imageUrl = null),
                item(id = 2, imageUrl = IMAGE_URL),
                item(id = 3, imageUrl = MISSING_URL),
            ),
        )

        val heights = compose.onAllNodesWithTag(ROW)
            .fetchSemanticsNodes()
            .map { it.size.height }
        assertThat(heights).hasSize(3)
        assertThat(heights.toSet()).hasSize(1)
    }

    // ---- helpers ---------------------------------------------------------------

    private fun assertPlaceholder() {
        thumbnail(EntryRowTestTags.THUMBNAIL_PLACEHOLDER).assertIsDisplayed()
        thumbnail(EntryRowTestTags.THUMBNAIL_IMAGE).assertDoesNotExist()
        assertThumbnailFootprint()
    }

    private fun assertThumbnailFootprint() {
        thumbnail(EntryRowTestTags.THUMBNAIL)
            .assertWidthIsEqualTo(Dimens.thumbnail)
            .assertHeightIsEqualTo(Dimens.thumbnail)
    }

    /** Every distinct colour inside [bounds], sampled coarsely, [inset] px in on each side. */
    private fun coloursIn(screen: Bitmap, bounds: Rect, inset: Float): Set<Int> {
        val seen = mutableSetOf<Int>()
        var y = bounds.top + inset
        while (y < bounds.bottom - inset) {
            var x = bounds.left + inset
            while (x < bounds.right - inset) {
                seen += screen.getPixel(x.toInt(), y.toInt())
                x += SAMPLE_STRIDE
            }
            y += SAMPLE_STRIDE
        }
        return seen
    }

    /** The thumbnail's parts are inside the row's merged node, so ask the unmerged tree. */
    private fun thumbnail(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    /** The distinct rendered title heights on screen, in pixels. */
    private fun titleHeights(): Set<Int> =
        compose.onAllNodesWithTag(EntryRowTestTags.TITLE, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.size.height }
            .toSet()

    private fun install(interceptor: Interceptor) =
        install(Builder(context).components { add(interceptor) })

    private fun install(mapper: Mapper<String, Drawable>) =
        install(Builder(context).components { add(mapper) })

    private fun install(builder: Builder) {
        Coil.setImageLoader(
            builder
                .dispatcher(Dispatchers.Main.immediate)
                .fetcherDispatcher(Dispatchers.Main.immediate)
                .decoderDispatcher(Dispatchers.Main.immediate)
                .transformationDispatcher(Dispatchers.Main.immediate)
                .build(),
        )
    }

    private fun meta() = compose.onNodeWithTag(EntryRowTestTags.META, useUnmergedTree = true)

    private fun date() = compose.onNodeWithTag(EntryRowTestTags.DATE, useUnmergedTree = true)

    private fun show(item: EntryListItem) = showAll(listOf(item))

    private fun showAll(items: List<EntryListItem>, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                PerchTheme(dynamicColor = false) {
                    Column {
                        items.forEach {
                            // The row wears its caller's tag, the way home's does.
                            EntryRow(
                                item = it,
                                now = NOW,
                                onClick = {},
                                modifier = Modifier.testTag(ROW),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun item(
        id: Long = 1,
        title: String = "An Async Runtime in C",
        sourceTitle: String = "Simon Willison",
        imageUrl: String? = null,
        publishedAt: Long = NOW - 5 * HOUR,
        isRead: Boolean = false,
        folderId: Long = FolderEntity.UNCATEGORIZED_ID,
        folderName: String = FolderEntity.UNCATEGORIZED_NAME,
    ) = EntryListItem(
        id = id,
        feedId = 1,
        title = title,
        summary = "A summary the redesigned row deliberately no longer shows.",
        imageUrl = imageUrl,
        publishedAt = publishedAt,
        isRead = isRead,
        sourceTitle = sourceTitle,
        folderId = folderId,
        folderName = folderName,
    )

    /** Maps exactly one URL to a real drawable; anything else falls through and errors. */
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
            const val WIDTH = 320
            const val HEIGHT = 180
        }
    }

    /** A request that never completes — the row a reader sees while an image is in flight. */
    private object PendingForever : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            awaitCancellation()
    }

    /** A 404, without needing a network to produce one. */
    private object AlwaysFails : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            ErrorResult(null, chain.request, IOException("404"))
    }

    private companion object {
        val NOW: Long = Instant.parse("2026-08-07T12:00:00Z").toEpochMilli()
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val ROW = "test:row"
        const val IMAGE_URL = "https://example.com/lead.png"
        const val MISSING_URL = "https://example.invalid/gone.png"
        val THREE_LINES = 76.dp

        /** An eighth of the square clears both the hairline and the rounded corners. */
        const val CORNER_INSET = 8f
        const val SAMPLE_STRIDE = 4f
    }
}
