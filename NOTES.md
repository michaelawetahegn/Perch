# NOTES.md

## Log
- 2026-09-21 — **PLAN-13 run 1 stopped after G10; G02 (done)/G07/G09 re-opened as eight smaller boxes.** All three blocks were avoidable: each task's own §0 section already specified the algorithm, and the sessions improvised. The re-opened boxes carry the anchors below. Do not re-derive them.
- 2026-09-21 — **G12: §0.4 step 4 (the page-one thumbnail) had never been built**, and `DocumentStore.sweep` deleted every thumbnail (no row names a `-1.png`); both fixed in G12 with `DocumentStoreTest` — the sweep had no test at all before it. G13: the other §0.4 rungs worth checking the same way are step 2's URL-last-segment title and step 5's delete-the-previous-file.
- 2026-09-21 — **G09b: the share path never reached the sheet before G09b.** G09a proved each layer alone, but `PerchNavHost` never collected `container.intake`, so To-Read (the owner of `SaveLinkViewModel`) was never brought up. Only a whole-shell test (`SaveLinkSheetTest > a link shared to Perch…`) shows that; a per-layer green is not "the feature works".
- 2026-09-21 — **Loop hazard: the nightly emulator reboot can hang a run forever.** `loop.sh:137` calls `"$DEV" reboot` bare while `boot_emulator:123` wraps its call in `timeout`; `device.sh:154`'s `wait-for-device` has no timeout and waits for `device` state, so an `offline` emulator blocks before session #1 and the stall guard never fires. Clear it by killing the `device.sh reboot` subtree. Worth a `timeout` on both call sites.
- 2026-09-21 — **G07c: `./gradlew test` can hang forever, not fail** (seen in G07c and again in G11, debug variant, early in the run; a re-run passed both times). `PerchApp.onCreate`'s startup `sweepDocuments()` (G01) opens Room on `arch_disk_io` while `onTerminate` → `AppContainer.close()` → `RoomDatabase.close()` runs on the Robolectric main thread; `startupScope.cancel()` does not stop Room's own executor, and the two deadlock inside Room (`InvalidationTracker.syncTriggers` vs `FrameworkSQLiteOpenHelper.close`). Diagnose with `jstack` on the `Gradle Test Executor` pid; kill it and re-run. Filed in `TECH_DEBT.md` "## Next plan". Wrap long runs in `timeout 45m`.
- **Standing grep gates:** the two commands are in the active plan's §0.2. Behind the hostname one: parse by
  **standards** (OG, JSON-LD, Dublin Core, sitemaps.org, RFC 5005/9309), so one blog's support makes similar ones work
  — **a rule lifting one fixture and moving no other is aimed at a site.** **U01: the repo is public** (MIT), so a
  harvested fixture differs from the served page by at most a key rewritten `REDACTED-THIRD-PARTY-KEY`.
