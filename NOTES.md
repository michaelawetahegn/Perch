# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after `wsl --shutdown`** — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze.

## Log
- **Standing grep gate:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones.
  **U01: the repo is public** (MIT) — never un-redact the `apiKey` in `fixtures/homepages/`.
- 2026-08-07 — **Standing UI-test traps.** Compose UI tests live in **`app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer sheet, bottom sheet or
  dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `PullToRefreshBox` ignores a swipe unless its child
  scrolls — since V03 **every empty state is a `LazyColumn` with one `fillParentMaxSize` item**. Screenshots: go
  through `Screenshots` (its KDoc says why **never `captureToImage()`** — CLAUDE.md is wrong).
- 2026-08-07 — **Live acceptance** (`acceptance/LiveAcceptanceTest`, `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 sits on the 38/42 floor** — `danluu.com`/`projectzero.google`
  bust SPEC §6's 8 MiB cap, `research.nccgroup.com` has no feed, `rachelbythebay.com` times out (issue #8/V12).
  **Not ours:** the LLVM feed omits spaces around inline `<code>`/`<a>` — do not "repair" it.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — the cert (SHA-256 `61367c04…fce489`) *is* the update identity and cannot be rotated. Both `chmod 600`,
  outside the repo, **not backed up yet**; absent the key, release falls back to debug signing silently. Version
  lives only atop `app/build.gradle.kts`; **`assembleRelease` runs `lintVitalRelease`.**
- 2026-08-07 — **U03: build test databases with `PerchDatabase.inMemory(context)`** — only it seeds Uncategorized,
  whose FK the first feed needs.
- 2026-08-07 — **U04: adding a fourth reader-owned flag beside `isRead`/`isSaved`/`isStarred` needs two edits** —
  `EntryDao.upsertAll` (never Room `@Upsert`: it resolves on the primary key, ours on `(feedId, guid)`) and
  `deleteReadOlderThan`, both of which otherwise erase it.
- 2026-08-07 — **U07: the window is a *calendar* one** (local midnight) and **defaults to Today** — a UI test seeding
  anything older must pin `TimeFilter.AllTime` via its own `SettingsStore` or it asserts against an empty screen.
  Address section headers by `HomeTestTags.section(id)`, never by text (the drawer composes while closed).
- 2026-08-07 — **U08: the row's 96dp thumbnail square is always reserved** (absent/loading/failed draw one
  placeholder). Coil offline: a `Mapper` succeeds, an `Interceptor` returning `ErrorResult` fails, one that
  `awaitCancellation()`s stays loading; a list screenshot needs `stubThumbnails()`.
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch). §0's back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`, the enum's order *being* the priority; **`EntryRow` owns its own
  `combinedClickable`** — an inner `clickable` eats the pointer stream.
- 2026-08-08 — **U09a: the selection `BackHandler` must live inside `ModalDrawerSheet`** — the root one registers
  first and loses. A batch delete's dialog is **a coroutine behind its tap**, so wait in wall-clock time.
- 2026-08-08 — **U07a: all three lists are Paging 3**, **placeholders off** so `startsSection` is answerable at a
  page edge. The three list queries live once in **`EntryQueries`** because each exists twice — `Flow<List>` *and*
  `PagingSource`. **`uiState.entries` is gone**: ask the screen; `performScrollToIndex` past loaded rows throws.
- 2026-08-08 — **U10:** Readability-over-jsoup in `data/extract/`, **no new dependency**. Three traps.
  (1) **`ArticleLowering` deletes truncation markers as chrome**, so `FullText` looks for "Continue reading" in the
  *unlowered* text. (2) Scoring finds the *tightest* subtree, so a decorative single-child wrapper wins and the
  article's last section, its sibling, is lost — hence `unwrapped()`. (3) **An extraction only ever replaces a body
  it beats.** Fixtures: `fixtures/articles/`.
- 2026-08-08 — **U11 (code).** Bundled JetBrains Mono 2.304 (OFL 1.1, licence in `assets/`) is DESIGN.md §3's one
  exception, **ligatures off**. **`HtmlSanitizer` keeps `class` on `pre` only**, holding a `language-x` normalised
  before `Cleaner` runs. The gutter sits **outside** the `horizontalScroll`, inside a **`DisableSelection`**.
- 2026-08-08 — **U12: the viewer is an overlay, not a destination** — a sibling of the article's `Scaffold` in one
  `Box`, so the reading position survives; `ZoomedImage` is hoisted to `PerchNavHost` because
  **`BackStep.CloseImageViewer` sits between `CloseOverlay` and `PopArticle`**, an order `BackChainTest` guards.
  An open overlay eats `performTouchInput` — scroll under it first; `performClick` needs `mainClock.advanceTimeBy`.
- 2026-08-08 — **U13 (OPML folders).** A folder is a **name, not an id** — ids do not survive the file (U14 inherits
  this). Import files a source under the **outermost** container and leaves a duplicate alone.
