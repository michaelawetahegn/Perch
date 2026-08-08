# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

Windows 10 Pro 19045.6466, WSL 2.7.11, i7-4790K, 15.9 GB host RAM. No physical device; WHPX **enabled**. Paths,
JDKs and wrappers are in CLAUDE.md §Environment. **The `.wslconfig` 7 GB cap only took effect at the *second*
`wsl --shutdown` — confirm `/proc/meminfo` MemTotal ~6.9 GB; ~9.9 GB means the cap is off and a freeze is coming.**

## Log
- 2026-08-07 — T19: screens address `colorScheme` roles, never a tone. **Grep gate: no `Color(0x`/`N.dp`/`N.sp` outside `ui/theme/`.**
- 2026-08-07 — **Standing UI-test traps (T20/T22/T26/T29).** Compose UI tests live in **`app/src/testDebug/`**
  (`ui-test-manifest` is `debugImplementation`). An injected tap/long-press **never reaches a node inside a drawer
  sheet, bottom sheet or dropdown** — use `performSemanticsAction(OnClick/OnLongClick)`. `compose.waitUntil` advances
  only the *virtual* clock; wait on Room in wall-clock time (`awaitInRealTime`). `PullToRefreshBox` ignores a swipe
  unless its child scrolls. Screenshots: **never `captureToImage()`** (CLAUDE.md is wrong) — `PixelCopy` waits on a
  frame callback Robolectric never delivers, while under `@GraphicsMode(NATIVE)` `View.draw(Canvas)` is synchronous;
  a sheet/dialog/dropdown is its **own window**, so draw its `rootView` over the decor view **translated by
  `getLocationOnScreen`**. **Residuals:** zero window insets; the empty state cannot be pulled.
- 2026-08-07 — T25: `ArticleLowering`'s input **must** be `HtmlSanitizer` output (`ArticleLoweringCorpusTest` asserts **0 `Unsupported`**); a source that renders wrong is an `ArticleLowering` bug, never a branch in `ArticleBody`.
- 2026-08-07 — T31: `fallbackToDestructiveMigration()` is **gone for good** — `PerchDatabaseMigrationTest` fails the
  build on a version bump with no migration or a stale `app/schemas/N.json`.
