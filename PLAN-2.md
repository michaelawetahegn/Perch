# PLAN-2.md — Perch v0.2, the daily-driver pass

**This is the active plan.** `PLAN.md` (T01–T32) is complete, frozen, and history only —
do not reopen a box in it. Same rules as before, restated because they still bind:

Ordered. **Do the single next unchecked `[ ]` task, then stop.** Never check a box
unless its Done-condition literally passed in this session. Failure → 2 attempts max,
then rewrite the box as `- [BLOCKED: one-line diagnosis]`, log it to NOTES.md, and move on.

Rungs, cheapest first: **unit** < **build** < **maestro** < **screenshot**.
Use the cheapest rung that actually answers the question.

**TDD is the method, not a suggestion.** Failing test first (`RED`), minimum code to pass
(`GREEN`), then tidy (`REFACTOR`). A `TDD` commit whose diff adds production code but no
test is a defect — reopen the box.

**v0.1 is installed on a real phone.** From U02 onward, every schema change ships a real
Room `Migration` and a matching `app/schemas/N.json`. `PerchDatabaseMigrationTest` (T31)
already fails the build if you forget. **Never `fallbackToDestructiveMigration()`** — that
is someone's read state.

---

## §0 — Decisions for v0.2 (authoritative; do not re-derive)

These extend SPEC.md and DESIGN.md. Where they conflict with v1 text, **this section
wins** and the task that touches it updates the older doc in the same commit.

**Folders.** Every source belongs to exactly one folder. A source added without a folder
goes to the built-in folder **"Uncategorized"** (id 1, undeletable, unrenameable).
Deleting a folder moves its sources to Uncategorized — it never deletes sources.
Folder order is user-controlled (`sortIndex`); Uncategorized sorts last.

**The two grouping dimensions are not the same dimension.** Time is a *filter*, folder is
a *section*. Home has a chip row — **Today · This Week · This Month · All** (default
Today) — which filters by `publishedAt`, and the surviving entries are sectioned under
folder headers in folder order, exactly like the Feedly reference. When the drawer scopes
to a single source or folder, the section headers collapse away (there is only one).
"Today" means *since local midnight*, not "the last 24 h". An empty bucket renders the
empty state with a "Show this week instead" affordance, never a blank screen.

**Read later, liked, unread.** Three independent flags on an entry, all local, all
surviving a reinstall via U02 + U14:
- `isSaved` — *Read later*. A queue. Cleared by the user, not by reading.
- `isStarred` — *Liked*. Permanent. (Column already exists, unused since T12.)
- `isRead` — already exists; v0.2 adds an explicit **Mark unread** that also nulls `readAt`.
Saved and Liked each get a drawer destination and are **exempt from the time filter** —
a to-read list that hides last month's articles is not a to-read list.

**Thumbnails.** `entries.imageUrl` is populated at parse time from the first of:
`<media:thumbnail>` → `<media:content medium="image">` → `<enclosure type="image/*">` →
the first `<img>` in the sanitized body whose dimensions aren't tracking-pixel-sized →
`og:image` from the entry page (only if the page is already being fetched by U10; never
fetch a page just for a thumbnail). No image is a first-class state, not a failure — the
row draws the placeholder from the reference, not a broken-image glyph.

**Full text.** Two different feeds look like the same bug and neither is one:
- **No body at all** — fabiensanglard.net ships `<title>`+`<link>`+`<pubDate>`, 144 items,
  nothing else. "This entry has no text in the feed" is *accurate*.
- **An excerpt standing in for the body** — gpuopen.com ships a 194-character
  `<description>` teaser and **no `content:encoded`**. The article renders, it is just the
  blurb. This is the more common shape and the more confusing one, because it looks like
  truncation rather than absence.

In both cases the text is on the page, not in the feed, so Perch goes and gets it. **The
goal is that visiting the site is never required to read an article.** See U10.

