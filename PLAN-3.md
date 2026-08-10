# PLAN-3.md — Perch v0.3, the issue-tracker pass

**This is the active plan.** `PLAN.md` (T01–T32, v0.1) and `PLAN-2.md` (U01–U16, v0.2) are
complete, frozen, and history only — do not reopen a box in either. Same rules as before,
restated because they still bind:

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box unless
its Done-condition literally passed in this session. Failure → 2 attempts max, then rewrite
the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.
Use the cheapest rung that actually answers the question.

**TDD is the method, not a suggestion.** Failing test first (`RED`), minimum code to pass
(`GREEN`), then tidy (`REFACTOR`). A commit whose diff adds production code but no test is
a defect — reopen the box.

**v0.2 is installed on a real phone.** Every schema change ships a real Room `Migration`
and a matching `app/schemas/N.json`. `PerchDatabaseMigrationTest` already fails the build
if you forget. **Never `fallbackToDestructiveMigration()`** — that is someone's read state.

---

## §0 — Decisions for v0.3 (authoritative; do not re-derive)

PLAN-2 §0 still holds in full. These are the deltas, and where they contradict PLAN-2 §0,
SPEC.md or DESIGN.md, **this section wins** and the task that touches it updates the older
doc in the same commit.

**Every task in this plan is a GitHub issue.** The issue body is part of the task: read it
with `gh issue view N` before you start — several carry a diagnosis, a trap, or acceptance
criteria that this file does not repeat. A task is not done until its issue is closed.

**No bug is fixed until it is first reproduced by a failing test.** If a bug cannot be
reproduced, the task does **not** get a speculative fix. Write down in NOTES.md exactly
what was tried, comment the finding on the issue, mark the box
`- [BLOCKED: cannot reproduce — …]` and move on. A guessed fix with no test is worse than
an open issue, because it looks closed.

**Clocks have zones.** `Clock.systemUTC()` is a bug anywhere a *calendar* boundary is
computed — "today" is the reader's today. The app's injected clock is the device's zoned
clock (V02); a test that cares about a day boundary pins its own zone explicitly and never
inherits the JVM default.

**"Unread" is dead as a user-facing word.** The first destination is **Feed** (V05). Read
entries can be kept in it by a setting, so "unread" was already a lie half the time.
Identifiers, routes and test tags may keep whatever name they have — this is a language
decision about what a human reads, not a refactor.

**Folder order is alphabetical, case-insensitive, with Uncategorized always last** (V06).
This replaces PLAN-2 §0's "folder order is user-controlled (`sortIndex`)": no reorder UI
was ever built, so `sortIndex` in practice means creation order, which is not an order
anybody asked for. The column stays (OPML and profile round-trips carry it), it just stops
deciding the display order.

**A missing thumbnail is a rendered state, not an absent one** (V13 of PLAN-2 §0 said this;
v0.3 makes it look like it). The placeholder is a filled, low-contrast surface carrying the
brand mark, monochrome, visually quiet — it must not read as "still loading". One
placeholder, everywhere a thumbnail can be missing.

**Release notes are a deliverable, not a commit-log dump** (V15). Every release page leads
with what a reader gets, in their language: what is new, what was fixed, anything they must
do when installing. The template lives in the repo so no release re-invents it.

---

## Phase 0 — A suite you can trust

