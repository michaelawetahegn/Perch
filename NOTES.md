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
- **2026-08-07 — host froze (session #11). Cause: host memory, not any task's code.**
  Fixed in `.wslconfig` (7 GB + `autoMemoryReclaim=gradual`), `gradle.properties` (caps —
  see CLAUDE.md) and `loop.sh` (`reclaim()` between sessions). **The `.wslconfig` half needs
  a `wsl --shutdown` that has not happened yet** — until then loop-side reclaim is all of it.

## Log

- 2026-08-07 — T04 done: 42 manifest rows, **39 snapshots** (19 MB) via `scripts/harvest.sh`;
  homepage HTML for the auto-discovery cases is in `fixtures/homepages/`. **3 exclusions T32
  must record:** `danluu.com` (11.1 MB) and `projectzero.google` (13.2 MB) exceed SPEC.md §6's
  8 MiB body cap, so the app would refuse them live; `research.nccgroup.com` publishes no feed
  anywhere (no `<link rel=alternate>`, every path guess soft-404s to HTML) — it is dead.
- 2026-08-07 — T05–T09 done: four parsers + `FeedParser` dispatch + `FeedCorpusTest` (39
  snapshots × 2; it `check`s ≥35 exist, so it cannot go vacuous). Shared plumbing lives in
  `data/parse/FeedXml.kt` (lenient parse, direct-child + local-name lookup, `plainText`,
  `resolveUrl`, `stableGuid`, `leadImageUrl`) — reuse it. Date floor 2000-01-01 (below → null,
  caller falls back); >now+24h clamps to now.
- 2026-08-07 — T10 done: `HtmlSanitizer` — jsoup `Cleaner` + a from-scratch `Safelist`, whose
  **`addProtocols` also resolves relative URLs**, so there is no manual `resolveUrl` pass.
- 2026-08-07 — T12 done: Room schema v1 + DAOs. No `@TypeConverter`s needed — every §4 column is
  SQLite-native, dates are epoch millis. **No Room `@Upsert` for entries**: it resolves the
  unique-index conflict on the *primary key*, still 0 on a freshly parsed entry, so the row is
  silently dropped. `EntryDao.upsertAll` matches on `(feedId, guid)`, carries the existing id
  forward, preserves `isRead`/`readAt`/`isStarred`, returns the count of genuinely new rows.
- 2026-08-07 — T13 done: `EntryRepository` read state. `observeUnreadCountsByFeed()` is a
  `@MapColumn` multimap over `GROUP BY`, so a **fully-read source is absent from the map, not 0**
  — read it as `counts[id] ?: 0`. `EntryDao.setRead` chunks ids at 900 (SQLite caps `IN (…)`);
  call it, never `setReadForIds`. T26's undo holds the exact ids that one call flipped, so it
  cannot resurrect an entry read before or after it.
- 2026-08-07 — T14 done: `data/net/FeedFetcher` + `PerchHttp`. The 8 MiB cap is checked against
  `Content-Length` *and* the stream (a chunked response declares no length). **`PerchHttp`'s disk
  cache does not fight conditional GET**: OkHttp bypasses its cache for any request already
  carrying `If-None-Match`/`If-Modified-Since` — verified. `client(cacheDir = null)` = no cache.
- 2026-08-07 — T15 done: `data/repo/FeedRepository`. **Sanitizing moved here**: parsers still
  hand out raw `contentHtml`, but what reaches the DB is already `HtmlSanitizer`-clean plus a
  summary, so T25's renderer never sees feed-authored markup. Three things a later task must not
  undo: every feed write goes through `mutate()`, which **re-reads the row** (a fetch takes
  seconds and a rename can land mid-flight — writing back the pre-fetch snapshot reverts it, and
  there is a test); and a redirect only moves `feedUrl` if no other feed owns it, since two
  converging subscriptions would abort on the unique index.
- 2026-08-07 — T16 done: add/remove/rename on `FeedRepository`. `resolve(url) → SourceResolution`
  then `add(Resolved)` — DESIGN.md §5's confirm-before-commit, and why adding costs **one** fetch:
  `Resolved` carries the parsed feed.
- 2026-08-07 — T17 done: `data/opml/Opml.kt` + `data/repo/OpmlRepository.kt`. Import **fetches
  nothing**: rows land with null validators and null `lastFetchedAt` — the "never polled" state
  T18's worker collects, and T27 owns the single refresh afterwards. Identity is `feedUrl`. An
  outline is `invalid` only if it claims to be a source (`xmlUrl`, or `type=rss|atom|feed`);
  anything else is a folder, even an empty one.
- 2026-08-07 — T18 done: `work/RefreshWorker.kt` + `work/WorkScheduler.kt`. The worker calls
  `FeedRepository.refreshDue()`, not `refreshAll()` — that is where §7's "5 failures → 6h floor"
  lives, and T26's pull-to-refresh must keep using `refreshAll`/`refresh(id)` so a pull still
  polls a sick source. Partial failure is a **success** (per-feed `lastError` already tells the
  drawer); only an all-failed pass retries, capped at 3. `WorkScheduler.setInterval` (UPDATE,
  T27's) vs `ensureScheduled` (KEEP, startup) — startup must never UPDATE or it resets the
  reader's interval every launch.
- 2026-08-07 — T19 done: `ui/theme/{Color,Type,Dimens,Theme}.kt`. Tonal palettes generated from
  `#3F6E5A` in LCh, **private to Color.kt** — screens address `MaterialTheme.colorScheme` roles,
  never a tone. `Dimens` owns every dp *incl.* the §8 article metrics; `ArticleType` (serif) is
  kept out of `PerchTypography` (sans) so furniture cannot render serif by accident — T25 reads
  `ArticleType`. **Standing grep gate: no `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.**
  `PerchTheme(dynamicColor = false)` pins the fallback scheme; T29/T32 need that determinism.
- 2026-08-07 — T20 done: `di/AppContainer` + `ui/nav/PerchNavHost` + screen shells. **Compose UI
  tests must live in `app/src/testDebug/`** — `ui-test-manifest` is a `debugImplementation`, so
  `ComponentActivity` is missing from the release manifest and `./gradlew test` fails there.
  Screens take dependencies only via `XxxViewModel.factory(container)`, never a singleton lookup.
- 2026-08-07 — T21 done: home's unread list. One SQL join, `EntryDao.observeUnreadListItems()
  → EntryListItem`, so a row never looks its feed up and no article body is loaded to draw a
  list. **Read entries drop out of that flow** — T27's "show read entries" adds the variant, it
  does not filter in the UI. **No paging library:** Room Flow + `LazyColumn` *is* the paging.
- 2026-08-07 — T22 done: source drawer + filter (debug 346, release 329, 0 failures). Two
  Robolectric traps every later UI task will hit. **`compose.waitUntil` only advances the
  *virtual* clock**, so it times out waiting for any Room emission *after* the first (the
  re-query runs on Room's executor, a real thread) — copy `HomeScreenTest.awaitState`, which
  polls `waitForIdle` in wall-clock time. And an **injected tap never reaches a node inside the
  opened drawer sheet**, on screen and carrying `OnClick` though it is; drive it with
  `performSemanticsAction(SemanticsActions.OnClick)` — T23/T24's sheet and dialog will need the
  same. The filter is one SQL predicate (`observeUnreadListItems(feedId)`, null = every source);
  an id no longer in `sources` drops the filter, which is what keeps T24's remove honest.
- 2026-08-07 — T23 done: `ui/source/{PastedUrl,AddSourceViewModel,AddSourceSheet}.kt` (debug 362,
  release 335, 0 failures). **The sheet owns paste normalization** — `example.com` and `feed://`
  become https here; the repository still refuses to guess. Confirm-then-commit is literally the
  button's two calls: `resolve` spends the round trip, `add` spends nothing more. A
  `ModalBottomSheet` *does* compose and expose its nodes under Robolectric. But the drawer's
  "Add source" row is composed even while the drawer is shut, so it collides by text with the
  empty state's button — tag one of them, as T24's dialogs will also have to.
- 2026-08-07 — T24 done: long-press rename/remove (debug 370, release 335, 0 failures).
  `NavigationDrawerItem` answers taps only and its own `clickable` eats the gesture, so source
  rows are a hand-built `SourceRow` (`combinedClickable` + `semantics(mergeDescendants)`); its
  metrics are `Dimens.drawerRow*`. `SourceUiItem` now carries `publishedTitle`/`customTitle`
  separately (`title` is derived) because rename edits one and falls back to the other.
  **Two test traps:** a drawer row and the app bar can hold the same text, so match rows with
  `filterToOne(hasClickAction())`, never bare `onNodeWithText`; and the dialogs leave the drawer
  open behind them — re-calling `openDrawer()` then leaves nodes `assertIsDisplayed`-false, so
  either assert straight away or close the drawer by selecting something.