**Top-level navigation is a bottom bar, not the drawer.** Three destinations —
**Feed · To-Read · Liked** — in a Material 3 `NavigationBar`. They are peers the user
switches between constantly, and a peer switch that costs a drawer open is a peer switch
that doesn't happen. The drawer keeps what it is actually good at: scoping the Feed to a
folder or a source. Rules that hold regardless of visual treatment:
- The bar is visible on all three list destinations and **hidden on the article screen**.
- Each tab keeps its own scroll position and its own state across switches (nav
  `saveState`/`restoreState`), and survives process death.
- The time filter (chips) belongs to **Feed only** — To-Read and Liked ignore it, per the
  read-later rule above.
- System back from To-Read or Liked returns to Feed; back from Feed exits.

**The UI's visual decisions are the implementing session's to make.** §0 fixes *behaviour
and information architecture*; icons, labels, badge counts, ordering, empty-state
illustration, and the exact bottom-bar treatment are the session's call, judged against
DESIGN.md and the screenshot critique — do not come back for permission on a visual choice.

**Signing.** From U02, every APK is signed with one stable key so a sideloaded update
installs *over* the previous one and the database survives. Losing that key means every
future install is a wipe, so it lives outside the repo and NOTES.md says where.

**Fonts.** DESIGN.md §3's "no custom fonts bundled" rule is amended by U11 for exactly one
family: a monospace face for code. Consolas is not redistributable; the bundled face is
its closest OFL-licensed relative. Body and furniture stay platform families.

---

## Phase 0 — Save the working state

- [x] **U01 — Publish v0.1.0 to GitHub.** The v0.1 build works and is unbacked-up. Fix
      that first, before any code changes. Remote: `git@github.com:michaelawetahegn/Perch.git`
      (`gh` is already authenticated as `michaelawetahegn`; SSH to github.com works).
      1. **Audit what is about to become public.** `git ls-files` is 22 MB / ~200 files.
         Confirm no keystore, no `local.properties`, no `.env`, no token, no API key, no
         absolute `/home/michael` path baked into a tracked source file, and nothing
         under `fixtures/` that isn't public web content. Grep the *history* too
         (`git log -p -S` for likely secret markers) — a rewrite is cheap now and
         impossible after the push.
      2. Write a `README.md`: what Perch is, the screenshots from `screenshots/`, how to
         build (JDK 17, `./gradlew assembleDebug`), and the v0.1 feature list. Add a
         `LICENSE` (MIT).
      3. Create the repo if it does not exist (`gh repo create`), push `main`, tag
         `v0.1.0` annotated, push the tag.
      4. `gh release create v0.1.0` with notes drawn from the PLAN.md phases, attaching
         `app/build/outputs/apk/debug/app-debug.apk` renamed `perch-0.1.0-debug.apk`.
         State plainly in the notes that it is **debug-signed** and that v0.2 will move to
         a stable release key.
      - Done: `gh release view v0.1.0 --json assets` lists the APK; `git ls-remote --tags`
        shows `v0.1.0`; `git status` is clean; the secret audit's commands and their empty
        output are pasted into the commit message.
      - Rung: build

- [x] **U02 — Stable signing key + upgrade-in-place.** Today's APK is signed by the
      auto-generated debug keystore. That works only by luck of it still existing on this
      machine; it is not a key you can ever rotate to, and it is not portable.
      Generate `~/.perch/perch-release.jks` (**outside the repo, 10000-day validity**) and
      `~/.perch/signing.properties`. Wire `signingConfigs` in `app/build.gradle.kts` to
      read that file **with a graceful fallback**: if it is absent, fall back to debug
      signing and print a warning, so a clean clone still builds. Add both paths to
      `.gitignore` defensively even though they live outside the tree.
      Move `versionCode`/`versionName` to a single place and bump to `2` / `0.2.0-dev`.
      Set an explicit `dataExtractionRules` / `fullBackupContent` so `allowBackup="true"`
      actually covers the Room DB and DataStore rather than defaulting.
      NOTES.md must record, in one line, that losing `~/.perch/perch-release.jks` makes
      every future install a data wipe.
      - Done: `./gradlew assembleRelease` produces a signed APK;
        `apksigner verify --print-certs` on it prints a certificate whose SHA-256 digest
        is pasted into the commit message; a second build at a higher `versionCode` prints
        the **same** digest (that identity is what makes the update install over the old
        app instead of demanding an uninstall); `./gradlew test` still green.
      - Rung: build