- **This `gh` is old:** bare `gh issue view N` dies on a Projects-classic GraphQL field (use `--json`), no `gh label list`, `gh issue close` has no `-r`. **V14: `scripts/release-notes.sh <last-tag>`** drafts a release page.
- 2026-08-07 — **Standing UI-test traps.** Compose UI tests live in **`app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer sheet, bottom sheet or
  dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `PullToRefreshBox` ignores a swipe unless its child
  scrolls — since V03 **every empty state is a `LazyColumn` with one `fillParentMaxSize` item**. Screenshots: go
  through `Screenshots` (its KDoc says why **never `captureToImage()`**).
  **W05/#16: `shareIntent(title, link)` is pure**, and a *chooser* — assert `EXTRA_INTENT`, not the outer action.
  **S02/#33: a `ModalBottomSheet` has two exits, not one** — the scrim reaches `onDismissRequest`, a swipe (and the
  settle after the IME collapses) does not: it hides the sheet through `SheetState` first. Refusing a dismissal means
  guarding **both** `onDismissRequest` and `rememberModalBottomSheetState(confirmValueChange = …)`; guarding one leaves
  an invisible sheet still composed. The rule itself lives on the state (`SaveLinkUiState.canDismiss`) so a VM test can
  assert it — the container is undrivable from a test.
  **S10/#28: a root `BackHandler` in `PerchNavHost` loses to `NavController`'s own callback** — the
  dispatcher is LIFO and the graph registers later, so on To-Read/Liked back pops the tab before the
  chain is consulted. Anything drawn *over* the NavHost (search) must answer back with its own
  handler, composed deeper; `BackChain` keeps modelling the rung as an order, not as what runs.
  **D29a: a `ModalBottomSheet` is its own window** — a `LocalDensity` provided around the screen reaches everything but the sheet, so a font-scale test of one sets the *environment* (`RuntimeEnvironment.setFontScale`) instead.
  **S03/#30: `HomeScope` is a bare id, so every delete path must widen it** —
  `HomeScreen.widenScopeIfRemoved` runs before `confirmRemoveSources`, `deleteFolders` and
  `deleteFolder`; a test that taps "All sources" first tests the workaround, not the bug.
- 2026-08-07 — **Live acceptance** (`acceptance/LiveAcceptanceTest`, `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **V12/#8: gate 1 has no quota** — every source in `feeds.txt` bar
  `EXCLUDED_SOURCES` must pull (39/39 today), so a break arrives as a URL, and an exclusion carries the measurement
  that settled it. **SPEC §6's 8 MiB cap stays 8 MiB**: `danluu` (11.1 MB) and `projectzero` (13.2 MB) are out of
  scope, not evidence against it. **Not ours:** the LLVM feed omits spaces around inline `<code>`/`<a>`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — the cert (SHA-256 `61367c04…fce489`) *is* the update identity, cannot be rotated, is `chmod 600` outside
  the repo and is **not backed up**. Absent it, release silently debug-signs. `assembleRelease` runs `lintVitalRelease`.
