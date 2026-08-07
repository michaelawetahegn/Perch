# NOTES.md

Working memory for unattended sessions. **Keep under 100 lines.** Record environment
quirks, blocked-task diagnoses, excluded feeds, version bumps, residual polish items,
and the final APK path. Prune anything a future session no longer needs.

Not a diary. If a workaround now lives in a script or in CLAUDE.md, delete its note.

---

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466 · WSL 2.7.11 · Ubuntu userland · i7-4790K (4c/8t, VT-x ✔),
  15.9 GB host RAM, 65 GB free on `C:`, 899 GB free on `/`.
- No physical device. WHPX **enabled** (human did the reboot); `emulator -accel-check` →
  `WHPX(10.0.19045) is installed and usable.` Every path/JDK/wrapper this implies now lives
  in CLAUDE.md §Environment — do not re-record it here.
- Windows gateway from WSL: `172.18.144.1` (only if the interop bridge fails and adb must go TCP).
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
- 2026-08-07 — T05–T09 done: four parsers + `FeedParser` dispatch + `FeedCorpusTest`
  (39 snapshots × 2; it `check`s ≥35 exist, so it cannot go vacuous). Shared plumbing lives in
  `data/parse/FeedXml.kt` (lenient parse, direct-child + local-name lookup, `plainText`,
  `resolveUrl`, `stableGuid`, `leadImageUrl`) — reuse it rather than reparsing. Date floor is
  2000-01-01 (below → null, caller falls back); >now+24h clamps to now. **Zero RSS 1.0 feeds in
  the corpus.** `FeedParser` picks format from an **8 KiB prefix scan**, so 5 MB of noise is ~ms.
- 2026-08-07 — T10 done: `HtmlSanitizer`. jsoup `Cleaner` + a from-scratch `Safelist`;
  **`addProtocols` also resolves relative URLs**, so no manual `resolveUrl` pass exists.
- 2026-08-07 — T12 done: Room schema v1 + DAOs. No `@TypeConverter`s are needed — every §4
  column is SQLite-native, dates are epoch millis. **No Room `@Upsert` for entries**: it resolves
  the unique-index conflict on the *primary key*, still 0 on a freshly parsed entry, so the row
  is silently dropped. `EntryDao.upsertAll` matches on `(feedId, guid)`, carries the existing id
  forward, preserves `isRead`/`readAt`/`isStarred`, and returns the count of genuinely new rows —
  which is what T15's "refetch inserts zero rows" assertion reads.
- 2026-08-07 — T13 done: `EntryRepository` read state. `observeUnreadCountsByFeed()` is a
  `@MapColumn` multimap over `GROUP BY`, so a **fully-read source is absent from the map, not
  0** — T22's drawer needs `counts[id] ?: 0`. `EntryDao.setRead` chunks ids at 900 (SQLite caps
  `IN (…)`); call it, never `setReadForIds`. Undo holds the exact ids that one call flipped, so
  it cannot resurrect an entry read before or after it; `readAt` comes from an injected `Clock`.
- 2026-08-07 — T14 done: `data/net/FeedFetcher` + `PerchHttp` (16 tests). The 8 MiB cap is
  checked against `Content-Length` *and* the stream (a chunked response declares no length).
  **`PerchHttp`'s disk cache does not fight conditional GET**: OkHttp bypasses its cache for
  any request already carrying `If-None-Match`/`If-Modified-Since` — verified, do not
  re-derive. `client(cacheDir = null)` skips the cache for tests.
- 2026-08-07 — T15 done: `data/repo/FeedRepository`. **Sanitizing moved here**: parsers still
  hand out raw `contentHtml`, but what reaches the DB is already `HtmlSanitizer`-clean plus a
  summary, so T25's renderer never sees feed-authored markup. Three things a later task must
  not undo: every feed write goes through `mutate()`, which **re-reads the row** — a fetch takes
  seconds and a rename can land mid-flight, so writing back the pre-fetch snapshot silently
  reverts it (there is a test). Retention says "still in the body" as `fetchedAt < refreshStart`,
  not a guid list, so it has no 999-variable ceiling. And a redirect only moves `feedUrl` if no
  other feed owns it — it is uniquely indexed, so two converging subscriptions would abort it.