## Phase 1 — Schema, one migration per task

- [ ] **U03 — Folders: schema, DAO, repository. TDD.** `FolderEntity(id, name, sortIndex,
      createdAt)` with a unique index on `name`; `feeds.folderId` non-null FK
      `ON DELETE SET DEFAULT`-equivalent behaviour implemented in the DAO (Room can't
      express it — do the reassignment in a transaction). Migration **1→2** creates the
      table, seeds Uncategorized as id 1, adds the column defaulted to 1, and backfills.
      Repository: `createFolder`, `renameFolder`, `deleteFolder` (reassign, never cascade),
      `moveSource(feedId, folderId)`, `observeFolders()`, and unread counts **per folder**
      as a SQL `GROUP BY` — never summed in Kotlin (same trap as
      `observeUnreadCountsByFeed`: a fully-read folder is *absent* from the map, not 0).
      - Done: migration test 1→2 on a v1 DB populated with real rows keeps every feed and
        entry and lands them all in Uncategorized; `app/schemas/2.json` committed; DAO
        tests cover delete-reassigns and the unique-name conflict; `./gradlew test` green.
      - Rung: unit

- [ ] **U04 — Read later, liked, mark-unread: schema + repository. TDD.** Migration
      **2→3** adds `entries.isSaved` (default 0), `savedAt`, `starredAt`, plus indices on
      `isSaved` and `isStarred`. `isStarred` keeps its column and becomes *Liked* in the
      domain language. Repository: `setSaved`, `setLiked`, `setRead(entryId, read)` where
      `read=false` also nulls `readAt`, and `observeSaved()` / `observeLiked()` ordered by
      `savedAt`/`starredAt` descending.
      **Critical:** `EntryDao.upsertAll` matches on `(feedId, guid)` and preserves
      `isRead`/`readAt`/`isStarred` (NOTES.md, T12–T18). Extend that preservation to
      `isSaved`/`savedAt`/`starredAt` or a refresh silently empties the to-read list —
      write that regression test first, it is the whole point of this task.
      - Done: a test that saves + likes + marks-unread an entry, re-runs `upsertAll` with a
        fresh parse of the same feed, and asserts all three flags survived; migration test
        2→3; `app/schemas/3.json`; `./gradlew test` green.
      - Rung: unit

- [ ] **U05 — Thumbnail extraction. TDD.** Populate `entries.imageUrl` per §0's ordered
      fallback chain, in the parser/repository layer (not the UI). Reject tracking pixels
      and sub-64px images by declared dimensions, and resolve relative URLs against the
      entry link. No migration — the column exists since T12.
      **A missing image is a normal, designed state, not a failure.** Many of the 42 sources
      are text-only blogs that will never have one. Resolve `imageUrl` to `null` — never to
      a guessed, broken, or placeholder URL — and let U08's row draw the placeholder.
      - Done: unit tests for each rung of the fallback chain, plus one asserting a text-only
        entry yields `null` rather than a bad URL; a corpus test over the 39
        `fixtures/snapshots/` feeds asserting **≥60%** of entries resolve a thumbnail and
        printing the per-source percentage table, so sources that resolve 0% are visible and
        can be attributed to the feed rather than to us (paste the total into the commit
        message); no test weakened.
      - Rung: unit

## Phase 2 — The feed, redesigned

