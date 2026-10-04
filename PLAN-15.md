# PLAN-15 — v0.11.0: lazy photos and their credits, Hexo code blocks, and a Report button

Three reader reports, triaged on 2026-10-04 by the orchestrating session against the pages themselves:

- **#85** "Not rendering images" — <https://spectrum.ieee.org/bloomberg-terminal>; the reader's comment adds
  that "the subtext under the images also doesn't render properly".
- **#87** "Blog post not rendering properly" — <https://sh4dy.com/2024/06/29/learning_llvm_01/>.
- **#86** "Add a report button" — the human's own comment on the issue is the spec; §0.5 below is how it is built.

Both pages are committed as fixtures, harvested with `curl` on 2026-10-04 and scanned for keys (none):
`fixtures/articles/ieee-spectrum-bloomberg-terminal.html` and `fixtures/articles/sh4dy-learning-llvm-01.html`.
**Never re-fetch them**: the tests read those bytes, not the sites.

Sessions read this file cold. Every task carries its anchors so a session **reads rather than searches**.
Line numbers are as of `fa13514` (the v0.10.0 archive commit). If a line has drifted, grep the *name*
quoted beside it, never the number. **Bare `gh issue view N` fails on this `gh`**: use
`gh issue view N --json title,body,comments`. Every task comments on its issue with its commit and what
it verified, so the human can watch from the tracker. This file's §0 outranks the issue bodies.

## §0 — Decisions for this version (authoritative; do not re-derive)

### §0.1 The version is `0.11.0`, `versionCode` **14**; no schema change

J07/J08 add a user-visible feature (Report), so SPEC.md §1 makes this a **MINOR** release.
`perchVersionCode` 13 → **14**, `perchVersionName` `0.10.0` → **`0.11.0`**, at `app/build.gradle.kts:12-13`
and **nowhere else**. The database stays at 11: no task touches Room. **No new dependency.** The issue
template and the `render` label are repository furniture, not app dependencies.

### §0.2 What is actually wrong (measured, not guessed)

The orchestrator ran both fixtures through `ArticleExtractor.extract` → `HtmlSanitizer.sanitize` →
`ArticleLowering.toBlocks`, drew the blocks with `ArticleBody`, and compared that against a headless
Edge capture of each live page at 412 px wide. Every defect found is listed. **Nothing else on either page
differs**, so do not go looking for more.

**IEEE Spectrum (#85): two defects.**
1. **The three in-body photos are gone.** Each is
   `<p class="shortcode-media …"><img src="data:image/svg+xml,…" data-runner-src="https://…jpg?…" …>`
   followed by two `<small>`s. `HtmlSanitizer.promoteLazySource` (`HtmlSanitizer.kt:200`) knows only
   `LAZY_SRC = data-src, data-lazy-src, data-original` (`:257`). The `src` is a `data:` placeholder, there is
   no `srcset`, and the allowlist drops the `data:` URI, so the picture vanishes.
2. **The caption and credit come out as a loose paragraph run together:** "…specialized financial data.
   Karjean Levine/Getty Images" and "…finance-specific hot keys.National Museum of American History…" (no
   space). After each image come `<small class="image-media media-caption">` **and**
   `<small class="image-media media-photo-credit">`. Both match `CAPTION_CLASS` (`:253`, which already
   names `credit`), so `captionSibling` (`:170`) finds two candidates, and `candidates.singleOrNull()`
   returns null. No figure is made, and the two smalls fall through as flow.