- **U03/D10: a test's database and container come from `PerchRule`**; its `@get:Rule(order = 1)` is load-bearing —
  higher order = *inner*, so the close stays inside the Compose environment (else a leaked scope bills the next test).
  **U04: another reader-owned column (four since E01's `scrollPosition`) needs two edits** — `EntryDao.upsertAll`
  (never Room `@Upsert`, it resolves on the primary key, ours on `(feedId, guid)` then `(feedId, link)` since F01 —
  a link match keeps the existing row's guid) and `deleteReadOlderThan`.
  **E01: `ArticleScreen`'s leaving write is `NonCancellable`** and outlives that close too — a test that shows the
  screen leaves it first (`ArticleScreenTest.leaveArticle`: drop the screen, idle, queue a no-op write behind it on
  Room's serial executor), or the *next* test fails with `UncaughtExceptionsBeforeTest`.
- 2026-08-18 — **W02/#15: the window is a *rolling* one** (24 h / 7 / 30 / 365 days back from `clock.instant()`),
  label **"Past 24 Hours"**, **defaults to Today** — a UI test seeding anything older pins `TimeFilter.AllTime` via its
  own `SettingsStore`. U07's calendar window is dead; the zone now only decides what a human *reads*. **W03: the Feed is
  one stream** — `HomeTestTags.section` and `startsSection` are gone; a test naming `"home:section:N"` spells the dead
  tag out on purpose, so nothing can put a header back unnoticed.
- 2026-08-08 — **U09: the bottom bar and `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch); back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`. **U09a:** the selection `BackHandler` must live *inside* `ModalDrawerSheet` — the root one wins otherwise.
- 2026-08-08 — **U14 (profile).** `pending_entry_state`, keyed `(feedUrl, guid)`, **no FK to `feeds`** — its job is
  outliving a source that does not exist yet. `EntryDao.upsertAll` consumes parked rows, so a restore's flag turns
  **on** and never off (idempotent). Codec is `org.json` — its tests need Robolectric.
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test** — a store cancels only a scope it *owns*. **Every full-suite flake so far: waiting on Room is not waiting on the screen. Poll in wall-clock time**, not `waitForIdle`.
- **V02/#9: a `Clock` carries a zone**; `AppContainer` injects `systemDefaultZone()`, **`DateParser` stays UTC
  deliberately**; a zone test pins `TimeZone.setDefault`. Since W02 the zone decides only what a human *reads*.
- **V06/#11: folder order is alphabetical, drawer only** (`FolderDao.observeAll`/`.getAll` must
  agree; `sortIndex` decides nothing — that KDoc has the NOCASE quirk). **V05/#12: "Unread" is gone
  from every reader-facing string** (identifiers keep it); **pin `HomeTestTags.TITLE`, never
  `onNodeWithText("Feed")`**, and a drawer row is `hasClickAction() and !hasTestTag(ENTRY)`.
- **V08/#10: the scoped list is state, not a route.** `HomeScope` is **hoisted into `PerchNavHost`** — third such
  state — `BackStep.LeaveScope` a rung above `ScrollFeedToTop`. `selectTab` is a silent no-op from the article route (`popUpTo(start){saveState}`/`restoreState`, pop first); scoping does not touch the time window.
- 2026-08-25 — **v0.5.0 (PLAN-6/#23, PLAN-7/#21, PLAN-8), archived.** `PageContentExtractor`
  (fetch→extract→sanitize→image) stays the **one** path `ArticleTextRepository`, `SavedLinkRepository`
  and `BackfillRepository` call — since D16 they share its parse and mapping too; do not clone it.
  Which queries filter `feeds.isSynthetic` (the seeded `perch:saved-links` row): **SPEC.md §4**.
  `ArchiveDiscovery`/`BackfillRepository` discovery order: RFC 5005 `prev-archive` → `robots.txt
  Sitemap:` → `/sitemap.xml`; post-vs-page is a dated URL path *or* a shape learned from the feed's
  own entry links, never a table of engines. Since F07 what discovery finds lives in `archive_posts`; `plan()`
  reads the next `MAX_PAGES=40` unfetched rows newest-first and rediscovers only after 7 days (`fzakaria.com`, 141/151 live).
  `quantpedia.com` excluded from live gate 1 — its TLS cert has expired, nothing Perch-side.
- 2026-09-07 — **S08/#28: the search index.** Shape, write path and query contract are in
  **SPEC.md §4/§8a**. What is only here: **`MIGRATION_6_7`'s `CREATE VIRTUAL TABLE` must be
  byte-for-byte what `7.json` exports** or Room fails validation on the *next* open, not on the
  migration — a test that only runs the migration will not catch it.
- 2026-09-14 — **v0.8.0 released** (`versionCode` 10, DB 8 → 10 via `MIGRATION_8_9` + `MIGRATION_9_10`); test
  floor **2067** (1200 debug + 867 release). APK `app/build/outputs/apk/release/perch-0.8.0.apk`. In-place
  upgrades are verified on the **emulator only** (it holds v0.8.0 now); the human's real phone no session can reach. An
  upgrade check needs a seeded install and **there is no first-run seeder** (the Maestro flow's "a
  clean install seeds itself" comment is stale): add a source through the UI, and set the range to
  All Time or a fresh install looks empty. `adb shell input text` drops everything past ~15
  characters — type a URL in short chunks, submit with `input keyevent 66`; never tap a button at
  its dump bounds while the IME is up (uiautomator does not dump it) — `keyevent 111` hides it.
- 2026-09-21 — **G02b: count both variants** of `./gradlew test` (run with `--continue`); a debug-only green is not the Done-condition. First-run-only flakes that pass alone: `SaveLinkSheetTest > pasting a feed address…`, `PagedFeedTest > a read-state change…`, `PerchNavHostTest > the bottom bar is on every list destination…`.
- 2026-09-08 — **Two full-suite-only flakes; green alone and on a re-run, so re-run before diagnosing.** `WorkSchedulerTest > choosing manual cancels…` (3 of 5 by D15) waits on WorkManager's own executor, which
  `SynchronousExecutor` misses; `SettingsViewModelTest` (D23) failed inside `Dispatchers.setMain`/`resetMain`.
- 2026-09-08 — **D28/D29a: screenshots are no committed baseline** — gitignored `build/perch-screenshots` keeps
  stale shots: **`rm -rf` it and `--rerun`** first. Untouched-pixels proof: `git worktree add /tmp/perch-<ref>
  <ref>`, copy `local.properties` in, `--tests '*ScreenshotTest*'` both sides, `md5sum` both. **35/35** twice.
- 2026-09-14 — **F04/F15: live gate 7 was the harness, not the app.** The article body is a plain vertical scroll, so
  a 44-image body composes every figure and the first sits below the fold; an injected tap at its centre landed outside
  the viewport. `performScrollTo()` before the click (F15) — gate 7 prints again, 0 failures, `39/39` on gate 1.
  `showArticle` also waits for the full-text fetch to come *and go* (§0.7).
