# DESIGN.md — Perch

> **v0.2 note:** parts of this document describe v1 only. `docs/plans/PLAN-2-v0.2.md` §0 is authoritative
> where the two disagree (folders, OPML nesting, saved/liked entries, bundled mono font,
> full-text extraction). The task that touches a stale section amends it in the same commit.

The visual and interaction contract. UI tasks are critiqued **against this file**, not
against vibes. Quality bar: **Feeder** and **Read You**. If a screenshot disagrees with
a rule here, the screenshot is wrong.

---

## 1. Direction in one paragraph

Perch is a **reading** app, not a dashboard. The content is long-form technical writing
from people who care about typography, so the UI's job is to get out of the way: a calm
neutral surface, one accent colour used sparingly, generous line height, and no
chrome that isn't doing work. It should feel like the OS shipped it — Material 3
throughout, dynamic colour on Android 12+, real elevation semantics, no custom widgets
that fight the platform. Density is *comfortable-compact*: enough rows to scan a
morning's reading without a scroll marathon, never so tight that it reads as a table.

Three words: **quiet, legible, fast.**

## 2. Colour

- **Material 3 dynamic colour** (`dynamicLightColorScheme` / `dynamicDarkColorScheme`)
  on API 31+; on 26–30 fall back to a hand-built scheme seeded from
  **`#3F6E5A`** (a muted forest green — a perch, and it doesn't fight the blues and
  oranges of syntax-highlighted code in article images).
- **Dark mode is required and is the default assumption** for review — I read at night.
  Follow the system setting; a manual Light / Dark / System toggle lives in Settings.
- Roles, used strictly: `surface` for the app background, `surfaceContainer` for cards
  and sheets, `surfaceContainerHighest` for pressed/selected rows, `primary` for exactly
  one thing per screen, `error` for per-source failure only.
- **The unread dot is `primary`. Nothing else on a list row is coloured.** Read rows
  drop their title to `onSurfaceVariant`; that contrast delta is the entire read/unread
  affordance. No strikethrough, no opacity tricks, no badges on rows.
- Contrast floor: body text ≥ 4.5:1, metadata ≥ 3:1 against its own surface, in both
  schemes. Never `onSurfaceVariant` on `surfaceContainerHighest` for body copy.
- **`tertiary` is amber** (U09b), not the default hue+60 off the seed. The Perch mark's
  one accent is an amber block; an app whose only warm colour lives in its logo has two
  identities. Nothing consumed `tertiary` before, so the amber ramp lands in M3's
  contrasting-accent role and the mark and the scheme share a hue.

### The brand (U09b)

- The mark is **redrawn**, never scaled from `design/brand/*-source.png` — those are
  84×88 and 124×138. Its geometry lives once, as path data in `ui/theme/Brand.kt`,
  and the launcher's two vector layers restate the same strings under a test that fails
  if they drift.
- The mark does **not** follow the theme. Paper stays paper and ink stays ink in light
  and dark: a document that inverts stops reading as paper and starts reading as a
  different logo. Only the wordmark's lettering follows `onSurface`.
- **Launcher icon:** adaptive only (minSdk 26), with `background`, `foreground` **and**
  `monochrome`. All ink stays inside the centre **66dp circle** — the only region every
  launcher mask is guaranteed to show. The monochrome layer is drawn *lighter* than the
  colour one and leaves the sheets hollow; with no paper fill separating them, strokes
  at full weight close into a black slab.
- **In the app:** the full lockup heads the drawer, and the mark stands in for an icon
  in the no-sources-yet empty state — the one moment the reader is looking at Perch
  rather than at their feeds. It stands down whenever the drawer is in selection mode.

## 3. Typography

Material 3 type scale, one deviation: article body gets a real reading measure.

| Role | Token | Use |
|---|---|---|
| `headlineSmall` | 24/32 | Article title on the article screen |
| `titleMedium` | 16/24, **w600** | Entry title in list rows (max **3 lines**, ellipsis) |
| `bodyMedium` | 14/20 | Body copy in app furniture — settings, sheets, empty states |
| `labelMedium` | 12/16 | `Source / 5h` metadata line under a row's title |
| `titleLarge` | 22/28 | Top app bar title (`LargeTopAppBar` on home) |

