# NOTES.md

Working memory for unattended sessions, per CLAUDE.md §NOTES.md discipline: environment
quirks, blocked-task diagnoses, excluded feeds, residual polish, the final APK path.
**Under 100 lines** — prune anything a future session no longer needs.

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466 · WSL 2.7.11 · i7-4790K (4c/8t, VT-x ✔), 15.9 GB host RAM,
  65 GB free on `C:`. No physical device; WHPX **enabled** (`emulator -accel-check` →
  `WHPX(10.0.19045) is installed and usable.`). Every path/JDK/wrapper this implies lives
  in CLAUDE.md §Environment — do not re-record it here.
- Windows gateway from WSL: `172.18.144.1` (only if interop fails and adb must go TCP).
- **2026-08-07 — host froze on memory (session #11); fixed in `.wslconfig`, `gradle.properties`
  and `loop.sh`. The `.wslconfig` half still needs a `wsl --shutdown` that has not happened.**

## Log
- 2026-08-07 — T04 done: 42 manifest rows, **39 snapshots** (19 MB) via `scripts/harvest.sh`;
  homepage HTML for the auto-discovery cases is in `fixtures/homepages/`. **3 exclusions T32
  must record:** `danluu.com` (11.1 MB) and `projectzero.google` (13.2 MB) exceed SPEC.md §6's
  8 MiB body cap, so the app would refuse them live; `research.nccgroup.com` publishes no feed
  anywhere (no `<link rel=alternate>`, every path guess soft-404s to HTML) — it is dead.
- 2026-08-07 — T05–T09 done: four parsers + dispatch + `FeedCorpusTest` (39 snapshots; it `check`s
  ≥35 exist, so it cannot go vacuous). Shared plumbing in `data/parse/FeedXml.kt` — reuse it.
- 2026-08-07 — T12–T18 done (storage, HTTP, sync, worker). Three rules that outlive them:
  **never Room `@Upsert` for entries** — it resolves on the *primary key*, still 0 on a freshly
  parsed row, so the row is silently dropped; `EntryDao.upsertAll` matches `(feedId, guid)` and
  preserves `isRead`/`readAt`/`isStarred`. **Sanitizing lives in `FeedRepository`**, so what
  reaches the DB is already `HtmlSanitizer`-clean plus a summary and no renderer sees feed markup.
  And `observeUnreadCountsByFeed()` is a `GROUP BY` multimap, so a **fully-read source is absent
  from the map, not 0** — read it as `counts[id] ?: 0`.
- 2026-08-07 — T19 done: `ui/theme/{Color,Type,Dimens,Theme}.kt`. Tonal palettes from `#3F6E5A`
  in LCh, **private to Color.kt** — screens address `colorScheme` roles, never a tone. `Dimens`
  owns every dp incl. the §8 article metrics; `ArticleType` (serif) is kept out of
  `PerchTypography` (sans) so furniture cannot render serif by accident. **Standing grep gate: no
  `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.** `PerchTheme(dynamicColor = false)` pins the
  fallback scheme; T29/T32 need that determinism.
- 2026-08-07 — T20 done: `di/AppContainer` + `ui/nav/PerchNavHost` + screen shells. **Compose UI
  tests must live in `app/src/testDebug/`** — `ui-test-manifest` is a `debugImplementation`, so
  `ComponentActivity` is missing from the release manifest and `./gradlew test` fails there.
- 2026-08-07 — T22 done: source drawer + filter. Two Robolectric traps every later UI task hits.
  **`compose.waitUntil` only advances the *virtual* clock**, so it times out without ever letting
  Room's executor run — poll in wall-clock time instead: copy `HomeScreenTest.awaitState`. And an
  **injected tap never reaches a node inside an opened drawer sheet or a scrolling column**, on
  screen and carrying `OnClick` though it is; drive it with `performSemanticsAction(...)`.
- 2026-08-07 — T25a done: `data/parse/{ArticleBlock,ArticleLowering}.kt`. Input **must** be
  `HtmlSanitizer` output; the mapper covers that whole allowlist, so `ArticleLoweringCorpusTest`
  asserts **0/2644 `Unsupported`** — stricter than T32 gate 2's ≤2%, and it names offending tags.
  Chrome regexes are whole-block matches, so **T25 must not re-strip anything**.
- 2026-08-07 — T25 done: `ui/article/{RichText,ArticleBody,ArticleScreen,ArticleViewModel}.kt`
  `ArticleBody` is total over the nine blocks with **no source-specific branch** — a source that
  renders wrong is a T25a lowering bug. An image **collapses the whole figure on a load error**, so
  testing one needs a Coil loader that succeeds (`Coil.setImageLoader` + a stub `Mapper`). **Residual:**
  Compose cannot colour or offset an underline, so §8's link rule is plain; inline code has no chip padding.
- 2026-08-07 — T26 done: pull-to-refresh, undo snackbar, `HomeBanner` (offline > selected-source
  error > all-failing), `data/net/ConnectivityMonitor` (`AlwaysOnline` is the `AppContainer`
  default, so no test needs a shadow network). Robolectric gesture traps, if a later task adds
  one: `PullToRefreshBox` ignores a swipe unless its child scrolls; poll helpers must
  `waitForIdle()` *after* the predicate passes; refresh-prepended rows compose above the viewport,
  so assert on list state, never `assertIsDisplayed`. **T29 residual:** the empty state does not
  scroll, so it cannot be pulled; the overflow's Refresh is the way out of it.
- 2026-08-07 — T28 done: `app/src/debug/{assets/seed,java/…/debug}` — 8 snapshots (760 KB) chosen
  for spread (code-heavy, image-heavy, a 144-entry archive, both dialects) plus `index.tsv`
  (`file<TAB>real feed url`). Seeding goes through **`FeedRepository.add`**, so seeded rows are
  sanitized/summarized/deduped exactly like fetched ones — no screenshot flatters the renderer —
  and each source keeps its **real** feed URL, so pull-to-refresh on a seeded install works.
  The startup hook is a **debug-manifest `ContentProvider`** (`DebugSeedProvider`): a source set
  can add one on its own, so `PerchApp` carries no seeding branch and release has neither the
  classes nor the assets (`src/testRelease/…/NoSeederInReleaseTest` asserts both). Seeds only when
  zero sources exist, so a source removed during testing stays removed. **T29/T32:** do not rely
  on the provider under Robolectric — construct `DebugSeeder` against your own DB, as its test does.
- 2026-08-07 — T29 done: `ui/screenshot/DesignScreenshotTest` → 6 PNGs in `screenshots/`.
  **T32 must reuse its capture path, not `captureToImage()`:** that goes through `PixelCopy`, which
  blocks on a frame-commit callback a Robolectric window never delivers (2s timeout, nothing drawn).
  Under `@GraphicsMode(NATIVE)` a plain `View.draw(Canvas)` gives the same real pixels synchronously;
  a sheet or dialog is its **own window**, so draw each extra Compose root's `rootView` over the
  activity's decor view or the capture silently omits it. Fix from the critique: `ui/home/EntrySnippet`
  drops a summary that opens by restating the headline (link-blog bodies nearly all do) — narrow on
  purpose, a title occurring *later* is load-bearing.
  **§9 residuals** (all cosmetic, none reopened): Robolectric reports zero window insets, so the app
  bar and the drawer sheet sit flush at y=0 in every capture — an artifact, not a layout bug; the
  §9 lines for font scale 1.3, rotation/process-death and TalkBack traversal are not checkable from
  pixels and this pass did not verify them; T25's link-underline and inline-code residuals stand.
- 2026-08-07 — T30 done: `maestro/regression.yaml`, green. **Maestro-on-Windows won; the TCP-adb
  fallback was never needed.** To re-run: `cp maestro/regression.yaml /mnt/c/perch-stage/maestro/`
  (`device.sh stage` will **not** overwrite an existing dir), then from `/mnt/c`
  `cmd.exe /c "C:\perch-stage\maestro.bat --device emulator-5554 test C:\perch-stage\maestro\regression.yaml"`.
  `PerchNavHost` sets `testTagsAsResourceId` — the only handle an out-of-process driver has on a
  Compose node, and it does **not** reach the sheet or dialogs (own windows; address those by label).
  A Maestro text selector matches a node's text *entirely*, so merged rows need a `.*…*.` regex.
- 2026-08-07 — T31 done: `fallbackToDestructiveMigration()` is **gone for good**. Version 1 is the
  shipped baseline; `PerchDatabase.MIGRATIONS` is the ordered list and `PerchDatabaseMigrationTest`
  fails the build on a version bump with no matching migration, a stale `app/schemas/N.json`, or the
  fallback reappearing. Also fixed a real flake `clean test` exposed: `WorkManagerTestInitHelper`'s
  `SynchronousExecutor` does **not** cover WorkManager's own task executor, so `cancelUniqueWork`
  lands asynchronously — `WorkSchedulerTest` now polls in wall-clock time.
  **APK: `/home/michael/source/rss-reader/app/build/outputs/apk/debug/app-debug.apk`** — 19,411,514 B
  (18.5 MiB), built 2026-08-07 19:18 by `./gradlew clean test assembleDebug`; install it with
  `./scripts/device.sh install app/build/outputs/apk/debug/app-debug.apk`.
