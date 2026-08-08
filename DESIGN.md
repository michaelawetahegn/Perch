# DESIGN.md — Perch

> **v0.2 note:** parts of this document describe v1 only. `PLAN-2.md` §0 is authoritative
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

## 3. Typography

Material 3 type scale, one deviation: article body gets a real reading measure.

| Role | Token | Use |
|---|---|---|
| `headlineSmall` | 24/32 | Article title on the article screen |
| `titleMedium` | 16/24, w500 | Entry title in list rows (max **3 lines**, ellipsis) |
| `bodyMedium` | 14/20 | Entry snippet in list rows (max **2 lines**) |
| `labelMedium` | 12/16 | Source name · relative time metadata line |
| `titleLarge` | 22/28 | Top app bar title (`LargeTopAppBar` on home) |

**The article surface has its own serif type scale — see §8.** App furniture is sans
(Roboto); editorial content is serif (`FontFamily.Serif`). That split is deliberate.

- Article body max width **`72.dp * fontScale`-independent measure**: cap the text
  column at 680dp and centre it, so a tablet/landscape read isn't a 100-character line.
- Monospace (`FontFamily.Monospace`, 13sp) for `<pre>`/`<code>`, in a
  `surfaceContainer` block with 12dp padding, 8dp corners, and **horizontal scroll —
  code never wraps.**
- Respect the system font scale everywhere. Nothing may clip at 1.3× — that's a
  polish-pass check.
- No custom fonts **bundled**. Platform families only: `FontFamily.Default` (Roboto)
  for app furniture, `FontFamily.Serif` (Noto Serif, on-device) for the article
  surface. Zero APK cost, no licensing, no download.

## 4. Spacing, density, shape

- 4dp grid. Tokens in `ui/theme/Dimens.kt`: `xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32`.
- Screen horizontal padding **16dp**. List rows: 16dp horizontal, **12dp vertical**,
  giving ~88dp tall rows with a 3-line title — comfortable-compact.
- Row separation is **whitespace + a hairline `outlineVariant` divider inset 16dp from
  the left**, not cards. Cards on a feed list are noise.
- Corners: `medium` (12dp) for sheets/dialogs/code blocks, `full` for chips and the FAB.
- **Touch targets ≥ 48×48dp, always.** Icon buttons get `minimumInteractiveComponentSize`
  even when the glyph is 20dp. This is the single most common failure — check it in the
  polish pass on every screen.
- Edge-to-edge: `enableEdgeToEdge()`, content insets respected, no hardcoded status-bar
  padding. The list scrolls under a colour-shifting top app bar
  (`TopAppBarDefaults.enterAlwaysScrollBehavior`).

## 5. Navigation & structure

```
┌ HOME ────────────────────────────────┐
│ LargeTopAppBar: "Unread" | source nm │  ← title reflects the active filter
│  ⋮ overflow: Mark all read, Refresh, │
│    Show read entries, Settings       │
│ ─────────────────────────────────────│
│ (Today)(Past Week)(Past Month)…      │  ← U07 time filter; scrolls, never scrolls away
│ ─────────────────────────────────────│
│ Folder name                          │  ← section header, accent colour
│ EntryRow ×N  (pull-to-refresh)       │
│  ● Title (≤3 lines)                  │
│    Snippet (≤2 lines)                │
│    Source · 3h ago          [thumb]  │
│ Next folder                          │
│ EntryRow ×N                          │
└──────────────────────────────────────┘
  ModalNavigationDrawer (swipe / hamburger) — scopes the Feed, nothing else:
    All unread            (12)
    ─────────────────────────
    ⌄ Folder name         (9) ⋮  ← chevron: expand/collapse · name: scope the list
      ● source name       (3)     ⋮: rename / delete (absent on Uncategorized)
      ⚠ failing source    (!)   ← long-press a source: rename / move / remove
    ⌄ Uncategorized      (57)   ← always last; sources with no folder chosen
    ─────────────────────────
    + Add source                → bottom sheet (which also picks the folder)
    + New folder                → name dialog
    Settings
  Time is a *filter* and folder is a *section* (PLAN-2 §0): the chips decide which
  entries survive, the headers decide where the survivors sit. Sections collapse away
  when the drawer has scoped the list to one folder or source, and when there is only
  one folder to begin with. The chips belong to the Feed alone — To-Read and Liked
  ignore them.
  FAB is NOT used on home. Adding a source is a drawer/overflow action; a FAB
  over a reading list is a Material cargo-cult.
```

- **Article screen:** `TopAppBar` with back, `Open in browser` (Custom Tab), and
  overflow (`Mark unread`, `Share`). Title, then `source · author · date`, then a
  hairline, then body. Scroll position survives rotation.
- **Add source sheet:** one text field (paste URL), one primary button. While resolving,
  the button becomes a spinner and the sheet shows the discovered feed title + entry
  count as confirmation *before* committing. Discovery failure renders inline under the
  field in `error`, with the URL still editable — never a toast, never a dead end.
- Back always goes back. No custom back interception except closing the drawer/sheet.

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
| **Empty** | Centred icon (48dp, `onSurfaceVariant`), one-line title, one-line explanation, one action button. Distinct copy per case: *no sources yet* → "Add your first source"; *all read* → "You're all caught up" + "Show read entries"; *source has no entries* → "Nothing here yet"; *empty time window* (U07) → "Nothing in this window" + **Show &lt;next window&gt; instead**, never a blank screen. |
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

Serif is `FontFamily.Serif` (Noto Serif, on-device — still no bundled fonts).

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

- **Code** (`pre`/`code`): the one place sans wins — `FontFamily.Monospace` 13sp/20 on
  `surfaceContainer`, 8dp corners, 14dp padding, full text-column width,
  **horizontal scroll, never wrapped, never reflowed**. A hairline `outlineVariant`
  border in light mode. Inline `code` gets a subtle `surfaceContainerHigh` chip with
  2dp horizontal padding — no border, no colour. **No syntax highlighting**: the feed
  gives us no reliable language, and half-right highlighting looks worse than none.
- **Images**: full text-column width, 4dp corners (editorial, not app-y), intrinsic
  aspect ratio reserved *before* load so nothing reflows. `figcaption` → Caption style
  directly beneath, 8dp gap. A failed load collapses to nothing — never a broken glyph,
  never a grey box mid-sentence. 24dp above and below.
- **Pull-quotes**: 24dp vertical margin, 20dp left inset, 2dp `primary` left rule at
  0.4 alpha. Attribution line in Caption style.
- **Lists**: 20dp marker gutter, hanging indent so wrapped lines align to the text.
  8dp between items. `ol` markers in sans tabular figures.
- **Rules** (`hr`): not a full-width line — a centred 32dp `outlineVariant` hairline
  with 32dp of air, the way a print section break reads.
- **Tables**: horizontally scrollable, hairline row separators, sans 14sp, header row
  w600. Tables from feeds are rare and usually broken; never let one widen the page.
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
- [ ] App icon is a real adaptive icon, not the green Android robot.
- [ ] Content descriptions on every icon-only control (TalkBack can traverse a row and
      reach: open, source, read-state).
- [ ] First launch with zero sources looks intentional, not broken.