**The article surface has its own serif type scale — see §8.** App furniture is sans
(Roboto); editorial content is serif (`FontFamily.Serif`). That split is deliberate.

- Article body max width **`72.dp * fontScale`-independent measure**: cap the text
  column at 680dp and centre it, so a tablet/landscape read isn't a 100-character line.
- Monospace (**JetBrains Mono**, 13sp, ligatures off) for `<pre>`/`<code>`, in a
  `surfaceContainer` block with 12dp padding, 8dp corners, and **horizontal scroll —
  code never wraps.**
- Respect the system font scale everywhere. Nothing may clip at 1.3× — that's a
  polish-pass check.
- **One** custom font is bundled, and only one (amended by U11). Everything else is
  platform families: `FontFamily.Default` (Roboto) for app furniture,
  `FontFamily.Serif` (Noto Serif, on-device) for the article surface — zero APK cost,
  no licensing, no download. The exception is the mono face, where the platform's
  Droid Sans Mono confuses `0`/`O` and `1`/`l` at 13sp, and code is the one place in
  the app where a misread character is a different program. JetBrains Mono
  (SIL OFL 1.1, licence verbatim in `app/src/main/assets/JetBrainsMono-OFL.txt`),
  regular weight only, 268 KB. Its programming ligatures are **disabled**: drawing
  `->` as `→` inside someone else's source is a substitution the reader did not ask
  for.

## 4. Spacing, density, shape

- 4dp grid. Tokens in `ui/theme/Dimens.kt`: `xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`.
- Screen horizontal padding **16dp**. List rows: 16dp horizontal, **12dp vertical**.
  Since U08 the 96dp thumbnail sets the floor, so a row is ~120dp whether its title runs
  to one line or three — comfortable-compact, and uniform.
- Row separation is **whitespace + a hairline `outlineVariant` divider inset 16dp from
  the left**, not cards. Cards on a feed list are noise.
- Corners: `medium` (12dp) for sheets/dialogs/code blocks, `full` for chips and the FAB.
- **Touch targets ≥ 48×48dp, always.** Icon buttons get `minimumInteractiveComponentSize`
  even when the glyph is 20dp. This is the single most common failure — check it in the
  polish pass on every screen.
- Edge-to-edge: `enableEdgeToEdge()`, content insets respected, no hardcoded status-bar
  padding. The list scrolls under a colour-shifting top app bar
  (`TopAppBarDefaults.enterAlwaysScrollBehavior`).
  **The contract is written once, in `ui/nav/PerchNavHost.kt`'s doc comment (V04):**
  Material's own chrome keeps its own inset defaults; content draws under the bars and only
  furniture moves; where two sibling surfaces would spend the same inset the shell consumes
  it (the bottom bar and the `NavHost`); and an overlay that bypasses a `Scaffold` by design
  — the image viewer — pads its furniture with `safeDrawing` itself. Do not add a
  `.statusBarsPadding()` to a screen: if a surface is under a bar, the contract is what is
  wrong, not that screen.

## 5. Navigation & structure