- [x] **V01 — The full suite is green on every ordering, not a lucky one. Issue #1.**
      `gh issue view 1` first: the diagnosis (a `runTest` billing an earlier test's leaked
      coroutine to whoever runs next), the leading suspect (`SettingsStore.create` builds a
      `CoroutineScope` nothing ever cancels, `data/settings/SettingsStore.kt:101`), and the
      note that **the named victim — `ArticleTextRepositoryTest`, `WorkSchedulerTest` — is
      never the culprit**, so do not "fix" it.
      **Bounded, in the foreground, no background runs.** Session #1 of this plan ended
      with "standing by for the flake hunt to complete" and produced no commit — a hunt with
      no bound does not fit in a session. So:
      1. **At most three** full `./gradlew test` runs to try to reproduce, each in the
         foreground, each copying `app/build/test-results/testDebugUnitTest/*.xml` aside
         first (the runs so far overwrote each other's, which is why the real thrower has
         never been seen). Session #1 left a probe in place that does the seeing:
         `app/src/test/java/dev/mkiros/perch/LoudUncaughtHandler.kt`, registered through
         `app/src/test/resources/META-INF/services/`, which prints every uncaught coroutine
         exception with its stack. Keep it for the hunt; delete it in the same commit once
         the fix lands, or keep it deliberately and say why.
      2. **Then fix the invariant whether or not it reproduced.** The leak does not need the
         flake to prove it: `SettingsStore.create` builds a scope with no owner and nothing
         cancels it, and *that* is directly testable — a test asserting the container's
         scope is cancelled on `close()` fails today because there is no `close()`. Reaching
         for the invariant is the cheap rung here; the repro is a bonus, not the gate.
      - Done: the fix is a scope with a lifecycle (a `close()` `AppContainer` calls, or a
        caller-owned scope) plus a test that fails without it; **three consecutive
        `./gradlew test` runs are green** and both named flakes are among the passes; if the
        flake reproduced, the real thrower's stack is pasted into the issue. Paste the three
        `BUILD SUCCESSFUL` lines with their test counts into the commit message.
      - Rung: unit

## Phase 1 — The three bugs you can feel

- [x] **V02 — "No articles" after ~18:00 Central: the clock has no zone. TDD. Issue #9.**
      `gh issue view 9`. `TimeFilter.since(clock)` opens *Today* at midnight **in
      `clock.zone`** — correct — but `di/AppContainer.kt` injects `Clock.systemUTC()`, whose
      zone is UTC. West of Greenwich, once local time passes UTC midnight (19:00 CDT /
      18:00 CST), "Today" opens at a moment *later than everything published today*, so the
      Feed empties. That matches the report exactly ("usually around 7 or 8 p.m. Central").
      RED first, and make it a test that would have caught this: a `HomeViewModel`-level (or
      `TimeFilter`-level, plus one wiring assertion on the container) test with the clock
      fixed at 2026-08-09T01:30Z **and the zone `America/Chicago`**, seeded with an entry
      published 2026-08-09T14:00Z-minus-a-day … i.e. an entry the reader published *this
      Chicago day*, asserting it survives the Today filter. It must fail on `systemUTC()`.
      Then audit every other `Clock.systemUTC()` default (`DateParser`, repositories, the
      container) and decide each one explicitly: parsing a feed date is zoneless and may
      stay UTC; anything feeding a calendar boundary or a "3 hours ago" label is the
      device's zone. Note the decision in a comment where it is non-obvious.
      - Done: the new test fails before the change and passes after (paste both lines);
        `RelativeTimeTest` and `HomeTimeRangeTest` still green; `./gradlew test` green;
        issue #9 closed with the diagnosis.
      - Rung: unit

- [x] **V03 — Pull-to-refresh works on an empty list. TDD. Issue #6.**
      `gh issue view 6`. `PullToRefreshBox` drops a swipe its child does not scroll, and the
      empty state is not scrollable — so the one gesture a reader reaches for on a fresh
      install is inert, on Feed, To-Read and Liked alike. Give each empty state a scrollable
      container (a `LazyColumn` with one `fillParentMaxSize` item, or `verticalScroll` on a
      `fillMaxSize` box).
      The test trap is in the issue: a swipe test that passes *without* the fix is asserting
      the wrong thing. Write the test, watch it fail, then fix.
      - Done: a test per destination (Feed, To-Read, Liked) that swipes the empty state and
        asserts a refresh was requested, each demonstrated RED first; `./gradlew test`
        green; issue #6 closed.
      - Rung: unit

