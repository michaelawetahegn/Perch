# PLAN-10.md — v0.6.1: simpler, cleaner, better tested, and it behaves exactly the same

**This is the active plan.** v0.1–v0.6 are complete, frozen, and history only, archived in
`docs/plans/`. The process is `docs/RALPH.md`.

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

---

## §0 — Decisions for this version (authoritative; do not re-derive)

This plan is a **tech-debt pass**, not a feature batch. The human's brief, verbatim in spirit:
*leave the code simpler, cleaner and better tested than you found it, with behaviour unchanged.*
Six read-only surveys of the whole tree (data, UI, dead code, latent bugs, the test suite,
architecture and doc drift) produced the findings below; every task carries its anchors so a
session **reads, never searches**. Issues **#34–#59** are one per task, D01–D26.

### §0.1 The version is `0.6.1`, `versionCode` **8**, database stays **7**

A release that is only bug fixes, polish and docs moves the **PATCH** digit (SPEC.md §1). Nothing
in this plan is a feature. `versionCode` goes up by exactly 1. Both live at
`app/build.gradle.kts:12-13` and **nowhere else**. **No task may change the schema** — a new
`@Query` on a DAO is not a schema change; a new column, table, index or trigger is, and is out
of scope here.

### §0.2 The three rules of this pass, and the standing gates

1. **Behaviour is unchanged, except where a failing test demonstrates a bug.** D03–D06 are
   bugs: each must show RED for the reported reason before GREEN. Every other task is a
   refactor or a deletion whose proof is the suite staying green *and* the task naming, in its
   commit message, the existing tests that pin the behaviour it touched. If the survey found
   no test pins it, **write the pinning test first**, before touching the implementation.
2. **Prefer deleting to adding.** A refactor that adds more lines than it removes needs a
   sentence in the commit saying why the trade is worth it. Record `wc -l` of
   `app/src/main` and of `app/src/test app/src/testDebug` before and after in the commit.
   Baseline on 2026-09-07: **main 17,806 · tests 27,372** (`find … -name '*.kt' | xargs wc -l`).
3. **If an improvement needs a behaviour change to justify it, write it into `TECH_DEBT.md`
   and move on.** That file exists and is seeded from the survey. A task must not quietly
   widen into one of its entries.

Standing gates, unchanged from `docs/plans/PLAN-9-v0.6.md` §0.2: no `Color(0x` / `N.dp` /
`N.sp` outside `ui/theme/`; **no hostname literal under `data/`**
(`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"' app/src/main/java/dev/mkiros/perch/data/`);
TDD; two attempts then `BLOCKED`; commit and **push** every task; close the issue it names with
a comment naming the commit and the command that verified it. **`fallbackToDestructiveMigration()`
never comes back.** No new dependencies.

### §0.3 The test floor is 1848, and how it may move

Baseline `./gradlew test` on 2026-09-07 at `46a145d`: **1848 tests (1081 debug + 767 release),
0 failures, 1 skipped** (the `-Pperch.live` gate), **6 m 28 s**. That is the gate for every task
— run it in the foreground and wait.

- The count **may not drop** through a consolidation task (D08–D12): those delete *helpers*,
  never tests. If a test file loses a test, the task is wrong.
- The count may drop by a named test only in **D01**, and only for a test whose *subject* was
  deleted; the commit names each one and the test that still covers the behaviour.
- **D14 adds tests and nothing else**; after it the count must be above 1848 again, and it
  stays above from there.
- **Screenshot tests are a pixel gate for the UI refactors (D23–D26).** If a screenshot test
  compares against a baseline and fails after a refactor, the refactor changed pixels: fix the
  refactor. Do not retake a baseline in this plan.

### §0.4 Where shared test support lives, decided

`app/src/test` **is visible to `app/src/testDebug`** — AGP compiles `test` + `testDebug` into
`testDebugUnitTest` — and the reverse is not true. Proof: `LiveAcceptanceTest` and
`ArticleFullTextTest` (both `testDebug`) already import `ArticleFixtures` from
`app/src/test/.../data/extract/`. So:

- **JVM-only support** (entity builders, the receiver-less await, `FakeFetcher`, the migration
  harness) goes in **`app/src/test/java/dev/mkiros/perch/support/`** — a new package, one file
  per concern, so it serves both source sets.
- **Compose-dependent support** (`awaitInRealTime(ComposeTestRule)`, the Home harness, the
  image stub) stays in `testDebug` under **`ui/screenshot/`**, where `ScreenshotSupport.kt`
  already lives and 12 files already import from. Do not create a second Compose-support
  package; do not move `ScreenshotSupport.kt`.
- `PerchDatabase.inMemory(context)` remains the **only** way a test builds a database (U03).

### §0.5 The four bugs — root causes established, fix shapes decided

**D03 / #36 — `loadFullText` writes a stale whole row.** `ArticleTextRepository.kt:43` reads
`entry`, `:46` fetches the page (seconds), `:68-75` does `entry.copy(contentHtml, fullTextAt,
imageUrl)` → `entryDao.update(updated)` (`EntryDao.kt:483-484`, Room `@Update`, every column).
`ArticleViewModel.toggleSaved`/`toggleLiked` (`ArticleViewModel.kt:189,197`) can land inside
that window — the screen fetches full text on open (`:120-124`) — and are reverted; `:151`
then re-renders the stale flag. `FeedRepository.mutate` (`FeedRepository.kt:407-410`) exists
precisely to avoid this and `ArticleTextRepository` does not use it. **Fix: a targeted
`@Query("UPDATE entries SET contentHtml = :contentHtml, fullTextAt = :fullTextAt, imageUrl =
:imageUrl WHERE id = :id")`, then re-read the row with `findById` and index *that*.** The
RED: a `PageFetcher` whose `fetch` calls `entryDao.setSaved(id, true, now)` before returning
the page; after `loadFullText`, `findById(id).isSaved` must still be true.

