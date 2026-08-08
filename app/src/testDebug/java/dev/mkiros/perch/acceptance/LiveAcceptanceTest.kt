package dev.mkiros.perch.acceptance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import com.google.common.truth.Truth.assertWithMessage
import dev.mkiros.perch.data.db.PerchDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.net.PerchHttp
import dev.mkiros.perch.data.parse.ArticleBlock
import dev.mkiros.perch.data.parse.ArticleLowering
import dev.mkiros.perch.data.repo.SourceResolution
import dev.mkiros.perch.di.AppContainer
import dev.mkiros.perch.ui.article.ArticleScreen
import dev.mkiros.perch.ui.article.ArticleUiState
import dev.mkiros.perch.ui.article.ArticleViewModel
import dev.mkiros.perch.ui.screenshot.Screenshots
import dev.mkiros.perch.ui.screenshot.awaitInRealTime
import dev.mkiros.perch.ui.theme.PerchTheme
import dev.mkiros.perch.ui.theme.ThemeMode
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T32 — the daily-driver gate. Everything before this proves Perch works against
 * *fixtures*; this proves it works against **the real internet**, and that forty-two
 * sources come out looking like one publication.
 *
 * It is network-gated and excluded from the default build, because `./gradlew test` must
 * stay offline and deterministic. Run it deliberately:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'
 * ```
 *
 * Three gates, in one method because they are one run: the pull feeds the standardize
 * pass, which picks the sample the screenshots render. Every gate collects its failures
 * rather than throwing, so one broken source names itself and the other two gates still
 * report their counts — a live run that tells you only the first thing that went wrong
 * costs another five minutes to learn the second.
 *
 * Where it deviates from PLAN.md: the file lives in `src/testDebug` rather than
 * `src/test`. Gate 3 needs a Compose rule, and `ui-test-manifest` is a
 * `debugImplementation` — a `src/test` class holding one fails the *release* unit-test
 * variant, which `./gradlew test` also runs, before `assumeTrue` ever executes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LiveAcceptanceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: PerchDatabase
    private lateinit var container: AppContainer

    private val clock: Clock = Clock.systemUTC()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PerchDatabase.inMemory(context)
        container = AppContainer(
            database = database,
            httpClient = PerchHttp.client(cacheDir = null),
            clock = clock,
        )
        // The figure treatment is what gate 3 is looking at, and an image block collapses
        // the whole figure on a failed load — so every remote image resolves to a flat
        // placeholder of a fixed shape. Layout structure is the subject; the photograph
        // is not, and fetching real ones would make the capture depend on the weather.
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(FlatImages(context)) }
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
        database.close()
    }

    @Test
    fun `every source in the reading list pulls, standardizes and reads as one publication`() {
        assumeTrue(
            "live acceptance is opt-in: rerun with -Pperch.live=true",
            System.getProperty(LIVE_PROPERTY) == "true",
        )
        val failures = mutableListOf<String>()

        val pull = pullEveryFeed()
        report("GATE 1 (pull)", pull.summary())
        if (pull.withEntries < MIN_LIVE_SOURCES) {
            failures += "gate 1: only ${pull.withEntries}/${pull.attempted} sources " +
                "resolved to a feed with ≥1 entry (need $MIN_LIVE_SOURCES). " +
                "Refused: ${pull.refusals}"
        }

        val standard = lowerEveryEntry()
        report("GATE 2 (standardize)", standard.summary())
        failures += standard.failures
        if (standard.unsupported.size * 100 > standard.blocks * UNSUPPORTED_PERCENT) {
            failures += "gate 2: ${standard.unsupported.size}/${standard.blocks} blocks are " +
                "Unsupported (over $UNSUPPORTED_PERCENT%). Extend the mapper for: " +
                "${standard.unsupported.distinct().sorted()}"
        }

        if (standard.samples.isEmpty()) {
            failures += "gate 3: nothing was pulled, so there was nothing to render"
        } else {
            val shots = captureHostileSample(standard.samples)
            report("GATE 3 (one publication)", shots.joinToString("\n"))
        }

        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
    }

    // ---- gate 1: pull ----------------------------------------------------------

    private class PullReport(val attempted: Int) {
        var withEntries = 0
        val refusals = mutableListOf<String>()
        fun summary() = "$withEntries/$attempted sources resolved with ≥1 entry" +
            refusals.sorted().joinToString("") { "\n  refused  $it" }
    }

    /**
     * Every URL in `fixtures/feeds.txt` through the stack the add-source sheet uses —
     * [dev.mkiros.perch.data.net.FeedFetcher] (SPEC.md §6 limits and all) → discovery →
     * `FeedParser` → the database. Not a reimplementation of the app's pull: the app's
     * pull, pointed at the real internet.
     *
     * Four in flight, matching the repository's own refresh concurrency, and each source
     * is committed the moment it resolves so the parsed feed can be collected rather than
     * forty-two of them held at once.
     */
    private fun pullEveryFeed(): PullReport = runBlocking {
        val urls = feedUrls()
        val report = PullReport(urls.size)
        val inFlight = Semaphore(CONCURRENCY)
        val writes = Mutex()

        coroutineScope {
            urls.map { url ->
                async {
                    inFlight.withPermit {
                        val outcome = runCatching { container.feeds.resolve(url) }
                        val resolution = outcome.getOrElse {
                            return@withPermit "$url — threw ${it.javaClass.simpleName}: ${it.message}"
                        }
                        when (resolution) {
                            is SourceResolution.Resolved -> {
                                if (resolution.entryCount == 0) return@withPermit "$url — feed has no entries"
                                writes.withLock { container.feeds.add(resolution) }
                                null
                            }
                            is SourceResolution.AlreadySubscribed ->
                                "$url — collided with ${resolution.title}, already subscribed"
                            is SourceResolution.NoFeedFound -> "$url — no feed found"
                            is SourceResolution.Unreachable -> "$url — ${resolution.message}"
                        }
                    }
                }
            }.awaitAll().forEach { refusal ->
                if (refusal == null) report.withEntries++ else report.refusals += refusal
            }
        }
        report
    }

    // ---- gate 2: standardize ---------------------------------------------------

    /** One entry's shape, kept so gate 3 can pick the hostile sample out of the corpus. */
    private class Sample(
        val entryId: Long,
        val feedUrl: String,
        val title: String,
        val code: Int,
        val images: Int,
        val enumerated: Int,
    )

    private class StandardizeReport {
        var entries = 0
        var blocks = 0
        val unsupported = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val samples = mutableListOf<Sample>()
        fun summary() = "$entries entries lowered to $blocks blocks; " +
            "${unsupported.size} Unsupported ${unsupported.distinct().sorted()}"
    }

    /**
     * Lowers every entry the pull stored. The rows are already `HtmlSanitizer` output —
     * sanitizing lives in `FeedRepository`, so nothing downstream ever sees feed markup —
     * which makes reading them back and calling `toBlocks` the app's exact article
     * pipeline rather than an approximation of it.
     *
     * Read one feed at a time: the corpus is tens of megabytes of content and the unit
     * test JVM is not generously heaped.
     */
    private fun lowerEveryEntry(): StandardizeReport = runBlocking {
        val report = StandardizeReport()
        for (feed in database.feedDao().getAll()) {
            for (entry in database.entryDao().observeByFeed(feed.id).first()) {
                report.entries++
                val blocks = try {
                    ArticleLowering.toBlocks(entry.contentHtml)
                } catch (t: Throwable) {
                    report.failures += "gate 2: ${feed.feedUrl} “${entry.title}” — " +
                        "toBlocks threw ${t.javaClass.simpleName}: ${t.message}"
                    null
                } ?: continue
                if (!entry.contentHtml.isNullOrBlank() && blocks.isEmpty()) {
                    report.failures += "gate 2: ${feed.feedUrl} “${entry.title}” — a " +
                        "${entry.contentHtml!!.length}-char body lowered to zero blocks"
                }
                val flat = flatten(blocks)
                report.blocks += flat.size
                report.unsupported += flat.filterIsInstance<ArticleBlock.Unsupported>().map { it.label }
                report.samples += entry.sample(feed.feedUrl, flat)
            }
        }
        report
    }

    private fun EntryEntity.sample(feedUrl: String, blocks: List<ArticleBlock>) = Sample(
        entryId = id,
        feedUrl = feedUrl,
        title = title,
        code = blocks.count { it is ArticleBlock.Code },
        images = blocks.count { it is ArticleBlock.Image },
        enumerated = blocks.count { it is ArticleBlock.ListBlock || it is ArticleBlock.Table },
    )

    /** Quote is the one block that holds blocks; its contents count too. */
    private fun flatten(blocks: List<ArticleBlock>): List<ArticleBlock> =
        blocks.flatMap {
            if (it is ArticleBlock.Quote) listOf(it) + flatten(it.blocks) else listOf(it)
        }

    // ---- gate 3: look like one publication --------------------------------------

    private class Shot(val name: String, val viewModel: ArticleViewModel, val mode: ThemeMode)

    /**
     * The article screen for a deliberately hostile sample, light and dark.
     *
     * The sample is chosen *from what came back*, not hardcoded: the code-heavy and
     * image-heavy picks name their source and fall back to the whole corpus if that
     * source is down, so a dead feed degrades the sample instead of failing the gate on
     * something gate 1 already reported.
     */
    private fun captureHostileSample(samples: List<Sample>): List<String> {
        val picks = listOfNotNull(
            pick(samples, "code-nullprogram") { it.feedUrl.contains("nullprogram.com") to it.code },
            pick(samples, "code-regehr") { it.feedUrl.contains("regehr.org") to it.code },
            pick(samples, "images") { it.feedUrl.contains("ciechanow.ski") to it.images },
            pick(samples, "enumerated") { true to it.enumerated },
            pick(samples, "longest-headline") { true to it.title.length },
        ).distinctBy { it.second.entryId }

        val current = mutableStateOf<Shot?>(null)
        compose.setContent {
            current.value?.let { shot ->
                key(shot.name) {
                    PerchTheme(mode = shot.mode, dynamicColor = false) {
                        ArticleScreen(viewModel = shot.viewModel, onBack = {})
                    }
                }
            }
        }

        val dir = Screenshots.dir(SCREENSHOT_DIR)
        dir.listFiles()?.forEach { it.delete() }
        return picks.flatMap { (name, sample) ->
            listOf(ThemeMode.Light, ThemeMode.Dark).map { mode ->
                val viewModel = ArticleViewModel(
                    entries = container.entries,
                    feeds = container.feeds,
                    articleText = container.articleText,
                    entryId = sample.entryId,
                    zone = ZoneOffset.UTC,
                )
                val suffix = if (mode == ThemeMode.Light) "light" else "dark"
                current.value = Shot("$name-$suffix", viewModel, mode)
                compose.awaitInRealTime("“${sample.title}” to load") {
                    viewModel.state.value !is ArticleUiState.Loading
                }
                val shot = Screenshots.capture(compose, compose.activity, dir, "$name-$suffix")
                check(shot.distinctColours > 8) { "${shot.file.name} rendered a blank slab" }
                "  ${shot.file.name} (${shot.file.length() / 1024} KB) — " +
                    "${sample.feedUrl}: “${sample.title.take(HEADLINE_ECHO)}”"
            }
        }
    }

    /**
     * The sample scoring highest on [by], among those it admits. Falls back to the whole
     * corpus when the named source contributed nothing, so a feed that was down at gate 1
     * costs the sample its best case rather than costing gate 3 a screenshot.
     */
    private fun pick(
        samples: List<Sample>,
        name: String,
        by: (Sample) -> Pair<Boolean, Int>,
    ): Pair<String, Sample>? {
        val admitted = samples.filter { by(it).let { (eligible, score) -> eligible && score > 0 } }
        val best = admitted.maxByOrNull { by(it).second }
            ?: samples.maxByOrNull { by(it).second }
            ?: return null
        return name to best
    }

    // ---- harness ----------------------------------------------------------------

    private fun feedUrls(): List<String> =
        File(Screenshots.repoRoot(), "fixtures/feeds.txt").readLines()
            .map { it.trim() }
            .filter { it.startsWith("http") }

    /** Straight to stdout: these counts are what the commit message has to quote. */
    private fun report(gate: String, body: String) = println("\n$gate\n$body")

    /** Every remote image becomes one flat, fixed-shape placeholder. See [setUp]. */
    private class FlatImages(private val context: Context) : Mapper<String, Drawable> {
        override fun map(data: String, options: Options): Drawable = BitmapDrawable(
            context.resources,
            Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
                eraseColor(PLACEHOLDER)
            },
        )

        private companion object {
            const val WIDTH = 1200
            const val HEIGHT = 675
            const val PLACEHOLDER = 0xFF8E9A94.toInt()
        }
    }

    private companion object {
        const val LIVE_PROPERTY = "perch.live"

        /** PLAN.md T32 gate 1: ≥38 of the 42 sources must come back with a feed. */
        const val MIN_LIVE_SOURCES = 38

        /** PLAN.md T32 gate 2. */
        const val UNSUPPORTED_PERCENT = 2

        /** The repository's own refresh concurrency (SPEC.md §7). */
        const val CONCURRENCY = 4

        const val SCREENSHOT_DIR = "build/perch-screenshots"
        const val HEADLINE_ECHO = 60
    }
}