- [x] **V04 — One window-inset contract, applied once. TDD + screenshot. Issue #3.**
      `gh issue view 3`. Nothing in the app handles insets — `grep -rn "WindowInsets\|
      safeDrawing\|statusBars\|systemBars" app/src/main` is empty — and the confirmed
      symptom is the image viewer's close affordance sitting under the status bar, because
      the viewer is deliberately a sibling of the article's `Scaffold` (PLAN-2 U12) and so
      bypasses the `Scaffold`'s inset handling entirely.
      Decide the contract once in `ui/nav/PerchNavHost.kt` — which surface consumes what,
      and which overlays opt in — and apply it there rather than sprinkling
      `.statusBarsPadding()` per screen. Cover at minimum: the viewer's close/actions, the
      bottom bar against a gesture handle, the drawer sheet, and the home top bar.
      **Robolectric's default device has no cutout**, so a test rendering only the default
      profile proves nothing: configure a device with a status-bar/cutout inset (qualifier
      or `RuntimeEnvironment`), or assert the composable's resolved padding directly.
      - Done: a test that fails on the current tree — the viewer's close button's top edge
        is above the status-bar inset — and passes after; one screenshot of the viewer under
        a cutout profile in `build/perch-screenshots/`; `./gradlew test` green; issue #3
        closed.
      - Rung: screenshot

## Phase 2 — The list, as it should read

- [x] **V05 — "Unread" becomes "Feed" everywhere a human reads it. TDD. Issue #12.**
      `gh issue view 12`. The reader's reason is exact: Settings can keep read entries in
      the list, at which point "Unread" names something the list is not. "Feed" is true
      under both settings.
      It is a smaller sweep than it sounds — the bottom-bar tab is **already** "Feed"
      (`strings.xml:141 tab_feed`, `ui/nav/PerchBottomBar.kt:36`). Two user-visible strings
      carry the word: `strings.xml:9 home_title_unread` = "Unread", the top-bar title
      (`ui/home/HomeScreen.kt:317`), and `strings.xml:53 drawer_all_unread` = "All unread",
      the drawer's top item (`HomeScreen.kt:720`) — which scopes the Feed to *everything*,
      so it should read "All sources" or similar rather than promising unread-only.
      Rename the resource keys too, so the next reader of `strings.xml` isn't misled.
      Three assertions pin the literal and move with it: `maestro/regression.yaml:36`
      (`text: "Unread"` under `id: "home:title"`), `HomeScreenTest.kt:251` and `:378`
      (`assertTextEquals("Unread")`), `PerchNavHostTest.kt:91`
      (`onNodeWithText("Unread")`). Leave identifiers alone —
      `HomeTestTags.TITLE = "home:title"` stays, and the doc-comment and DAO uses of the
      word ("unread count") are correct English about a real flag.
      - Done: no user-visible "Unread"/"All unread" string remains (say in the commit which
        two keys changed and to what); `./gradlew test` green with the three assertions
        updated; `maestro/regression.yaml` still matches the app; issue #12 closed.
      - Rung: unit

