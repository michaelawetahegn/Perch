package dev.mkiros.perch.ui.search

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.settings.SettingsStore
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.collection.CollectionTestTags
import dev.mkiros.perch.ui.home.HomeTestTags
import dev.mkiros.perch.ui.home.TimeFilter
import dev.mkiros.perch.ui.nav.NavTestTags
import dev.mkiros.perch.ui.nav.PerchNavHost
import dev.mkiros.perch.ui.nav.PerchTab
import dev.mkiros.perch.ui.nav.Routes
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import kotlinx.coroutines.flow.first
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
 * #28's carefully-planned part: **which** articles a search looks at depends on where the
 * reader opened it from, and the issue names all five surfaces — the Feed, To-Read, Liked,
 * a source and a folder.
 *
 * Driven through the shell, because that is where the answer actually lives: search is
 * state and not a route (§0.8), the state is hoisted beside `homeScope`, and every surface
 * hands it a different scope on the way in. A per-screen test would assert that an icon
 * exists and nothing about what it inherits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class SearchFromEverySurfaceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController

    private var gijnId = 0L
    private var nullProgramId = 0L

    /** The Feed opens on Today (U07) and these entries are older; the search must not care. */
    private val settings = SettingsStore.inMemory().also {
        runBlocking { it.setTimeFilter(TimeFilter.AllTime) }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = Clock.systemUTC(),
            settings = settings,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `search opened from the Feed reaches every stored article`() {
        seed()
        showNavHost()

        openSearchFromFeed()
        type("the")

        awaitDisplayed("Investigations Using the Strava Fitness App")
        assertThat(isDisplayed("Chris on allocators")).isTrue()
    }

    /**
     * The reader's own words on #25's sibling complaint apply here too: a search opened
     * inside a source is a search *of that source*. Scoped through the article byline,
     * which is V08's own way in and the one a test can drive without the drawer.
     */
    @Test
    fun `search opened from a scoped Feed inherits that source`() {
        val entryId = seed()
        showNavHost()
        navigateTo(Routes.article(entryId))
        awaitDisplayed("Investigations Using the Strava Fitness App")
        compose.onNodeWithTag(ArticleTestTags.SOURCE)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        openSearchFromFeed()
        type("the")

        awaitDisplayed("Investigations Using the Strava Fitness App")
        assertThat(isDisplayed("Chris on allocators")).isFalse()
    }

    @Test
    fun `search opened from To-Read looks only at what is queued`() {
        seed()
        saveTheAllocatorPiece()
        showNavHost()
        selectTab(PerchTab.ToRead)

        openSearchFromCollection()
        type("the")

        awaitDisplayed("Chris on allocators")
        assertThat(isDisplayed("Investigations Using the Strava Fitness App")).isFalse()
    }

    @Test
    fun `search opened from Liked looks only at what was liked`() {
        seed()
        likeTheStravaPiece()
        showNavHost()
        selectTab(PerchTab.Liked)

        openSearchFromCollection()
        type("the")

        awaitDisplayed("Investigations Using the Strava Fitness App")
        assertThat(isDisplayed("Chris on allocators")).isFalse()
    }

    /** §0.8's one scope control, end to end from the surface that narrowed it. */
    @Test
    fun `search everything widens a search opened from To-Read`() {
        seed()
        saveTheAllocatorPiece()
        showNavHost()
        selectTab(PerchTab.ToRead)
        openSearchFromCollection()
        type("the")
        awaitDisplayed("Chris on allocators")

        compose.onNodeWithTag(SearchTestTags.WIDEN).performClick()
        compose.waitForIdle()

        awaitDisplayed("Investigations Using the Strava Fitness App")
    }

    /**
     * The back rung, through the shell rather than as a pure `BackState`: back out of a
     * search opened on Liked returns to Liked, and does not fall through to the Feed.
     */
    @Test
    fun `back out of a search returns to the surface it was opened from`() {
        seed()
        likeTheStravaPiece()
        showNavHost()
        selectTab(PerchTab.Liked)
        openSearchFromCollection()
        assertThat(nodes(SearchTestTags.FIELD)).hasSize(1)

        pressBack()

        assertThat(currentRoute()).isEqualTo(Routes.LIKED)
        assertThat(nodes(SearchTestTags.FIELD)).isEmpty()
        assertThat(compose.activity.isFinishing).isFalse()
    }

    // ---- harness -----------------------------------------------------------------

    private fun openSearchFromFeed() {
        compose.onNodeWithTag(HomeTestTags.SEARCH).performClick()
        compose.waitForIdle()
    }

    private fun openSearchFromCollection() {
        compose.onNodeWithTag(CollectionTestTags.SEARCH).performClick()
        compose.waitForIdle()
    }

    private fun type(text: String) {
        compose.onNodeWithTag(SearchTestTags.FIELD).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun selectTab(tab: PerchTab) {
        compose.onNodeWithTag(NavTestTags.tab(tab)).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun navigateTo(route: String) {
        compose.runOnUiThread { navController.navigate(route) }
        compose.waitForIdle()
    }

    private fun currentRoute(): String? = navController.currentDestination?.route

    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()

    private fun isDisplayed(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
            .any { it.layoutInfo.isPlaced && it.size.height > 0 }

    private fun awaitDisplayed(text: String) {
        compose.awaitInRealTime("\"$text\"") { isDisplayed(text) }
    }

    private fun showNavHost() {
        compose.setContent {
            PerchTheme(dynamicColor = false) {
                navController = rememberNavController()
                PerchNavHost(container = container, navController = navController)
            }
        }
        compose.waitForIdle()
    }

    private fun saveTheAllocatorPiece() = runBlocking {
        val id = database.entryDao().observeAll().first()
            .first { it.title == "Chris on allocators" }.id
        container.entries.setSaved(id, isSaved = true)
    }

    private fun likeTheStravaPiece() = runBlocking {
        val id = database.entryDao().observeAll().first()
            .first { it.title.startsWith("Investigations") }.id
        container.entries.setLiked(id, isLiked = true)
    }

    /** Returns the id of the GIJN article, so a test can open it and scope from its byline. */
    private fun seed(): Long = runBlocking {
        gijnId = database.feedDao().insert(feed("https://gijn.org/feed/", "GIJN"))
        nullProgramId = database.feedDao()
            .insert(feed("https://nullprogram.com/feed/", "Null Program"))
        database.entryDao().upsertAll(
            listOf(
                entry(
                    gijnId,
                    "a",
                    "Investigations Using the Strava Fitness App",
                    "<p>The heatmap gave away the perimeter of the base.</p>",
                ),
                entry(
                    nullProgramId,
                    "b",
                    "Chris on allocators",
                    "<p>An arena is the whole allocator, and the arena is freed at once.</p>",
                ),
            ),
        )
        database.entryDao().observeAll().first().first { it.feedId == gijnId }.id
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
