# NOTES.md

## Log
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
  `EXCLUDED_SOURCES` must pull (38/38 today), so a break arrives as a URL, and an exclusion carries the measurement
  that settled it. **SPEC §6's 8 MiB cap stays 8 MiB**: `danluu` (11.1 MB) and `projectzero` (13.2 MB) are out of
  scope, not evidence against it. **Not ours:** the LLVM feed omits spaces around inline `<code>`/`<a>`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — the cert (SHA-256 `61367c04…fce489`) *is* the update identity, cannot be rotated, is `chmod 600` outside
  the repo and is **not backed up**. Absent it, release silently debug-signs. `assembleRelease` runs `lintVitalRelease`.
- **U03/D10: a test's database and container come from `PerchRule`**; its `@get:Rule(order = 1)` is load-bearing —
  higher order = *inner*, so the close stays inside the Compose environment (else a leaked scope bills the next test).
  **U04: a fourth reader-owned flag needs two edits** — `EntryDao.upsertAll` (never Room `@Upsert`, it resolves on
  the primary key, ours on `(feedId, guid)`) and `deleteReadOlderThan`.
- 2026-08-18 — **W02/#15: the window is a *rolling* one** (24 h / 7 / 30 / 365 days back from `clock.instant()`),
  label **"Past 24 Hours"**, **defaults to Today** — a UI test seeding anything older pins `TimeFilter.AllTime` via its
  own `SettingsStore`. U07's calendar window is dead; the zone now only decides what a human *reads*. **W03: the Feed is
  one stream** — `HomeTestTags.section` and `startsSection` are gone; a test naming `"home:section:N"` spells the dead
  tag out on purpose, so nothing can put a header back unnoticed.
- 2026-08-08 — **U09: the bottom bar and `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch); back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`. **U09a:** the selection `BackHandler` must live *inside* `ModalDrawerSheet` — the root one wins otherwise.
- 2026-08-08 — **U10: `ArticleLowering` deletes truncation markers as chrome**, so `FullText` looks
  for "Continue reading" in the *unlowered* text; an extraction only ever replaces a body it beats.
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
- 2026-08-25 — **v0.5.0 shipped (PLAN-6/#23, PLAN-7/#21, PLAN-8), archived.** `PageContentExtractor`
  (fetch→extract→sanitize→image) is the **one** function `ArticleTextRepository`,
  `SavedLinkRepository` and `BackfillRepository` all call — do not clone it. Which queries filter
  `feeds.isSynthetic` (the seeded `perch:saved-links` row) is settled in **SPEC.md §4**; do not
  re-derive it. `ArchiveDiscovery`/`BackfillRepository` (`data/archive/`, `data/repo/`): discovery order
  RFC 5005 `prev-archive` → `robots.txt Sitemap:` → `/sitemap.xml`; post-vs-page is a dated URL
  path *or* a shape learned from the feed's own entry links, never a table of engines. `plan()`
  sorts by `lastmod` before `.take(MAX_PAGES=40)` because discovery order is sitemap *document*
  order (measured live on `fzakaria.com`, 143/133 discovered). `quantpedia.com` excluded from
  live gate 1 — its own TLS cert has expired, confirmed with `curl -v`, nothing Perch-side.
- 2026-09-07 — **S08/#28: the search index.** Shape, write path and query contract are in
  **SPEC.md §4/§8a**. What is only here: **`MIGRATION_6_7`'s `CREATE VIRTUAL TABLE` must be
  byte-for-byte what `7.json` exports** or Room fails validation on the *next* open, not on the
  migration — a test that only runs the migration will not catch it.
- 2026-09-07 — **v0.6.0 released** (`versionCode` 7, DB 7); test floor **1848** (1081 debug +
  767 release). APK `app/build/outputs/apk/release/perch-0.6.0.apk`. Its in-place upgrade was
  verified on the **emulator only** — the human's real phone is a separate device no session
  can reach. An upgrade check needs a seeded install and **there is no first-run seeder** (the
  Maestro flow's "a clean install seeds itself" comment is stale): add a source through the UI,
  and set the range to All Time or a fresh install looks empty. `adb shell input text` drops
  everything past ~15 characters — type a URL in short chunks and submit with `input keyevent
  66`; never tap a button at its dump bounds while the IME is up (the tap lands on a key, and
  uiautomator does not dump the IME window) — `keyevent 111` hides it first.
- 2026-09-08 — **D21: `SettingsStore` persists an enum by its bare `name`**, so moving one to
  `model/` is invisible to an installed reader; `SettingsStoreTest` pins that with a base64
  preferences file **captured from 8e9a5b8** — only regenerate it from a build that wrote it.
- 2026-09-08 — **Two full-suite-only flakes; both are green alone and on a re-run, so re-run before diagnosing.**
  `WorkSchedulerTest > choosing manual cancels…` (3 of 5 runs by D15) waits on WorkManager's *own* task executor,
  which `SynchronousExecutor` does not cover, so a loaded host outruns the 20 s `awaitInRealTime`.
  `SettingsViewModelTest` (D23, 1 of 2 runs) failed inside `Dispatchers.setMain`/`resetMain`. If either hardens, fix it.
- 2026-09-07 — **S12, live acceptance v6 (15 gates, ~90 s, not the 15–25 min the plan budgeted).**
  Gates 13/14/15 take their keywords **out of the corpus that just arrived** — see the file's own
  KDoc. A gate keyword must be ASCII-lettered and space-delimited or it tests typography, not
  search: FTS4's `simple` tokenizer keeps every byte above 0x7F *inside* a word, `FtsQuery` splits
  on it. Live run 1 caught what S09's unit tests could not — ` AND ` is an operator only under
  SQLite's *enhanced* FTS syntax, so two-word search demanded a word nobody typed (SPEC §8a).
- 2026-09-08 — **D28/D29a: the design screenshots are no committed baseline** — they render into gitignored
  `build/perch-screenshots`, which **keeps stale shots: `rm -rf` it and `--rerun`** or the diff shows phantom extras.
  The proof does it itself: `git worktree add /tmp/perch-<ref> <ref>`, copy `local.properties` in, `--tests
  '*ScreenshotTest*'` both sides, `md5sum` both dirs, `git worktree remove`. **35/35** twice, v0.6.0 and D29a.
