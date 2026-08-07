# NOTES.md

Working memory for unattended sessions. **Keep under 100 lines.** Environment quirks,
blocked-task diagnoses, excluded feeds, residual polish, the final APK path. Not a diary:
prune anything a future session no longer needs, and delete any workaround that now lives
in a script or in CLAUDE.md.

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
- 2026-08-07 — T12 done: Room schema v1 + DAOs; no `@TypeConverter`s (every §4 column is
  SQLite-native, dates are epoch millis). **Never Room `@Upsert` for entries** — it resolves on
  the *primary key*, still 0 on a freshly parsed row, so the row is silently dropped;
  `EntryDao.upsertAll` matches `(feedId, guid)` and preserves `isRead`/`readAt`/`isStarred`.
- 2026-08-07 — T13 done: `EntryRepository` read state. `observeUnreadCountsByFeed()` is a
  `@MapColumn` multimap over `GROUP BY`, so a **fully-read source is absent from the map, not 0**
  — read it as `counts[id] ?: 0`. `EntryDao.setRead` chunks ids at 900; never `setReadForIds`.
- 2026-08-07 — T14 done: `data/net/FeedFetcher` + `PerchHttp`. The 8 MiB cap is checked against
  `Content-Length` *and* the stream (a chunked response declares no length). **The disk cache does
  not fight conditional GET** — OkHttp bypasses it once a request carries a validator (verified).
- 2026-08-07 — T15 done: `data/repo/FeedRepository`. **Sanitizing moved here**: what reaches the
  DB is already `HtmlSanitizer`-clean plus a summary, so the renderer never sees feed markup. Two
  invariants: every feed write goes through `mutate()`, which **re-reads the row** (a rename can
  land mid-fetch; writing back the pre-fetch snapshot would revert it — there is a test); and a
  redirect moves `feedUrl` only if no other feed owns it, else converging subs hit the unique index.
- 2026-08-07 — T18 done: `work/RefreshWorker.kt` + `work/WorkScheduler.kt`. The worker calls
  `refreshDue()` — that is where §7's "5 failures → 6h floor" lives; a manual pull uses
  `refreshAll`/`refresh(id)` so it still polls a sick source. Partial failure is a **success**
  (per-feed `lastError` already tells the drawer); only an all-failed pass retries, capped at 3.
  `WorkScheduler.setInterval` (UPDATE, T27's) vs `ensureScheduled` (KEEP, startup) — startup must
  never UPDATE or it resets the reader's interval every launch.
- 2026-08-07 — T19 done: `ui/theme/{Color,Type,Dimens,Theme}.kt`. Tonal palettes from `#3F6E5A`
  in LCh, **private to Color.kt** — screens address `colorScheme` roles, never a tone. `Dimens`
  owns every dp incl. the §8 article metrics; `ArticleType` (serif) is kept out of
  `PerchTypography` (sans) so furniture cannot render serif by accident. **Standing grep gate: no
  `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.** `PerchTheme(dynamicColor = false)` pins the
  fallback scheme; T29/T32 need that determinism.
- 2026-08-07 — T20 done: `di/AppContainer` + `ui/nav/PerchNavHost` + screen shells. **Compose UI
  tests must live in `app/src/testDebug/`** — `ui-test-manifest` is a `debugImplementation`, so
  `ComponentActivity` is missing from the release manifest and `./gradlew test` fails there.
- 2026-08-07 — T21 done: home's unread list. One SQL join, `EntryDao.observeUnreadListItems()`,
  so a row never looks its feed up and no article body is loaded to draw a list. **Read entries
  drop out of that flow** — T27's "show read entries" adds the query variant, it does not filter
  in the UI. **No paging library:** Room Flow + `LazyColumn` *is* the paging.
- 2026-08-07 — T22 done: source drawer + filter. Two Robolectric traps every later UI task hits.
  **`compose.waitUntil` only advances the *virtual* clock**, so it times out without ever letting
  Room's executor run — poll in wall-clock time instead: copy `HomeScreenTest.awaitState`. And an
  **injected tap never reaches a node inside an opened drawer sheet or a scrolling column**, on
  screen and carrying `OnClick` though it is; drive it with `performSemanticsAction(...)`.
- 2026-08-07 — T23 done: `ui/source/{PastedUrl,AddSourceViewModel,AddSourceSheet}.kt`. **The sheet
  owns paste normalization** — `example.com` and `feed://` become https here; the repository still
  refuses to guess. The drawer's "Add source" row collides by text with the empty state's button.
- 2026-08-07 — T24 done: long-press rename/remove. `NavigationDrawerItem` answers taps only and
  its own `clickable` eats the gesture, so source rows are a hand-built `SourceRow`
  (`combinedClickable` + `semantics(mergeDescendants)`). **Two test traps:** a drawer row and the
  app bar can hold the same text, so match rows with `filterToOne(hasClickAction())`, never bare
  `onNodeWithText`; and the dialogs leave the drawer open, so nodes behind it are not "displayed".
- 2026-08-07 — T25a done: `data/parse/{ArticleBlock,ArticleLowering}.kt`. Input **must** be
  `HtmlSanitizer` output; the mapper covers that whole allowlist, so `ArticleLoweringCorpusTest`
  asserts **0/2644 `Unsupported`** — stricter than T32 gate 2's ≤2%, and it names offending tags.
  Chrome regexes are whole-block matches, so **T25 must not re-strip anything**.
- 2026-08-07 — T25 done: `ui/article/{RichText,ArticleBody,ArticleScreen,ArticleViewModel}.kt`
  (debug 425, release 369). `ArticleBody` is total over the nine blocks with **no source-specific
  branch** — a source that renders wrong is a T25a lowering bug. Opening marks read. An image
  **collapses the whole figure on a load error**, so testing one needs a Coil loader that succeeds
  (`Coil.setImageLoader` + a stub `Mapper`). **T29 residual:** Compose cannot colour or offset an
  underline, so §8's 1dp-primary-offset-3dp link rule is a plain one; inline code has no chip padding.
- 2026-08-07 — T26 done: pull-to-refresh, undo snackbar, `HomeBanner` (offline > selected-source
  error > all-failing) and `data/net/ConnectivityMonitor` (a `fun interface`; `AlwaysOnline` is the
  `AppContainer` default, so no test needs a shadow network). **Three Robolectric traps:**
  `PullToRefreshBox` answers a swipe only when its child actually scrolls, so a pull test must seed
  cached entries — over an empty state the gesture is silently inert; poll helpers must
  `waitForIdle()` **after** the predicate passes too, or the tree is one emission behind the view
  model; and refresh-prepended rows compose *above* the viewport (`LazyColumn` anchors on the old
  first key), so assert arrivals on list state, never `assertIsDisplayed`. **T29 residual:** the
  empty state does not scroll, so it cannot be pulled; the overflow's Refresh is the way out of it.
- 2026-08-07 — T27 done: `data/settings/SettingsStore.kt` (DataStore) + `ui/settings/*`.
  `AppContainer.settings` defaults to `SettingsStore.inMemory()`, so no unrelated test needs a
  file; `SettingsStore.at(file, scope)` is the real one. **DataStore refuses two live instances
  over one file**, so a test that wants a "restart" must cancel the first store's scope first.
  Startup seeds `ensureScheduled` from the *persisted* interval — the default would put a reader
  who chose Manual back on hourly polling every cold launch. `buildConfig = true` is on now (the
  About line). Two UI tests were load-flaky (assert before an async DB read / before the drawer
  finishes closing); both now poll in wall-clock time — `awaitText` / `awaitDisplayed`.