- 2026-08-08 — **U14 (profile).** DB is **version 5**: `pending_entry_state`, keyed `(feedUrl, guid)`, **no FK to
  `feeds`** — its job is outliving a source that does not exist yet. **`EntryDao.upsertAll` consumes parked rows**,
  which is what stops the refresh straight after a restore undoing it. A restore turns a flag **on** and never off,
  so it is idempotent. Codec is `org.json` — **its tests need Robolectric**; on a bare JVM `JSONObject` stubs.
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test** — a store cancels only a scope it *owns*.
- 2026-08-09 — **Every full-suite flake so far: waiting on Room is not waiting on the screen** — asserting rendered
  text the moment the DB has it loses the emit→recompose hop. **Poll in wall-clock time**, not `waitForIdle`.
- 2026-08-09 — **V02/#9: a `Clock` carries a zone, and the container's was Greenwich's.** `AppContainer` now injects
  `systemDefaultZone()`; **`DateParser` stays UTC deliberately.** A zone test must pin `TimeZone.setDefault` —
  inheriting the JVM's cannot tell UTC-the-bug from UTC-the-agent.
- 2026-08-09 — **V06/#11: folder order is alphabetical (`COLLATE NOCASE`), Uncategorized pinned by `(id = 1) ASC`.**
  The clause is stated **three** times and they must agree — `FolderDao.observeAll`, `FolderDao.getAll`,
  `EntryQueries.LIST_ITEMS` (the drawer and the section headers read *different* ones). `sortIndex` stays a column
  (OPML/profile round-trips) but decides nothing. **`COLLATE NOCASE` folds ASCII only**: `Émacs` sorts last by UTF-8
  byte — pinned by a test and accepted, not a bug to "fix" without an ICU collation.
- 2026-08-09 — **V07/#13: a missing thumbnail is `surfaceVariant` + the mark as line art in `outline`**
  (`Placeholder(marked = true)`); **only `loading` keeps the bare frame**. `ColorFilter.tint` flattens the mark to a
  silhouette — `perchMarkMonochrome(ink, paper)` rebuilds it. `Screenshots.rasterize` reads pixels, writes no file.
- 2026-08-10 — **V10/#5: a refused row is `refusesFolder` = "a tick would change nothing"**, so the affordance cannot
  drift from `toggleFolder`. **`combinedClickable(enabled = false)` keeps its `OnClick` semantics action** — only
  `Disabled` is added, so `performSemanticsAction` still fires it: assert `assertIsNotEnabled`, never a missing action.
  The header dims (name + badge, `UNAVAILABLE_ALPHA`) while its chevron keeps full contrast — it is still live.
- 2026-08-08 — **U16: v0.2.0 shipped** (versionCode 3, `app/build/outputs/apk/release/app-release.apk`).
- 2026-08-09 — **V04/#3: the inset contract is one doc comment in `ui/nav/PerchNavHost.kt`** — four clauses, one
  test each in `WindowInsetsTest`. Never add `.statusBarsPadding()` to a screen. **Robolectric has no bars or cutout
  on any profile**, so a test dispatches its own — `ui/WindowInsetsSupport.kt`'s `applyWindowInsets` reaches **every
  Compose root** (`WindowInsetsHolder` listens on the `AndroidComposeView`). The bottom bar and the `NavHost` are
  siblings and both spent the bottom inset; the shell now `consumeWindowInsets` while a tab is showing.
- 2026-08-09 — **V05/#12: "Unread" is gone from every string a reader sees**; identifiers keep the word on purpose.
  **Pin `HomeTestTags.TITLE`, never `onNodeWithText("Feed")`** — the bottom-bar tab has read "Feed" since U09.
- 2026-08-09 — **V08/#10: the scoped list is state, not a route.** `HomeScope` is **hoisted into `PerchNavHost`** —
  third such state — because `BackStep.LeaveScope` is a rung, *above* `ScrollFeedToTop`, and the drawer is no longer
  its only writer; `HomeScreen` pushes it down through one `LaunchedEffect` and nothing else writes `scope`.
  **`selectTab` is a silent no-op from the article route**: `popUpTo(start){saveState}` saves the article and
  `restoreState` puts it straight back — pop first, switch tabs only if needed. The byline is segments now
  (`ArticleTestTags.SOURCE` / `BYLINE`), the source carrying §8's editorial underline, never a colour. Scoping
  **does not touch the time window** — a navigation must not rewrite a persisted setting, and U07's empty-window
  state already names it. Residual: a scoped list still repeats the source's name on every row — polish, T29.
- 2026-08-10 — **V09/#4: a table joins the article on its *shape*, not its text density** — `carriesContentTable`
  (≥3 rows, ≥2 columns, no nested table, not linky), and `carriesSubstantialProse` now counts a `<p>` **wrapped** in
  a block div: Squarespace gives every block its own `sqs-block`, so the table and the closing paragraph were both
  dropped siblings. A page fixture is not a feed body — the page is `zdi-page-*.html`. **Live gate 7 now fails alone**:
  home shows 1 folder section of 3 (V06 changed which sections page 1 holds) — V15's, gate 6b green at 122 tables.