**sh4dy.com (#87): one defect, six times.** Every code block is Hexo's line-number table:
`<figure class="highlight bash"><table><tr><td class="gutter"><pre><span class="line">1</span><br>…</pre></td><td class="code"><pre><span class="line">…</span><br>…</pre></td></tr></table></figure>`.
That is one row of **two** cells, so H01's layout rule (one row of *one* cell, `ArticleLowering.kt:175`)
does not fire. `table()` builds a grid with the line numbers "1 2 3 4" in one cell and the whole program
in the other, joined onto one line in the body font. The live page shows a dark monospaced block with a
line-number gutter. The page has six of these: four `bash`, one `c`, one `cpp`.

**Considered and left out, on the human's word (2026-10-04):** IEEE's hero photo sits outside the article
body, and Perch never draws a page's hero (a possible future feature, logged in `TECH_DEBT.md`). IEEE's
trailing "From Your Site Articles" / "Related Articles Around the Web" link lists render as the site
renders them. **No task touches either.**

### §0.3 Lazy source: any `data-…src` attribute, but only when `src` is unusable (J01)

In `promoteLazySource`, keep today's order and add exactly one rung:
1. a named `LAZY_SRC` attribute wins outright (unchanged);
2. **new:** otherwise, if `src` is unusable (absent, blank, or `data:`), take the first attribute whose
   name starts with `data-` and ends with `src` (so `data-runner-src`, `data-lazy-src`, `data-echo-src`,
   and never `data-srcset`), provided its value is non-blank and not itself `data:`;
3. otherwise the widest `srcset` candidate (unchanged).

A real `src` is never overridden by rung 2: only the *named* attributes may do that, for the spacer-GIF
reason the KDoc gives. Update that KDoc to state rung 2 and why it is not a list: a lazy loader names its
attribute after itself, so a list only ever grows one CMS at a time.

### §0.4 A caption and a credit become one caption (J02)

In `captionSibling`, the caption-classed siblings after the image may be **one caption and one credit**.
"Credit" means a class matching `\bcredit\b`, case-insensitive. The caption is the one that does not
match it. When there is exactly one of each, both go into the `<figcaption>`: the caption's nodes, then
**` — `** (space, em dash, space), then the credit's nodes, and both source elements are removed. One
caption alone, or one credit alone, behaves as today. Two captions, two credits, or anything else
ambiguous keeps today's answer (null, no figure): **nothing looser**, as the KDoc at `:135` insists. The
credit is never dropped: it is attribution the publisher required.

### §0.5 A line-number table is a code block (J03, J04)

**J03, lowering.** In `ArticleLowering.table` (`:173`), before the layout check: a table is a
**line-numbered code block** when it has **exactly one own row** and that row's cells are (a) one or more
cells whose text is only digits and whitespace (the gutter) and (b) exactly one cell holding a `<pre>`.
It lowers to **`code(thatPre)`**, the gutter dropped. This is the shape Hexo (`td.gutter`/`td.code`),
Pygments' `linenos=table` (`td.linenos`/`td.code`) and Rouge (`table.rouge-table`) all emit. Judge by
**shape, never class names**: `class` is gone from a `td` by the time lowering runs, since the sanitizer
keeps `class` only on `pre` (`HtmlSanitizer.kt:274`).

**Trap, J03: `code()` drops `<br>`.** `code()` (`:89`) reads `el.wholeText()`, which ignores elements, and
Hexo separates lines with `<span class="line">…</span><br>`, not `\n`. Without a fix, the six blocks
become one line each ("llvm.shchmod +x"). `code()` must turn each `<br>` inside the `pre` into `\n`, and
must keep text nodes' own newlines unchanged so that no existing code fixture moves. Test both.

**J04, language.** `HtmlSanitizer.normalizeLanguage` (`:105`) looks for `language-x`/`lang-x`/… on the
`pre`, its `code`, then `ANCESTOR_REACH` = 3 ancestors. Hexo's claim is `<figure class="highlight bash">`,
**five** levels up (td → tr → tbody → table → figure) and unprefixed. Add one rung after the existing ones:
when the `pre` sits in a table cell, also search the table's parent. There, the pair `highlight <token>`
(Hexo's and Pygments' wrapper) names `<token>` when it is a language `CodeLanguage` (`ui/article/code/CodeLanguage.kt`)
already knows. An unknown token stays unclaimed (plain code, as today). Expected over the fixture, in order:
`bash, bash, cpp, bash, c, bash`. **`data/` must not import `ui/`**: if `CodeLanguage`'s names live only in `ui/`,
move the plain set of names down to `data/parse/` and have `ui/` read it, rather than restating it.

### §0.6 Report (#86): a prefilled GitHub issue, no token, no server (J06–J08)

The human's comment on #86 is the spec. As built:
- **Where:** a **Report** item in the article's ⋮ menu (`Overflow`, `ArticleScreen.kt:235`), after
  "Copy link". It is shown whenever `state.link != null`, which covers feed articles, To-Read links and
  stored PDFs that have a link. It is hidden with no link, since there is nothing to report.
- **The sheet:** a `ModalBottomSheet` titled "Report a rendering problem", with five single-choice rows
  (exactly these strings): **Text missing or cut off** · **Images broken** · **Code or tables wrong** ·
  **Layout off** · **Something else**. Below them is an optional one-field note, capped at **500**
  characters, and one button, **Open GitHub**, enabled once a row is chosen. Nothing is pre-selected.
- **What it opens:** `openInBrowser` (`ArticleScreen.kt:540`, Custom Tabs, already handles "no browser")
  on `https://github.com/michaelawetahegn/Perch/issues/new` with these query parameters:
  - `template=render-report.md`;
  - `title=Render: <host> — <problem>`, where `<host>` is the link's host without `www.`;
  - `body=` the article link, the source (`state.source?.name`; To-Read's synthetic feed reads
    **To-Read**; none reads **unknown**), the problem, the note (omitted when blank) and
    `Perch <BuildConfig.VERSION_NAME>`. Write it as a short Markdown list, each value on its own line.

  Everything is percent-encoded with `Uri.Builder.appendQueryParameter`, never by hand. The repository
  URL is **one constant** in the one file that builds the link.
- **The builder is pure.** `fun renderReportUrl(link, source, problem, note, version): String` in
  `ui/article/RenderReport.kt` together with the `enum class RenderProblem` (labels as string
  resources). It is unit-tested without a screen.
- **The label is applied by the template, not the URL.** `labels=` in a new-issue URL only works for
  someone with triage rights. A Markdown template's front-matter `labels: render` works for anyone. J06
  creates the `render` label and `.github/ISSUE_TEMPLATE/render-report.md`. That is the repo's first
  `.github/` file and **it is not CI**, so the "no CI" statements in CLAUDE.md and `docs/RALPH.md` stay
  true. The review (J09) checks that they still read correctly.
- **Perch never holds a GitHub account or token.** The issue is filed by whoever is signed in to GitHub
  in the browser. No network call is made by Perch itself.

### §0.7 Generality gate (binds J01–J04)

No hostname, no `ieee`, `rebelmouse`, `hexo` or `sh4dy` in `app/src/main`, and no class-name list for
one CMS. PLAN-10 §0.2's grep gate,
`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"' app/src/main/java/dev/mkiros/perch/data/`,
must return what it returns at `fa13514`: **0 lines**. Paste the count before and after. **A rule that
lifts one fixture and moves no other is aimed at a site.** Each of J01–J04 states, in a test's hand-written
HTML, the general shape it handles, not just the fixture.

### §0.8 Fixtures and the standing corpus

Register both new fixtures in `ArticleFixtures.other` (`app/src/test/.../data/extract/ArticleFixtures.kt:219`)
with a `mid` and a `last` sentence read from the extracted text, as H01 did for `paulgraham`. That
subjects them to the existing mid/last/unsupported tests. `FeedCorpusTest`, `ArticleLoweringCorpusTest`
and `TableCorpusTest` must stay green **unweakened**. If one legitimately moves, the commit names the
assertion and says why. **`TableCorpusTest`'s data tables must not change at all**: J03 only claims a
one-row table with a digits-only cell beside a `<pre>`.

### §0.9 Traps

- **Jsoup inserts `<tbody>`**: a `tr` is never a direct child of `table` after parsing.
  `ArticleLowering.ownRows()` already walks through it, so use it.
- **Screenshots: go through `Screenshots`** (`ScreenshotSupport.kt`), never `captureToImage()`.
  Images are stubbed with `stubImages` (`ImageStubs.kt:33`), since Coil never leaves the JVM.
  `LayoutTableScreenshotTest` (`app/src/testDebug/.../ui/screenshot/`) is the shape to copy. `rm -rf
  build/perch-screenshots` before a screenshot run (NOTES: stale shots persist).
- **Sheet UI tests:** a tap injected into a `ModalBottomSheet` or `DropdownMenu` never arrives, so use
  `performSemanticsAction(SemanticsActions.OnClick)` (NOTES "Standing UI-test traps"). A sheet is its
  own window.
- **Asserting the browser launch:** under Robolectric, read the started intent with
  `shadowOf(application).nextStartedActivity` and assert its `data` URI's query parameters. Never
  assert on the raw string's encoding.
- **Known full-suite flakes** (NOTES.md): `WorkSchedulerTest > choosing manual…`,
  `SettingsViewModelTest`, and three first-run-only ones. Re-run once before diagnosing. `./gradlew test`
  can **hang** in `PerchApp.onTerminate`, so wrap it as `timeout 45m ./gradlew test --continue`, and if
  it hangs, kill it and re-run once. **Count both variants** (debug + release).
- **The test floor is 2309** (v0.10.0). Every task ends at or above it.
- **No emulator is installed on this machine, and none is needed.** Every rung here is JVM-only.
- **Environment:** every session exports `JAVA_HOME=$HOME/.jdks/temurin-17` and puts `$JAVA_HOME/bin`
  first on `PATH` itself (see CLAUDE.md). Run `./gradlew --stop` when done.

### §0.10 Reference captures of the live pages (J05 only)

For the visual comparison, J05 captures the live pages with Windows Edge headless. This is a dev-only
look, not a dependency, and nothing it writes is committed:

```bash
cd /mnt/c && "/mnt/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless=new \
  --disable-gpu --hide-scrollbars --window-size=412,9000 \
  --screenshot='C:\perch-stage\ref\<name>.png' '<url>'
```

Slice the 9000-px PNG into 1800-px strips with PIL before reading it, because one tall image is unreadable.

---

## The tasks

- [BLOCKED: code + tests committed and green targeted (141) and debug full (1341, 1 known flake); release `./gradlew test` hangs 2/2 in the PerchApp.onTerminate Room deadlock (TECH_DEBT) — NOTES 2026-10-04] **J01 — A photo hidden behind any `data-…src` attribute is kept. TDD. Issue #85.**
      `gh issue view 85 --json title,body,comments` first; §0.2 (IEEE 1) is the diagnosis, §0.3 the rule.
      RED first, in `HtmlSanitizerTest`, with hand-written HTML: (a) `<img src="data:image/svg+xml,…"
      data-runner-src="https://example.com/a.jpg">` sanitizes to an `img` whose `src` is that URL; (b) the
      same with `data-foo-src` (the rule is the shape, not the name); (c) **guard:** `<img src="https://example.com/real.jpg"
      data-other-src="https://example.com/x.jpg">` keeps `real.jpg`; (d) **guard:** `data-srcset` is never
      read by rung 2. Then, in `ArticleExtractorTest`, *a page whose photos load lazily keeps them*:
      extract → sanitize → lower over `ieee-spectrum-bloomberg-terminal` gives **exactly 3 `ArticleBlock.Image`**,
      the first ending `…background.jpg?id=67857167&width=980`. Register the fixture per §0.8. GREEN in
      `promoteLazySource` (`HtmlSanitizer.kt:200`) per §0.3, KDoc updated.
      - Done: the RED line in the commit; `./gradlew :app:testDebugUnitTest --tests '*HtmlSanitizer*' --tests '*ArticleExtractor*' --tests '*Corpus*'` green; `timeout 45m ./gradlew test --continue` green, ≥2309; §0.7 counts pasted; #85 commented; pushed.
      - Rung: unit

- [BLOCKED: code + RED/GREEN tests committed, targeted 145 green; full suite now finishes (onTerminate deadlock fixed) but debug fails 2/2 on PerchNavHostTest's order-dependent UncaughtExceptionsBeforeTest (ArticleViewModel on a closed pool) — NOTES/TECH_DEBT 2026-10-04] **J02 — A photo's caption and credit read as one caption under it. TDD. Issue #85.**
      §0.2 (IEEE 2) and §0.4. RED first in `HtmlSanitizerTest` (beside the F05 caption tests; grep
      `aria-describedby`): (a) `<p><img src="https://example.com/a.jpg"><small class="media-caption">Cap.</small><small class="photo-credit">Who</small></p>`
      gives a `figure` whose `figcaption` text is exactly `Cap. — Who`, with no stray `small` left; (b) a
      lone credit still becomes the caption (today's behaviour, pinned); (c) **guard:** two caption-classed
      siblings and no credit still produce no figure. Then in `ArticleExtractorTest`, over the IEEE fixture:
      every one of the 3 images has a caption; the first is
      `Michael Bloomberg believed Wall Street would pay a premium for access to specialized financial data. — Karjean Levine/Getty Images`
      (trim the source's trailing space); and **no `Paragraph`** contains `Getty Images` or
      `National Museum of American History/Smithsonian`. GREEN in `captionSibling`/`captionFromDescription`
      (`HtmlSanitizer.kt:144-183`), with the KDoc at `:135` saying caption + credit is the one widening.
      - Done: RED line in the commit; narrow tests then `timeout 45m ./gradlew test --continue` green, ≥2309; #85 commented; pushed.
      - Rung: unit

- [x] **J03 — A line-numbered code table lowers to one code block, gutter dropped, lines intact. TDD. Issue #87.**
      `gh issue view 87 --json title,body,comments`; §0.2 (sh4dy) and §0.5 J03, **including its `<br>` trap**.
      RED first in `ArticleLoweringTest` (beside H01's table tests; grep `one row of one cell`): (a) a
      one-row table, `<td><pre>1<br>2</pre></td><td><pre>a = 1<br>b = 2</pre></td>`, lowers to exactly
      `[Code("a = 1\nb = 2", null)]`; (b) the same with Hexo's `<span class="line">…</span><br>` markup;
      (c) a bare `<pre>x<br>y</pre>` lowers to `Code("x\ny")`; (d) **guards:** a one-row table of a
      number cell and a *text* cell (no `pre`) is still a `Table`; a 2-row table with a `pre` in a cell is
      still a `Table`. Then in `ArticleExtractorTest`, *a Hexo post's code blocks read as code*: over
      `sh4dy-learning-llvm-01`, **no `ArticleBlock.Table`**, exactly **6 `Code`** blocks, the first
      `#!/bin/bash\nwget https://apt.llvm.org/llvm.sh\nchmod +x llvm.sh\n./llvm.sh 16`, and the long one
      has **44 lines**. Register the fixture per §0.8. GREEN in `ArticleLowering.table` and `code()`.
      - Done: RED line in the commit; `--tests '*ArticleLowering*' --tests '*ArticleExtractor*' --tests '*TableCorpus*'` green; full suite green ≥2309; §0.7 counts pasted; #87 commented; pushed.
      - Rung: unit

- [x] **J04 — Hexo's `highlight <lang>` names the code block's language. TDD. Issue #87.**
      §0.5 J04. RED first in `HtmlSanitizerTest` (grep `highlighter-rouge` for the neighbouring tests):
      (a) `<figure class="highlight cpp"><table><tr><td><pre>1</pre></td><td><pre>int x;</pre></td></tr></table></figure>`
      sanitizes to a code `pre` with `class="language-cpp"`; (b) `highlight plaintext-unknown` stays
      unclaimed; (c) **guard:** a `figure class="highlight cpp"` wrapping a `pre` *not* in a table already
      worked or now works the same way. Say which in the commit. Then over the sh4dy fixture, the six
      `Code` blocks' languages are `bash, bash, cpp, bash, c, bash` **in document order** (checked against the
      fixture's `<figure class="highlight …">`s on 2026-10-04). `CodeLanguage.of` (`CodeLanguage.kt:39`) already
      *sniffs* when nothing is declared, so the screen may already highlight; a declared language is still
      the contract, and is what the assertion pins. GREEN in `normalizeLanguage`.
      - Done: RED line; narrow then full suite green ≥2309; #87 commented; pushed.
      - Rung: unit

- [x] **J05 — Both pages, drawn by Perch, match their sites. Screenshot. Issues #85 #87.**
      Add `ReportedPagesScreenshotTest` in `app/src/testDebug/.../ui/screenshot/`, copying
      `LayoutTableScreenshotTest`'s shape (extract → sanitize → lower → `ArticleBody`, `w411dp-h891dp-xhdpi`),
      with every image stubbed as an 800×450 grey slab. It writes, in light and dark:
      `ieee-figure-{light,dark}`, scrolled so the first in-body photo and its caption are on screen (render
      the block list from the paragraph before it), and `hexo-code-{light,dark}`, the block list from
      "Before actually writing LLVM passes" through the first two code blocks. Capture the live references
      per §0.10. `Read` all four PNGs and the matching reference strips, then critique in the commit,
      numbered: photo present at column width; caption under it in caption style with the credit after
      ` — `; code in the code block style, monospaced, one line per source line, no line-number gutter,
      highlighted when a language is known; nothing else on screen differs from the site beyond Perch's
      own typography. A fix goes in lowering or `ArticleBody`, never in a fixture. **Two iterations at
      most**, then log the residue to NOTES.md.
      - Done: four PNG paths and the critique in the commit; full suite green ≥2309; #85 and #87 commented with the critique; pushed.
      - Rung: screenshot

- [x] **J06 — Reports arrive labelled `render`, whoever files them. Issue #86.**
      `gh issue view 86 --json title,body,comments` (the human's comment is the spec); §0.6. Create the label:
      `gh api repos/michaelawetahegn/Perch/labels -f name=render -f color=d93f0b -f description="An article that does not render as its page does"`.
      Add `.github/ISSUE_TEMPLATE/render-report.md` with front matter `name: Rendering problem`, `about:`
      one line, `title: "Render: "`, `labels: render`, and a body with the same headings J07's builder
      fills. When the link carries `body=`, it replaces the template body, so the template body is only
      what someone sees filing by hand.
      - Done: `gh api repos/michaelawetahegn/Perch/labels/render --jq .name` prints `render`; the file committed and pushed; #86 commented.
      - Rung: build

- [x] **J07 — The report link is built, encoded and capped in one pure function. TDD. Issue #86.**
      §0.6 "The builder is pure". RED first in a new `app/src/test/.../ui/article/RenderReportTest.kt` (Robolectric,
      since `Uri` needs it): (a) the URL's host/path is `github.com/michaelawetahegn/Perch/issues/new` and
      `template=render-report.md`; (b) `title` is `Render: spectrum.ieee.org — Images broken` for
      `https://www.spectrum.ieee.org/x`, with `www.` dropped; (c) `body` contains the link, the source,
      the problem, the note and `Perch 0.10.0` (pass the version in, never read `BuildConfig` in the
      function); (d) a blank note leaves no note line; (e) a 600-character note is cut to 500; (f) a
      note with `&`, `#`, `?` and a newline round-trips through `Uri.parse(url).getQueryParameter("body")`
      intact; (g) a null source reads `unknown`. Add the five `RenderProblem` labels to `strings.xml`
      exactly as §0.6 spells them. GREEN in `ui/article/RenderReport.kt`.
      - Done: RED line; `--tests '*RenderReport*'` green; full suite green ≥2309; #86 commented; pushed.
      - Rung: unit

- [ ] **J08 — Report sits in the article's ⋮ menu and opens GitHub prefilled. TDD + screenshot. Issue #86.**
      §0.6 "Where" and "The sheet"; §0.9's sheet and intent traps. RED first in `ArticleScreenTest`
      (`app/src/testDebug/…/ui/article/`): (a) a feed article's ⋮ menu has **Report**, which opens the
      sheet with five rows and **Open GitHub** disabled; (b) choosing **Code or tables wrong**, typing a
      note and pressing **Open GitHub** starts an `ACTION_VIEW` whose URI has `template=render-report.md`
      and a `body` containing the article link and the note, after which the sheet closes; (c) a To-Read
      link's article reads **To-Read** as its source in `body`; (d) an article with `link == null` has no
      **Report**. Add `ArticleTestTags.REPORT` and sheet tags beside `COPY_LINK`. GREEN in
      `ArticleScreen.Overflow` (`:235`) plus a small `RenderReportSheet` composable in
      `ui/article/RenderReport.kt`. The source string comes from `state.source`, and the ViewModel only
      changes if To-Read's source is not already distinguishable there. Check `ArticleViewModel.kt:49` and
      the synthetic feed in SPEC.md §4 before adding anything. Screenshot the open sheet with a row chosen,
      `report-sheet-{light,dark}`, and critique it against DESIGN.md's sheet rules (`SaveLinkSheet` is the
      house sheet) in the commit. Update SPEC.md §10 (navigation/actions) and DESIGN.md's article-actions
      section with one paragraph each describing Report.
      - Done: RED line; `--tests '*ArticleScreen*' --tests '*RenderReport*'` green; both PNGs and the critique in the commit; full suite green ≥2309; #86 commented; pushed.
      - Rung: screenshot

- [ ] **J09 — The review pass. The whole of v0.11.0, read at once.**
      Read `git diff v0.10.0..HEAD`, **the whole of it**, and answer in the commit message (and in NOTES.md
      where it outlives the plan):
      1. Does any doc still describe v0.10.0, or describe lazy images, captions, code tables or the ⋮ menu
         wrongly? README.md; SPEC.md §1, §5 (lowering: line-number tables, caption + credit, lazy `data-…src`),
         §10; DESIGN.md §8 (code, figures) and the article actions; NOTES.md; CLAUDE.md and
         `docs/RALPH.md`, both of which say there is **no CI** and that `.github/` does not exist. The
         second claim is now false, so reword it to "no CI; `.github/` holds only an issue template".
         Also `TECH_DEBT.md` "## Next plan", which must now carry the hero-image idea from §0.2.
      2. Did any task leave a helper, constant, string or test tag orphaned?
      3. Was any test weakened rather than rewritten? `git diff v0.10.0..HEAD -- app/src/test app/src/testDebug`:
         name every changed or removed assertion and say which is at least as strong as what it replaced.
         `TableCorpusTest` must show **no** diff.
      4. Is the suite at or above 2309, and did J01–J04, J07 and J08 each land with their RED in the commit?
      5. Does §0.7's grep gate still return 0 lines?
      Fix what is small and mechanical **in this session**. Anything larger becomes an issue for the next
      plan and a line in `TECH_DEBT.md` "## Next plan". Do not start a feature in a review.
      - Done: the five answered in the commit message, each with the command that settled it; `./gradlew test` green; pushed.
      - Rung: unit

- [ ] **J10 — Release v0.11.0 publicly. Issues #85 #86 #87.**
      - Bump `perchVersionCode` 13 → **14**, `perchVersionName` `0.10.0` → **`0.11.0`** at
        `app/build.gradle.kts:12-13`, the one place they live.
      - **Live acceptance first, bounded:** `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'`
        in the **foreground**, **at most two runs**; paste every gate's line. A network gate that fails
        twice is named in the commit and in "Known issues" and does not block.
      - `timeout 45m ./gradlew test --continue assembleRelease`, **not `clean`**. Rename Gradle's
        `app-release.apk` to `perch-0.11.0.apk`. **Verify on the file, not the build log.** This is a new
        machine, and a missing or unreadable `~/.perch/signing.properties` silently debug-signs. Check that
        `aapt2 dump badging` reads `versionCode='14' versionName='0.11.0'`, and that `apksigner verify --print-certs`
        prints U02's digest `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`. Paste
        both. **If the digest differs, stop:** mark this box `[BLOCKED: release signed with the wrong key]`,
        and do not tag, publish or delete anything. An APK with another key cannot update the human's
        phone without wiping it.
      - Release notes through `docs/RELEASE-NOTES.md`'s template (`scripts/release-notes.sh v0.10.0`
        drafts from the closed issues), written in the reader's words: photos that load lazily (IEEE
        Spectrum's, among others) now appear, with their caption and credit beneath them; code blocks
        with line numbers (Hexo blogs and others) read as code; and **Report** in an article's ⋮ menu
        opens a prefilled GitHub issue, filed under your own GitHub sign-in. "Installing / upgrading":
        installs in place over v0.10.0; no database change.
      - Tag `v0.11.0` at `HEAD`, push the tag, then `gh release create v0.11.0` with those notes and
        `perch-0.11.0.apk` attached. Close **#85, #86, #87**, naming the release. **Do not touch #82.**
      - Turnover edits, so the next session is not a loop session: CLAUDE.md's active-plan section
        says v0.11.0 shipped 2026-MM-DD and there is no active plan, and its finished-plans list gains
        v0.11.0 (J01–J10, #85–#87). `loop.sh:19` and `scripts/progress.sh:11` keep naming
        `PLAN-15.md`, so a stray launch fails loudly on the missing root file. `docs/plans/README.md`
        gains PLAN-15's row. Keep NOTES.md under 100 lines, with the new test floor and the APK path.
        **Tick this box in the same commit. Do not move this file**: the watching session archives it.
      - Done: `gh release view v0.11.0 --json assets` lists `perch-0.11.0.apk`; the badging and signature
        outputs are in the commit; `git status` is clean and pushed; `gh issue list --state open` lists
        none of #85–#87 (and still lists #82).
      - Rung: build
