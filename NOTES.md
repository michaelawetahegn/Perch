# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after `wsl --shutdown`** — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze.

## Log
- **Standing grep gate:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones.
  **U01: the repo is public** (MIT) — never un-redact the `apiKey` in `fixtures/homepages/`.
- **This `gh` is old:** bare `gh issue view N` dies on a Projects-classic GraphQL field (use `--json`), there is
  no `gh label list`, `gh issue close` has no `-r`. **V14: `scripts/release-notes.sh <last-tag>`** drafts a release
  page from the issues closed since it; `docs/RELEASE-NOTES.md` is the template V16 writes v0.3.0's through.
- 2026-08-07 — **Standing UI-test traps.** Compose UI tests live in **`app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer sheet, bottom sheet or
  dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `PullToRefreshBox` ignores a swipe unless its child
  scrolls — since V03 **every empty state is a `LazyColumn` with one `fillParentMaxSize` item**. Screenshots: go
  through `Screenshots` (its KDoc says why **never `captureToImage()`** — CLAUDE.md is wrong).
- 2026-08-07 — **Live acceptance** (`acceptance/LiveAcceptanceTest`, `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **V12/#8: gate 1 has no quota** — every source in `feeds.txt` bar
  `EXCLUDED_SOURCES` must pull (38/38 today), so a break arrives as a URL, and an exclusion carries the measurement
  that settled it. **SPEC §6's 8 MiB cap stays 8 MiB**: `danluu` (11.1 MB) and `projectzero` (13.2 MB) are out of
  scope, not evidence against it. **Not ours:** the LLVM feed omits spaces around inline `<code>`/`<a>`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — the cert (SHA-256 `61367c04…fce489`) *is* the update identity and cannot be rotated. Both `chmod 600`,
  outside the repo, **not backed up yet**; absent the key, release falls back to debug signing silently. Version
  lives only atop `app/build.gradle.kts`; **`assembleRelease` runs `lintVitalRelease`.**
- 2026-08-07 — **U03: build test databases with `PerchDatabase.inMemory(context)`** — only it seeds Uncategorized.
- 2026-08-07 — **U04: adding a fourth reader-owned flag beside `isRead`/`isSaved`/`isStarred` needs two edits** —
  `EntryDao.upsertAll` (never Room `@Upsert`: it resolves on the primary key, ours on `(feedId, guid)`) and
  `deleteReadOlderThan`, both of which otherwise erase it.
- 2026-08-07 — **U07: the window is a *calendar* one** (local midnight), **defaults to Today** — a UI test seeding
  anything older pins `TimeFilter.AllTime` via its own `SettingsStore`, and addresses section headers by
  `HomeTestTags.section(id)`, never by text (the drawer composes while closed).
- 2026-08-07 — **U08: the row's 96dp thumbnail square is always reserved.** Coil offline: a `Mapper` succeeds, an
  `Interceptor` returning `ErrorResult` fails, one that `awaitCancellation()`s stays loading; `stubThumbnails()` for list shots.
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch). §0's back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`, the enum's order *being* the priority.
- 2026-08-08 — **U09a: the selection `BackHandler` must live inside `ModalDrawerSheet`** — the root one wins
  otherwise; a batch delete's dialog is **a coroutine behind its tap**, so wait in wall-clock time.
- 2026-08-08 — **U07a: all three lists are Paging 3**, **placeholders off** so `startsSection` is answerable at a
  page edge. The three list queries live once in **`EntryQueries`** because each exists twice — `Flow<List>` *and*
  `PagingSource`. **`uiState.entries` is gone**: ask the screen; `performScrollToIndex` past loaded rows throws.
- 2026-08-08 — **U10:** Readability-over-jsoup in `data/extract/`, **no new dependency**; fixtures in
  `fixtures/articles/`. **`ArticleLowering` deletes truncation markers as chrome**, so `FullText` looks for
  "Continue reading" in the *unlowered* text; **an extraction only ever replaces a body it beats.**
- 2026-08-08 — **U12: the viewer is an overlay, not a destination** — a sibling of the article's `Scaffold` in one
  `Box`, so the reading position survives; `ZoomedImage` is hoisted to `PerchNavHost` because
  **`BackStep.CloseImageViewer` sits between `CloseOverlay` and `PopArticle`**, an order `BackChainTest` guards.
  An open overlay eats `performTouchInput` — scroll under it first; `performClick` needs `mainClock.advanceTimeBy`.
- 2026-08-08 — **U13 (OPML folders).** A folder is a **name, not an id** — ids do not survive the file (U14 inherits
  this). Import files a source under the **outermost** container and leaves a duplicate alone.