- [ ] **U06 — Folders in the drawer. TDD + screenshot.** The drawer scopes the Feed; it does
      **not** hold To-Read or Liked (those are bottom-bar destinations — see §0 and U09).
      Drawer becomes: All unread · hairline · folder sections (expandable, unread count on
      the header, sources nested beneath) · Add source · Settings. Long-press a source →
      rename / move to folder / remove. Folder header overflow → rename / delete.
      "New folder" from the Add-source sheet and from the drawer.
      Remember the T22 Robolectric traps: `compose.waitUntil` only advances the *virtual*
      clock (use `awaitInRealTime`), and an injected tap never reaches a node inside an
      opened drawer sheet — drive it with `performSemanticsAction`.
      - Done: tests cover create/rename/delete folder and move-source, each asserting
        against the DB not the UI text; one dark-theme screenshot of the open drawer with
        ≥2 folders; `./gradlew test` green.
      - Rung: screenshot

- [ ] **U07 — Time filter + folder sections on home. TDD.** Implement §0's two dimensions:
      a chip row (Today · This Week · This Month · All, default **Today**), entries
      sectioned under folder headers. The bucket boundary is local midnight via
      `java.time` with an injectable `Clock` — a test that can't pin "today" is not a test.
      The selected chip persists in DataStore across process death. Saved and Liked
      destinations ignore the filter entirely.
      - Done: tests with a fixed `Clock` prove an entry published at 23:59 yesterday is out
        of Today and in This Week; section order follows folder `sortIndex`; empty-bucket
        state offers the widen affordance; `./gradlew test` green.
      - Rung: unit

- [ ] **U08 — The entry row, redesigned. TDD + screenshot.** Rebuild `EntryRow` to the
      reference at **`design/reference/feed-row-reference.jpg`** (a Feedly light-mode
      capture — build the **dark** equivalent, matching structure not colour): **title** (titleMedium, w600, ≤3 lines) on the left, **`Source / 5h`** metadata
      beneath it in `onSurfaceVariant`, and a **thumbnail on the right** — ~96dp, 12dp
      corners, `ContentScale.Crop`. **The placeholder is not an edge case — spec it as
      carefully as the image.** A row with no image, a row whose image is still loading, and
      a row whose image 404s or times out must all occupy the *identical* footprint and draw
      the same 1dp-outline placeholder, so the list never jitters, never collapses a row, and
      never shows a broken-image glyph. Coil's `error` and `fallback` slots both point at it,
      not just `placeholder`. (T25 collapses the whole figure on an image load error inside
      an *article* — that is right there and wrong here; do not copy it.) Section headers use the accent colour.
      Relative time is compact (`47min`, `5h`, `1d`, `3d`, then a date past 7 days).
      Drop the 2-line snippet from the row — the reference doesn't have it and the
      thumbnail is doing that work now; update DESIGN.md §5's diagram to match.
      All spacing/size tokens go through `ui/theme/Dimens.kt` — the standing grep gate
      (no `Color(0x`, `N.dp`, `N.sp` outside `ui/theme/`) still holds.
      - Done: dark screenshot of a populated list with a mix of image/no-image rows,
        critiqued against the reference (max 2 iterations, residuals to NOTES.md);
        row tests cover the ≤3-line clamp, the placeholder in all three of its cases
        (absent / loading / load-failed), and each relative-time band;
        `./gradlew test` green.
      - Rung: screenshot

