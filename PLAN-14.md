# PLAN-14 — v0.10.0: a page laid out in tables reads as an article, not as a grid

One reader report, **#83** "Poor rendering": <https://paulgraham.com/powerful.html> "doesn't render
properly in Perch". Triaged on 2026-09-27 against the page itself, which is now committed as
`fixtures/articles/paulgraham-powerful.html` (harvested with `curl`, **never re-fetch it**: the tests
read those bytes, not the site). The one issue is **#83**. Every task comments on it with its commit
and what it verified, so the human can watch from the tracker. This file's §0 outranks the issue body.

Sessions read this file cold. Every task carries its anchors so a session **reads rather than
searches**. Line numbers are as of `aa29c53` (the v0.9.0 archive commit). If a line has drifted,
grep the *name* quoted beside it, never the number.

## §0 — Decisions for this version (authoritative; do not re-derive)

### §0.1 The version is `0.10.0`, `versionCode` **13**; no schema change

**The human asked for this to ship as a new MINOR version** (2026-09-27). SPEC.md §1 would call a fix-only
release a PATCH. The human's explicit instruction outranks that rule for this release, and the notes
say so in one line. `perchVersionCode` 12 → **13**, `perchVersionName` `0.9.0` → **`0.10.0`**, at
`app/build.gradle.kts:12-13` and **nowhere else**. The database stays at 11: no task touches Room. No new dependency.

### §0.2 What is actually wrong (measured, not guessed)

Running the fixture through `ArticleExtractor.extract` → `HtmlSanitizer.sanitize` → `ArticleLowering.toBlocks`
gives **exactly one block: an `ArticleBlock.Table`**. It is one row of three cells, and the third cell holds
the whole essay as a single span, with every `<br><br>` paragraph break flattened to a space. That is the
bug the reader saw.

- **Extraction is right.** The extracted HTML holds the whole essay from "September 2026" through the notes
  `[1]`–`[9]` to the "Thanks to …" line. Do not touch scoring.
- **Lowering is wrong.** PG's pages (like much hand-written HTML from before CSS) are *laid out* with
  `<table>`: an outer one-row table (nav image | spacer | content), a nested one-row one-cell table holding
  the essay, and a trailing one-cell table holding `<br><br><hr>`. `ArticleLowering.table`
  (`ArticleLowering.kt:163`) treats every `<table>` as data. It collapses each cell to one span through
  `inlineSpan()` (`:234`, `hardBreaks = false`), which is correct for a data cell and destroys an essay.
- **Two chrome images survive extraction** and would render as full-width pictures once the essay is flow:
  the left navigation (`<img … usemap=#…>` over a `<map>` of `<area>` links, a 69×357 GIF) and the site
  logo (`<a href="index.html"><img …bel-8.gif></a>`).

### §0.3 A layout table lowers as flow (the rule; H01)

In `ArticleLowering.table`, before building the grid: a `<table>` is **layout** when either
1. it has a `<table>` nested anywhere inside it (nesting is how a page is laid out, not how data is
   written; `ArticleExtractor.carriesContentTable`, `ArticleExtractor.kt:282`, already uses exactly
   this signal, so say so in the KDoc and do not invent a second one), or
2. it has **exactly one row**. A single row has no relationship between rows to express: it is a header
   with no body or a body with no header. Two images side by side in a one-row table stack on a phone,
   which is better than a scroll-sideways grid.

A layout table lowers as **flow**: its **own** rows in document order, each cell's content through
`lowerFlow(cell)` (`:42`) and appended in order. A nested table meets the same rule again through
`lowerBlock` (`:66`), so recursion needs no special case. Everything else (≥2 rows, no nested table)
lowers exactly as today.

**Generality.** No hostname, no `paulgraham`, no class name. PLAN-10 §0.2's grep gate
(`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"' app/src/main/java/dev/mkiros/perch/data/`, verbatim from PLAN-10:45) must return
nothing, as it returns nothing at `aa29c53` (0 lines; paste the count after).

### §0.4 Image-map navigation and the home-link logo are chrome (H02)