```
┌ FEED ────────────────────────────────┐
│ LargeTopAppBar: "Feed"   | source nm │  ← title reflects the active filter
│  ⋮ overflow: Mark all read, Refresh, │
│    Show read entries, Settings       │
│ ─────────────────────────────────────│
│ All Time ⌄                           │  ← U08a time range; a dropdown, never scrolls away
│ ─────────────────────────────────────│
│ Folder name                          │  ← section header, accent colour
│ EntryRow ×N  (pull-to-refresh)       │
│  ● Title (≤3 lines)        ┌───────┐ │
│    Source / 5h             │ thumb │ │
│                            └───────┘ │
│ Next folder                          │
│ EntryRow ×N                          │
│ ─────────────────────────────────────│
│  ▣ Feed    ▤ To-Read    ♡ Liked      │  ← U09 NavigationBar; hidden on the article
└──────────────────────────────────────┘
  ModalNavigationDrawer (swipe / hamburger) — scopes the Feed, nothing else:
    ▤ Perch                     ← U09b lockup; stands down in selection mode
      RSS READER
    All sources           (12)
    ─────────────────────────
    ⌄ Folder name         (9) ⋮  ← chevron: expand/collapse · name: scope the list
      ● source name       (3)     ⋮: rename / delete (absent on Uncategorized)
      ⚠ failing source    (!)   ← long-press a row: multi-select (U09a)
    ⌄ Uncategorized      (57)   ← always last; sources with no folder chosen
    ─────────────────────────
    + Add source                → bottom sheet (which also picks the folder)
    + New folder                → name dialog
    Settings
  Time is a *filter* and folder is a *section* (PLAN-2 §0): the range decides which
  entries survive, the headers decide where the survivors sit. Sections collapse away
  when the drawer has scoped the list to one folder or source, and when there is only
  one folder to begin with. The range belongs to the Feed alone — To-Read and Liked
  ignore it.
  FAB is NOT used on home. Adding a source is a drawer/overflow action; a FAB
  over a reading list is a Material cargo-cult.
```

- **Top-level navigation is the bottom bar (U09), not the drawer.** Three peers —
  **Feed · To-Read · Liked** — in a `NavigationBar` that is present on all three list
  destinations and **absent on the article screen**. Selected tabs take the filled glyph,
  unselected the outlined one: at a glance the shape reads before the tint. Each tab keeps
  its own scroll position and its own state across switches (`saveState`/`restoreState`,
  `launchSingleTop`), and the time range belongs to the Feed alone.
  **To-Read** and **Liked** draw the same `EntryRow` as the Feed — they are the same
  articles, and a second row shape would make them read as a different kind of object.
  What changes is the ordering (when the reader filed it, not when it was published), the
  absence of the drawer and the range, and the absence of folder sections: these lists are
  ordered by the reader's own gesture, and a folder header would cut across the one
  ordering that means anything in them. Each empty state says what its list is *for*.
