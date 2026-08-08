# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM. No physical device; WHPX **enabled**. Paths,
JDKs and wrappers are in CLAUDE.md §Environment. **The `.wslconfig` 7 GB cap only took effect at the *second*
`wsl --shutdown` — confirm `/proc/meminfo` MemTotal ~6.9 GB; ~9.9 GB means the cap is off and a freeze is coming.**

## Log
- 2026-08-07 — T12–T18. **Never Room `@Upsert` for entries** (it resolves on the *primary key*, 0 on a fresh parse); `EntryDao.upsertAll` matches `(feedId, guid)`. Sanitizing lives in `FeedRepository`.
- 2026-08-07 — T19: screens address `colorScheme` roles, never a tone. **Grep gate: no `Color(0x`/`N.dp`/`N.sp` outside `ui/theme/`.**
- 2026-08-07 — **Standing UI-test traps (T20/T22/T26/T29).** Compose UI tests live in **`app/src/testDebug/`**
  (`ui-test-manifest` is `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer
  sheet, bottom sheet or dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `compose.waitUntil` advances
  only the *virtual* clock; wait on Room in wall-clock time (`awaitInRealTime`). `PullToRefreshBox` ignores a swipe
  unless its child scrolls. Screenshots: `ui/screenshot/*` → `screenshots/`, and **never `captureToImage()`**
  (CLAUDE.md is wrong) — `PixelCopy` waits on a frame-commit callback Robolectric never delivers, while under
  `@GraphicsMode(NATIVE)` a plain `View.draw(Canvas)` is synchronous; a sheet, dialog or dropdown is its **own
  window**, so draw its `rootView` over the decor view **translated by `getLocationOnScreen`**. **Residuals:** zero
  window insets (app bar flush at y=0); the empty state cannot be pulled.
- 2026-08-07 — T25: `ArticleLowering`'s input **must** be `HtmlSanitizer` output (`ArticleLoweringCorpusTest` asserts **0 `Unsupported`**); a source that renders wrong is an `ArticleLowering` bug, never a branch in `ArticleBody`.
- 2026-08-07 — T31: `fallbackToDestructiveMigration()` is **gone for good** — `PerchDatabaseMigrationTest` fails the
  build on a version bump with no migration or a stale `app/schemas/N.json`. (`WorkManagerTestInitHelper`'s
  `SynchronousExecutor` misses WorkManager's own executor — poll in wall-clock.)
- 2026-08-07 — **T32.** `acceptance/LiveAcceptanceTest` is in `testDebug`. Re-run: `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 landed on the 38/42 floor** — `danluu.com` (11.1 MB) and
  `projectzero.google` (13.2 MB) bust SPEC §6's 8 MiB cap, `research.nccgroup.com` has no feed, `rachelbythebay.com`
  times out from this host — expect a red run if one more source dies. **§8 residual, not ours:** the LLVM feed omits
  the spaces around inline `<code>`/`<a>` — do not "repair" it. **v0.1 APK (on the phone, debug-signed):**
  `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-08-07 — **U01: the repo is public** (MIT) — never un-redact the `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside the repo, **not backed up
  anywhere yet**. Cert SHA-256 `61367c04…fce489` (valid to 2053) *is* the update identity; absent the key, release
  falls back to debug signing with a warning. Version lives only in `perchVersionCode`/`perchVersionName` atop
  `app/build.gradle.kts`; **`assembleRelease` runs `lintVitalRelease` where `assembleDebug` does not.**
- 2026-08-07 — **U03 (folders): build test databases with `PerchDatabase.inMemory(context)`**, never
  `Room.inMemoryDatabaseBuilder` — only the former seeds Uncategorized, without which the `feeds.folderId` FK rejects
  the first feed. **A migration test builds the old DB from `app/schemas/N.json` via
  `ExportedSchemas.createStatements`.** **`WorkSchedulerTest` "choosing manual cancels the periodic refresh" is flaky
  in a full-suite run** and passes alone.
- 2026-08-07 — **U04 (`isSaved`/`savedAt`/`starredAt`): three independent reader-owned flags**, each nulling its
  timestamp when it goes off. **Two places erase them if you add a fourth and forget:** `EntryDao.upsertAll` must copy
  every flag *and* timestamp from the existing row (a parsed entry arrives with them at their defaults), and
  `deleteReadOlderThan` exempts `isSaved`/`isStarred`.
- 2026-08-07 — **U05 BLOCKED on its gate, not its code.** Diagnosis is in PLAN-2's box; **re-gated at U15 gate 4.**
- 2026-08-07 — **U06: folders scope the Feed via `HomeScope`** — a **second SQL predicate on `feeds.folderId`**,
  never a resolved list of feed ids, which a move would invalidate. **Room rejects a `@Query` whose parameter it
  cannot see used**: an always-true predicate must still say `:folderId`.
- 2026-08-07 — `FeedXml.kt`'s `stableGuid` separator must stay `"\u0000"` **escaped**: a raw NUL byte in a literal
  makes git diff the file as binary, so no session can review it.
- 2026-08-07 — **U07: the window is a *calendar* one** (`TimeFilter.since(clock)` = local midnight, never `now - n`)
  and **defaults to Today** — a UI test seeding anything older must pin `TimeFilter.AllTime` via its own
  `SettingsStore` or it asserts against an empty screen. Sections fall out of the row (ordered folder-then-recency,
  carrying `folderId`/`folderName`); address headers by `HomeTestTags.section(id)`, never by text — the drawer
  composes even while closed. `uiState` is `WhileSubscribed`: an action needing the window reads `settings.current()`.
- 2026-08-07 — **U08: the row's 96dp thumbnail square is always reserved** — absent, loading and failed draw the same
  placeholder, so an arriving image never reflows the list. Coil states are reproducible offline: a `Mapper` succeeds,
  an `Interceptor` returning `ErrorResult` fails, one that `awaitCancellation()`s stays loading; **a list screenshot
  needs `stubThumbnails()`**. **Residual:** a one-line title leaves dead space under its metadata.
- 2026-08-08 — **U08a (`TimeRangeControl`).** Two Compose-test traps: a `TextButton` **merges its descendants**, so
  its label needs `useUnmergedTree = true`; and **`hasVisualOverflow` is not a clipping assertion** (a fractional
  paragraph height rounding past the layout height also trips it) — assert `lineCount` plus
  `size.width >= maxIntrinsicWidth` under **`@GraphicsMode(NATIVE)`**, since Robolectric's default text measurement is
  ~1px per character and every string "fits".
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**, not nested — only the shell can say which tab
  is selected or leave the bar off `article/{id}`. **Feed's `DrawerState`/`LazyListState` are hoisted into
  `PerchNavHost`**: state remembered inside the Feed composable is torn down on every tab switch. §0's back policy is
  the pure `nextBackStep(BackState)` in `BackChain.kt` — the enum's declaration order *is* the priority, and the one
  root `BackHandler` is disabled at `Exit`. **`EntryRow` owns its own `combinedClickable`**: an inner `clickable` eats
  the pointer stream.
- 2026-08-08 — **U09a: the drawer long press is multi-select.** `DrawerSelection` holds §0's two invariants
  (homogeneous; never Uncategorized) as a value, hoisted into `PerchNavHost` as `rememberSaveable`. **The selection
  `BackHandler` must live inside `ModalDrawerSheet`** — the root one registers first and loses to the drawer's own.
  A batch delete's dialog is **a coroutine behind its tap** (the count is a DB read), so wait in wall-clock time.
  **Residual:** mid-selection a folder header's name is a live-looking target that does nothing.
- 2026-08-08 — **U09b: the mark is path data in `ui/theme/Brand.kt`**, restated verbatim in
  `ic_launcher_foreground.xml` / `_monochrome.xml` (a VectorDrawable cannot read a Kotlin constant); `LauncherIconTest`
  asserts the three agree. Geometry is in **launcher coordinates**, all ink inside the centre 66dp circle; the
  **monochrome layer needs *lighter* strokes** or the rules close into a black slab.
  `design/brand/perch-wordmark.png` is **generated** by `BrandScreenshotTest`. **Residual:** the themed icon's P
  counter closes up at 48dp.
- 2026-08-08 — **U07a: all three lists are Paging 3.** New deps `androidx.paging:paging-runtime-ktx`/`-compose`
  (+`room-paging`, `paging-testing`): the fallback `LIMIT`/`OFFSET` would have hand-rolled the invalidation plumbing
  Room already generates. `PerchPaging.config` is shared by all three — **placeholders off**, so
  `startsSection(previous, item)` is answerable at a page edge; `initialLoadSize` is one page, not Paging's three. The three list queries live once in **`EntryQueries`** (`const val`, which Room/KSP resolves) because each
  exists twice — `Flow<List>` *and* `PagingSource`. **`uiState.entries` is gone**: ask the screen
  (`compose.rowTitles()`) what the list holds, `observeEntries` what the *query* holds. `performScrollToIndex` past
  the loaded rows throws.
- 2026-08-08 — **U10: DB is version 4** (`entries.bodyIsExcerpt`, `fullTextAt`); Readability-over-jsoup in
  `data/extract/`, **no new dependency**. Three traps. (1) **`ArticleLowering` deletes truncation markers as chrome**
  (T25's `CHROME`), so `FullText` looks for "Continue reading" in the *unlowered* text — once there are blocks the
  evidence is gone. (2) Scoring finds the *tightest* subtree, so a decorative single-child wrapper (ciechanow.ski's
  `bg_content`) wins and the article's last section, its sibling, is lost — hence `unwrapped()`. (3) **`upsertAll` is
  now the third place a refresh can erase reader-visible state**: it keeps the extracted `contentHtml`+`fullTextAt`
  unless the feed's body is longer. The guard that makes auto-extract-on-open safe: **an extraction only ever replaces
  a body it beats.** §0 says fabiensanglard ships "nothing else" — really 68 of 144 ship nothing, 76 ship a one-line
  `<description>`; both need U10. Fixtures: `fixtures/articles/` (15 pages + the gpuopen feed, 2 MB, 2026-08-08).
