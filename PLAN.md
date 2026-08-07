# PLAN.md — Perch build plan

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box
unless its Done-condition literally passed in this session. Failure → 2 attempts max,
then rewrite the box as `- [BLOCKED: one-line diagnosis]` and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.
Use the cheapest rung that actually answers the question.

**TDD is the method, not a suggestion.** For every task marked `TDD`, the session
writes the failing test first, watches it fail (`RED`), implements the minimum to pass
(`GREEN`), then tidies (`REFACTOR`). A commit for a `TDD` task whose diff adds
production code but no test is a defect — reopen the box.

---

## Phase 0 — Environment

- [x] **T01 — Build toolchain (no emulator).** Install Temurin **JDK 17** (system Java is
      8 — `JAVA_HOME` must point at 17 for Gradle) and Android cmdline-tools to
      `~/Android/Sdk`. `yes | sdkmanager --licenses`. Install
      `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, `emulator`.
      Export `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`PATH` in `~/.bashrc` **and** write them
      into `local.properties`. Install Maestro to `~/.maestro/bin`.
      Persist every path/env quirk into CLAUDE.md §Environment.
      - Done: `java -version` reports 17; `sdkmanager --list_installed` shows
        platform-tools, android-35, build-tools 35.0.0; `maestro -v` prints a version.
      - Rung: build

- [x] **T02 — Project skeleton, green build.** Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin
      2.1.0, version catalog exactly per SPEC.md §2, single `:app` module, package
      `dev.mkiros.perch`, minSdk 26 / compileSdk 35, Compose enabled, Room+KSP wired,
      one trivial Compose screen, one trivial passing unit test, `.gitignore`.
      - Done: `./gradlew test assembleDebug` exits 0 from clean; APK exists at
        `app/build/outputs/apk/debug/app-debug.apk`.
      - Rung: build

- [x] **T03 — Emulator on the Windows side + WSL bridge.** `/dev/kvm` is absent and
      **cannot** be obtained (Windows 10 → no WSL nested virtualization). So the
      emulator runs on Windows, accelerated by WHPX, driven from WSL via
      `scripts/device.sh` — read that script's header before starting; it already
      encodes the whole bridge.
      Steps: `./scripts/device.sh check`; install the Windows SDK by unzipping
      `commandlinetools-win-*.zip` to `C:\Android\Sdk\cmdline-tools\latest`
      (drive it with `cmd.exe`/`powershell.exe` from WSL — no GUI, no Android Studio),
      then `sdkmanager.bat` for `platform-tools`, `emulator`,
      `system-images;android-35;google_apis;x86_64`; `avdmanager.bat create avd -n perch`;
      `./scripts/device.sh boot`.
      **Only ever touch the device through `scripts/device.sh`** — a WSL-side adb server
      and the Windows adb server will fight over the same emulator.
      Record the boot time and the accelerator in use (`emulator -accel-check`) in NOTES.md.
      - Done: `./scripts/device.sh check` exits 0 with `✔ WHPX enabled` and a booted
        device; `./scripts/device.sh install app/build/outputs/apk/debug/app-debug.apk`
        succeeds; `./scripts/device.sh screenshot /tmp/hello.png` prints a byte count
        (it self-verifies PNG magic and non-blankness).
      - **If this task BLOCKs:** survivable, keep going. Mark it `[BLOCKED: …]`, log it,
        and **continue to T04**. T01–T28 and T31 are verified by JVM/Robolectric tests
        with no device at all, and T29 captures screenshots on the JVM. Only T30
        (Maestro) hard-depends on this; if still blocked at T30, mark T30 BLOCKED and
        finish T31 so a working APK still ships.
      - **If WHPX is disabled** (the human hasn't done the NOTES.md ACTION REQUIRED
        yet): do not wait and do not retry in a loop — mark this task
        `[BLOCKED: WHPX disabled, awaiting host reboot]` and move to T04. A later
        session re-runs `check` and unblocks it.
      - Rung: screenshot

- [x] **T04 — Fixture harvest.** For each URL in `fixtures/feeds.txt`: `curl -L`
      with a browser-ish UA, auto-discover `<link rel="alternate">` for homepage URLs,
      save resolved raw bytes to `fixtures/snapshots/<slug>.xml`, and record
      `<slug> <resolved-url> <http-status> <bytes>` in `fixtures/manifest.tsv`.
      One retry on failure, then exclude and note it. Do not stall on dead feeds.
      - Done: `fixtures/manifest.tsv` has a row for all 42 inputs; ≥35 snapshots on
        disk, each non-empty and starting with `<?xml`, `<rss`, `<feed`, or `<rdf`;
        every exclusion has a one-line reason in NOTES.md.
      - Rung: build