In `ArticleExtractor` (not the sanitizer: this is about what the *article* is, and feed bodies never
carry either):
- `img[usemap]` and `map` are removed in `strip` (`ArticleExtractor.kt:119`). An image map is a
  navigation widget by definition: its links live in `<area>`, not in the picture.
- In `clean` (`:302`), an `<a>` that holds **no text of its own and only images**, and whose `href`
  resolves (against the page URL) to the **same host's root** (path empty, `/`, or `/index.htm[l]`,
  `/index.php`, `/default.htm[l]`), is removed with its image. That is the site logo. It is never an
  article's picture. Resolve with `absUrl("href")` so relative `index.html` counts.

The title GIF (`making-startups-powerful-1.gif`, `alt="Making Startups Powerful"`) **stays**: it is
the author's own heading, and dropping an image because its alt matches the title would also drop
every hero image that is captioned with its headline. Do not "fix" it.

### §0.5 Tests and gates

- **The floor is 2292 and may only rise** (v0.9.0: 1327 debug + 965 release, NOTES.md). `FeedCorpusTest`
  is untouchable. **TDD in H01 and H02**, RED pasted into the commit.
- **The fixture joins the contract in H01**: an `ArticleFixture` in `ArticleFixtures.other`
  (`app/src/test/.../data/extract/ArticleFixtures.kt`; add it after `gijn`, and add it to `other`'s
  list, not to `pending`, because extraction already passes and `ArticleExtractorBlindSpotTest` would
  fail a pending fixture that extracts), with slug `paulgraham-powerful`, url
  `https://paulgraham.com/powerful.html`, cms `"hand-written HTML, table layout"`,
  mid `"It's always good when money flows through you."`, last `"for reminding me about token flow"`,
  `excludes = emptyList()` (its chrome is images, which H02's test covers).
- **Data tables must not move.** `TableCorpusTest` (`app/src/test/.../data/parse/TableCorpusTest.kt:29`), the
  ZDI extraction tests (`ArticleExtractorTest.kt:112`, `:132`), `ArticleLoweringTest.kt:201`/`:214` and
  `TableScreenshotTest` stay green **unmodified**. If the layout rule turns any corpus table into flow,
  the rule is wrong, not the corpus: stop and mark the box BLOCKED with the table named.
- **Screenshots** go through `Screenshots.captureAndAssert` (`ScreenshotSupport.kt`), never
  `captureToImage()`, into `build/perch-screenshots/`. A screenshot's Done is the PNG **looked at**
  (`Read` it) and critiqued against DESIGN.md §8 in the commit message. At most **two** critique-fix
  iterations, then residual polish goes to NOTES.md.

### §0.6 Traps that will look like bugs

- **`el.select("tr")` finds nested tables' rows too.** Today's `table()` gets away with it because a data
  table has no nesting. The flow path must walk **only this table's own rows**: `tr` children of the
  table and of its direct `thead`/`tbody`/`tfoot`. Otherwise the essay lowers twice (once through the
  outer table's `select`, once through recursion). A doubled essay in H01's test is this trap.
- **Jsoup inserts `<tbody>`**, so a `tr` is never a direct child of `table` after parsing. Walk through it.
- **`lowerFlow(td)` already flushes at the end**, so cell boundaries end paragraphs for free. Do not add a
  second flush.
- **The trailing `<table><tr><td><br><br><hr>`** lowers to a `Rule` that `trimRules` (`:211`) drops as
  trailing. An essay whose last block is a `Rule` means `trimRules` was bypassed.
- **Two known full-suite-only flakes** (NOTES.md): `WorkSchedulerTest > choosing manual…` and
  `SettingsViewModelTest`. Re-run once before diagnosing. **`PerchApp.onTerminate` can hang one full
  suite** (TECH_DEBT.md). If `./gradlew test` hangs past 15 min, kill it and re-run once.
- **The Windows emulator is not needed by any task.** Never boot it by hand.

### §0.7 Rungs

`./gradlew test` in the **foreground** is the rung for every task (`unit`, 3–7 min); run the narrowest
`--tests` first. H03 adds one screenshot. Live acceptance is a bounded step of H05 (~90 s, two runs at
most). The review box (H04) is second from last, as CLAUDE.md requires.

---

## The tasks

- [x] **H01 — A table used for layout lowers as paragraphs; the PG essay reads as an essay. TDD. Issue #83.**
      `gh issue view 83` first. §0.2 is the diagnosis, §0.3 the rule, §0.6 the traps; read them before the code.
      RED first, in `ArticleLoweringTest` (beside `:201`) with hand-written HTML, three tests: (a) a
      one-row table whose one cell holds `para one<br><br>para two` lowers to **two `Paragraph`s and no
      `Table`**; (b) a table with a nested table lowers its inner cells' text exactly **once**, and
      contains no `Table`; (c) a 2×2 table with no nesting still lowers to one `Table` (the guard). Then in
      `ArticleExtractorTest`, a test named for the behaviour: *a page laid out in tables lowers to its
      paragraphs, not a grid*. It runs extract → sanitize → lower over the new fixture, asserts no
      `ArticleBlock.Table`, **≥ 20 `Paragraph`s**, and that the mid and last sentences each sit in their own
      paragraph. Add the fixture to `ArticleFixtures.other` per §0.5 (that also puts it under the
      existing mid/last/unsupported tests). Then GREEN in `ArticleLowering.table` (`:163`) per §0.3.
      Update the KDoc above `table()` to state the rule and why.
      - Done: the RED output line in the commit; `./gradlew :app:testDebugUnitTest --tests '*ArticleLowering*' --tests '*ArticleExtractor*' --tests '*TableCorpus*'` green; `./gradlew test` green and above 2292; §0.3's grep-gate counts before/after pasted; #83 commented with the commit; pushed.
      - Rung: unit

- [x] **H02 — Image-map navigation and the home-link logo do not survive extraction. TDD. Issue #83.**
      §0.4 is the whole decision. RED first in `ArticleExtractorTest`: (a) hand-written page, article
      prose plus `<img usemap="#m" src="/nav.gif"><map name="m"><area href="/a"></map>` → the extracted
      HTML has no `nav.gif`; (b) hand-written page with `<a href="index.html"><img src="/logo.gif"></a>`
      at `https://example.com/posts/one.html` → no `logo.gif`, **and** a control:
      `<a href="/posts/two.html"><img src="/figure.png"></a>` inside the prose survives; (c) over
      `paulgraham-powerful`: the extracted images are **exactly** the title GIF
      (`making-startups-powerful-1.gif`). Also prove no other fixture lost an image: before the GREEN
      change, record `images(extract(fixture))` for every fixture in `ArticleFixtures.all` (the helper at
      `ArticleExtractorTest.kt:257`), and after it, show the sets unchanged except `paulgraham-powerful`.
      A throwaway loop is fine; paste the per-fixture counts, do not commit the loop. Then GREEN in
      `strip` (`:119`) and `clean` (`:302`).
      - Done: RED line in the commit; the before/after image counts pasted; `./gradlew test` green; #83 commented; pushed.
      - Rung: unit

- [x] **H03 — The PG essay renders as an article, looked at in both themes. Screenshot. Issue #83.**
      Add `LayoutTableScreenshotTest` in `app/src/testDebug/.../ui/screenshot/`, a copy of
      `TableScreenshotTest`'s shape (`TableScreenshotTest.kt:52`, `:58`: fixture → sanitize → `ArticleBody`)
      but fed by extract → sanitize over `paulgraham-powerful`. It writes `layout-table-essay-light` and
      `layout-table-essay-dark`: the top of the article, title GIF then "September 2026" then the first
      paragraphs. `Read` both PNGs and critique against DESIGN.md §8 in the commit, numbered: paragraph
      spacing, no grid rules, no horizontal scroll cue, footnote markers `[1]` legible, the title GIF not
      stretched past its natural width. The fix for anything here is in lowering or `ArticleBody`, never in
      the fixture. Two iterations at most (§0.5).
      - Done: both PNG paths and the critique in the commit; `./gradlew test` green; #83 commented; pushed.
      - Rung: screenshot

- [ ] **H04 — The review pass. The whole of v0.10.0, read at once.**
      Read `git diff v0.9.0..HEAD`, **the whole of it**, and answer in the commit message (and in NOTES.md
      where it outlives the plan):
      1. Does any doc still describe v0.9.0 or describe tables wrongly? README.md; SPEC.md §1 (version) and
         §5 (lowering: say a layout table lowers as flow); DESIGN.md §8 (tables); NOTES.md; CLAUDE.md;
         `docs/RALPH.md`; `TECH_DEBT.md` "## Next plan".
      2. Did any task leave a helper, constant or test orphaned? Grep for anything H01/H02 replaced.
      3. Was any test weakened rather than rewritten? `git diff v0.9.0..HEAD -- app/src/test app/src/testDebug`:
         name every changed or removed assertion and say which is at least as strong as what it replaced.
         §0.5's "data tables must not move" list must show **no** diff at all.
      4. Is the suite above 2292, and did H01/H02 land with their RED in the commit?
      5. Does §0.3's grep gate return what it did at `aa29c53`?
      Fix what is small and mechanical **in this session**. Anything larger becomes an issue for the next
      plan and a line in `TECH_DEBT.md` "## Next plan". Do not start a feature in a review.
      - Done: the five answered in the commit message, each with the command that settled it; `./gradlew test` green; pushed.
      - Rung: unit

- [ ] **H05 — Release v0.10.0 publicly. The human has asked for it. Issue #83.**
      The human asked on 2026-09-27 for this fix to be published under a new MINOR version (§0.1).
      **Not held**: build, verify, publish.
      - Bump `perchVersionCode` 12 → **13**, `perchVersionName` `0.9.0` → **`0.10.0`** at
        `app/build.gradle.kts:12-13`, the one place they live.
      - **Live acceptance first, bounded:** `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`
        in the **foreground**, ~90 s, **at most two runs**; paste every gate's line. A network gate that
        fails twice is named in the commit and in the release's "Known issues" and does not block. Nothing
        in this plan touches fetching.
      - `./gradlew test assembleRelease`, **not `clean`**. Rename Gradle's `app-release.apk` to
        `perch-0.10.0.apk`. **Verify on the file, not the build log** (a missing
        `~/.perch/signing.properties` silently debug-signs): `aapt2 dump badging` reads
        `versionCode='13' versionName='0.10.0'`; `apksigner verify --print-certs` prints U02's digest
        `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`. Paste both.
      - Release notes through `docs/RELEASE-NOTES.md`'s template (`scripts/release-notes.sh v0.9.0`
        drafts from #83), in the reader's words: pages built from old-style table layouts, such as Paul
        Graham's essays, now read as paragraphs instead of one squashed grid, and their navigation images
        are gone. One line says this is a MINOR release at the human's request although it only fixes.
        "Installing / upgrading": installs in place over v0.9.0; no database change. Record them in the
        file per its rules.
      - Tag `v0.10.0` at `HEAD`, push the tag, `gh release create v0.10.0` with those notes and
        `perch-0.10.0.apk` attached. Close **#83** naming the release. **Do not touch #82.**
      - Then the turnover edits, so the next session is not a loop session: CLAUDE.md's active-plan section
        says v0.10.0 shipped 2026-MM-DD and there is no active plan; `loop.sh:19` and
        `scripts/progress.sh:11` keep naming `PLAN-14.md` (a stray launch fails loudly on the missing
        root file). NOTES.md under 100 lines, with the new test floor and the APK path.
        **Tick this box in the same commit** (v0.9.0's release commit forgot to). **Do not move this
        file**: the watching session archives it into `docs/plans/`.
      - Done: `gh release view v0.10.0 --json assets` lists `perch-0.10.0.apk`; badging and signature
        outputs in the commit; `git status` clean and pushed; `gh issue list --state open` does not list
        #83 (and still lists #82).
      - Rung: build
