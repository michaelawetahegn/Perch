# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline. **Under 100 lines.**

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466 · WSL 2.7.11 · i7-4790K (4c/8t, VT-x ✔), 15.9 GB host RAM,
  65 GB free on `C:`. No physical device; WHPX **enabled** (`emulator -accel-check` →
  `WHPX(10.0.19045) is installed and usable.`). Every path/JDK/wrapper this implies lives
  in CLAUDE.md §Environment — do not re-record it here.
- **2026-08-07 — host froze on memory (session #11); fixed in `.wslconfig`, `gradle.properties`
  and `loop.sh`. The `.wslconfig` half still needs a `wsl --shutdown` that has not happened.**

## Log
- 2026-08-07 — T04 done: 42 manifest rows, **39 snapshots** (19 MB) via `scripts/harvest.sh`.
  **3 exclusions:** `danluu.com` (11.1 MB) and `projectzero.google` (13.2 MB) exceed SPEC.md §6's
  8 MiB body cap, so the app refuses them live; `research.nccgroup.com` publishes no feed — dead.
- 2026-08-07 — T05–T09: four parsers + dispatch + `FeedCorpusTest` (39 snapshots; it `check`s ≥35
  exist, so it cannot go vacuous). Shared plumbing in `data/parse/FeedXml.kt` — reuse it.
- 2026-08-07 — T12–T18 done (storage, HTTP, sync, worker). Three rules that outlive them:
  **never Room `@Upsert` for entries** — it resolves on the *primary key*, still 0 on a freshly
  parsed row, so the row is silently dropped; `EntryDao.upsertAll` matches `(feedId, guid)` and
  preserves `isRead`/`readAt`/`isStarred`. **Sanitizing lives in `FeedRepository`**, so what reaches
  the DB is already `HtmlSanitizer`-clean and no renderer sees feed markup. And
  `observeUnreadCountsByFeed()` is a `GROUP BY` multimap, so a **fully-read source is absent from
  it, not 0** — read it as `counts[id] ?: 0`.
- 2026-08-07 — T19 done: `ui/theme/{Color,Type,Dimens,Theme}.kt`. Tonal palettes from `#3F6E5A` in
  LCh, **private to Color.kt** — screens address `colorScheme` roles, never a tone. `ArticleType`
  (serif) is kept out of `PerchTypography` (sans) so furniture cannot render serif by accident.
  **Standing grep gate: no `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.**
  `PerchTheme(dynamicColor = false)` pins the fallback scheme — the screenshot tests need it.
- 2026-08-07 — T20 done: `di/AppContainer`, `ui/nav/PerchNavHost`, screen shells. **Compose UI tests
  must live in `app/src/testDebug/`** — `ui-test-manifest` is `debugImplementation`, so the release
  manifest has no `ComponentActivity` and `./gradlew test` fails there.
- 2026-08-07 — T22 done: source drawer + filter. Two standing Robolectric traps: **`compose.waitUntil`
  only advances the *virtual* clock** (poll in wall-clock time — `awaitInRealTime` in
  `ui/screenshot/ScreenshotSupport.kt`), and an **injected tap never reaches a node inside an opened
  drawer sheet or a scrolling column** — drive it with `performSemanticsAction(...)`.
- 2026-08-07 — T25a done: `data/parse/{ArticleBlock,ArticleLowering}.kt`. Input **must** be
  `HtmlSanitizer` output; the mapper covers that whole allowlist, so `ArticleLoweringCorpusTest`
  asserts **0/2644 `Unsupported`** — stricter than T32 gate 2's ≤2%, and it names offending tags.
- 2026-08-07 — T25 done: `ui/article/{RichText,ArticleBody,ArticleScreen,ArticleViewModel}.kt`.
  `ArticleBody` is total over the nine blocks with **no source-specific branch** — a source that
  renders wrong is an `ArticleLowering` bug. An image **collapses the whole figure on a load error**,
  so testing one needs a Coil loader that succeeds (`Coil.setImageLoader` + a stub `Mapper`).
  **Residual:** Compose cannot colour or offset an underline, so §8's link rule is plain.
- 2026-08-07 — T26 done: pull-to-refresh, undo snackbar, `HomeBanner` (offline > selected-source
  error > all-failing), `data/net/ConnectivityMonitor` (`AlwaysOnline` is the `AppContainer`
  default, so no test needs a shadow network). Gesture traps: `PullToRefreshBox` ignores a swipe
  unless its child scrolls, and refresh-prepended rows compose above the viewport, so assert on
  list state, never `assertIsDisplayed`. **Residual:** the empty state does not scroll, so it
  cannot be pulled — the overflow's Refresh is the way out.
- 2026-08-07 — T28 done: `app/src/debug/{assets/seed,java/…/debug}` — 8 snapshots (760 KB) plus
  `index.tsv` (`file<TAB>real feed url`). Seeding goes through **`FeedRepository.add`**, so seeded
  rows are sanitized/summarized/deduped exactly like fetched ones and keep their **real** feed URL.
  The hook is a **debug-manifest `ContentProvider`**, so `PerchApp` carries no seeding branch and
  release has neither classes nor assets (`NoSeederInReleaseTest`); it seeds only when zero sources
  exist. Under Robolectric construct `DebugSeeder` against your own DB — the provider won't run.
- 2026-08-07 — T29 done: `ui/screenshot/{Screenshots,DesignScreenshotTest}` → 6 PNGs in
  `screenshots/`. **Never `captureToImage()`:** it goes through `PixelCopy`, which blocks on a
  frame-commit callback a Robolectric window never delivers (2s timeout, nothing drawn). Under
  `@GraphicsMode(NATIVE)` a plain `View.draw(Canvas)` gives the same pixels synchronously; a sheet
  or dialog is its **own window**, so draw each extra Compose root's `rootView` over the decor view.
  **§9 residuals** (cosmetic): Robolectric reports zero window insets, so the app bar and drawer sit
  flush at y=0 in every capture — an artifact. Font scale 1.3, rotation and TalkBack are unverified.
- 2026-08-07 — T30 done: `maestro/regression.yaml`, green, driven from Windows. To re-run:
  `cp maestro/regression.yaml /mnt/c/perch-stage/maestro/`
  (`device.sh stage` will **not** overwrite an existing dir), then from `/mnt/c`
  `cmd.exe /c "C:\perch-stage\maestro.bat --device emulator-5554 test C:\perch-stage\maestro\regression.yaml"`.
  `PerchNavHost` sets `testTagsAsResourceId` — an out-of-process driver's only handle on a Compose
  node, and it does **not** reach sheets or dialogs (own windows; address those by label). A Maestro
  text selector matches a node's text *entirely*, so merged rows need a `.*…*.` regex.
- 2026-08-07 — T31 done: `fallbackToDestructiveMigration()` is **gone for good**. Version 1 is the
  shipped baseline; `PerchDatabase.MIGRATIONS` is the ordered list and `PerchDatabaseMigrationTest`
  fails the build on a version bump with no matching migration, a stale `app/schemas/N.json`, or the
  fallback reappearing. Also: `WorkManagerTestInitHelper`'s `SynchronousExecutor` misses WorkManager's
  own task executor, so `cancelUniqueWork` lands asynchronously — poll in wall-clock.
- 2026-08-07 — **T32 done — the build is complete.** `acceptance/LiveAcceptanceTest` (in `testDebug`,
  not `test`: gate 3 needs a Compose rule and `ui-test-manifest` is `debugImplementation`). Re-run:
  `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'` — the property is
  forwarded to the test JVM as a system property in `build.gradle.kts`; without it `assumeTrue` skips
  and `./gradlew test` stays offline. Result: **gate 1 38/42** (the floor exactly), **gate 2 0
  Unsupported of 25,882 blocks over 1,037 entries**, gate 3 10 PNGs in `build/perch-screenshots/`.
  **4 refusals** — the 3 T04 exclusions above plus `rachelbythebay.com`, whose DNS resolves but whose
  port 443 times out from this host; site-side, not ours, and it puts gate 1 on the floor, so expect
  a red run if one more source dies. Fix from the critique: `ArticleViewModel.standfirst` compares
  the summary against the body's opening **prose across blocks**, not its first block — 300 flattened
  chars routinely span a heading and two paragraphs, and 4 of 5 sampled sources printed their lede
  twice. **§8 residuals, cosmetic and neither ours to fix in code:** the LLVM blog's own feed markup
  omits the spaces around inline `<code>`/`<a>` (`However,until`) — the renderer is being faithful,
  do not "repair" it in the lowering; and an inline image (WordPress LaTeX) is promoted to a
  full-measure block that splits a sentence, which the 1200×675 test placeholder exaggerates.
  **APK: `/home/michael/source/rss-reader/app/build/outputs/apk/debug/app-debug.apk`** — 19,411,514 B
  (18.5 MiB), built 2026-08-07 20:14 by `./gradlew clean test assembleDebug`; install it with
  `./scripts/device.sh install app/build/outputs/apk/debug/app-debug.apk`.
- 2026-08-07 — **U01: the repo is public** — `github.com/michaelawetahegn/Perch`, MIT, tag `v0.1.0`.
  Two audit changes not to undo: `org.gradle.java.home` moved out of the tracked
  `gradle.properties` into `~/.gradle/gradle.properties` (CLAUDE.md §Environment), and a
  third-party consent `apiKey` in `fixtures/homepages/research-nccgroup-com.html` is redacted.
