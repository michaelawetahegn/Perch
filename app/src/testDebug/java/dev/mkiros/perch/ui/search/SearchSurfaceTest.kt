package dev.mkiros.perch.ui.search

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.repo.EntryRepository
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

/**
 * The search screen itself (S10, #28): the field, the two empty states, the results, and
 * the one control that widens a search the reader inherited.
 *
 * Driven against the surface rather than through the shell, which
 * [SearchFromEverySurfaceTest] does: what is asserted here is the screen's own behaviour,
 * and the shell's is *which* scope it is handed. The search state is hoisted, so the test
 * holds it and reads it back — that is the same seam `PerchNavHost` writes through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SearchSurfaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var repository: EntryRepository
    private lateinit var viewModel: SearchViewModel
    private lateinit var state: MutableState<SearchState?>

    private var gijnId = 0L
    private var otherId = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        repository = EntryRepository(database.entryDao(), Clock.systemUTC())
        viewModel = SearchViewModel(repository, Clock.systemUTC())
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * DESIGN.md §7: one empty state per cause. Nothing typed is not "nothing found" — the
     * reader has not asked anything yet, and reporting a miss for a question nobody put
     * reads as a broken index.
     */
    @Test
    fun `a search with nothing typed says what it is for rather than that it found nothing`() {
        seed()
        showSearch(SearchState.everything())

        compose.onNodeWithTag(SearchTestTags.PROMPT).assertIsDisplayed()
        assertThat(nodes(SearchTestTags.EMPTY)).isEmpty()
        assertThat(nodes(SearchTestTags.RESULT)).isEmpty()
    }

    @Test
    fun `typing a remembered word brings back the article it is in`() {
        seed()
        showSearch(SearchState.everything())

        type("strava")

        awaitDisplayed("Investigations Using the Strava Fitness App")
        assertThat(isDisplayed("Chris on allocators")).isFalse()
    }

    /** The other cause, and it names the question so the reader can see what was asked. */
    @Test
    fun `a question nothing matches says so, and says what was asked`() {
        seed()
        showSearch(SearchState.everything())

        type("badminton")

        compose.awaitInRealTime("the no-results state") {
            nodes(SearchTestTags.EMPTY).isNotEmpty()
        }
        assertThat(isDisplayed("badminton")).isTrue()
        assertThat(nodes(SearchTestTags.PROMPT)).isEmpty()
    }

    /**
     * §0.8: the inherited scope is real, not a caption. A search opened on one source must
     * not answer with another source's articles.
     */
    @Test
    fun `a search opened on a source answers only from that source`() {
        seed()
        showSearch(SearchState.inSource(otherId, label = "Null Program"))

        type("the")

        awaitDisplayed("Chris on allocators")
        assertThat(isDisplayed("Investigations Using the Strava Fitness App")).isFalse()
    }

    @Test
    fun `search everything widens a narrowed search without losing the question`() {
        seed()
        showSearch(SearchState.inSource(otherId, label = "Null Program"))
        type("the")
        awaitDisplayed("Chris on allocators")

        compose.onNodeWithTag(SearchTestTags.WIDEN).performClick()
        compose.waitForIdle()

        assertThat(checkNotNull(state.value).isNarrowed).isFalse()
        assertThat(checkNotNull(state.value).query).isEqualTo("the")
        awaitDisplayed("Investigations Using the Strava Fitness App")
    }

    /** Nothing to widen to, so nothing offering it — the only scope control there is. */
    @Test
    fun `an already unnarrowed search offers no way to widen`() {
        seed()
        showSearch(SearchState.everything())

        assertThat(nodes(SearchTestTags.WIDEN)).isEmpty()
    }

    @Test
    fun `leaving search puts the reader back on the list they opened it from`() {
        seed()
        showSearch(SearchState.everything())
        type("strava")

        compose.onNodeWithTag(SearchTestTags.CLOSE).performClick()
        compose.waitForIdle()

        assertThat(state.value).isNull()
    }

    @Test
    fun `clearing the field returns to the prompt rather than to no results`() {
        seed()
        showSearch(SearchState.everything())
        type("strava")
        awaitDisplayed("Investigations Using the Strava Fitness App")

        compose.onNodeWithTag(SearchTestTags.CLEAR).performClick()
        compose.waitForIdle()

        assertThat(checkNotNull(state.value).query).isEmpty()
        compose.awaitInRealTime("the prompt") { nodes(SearchTestTags.PROMPT).isNotEmpty() }
    }

    // ---- harness -----------------------------------------------------------------

    private fun type(text: String) {
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()

    private fun isDisplayed(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced && it.size.height > 0 }

    /** Wall-clock, not `waitUntil` (V01): the rows come back on Room's executor. */
    private fun awaitDisplayed(text: String) {
        compose.awaitInRealTime("\"$text\" in the results") { isDisplayed(text) }
    }

    private fun showSearch(initial: SearchState) {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                state = androidx.compose.runtime.remember {
                    mutableStateOf<SearchState?>(initial)
                }
                SearchSurface(viewModel = viewModel, state = state, onOpenEntry = {})
            }
        }
        compose.waitForIdle()
    }

    private fun seed() = runBlocking {
        gijnId = database.feedDao().insert(feed("https://gijn.org/feed/", "GIJN"))
        otherId = database.feedDao().insert(feed("https://nullprogram.com/feed/", "Null Program"))
        database.entryDao().upsertAll(
            listOf(
                entry(
                    gijnId,
                    "a",
                    "Investigations Using the Strava Fitness App",
                    "<p>The heatmap gave away the perimeter of the base.</p>",
                ),
                entry(
                    otherId,
                    "b",
                    "Chris on allocators",
                    "<p>An arena is the whole allocator, and the arena is freed at once.</p>",
                ),
            ),
        )
    }

    private fun feed(url: String, title: String) = FeedEntity(
        feedUrl = url,
        siteUrl = url,
        title = title,
        customTitle = null,
        faviconUrl = null,
        etag = null,
        lastModified = null,
        lastFetchedAt = null,
        lastSuccessAt = null,
        lastError = null,
        addedAt = 0L,
    )

    private fun entry(feedId: Long, guid: String, title: String, html: String) = EntryEntity(
        feedId = feedId,
        guid = guid,
        title = title,
        link = "https://example.com/$guid",
        author = null,
        publishedAt = 1_700_000_000_000L,
        publishedIsEstimated = false,
        summary = null,
        contentHtml = html,
        imageUrl = null,
        readAt = null,
        fetchedAt = 0L,
    )
}
