# DESIGN.md — Perch

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
| `bodyLarge` **+ 1.55 line height** | 16/25 | **Article body only** |
| `titleLarge` | 22/28 | Top app bar title (`LargeTopAppBar` on home) |

- Article body max width **`72.dp * fontScale`-independent measure**: cap the text
  column at 680dp and centre it, so a tablet/landscape read isn't a 100-character line.
- Monospace (`FontFamily.Monospace`, 13sp) for `<pre>`/`<code>`, in a
  `surfaceContainer` block with 12dp padding, 8dp corners, and **horizontal scroll —
  code never wraps.**
- Respect the system font scale everywhere. Nothing may clip at 1.3× — that's a
  polish-pass check.
- No custom fonts bundled. Platform default (Roboto) only.

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
│ EntryRow ×N  (pull-to-refresh)       │
│  ● Title (≤3 lines)                  │
│    Snippet (≤2 lines)                │
│    Source · 3h ago          [thumb]  │
└──────────────────────────────────────┘
  ModalNavigationDrawer (swipe / hamburger):
    All unread            (12)
    ─────────────────────────
    ● source name         (3)   ← long-press: rename / remove
    ⚠ failing source      (!)   ← error state, tappable for the message
    ─────────────────────────
    + Add source                → bottom sheet
    Settings
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
| **Empty** | Centred icon (48dp, `onSurfaceVariant`), one-line title, one-line explanation, one action button. Distinct copy per case: *no sources yet* → "Add your first source"; *all read* → "You're all caught up" + "Show read entries"; *source has no entries* → "Nothing here yet". |
| **Error** | Per-source failures **never block the list**. The feed shows its last-known entries plus a `⚠` in the drawer; tapping shows the message + Retry. Global failure (all feeds failed) = inline banner above the list, dismissible, with Retry. |
| **Offline** | Detected via connectivity, shown as a slim inline banner: "Offline — showing saved entries". Cached content stays fully readable and navigable. Never a blocking dialog. |

Snackbars for undoable actions (mark-all-read, remove source). Toasts: never.

## 8. Article rendering

Rendered natively from sanitized HTML into Compose (`RichText.kt`) — **no WebView**.

- Paragraph spacing 12dp; `h2/h3` get 24dp top / 8dp bottom.
- Links: `primary`, no underline, 48dp-tall tap slop, open in a Custom Tab.
- `blockquote`: 3dp `outlineVariant` left rule, 12dp inset, `onSurfaceVariant` italic.
- Images: full-bleed to the text column, `RoundedCornerShape(8dp)`, aspect ratio held
  from intrinsic size to prevent reflow jank, Coil with a `surfaceContainer` placeholder
  and a graceful failure box (never a broken-image glyph).
- Lists: 8dp marker gutter, hanging indent — wrapped lines align to the text, not the bullet.
- A stripped/empty body falls back to the summary plus a prominent "Read on the web" button.

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