- **Multi-select in the drawer (U09a).** A long press on a source or a folder header
  starts a selection; every row of *that kind* gains a checkbox, the "All sources" row and
  the drawer's three navigation items are replaced by a contextual bar — close · *N
  selected* · delete, plus rename (and move, for a source) at exactly one ticked row — and
  tapping toggles rows until the reader leaves. A selection is **homogeneous**: one
  started on a source takes only sources, one started on a folder takes only folders, and
  the rule is drawn as well as enforced — rows of the other kind keep their ordinary icon
  and carry no tick. Uncategorized draws a disabled box: §0 makes it undeletable.
  The two deletes differ because their risk does: **folders** move their sources to
  Uncategorized and get an undo snackbar that says so ("3 folders deleted · 12 sources
  moved to Uncategorized"), while **sources** cascade to their entries, saved and liked
  ones included, and so get a dialog that counts what is about to be lost. Back leaves
  selection before it closes the drawer — it is the first rung of §0's chain.
- **Row actions (U09)** live behind a long press, in a bottom sheet: *Save for later* ·
  *Like* · *Mark read/unread* · *Share*. Each toggle names the direction it is about to
  go — *Remove from To-Read*, never *Saved ✓* — because a verb that means one of two
  things depending on a checkmark is a question asked before the reader can press it.
  Taking something out of To-Read or Liked animates the row out and offers an undo
  snackbar; adding to one offers nothing, because nothing vanished.
- **Back is one ordered chain (PLAN-2 §0), not N handlers that happen to agree:**
  an overlay closes → an article pops → To-Read/Liked returns to Feed → a scrolled Feed
  scrolls to top → and only Feed-already-at-top leaves the app. The scroll-to-top rung is
  not a navigation, so it must not animate as one.

- **The time range (U08a)** is one control, not five: a text button carrying the active
  range and a chevron, set in the accent colour so it reads as a control rather than as a
  second title, opening a menu of the five with a tick against the active one. It says
  what the reader chose and nothing about what they did not — a row of five chips spends a
  band of the screen restating the four options nobody is choosing, and on a narrow phone
  it hides some of them behind a horizontal scroll while doing it. The empty bucket's
  "Show Past Week instead" moves this control's own selection; the two never disagree.

- **The entry row (U08),** built to `design/reference/feed-row-reference.jpg`: title
  (`titleMedium` w600, ≤3 lines) over a `Source / 5h` metadata line in
  `onSurfaceVariant`, and a **96dp square thumbnail** on the right with 12dp corners,
  cropped. Relative time is compact — `47min`, `5h`, `1d`, `3d`, then a date past a week.
  **No snippet:** the thumbnail does that work, and a row with both is a card in
  everything but name.
  The thumbnail square is **always reserved**. No image, an image in flight, and an image
  that 404s all draw the same hairline `outlineVariant` frame in the same footprint — a
  list that reflows as images arrive moves the row out from under the reader's thumb.
  Never a broken-image glyph, never a collapsed row. (The *article* surface does the
  opposite and collapses a failed figure — §8. A gap mid-sentence beats an empty frame;
  in a list, a stable footprint beats both.)
- **Article screen:** `TopAppBar` with back, the **Like and Read-later toggles** (filled
  when on, outlined when off — U09), and `Open in browser` (Custom Tab). Title, then `source · author · date`, then a
  hairline, then body. Scroll position survives rotation.
- **Add source sheet:** one text field (paste URL), one primary button. While resolving,
  the button becomes a spinner and the sheet shows the discovered feed title + entry
  count as confirmation *before* committing. Discovery failure renders inline under the
  field in `error`, with the URL still editable — never a toast, never a dead end.
- Back always goes back, along the chain above. The only step that leaves the app is the
  last one.

## 6. Motion

Restrained and standard. Material 3 defaults; do not hand-roll easing.

- Screen transitions: `slideInHorizontally` + fade, **250ms**, `FastOutSlowInEasing`.
- Drawer/sheet: platform defaults, untouched.
- List: `animateItemPlacement()` when entries arrive; **no staggered entrance
  animation** — a list that dances on every refresh is exhausting.
- Read-state change: 150ms colour crossfade on the title, dot fades out. That's it.
- Pull-to-refresh: M3 `PullToRefreshBox` indicator, no custom spinner.
- Zero decorative animation. If it doesn't communicate a state change, delete it.

## 7. States — every screen defines all four

| State | Rule |
|---|---|
| **Loading** | First load only: 6 shimmer-free skeleton rows (`surfaceContainer` blocks). Refreshes use the pull indicator, never a blocking spinner, never a full-screen replace. |
| **Empty** | Centred icon (48dp, `onSurfaceVariant`; the 72dp brand mark in the *no sources yet* case — U09b), one-line title, one-line explanation, one action button. Distinct copy per case: *no sources yet* → "Add your first source"; *all read* → "You're all caught up" + "Show read entries"; *source has no entries* → "Nothing here yet"; *empty time window* (U07) → "Nothing in this window" + **Show &lt;next window&gt; instead**, never a blank screen. |
| **Error** | Per-source failures **never block the list**. The feed shows its last-known entries plus a `⚠` in the drawer; tapping shows the message + Retry. Global failure (all feeds failed) = inline banner above the list, dismissible, with Retry. |
| **Offline** | Detected via connectivity, shown as a slim inline banner: "Offline — showing saved entries". Cached content stays fully readable and navigable. Never a blocking dialog. |

Snackbars for undoable actions (mark-all-read, remove source). Toasts: never.

## 8. The reading surface — one standardized, editorial article view

This is the heart of the app and the thing most RSS readers get wrong. Forty-two
sources ship forty-two different HTML dialects — WordPress soup, Hugo output,
Blogspot templates, hand-written XHTML. **The reader's job is to erase that.** Every
article, whatever its origin, is normalized into the same canonical block model
(SPEC.md §5, `ArticleBlock`) and rendered by exactly one renderer. A post from
`nullprogram.com` and a post from `krebsonsecurity.com` must be typographically
indistinguishable in structure — same measure, same rhythm, same code and image
treatment. Source identity belongs in the byline, never in the layout.

**Direction: New York Times.** The app *furniture* (list, drawer, sheets, settings)
stays Material 3 sans — that's the platform. The *article surface* is editorial:
serif, generous, quiet, print-derived. Rendered natively into Compose
(`RichText.kt`) — **no WebView**, so theming, selection, and dark mode are ours.

### Type on the article surface

Serif is `FontFamily.Serif` (Noto Serif, on-device). The only bundled face is the
mono one — see §3.

| Element | Spec |
|---|---|
| Headline | **Serif**, 30sp/36, w700, `onSurface`, left-aligned, never centred |
| Standfirst (summary, when it adds anything) | Serif, 18sp/26, `onSurfaceVariant`, italic |
| Byline | **Sans**, 12sp, w500, letterSpacing 0.06em, uppercase: `SOURCE · AUTHOR · 12 MAR 2026` |
| Body | **Serif**, 18sp / **29sp line height** (1.6), `onSurface` at 0.92 alpha |
| Section head (`h2`) | Serif, 22sp/28, w700 · (`h3`) 19sp/26, w600 |
| Caption | Sans, 13sp/18, `onSurfaceVariant`, above-the-fold hairline rule |
| Pull-quote (`blockquote`) | Serif, 21sp/30, italic, no quotation marks |

- **Measure caps at 680dp** and centres. 30–36 characters per line on a phone.
- Paragraph rhythm: 0 top margin, **16dp** bottom. No first-line indent (web idiom, not print).
- Section heads: **32dp** above, 10dp below. Whitespace does the separating, not rules.
- Text is **selectable**. Long-press → copy.

### The block treatments — identical across every source

- **Code** (`pre`/`code`): the one place sans wins — JetBrains Mono 13sp/20 on
  `surfaceContainer`, 8dp corners, 14dp padding, full text-column width,
  **horizontal scroll, never wrapped, never reflowed**. A hairline `outlineVariant`
  border in light mode. Inline `code` gets a subtle `surfaceContainerHigh` chip with
  2dp horizontal padding — no border, no colour.
- **Syntax highlighting** (U11, reversing §8's original "no highlighting" line — the
  objection was *half-right* highlighting, and the answer turned out to be a smaller
  vocabulary rather than no colour). Five token roles, no more: keyword, string,
  number, comment, meta. Colours are theme values in `ui/theme/CodeTheme.kt`, one set
  per background, provided through `LocalCodeColors`; they are the only hues in the app
  that are not tonal stops off `#3F6E5A`, because three of the five must be told apart
  at 13sp. The language comes from the author's own `class="language-*"` — **a
  declaration is final, including `plaintext`** — and only an *undeclared* block is
  sniffed. An unknown language renders in the mono face with no colour, which is
  exactly what every block looked like before U11.
- **Line numbers**: a right-aligned gutter in the mono face, `outline` colour, 12dp
  from the code, omitted on a one-line block. Two things are load-bearing. It is
  **outside the horizontal scroll**, so scrolling a wide line moves the code and leaves
  the numbers; and it is **its own composable inside a `DisableSelection`**, so
  selecting the block yields runnable code with no numbers to strip. One `Text` holds
  every number, which is what keeps the code's left edge still at 9→10 and 99→100.
- **Images**: full text-column width, 4dp corners (editorial, not app-y), intrinsic
  aspect ratio reserved *before* load so nothing reflows. `figcaption` → Caption style
  directly beneath, 8dp gap. A failed load collapses to nothing — never a broken glyph,
  never a grey box mid-sentence. 24dp above and below.
  **Tapping a figure opens it full screen** (U12): the article goes to a near-opaque
  scrim, the image fades up to fit the width, and pinch, double-tap and pan take over.
  Zoom is clamped 1×–5× with a rubber band past the stops rather than a hard wall, and pan
  is fenced to the scaled image so it can never be flung into empty space. **Drag-down
  dismisses only at fit** — while zoomed every drag is a pan, or the viewer closes itself
  under a reader who is examining a corner of a schematic. The scrim's alpha tracks the
  drag, so the article reappearing *is* the affordance that says the gesture will dismiss.
  Back, a tap anywhere, and a close button in the top-left all leave; the viewer is an
  overlay over the article rather than a destination, so what it closes back to is the
  article itself, still scrolled where it was. Its furniture is the one thing in the app
  that does not take a theme colour — the overlay is dark in both themes, so it addresses
  `ViewerColors` (`ui/theme/Color.kt`) instead of `onSurface`, which would vanish in light.
- **Pull-quotes**: 24dp vertical margin, 20dp left inset, 2dp `primary` left rule at
  0.4 alpha. Attribution line in Caption style.
- **Lists**: 20dp marker gutter, hanging indent so wrapped lines align to the text.
  8dp between items. `ol` markers in sans tabular figures.
- **Rules** (`hr`): not a full-width line — a centred 32dp `outlineVariant` hairline
  with 32dp of air, the way a print section break reads.
- **Tables** (U11a): sans 14sp, hairline `outlineVariant` rules between rows and one
  closing the foot, 12dp/8dp cell padding, 24dp of air above and below — a table is a
  block like a figure, not a paragraph. A **header row** (any `<th>` in the first row)
  takes w600 on a `surfaceContainer` tint; a first row of `<td>` stays a body row however
  much it reads like a heading. **Columns are measured from their own text**, held between
  56dp and 260dp: a `Yes`/`No` column and a paragraph of impact text do not want the same
  slot. A column at the ceiling **wraps inside itself** rather than pushing the table
  wider. Beyond the measure the table **scrolls horizontally and the header scrolls with
  it** — a frozen header sounds better until the body slides and every value sits under
  the wrong name. Rules and tint are drawn at the table's own content width, never
  `fillMaxWidth`, which inside a scroll measures to zero. A column whose every written
  cell is a number is **right-aligned**, header included; one `N/A` and it reads left
  again. Tables from feeds are rare and usually broken; never let one widen the page.
- **Links**: `onSurface` with a 1dp `primary`-at-0.5 underline offset 3dp — an editorial
  underline, not a blue hyperlink. Custom Tab on tap.
- **Anything unmapped** (iframes, embeds, video): a single tasteful inline card —
  "Embedded content · open on the web" — never an empty hole, never raw markup.

### Normalization rules the renderer must enforce

These are what make heterogeneous sources look like one publication:

- Strip leading/trailing decoration many feeds append: share widgets, "Read more"
  stubs, "The post X appeared first on Y", subscribe CTAs, comment counts.
- Collapse `<div>`/`<span>` wrapper soup — structure comes from the block model, never
  from the source's nesting.
- **Drop all inline styles, colours, font sizes, and alignment** from source HTML. The
  source does not get a vote on typography. This is non-negotiable and is the single
  rule that makes 42 sources look like one app.
- Consecutive empty paragraphs and `<br><br>` become one paragraph break.
- A stripped or empty body falls back to the summary plus a prominent
  "Read on the web" button — an honest empty state, not a blank page.

## 9. "Feels like a real app" checklist

The polish pass (T29) walks this list per screen. Every line is pass/fail.

- [ ] Every tappable thing is ≥48dp and has a ripple.
- [ ] Dark and light both checked; nothing is a pure-black or pure-white slab.
- [ ] No `TODO`, `Lorem`, placeholder strings, debug borders, or logcat spam in a
      release-shaped build. No hardcoded strings in Composables — all in `strings.xml`.
- [ ] Spacing is on the 4dp grid; optical alignment of the unread dot to the title's
      first line, not to the row centre.
- [ ] Long titles (150 chars), long source names, and 0/1/999+ unread counts all render
      without clipping or layout jump.
- [ ] Font scale 1.0 and 1.3 both legible and unclipped.
- [ ] Empty, loading, error, offline all reachable and all styled — no bare white screen.
- [ ] Rotation and process-death restore scroll position and the active filter.
- [ ] Refresh never blocks scrolling; the list never jumps under the finger.
- [x] App icon is a real adaptive icon, not the green Android robot — and its ink
      survives the circle, squircle and rounded-square masks (U09b).
- [ ] Content descriptions on every icon-only control (TalkBack can traverse a row and
      reach: open, source, read-state).
- [ ] First launch with zero sources looks intentional, not broken.