## Phase 1 — Parsing (pure JVM, TDD, no Android)

- [x] **T05 — `DateParser`. TDD.** RFC-822 + all its broken variants, ISO-8601/RFC-3339,
      the junk formats present in the corpus. Fallback chain and clamping per SPEC.md §5.
      - Done: ≥20 table-driven cases green incl. missing leading zero, `UT`/`GMT`/`EST`,
        `Z` vs `+00:00`, millis, wrong weekday, empty string, future date clamped, and
        every distinct `pubDate`/`updated` shape found in `fixtures/snapshots/`.
      - Rung: unit

- [x] **T06 — `RssParser` (RSS 2.0/0.9x). TDD.** Channel + item extraction,
      `content:encoded`, `dc:date`, `dc:creator`, enclosures, GUID fallback chain.
      - Done: unit tests over ≥6 hand-written malformed-and-valid RSS documents green;
        never throws on truncated/mismatched-tag input.
      - Rung: unit

- [x] **T07 — `AtomParser` (Atom 1.0). TDD.** `link[rel=alternate]` selection,
      `content[type=html|xhtml|text]`, `author/name`, `published` vs `updated`.
      - Done: as T06, over Atom documents.
      - Rung: unit

- [ ] **T08 — `RdfParser` (RSS 1.0) + `FeedParser` dispatch. TDD.** Dispatch on root
      element/namespace only. `ParseResult.Failure` for HTML or non-feed XML.
      - Done: dispatch tests green (rss/atom/rdf/html/garbage/empty); a 5 MB random-bytes
        input returns Failure in < 2s without OOM.
      - Rung: unit

- [ ] **T09 — Corpus test (standing).** `FeedCorpusTest` iterates every file in
      `fixtures/snapshots/` and asserts: parses to Success, feed title non-blank,
      ≥1 entry, and for **every** entry — title non-blank, link absolute-http(s) or null,
      `publishedAt` in `[2000-01-01, now+24h]`, guid non-blank and unique within the feed.
      **This test stays green for the rest of the build; a later task may never weaken it.**
      - Done: `./gradlew :app:testDebugUnitTest --tests '*FeedCorpusTest*'` green over
        all snapshots.
      - Rung: unit

- [ ] **T10 — `HtmlSanitizer` + plain-text summary. TDD.** Allowlist per SPEC.md §5,
      relative→absolute URL resolution, tracking-pixel drop, entity decoding, title
      de-HTML-ing, ≤300-char snippet on a word boundary.
      - Done: ≥12 cases green incl. `<script>` stripped, `onclick` stripped,
        `javascript:` href dropped, relative `src` resolved, nested-quote entities,
        and a real `content:encoded` blob from the corpus.
      - Rung: unit

- [ ] **T11 — `FeedDiscovery`. TDD.** Homepage HTML → feed URL, priority order and
      path-guess fallback per SPEC.md §5.
      - Done: unit tests over the saved homepage HTML in `fixtures/homepages/` for
        **xania.org, hillelwayne.com, gwern.net** each resolve to the URL recorded in
        `fixtures/manifest.tsv` (all three use a different `<link>` attribute order —
        that is the point); **research.nccgroup.com** is the negative case — its homepage
        has no `<link rel=alternate>` and every path guess soft-404s to HTML, so
        discovery must return null rather than a bogus URL (T04 confirmed the site no
        longer publishes a feed); a feed URL passed directly is returned unchanged
        without a network call.
      - Rung: unit

## Phase 2 — Storage & sync (Robolectric, TDD, still no emulator)

- [ ] **T12 — Room schema + DAOs. TDD.** Entities/indices/converters exactly per
      SPEC.md §4; schema export on. `FeedDao`, `EntryDao` with Flow queries.
      - Done: Robolectric in-memory DB tests green: insert/query/cascade-delete,
        unique `(feedId, guid)` conflict is an upsert not a crash, `app/schemas/*.json`
        committed.
      - Rung: unit