- [x] **V06 — Folders sort alphabetically, Uncategorized last. TDD. Issue #11.**
      `gh issue view 11` and §0 above. Today's order is `sortIndex ASC` with `name` only as
      a tie-break, and `sortIndex` is append-on-create (`FolderDao.kt:47` takes
      `MAX(sortIndex)+1`), so in practice it is creation order — and nothing in the UI
      reorders folders, so nothing conflicts with this change.
      The clause is stated in **three** places and they must not be able to disagree, since
      the drawer and the home section headers read different ones: `FolderDao.kt:27`
      (`observeAll`), `FolderDao.kt:32` (the non-Flow variant), and `EntryDao.kt:53` (the
      `LIST_ITEMS` const that sections the list). All three currently read
      `ORDER BY (id = 1) ASC, sortIndex ASC, name COLLATE NOCASE ASC` — drop the `sortIndex`
      term, keeping `(id = 1) ASC` first so Uncategorized stays pinned last. Do it **in
      SQL**, never re-sorted in Kotlin afterwards.
      Keep the `sortIndex` column: OPML and profile round-trips carry it. Update
      `FolderEntity.kt:22`'s and `FolderRepository.kt:52`'s comments ("the user reorders from
      there" stops being true) and PLAN-2 §0's "folder order is user-controlled" sentence in
      the same commit — a one-line pointer to §0 of this plan is enough.
      `COLLATE NOCASE` is ASCII-only in SQLite: `Émacs` will not case-fold. Decide whether
      that is acceptable, say so in a comment, and test the case you chose.
      - Done: a DAO test seeding folders out of order (mixed case, one leading digit,
        Uncategorized created first) asserting the exact returned order, RED first; a UI or
        ViewModel test asserting the drawer's sections and headers agree; `./gradlew test`
        green; issue #11 closed.
      - Rung: unit

- [x] **V07 — One quiet placeholder for a missing thumbnail. TDD + screenshot. Issue #13.**
      `gh issue view 13` — the reader's words: the empty square "looks like it's still
      trying to load the thumbnail", and what is wanted is a normalised, low-contrast filled
      square carrying the app's own mark, monochrome, that "goes to the back of your mind".
      All three states already funnel into one composable — `EntryRow.kt:175`'s
      `Placeholder`, reached from `url == null` (`:153`), Coil's `loading` (`:161`) and its
      `error` (`:162`) — and it draws *only* a hairline `outlineVariant` border around
      nothing, which is exactly why it reads as a pending image. Fill it: a low-contrast
      surface plus the brand mark, monochrome, centred at a fraction of the square.
      The mark already exists as `PerchMarkVector` (`ui/theme/Brand.kt:142`, cropped to the
      48×48 ink box) and as the `PerchMark` composable (`ui/brand/PerchBrandMark.kt:31`) —
      reuse one of those, tinted to a single role colour; do not re-embed the launcher
      asset or restate the path data. Square and corner come from `Dimens.thumbnail` /
      `Dimens.thumbnailCorner` (`ui/theme/Dimens.kt:61`). Keep the
      `EntryRowTestTags.THUMBNAIL_PLACEHOLDER` tag so existing assertions still find it, and
      check it holds up in both light and dark. Colours and dimensions come from
      `ui/theme/` roles — the standing grep gate (no `Color(0x`, no `N.dp`/`N.sp` outside
      `ui/theme/`) applies.
      Distinguish the three states honestly: *loading* may keep a subtle shimmer or stay
      plain, but *absent* and *failed* must be the finished placeholder, not a loading
      state. PLAN-2 U08's rule still holds — the 96dp square is always reserved, so nothing
      reflows when an image arrives.
      - Done: a test asserting the placeholder is drawn for absent/failed (PLAN-2 U08's Coil
        interceptor recipe in NOTES.md gives you all three states offline); screenshots of a
        list with mixed thumbnails in **both** light and dark under
        `build/perch-screenshots/`, critiqued against DESIGN.md (max 2 iterations,
        residuals to NOTES.md); `./gradlew test` green; issue #13 closed.
      - Rung: screenshot

## Phase 3 — Reading

- [ ] **V08 — Tapping the blog's name opens that blog's articles. TDD + screenshot.
      Issue #10.** `gh issue view 10`. On the article screen the source name at the top is
      inert; every other reader treats it as the way into that source's list. Perch already
      has the destination — the drawer scopes the Feed to a single source — so this is
      wiring an affordance to an existing state, not a new screen.
      Two facts shape the work. **(a) The name is not its own element**: it is folded into
      one uppercased byline string, `SOURCE · AUTHOR · 3 AUG 2026`, built at
      `ui/article/ArticleViewModel.kt:237` and drawn as a single `Text` at
      `ui/article/ArticleScreen.kt:279`. `ArticleUiState.Loaded` already carries `source`
      separately (`ArticleViewModel.kt:90`), so split the byline into segments and make only
      the source segment interactive — do not make the whole line tappable, the date is not
      a link. **(b) The scoped list is state, not a route**: `HomeScope.Source(feedId)`
      (`HomeViewModel.kt:90`) set by `selectSource(feedId)` (`:381`), while `Routes.FEED`
      (`ui/nav/PerchNavHost.kt:50`) takes no argument. So either give the route an optional
      `feedId` argument or hoist the scope — pick one, say why in the commit, and make sure
      the tab's saved state (PLAN-2 §0: each tab keeps its own state across switches)
      survives whichever you pick.
      Make the name look tappable — a touch target ≥48dp, a ripple, `Role.Button` semantics
      — not a bare text that happens to have a click. Decide and write down the back
      behaviour: from the scoped list, Back returns to the article or to the unscoped Feed —
      pick one, state it in the commit, and make `BackChainTest` assert it, since PLAN-2 U12
      made that order a guarded contract.
      Also decide what the time filter does on arrival: a source you tap into and find empty
      because of "Today" is the same complaint as issue #9. Scoping to a source should
      widen, or the scoped header should say what is being hidden.
      - Done: a UI test tapping the source name and asserting the scoped list shows only
        that source's entries, RED first; a `BackChainTest` case for the new step; one
        screenshot of the scoped list; `./gradlew test` green; issue #10 closed.
      - Rung: screenshot

- [ ] **V09 — The extractor keeps a Squarespace page's tables. TDD. Issue #4.**
      `gh issue view 4` — it carries the diagnosis and the trap. `ArticleExtractor.assemble()`
      grows the winning subtree by a one-level sibling sweep keyed on text density, and
      Squarespace wraps every block in its own `sqs-block` div, so a table — mostly markup,
      no "substantial paragraph" — is dropped. On ZDI posts the table *is* the post.
      **The trap:** `fixtures/articles/zdi-*.html` are **feed bodies, not pages**; they
      exercise lowering, not extraction. This needs a real ZDI **page** fixture saved into
      `fixtures/articles/` (fetch it once with curl; keep it in the repo like the others).
      Make a `<table>` survive on its own merits — an extra `keep` predicate or a table-aware
      score — without resurrecting layout/nav tables, which `ArticleLowering` already treats
      as `CHROME`.
      - Done: the new page fixture is committed; a test asserting the recovered article
        contains the table with its cell count intact, RED first; `FeedCorpusTest` green and
        **unweakened**; the table gate (PLAN-2 U15 gate 6b) still passes; `./gradlew test`
        green; issue #4 closed.
      - Rung: unit

## Phase 4 — Rules you can see

- [ ] **V10 — An unavailable folder header says so. TDD + screenshot. Issue #5.**
      `gh issue view 5`. Mid-*source* selection, tapping a folder header does nothing: the
      selection is deliberately homogeneous (sources or folders, never both), and
      Uncategorized refuses selection outright. The rule is right and its tests
      (`DrawerMultiSelectTest.kt:158-172`) must stay exactly as they are — what is missing is
      the affordance. Render the unavailable state (dim the row, drop its ripple/`onClick`,
      and give it a `disabled` semantics so it is honest to accessibility too) while
      `selection is DrawerSelection.Sources`, and the same for Uncategorized during a folder
      selection.
      Do **not** relax the homogeneity rule to "fix" this.
      - Done: the existing behavioural assertions unchanged and green; a new test asserting
        the disabled state (semantics + no click) during a source selection; one screenshot
        of the drawer mid-source-selection; `./gradlew test` green; issue #5 closed.
      - Rung: screenshot

- [ ] **V11 — Three cosmetic residuals. Screenshot. Issue #7.**
      `gh issue view 7` — all three verifiable from Robolectric screenshots, all "render it
      correctly", grouped because one session can reasonably take all three.
      1. **A rule between the code gutter and the code.** The gutter is pinned outside the
         `horizontalScroll` by design (PLAN-2 U11) and a long line scrolls to within ~12dp of
         the numbers. Separate them without breaking the pinning or the `DisableSelection`.
      2. **An edge affordance on a wide table.** A table wider than the viewport scrolls with
         nothing saying so. Remember U11a: inside a `horizontalScroll` a `fillMaxWidth`
         divider measures to 0 — a fade/shadow must be positioned like the rules are, at the
         summed column width or over the viewport, not "across the table".
      3. **The themed launcher icon's counter closes up at 48dp.** The path is stated three
         times — `ui/theme/Brand.kt` and both launcher VectorDrawables — and
         `LauncherIconTest` asserts all three agree, so **fix all three together**.
      Budget: max 2 critique iterations across the three; anything left goes to NOTES.md.
      - Done: one screenshot per item under `build/perch-screenshots/`, critiqued against
        DESIGN.md; `LauncherIconTest` green; `./gradlew test` green; issue #7 closed.
      - Rung: screenshot

## Phase 5 — Ship it

- [ ] **V12 — Live gate 1 means something specific again. Issue #8.**
      `gh issue view 8`. The gate demands 38 of 42 sources pull and exactly 38 do, so a
      transient outage anywhere reads as a regression, and four sources are permanently out:
      `danluu.com` and `projectzero.google` bust SPEC §6's 8 MiB cap, `research.nccgroup.com`
      has no feed at all, `rachelbythebay.com` times out from this network.
      Split the two failure modes: a **named exclusion list** in the test (each entry with a
      one-line reason) that the gate reports rather than silently spends budget on, and a
      hard gate over the sources that *should* work. A newly-dead source then surfaces as a
      name, not as `37/42`. Decide the 8 MiB cap explicitly — either SPEC §6's number is
      wrong for real feeds (raise it, in SPEC.md, with the measured sizes) or those two are
      out of scope (say so in the exclusion list) — and drop `research.nccgroup.com` from the
      reading list, since it has no feed and never will pull.
      **Do not lower the floor to 37.** That trades the signal away.
      - Done: `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`
        green, printing the exclusion list with reasons and the healthy-source score, both
        pasted into the commit message; the default no-network `./gradlew test` still green;
        issue #8 closed.
      - Rung: unit

- [ ] **V13 — The v0.1.0 → v0.2.0 bridge, executed and written down. Issue #2.**
      `gh issue view 2` — it has the full path and the acceptance criteria. v0.1.0 shipped
      debug-signed, so the release key cannot update it in place and an uninstall wipes the
      reader's Room database, which is exactly what PLAN-2 U02 exists to prevent. This is a
      one-time break; v0.2.0 onward updates normally.
      Execute the path once on the emulator (`scripts/device.sh`): install the released
      `perch-0.1.0-debug.apk` from the v0.1.0 release, use it enough to have read state,
      install a **debug-signed v0.2.x** over it (same debug key → migrations 1→5 run), export
      the profile via Settings, pull the file off the device, uninstall, install the
      release-signed APK, import, and verify the read/liked/saved flags survived. The unknown
      the issue names is step 2: v0.1.0 predates U14, so the *debug v0.2* build is what
      carries the export — confirm it does.
      Then document it: README.md and the v0.2.0 release notes say plainly that v0.1.0 needs
      the bridge and v0.2.0 onward does not, and attach a clearly-labelled debug-signed
      `perch-0.2.0-debug.apk` to the v0.2.0 release as the bridge.
      If the emulator cannot carry this (it is the heaviest task in the plan), do not fake
      it: `- [BLOCKED: …]`, log what failed, and leave the documentation half done in the
      same commit — the writing is useful even if the run is not.
      - Done: the flags-survived check pasted into the commit message with the adb/device
        commands that produced it; README and release notes updated; the bridge APK attached
        (`gh release view v0.2.0 --json assets`); issue #2 closed.
      - Rung: maestro

- [ ] **V14 — Release notes are a template, not a memory. Issue #14.**
      `gh issue view 14`. The reader wants each release page to read like an announcement:
      what is new, what was fixed, what they must do — user-facing, easy to consume. v0.2.0's
      notes are close to right; the gap is that nothing in the repo makes the next one look
      like it.
      Write `docs/RELEASE-NOTES.md`: the template (headline sentence · Installing/upgrading ·
      New · Fixed · Known issues), the rules (a reader's language, no task IDs, no commit
      hashes, every "Fixed" line naming the symptom the reader saw and linking its issue),
      and a worked example. Add a short `scripts/release-notes.sh` that assembles the *draft*
      — the closed issues since the last tag, grouped by label, with their titles — so the
      writer starts from the list and not from `git log`. It drafts; a human-readable pass is
      still required, and the script should say so in its output.
      - Done: `docs/RELEASE-NOTES.md` committed; `scripts/release-notes.sh v0.2.0` prints a
        usable draft (paste its first lines into the commit message); README links the
        releases page; issue #14 closed.
      - Rung: build

- [ ] **V15 — Live acceptance v3.** Re-run PLAN-2 U15's gates plus this plan's additions
      against the live corpus, with the same `-Pperch.live=true` switch. Gates: (1) the
      reworked source gate from V12, with its exclusion list; (2) V02's day boundary asserted
      against a **non-UTC** zone across the live corpus — an entry published today in
      `America/Chicago` at 23:00 UTC is in Today's bucket at 19:30 CDT; (3) folder order
      alphabetical with Uncategorized last across the live folder set; (4) every table across
      the corpus still rectangular, now including the ZDI page path from V09; (5) thumbnail
      coverage per source unchanged or better than U15's table, with the V07 placeholder
      drawn for the rest; (6) paging: the Feed's first collection at All Time loads one page;
      (7) screenshots of the Feed (dark, ≥2 folder sections, mixed thumbnails including
      placeholders), the scoped source list from V08, the drawer mid-source-selection from
      V10, and the image viewer under a cutout profile from V04.
      Critique against DESIGN.md; max 2 iterations; residuals to NOTES.md.
      Then `./gradlew test assembleRelease` — **not `clean`**: it deletes
      `build/perch-screenshots/`, which is the evidence this box asks for.
      - Done: the live run green with every gate's count pasted into the commit message;
        the screenshots exist; the default no-network `./gradlew test` still green.
      - Rung: screenshot

- [ ] **V16 — Release v0.3.0.** Bump `versionCode` 4 / `versionName` `0.3.0` (both live atop
      `app/build.gradle.kts` and nowhere else). Build the **release-signed** APK with U02's
      key — `assembleRelease` runs `lintVitalRelease` where `assembleDebug` does not, so
      expect lint to have opinions. Update README.md with the fresh screenshots. Tag
      `v0.3.0`, push, and `gh release create v0.3.0` with notes written to
      `docs/RELEASE-NOTES.md` (V14) — an announcement, not a changelog: what is new, every
      bug fixed named by the symptom the reader reported, and the plain statement that
      v0.2.0 → v0.3.0 installs in place and keeps read state, likes and the to-read queue.
      Attach `perch-0.3.0.apk`. Prune NOTES.md back under 100 lines. Confirm every issue this
      plan touched is closed (`gh issue list --state open` should hold only what is genuinely
      still open, each with a comment saying why).
      - Done: `gh release view v0.3.0 --json assets` lists the APK; `apksigner verify
        --print-certs` on the released file prints U02's certificate digest
        (`61367c04…fce489`); `git status` clean and pushed; `grep -c '^- \[ \]' PLAN-3.md`
        returns 0.
      - Rung: build