- [ ] **U09 — To-Read and Liked: bottom bar + the actions that fill them. TDD + screenshot.**
      Add the §0 `NavigationBar` with **Feed · To-Read · Liked** and the two new
      destinations, both rendering the same `EntryRow` as Feed, sorted by `savedAt` /
      `starredAt` descending, each with its own empty state that says what the list is
      *for* rather than just that it is empty.
      Actions that populate them: row long-press sheet gains *Save for later*, *Like*,
      *Mark unread/read*, *Share*; the article top bar gains like + save toggles with
      filled/outlined state. Un-saving from To-Read animates the row out with an undo
      snackbar (reuse T26's).
      Update DESIGN.md §5's navigation diagram and SPEC.md §10's destination list in the
      same commit — both still describe a four-destination, drawer-only v1.
      Nav plumbing is the part that silently breaks: use one `NavHost` with
      `saveState`/`restoreState` and `launchSingleTop` so switching tabs does not stack
      duplicates, and confirm the bar does not appear over the article screen.
      - Done: Robolectric tests assert each action flips the right DB column and that the
        target list reflects it through the Flow; a test switches Feed → To-Read → Feed and
        asserts the Feed scroll position and selected time-filter chip survived; a test
        asserts the bar is absent on `article/{id}`; both empty states covered; one
        dark-theme screenshot of To-Read with the bar; `./gradlew test` green.
      - Rung: screenshot

## Phase 3 — The reading surface

- [ ] **U10 — Full text for feeds that don't ship it. TDD.** The single most important task
      in this plan: **reading an article must never require visiting the site.** Covers both
      §0 shapes — fabiensanglard.net (no body at all) and gpuopen.com (a 194-char
      `<description>` teaser with no `content:encoded`, which renders as a stub).
      New `data/extract/ArticleExtractor.kt`: a Readability-style scored extraction over
      jsoup (already a dependency — **do not add one**; if you conclude a library is
      genuinely necessary, justify it in NOTES.md per CLAUDE.md). Score candidate blocks by
      text density, comma count, and paragraph depth; penalise `nav|footer|sidebar|comment|
      share|related|promo` class/id patterns; take the top subtree, strip chrome, then hand
      it to the **existing** `HtmlSanitizer` → `ArticleLowering` pipeline so extracted
      articles are lowered by the same code as feed bodies and get zero special treatment
      downstream.
      **Trigger — a length threshold alone is not enough.** Extract on article open when
      any of: `contentHtml` is null or empty; its lowered prose is under ~1200 characters;
      the body ends in a truncation marker (`…`, `[…]`, `Read more`, `Continue reading`,
      `The post … appeared first on …`); or the item is RSS 2.0 with a `<description>` and
      no `<content:encoded>` (the gpuopen shape — that combination means "excerpt" far more
      often than it means "short post").
      **Always offer the manual override.** The article overflow gets **Load full article**,
      enabled whenever the body did not come from an extraction — the heuristic will be
      wrong sometimes and the user must never be stuck with a stub.
      Fetch through the existing `HttpClient` (SPEC §6 limits apply), write the result back
      to `contentHtml`, and render a progress state while it runs. Never block the list;
      never fetch on refresh (that would hammer 42 sites). If extraction fails or yields
      less prose than the feed already had, **keep what the feed had** — never replace text
      with less text — and fall back to today's "Read on the web".
      Harvest fixtures under `fixtures/articles/`: 5 pages from fabiensanglard.net, 5 from
      gpuopen.com, and 5 from other excerpt-only sources, covering at least one WordPress
      site, one static-site generator, and one heavily-scripted page.
      - Done: extractor unit tests over the 15 saved pages assert the extracted text
        contains a known **mid-article** sentence *and* a known **final** sentence (an
        extractor that truncates still passes a "contains" test), and **excludes** the site
        nav, footer, cookie banner, and related-posts block; the gpuopen cases assert the
        extracted body is ≥10× the length of the feed excerpt; lowering the extracted HTML
        yields 0 `Unsupported`; Robolectric proves that opening a content-less entry **and**
        an excerpt-only entry both populate `contentHtml` and render full paragraphs;
        `./gradlew test` green with no network.
      - Rung: unit

- [ ] **U11 — Code blocks: monospace face + syntax highlighting. TDD + screenshot.**
      Bundle one mono family under `app/src/main/res/font/` — Consolas cannot be
      redistributed, so use its closest OFL-licensed relative (JetBrains Mono or
      Inconsolata; state the choice and its licence in NOTES.md and drop the licence file
      in `app/src/main/assets/`). This is a deliberate, single exception to DESIGN.md §3's
      no-bundled-fonts rule — amend that line in the same commit.
      Highlighting: detect the language from `class="language-*"` / `class="lang-*"` on
      `<pre>`/`<code>` (preserve that attribute through `HtmlSanitizer` — check it isn't
      being stripped) with a heuristic sniffer as fallback. Support Kotlin, Java, C/C++,
      Python, JavaScript/TypeScript, Rust, Go, shell, XML/HTML, JSON, SQL, and a
      no-op passthrough for anything else. Tokenise to `AnnotatedString` with colours
      taken from **theme roles in `ui/theme/`**, working in both light and dark.
      **Line numbers.** Code blocks get a left gutter of right-aligned line numbers in the
      same mono face, dimmed to `onSurfaceVariant`, its width sized to the widest number so
      the code's left edge never shifts mid-block. Two details make or break it: the gutter
      is **pinned** — when the code scrolls horizontally the numbers stay put, or they slide
      out of view and the feature is worse than useless — and the numbers are **not part of
      the text**, so selecting or copying a block yields runnable code with no numbers in it.
      A one-line block may omit the gutter; that is the session's call.
      Highlighting and numbering are presentation only: `ArticleBlock.Code.text` stays
      verbatim, and code still never wraps and still scrolls horizontally.
      - Done: tokeniser tests per language including a string-containing-a-keyword and an
        unterminated-comment case (a highlighter that throws on malformed code is a crash
        in a reader); an unknown language renders unstyled, not blank; a test asserts the
        gutter numbers a 100+ line block correctly (alignment at the 9→10 and 99→100
        widths) and that the copied/selected text equals `Code.text` exactly, numbers
        excluded; light+dark screenshots of a `nullprogram.com` C post and a Kotlin post,
        one of them scrolled horizontally to show the gutter staying pinned;
        `./gradlew test` green.
      - Rung: screenshot

- [ ] **U12 — Tap-to-zoom image viewer. TDD + screenshot.** Tapping an image in an article
      opens a full-screen overlay: the image animates to fit the width, the article behind
      it fades to a scrim, and the user can **pinch-zoom, double-tap-to-zoom, and pan**.
      Dismissal must be effortless and must always work: back button, tap outside, and
      drag-down-to-dismiss (with the scrim fading proportionally to drag distance).
      **Read a reference implementation before writing this** — study `saket/telephoto`'s
      `ZoomableImage` for the behaviours that make or break it: zoom clamped to
      1×–~5× with rubber-banding past the bounds, pan bounded to the scaled image so it
      can't be flung off-screen, double-tap toggling between fit and ~2×, and drag-to-
      dismiss only engaging when already at 1× (otherwise panning a zoomed image fights the
      dismiss gesture — this is the single most common way this feature ships broken).
      Prefer implementing those behaviours directly with `TransformableState` + a bounded
      offset; adding the dependency needs the NOTES.md justification per CLAUDE.md.
      - Done: gesture tests via `performTouchInput` covering pinch past max (clamps),
        double-tap (toggles), pan at 1× (dismisses) vs pan at 3× (pans, does not dismiss),
        and back (closes, restoring scroll position); one screenshot of the zoomed state;
        `./gradlew test` green.
      - Rung: screenshot

## Phase 4 — Get your sources in and out

- [ ] **U13 — OPML with folders. TDD.** SPEC.md §9's "flat, no folders in v1" is now
      obsolete — amend it in this commit. Export nests each folder as a container
      `<outline text="AI/LLM" title="AI/LLM">` holding its sources' `type="rss"` outlines;
      Uncategorized's sources are written at top level (that is what other readers expect).
      Import reads arbitrary nesting: a container outline becomes a folder, creating it if
      absent, matching case-insensitively if present; nesting deeper than one level is
      flattened to the outermost folder name; sources still dedupe on `feedUrl`.
      The T-era round-trip test is the contract — extend it, don't replace it.
      - Done: round-trip test over the real 42-source manifest split across ≥3 folders
        reproduces folders *and* membership exactly; a fixture OPML exported by another
        reader (hand-write one with 2-deep nesting and mixed top-level feeds) imports
        without loss; import reports `n added / m duplicates / k invalid / f folders`;
        `./gradlew test` green.
      - Rung: unit

- [ ] **U14 — Profile backup & restore. TDD.** One file that carries a whole reading
      identity so a reinstall or a new phone is a 10-second setup instead of 42 paste
      operations. `perch-profile-YYYYMMDD.json` via SAF, versioned with a `schemaVersion`
      field, containing: folders (name, order), sources (feedUrl, siteUrl, customTitle,
      folder), and per-entry state keyed by `(feedUrl, guid)` — read, liked, saved, and
      their timestamps. Not entry bodies; this is state, not an archive.
      Restore **merges and is idempotent**: restoring twice equals restoring once, existing
      sources are not duplicated, and state for an entry not yet fetched is held in a
      pending table and applied when that entry next arrives (otherwise a restore-then-
      refresh loses everything it just restored — test that ordering explicitly).
      Settings gets Export profile / Import profile alongside the OPML actions.
      - Done: export → wipe DB → import → refresh → the same sources in the same folders
        and the same read/liked/saved entries; a restore run twice changes nothing the
        second time; an unknown future `schemaVersion` is refused with a clear message
        rather than half-applied; `./gradlew test` green.
      - Rung: unit

## Phase 5 — Ship it

- [ ] **U15 — Live acceptance v2.** Extend `acceptance/LiveAcceptanceTest` (still gated by
      `-Pperch.live=true`; `./gradlew test` must stay offline and deterministic). Keep the
      three v1 gates — **≥38/42 sources**, **≤2% `Unsupported`**, one-publication
      screenshots — and add:
      4. **Thumbnails:** ≥60% of live entries resolve an `imageUrl`.
      5. **Full text:** for every source whose feed ships no body or only an excerpt,
         U10's extractor recovers real prose for ≥90% of sampled entries, and the recovered
         body is ≥10× the excerpt where there was one. **fabiensanglard.net and gpuopen.com
         must both be named in the output**, since they are the two shapes this exists for.
      5b. **Thumbnail coverage per source** is printed as a table, not just a total, so a
         source resolving 0% is attributable rather than hidden inside the average.
      6. **Folders survive the round trip:** the live 42 split across folders, exported to
         OPML, reimported into an empty DB, compared exactly.
      7. **Screenshots** of the redesigned home (dark, ≥2 folder sections, mixed
         thumbnails), the Saved list, a highlighted code block, and the zoomed image
         viewer. Critique against DESIGN.md; max 2 iterations, residuals to NOTES.md.
      Then `./gradlew clean test assembleRelease` and update the APK path in NOTES.md.
      - Done: the `-Pperch.live=true` run is green with every gate's count pasted into the
        commit message; screenshots exist under `build/perch-screenshots/`; the default
        `./gradlew test` is still green with no network.
      - Rung: screenshot

- [ ] **U16 — Release v0.2.0.** Bump `versionCode` 3 / `versionName` `0.2.0`. Build the
      **release-signed** APK (U02's key). Update README.md with the new features and fresh
      screenshots. Tag `v0.2.0`, push, `gh release create v0.2.0` with notes written from
      this plan's phases, attaching `perch-0.2.0.apk`. Say explicitly in the notes that
      installing over v0.1.0 requires an uninstall **only because** v0.1.0 was debug-signed,
      and that v0.2.0 onward updates in place and keeps read state. Prune NOTES.md back
      under 100 lines.
      - Done: `gh release view v0.2.0 --json assets` lists the APK; `apksigner verify
        --print-certs` on the released file prints U02's certificate digest;
        `git status` clean; and `grep -c '^- \[ \]' PLAN-2.md` returns 0.
      - Rung: build