- [ ] **T13 — Read state & unread counts. TDD.** Mark read/unread, mark-all-read in
      scope with undo, per-source + total unread counts as SQL `COUNT` Flows.
      - Done: Turbine tests assert counts update reactively; mark-all-read affects only
        the scoped feed; undo restores the exact prior set.
      - Rung: unit

- [ ] **T14 — HTTP layer + conditional GET. TDD (MockWebServer).** `FeedFetcher`
      returning `NotModified | Success(bytes, etag, lastModified, finalUrl) | Failure`.
      - Done: MockWebServer tests green: 200 stores ETag/Last-Modified; the follow-up
        request carries `If-None-Match`/`If-Modified-Since`; 304 → `NotModified` with
        **zero** body reads; 301→200 records the final URL; timeout, 404, 500, and a
        9 MiB body each map to `Failure` with a human-readable message.
      - Rung: unit

- [ ] **T15 — `FeedRepository.refresh()`. TDD.** fetch → parse → dedupe by guid →
      upsert → update feed health (`lastError`, `consecutiveFailures`, timestamps).
      Concurrency 4, per-feed isolation, 30-day read-entry retention.
      - Done: tests green: refreshing the same snapshot twice inserts **zero** new rows
        and preserves `isRead`; one failing feed does not abort the other three;
        `lastError` set on failure and cleared on the next success.
      - Rung: unit

- [ ] **T16 — Add / remove / rename source. TDD.** Paste-URL flow end to end:
      direct-feed vs discovery, duplicate rejection, cascade delete, rename semantics
      (`customTitle`).
      - Done: tests green for: direct feed URL, homepage needing discovery, duplicate
        URL rejected with a typed error, non-feed URL rejected with a typed error,
        remove cascades entries, rename does not touch parsed `title`.
      - Rung: unit

- [ ] **T17 — OPML import/export. TDD.** Per SPEC.md §9.
      - Done: round-trip test (export 42 sources → import → identical set) green;
        importing a real nested OPML flattens correctly; duplicates counted not
        duplicated; malformed OPML returns a typed error, never throws.
      - Rung: unit

- [ ] **T18 — `RefreshWorker` + scheduling. TDD (work-testing).** Periodic work per
      SPEC.md §7, `NetworkType.CONNECTED`, backoff, 6h floor after 5 failures.
      - Done: `TestListenableWorkerBuilder` runs the worker to `Result.success()`
        against a MockWebServer feed and the DB shows the new entries; interval change
        in settings re-enqueues with `UPDATE` and no duplicate work.
      - Rung: unit

## Phase 3 — UI (DESIGN.md is the spec)

- [ ] **T19 — Theme & design system.** `Color/Type/Theme/Dimens.kt` per DESIGN.md §2–4:
      dynamic colour on 31+, `#3F6E5A`-seeded fallback below, dark+light, 4dp token
      scale, edge-to-edge, adaptive launcher icon.
      - Done: `./gradlew assembleDebug` green; **zero** hardcoded `Color(0x…)`,
        `.dp` literals in feature packages (grep-checked), or `TODO(` in `ui/`.
      - Rung: build

- [ ] **T20 — App scaffold + navigation.** `MainActivity`, `PerchNavHost`, routes
      `home` / `article/{entryId}` / `settings`, drawer shell, `AppContainer` wired,
      ViewModels constructed from it.
      - Done: `assembleDebug` green; a Robolectric nav test asserts each route composes
        without crashing and back from `article` returns to `home`.
      - Rung: unit

- [ ] **T21 — Home: unified unread list.** `EntryRow` per DESIGN.md §5, newest first,
      paging via Flow, skeleton/empty states, scroll position preserved.
      - Done: Robolectric Compose tests assert row content (title/snippet/source·time)
        and ordering from a seeded DB; empty state renders "Add your first source" with
        zero sources and "You're all caught up" with zero unread.
      - Rung: unit

- [ ] **T22 — Source drawer + per-source filter.** Source list with unread counts,
      error `⚠` affordance, selecting a source filters the list and retitles the bar.
      - Done: Robolectric test: seed 3 feeds, select #2, assert only #2's entries are
        listed and the title is #2's display name; counts match the DB.
      - Rung: unit

- [ ] **T23 — Add-source sheet (UI).** Paste → resolve → confirm-then-commit per
      DESIGN.md §5, inline error, loading button state.
      - Done: Robolectric tests: valid feed URL adds a source; homepage URL is
        discovered then added; garbage URL shows the inline error and adds nothing.
      - Rung: unit