- 2026-08-08 — **U14 (profile).** DB is **version 5**: `pending_entry_state`, keyed `(feedUrl, guid)`, **no FK to
  `feeds`** — its job is outliving a source that does not exist yet. **`EntryDao.upsertAll` consumes parked rows**,
  so the refresh after a restore cannot undo it; a restore turns a flag **on** and never off, so it is idempotent.
  Codec is `org.json` — **its tests need Robolectric**; on a bare JVM `JSONObject` stubs.
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test** — a store cancels only a scope it *owns*.
  **Every full-suite flake so far: waiting on Room is not waiting on the screen** — asserting rendered text the
  moment the DB has it loses the emit→recompose hop. **Poll in wall-clock time**, not `waitForIdle`.
- **V02/#9: a `Clock` carries a zone**; `AppContainer` injects `systemDefaultZone()`, **`DateParser` stays UTC
  deliberately**. A zone test pins `TimeZone.setDefault` — the JVM's cannot tell bug from agent.
- **V06/#11: folder order is alphabetical (`COLLATE NOCASE`), Uncategorized pinned by `(id = 1) ASC`.** Stated
  **three** times and they must agree — `FolderDao.observeAll`, `.getAll`, `EntryQueries.LIST_ITEMS` (drawer and
  section headers read *different* ones). `sortIndex` stays a column but decides nothing. **`COLLATE NOCASE` folds
  ASCII only**: `Émacs` sorts last by UTF-8 byte — pinned by a test, not a bug to "fix".
- **V07/#13: a missing thumbnail is `surfaceVariant` + the mark as line art in `outline`** (`Placeholder(marked =
  true)`), **only `loading` keeps the bare frame**; `ColorFilter.tint` would flatten the mark to a silhouette, so
  `perchMarkMonochrome(ink, paper)`. `Screenshots.rasterize` reads pixels, writes no file.
- **V10/#5: a refused row is `refusesFolder` = "a tick would change nothing"**, so the affordance cannot drift
  from `toggleFolder`; the header dims while its chevron keeps full contrast. **`combinedClickable(enabled =
  false)` keeps its `OnClick` semantics action** — assert `assertIsNotEnabled`, never a missing action.
- **U16: v0.2.0 shipped** (versionCode 3, `app/build/outputs/apk/release/`); **V13/#2: the v0.1.0 bridge is
  executed and attached** (`perch-0.2.0-debug.apk`). On device, **`run-as` dies on a release build** — verify
  through the UI, not sqlite3.
- 2026-08-09 — **V04/#3: the inset contract is one doc comment in `ui/nav/PerchNavHost.kt`** — four clauses, one
  test each in `WindowInsetsTest`. Never add `.statusBarsPadding()` to a screen. **Robolectric has no bars or cutout
  on any profile**, so a test dispatches its own — `ui/WindowInsetsSupport.kt`'s `applyWindowInsets` reaches every
  Compose root.
- **V05/#12: "Unread" is gone from every reader-facing string** (identifiers keep it); **pin `HomeTestTags.TITLE`,
  never `onNodeWithText("Feed")`** — the bottom-bar tab has read "Feed" since U09.
- 2026-08-09 — **V08/#10: the scoped list is state, not a route.** `HomeScope` is **hoisted into `PerchNavHost`** —
  third such state — `BackStep.LeaveScope` is a rung above `ScrollFeedToTop` and the drawer is no longer its
  only writer. **`selectTab` is a silent no-op from the article route**: `popUpTo(start){saveState}` saves the
  article and `restoreState` puts it back — pop first, switch tabs only if needed. Scoping **does not touch the
  time window**. Residual: a scoped list still repeats the source's name on every row — polish, T29.
- 2026-08-10 — **V09/#4: a table joins the article on its *shape*, not its text density** — `carriesContentTable`
  (≥3 rows, ≥2 columns, no nested table, not linky). A page fixture is not a feed body: `zdi-page-*.html`.
- **V11/#7.** Anything spanning a scrolling child — the gutter's rule, a table's edge fade — measures 0 against
  the article's unbounded height: the rule needs the Row at **`height(IntrinsicSize.Min)`**, the fade is a
  draw-only `matchParentSize` **sibling** of the scroll (inside it it lands off-screen at the content's far end).
- 2026-08-10 — **V15: the live gate is twelve gates.** V02's day boundary is **gate 8** (`America/Chicago`, 23:30
  on the entry's own day — 636 of 1037 live entries would have been dropped by a UTC clock), V06's order is **gate
  9**, and gate 6b fetches V09's ZDI *page* by name because its feed ships full bodies. **Stage a screenshot in
  the reader's order, never `sortIndex`** — that alone was gate 7's "1 folder section of 3". Gate 5b's floor is
  U15's 75.4% less 10 points: the sampled set is ~70 entries, so one entry is 1.4 of them.
  **`research.checkpoint.com` answers 202 with an empty body once live runs come too close together** (Cloudflare;
  plain `curl` sees it too, and **a `curl` probe spends the allowance the next run needs**) — wait ~10 quiet
  minutes and rerun without probing first. It is a healthy source, not an exclusion.