- 2026-08-07 — **T32.** `acceptance/LiveAcceptanceTest` (in `testDebug`): `./gradlew :app:testDebugUnitTest
  -Pperch.live=true --tests '*LiveAcceptance*'`. **Gate 1 sits on the 38/42 floor** — `danluu.com`/`projectzero.google`
  bust SPEC §6's 8 MiB cap, `research.nccgroup.com` has no feed, `rachelbythebay.com` times out here; one more death is
  a red run. **§8 residual, not ours:** the LLVM feed omits the spaces around inline `<code>`/`<a>` — do not "repair"
  it. **v0.1 APK (on the phone, debug-signed):** `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-08-07 — **U01: the repo is public** (MIT) — never un-redact the `apiKey` in `fixtures/homepages/research-nccgroup-com.html`.
- 2026-08-07 — **U02: losing `~/.perch/perch-release.jks` or `signing.properties` makes every future install a data
  wipe** — you cannot rotate to a key you no longer have. Both `chmod 600`, outside the repo, **not backed up yet**.
  Cert SHA-256 `61367c04…fce489` *is* the update identity; absent the key, release falls back to debug signing with a
  warning. Version lives only in `perchVersionCode`/`perchVersionName` atop `app/build.gradle.kts`; **`assembleRelease`
  runs `lintVitalRelease` where `assembleDebug` does not.**
- 2026-08-07 — **U03: build test databases with `PerchDatabase.inMemory(context)`**, never
  `Room.inMemoryDatabaseBuilder` — only the former seeds Uncategorized, without which the `feeds.folderId` FK rejects
  the first feed; a migration test builds the old DB from `app/schemas/N.json` via `ExportedSchemas.createStatements`.
  **`WorkSchedulerTest` "choosing manual cancels the periodic refresh" is flaky in a full-suite run**, passes alone.
- 2026-08-07 — **U04 (`isSaved`/`savedAt`/`starredAt`): three independent reader-owned flags**, each nulling its
  timestamp when it goes off. **Add a fourth and two places erase it:** `EntryDao.upsertAll` (never Room `@Upsert` —
  it resolves on the primary key; ours matches `(feedId, guid)`) must copy every flag *and* timestamp off the existing
  row, and `deleteReadOlderThan` must exempt it.
- 2026-08-07 — **U06: folders scope the Feed via `HomeScope`** — a **second SQL predicate on `feeds.folderId`**, never
  a resolved feed-id list, which a move invalidates. **Room rejects a `@Query` whose parameter it cannot see used.**
- 2026-08-07 — **U07: the window is a *calendar* one** (`TimeFilter.since(clock)` = local midnight) and **defaults to
  Today** — a UI test seeding anything older must pin `TimeFilter.AllTime` via its own `SettingsStore` or it asserts
  against an empty screen. Address section headers by `HomeTestTags.section(id)`, never by text (the drawer composes
  while closed). `uiState` is `WhileSubscribed`: an action needing the window reads `settings.current()`.
- 2026-08-07 — **U08: the row's 96dp thumbnail square is always reserved** (absent/loading/failed draw one
  placeholder). Coil offline: a `Mapper` succeeds, an `Interceptor` returning `ErrorResult` fails, one that
  `awaitCancellation()`s stays loading; **a list screenshot needs `stubThumbnails()`**.
- 2026-08-08 — **U08a.** A `TextButton` **merges its descendants** (label needs `useUnmergedTree = true`); and
  **`hasVisualOverflow` is not a clipping assertion** — assert `lineCount` plus `size.width >= maxIntrinsicWidth`
  under **`@GraphicsMode(NATIVE)`**: Robolectric's default text measurement gives every char ~1px, so everything fits.
- 2026-08-08 — **U09: the bottom bar and the `NavHost` are siblings**, not nested. **Feed's `DrawerState`/
  `LazyListState` are hoisted into `PerchNavHost`**: state remembered inside the Feed composable dies on a tab switch.
  §0's back policy is the pure `nextBackStep(BackState)` in `BackChain.kt` — the enum's declaration order *is* the
  priority. **`EntryRow` owns its own `combinedClickable`**: an inner `clickable` eats the pointer stream.
- 2026-08-08 — **U09a: the drawer long press is multi-select.** `DrawerSelection` holds §0's two invariants
  (homogeneous; never Uncategorized), hoisted into `PerchNavHost` as `rememberSaveable`. **The selection `BackHandler`
  must live inside `ModalDrawerSheet`** — the root one registers first and loses. A batch delete's dialog is **a
  coroutine behind its tap**, so wait in wall-clock time. **Residual:** mid-selection a folder header does nothing.
- 2026-08-08 — **U09b: the mark is path data in `ui/theme/Brand.kt`**, restated verbatim in
  `ic_launcher_foreground.xml` / `_monochrome.xml` (a VectorDrawable cannot read a Kotlin constant); `LauncherIconTest`
  asserts the three agree, all ink inside the centre 66dp circle. **Residual:** the themed icon's P counter closes up
  at 48dp.
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
- 2026-08-08 — **U11: the mono face is bundled JetBrains Mono 2.304** (`res/font/jetbrains_mono.ttf`, 268 KB, SIL OFL
  1.1, licence in `assets/`) — the one exception to DESIGN.md §3, **ligatures off** (`liga 0, calt 0, dlig 0`) so we
  never draw `->` as `→` in someone's source. **`HtmlSanitizer` now keeps `class` on `pre` and nowhere else**, holding
  only a normalised `language-x`: the claim is on the `<code>` (Prism), the `<pre>`, or a wrapper `<div>` two levels up
  (Rouge/Jekyll — nullprogram), so it is hoisted before `Cleaner` runs. **A declared language is final, `plaintext`
  included**; only an undeclared block is sniffed. Highlighting is five roles in `ui/theme/CodeTheme.kt` via
  `LocalCodeColors`; the lexer is total by construction (never a `catch`) — every branch advances and an unclosed
  construct runs to end-of-line or EOF. The line-number gutter sits **outside** the `horizontalScroll` and inside a
  **`DisableSelection`** (the article body is one big `SelectionContainer`, so numbers would otherwise copy).
  **Residual:** no rule between gutter and code, so a scrolled wide line slides to within 12dp of the numbers.
- 2026-08-08 — **U11a (tables).** `colspan`/`rowspan` survive `HtmlSanitizer` (structure, not style) and
  `ArticleLowering.table()` lays a real grid — merged cells **padded out**, short rows padded to the widest, so every
  row is one width; header = **any** `th` in row 1. **Inside a `horizontalScroll` a `fillMaxWidth` divider measures to
  0** — that, not the lowering, is why tables looked ruleless; rules and the header tint are drawn at the summed
  column width. Columns come from `rememberTextMeasurer` over the first 50 rows (a ZDI advisory is 211×10), clamped
  56–260dp; at the ceiling a cell wraps rather than widen the table. `fixtures/articles/zdi-*.html` are **feed bodies,
  not pages**: ZDI ships full content, and `ArticleExtractor` loses a table on a Squarespace page anyway (each block is
  its own `sqs-block` div, past `assemble`'s sibling sweep) — a real gap for excerpt-only Squarespace, open till U15
  6b. **Residual:** no edge affordance says a wide table scrolls.
