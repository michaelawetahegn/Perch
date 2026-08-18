# NOTES.md

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM; no physical device, WHPX **enabled**. **The
`.wslconfig` 7 GB cap only applies after `wsl --shutdown`** — MemTotal ~6.9 GB means live, ~9.9 GB means a freeze.

## Log
- **Standing grep gate:** no `Color(0x` / `N.dp` / `N.sp` outside `ui/theme/` — screens address roles, never tones.
  **U01: the repo is public** (MIT) — a harvested fixture may differ from the served page by exactly one thing,
  a third-party key rewritten `REDACTED-THIRD-PARTY-KEY` (`fixtures/homepages/`, the huggingface article page).
- **This `gh` is old:** bare `gh issue view N` dies on a Projects-classic GraphQL field (use `--json`), no
  `gh label list`, `gh issue close` has no `-r`. **V14: `scripts/release-notes.sh <last-tag>`** drafts a release page
  from the issues closed since it, through the `docs/RELEASE-NOTES.md` template.
- 2026-08-07 — **Standing UI-test traps.** Compose UI tests live in **`app/src/testDebug/`** (`ui-test-manifest` is
  `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer sheet, bottom sheet or
  dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `PullToRefreshBox` ignores a swipe unless its child
  scrolls — since V03 **every empty state is a `LazyColumn` with one `fillParentMaxSize` item**. Screenshots: go
  through `Screenshots` (its KDoc says why **never `captureToImage()`** — CLAUDE.md is wrong).
  **W05/#16: `shareIntent(title, link)` is pure**, and a *chooser* — assert `EXTRA_INTENT`, not the outer action.
- 2026-08-07 — **Live acceptance** (`acceptance/LiveAcceptanceTest`, `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **V12/#8: gate 1 has no quota** — every source in `feeds.txt` bar
  `EXCLUDED_SOURCES` must pull (38/38 today), so a break arrives as a URL, and an exclusion carries the measurement
  that settled it. **SPEC §6's 8 MiB cap stays 8 MiB**: `danluu` (11.1 MB) and `projectzero` (13.2 MB) are out of
  scope, not evidence against it. **Not ours:** the LLVM feed omits spaces around inline `<code>`/`<a>`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — the cert (SHA-256 `61367c04…fce489`) *is* the update identity and cannot be rotated. Both `chmod 600`,
  outside the repo, **not backed up yet**; absent it, release silently debug-signs. `assembleRelease` runs `lintVitalRelease`.
- 2026-08-07 — **U03: test databases come from `PerchDatabase.inMemory(context)`** (only it seeds Uncategorized).
  **U04: a fourth reader-owned flag needs two edits** — `EntryDao.upsertAll` (never Room `@Upsert`: it resolves on the
  primary key, ours on `(feedId, guid)`) and `deleteReadOlderThan`.
- 2026-08-18 — **W07/#17: a page that extracts to null is read again with every `class`/`id` erased** — structure
  alone decides, and a page that already worked never reaches it (the defect was `namesChrome()` matching an
  unanchored substring: Tailwind's `max-lg:overflow-hidden` read as `hidden`). Corpus cost **zero**, HF 0 → 9355. A
  page that fails goes in `ArticleFixtures.pending`, never `all`, held to still failing by the blind-spot test (W10).
- 2026-08-18 — **W02/#15: the window is a *rolling* one** (24 h / 7 / 30 / 365 days back from `clock.instant()`),
  label **"Past 24 Hours"**, **defaults to Today** — a UI test seeding anything older pins `TimeFilter.AllTime` via its
  own `SettingsStore`. U07's calendar window is dead; the zone now only decides what a human *reads*. **W03: the Feed is
  one stream** — `HomeTestTags.section` and `startsSection` are gone; a test naming `"home:section:N"` spells the dead
  tag out on purpose, so nothing can put a header back unnoticed.
- **U08: the row's 96dp thumbnail square is always reserved.** Coil offline: a `Mapper` succeeds, an `Interceptor`
  returning `ErrorResult` fails, one that `awaitCancellation()`s stays loading; `stubThumbnails()` for list shots.
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**; **Feed's `DrawerState`/`LazyListState` are
  hoisted into `PerchNavHost`** (state remembered inside Feed dies on a tab switch). §0's back policy is the pure
  `nextBackStep(BackState)` in `BackChain.kt`, the enum's order *being* the priority.
- 2026-08-08 — **U09a: the selection `BackHandler` must live inside `ModalDrawerSheet`** — the root one wins
  otherwise; a batch delete's dialog is **a coroutine behind its tap**, so wait in wall-clock time.
- 2026-08-08 — **U07a: all three lists are Paging 3**, **placeholders off**; the queries live once in **`EntryQueries`**
  because each exists twice — `Flow<List>` *and* `PagingSource`. **`uiState.entries` is gone**: ask the screen.
- 2026-08-08 — **U10:** Readability-over-jsoup in `data/extract/`, **no new dependency**; fixtures in
  `fixtures/articles/`. **`ArticleLowering` deletes truncation markers as chrome**, so `FullText` looks for
  "Continue reading" in the *unlowered* text; **an extraction only ever replaces a body it beats.**
- **U12: the viewer is an overlay, not a destination** — a sibling of the article's `Scaffold` in one `Box`, so the
  reading position survives; `ZoomedImage` is hoisted to `PerchNavHost`. An open overlay eats `performTouchInput`.
- 2026-08-08 — **U14 (profile).** DB is **version 5**: `pending_entry_state`, keyed `(feedUrl, guid)`, **no FK to
  `feeds`** — its job is outliving a source that does not exist yet. **`EntryDao.upsertAll` consumes parked rows**,
  so the refresh after a restore cannot undo it; a restore turns a flag **on** and never off, so it is idempotent.
  Codec is `org.json` — **its tests need Robolectric**; on a bare JVM `JSONObject` stubs.
- 2026-08-09 — **V01/#1: Robolectric builds `PerchApp` for every test** — a store cancels only a scope it *owns*.
  **Every full-suite flake so far: waiting on Room is not waiting on the screen. Poll in wall-clock time**, not `waitForIdle`.
- **V02/#9: a `Clock` carries a zone**; `AppContainer` injects `systemDefaultZone()`, **`DateParser` stays UTC
  deliberately**; a zone test pins `TimeZone.setDefault`. Since W02 the zone decides only what a human *reads*.
- **V06/#11: folder order is alphabetical (`COLLATE NOCASE`), Uncategorized pinned by `(id = 1) ASC`.** It governs
  the **drawer only** since W03 — `FolderDao.observeAll` and `.getAll`, which must agree; `EntryQueries.LIST_ITEMS`
  left the rule and is pure recency. `sortIndex` stays a column but decides nothing. **`COLLATE NOCASE` folds
  ASCII only**: `Émacs` sorts last by UTF-8 byte — pinned by a test, not a bug to "fix".
- **V07/#13: a missing thumbnail is `surfaceVariant` + the mark in `outline`** (`Placeholder(marked = true)`), **only
  `loading` keeps the bare frame**; `ColorFilter.tint` flattens the mark, so `perchMarkMonochrome(ink, paper)`.
- **V10/#5: a refused row is `refusesFolder` = "a tick would change nothing"**, so the affordance cannot drift from
  `toggleFolder`. **`combinedClickable(enabled = false)` keeps its `OnClick` action** — assert `assertIsNotEnabled`.
- **V16: v0.3.0 shipped** — versionCode 4, `app/build/outputs/apk/release/perch-0.3.0.apk`, U02-signed. On device
  **`run-as` dies on a release build** — verify through the UI, not sqlite3.
- 2026-08-18 — **W11: the live gate is thirteen gates** — **5c** is #17's Hugging Face page fetched *live* (a fixture
  cannot notice a Tailwind class rename), held to beating the page's own teaser: 9355 vs 51 chars. **The home shot is
  staged freshest-first, not quietest-first** — quietest was right while a small folder bought a section header, and
  after W03 it put every category off-screen. 38/38 pull, 1038 entries, 0 rows out of order, 7 inside 24 h.
- 2026-08-18 — **W10 (the review) caught what a per-task session cannot**: live **gate 9 still read the folder-grouped
  list W03 deleted** (it would have failed W11), the README claimed swipe actions and a row source-tap that do not
  exist, and `ArticleFixtures.pending` said a test measured it when none did.
- **V04/#3: the inset contract is one doc comment in `ui/nav/PerchNavHost.kt`** — four clauses, one test each in
  `WindowInsetsTest`; never `.statusBarsPadding()` a screen. **Robolectric has no bars**, so `WindowInsetsSupport.kt`'s
  `applyWindowInsets` dispatches its own to every Compose root.
- **V05/#12: "Unread" is gone from every reader-facing string** (identifiers keep it); **pin `HomeTestTags.TITLE`,
  never `onNodeWithText("Feed")`** — the tab has read "Feed" since U09. **W04/#20: a row's meta is now the bare
  source name** (`EntryRowTestTags.META`, category dimmed after a `·`, Uncategorized unlabelled; `DATE` beneath),
  so a drawer row is `hasClickAction() and !hasTestTag(HomeTestTags.ENTRY)`.
- **V08/#10: the scoped list is state, not a route.** `HomeScope` is **hoisted into `PerchNavHost`** — third such
  state — `BackStep.LeaveScope` a rung above `ScrollFeedToTop`. **`selectTab` is a silent no-op from the article
  route**: `popUpTo(start){saveState}` saves it and `restoreState` puts it back — pop first, switch tabs only if
  needed. Scoping **does not touch the time window**. Residual (T29): a scoped list repeats the source on every row.
- **V09/#4: a table joins on its *shape*** — `carriesContentTable` (≥3 rows, ≥2 columns, no nesting, not linky).
  A page fixture is not a feed body: `zdi-page-*.html`.
- **V11/#7.** Anything spanning a scrolling child — the gutter's rule, a table's edge fade — measures 0 against
  the article's unbounded height: the rule needs the Row at **`height(IntrinsicSize.Min)`**, the fade is a
  draw-only `matchParentSize` **sibling** of the scroll (inside it it lands off-screen at the content's far end).
- 2026-08-10 — **V15.** Gate 8 is the time window, **gate 9** V06's order (the drawer's two queries only, since W03),
  **gate 7** the live page's `publishedAt` order (it stopped counting folder sections at W03), and gate 6b fetches
  V09's ZDI *page* by name because its feed ships full bodies. Gate 5b's floor is U15's 75.4% less 10: the sampled set
  is ~70 entries, so one entry is 1.4 of them. **`research.checkpoint.com` answers 202 empty when live runs come too
  close together** (Cloudflare; **a `curl` probe spends the allowance the next run needs**) — wait ~10 quiet minutes
  and rerun. Healthy, not an exclusion.