- 2026-08-07 — T16 done: add/remove/rename on `FeedRepository` (23 tests). `resolve(url) →
  SourceResolution`, then `add(Resolved)` — DESIGN.md §5's confirm-before-commit, and also
  why adding costs **one** fetch: `Resolved` carries the parsed feed. **Paste normalization
  is deliberately not here**: `example.com` is rejected as unreachable, T23's sheet owns it.
- 2026-08-07 — T17 done: `data/opml/Opml.kt` + `data/repo/OpmlRepository.kt` (22 tests; suite
  now 308, 0 failures). Import **fetches nothing**: rows land with null validators and null
  `lastFetchedAt` — the "never polled" state T18's worker collects, and T27 owns the single
  refresh afterwards. Identity is `feedUrl` (same feed under two folders → one add, one
  duplicate). An outline counts as `invalid` only if it claims to be a source (`xmlUrl`
  present, or `type=rss|atom|feed`); anything else is a folder, even an empty one.
- 2026-08-07 — T18 done: `work/RefreshWorker.kt` + `work/WorkScheduler.kt` (14 tests; suite
  now 322, 0 failures). The worker calls the **new** `FeedRepository.refreshDue()`, not
  `refreshAll()` — that is where §7's "5 failures → 6h floor" lives, and manual pull-to-refresh
  (T26) must keep using `refreshAll`/`refresh(id)` so a pull still polls a sick source.
  Retry policy: partial failure is a **success** (per-feed `lastError` already tells the
  drawer); only an all-failed pass retries, capped at 3 attempts. `WorkScheduler.setInterval`
  (UPDATE, T27's) vs `ensureScheduled` (KEEP, startup) — startup must never UPDATE or it
  resets the reader's interval every launch. **`PerchApp` now builds db/http/`FeedRepository`
  lazily** to feed `PerchWorkerFactory`; T20 moves exactly those into `di/AppContainer`.
- 2026-08-07 — T19 done: `ui/theme/{Color,Type,Dimens,Theme}.kt`. Tonal palettes generated from
  `#3F6E5A` in LCh, **private to Color.kt** — screens address `MaterialTheme.colorScheme` roles,
  never a tone. `Dimens` owns every dp *incl.* the §8 article metrics; `ArticleType` (serif) is
  kept out of `PerchTypography` (sans) so furniture cannot render serif by accident — T25 reads
  `ArticleType`. **Standing grep gate: no `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`.**
  `PerchTheme(dynamicColor = false)` pins the fallback scheme — T29/T32 need it to be deterministic.
- 2026-08-07 — T20 done: `di/AppContainer` + `ui/nav/PerchNavHost` + screen shells. **Compose UI
  tests must live in `app/src/testDebug/`** — `ui-test-manifest` is a `debugImplementation`, so
  `ComponentActivity` is missing from the release manifest and `./gradlew test` fails there.
  Screens get dependencies only via `XxxViewModel.factory(container)`; nothing looks up a
  singleton, which is what lets a test compose any route over an in-memory DB.
- 2026-08-07 — T21 done: home's unread list (debug 340, release 329, 0 failures). The list is
  one SQL join, `EntryDao.observeUnreadListItems() → EntryListItem`, so a row never looks its
  feed up and no article body is loaded to draw a list. **Read entries drop out of that flow** —
  T27's "show read entries" adds the variant, it does not filter in the UI. **No paging library:**
  Room Flow + `LazyColumn` *is* the paging. `HomeUiState.nowMillis` comes from the injected
  `Clock`, which is what makes `RelativeTime` ("3h ago", English-only, no resources) assertable.
  The two empty states are told apart by `FeedRepository.observeSourceCount()`; each wants an
  action button that only T23 (add source) and T27 (show read) can wire.