- [ ] **T24 — Remove (confirm) + rename (dialog).** Long-press affordance in the drawer.
      - Done: Robolectric tests: confirm dialog appears; cancel is a no-op; confirm
        removes the source and its entries; rename updates the drawer label only.
      - Rung: unit

- [ ] **T25 — Article screen + `RichText` renderer. TDD.** Sanitized HTML → Compose per
      DESIGN.md §8; open-in-browser (Custom Tab); mark-read on open; 680dp measure cap.
      - Done: `RichText` unit tests cover p/h2/h3/ul/ol/blockquote/pre/code/a/img/hr;
        Robolectric test asserts opening an entry flips `isRead` in the DB and that the
        overflow "Open in browser" fires an `ACTION_VIEW` intent with the entry link.
      - Rung: unit

- [ ] **T26 — Refresh UX + error/offline surfacing.** Pull-to-refresh, mark-all-read
      with undo snackbar, per-source error banner + retry, offline banner.
      - Done: Robolectric tests: pull-to-refresh calls the repository once; a failing
        feed shows `⚠` in the drawer while its cached entries stay listed; mark-all-read
        + undo restores the exact prior unread set.
      - Rung: unit

- [ ] **T27 — Settings screen.** OPML import/export via SAF, refresh interval, theme
      (Light/Dark/System), "show read entries", about + APK version.
      - Done: Robolectric tests: interval change persists to DataStore and re-enqueues
        work; theme toggle recomposes to the other scheme; export/import launchers fire
        the correct SAF intents.
      - Rung: unit

- [ ] **T28 — Debug seed data.** Bundle ~8 varied snapshots into
      `app/src/debug/assets/seed/` and pre-seed them on first run of the **debug** build
      only, so screenshots always show realistic content.
      - Done: `assembleDebug` green; a Robolectric test asserts the debug seeder inserts
        8 feeds and >50 entries; a `assembleRelease`-shaped source set contains no seeder.
      - Rung: build

## Phase 4 — Polish & ship

- [ ] **T29 — Consolidated design-polish pass (screenshot-driven).** **No device
      needed.** Render real pixels on the JVM with Robolectric native graphics:
      `@GraphicsMode(NATIVE)`, `testOptions.unitTests.isIncludeAndroidResources = true`,
      `composeTestRule.onRoot().captureToImage()` → PNG in `screenshots/`. Seconds per
      capture, deterministic, and it works whether or not T03 succeeded.
      Capture **home (dark), home (light), drawer, article, add-source sheet, empty
      state** — six screenshots, seeded from the T28 fixture data. Then *look at them*
      and critique each against the DESIGN.md §9 checklist, fix, re-capture.
      **Max 2 critique-fix iterations**, then log residual items to NOTES.md and check
      the box. If the Robolectric capture path fails twice, fall back to
      `./scripts/device.sh screenshot` (needs T03) rather than burning a third attempt.
      - Done: six PNGs in `screenshots/`, each > 10 KB and visually inspected; every
        §9 line either passes or has a one-line residual note in NOTES.md.
      - Rung: screenshot (JVM-rendered)

- [ ] **T30 — Maestro regression flow.** Requires T03 (this is the one genuinely
      device-bound task). `maestro/regression.yaml`: add source → refresh → open entry
      (read) → back → filter by source → mark all read → remove source (confirm) →
      OPML export.
      Maestro must reach the **Windows** adb server. Preferred: install Maestro on
      Windows and run it via interop against a staged copy of the flow
      (`./scripts/device.sh stage maestro/`). Fallback: WSL Maestro pointed at the
      Windows adb server over TCP (`adb.exe -a -P 5037 nodaemon server` on Windows,
      then `ADB_SERVER_SOCKET=tcp:172.18.144.1:5037`). Record which one worked in
      NOTES.md so no future session re-derives it.
      - Done: the Maestro run exits 0 on a clean install of the current APK.
      - Rung: maestro

- [ ] **T31 — Ship.** Remove `fallbackToDestructiveMigration()` and add a real migration
      baseline. `./gradlew clean test assembleDebug`. Write the absolute APK path, its
      size, the build timestamp, and install instructions to NOTES.md. Final
      `git log --oneline` sanity check.
      - Done: clean build green, `app-debug.apk` exists, APK path in NOTES.md,
        `grep -c '^- \[ \]' PLAN.md` returns 0.
      - Rung: build