**D04 / #37 — a re-pasted link stays un-saved.** `SavedLinkRepository.kt:97` sets `isSaved =
true`; `:104` calls `entryDao.upsertAll`, which carries the existing row's flags forward
(`EntryDao.kt:553-556`) because a *feed* must never overwrite reader state. A pasted link is
the one caller for which the incoming flag is the reader's intent. **Fix: after `upsertAll`,
if the re-read row (`:105`) is not saved, `entryDao.setSaved(id, true, clock.millis())`.**
Do not touch `upsertAll` — U04's rule is right for every other caller. The RED: paste, then
`entries.setSaved(id, false)`, then paste again; the row must be saved.

**D05 / #38 — cancellation recorded as failure.** `FeedRepository.kt:318` `runCatching {
fetchAndStore(…) }` and `BackfillRepository.kt:117` `runCatching { fetchAndStore(…) }` both
catch `CancellationException`; the feed path then runs `recordFailure`, bumping
`consecutiveFailures` and storing the message in `lastError`. `RefreshWorker.kt:37` and
`BackfillWorker.kt:43` already `catch (e: CancellationException) { throw e }` — copy that
awareness down. **Fix: rethrow `CancellationException` inside both `runCatching` blocks**
(`.onFailure { if (it is CancellationException) throw it }`, or a `try`/`catch` that rethrows).
The RED, JVM: `MockWebServer` with a delayed body, `launch { repo.refreshAll() }`, cancel the
job while the request is open, then assert the feed's `consecutiveFailures == 0` and
`lastError == null`. Cancellation must reach the caller, not the database.

**D06 / #39 — a throw in a ViewModel action is a crash.** `SaveLinkViewModel.submit`
(`SaveLinkViewModel.kt:70-83`) has no `try` around `savedLinks.saveLink`, which throws at
`SavedLinkRepository.kt:78,106` and on any Room error; the KDoc at `:78` even says failure only
arrives as `SaveLinkFailure`. `SettingsViewModel.kt:174,191,219,237` catch `IOException` only;
a revoked SAF URI raises `SecurityException`. **Fix: catch `Exception` in those five places,
rethrow `CancellationException`, and surface the message through the state field that already
exists** (`error` / `message`). No new strings — reuse the existing failure copy. The RED:
a fake repository (or a throwing `read`/`write` lambda) that throws `IllegalStateException` /
`SecurityException`; the state must end with `isBusy = false`, `canDismiss = true` and a
non-null message, and the test must not need an uncaught-exception handler to pass.

### §0.6 Where deduplicated code lands, decided

- **URL helpers** (`hostRoot`, `pathOf`): `data/parse/FeedXml.kt` beside `hostOf` (`:96-99`)
  and `stableGuid` (`:106-110`).
- **Page parse + entity mapping**: `data/extract/PageContent.kt`, which already owns
  `PageContentExtractor` (`:37-52`). A `PageContentExtractor.parse(bytes, baseUrl): Document?`
  (jsoup stays inside `data/extract`) and a `PageContent.toEntry(feedId, finalUrl, publishedAt,
  isEstimated, …)`. `ArticleTextRepository.isFullerThan` becomes a comparison of
  `ArticleExtractor.proseLength` — the caller its KDoc (`ArticleExtractor.kt:93-100`) promises.
- **`robots.txt`**: `RobotsRules` (`data/archive/RobotsRules.kt`) gains `sitemaps: List<String>`
  parsed from `Sitemap:` lines; `ArchiveDiscovery` takes a `RobotsRules` instead of fetching.
  One fetch per backfill, in `BackfillRepository`.
- **RSS/RDF entry mapping**: a shared `internal fun` in `data/parse/` (a new `ItemMapping.kt`
  or inside `FeedXml.kt`) taking the guid and the date-element names as parameters. The three
  `UNTITLED_*` declarations collapse to one `internal const` in `FeedXml.kt`.
- **Folder resolution**: `ProfileRepository.FolderResolver` (`ProfileRepository.kt:201-215`)
  becomes a top-level `internal class FolderResolver(folders: FolderRepository)` in
  `data/repo/`, and `OpmlRepository.import` (`OpmlRepository.kt:82-100`) uses it.
- **`normalizePastedUrl`**: `ui/source/PastedUrl.kt` → `data/parse/PastedUrl.kt`, same
  function name; its test moves to `app/src/test/.../data/parse/`.
- **The `model` package**: `dev.mkiros.perch.model` holds `TimeFilter`, `ThemeMode`,
  `RefreshInterval`, `BackfillRunner`/`BackfillProgress`/`BackfillRunState`, `RefreshScheduler`.
  Nothing else moves; entities stay where they are (TECH_DEBT.md says why).
- **The WorkManager seams**: `AppContainer` gains `backfillRunner: BackfillRunner =
  BackfillRunner.NoOp` and `refreshScheduler: RefreshScheduler = RefreshScheduler { }`;
  `PerchApp` wires the real ones. Both context-taking factories become `factory(container)`.
- **UI**: `PagedEntryList` in `ui/home/PagedList.kt` (beside `pagedFooter`, which Collection and
  Search already import); `EmptyState` in a new `ui/home/EmptyState.kt`, grown from
  `SearchSurface.Prompt`; `UrlFormContent` in a new `ui/source/UrlForm.kt`; the small `ui/home`
  composables stay in the file of the first surviving copy.

### §0.7 Rungs

`./gradlew test` is the rung for every task in this plan — **`unit`**, 6.5 minutes, in the
foreground. D02 is `build` (Gradle config changes). D29 is the live suite. D30 is `build`.
No task in this plan takes a device screenshot.

---

## The tasks

- [x] **D01 — Nothing that has no caller. Issue #34.**
      `gh issue view 34 --json body`. Delete, in `main`: `HomeViewModel.selectSource` and
      `selectFolder` (`HomeViewModel.kt:517-527`; `grep -rn '\.selectSource(\|\.selectFolder('
      app/src` returns nothing — `HomeScreen.kt:196/202` are local lambdas, not these),
      `EntryRepository.observeUnreadEntries` (`:155-157`, KDoc included), `FeedRepository.reach`
      (`:128`; production reads `entries.reach` at `HomeViewModel.kt:377`),
      `Dimens.inlineCodePadding` (`Dimens.kt:157-158`) and `Dimens.linkUnderline` /
      `linkUnderlineOffset` (`:196-198`; `ArticleType.link` is `TextDecoration.Underline`,
      `Type.kt:200`, so the tokens never drew anything — TECH_DEBT.md already records the
      DESIGN.md §8 gap). In tests: `WindowInsetsSupport.kt:56` `CUTOUT_PX`,
      `CodeScreenshotTest.kt:192` `stringEnd()`. In the catalog: the `kotlinx-coroutines-core`
      alias (`gradle/libs.versions.toml:70`, declared by no build file).
      - `FeedRepositoryTest.kt:595,604` test the deleted `reach`. If `EntryRepositoryTest`
        already covers `reach` for the same cases, delete them and **name both in the commit**;
        if not, move them. This is the only place in the plan a test may be deleted (§0.3).
      - Fix the two KDocs that describe callers that do not exist: `FeedRepository.kt:109-111`
        (`observeSourceCount` — only `DebugSeeder.kt:48` calls it; say so) and
        `EntryRepository.kt:155` (goes with the deletion).
      - Do **not** delete the test-only surface listed in TECH_DEBT.md ("Deliberate").
      - **Done 2026-09-07 — two of the named symbols were survey false positives and stay.**
        `CUTOUT_PX` has three callers (`WindowInsetsTest.kt:124/128/135`,
        `ImageViewerScreenshotTest.kt:113`, `LiveAcceptanceTest.kt:1484-1495`); deleting it
        breaks the build. `CodeScreenshotTest.kt:192` is a line *inside* the `KOTLIN_SAMPLE`
        string literal the highlighter is screenshotted against, not a declaration — the
        survey grep matched fixture text. Neither `EntryRepositoryTest` reach test covered
        `entryCount` or the empty source, so both `FeedRepositoryTest` tests were **moved**,
        not deleted, and the count held at 1848.
      - Done: `./gradlew test` green, count ≥ 1846 with every removed test named; `wc -l`
        before/after in the commit; issue #34 closed.
      - Rung: unit

- [x] **D02 — The docs and the build describe the app that exists. Issue #35.**
      `gh issue view 35 --json body`. Fix, each against the tree:
      - `SPEC.md:96-118` §3 package structure: rewrite from `find app/src/main -type d` — no
        `net/HttpModule.kt` (it is `data/net/PerchHttp.kt`), no `ui/components/`, no
        `RefreshCoordinator.kt`, no `androidTest`; add `data/archive`, `data/extract`,
        `data/profile`, `ui/collection`, `ui/search`, `ui/brand`, `ui/article/code`,
        `ui/article/zoom`. `:100` claims `AppContainer` holds "dispatchers" — it does not.
      - `SPEC.md:51-77` §2: add Paging 3 (`paging-runtime`, `paging-compose`, `room-paging`,
        `paging-testing`; `libs.versions.toml`, `app/build.gradle.kts:136-139,161`) with the
        one-line justification NOTES.md's U07a line gives.
      - `SPEC.md:22`: distribution is the release-signed `perch-<version>.apk` (U02), not a
        debug APK.
      - `CLAUDE.md:179-180`: screenshots go through the `Screenshots` helper
        (`ScreenshotSupport.kt`), never `captureToImage()` — then delete NOTES.md's "CLAUDE.md is
        wrong" clause (`NOTES.md:13-14`). `CLAUDE.md:184` and `SPEC.md:114`: there is no
        `src/androidTest`; Compose UI tests live in `app/src/testDebug` (NOTES.md:10).
      - `app/build.gradle.kts:165-167`: remove the three `androidTestImplementation` lines and
        `:35`'s `testInstrumentationRunner`; the source set does not exist.
      - `.gitignore`: add `.kotlin/` (`git check-ignore .kotlin` → not ignored today).
      - Done: `./gradlew test` green (the Gradle edit must still build); every quoted line
        fixed; issue #35 closed.
      - Rung: build
      - **Done 2026-09-07.** §3 rewritten from `find app/src/main -type d` plus the real file
        lists (28 lines → 42): `data/{archive,extract,profile,settings}`, `ui/{brand,collection,
        search,article/code,article/zoom}` added; `HttpModule.kt`, `Converters.kt`, `FetchResult.kt`,
        `ContentBlocks.kt`, `RefreshCoordinator.kt`, `SettingsRepository.kt`, `Routes.kt`,
        `SourceDrawer.kt`, `SourceViewModel.kt`, `ManageSourceDialogs.kt` and `ui/components/`
        gone; `AppContainer` now reads "clock, connectivity, settings" (it holds no dispatchers);
        `src/androidTest` replaced by `src/testDebug` with the `ui-test-manifest` reason. §2 gained
        Paging 3 (runtime-ktx/compose 3.3.5, room-paging, paging-testing) with a **Why Paging 3**
        paragraph from NOTES.md's U07a line, plus `ui-test-junit4`. §1 distribution is the
        release-signed `perch-<version>.apk`. CLAUDE.md:135 and :177 fixed (Screenshots helper,
        no `src/androidTest`); NOTES.md's "CLAUDE.md is wrong" clause deleted. `app/build.gradle.kts`
        lost `testInstrumentationRunner` and all three `androidTestImplementation` lines.
        `.gitignore` gained `.kotlin/`.

- [x] **D03 — Read later and Like survive a full-text load. TDD. Issue #36.**
      `gh issue view 36 --json body`. §0.5 carries the root cause and the fix shape; the RED
      lives in `ArticleTextRepositoryTest.kt` (`app/src/test/.../data/repo/`, 11 tests today,
      none concurrent) using the fetcher hook described there.
      - The targeted `UPDATE` goes on `EntryDao` beside `setSaved`/`setStarred` (`:355-358`).
        Re-read with `findById` after the write; index the re-read row; return it under the
        same `takeIf { safeHtml != null }` rule as today (`:79`).
      - A second RED for the same class: a title changed by a refresh during the fetch must not
        be reverted either.
      - Done: both tests named in the commit, RED output pasted, then GREEN; `./gradlew test`
        green and growing; issue #36 closed.
      - Rung: unit
      - **Done 2026-09-07.** `EntryDao.setFullText(id, contentHtml, fullTextAt, imageUrl)` —
        a targeted `UPDATE`, added beside `setSaved`/`setStarred` — replaces the Room `@Update`
        of the whole row; `loadFullText` then re-reads with `findById`, indexes *that* row and
        returns it under the unchanged `takeIf { safeHtml != null }`. RED (both new tests in
        `ArticleTextRepositoryTest`, fetcher hook writing mid-fetch): `a Read later tapped while
        the page is loading survives the write-back FAILED` / `a title a refresh corrected during
        the fetch is not reverted FAILED`, `13 tests completed, 2 failed`. GREEN: same command
        `BUILD SUCCESSFUL`. `./gradlew test` **BUILD SUCCESSFUL**, 1083 debug + 769 release =
        **1852** (floor 1848). No schema change — the columns and the DB version are untouched.

- [x] **D04 — Pasting a link you had removed puts it back on To-Read. TDD. Issue #37.**
      `gh issue view 37 --json body`. §0.5: `SavedLinkRepository.kt:97,104-106`; the RED goes
      beside `pasting the same link twice does not duplicate and does not error`
      (`SavedLinkRepositoryTest.kt:83`), which passes today only because it never un-saves.
      - `upsertAll` is **not** touched (U04); the flag is set after it, on the re-read row.
      - Done: the RED output and the GREEN in the commit; `./gradlew test` green and growing;
        issue #37 closed.
      - Rung: unit
      - **Done 2026-09-07.** RED (`:app:testDebugUnitTest --tests '*SavedLinkRepositoryTest*'`,
        new `pasting a link that was removed from To-Read puts it back on the queue`):
        `FAILED ... SavedLinkRepositoryTest.kt:97`, `8 tests completed, 1 failed`. GREEN: same
        command `BUILD SUCCESSFUL`. `./gradlew test` **BUILD SUCCESSFUL**, 1084 debug + 770
        release = **1854** (floor 1848). `upsertAll` untouched; the flag is set on the re-read
        row via the existing `EntryDao.setSaved`.

- [x] **D05 — A cancelled refresh is not a failed source. TDD. Issue #38.**
      `gh issue view 38 --json body`. §0.5: `FeedRepository.kt:318`, `BackfillRepository.kt:117`;
      the precedent is `RefreshWorker.kt:37`. RED in `FeedRepositoryTest.kt` (it already uses
      `MockWebServer`; a `Dispatcher` that blocks on a latch holds the request open) and a twin
      in `BackfillRepositoryTest.kt` with its `FakeFetcher` suspending on a `CompletableDeferred`.
      - **Bounded:** if the cancellation cannot be made to land inside the fetch inside two
        attempts, mark BLOCKED with the diagnosis — do not ship the rethrow without its RED.
      - Done: both REDs shown, both GREEN; `./gradlew test` green and growing; issue #38 closed.
      - Rung: unit
      - **Done 2026-09-07.** The plan's suggested RED — `MockWebServer` holding the response
        open, `job.cancel()` mid-fetch — **passes on the unfixed code**: Room refuses a write
        on a cancelled coroutine, so `recordFailure` throws before it can store anything. It is
        kept as `a refresh cancelled mid-fetch is not recorded as the source failing` (it pins
        the end-to-end property), and the RED that lands delivers the cancellation *from* the
        fetch with the caller alive — an OkHttp interceptor that throws `CancellationException`
        for the feed, `FakeFetcher.cancelOn` for the archive. RED: `expected instance of:
        java.util.concurrent.CancellationException / but was: null`, `FeedRepositoryTest.kt:347`
        and `BackfillRepositoryTest.kt:240`, `48 tests completed, 2 failed`. GREEN: same command
        `BUILD SUCCESSFUL`. `./gradlew test` **BUILD SUCCESSFUL**, 1087 debug + 773 release =
        **1860** (floor 1848). Fix is `.onFailure { if (it is CancellationException) throw it }`
        in both `runCatching` blocks, per §0.5.

- [x] **D06 — A throw in a ViewModel action becomes a message, not a crash. TDD. Issue #39.**
      `gh issue view 39 --json body`. §0.5: `SaveLinkViewModel.kt:70-83` and
      `SettingsViewModel.kt:174,191,219,237`. REDs in `SaveLinkViewModelTest.kt`
      (`app/src/test/.../ui/collection/`, drives `Result.failure` today only) and a new
      `SettingsViewModelTest.kt` beside it — the four transfer functions have **no test at all**
      today, so this task also pins their happy paths.
      - `CancellationException` is rethrown, never swallowed (D05's rule, same session).
      - No new strings: the existing `error`/`message` copy carries the reason.
      - Done: five REDs (one per site) shown and GREEN; `./gradlew test` green and growing;
        issue #39 closed.
      - Rung: unit
      - **Done 2026-09-07.** RED, `./gradlew :app:testDebugUnitTest --tests
        '*SaveLinkViewModelTest*' --tests '*SettingsViewModelTest*'`: `14 tests completed,
        6 failed` — one per site (`SaveLinkViewModelTest.kt:174` timed out with the sheet
        stuck on `isBusy = true`; the four `SettingsViewModelTest.kt:198` timed out with
        `last message was null`, the `SecurityException` having left `viewModelScope`),
        plus one happy-path assertion that `org.json` escapes `/`. GREEN: same command
        `BUILD SUCCESSFUL`. `./gradlew test` **BUILD SUCCESSFUL**, 1097 debug + 783 release
        = **1880** (floor 1848). Fix per §0.5: `catch (e: Exception) { if (e is
        CancellationException) throw e; … }` at all five sites, surfaced through the
        existing `SaveLinkFailure.Unreachable` / `SettingsMessage.TransferFailed` copy.
        `SettingsViewModel` had no test at all before this; the four transfers' happy paths
        are pinned here too.

- [x] **D07 — `countSavedOrLikedIn` chunks like its siblings. Issue #40.**
      `gh issue view 40 --json body`. `EntryDao.kt:374` is the one `IN (:ids)` without
      `.chunked(MAX_IDS_PER_STATEMENT)` (`:325,474,575,582`). Follow `setRead`'s shape exactly:
      the abstract `@Query` becomes private-ish, an `open suspend fun` sums the chunks.
      - The pinning test first: 2,000 feed ids, a known number saved/liked among them, the
        count is right. It will pass before the change (Robolectric's SQLite allows it); that is
        fine — it pins, the change is consistency.
      - Done: test named; `./gradlew test` green and growing; issue #40 closed.
      - Rung: unit
      - **Done 2026-09-08.** Pinning test `EntryRepositoryTest.the count survives a batch
        larger than SQLite binds in one statement` — 2,000 feed ids, 2 saved/liked among
        them — passed before the change, as the plan said it would. `./gradlew test`
        **BUILD SUCCESSFUL**, 1098 debug + 784 release = **1882** (was 1880). The
        `@Query` is now `countSavedOrLikedInChunk`; the `@Transaction open suspend fun
        countSavedOrLikedIn` sums `chunked(MAX_IDS_PER_STATEMENT)`, `setRead`'s shape
        exactly. Summing is exact because `feedId` partitions the rows.

- [x] **D08 — One wall-clock poll loop. Issue #41.**
      `gh issue view 41 --json body`. `ScreenshotSupport.kt:117`
      `ComposeTestRule.awaitInRealTime(what, timeoutMs = 20_000, predicate)` is the survivor.
      Replace the 14 hand-rolled copies: `HomeScreenTest.kt:661,680`, `HomeRefreshTest.kt:403`,
      `HomeTimeFilterTest.kt:325`, `HomeTimeRangeTest.kt:250`, `FolderDrawerTest.kt:396`,
      `DrawerMultiSelectTest.kt:513,524`, `BackfillOfferTest.kt:329`, `PerchNavHostTest.kt:365`,
      `ArticleScreenTest.kt:467`, `ArticleFullTextTest.kt:217,236`, `SaveLinkSheetTest.kt:162`,
      `AddSourceSheetTest.kt:230`, `SettingsScreenTest.kt:274`, plus their private
      `TIMEOUT_MS`/`POLL_MS` companions. `awaitDisplayed(text)` as `SearchSurfaceTest.kt:189`
      already writes it (two lines, delegating).
      - The three JVM copies (`SaveLinkViewModelTest.kt:138`, `EntryPagingTest.kt:173`,
        `WorkSchedulerTest.kt:111`) get a receiver-less `awaitInRealTime(what, timeoutMs,
        predicate)` in `app/src/test/.../support/Await.kt` (§0.4); the Compose one delegates
        to it.
      - Timeouts diverged for no stated reason; use the shared default. If a test genuinely
        needs longer, pass it explicitly and say why in a comment.
      - Done: `grep -rn 'System.currentTimeMillis()\|nanoTime()' app/src/test app/src/testDebug`
        finds only `Await.kt` and `ScreenshotSupport.kt`; test count unchanged; `./gradlew test`
        green; issue #41 closed.
      - Rung: unit
      - **Done, 2026-09-08.** 18 poll loops, not 14 — `SettingsViewModelTest.awaitMessage` was a
        fourth JVM copy the survey missed. All of them now delegate to
        `app/src/test/.../support/Await.kt`; `ScreenshotSupport`'s Compose twin delegates too, so
        it no longer reads a clock at all. The five `compose.waitUntil(TIMEOUT_MS)` calls that
        kept a `TIMEOUT_MS` companion alive went with them — a *virtual*-clock wait for a Room
        load is the very bug the wall-clock loop exists for. Shared default 20 s; nothing needed
        longer. The grep's remaining hits are **not** poll loops and were never in scope:
        `FeedParserTest:224,240` time a parse, `LiveAcceptanceTest:1945` ages an entry,
        `EntryRepositoryTest:42` is a KDoc sentence. `./gradlew test`: **1882** (1098 debug + 784
        release), 0 failures; `@Test` count 1004, unchanged against `HEAD`. −276/+97 lines.

- [x] **D09 — One way to build a feed and an entry in a test. Issue #42.**
      `gh issue view 42 --json body`. `FeedEntity(` in 31 test files, `EntryEntity(` in 26.
      Create `app/src/test/.../support/Entities.kt` (§0.4) with `testFeed(…)` and `testEntry(…)`
      builders whose defaults are the ones the 10 testDebug `seedFeed`/`seedEntry` pairs agree
      on (`HomeScreenTest.kt:731/755`, `FolderDrawerTest.kt:436/458`, `HomeTimeFilterTest.kt:375/397`,
      `HomeTimeRangeTest.kt:288/307`, `DrawerMultiSelectTest.kt:573/595`, `HomeRefreshTest.kt:471/493`,
      `BackfillOfferTest.kt:413/433`, `CollectionScreenTest.kt:296/314`, `ArticleScreenTest.kt:481/499`,
      `ArticleFullTextTest.kt:252`, `CollectionRefreshTest.kt:125`), and the JVM twins
      (`EntryFtsIndexTest.kt:155/170`, `PerchDatabaseTest.kt:232/251`, `FolderDaoTest.kt:148/164`,
      `EntrySearchTest.kt:355/372`, `ArticleTextRepositoryTest.kt:260/275`, `EntryPagingTest.kt:246`,
      `EntryRepositoryTest.kt:773`, `OpmlRepositoryTest.kt:57`, `FolderRepositoryTest.kt:219`).
      - **Sized to one session:** convert the testDebug seed pairs and as many JVM twins as fit;
        a file whose builder differs materially (a test *about* a particular column) may keep
        it. Name the files left for a later pass in the commit; do not start a second session.
      - The seeded `Uncategorized`/`saved-links` ids come from `PerchDatabase.inMemory` (U03);
        the builders take a `feedId`, they never guess one.
      - Done: test count unchanged; `./gradlew test` green; the number of files constructing
        `FeedEntity(` directly, before and after, in the commit; issue #42 closed.
      - Rung: unit
      - **Done 2026-09-08.** `app/src/test/.../support/Entities.kt` holds `testFeed(…)` and
        `testEntry(…)`; all 11 testDebug seed pairs and all 9 JVM twins now delegate, so the
        files spelling a constructor out go **32 → 13** for `FeedEntity(` and **26 → 9** for
        `EntryEntity(` — and one of each remaining is `Entities.kt` itself. The defaults are the
        ones the copies already agreed on, with three *derived* rather than fixed because that
        is the relation the copies kept restating by hand: `guid` from `title`, `contentHtml`
        from `summary` (so a body-less fixture stays body-less), `fetchedAt` from `publishedAt`.
        `isRead`/`isSaved`/`isStarred` likewise default to their timestamp being non-null.
        Left for a later pass, none of them a seed *pair*: `BackfillRepositoryTest`,
        `BackfillWorkerTest`, `DesignScreenshotTest`, `FeedRepositoryTest`,
        `HomeEntryActionsTest`, `PagedFeedTest`, `PerchNavHostTest`, `ProfileRepositoryTest`,
        `RefreshWorkerTest`, `SearchFromEverySurfaceTest`, `SearchSurfaceTest`,
        `SettingsViewModelTest`. `./gradlew test`: **1882** (1098 debug + 784 release), 0
        failures; `@Test` count 1004, unchanged against `HEAD`. −355/+76 lines.

- [x] **D10 — One rule opens the database and builds the container. Issue #43.**
      `gh issue view 43 --json body`. 22 files carry the same `@Before`/`@After`
      (`HomeScreenTest.kt:85-99`, `FolderDrawerTest.kt:76-90`, and every other
      `grep -l 'AppContainer(' app/src/test app/src/testDebug`). A JUnit `TestRule`,
      `PerchRule(clock, settings)` in `app/src/test/.../support/PerchRule.kt`, that owns
      `PerchDatabase.inMemory(context)` and `AppContainer(database, httpClient =
      PerchHttp.client(cacheDir = null), clock, settings)` and closes the database after.
      - Compose tests already have `@get:Rule val compose = createComposeRule()`. The plan
        assumed the two rules were independent; **they are not**, and the rule's KDoc now says
        so. `@get:Rule(order = 1)` on `PerchRule` keeps it *inner*, where the hand-written
        `@After` closed the database — inside the Compose environment. Left to the default,
        `BackfillOfferTest` failed the next test in the class with
        `UncaughtExceptionsBeforeTest`: a `viewModelScope` nothing cancels reached a closed
        connection pool after the environment had gone.
      - A test that customises `clock` or `settings` passes them to the rule; nothing else
        changes in test bodies.
      - Done: test count unchanged; `./gradlew test` green; `grep -c 'PerchDatabase.inMemory'`
        across tests before/after in the commit; issue #43 closed.
      - Rung: unit

- [x] **D11 — One Home harness. Issue #44.**
      `gh issue view 44 --json body`. `showHome()` copies at `HomeScreenTest.kt:695`,
      `HomeRefreshTest.kt:419`, `HomeTimeFilterTest.kt:346`, `HomeTimeRangeTest.kt:260`,
      `FolderDrawerTest.kt:409`, `DrawerMultiSelectTest.kt:534`, and the near-copies in
      `HomeEntryActionsTest`, `PagedFeedTest`, `BackfillOfferTest`, `DesignScreenshotTest`
      (`HomeViewModel(` is constructed 13 times across 11 testDebug files). One
      `showHome(container, compose, clock, settings, backfillRunner, …)` in
      `testDebug/.../ui/screenshot/HomeHarness.kt` (§0.4) returning the ViewModel(s) a test
      needs to poll.
      - The hoisted state (`drawerState`, `listState`, `homeScope`, selection, search —
        `PerchNavHost.kt:138-171`) is what every copy re-declares; the harness owns it and
        exposes what tests read.
      - `DesignScreenshotTest` is a pixel gate (§0.3): if its captures change, the harness
        changed layout — fix the harness.
      - The harness composes with the hoisted state **always** created, so a font scale is the
        one seam it cannot pass through unchanged: `LocalDensity` is re-provided only when the
        scale is not 1f, leaving the default path exactly the density Robolectric configured.
      - The two relaunch tests (`HomeTimeFilterTest`, `HomeTimeRangeTest`) need a second
        view-model over the same database with nothing composed, so the harness also exposes
        `homeViewModel(perch, clock, settings, …)` — the one `HomeViewModel(` in testDebug.
      - Done: test count unchanged; `./gradlew test` green; issue #44 closed.
      - Rung: unit

- [x] **D12 — One image stub, one fake fetcher, one migration harness. Issue #45.**
      `gh issue view 45 --json body`. Three small consolidations, one session:
      1. Coil `Mapper<String, Drawable>` stubs — `ImageViewerTest.kt:240`,
         `WindowInsetsTest.kt:238`, `ArticleBodyTest.kt:262`, `EntryRowTest.kt:424`,
         `BrandScreenshotTest.kt:282`, `DesignScreenshotTest.kt:520`,
         `ImageViewerScreenshotTest.kt:191`, `LiveAcceptanceTest.kt:2417` — become one
         `stubImages(context, url → (w, h))` in `testDebug/.../ui/screenshot/`.
      2. `FakeFetcher : PageFetcher` — `ArchiveDiscoveryTest.kt:171`, `FeedDiscoveryTest.kt:172`,
         `BackfillRepositoryTest.kt:341`, `BackfillOfferTest.kt:457`, `BackfillWorkerTest.kt:117`
         — become one `MapPageFetcher(pages, default)` in `app/src/test/.../support/`, recording
         `requested` (the finalUrl-stamping variant is a constructor option; the always-null one
         is `PageFetcher { null }`, it is a `fun interface`).
      3. `openAtCurrentVersion()` and the four-line seed preamble in the six
         `PerchMigration{1To2…6To7}Test.kt` files move into `ExportedSchemas.kt`
         (`app/src/test/.../data/db/`), which is already the shared migration support.
      - Done: test count unchanged; `./gradlew test` green; issue #45 closed.
      - Rung: unit

- [x] **D13 — The concurrency test measures, it does not sleep. Issue #46.**
      `gh issue view 46 --json body`. `FeedRepositoryTest.kt:315` `Thread.sleep(120)` inside a
      `MockWebServer` `Dispatcher` is how `at most four feeds are in flight at once` holds eight
      requests open. Replace with a `CountDownLatch`/`CyclicBarrier` so peak concurrency is
      observed, not timed: the dispatcher counts arrivals, releases when the test says so, and
      the assertion reads the peak. **Timeouts on the latch, so a wrong answer fails, never
      hangs.**
      - Done: `grep -n 'Thread.sleep' app/src/test app/src/testDebug` shows only bounded
        poll loops (`Await.kt`, `ScreenshotSupport.kt`); `./gradlew test` green three times in a
        row for this test alone (`--tests '*FeedRepositoryTest*'`); issue #46 closed.
      - Rung: unit

- [x] **D14 — The untested controls get their tests. No production change. Issue #47.**
      `gh issue view 47 --json body`. Add tests, touch no `main` file:
      - `BackfillTestTags.OFFER_DECLINE` and `PROGRESS_DISMISS` (`BackfillOfferUi.kt:153,157`)
        in `BackfillOfferTest`; `HomeTestTags.REFRESH` (`HomeScreen.kt:1197`, the overflow item)
        in `HomeRefreshTest`.
      - `FeedRepository.refreshFolder(folderId)` in `FeedRepositoryTest` — only that folder's
        sources are pulled.
      - `ArticleViewModel.toggleSaved`, `toggleLiked`, `loadFullArticle` in a new JVM
        `ArticleViewModelTest` (`app/src/test/.../ui/article/`) — the least-covered interactive
        surface in the app, and the one D03 fixed.
      - `ConnectivityMonitor.system(context)` — Robolectric's `ShadowConnectivityManager` can
        drive it; if it cannot inside one attempt, say so and leave it.
      - Done: count above 1848 and every new test named; `./gradlew test` green; issue #47
        closed.
      - Rung: unit

- [x] **D15 — `hostRoot` once, `pathOf` once. Issue #48.**
      `gh issue view 48 --json body`. `ArchiveDiscovery.kt:27-32` and `FeedDiscovery.kt:83-88`
      are byte-identical; `RobotsRules.kt:20`, `ArchiveDiscovery.kt:175,191`, `LeadImage.kt:335`
      each write `runCatching { URI(url).path }.getOrNull()`. Both land in `FeedXml.kt` beside
      `hostOf` (§0.6). Pinned by `FeedDiscoveryTest`, `ArchiveDiscoveryTest`,
      `BackfillRepositoryTest`, `RobotsRulesTest`, `LeadImageTest` — name them in the commit.
      - Done: `grep -rn 'fun hostRoot\|URI(url).path' app/src/main` shows one declaration each;
        `./gradlew test` green; issue #48 closed.
      - Rung: unit

- [ ] **D16 — The page repositories share one parse, one mapping, one prose rule. Issue #49.**
      `gh issue view 49 --json body`. §0.6 decides where. Anchors: the parse at
      `ArticleTextRepository.kt:86-92`, `SavedLinkRepository.kt:111-112`,
      `BackfillRepository.kt:171-172`, `FeedDiscovery.kt:58-60`; the mapping at
      `SavedLinkRepository.kt:81-99` and `BackfillRepository.kt:130-148` (they differ only in
      the date rung and the saved flags — those are parameters); `isFullerThan` at
      `ArticleTextRepository.kt:83-84` vs `ArticleExtractor.proseLength` (`:101`).
      - After this task `grep -rn 'import org.jsoup' app/src/main/java/dev/mkiros/perch/data/repo`
        returns nothing.
      - **`PageContentExtractor` stays the one function** (NOTES.md); this task gives it a parse
        entry point, it does not clone it.
      - Pinned by `ArticleTextRepositoryTest`, `SavedLinkRepositoryTest`, `BackfillRepositoryTest`,
        `FeedDiscoveryTest`, `ArticleExtractorBlindSpotTest`.
      - Done: the jsoup grep empty; `./gradlew test` green; `wc -l` shows `data/repo` shrank;
        issue #49 closed.
      - Rung: unit

- [ ] **D17 — `robots.txt` once per backfill. Issue #50.**
      `gh issue view 50 --json body`. `ArchiveDiscovery.sitemapsFromRobots` (`:113-122`) and
      `RobotsRules.parse` (`RobotsRules.kt:27-44`) each fetch `$root/robots.txt`
      (`BackfillRepository.kt:164-166` is the second). §0.6: `RobotsRules` learns `sitemaps`,
      `ArchiveDiscovery.discover` takes the parsed rules, `BackfillRepository` fetches once.
      - `BackfillRepositoryTest`/`ArchiveDiscoveryTest` may assert the *list* of requested URLs
        via `FakeFetcher.requested`; one fewer `robots.txt` in that list is the point of the
        task, and the commit says so — that is not a weakened test.
      - Done: a test asserting exactly one `robots.txt` request per run; `./gradlew test`
        green; issue #50 closed.
      - Rung: unit

- [ ] **D18 — RSS and RDF map an item through one function. Issue #51.**
      `gh issue view 51 --json body`. `RssParser.kt:36-64` and `RdfParser.kt:242-272` differ
      only in the guid rung (`childText("guid")` vs `attrNamed("about")`) and the date-element
      names; `UNTITLED_FEED`/`UNTITLED_ENTRY` are at `RssParser.kt:66-69`, `AtomParser.kt:193-197`,
      `RdfParser.kt:274-277`. §0.6 says where the shared mapper goes.
      - **`FeedCorpusTest` is the standing contract** (CLAUDE.md). It, `RssParserTest`,
        `RdfParserTest`, `AtomParserTest` and `ExcerptOnlyTest` must pass **untouched**. If any
        snapshot expectation changes, the refactor changed behaviour: revert it.
      - No site-specific anything; the hostname grep in §0.2 stays empty.
      - Done: the five parser test classes green and unmodified (`git diff --stat` shows no test
        file); `./gradlew test` green; issue #51 closed.
      - Rung: unit

- [ ] **D19 — Both importers resolve folders the same way. Issue #52.**
      `gh issue view 52 --json body`. `ProfileRepository.FolderResolver` (`:201-215`) becomes
      top-level (§0.6); `OpmlRepository.import` (`:82-100`) uses it and drops its inline map and
      counter. The never-polled `FeedEntity` insert with five nulls (`OpmlRepository.kt:103-118`,
      `ProfileRepository.kt:269-288`) becomes one `FeedEntity.unpolled(url, title, folderId, …)`
      factory or a shared private function — the comment survives once.
      - Pinned by `OpmlRepositoryTest`, `ProfileRepositoryTest` — including the
        `foldersCreated` counts they assert.
      - Done: `./gradlew test` green; `wc -l` of the two repositories before/after in the
        commit; issue #52 closed.
      - Rung: unit

- [ ] **D20 — `normalizePastedUrl` lives in `data`. Issue #53.**
      `gh issue view 53 --json body`. `git mv` `ui/source/PastedUrl.kt` → `data/parse/PastedUrl.kt`
      and its test `test/.../ui/source/PastedUrlTest.kt` → `test/.../data/parse/`; fix the five
      imports (`SavedLinkRepository.kt:14`, `AddSourceViewModel.kt`, and whatever
      `grep -rln normalizePastedUrl app/src` lists). `FeedRepository.resolve:138` re-trims an
      already-normalised string — leave it; it is a defensive trim, not a second normaliser.
      - Done: `grep -rn 'import dev.mkiros.perch.ui' app/src/main/java/dev/mkiros/perch/data`
        no longer lists this one; `./gradlew test` green; issue #53 closed.
      - Rung: unit

- [ ] **D21 — The shared types leave `ui/` and `work/`. Issue #54.**
      `gh issue view 54 --json body`. §0.6 names the seven types and the package
      `dev.mkiros.perch.model`. Today: `SettingsStore.kt:12-14` imports `ui.home.TimeFilter`,
      `ui.theme.ThemeMode`, `work.RefreshInterval`; `WorkManagerBackfillRunner.kt:6-8` imports
      three `ui.home` types; `RefreshScheduler` is declared at `SettingsViewModel.kt:96`;
      `RefreshInterval` at `WorkScheduler.kt:19`; `BackfillRunner` at `ui/home/BackfillRunner.kt:21`.
      - **The trap:** files in `ui/home`, `ui/theme`, `ui/settings` and `work` use these types
        *without an import* today because they share the package. After the move, every one of
        them needs an import; let the compiler list them, do not hunt.
      - `SettingsStore` persists enums by `name`; a package move does not change that. Pin it:
        `SettingsStoreTest` (or the nearest) must read back a value stored before the change.
      - Done: `grep -rn 'import dev.mkiros.perch.ui' app/src/main/java/dev/mkiros/perch/data
        app/src/main/java/dev/mkiros/perch/work` returns nothing; `./gradlew test` green; issue
        #54 closed.
      - Rung: unit

- [ ] **D22 — `AppContainer` owns the WorkManager seams. Issue #55.**
      `gh issue view 55 --json body`. §0.6 decides the two fields and their defaults.
      `HomeViewModel.factory(container, context)` (`:754-764`, "mirrors `SettingsViewModel.factory`")
      constructs `WorkManagerBackfillRunner(context.applicationContext)` itself;
      `SettingsViewModel.factory(container, context)` (`:275-282`) reaches
      `WorkScheduler.setInterval(app, interval)`. `PerchApp.kt:56` already calls `WorkScheduler`;
      it wires both real seams into the container it builds (`AppContainer.kt:32,128`).
      Callers: `PerchNavHost.kt:236,309` drop `context`.
      - Tests construct `AppContainer(database, httpClient, clock)` in 22 places (D10 made that
        one rule); the defaults keep them compiling. `BackfillOfferTest`'s `FakeBackfillRunner`
        now goes in through the container.
      - Done: `grep -rn 'factory(container, context)' app/src` returns nothing; `./gradlew test`
        green; issue #55 closed.
      - Rung: unit

- [ ] **D23 — One paged entry list. Issue #56.**
      `gh issue view 56 --json body`. `HomeScreen.kt:1251-1288`, `CollectionScreen.kt:246-278`,
      `SearchSurface.kt:232-262` → `PagedEntryList(entries, listState, tag, onClick, onLongClick?,
      animate: Boolean, …)` in `ui/home/PagedList.kt` beside `pagedFooter` (§0.6). The three
      differ in tag, `animateItem()`, `onLongClick` and hoisted-vs-remembered `listState` — those
      are the parameters, nothing else.
      - Pinned by `PagedFeedTest`, `CollectionScreenTest`, `SearchSurfaceTest`, `EntryRowTest`,
        and the design screenshots (§0.3 pixel gate).
      - Done: `grep -rn 'pagedFooter(' app/src/main` shows one call site; `./gradlew test`
        green; issue #56 closed.
      - Rung: unit

- [ ] **D24 — One empty state. Issue #57.**
      `gh issue view 57 --json body`. `HomeScreen.kt:1420-1522`, `CollectionScreen.kt:289-346`,
      `SearchSurface.kt:271-305`. Grow `SearchSurface.Prompt(icon, title, body, tag)` into
      `EmptyState` in `ui/home/EmptyState.kt` (§0.6) with an icon slot (Home draws `PerchMark`)
      and an optional action slot (Home's button). **It stays a `LazyColumn` with one
      `fillParentMaxSize` item** — V03/NOTES.md: `PullToRefreshBox` ignores a swipe otherwise —
      and the KDoc says so, so the rule now lives in one place.
      - Pinned by the empty-state cases in `HomeScreenTest`, `CollectionScreenTest`,
        `SearchSurfaceTest` and the design screenshots.
      - Done: `grep -rn 'fillParentMaxSize' app/src/main` shows one site; `./gradlew test` green;
        issue #57 closed.
      - Rung: unit

- [ ] **D25 — One URL form. Issue #58.**
      `gh issue view 58 --json body`. `AddSourceSheet.kt:114-217` and `SaveLinkSheet.kt:96-168`
      share the column, title, `OutlinedTextField(Uri, Go)`, error line and spinner-or-label
      button; both containers share `LaunchedEffect(id) { onX(id); reset(); onDismiss() }`
      (`AddSourceSheet.kt:60-77`). Extract `UrlFormContent(...)` into `ui/source/UrlForm.kt`
      (§0.6); the Add Source sheet keeps its folder picker below it.
      - **S02/#33's `canDismiss` rule stays on the Save Link container only**
        (`SaveLinkSheet.kt:58-61` and its `confirmValueChange`). Do not generalise it onto Add
        Source; NOTES.md says why a sheet has two exits.
      - Pinned by `AddSourceSheetTest`, `SaveLinkSheetTest`, `SaveLinkViewModelTest`.
      - Done: `./gradlew test` green; `wc -l` of the two sheet files before/after; issue #58
        closed.
      - Rung: unit

- [ ] **D26 — The small `ui/home` composables exist once. Issue #59.**
      `gh issue view 59 --json body`. `ActionRow` (`EntryActions.kt:141-166`) and `DialogRow`
      (`FolderActions.kt:210-236`) → one; `CancelButton` (`SourceActions.kt:61-67`),
      `FolderCancelButton` (`FolderActions.kt:203-208`) and the inline `TextButton` at
      `SelectionBar.kt:198-205` → one `CancelButton(tag)`; the three `NavigationDrawerItem`
      blocks at `HomeScreen.kt:916-937` → `DrawerNavItem(icon, labelRes, onClick)`.
      - Test tags keep their **values** (`SourceActionTestTags.CANCEL`,
        `FolderActionTestTags.CANCEL`, `SelectionTestTags.DELETE_CANCEL`) — tests address them.
      - Pinned by `HomeEntryActionsTest`, `FolderDrawerTest`, `DrawerMultiSelectTest`,
        `HomeScreenTest`, and the design screenshots.
      - Done: `./gradlew test` green; issue #59 closed.
      - Rung: unit

- [ ] **D27 — The survey again, bounded: what is left becomes issues, not code.**
      Re-run the six surveys of this plan's preamble against the tree as it now is — duplicated
      logic (data and UI), dead code, latent bugs and untested paths, test debt, architecture
      and doc drift — using greps, not a full read. **Write no production code.** For each
      finding worth a session, open an issue (label it) and list it under a new
      "## Next plan" heading in `TECH_DEBT.md` with its anchor; update the file's existing
      entries where this plan changed the facts. If the survey finds nothing worth a session,
      say exactly that in the commit and in TECH_DEBT.md — that is the human's stop condition.
      - Bounded: one session, at most ten issues.
      - Done: `TECH_DEBT.md` committed; issues linked in the commit message; `./gradlew test`
        green (nothing changed, prove it anyway).
      - Rung: unit

- [ ] **D28 — The review pass. The whole of v0.6.1, read at once.**
      Read `git diff v0.6.0..HEAD` — **the whole of it** — and answer, in the commit message and
      in NOTES.md where it outlives the plan:
      1. **Did the codebase shrink?** `wc -l` of `app/src/main` and of the tests against §0.2's
         baseline (17,806 / 27,372). Name any task whose diff grew `main` and whether its commit
         said why.
      2. **Does any doc still describe v0.6.0?** README.md, SPEC.md, DESIGN.md, NOTES.md,
         CLAUDE.md, `docs/RALPH.md`, `TECH_DEBT.md`, against what actually shipped. CLAUDE.md
         will still name PLAN-10 as active — that is D30's edit, not this one's.
      3. **Did any task leave a helper, string, dimension or test tag orphaned?** Name the ones
         this plan scheduled to die and confirm they did, everywhere — including the old
         `seedFeed`/`showHome`/`FakeFetcher`/stub copies.
      4. **Was any test weakened rather than rewritten?** Name every changed assertion. D01 may
         have deleted two; D17 changed a request-list assertion deliberately; nothing else may
         have.
      5. **Is the suite above 1848, and did any bug fix (D03–D06) land without its RED shown
         in the commit?**
      6. **Behaviour unchanged?** For each refactor task, name the pinning tests its commit
         cited; if a commit cited none, run the survey's grep for that task and say what pins
         it now. The design screenshots must be byte-identical to `v0.6.0`'s for the same
         scenes — say how you checked.
      Fix what is small and mechanical **in this session**. Anything larger becomes an issue and
      a line in `TECH_DEBT.md` "## Next plan" — do not start a feature in a review.
      - Done: the six answered in the commit message, each with the command that settled it;
        `./gradlew test` green; any new issue linked.
      - Rung: unit

- [ ] **D29 — Live acceptance for v0.6.1.**
      The real corpus, the real network. This version adds **no gate**: the fifteen from S12
      must pass unchanged, which is the proof the refactors changed nothing a reader sees.
      - `JAVA_HOME=$HOME/.jdks/temurin-17 PATH=$JAVA_HOME/bin:$PATH ./gradlew
        :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`
      - **Gate 1 has no quota** (V12/#8); `quantpedia.com` stays excluded (NOTES.md);
        `research.checkpoint.com` answers 202 when runs come too close — wait ten quiet minutes,
        rerun, do not probe with `curl` first.
      - **Run it in the FOREGROUND and wait** — a headless session that backgrounds it commits
        nothing. ~90 s last time (S12), not the 15–25 min older plans budgeted.
      - **Bounded: at most three foreground runs.** Then exclude with a measurement, or BLOCKED.
      - Then `./gradlew test assembleRelease` — **not `clean`**.
      - Done: every gate's count pasted into the commit message; the default no-network
        `./gradlew test` still green.
      - Rung: maestro (live)

- [ ] **D30 — Release v0.6.1.** Bump `perchVersionCode` 7 → **8** and `perchVersionName`
      `0.6.0` → **`0.6.1`** at `app/build.gradle.kts:12-13`, **the one place they live**. §0.1
      settles the digit; it is not this task's judgement call.
      - `./gradlew assembleRelease` (runs `lintVitalRelease`). Signing from
        `~/.perch/signing.properties` (U02) — **absent it the build silently debug-signs**, so
        verify the certificate on the file, not the build log.
      - **Gradle writes `app-release.apk`; the rename to `perch-0.6.1.apk` is this task's own**
        (`output-metadata.json` still names the unrenamed file — W12).
      - Release notes through `docs/RELEASE-NOTES.md`'s template; `scripts/release-notes.sh
        v0.6.0` drafts from the issues closed since that tag — **most are refactors with no
        reader-visible change and do not belong on the page**; the four bugs (#36–#39) do, in
        the reader's words. "Installing / upgrading": the schema is unchanged, it installs in
        place and keeps everything — **verify it**: `./scripts/device.sh install` over the
        emulator's v0.6.0 (S13 left it seeded with `lemire.me`, one liked, one saved) and confirm
        To-Read and Liked still hold their rows through the UI.
      - Tag `v0.6.1`, push, `gh release create v0.6.1` with the notes and `perch-0.6.1.apk`.
        Then the turnover edits so the next session is not a loop session: CLAUDE.md's
        active-plan section says the plan is finished and there is no active plan; NOTES.md
        pruned under 100 lines with this version's floor and APK path.
      - Done: `gh release view v0.6.1 --json assets` lists the APK; `aapt2 dump badging` reads
        `versionCode='8' versionName='0.6.1'`; `apksigner verify --print-certs` prints U02's
        digest `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`; the
        in-place upgrade verified on the device; `git status` clean and pushed;
        `grep -c '^- \[ \]' PLAN-10.md` returns 0; `gh issue list --state open` holds only
        D27's issues for the next plan, each saying so.
      - Rung: build
