# PLAN-13 — v0.9.0: a PDF is something you can put on To-Read and read there

One reader request, **#72** — "some things I want to read later are in PDF form" — with the shape
settled in conversation on 2026-09-20/21 against rendered mocks: a PDF is **stored whole and read
as pages** inside the article surface, page-width fit and untouched, with pinch zoom and a
double-tap that zooms to the text column; the three ways in are a pasted URL, the share sheet (a
link or a file) and a file picker. One **MINOR** release. The one issue is **#72**: there is no other, every task comments on it with its commit and what was
verified so the human can watch from the tracker, and this file's §0 outranks the issue body.

Sessions read this file cold; every task carries its anchors so a session **reads, never
searches**. Anchors are line numbers at `5f82eca` (the v0.8.0 archive commit) unless a task says
otherwise; if a line has drifted, grep the *name* quoted beside it — never the number.

## §0 — Decisions for this version (authoritative; do not re-derive)

### §0.1 The version is `0.9.0`, `versionCode` **11**, database **10 → 11**

A new user-visible capability, so the **MINOR** digit moves (SPEC.md §1). `perchVersionCode` 10 → 11,
`perchVersionName` `0.8.0` → `0.9.0`, at `app/build.gradle.kts:12-13` and **nowhere else**. One task
(G01) touches the schema, with a real `Migration`, an exported
`app/schemas/dev.mkiros.perch.data.db.PerchDatabase/11.json` (Room writes it on the next compile through
`room.schemaLocation`; `./gradlew :app:kspDebugKotlin` is the cheapest task that does; **commit it**) and
a `PerchMigration10To11Test` beside `PerchMigration9To10Test` (`app/src/test/.../data/db/PerchMigration9To10Test.kt:25`;
copy its `ExportedSchemas.seedVersion` / `openAtCurrentVersion` shape, `ExportedSchemas.kt:42/57`).
`fallbackToDestructiveMigration()` never comes back. v0.8.0 is on the reader's phone.

**No new dependency.** Rendering is the platform's own `android.graphics.pdf.PdfRenderer` (API 21+;
`minSdk` is 26). Metadata is read by a pure-JVM scanner of Perch's own (§0.3) using `java.util.zip.Inflater`.
SPEC.md §2's "deliberate omissions" list gains nothing and loses nothing.

### §0.2 A stored document is an entry one column, one directory, one sweep

**The row.** A saved PDF is a row on the seeded saved-links feed (`FeedEntity.SAVED_LINKS_FEED_URL`,
`perch:saved-links`), exactly as a pasted link is (PLAN-6 §0.3, PLAN-9 §0.3): it is on To-Read, it can be
liked, it is searchable by title, it never appears in the Feed and never counts toward a badge. Every
`isSynthetic` predicate SPEC.md §4 lists already does this; nothing new is filtered.

**One column.** `entries.documentPath TEXT` (nullable, default null): the file's path **relative to
`filesDir`** — `documents/<uuid>.pdf`. Schema **11** = `ALTER TABLE entries ADD COLUMN documentPath TEXT`,
the shape of `MIGRATION_7_8` (`PerchDatabase.kt:204`, one additive column). `EntryEntity` gains
`val documentPath: String? = null` after `scrollPosition` (`EntryEntity.kt:57-80`). Nothing else changes
shape: page count is read from the file when it is opened (the renderer is authoritative for what will be
shown), the kind is the extension (`.pdf` is the only kind this version stores), and the thumbnail is an
ordinary `imageUrl` (§0.4).

**Carried like the reader's flags.** `EntryDao.upsertAll`'s existing-row copy (`EntryDao.kt:590-604`)
adds `documentPath = entry.documentPath ?: existing.documentPath` — a later write of the same row that
says nothing about the file must not forget it. (`deleteReadOlderThan`, `EntryDao.kt:443`, needs no edit:
it already exempts saved and liked rows, and never runs against the synthetic feed.) The profile (U14)
does **not** carry `documentPath` or the file; Auto Backup's include list (`app/src/main/res/xml/backup_rules.xml`,
mirrored in `data_extraction_rules.xml`) is left alone, so documents deliberately do **not** travel to a new
phone — a restored row whose file is missing is what the sweep is for. Say both in SPEC.md §4 (G01).

**The row for a list.** `EntryQueries.ROW` (`EntryDao.kt:49`) projects `(e.documentPath IS NOT NULL) AS isDocument`
and `EntryListItem` (`EntryListItem.kt:31-46`) gains `val isDocument: Boolean = false` — with a default,
unlike `publishedIsEstimated`, because a row that forgets to say it is a document merely loses its label.

**The page you stopped on** lives in the existing `scrollPosition` column (E01): **for a document row it
holds the page number** (1-based; 0 is the top). No second column — the two meanings never coexist on
one row, `ArticleViewModel.saveScrollPosition` (`ArticleViewModel.kt:198-202`, F06's dedupe included)
needs no change, and the profile already declines to export it. State it in `EntryEntity`'s KDoc and SPEC.md §8.

**One directory.** `DocumentStore` (`data/document/DocumentStore.kt`), constructed on `File(context.filesDir, "documents")`:
`newDocument(): File` (`<uuid>.pdf`), `thumbnailFor(document: File): File` (`<uuid>-1.png` beside it),
`resolve(relativePath): File`, `relativize(file): String`, `delete(document)` (file **and** thumbnail), and
`sweep(rows: List<DocumentRow>): List<Long>` — deletes every file in the directory that no row names, and
every file whose row is **neither saved nor liked**, returning the ids whose row must forget the file.
`DocumentRow(id, documentPath, isSaved, isStarred)` comes from a new `EntryDao.documentRows()`; the store
never touches Room. `EntryRepository.sweepDocuments()` runs the two together and calls
`EntryDao.clearDocument(id)` (sets `documentPath = NULL, imageUrl = NULL`) for each id returned.
**It runs once per process start**, from `PerchApp.onCreate`'s existing `startupScope.launch` (`PerchApp.kt`,
the `WorkScheduler.ensureScheduled` call) — never on a flag change, because *Removed from To-Read* has an
Undo (`CollectionScreen.kt:95-103`) and a file deleted under an Undo cannot come back. A row whose file was
swept reads as a pasted link with no body: the article screen shows *Read on the web* when it has a link
(`ArticleScreen`'s existing `EmptyBody`), and for a document with no link says it is gone (§0.6).

### §0.3 The title and the date come from the PDF itself standards, no dependency, no site

`PdfInfo` (`data/document/PdfInfo.kt`), pure JVM, `File`-in, value-out — the shape of `PageMetadataExtractor`:

```kotlin
data class PdfInfo(val title: String?, val creationDate: Instant?)
object PdfInfoReader { fun read(file: File): PdfInfo }   // never throws; a file it cannot read is PdfInfo(null, null)
```

**Where a title is looked for, in order.** (1) The document information dictionary (ISO 32000-1 §14.3.3):
the **last** `/Info N 0 R` reference in the file (incremental updates append newer trailers), resolved to
object `N` — as a plain `N 0 obj` **or inside a compressed object stream** (§7.5.7: every `/Type /ObjStm`
stream, inflated with `java.util.zip.Inflater`, its header of `objnum offset` pairs scanned for `N`) — and
its `/Title`. (2) XMP (§14.3.2): the first `<dc:title>` … `<rdf:li …>text</rdf:li>`, XML-unescaped, in any
uncompressed metadata stream. (3) Nothing. Strings are decoded per §7.9.2: a literal `(…)` with `\` escapes
and balanced nested parentheses, a hex `<…>`, a leading UTF-16BE BOM (`FE FF`) → UTF-16, otherwise
PDFDocEncoding read as ISO-8859-1; one level of indirect reference (`/Title 12 0 R`) is followed. Whitespace
collapsed, trimmed. **A file with `/Encrypt` in its trailer yields no title and no date** — its strings are
ciphertext (`encrypted-empty-user-password` is the fixture) — and the caller's file-name rung answers.
**Producer boilerplate is not a title:** `Print`, `Untitled`, `untitled`, `PowerPoint Presentation`, `Slide 1`
and a title equal to the file name are rejected; a `Microsoft Word - ` prefix and a `.doc`/`.docx`/`.indd`/`.tex`/`.dvi`
suffix are stripped. That list is a *producer* fact, not a site fact; PLAN-10 §0.2's grep gate stays empty.

**The date** is the same dictionary's `/CreationDate` (§7.9.4, `D:YYYYMMDDHHmmSSOHH'mm'`), parsed with the
same leniency `DateParser` gives feeds (missing seconds, missing zone → UTC, `Z`). It becomes `publishedAt`
with `publishedIsEstimated = false`; absent, the row is dated now and says so, the pasted-link rule.

**The corpus is the contract** — `fixtures/documents/*.pdf` and `fixtures/documents/manifest.tsv` are committed
with this plan (columns `slug bytes sha256 pages page1_pt title published producer origin`), and the test
iterates the manifest, so adding a fixture is adding a row. What each one holds is in its `origin` column;
the ones that decide the rules are `nist-sp800-63-4` (Info dictionary inside an object stream, XMP says
"Print"), `ssrn-6191618` (the reader's sample: UTF-16BE hex title, object streams), `letter-margins`
(escaped parentheses in a literal), and the three that must answer **null** — `empty-title`, `scan-image-only`,
`encrypted-empty-user-password`. `html-in-disguise.pdf` is not a document and `read()` says so quietly.
**No `PdfInfo` page count** and no text extraction: API 35's `PdfRenderer.Page.getTextContents()` would
give the first line of page one as a title rung and a search index, but it runs on one Android version,
on the device only — it goes to `TECH_DEBT.md`, not into this plan.

### §0.4 A pasted link to a PDF is stored, not parsed

**The fetch streams to a file first.** `FeedFetcher.download(url: String, into: File): DownloadResult`
(beside `fetch`, `FeedFetcher.kt:56`; same client, same redirect policy, same `unreachable`/`httpFailure`
phrasing — reuse them) writes the body to `into` through okio up to **`DOCUMENT_MAX_BYTES = 40 MiB`**
(declared `Content-Length` first, then the stream, as `read()` does at `:84-101`), and returns
`DownloadResult.Success(finalUrl, contentType, contentDisposition, bytes)` or `Failure(message)`. The 8 MiB feed
cap (`:118`) is untouched — SPEC.md §6 gains one sentence for the document cap (G04).

**`SavedLinkRepository.saveLink` (`SavedLinkRepository.kt:52`) fetches once, through `download`, into
`DocumentStore.newDocument()`.** Then, in order: if the first 1 KiB contains `%PDF-` **or** `contentType`
starts with `application/pdf` → the **document branch**; else the file's bytes are read back (over 8 MiB →
the existing "too large" failure, and the file deleted) and the existing path continues unchanged from
`parser.parse` (`:66`) onward — `PageContentExtractor.parse` (`PageContent.kt:62`) still never sees a PDF.
The eight cases in `SavedLinkRepositoryTest` (`:66-176`) stay green **unchanged**; the repository gains two
constructor parameters, `documents: DocumentStore` and `rasterizer: PageRasterizer`, wired in
`AppContainer.kt:116-123` and `PerchRule.kt:53-59`.

**The document branch** is one private `storeDocument(file, link, guid, nameHint, isSaved = true)` shared with
§0.8's local-file path:

1. `rasterizer.open(file)` (§0.5). Null, or zero pages → the file is deleted and the result is
   `SaveLinkFailure.Unreachable("$url is not a readable page.")` — the existing vocabulary; the sheet's
   wording does not change.
2. **Title**, first non-null of: `PdfInfoReader.read(file).title` → the `Content-Disposition` `filename`
   (RFC 6266; `filename*=` UTF-8 form first) → the URL's last path segment → for a local file its display
   name → `"Document"`. **A file name becomes a title** by dropping one extension and turning `_` into
   spaces — nothing else (`NIST.SP.800-63-4`, `1706.03762v7`, `ssrn-6191618` stay as they are).
3. **Date**: `creationDate` → `publishedIsEstimated = false`; else now, `true`.
4. **Thumbnail**: page one rendered at **256 px wide**, cropped to its **top square**, written as PNG to
   `DocumentStore.thumbnailFor(file)`; `imageUrl = file.toURI().toString()` (`file:///…`, which Coil's
   default loader maps to a `File` unchanged — `EntryRow.kt:210`'s `SubcomposeAsyncImage(model = url)`
   needs no edit).
5. The row: `guid = link = finalUrl` (a local file: `guid = "perch:document:<sha256 of the bytes>"`,
   `link = null`), `title`, `author = null`, `summary = null`, `contentHtml = null`, `documentPath =
   documents.relativize(file)`, `isSaved = true, savedAt = now`, `fetchedAt = now`. Through `entryDao.upsertAll`
   as today (`:88-99`) — idempotent on `(feedId, guid)`, so re-pasting the URL lands on the same row, and
   the `setSaved` re-arm at `:97` still puts a removed document back on the queue. **The previous file, if
   the row already had one, is deleted** before the new path is written (`documents.delete`).
6. `entryDao.index(entry)` (`EntryDao.kt:544`) as `upsertAll` already does — the FTS body is empty, the
   title is searchable, and that is the whole of search for a document this version (say so in SPEC.md §8a).

**A fact the G04 comment on #72 must state:** SSRN — the site the reader's sample came from — answers
**403** to every non-browser client, Perch's `User-Agent` included (measured 2026-09-21 with
`Perch/1.0 (Android; +local-first RSS reader)`). Perch does not impersonate a browser (the gijn.org rule,
CLAUDE.md), so an SSRN paper arrives through the browser's own download shared to Perch — §0.8's path —
never by pasting its URL. The sample is in the corpus as a *file* for exactly that reason.

### §0.5 The rasterizer is a seam, because `PdfRenderer` cannot run under Robolectric (measured)

Planning ran `PdfRenderer` under Robolectric in NATIVE and LEGACY graphics modes, through
`ParcelFileDescriptor.open` and `.dup`: every variant dies in `NoSuchMethodError: FileDescriptor.getOwnerId$()`
before a page is opened. **So no JVM test may construct `PdfRenderer`**, and every screen that shows a page
is drawn, in tests, from a fixture. That is a seam, not a shortcut:

```kotlin
interface PageRasterizer { fun open(file: File): PageSource? }          // null: not a document this renderer can read
interface PageSource : Closeable {
    val pageCount: Int
    fun size(index: Int): PageSize                                       // PDF points; PageSize(width: Int, height: Int)
    fun render(index: Int, widthPx: Int): Bitmap                         // ARGB_8888, height = widthPx / aspect, white ground
}
```

- **`PdfRendererRasterizer`** (`data/document/PdfRendererRasterizer.kt`, the production one, wired in
  `AppContainer` as the default `rasterizer` parameter): `ParcelFileDescriptor.open(MODE_READ_ONLY)`,
  `PdfRenderer`, `openPage(index)` → `render(bitmap, null, null, RENDER_MODE_FOR_DISPLAY)` on a white-filled
  bitmap, `close()`. **`PdfRenderer` is not thread-safe and allows one open page at a time**: every call on a
  `PageSource` runs under one `Mutex` per source, on `Dispatchers.IO`. A `SecurityException` (a password the
  reader does not have) or `IOException` in `open` returns null. It is exercised only on the emulator (G14).
- **`FixtureRasterizer`** (`app/src/test/.../support/FixtureRasterizer.kt`, visible to `testDebug` the way
  `PerchRule` is): identifies a file by its **SHA-256 against `fixtures/documents/manifest.tsv`**, answers
  `pageCount` and sizes from the manifest (`pages`, `page1_pt`; every page is page one's size except
  `mixed-sizes`, whose three sizes the fixture reader hard-codes from `origin`), and `render`s by decoding
  `fixtures/documents/rendered/<slug>-<n>.png` (600 px wide, rendered by pdfium — the same engine Android
  ships — at planning) scaled to `widthPx`; a page with no PNG renders a white bitmap with a grey rule
  across it, so a list of 112 pages still has 112 pages. An unknown file → null. `DocumentFixtures`
  (same package) reads the manifest and finds the repository root the way `CodeScreenshotTest.repoRoot()` does.
- **Rendering policy in the UI (§0.6):** a page bitmap is `min(viewportWidth, Dimens.articleMeasure) × bucket`
  pixels wide, bucket ∈ {1, 2} = `ceil(scale)` capped at 2, chosen when a gesture **ends** (never mid-pinch —
  the fit bitmap is scaled up until then); a `PageCache` (`LruCache<Pair<index, bucket>, Bitmap>`, **6**
  entries, `recycle()` on eviction) per open document; a page whose bitmap has not arrived is a
  `surfaceContainerLow` box of the right aspect, so nothing reflows when it does. The source is opened in
  a `DisposableEffect` on the document screen and closed when it leaves.

### §0.6 The reader: the approved mock, stated

The reader approved the second round of mocks on 2026-09-21: **pages at screen width, edge to edge and
untouched — no cropping — a separator line, a page toast, and zoom by pinch and by double-tap.**

**Layout.** `ArticleScreen`'s `Scaffold`, top bar and actions (`ArticleScreen.kt:98-160`) are unchanged: like,
save, open-in-browser (present only when `link != null`, as today), share (`shareIntent` already tolerates a
null link, `EntryActions.kt:207-214`) and the overflow. The body (`Article`, `:302-366`) branches: a state with
`document != null` composes **`DocumentArticle`** (`ui/article/document/DocumentBody.kt`) — a `LazyColumn`
whose item 0 is the header (headline, `Byline`, and one new line, the **document strip**) inset by
`Dimens.screenHorizontal` and capped at `Dimens.articleMeasure` like the text measure, and whose items 1…N
are the pages, **full-bleed to the measure and centred**, in reading order, no gutters. No `SelectionContainer`
around the document branch (there is no text). The strip: a `PDF` chip (`labelSmall`, bold,
`secondaryContainer`, 4 dp corners) then `112 pages · 850 KB · saved offline` in `ArticleType.caption` /
`onSurfaceVariant` (plurals `document_pages`; size via `android.text.format.Formatter.formatShortFileSize`).
**Pages stay white in dark mode** — it is the document as published, and every phone PDF reader does the
same; an inverted-page setting is a `TECH_DEBT.md` line, not this plan.

**Separator.** A 2 dp rule the width of the page in `MaterialTheme.colorScheme.outline`, after every page
including the last, so it reads in both themes (the reader asked for a black line; the outline colour *is*
near-black in light and visible in dark, which pure black is not).

**Page toast.** The page under the **vertical centre of the viewport** — pure `pageUnderCentre(visible:
List<VisiblePage>, viewportHeight: Int): Int?` over `LazyListLayoutInfo`'s `(index, offset, size)` triples,
in `DocumentBody.kt`'s companion, unit-tested. When it changes **after** the first composition, a pill at the
bottom centre (`inverseSurface` / `inverseOnSurface`, full radius, `labelLarge`, `Dimens.xl` from the edge)
reads *Page 2 of 112* (`document_page_toast`) and fades out **1 200 ms** after the last change
(`AnimatedVisibility`, `tween`; DESIGN.md §6's durations). It never shows on open.

**Zoom is per document, one-dimensional, and the list keeps the vertical axis.** The image viewer's
`ZoomGeometry` (`ZoomGeometry.kt:32-135`) models a letterboxed image with a 2-D pan and a dismiss drag; a
document is a column of pages whose vertical scroll *is* the list's, so its arithmetic does not transfer and
is **not** forced to (PLAN-10 §0.2: nothing here deletes it, and the double-tap semantics differ). A new pure
`DocumentZoom` (`ui/article/document/DocumentZoom.kt`), tested like `ZoomGeometryTest`:

```kotlin
data class DocTransform(val scale: Float = 1f, val offsetX: Float = 0f)      // offsetX ≤ 0: pages' left edge
data class TextColumn(val left: Float, val width: Float)                     // fractions of page width
object DocumentZoom {
    const val MIN_SCALE = 1f; const val MAX_SCALE = 4f; const val FALLBACK_SCALE = 2f; const val COLUMN_MAX_WIDTH = 0.9f
    fun pinch(current, viewportWidth, centroidX, panX, zoom): DocTransform   // scale = (current.scale·zoom).coerceIn(MIN, MAX);
                                                                             // the content point under centroidX stays under it (the 1-D form of ZoomGeometry.kt:91-108); offsetX clamped to [viewportWidth·(1 − scale), 0]
    fun drag(current, viewportWidth, dx): DocTransform                        // offsetX + dx, clamped
    fun doubleTap(current, viewportWidth, atX, column: TextColumn?): DocTransform
        // zoomed at all → DocTransform(); column != null && column.width < COLUMN_MAX_WIDTH → scale = min(1/column.width, MAX_SCALE),
        // offsetX = −column.left·viewportWidth·scale, clamped; otherwise pinch(current, viewportWidth, atX, 0f, FALLBACK_SCALE)
    fun isZoomed(scale: Float): Boolean
}
```

**The text column** is the ink bounds of the document's body pages: `TextColumn.of(pages: List<Bitmap>)`
(`ui/article/document/TextColumn.kt`, pure `Bitmap` arithmetic, testable under `@GraphicsMode(NATIVE)` on the
rendered fixtures): per bitmap, the ground is the most common colour along its border; a pixel is ink when its
luminance differs from the ground's by more than **48/255**; a column x has ink when at least **2** sampled
rows (every second row) are ink; the column spans the first and last ink x over all bitmaps, **padded 2 %**
each side and clamped to [0, 1]; a bitmap with no ink is skipped; the result is **null** when nothing had ink
or the span is ≥ `COLUMN_MAX_WIDTH`. The pages measured are **2, 3 and 4** (indices 1..3) when the document has
four or more, else all of them — the title page's centred block is not the body's column. It is computed once
per open document from the fit-width bitmaps those pages render to anyway, on `Dispatchers.Default`, and
until it exists a double-tap is the 2× fallback. `letter-margins` pins the numbers (ink at x ≈ 72..540 of 612 pt
→ `left ≈ 0.10`, `width ≈ 0.80`); `scan-image-only` (a grey ground, ink but no white margin) and
`two-column-landscape` (both columns inside one span) pin the edges.

**Gestures**, on the `LazyColumn`'s container: `Modifier.transformable(state, canPan = { false })` for the
pinch (pinch only — a two-finger pan is left to the list), `Modifier.draggable(Orientation.Horizontal,
enabled = zoomed)` for the horizontal pan (Compose's touch-slop axis lock is what keeps a vertical drag with
the list), and `pointerInput { detectTapGestures(onDoubleTap = …) }`. Each page item is a `Box` of
`requiredWidth(pageWidth × scale)` and the matching height, `offset { IntOffset(offsetX, 0) }`, inside a
`clipToBounds()` list — so the list's own scroll and fling cover the zoomed pages' extra height without any
custom vertical handling. When a pinch ends (`TransformableState`'s gesture end, the way `ImageViewer.kt:135-142`
uses `onEnd`), the render bucket is re-chosen (§0.5).

**The page you stopped on.** On scroll settle (`isScrollInProgress` `true → false`, the E01 shape at
`ArticleScreen.kt:310-317`) and on leave, `onScrollSettled(pageUnderCentre ?: 0)`; the list opens at
`rememberLazyListState(initialFirstVisibleItemIndex = state.scrollPosition)` — item index *is* page number
because item 0 is the header.

**The ViewModel.** `ArticleUiState.Loaded` (`ArticleViewModel.kt:66-79`) gains `val document: DocumentUi? = null`
(`DocumentUi(file: File, pageCount: Int, aspects: List<Float>, sizeBytes: Long)` — aspects for every page,
read once through `PageSource.size`, so item heights are known before any bitmap is) and `val documentGone:
Boolean = false`. `loaded(entry)` (`:162-179`): when `entry.documentPath != null`, open through the container's
`rasterizer` on `Dispatchers.IO`; null (file missing or unreadable) → `documentGone = true`; either way
`blocks = emptyList()`, `standfirst = null`, **`canLoadFullText = false`**. The automatic trigger at `:126-129`
gains `entry.documentPath == null &&` — without it `FullText.needsExtraction(null, false)` fires on every
document and fetches its URL as a page. The constructor gains `rasterizer: PageRasterizer` and
`documents: DocumentStore` (`ArticleViewModelTest.kt:322`, `ArticleScreenTest.kt:456`, `DesignScreenshotTest.kt:512`
construct it — pass the fixture one). `documentGone` with `link == null` shows a new `article_document_gone`
("This document is no longer stored. Save it again to fetch it."); with a link, the existing `EmptyBody`.

### §0.7 The row

`EntryRow` (`EntryRow.kt:85`): when `item.isDocument`, `sourceAndCategory(item)` (`:126-135`) ends with
` · PDF` (`document_kind_pdf`) — the same `labelMedium` meta line, no chip, no new colour; the thumbnail is
the ordinary `Thumbnail(url = item.imageUrl)` (`:149`) showing the top of page one because §0.4 put it there.
**Every other row is pixel-identical**: NOTES.md's worktree `md5sum` recipe, `35/35`, is the Done.

### §0.8 The ways in: a share is a paste the reader has already confirmed

**Intents.** `MainActivity` (`AndroidManifest.xml:18-27`) gains `android:launchMode="singleTask"` and three
filters: `ACTION_SEND` + `text/plain`, `ACTION_SEND` + `application/pdf`, `ACTION_VIEW` + `application/pdf`
(with `BROWSABLE` **not** set — Perch is an *Open with* target, not a URL handler; and `DEFAULT`). `onCreate`
and a new `onNewIntent` both call `container.intake.offer(incomingFrom(intent))`.

**The value.** `model/Incoming.kt`: `sealed interface Incoming { data class Link(val url: String); data class
Document(val uri: Uri, val displayName: String?) }`. `ui/nav/IncomingShare.kt`: `fun incomingFrom(intent: Intent?):
Incoming?` — pure over the intent: `SEND`/`text/plain` → the **first `http(s)://` URL** in `EXTRA_TEXT`
(browsers share "Title https://…"; `PastedUrl`'s normaliser then runs as for a paste), none → null; `SEND`
with `EXTRA_STREAM` or `VIEW` with a `data` URI → `Document(uri, displayName)` (`displayName` from
`OpenableColumns.DISPLAY_NAME` through the resolver, or the URI's last segment); a `MAIN` launch → null.
`AppContainer` gains `val intake = MutableStateFlow<Incoming?>(null)`.

**The flow.** `PerchNavHost` (`PerchNavHost.kt:123`) collects `container.intake` in a `LaunchedEffect`; a
non-null value → `selectTab(navController, PerchTab.ToRead)` (`:354`). `SaveLinkViewModel` (`SaveLinkViewModel.kt:55`)
takes `intake` as a second constructor parameter and collects it in `init`: `Link` → `onUrlChange(url)`,
`open()`, `submit()`; `Document` → `open()`, `submitDocument(uri, displayName)`; then `intake.value = null`.
The sheet's visibility moves into the state — `SaveLinkUiState.isOpen` with `open()`; `CollectionScreen`'s
local `savingLink` (`CollectionScreen.kt:82`, the tap at `:146`, the host at `:221-227`) becomes
`state.isOpen`, and `onDismissRequest` (`:106`) closes it — one owner, so an intake can open the sheet from
outside. Success and failure are what a paste already does: `savedEntryId` → `DismissWhenDone` → the
*Saved “…”* snackbar (`CollectionScreen.kt:108-116`); a failure stays in the sheet with its reason. Perch
does **not** open the article after an import: a share is "read this later", and To-Read with the
confirmation is where a paste lands.

**A local file.** `SavedLinkRepository.saveDocument(uri: Uri, displayName: String?): Result<Long>` copies the
stream through a `DocumentOpener` seam (`fun interface DocumentOpener { fun open(uri: Uri): InputStream? }`,
built on `contentResolver::openInputStream` in `AppContainer`, a map in tests) into `DocumentStore.newDocument()`
while hashing it (SHA-256, the 40 MiB cap applied to the stream), then §0.4's `storeDocument(file, link = null,
guid = "perch:document:<hash>", nameHint = displayName)`. Not a PDF → `SaveLinkFailure.NotDocument`, phrased
by the sheet as `save_link_error_not_document` ("That file is not a PDF Perch can read"). The busy label is
`save_link_importing` ("Importing…") while a document copies.

**The picker.** `SaveLinkSheetContent` (`SaveLinkSheet.kt:79`) fills `UrlFormContent`'s `belowField`
slot (`UrlForm.kt:52`) with one `TextButton`, *Choose a PDF…* (`save_link_choose_file`, tag
`SaveLinkTestTags.CHOOSE_FILE`), whose `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`
launches for `arrayOf("application/pdf")` and hands the `Uri` to `submitDocument`. No persistable permission:
the bytes are copied at once. The To-Read empty state (`collection_empty_to_read_body`) gains "…or share a
PDF to Perch" (one clause; the string is at `strings.xml:228`).

### §0.9 Tests, screenshots and gates

- **The floor is 2067 and may only rise** (v0.8.0: 1200 debug + 867 release). Every task adds tests; none
  deletes one. `FeedCorpusTest` is untouchable.
- **TDD everywhere a task says so**, RED pasted into the commit. The pure objects — `PdfInfoReader`,
  `DocumentZoom`, `TextColumn`, `pageUnderCentre`, `incomingFrom` — are `src/test`; the screen and the sheet
  are `src/testDebug`; the migration and the DAO are `src/test` Robolectric.
- **The document gallery** is `DocumentScreenshotTest` (`app/src/testDebug/.../ui/screenshot/`), over the
  `ssrn-6191618` fixture through `FixtureRasterizer`, into `build/perch-screenshots/`: `document-reader-dark`,
  `document-reader-light`, `document-reader-scrolled` (page 2 under the centre, toast showing),
  `document-reader-zoomed` (after a double-tap: the text column fills the width), `document-to-read-row`
  (the real shell, To-Read, one document row beside one pasted link). **A screenshot task's Done is the PNG
  looked at** (`Read` it) **and critiqued against DESIGN.md §8 in the commit message**, numbered; at most
  **two** critique-fix iterations (CLAUDE.md), then residual polish goes to NOTES.md.
- **Untouched pixels stay untouched.** G07 and G08 change files every existing shot composes; the proof is
  NOTES.md's worktree `md5sum` recipe over `*ScreenshotTest*` both sides, `35/35`, in the commit.
- **Live gate 16.** `LiveAcceptanceTest` (last gate today: 15, `LiveAcceptanceTest.kt:1388`) gains gate
  16: `container.savedLinks.saveLink("https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-63-4.pdf")`
  — `application/pdf`, 858 054 bytes, public domain, no quota; the row is on To-Read titled *Digital Identity
  Guidelines*, `documentPath` resolves to a file of exactly that size, the thumbnail exists, `scrollPosition == 0`.
  The container's rasterizer under Robolectric is `FixtureRasterizer`, which knows this file by hash — so the
  gate proves download, sniff, store, title and row end to end, and the render is G14's.
- **The emulator proves `PdfRendererRasterizer`**, once, in G14, **only if it is already up**
  (`./scripts/device.sh check`; never boot it for this): install the release APK, then
  `./scripts/device.sh adb shell am start -n dev.mkiros.perch/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT <the NIST URL>`
  — which is also the share path — wait, `screenshot` To-Read, tap the row, `screenshot` the reader. Two PNGs
  looked at; that is the whole of the on-device proof and it is bounded.

### §0.10 Traps that will look like bugs

- **`PerchDatabaseMigrationTest`** fails the moment `VERSION` moves and `11.json` does not exist — G01's RED,
  not a broken build. Compile to export it. **Room validates the exported schema on open** (NOTES.md, S08):
  the `ALTER TABLE` must produce exactly what `11.json` says.
- **`PdfRenderer` under Robolectric** dies in `FileDescriptor.getOwnerId$()` — every mode, every descriptor
  path (§0.5). A JVM test that reaches it has wired the wrong rasterizer, not found a bug.
- **`FullText.needsExtraction(null, false)` is true.** Without §0.6's guard the article screen fetches every
  document's URL as an HTML page the moment it opens; `ArticleFullTextTest` is where that would show.
- **`FixtureRasterizer` keys on SHA-256.** A fixture edited by hand (even re-saved) is unknown to it; regenerate
  the manifest row. The `rendered/` PNGs are 600 px wide — scale, never assume.
- **The title rung order is XMP *after* the Info dictionary**, and `nist-sp800-63-4` is why: its XMP says
  "Print". A session that swaps them to "fix" a fixture is aiming at a producer.
- **Injected taps do not reach a node inside a bottom sheet** (NOTES.md) — `performSemanticsAction(OnClick)`
  for `CHOOSE_FILE`; and `rememberLauncherForActivityResult` needs an `ActivityResultRegistryOwner` — provide
  one via `LocalActivityResultRegistryOwner` in the sheet test rather than launching an activity.
- **`Modifier.draggable(Horizontal)` on the same node as a `LazyColumn`** competes for slop: put it on the
  list's *parent* `Box` with the `transformable`, and the list beneath keeps vertical.
- **Waiting on Room is not waiting on the screen** (V01/#1): poll through the shared wait helper.
- **`ArticleScreenTest.leaveArticle` (`:492`)** drains the leaving write; a document screen writes a page
  number through the same path and needs the same drain.
- **Two known full-suite-only flakes** (NOTES.md): `WorkSchedulerTest > choosing manual…` and
  `SettingsViewModelTest`. Re-run once before diagnosing.
- **The Windows emulator holds v0.8.0** (NOTES.md). Do not boot it for anything but G14, and only if it is
  already up.

### §0.11 Rungs

`./gradlew test` in the **foreground** is the rung for every task but the release (`unit`, 3–7 min). Five
tasks take screenshots (G07, G08, G11 and the two live ones) — through `Screenshots.captureAndAssert`
(`ScreenshotSupport.kt:78`), never `captureToImage()`. Live acceptance is a bounded pre-step of G12 and G14
(~90 s, two runs at most). The review box (G13) is second from last, as CLAUDE.md requires.

---

## The tasks

- [ ] **G01 — A stored document is an entry: schema 11, `documentPath`, the store and its sweep. TDD. Issue #72.**
      §0.2 is the decision, whole.
      1. **RED, schema:** `PerchMigration10To11Test` (copy `PerchMigration9To10Test.kt:25-60`'s shape) — a
         populated v10 file opens at 11 with every feed and entry intact and `documentPath` NULL on every row;
         `PerchDatabaseMigrationTest` names `11.json`.
      2. **RED, DAO:** in `EntryDao`'s existing test file — `upsertAll keeps a row's documentPath when the
         incoming row has none`; `documentRows lists only rows with a file, with both flags`;
         `clearDocument forgets the file and the thumbnail`; `the list row says whether it is a document`
         (`ROW`'s `isDocument`, through `observeSaved`).
      3. **RED, store:** `DocumentStoreTest` (`src/test/.../data/document/`, a temp `filesDir` via
         `ApplicationProvider`) — `newDocument names a pdf under documents/`; `thumbnailFor sits beside it`;
         `delete removes both`; `sweep deletes a file no row names`; `sweep deletes a file whose row is neither
         saved nor liked and returns its id`; `sweep keeps a saved file and a liked file`.
         `EntryRepositoryTest` — `sweepDocuments clears the rows the store returned`.
      4. **GREEN:** `EntryEntity.documentPath`, `VERSION = 11`, `MIGRATION_10_11`, `11.json` (compile, commit),
         `upsertAll`'s carry line, `EntryQueries.ROW` + `EntryListItem.isDocument`, `EntryDao.documentRows` /
         `clearDocument`, `DocumentStore`, `EntryRepository.sweepDocuments`, `AppContainer.documentStore`
         (`AppContainer.kt:36-44`, built on `context.filesDir` in `create`; `PerchRule.kt:53-59` passes one on
         the Robolectric context's `filesDir`), `PerchApp.onCreate`'s startup launch calling the sweep.
      5. SPEC.md §4: the column, "version 11 adds `entries.documentPath` — a stored PDF's file (PLAN-13 G01)",
         the sweep rule, and that neither the profile nor Auto Backup carries the file; §8: `scrollPosition`
         holds the page for a document row; §3's tree gains `data/document/DocumentStore.kt`.
      - Done: RED shown for all three layers; `./gradlew test` green and above 2067;
        `PerchDatabaseMigrationTest` green; #72 commented naming the commit and the tests; pushed.
      - Rung: unit

- [BLOCKED: PDF metadata parsing — object stream decompression and XMP filtering incomplete (see commit f318c4b, NOTES.md 2026-09-21)] **G02 — A document's title and date come from the PDF itself. TDD. Issue #72.**
      §0.3 is the decision — the rung order, the string decoding, the
      object-stream path, the encrypted rule, the boilerplate list, the date.
      1. **RED:** `PdfInfoReaderTest` (`src/test/.../data/document/`) — one case per manifest row, iterating
         `DocumentFixtures.manifest()` (write `DocumentFixtures` now: `slug`, `file()`, `sha256`, `pages`,
         `sizes`, `title`, `published`, from `fixtures/documents/manifest.tsv`; `repoRoot()` as
         `CodeScreenshotTest.kt` finds it): `title` equals the manifest's `title` column **when that column is
         not the slug** and is null when it is (`empty-title`, `scan-image-only`, `encrypted-empty-user-password`);
         `creationDate` equals `published` where the manifest has one. Then the unit cases, inline bytes:
         `a literal title with escaped parentheses`, `a UTF-16BE hex title`, `an indirect title reference`,
         `the last Info dictionary wins over an earlier one`, `an Info dictionary inside an object stream`,
         `XMP dc:title is read only when the dictionary has no title`, `"Print" is not a title`, `a "Microsoft
         Word - " prefix is dropped`, `an encrypted file yields nothing`, `an HTML file yields nothing and
         throws nothing`, `a CreationDate without a zone is UTC`.
      2. **GREEN:** `PdfInfo.kt` — a byte scanner over the file (read once into memory; the 40 MiB cap
         bounds it), `Inflater` for object streams, no regex over the whole file for anything but the
         anchors (`/Info`, `obj`, `/ObjStm`, `<dc:title>`).
      3. SPEC.md §5 gains a short "Documents" paragraph naming the standard and the rung order.
      - Done: RED shown; `./gradlew test` green and above G01's count; the §0.2 grep gate empty
        (`grep -rnoE '"[a-z0-9.-]+\.(com|org|net|io|dev|me|ski|ca|xyz|blog)"' app/src/main/java/dev/mkiros/perch/data/`);
        #72 commented with the commit; pushed.
      - Rung: unit

- [x] **G03 — The rasterizer seam: `PageRasterizer`, the platform one, and the fixture one. TDD. Issue #72 (part 2 of 2).**
      §0.5 is the decision. `PdfRendererRasterizer` is production code that no JVM test can execute — its
      test is the compile, a `open` returning null on a non-PDF (that path throws before the native call —
      prove it with `html-in-disguise.pdf` under Robolectric, expecting null, **not** an exception), and G14.
      1. **RED:** `FixtureRasterizerTest` (`src/test/.../support/`) — `a known file answers the manifest's
         page count and sizes` (ssrn: 112, 612×792; mixed-sizes: 595×842, 300×200, 595×842); `a rendered page
         decodes at the width asked for` (`@GraphicsMode(NATIVE)`; `render(0, 300)` is 300 wide and its
         aspect matches); `a page with no PNG still renders`; `an unknown file is null`.
         `PdfRendererRasterizerTest` (`src/test`) — `a file that is not a PDF opens as null`.
      2. **GREEN:** `data/document/PageRasterizer.kt` (the two interfaces, `PageSize`),
         `PdfRendererRasterizer.kt` (the `Mutex`, `Dispatchers.IO`, `SecurityException`/`IOException` → null),
         `support/FixtureRasterizer.kt`; `AppContainer.rasterizer: PageRasterizer = PdfRendererRasterizer()`
         and `PerchRule` passing `FixtureRasterizer()`.
      3. SPEC.md §3's tree gains the two production files; NOTES.md gains the one-line Robolectric fact
         from §0.5 (it outlives the plan).
      - Done: RED shown; `./gradlew test` green and above G02's count; #72 commented; pushed.
      - Rung: unit

- [x] **G04 — A pasted link to a PDF lands on To-Read as a stored document. TDD. Issue #72.**
      §0.4 is the decision, in its order.
      1. **RED, fetcher:** `FeedFetcherTest` — `download streams a body to the file and reports its headers`
         (MockWebServer serving `letter-margins.pdf` with `Content-Type: application/pdf` and a
         `Content-Disposition: attachment; filename="paper.pdf"`); `download stops at the document cap`
         (a fetcher built with a small `maxDocumentBytes`, a body one byte over: `Failure`, file deleted).
      2. **RED, repository:** `SavedLinkRepositoryTest` (its `:49` builder gains the store — a temp
         `filesDir` — and `FixtureRasterizer()`) — `a pasted PDF is stored whole and titled from the file`
         (serve `ssrn-6191618.pdf` as `application/pdf`; the row: title from the manifest, `documentPath`
         resolving to a file of 870 451 bytes, `contentHtml == null`, `summary == null`, `publishedAt ==
         2026-06-30T03:07:59Z` and not estimated, `isSaved`, `imageUrl` a `file:` URI whose file exists and is
         256 px wide and square); `a PDF served as octet-stream is recognised by its bytes`; `a PDF with no
         title takes the Content-Disposition file name` (`empty-title.pdf`, `filename="Quarterly_Report.pdf"`
         → "Quarterly Report"); `…and then the URL's last segment` (no disposition, `/files/NIST.SP.800-63-4.pdf`
         → "NIST.SP.800-63-4"); `pasting the same PDF twice replaces the file and keeps one row` (two files
         in the directory before the second paste → one after); `a file that is not a PDF is not a document`
         (`html-in-disguise.pdf` as `application/pdf` → it is parsed as the page it is, and saves as one, since
         it has a `<title>` — assert `documentPath == null` and the title "Not a PDF"); the eight existing cases
         unchanged.
      3. **GREEN:** `FeedFetcher.download` + `DownloadResult` + `DOCUMENT_MAX_BYTES`; `saveLink`'s single
         download, the sniff, the branch, `storeDocument`; `PageRasterizer` for the count and the thumbnail;
         `PdfInfoReader` for the title and date.
      4. SPEC.md §6: the document cap; §8a: a document is found by its title only. Comment on #72 with the
      SSRN 403 fact from §0.4, verbatim.
      - Done: RED shown for both layers; `./gradlew test` green and above G03's count; #72 commented with the commit;
        pushed.
      - Rung: unit

- [ ] **G05 — The article ViewModel knows a document: pages, no full text, the page remembered. TDD. Issue #72 (part 1 of 3).**
      §0.6's *ViewModel* paragraph is the decision.
      1. **RED:** `ArticleViewModelTest` (`:322`'s `newViewModel` gains `rasterizer = FixtureRasterizer()` and
         `documents`) — `a document entry loads with its page count and aspects and no blocks` (seed a row
         with `documentPath` naming a copy of `mixed-sizes.pdf` in the test `filesDir`; `document.pageCount ==
         3`, `aspects[1] == 1.5f`, `blocks.isEmpty()`, `standfirst == null`, `canLoadFullText == false`);
         `a document never triggers the automatic full-text fetch` (an `ArticleTextRepository` whose fetcher
         counts calls: 0); `a document whose file is gone says so` (`documentGone`); `Load full article is not
         offered for a document`; `the page a document stopped on is written through saveScrollPosition`
         (F06's dedupe holds: 3, 3, 4 → two writes).
      2. **GREEN:** `DocumentUi`, `Loaded.document`/`documentGone`, `loaded()`'s branch, the trigger guard, the
         two constructor parameters; every construction site compiles (`ArticleScreenTest.kt:456`,
         `DesignScreenshotTest.kt:512`, `LiveAcceptanceTest`, `PerchNavHost`'s factory).
      - Done: RED shown; `./gradlew test` green and above G04's count; #72 commented; pushed.
      - Rung: unit

- [ ] **G06 — Zoom as arithmetic: `DocumentZoom`, `TextColumn`, `pageUnderCentre`. TDD. Issue #72 (part 2 of 3).**
      §0.6's *zoom*, *text column* and *page toast* paragraphs are the decision, numbers included. Pure
      code only — no composable this task.
      1. **RED:** `DocumentZoomTest` (`src/test/.../ui/article/document/`, the shape of `ZoomGeometryTest.kt`) —
         `a pinch scales about the centroid and keeps the point under the fingers`; `scale is clamped to
         1×–4×`; `offsetX never shows the left or right edge past the viewport`; `a drag pans only while
         zoomed and stays clamped`; `double tap at fit goes to the text column when there is one`
         (column `(0.10, 0.80)`, viewport 1000 → scale 1.25, offsetX −125); `double tap with a column wider
         than nine tenths is a 2× about the tap`; `double tap while zoomed returns to fit`; `the column's
         scale is capped at the maximum`.
         `TextColumnTest` (`@GraphicsMode(NATIVE)`) — over the rendered fixtures through `DocumentFixtures`:
         `letter-margins finds the one-inch margins` (`left` within 0.08..0.12, `width` within 0.76..0.84);
         `a scan on a grey ground finds its ink`; `two columns are one span`; `a blank page is skipped and
         all-blank is null`; `ink from edge to edge is null`.
         `PageUnderCentreTest` — `the page whose item straddles the centre wins`; `the header is never a
         page`; `an empty layout is null`.
      2. **GREEN:** `DocumentZoom.kt`, `TextColumn.kt`, `pageUnderCentre` in `DocumentBody.kt`'s companion
         (the file may exist with only that until G07).
      - Done: RED shown; `./gradlew test` green and above G05's count; #72 commented; pushed.
      - Rung: unit

- [ ] **G07 — The document reader on screen. TDD + screenshot. Issue #72 (part 3 of 3).**
      §0.6's *layout*, *separator*, *page toast*, *gestures*, *page you stopped on* and §0.5's *rendering
      policy* paragraphs are the decision. Test tags: `ArticleTestTags.DOCUMENT` (`article:document`, the
      list), `DOCUMENT_STRIP`, `DOCUMENT_PAGE` (every page item), `DOCUMENT_SEPARATOR`, `DOCUMENT_TOAST`,
      `DOCUMENT_GONE`; strings `document_kind_pdf`, `document_pages` (plurals), `document_strip`
      (`%1$s · %2$s · saved offline`), `document_page_toast`, `document_page_description` (`Page %1$d`),
      `article_document_gone`.
      1. **RED:** `DocumentBodyTest` (`src/testDebug/.../ui/article/`, `ArticleScreenTest.kt:456`'s
         `showArticle` shape and `:492`'s drain, a seeded `ssrn-6191618` row through `FixtureRasterizer`) —
         `a document shows its headline, byline, strip and first page` (strip text "112 pages · 850 KB · saved
         offline", **no** `article:standfirst`, no `LOAD_FULL_TEXT` in the overflow); `every page is followed
         by a separator`; `scrolling to page two raises the toast and it fades` (`performScrollToNode` on
         the second `DOCUMENT_PAGE`; `DOCUMENT_TOAST` shows "Page 2 of 112"; after `mainClock.advanceTimeBy(1_500)`
         it is gone); `the toast never shows on open`; `a double tap widens the pages to the text column`
         (`performTouchInput { doubleClick() }`; the page node's width grows by the column's factor, its
         x becomes negative); `a second double tap returns to fit`; `a pinch past the maximum settles at the
         maximum` (`pinch()`); `leaving the screen writes the page under the centre` (`scrollPosition == 2`);
         `a document reopens at the page it stopped on` (seed `scrollPosition = 3`; the third page item is
         at the top); `a document whose file is gone says so`.
      2. **GREEN:** `DocumentBody.kt` (`DocumentArticle`, `DocumentStrip`, the page item, `PageCache`, the
         render effect, the separator, the toast, the gestures), `ArticleScreen`'s branch at `:180-190` /
         `:302-366` (the `SelectionContainer` stays around the text branch only), the strings, the tags.
      3. **Screenshots** — this task's own, the first four of §0.9's gallery: `DocumentScreenshotTest` with
         `document-reader-dark`, `-light`, `-scrolled`, `-zoomed`. Look at each PNG. Critique against
         DESIGN.md §8 in the commit, numbered; ≤ 2 iterations.
      4. **Untouched pixels:** the `md5sum` recipe over the *existing* screenshot tests, `35/35`.
      - Done: RED shown; `./gradlew test` green and above G06's count; four PNGs named in the commit with
        the critique; `35/35`; #72 commented naming the three commits; pushed.
      - Rung: screenshot

- [ ] **G08 — A document on To-Read shows its first page and says it is a PDF. TDD + screenshot. Issue #72.**
      §0.7 is the decision.
      1. **RED:** `EntryRowTest` (`:80-108`'s shape) — `a document row says PDF after its source`
         (`META` reads "Saved links · PDF"); `an ordinary row does not`. `DocumentScreenshotTest` gains
         `document-to-read-row`: the real shell (`DesignScreenshotTest.kt:502`'s `showShell`), To-Read, one
         document row (seed through `PerchRule.seedEntry` with `documentPath` and an `imageUrl` pointing at
         `fixtures/documents/rendered/ssrn-6191618-1.png` — Coil loads a `file:` URI without a stub) beside
         the pasted-link row `DesignScreenshotTest.kt:263-284` already makes.
      2. **GREEN:** `sourceAndCategory`'s suffix.
      3. `md5sum`, `35/35`; the new PNG looked at and critiqued (≤ 2 iterations).
      - Done: RED shown; `./gradlew test` green and above G07's count; `35/35` and the PNG in the commit;
        #72 commented with the commit; pushed.
      - Rung: screenshot

- [ ] **G09 — Share a link or a PDF to Perch, or open a PDF with it. TDD. Issue #72.**
      §0.8's *intents*, *value*, *flow* and *local file* paragraphs are the
      decision.
      1. **RED, pure:** `IncomingShareTest` (`src/test/.../ui/nav/`, Robolectric for `Intent`) — `a shared
         sentence with a URL in it is the URL`; `a share with no URL is nothing`; `a shared PDF stream is a
         document with its display name`; `a viewed PDF is a document`; `a plain launch is nothing`.
         **RED, repository:** `SavedLinkRepositoryTest` — `a shared file is stored under a content hash and
         titled from the file` (opener map: `uri → ssrn-6191618.pdf` bytes; `guid == "perch:document:0a8e0e01…"`
         (the manifest's sha256), `link == null`, title from the manifest); `the same file shared twice is one
         row`; `a shared file that is not a PDF fails as NotDocument`; `a shared file with no title takes its
         display name`.
         **RED, ViewModel:** `SaveLinkViewModelTest` (`src/test/.../ui/collection/`, new or existing) — `an
         incoming link opens the sheet and submits it`; `an incoming document opens the sheet and imports it`;
         `the intake is cleared once taken`; `open and dismiss own the sheet's visibility`.
         **RED, screen:** `SaveLinkSheetTest` (`:61-104`) — `a link shared to Perch lands on To-Read with the
         confirmation` (set `container.intake.value = Incoming.Link(server.url(...))`, show the shell, await
         the row and the *Saved “…”* snackbar).
      2. **GREEN:** the manifest, `MainActivity.onNewIntent`, `model/Incoming.kt`, `ui/nav/IncomingShare.kt`,
         `AppContainer.intake` and `documentOpener`, `PerchNavHost`'s collector, `SaveLinkUiState.isOpen` /
         `open()` / `accept` / `submitDocument`, `CollectionScreen`'s `savingLink` → `state.isOpen`,
         `SavedLinkRepository.saveDocument`, `SaveLinkFailure.NotDocument`, the two strings.
      3. SPEC.md §10 (navigation) gains the intake sentence; DESIGN.md §5's To-Read paragraph gains "a share
         is a paste already confirmed".
      - Done: RED shown for all four layers; `./gradlew test` green and above G08's count; #72 commented with the commit;
        pushed.
      - Rung: unit

- [ ] **G10 — Choose a PDF from the phone in the Save-a-link sheet. TDD. Issue #72.**
      §0.8's *picker* paragraph is the decision.
      1. **RED:** `SaveLinkSheetTest` — `the sheet offers to choose a PDF` (`CHOOSE_FILE` present, under the
         field); `choosing a file imports it` (a fake `ActivityResultRegistry` provided through
         `LocalActivityResultRegistryOwner` that answers the `OpenDocument` contract with a `Uri` the test
         opener maps to `letter-margins.pdf`; the row appears; the snackbar names "Letter margins: a (quoted)
         title"); `cancelling the picker leaves the sheet as it was`.
      2. **GREEN:** the button in `belowField`, the launcher, `save_link_choose_file`, the empty-state clause.
      - Done: RED shown; `./gradlew test` green and above G09's count; #72 commented with the commit; pushed.
      - Rung: unit

- [ ] **G11 — The gallery critique, and DESIGN.md says what a document is. Screenshot. Issue #72 (follow-up).**
      Re-run `DocumentScreenshotTest`; **look at all five PNGs together** against DESIGN.md §8 and the mocks
      the reader approved (`document-reader-*`: headline measure and byline unchanged from an article; the
      strip's chip and caption; pages full-bleed to the measure with **no** gutter; the 2 dp outline rule; the
      toast's pill; the zoomed shot's column filling the width; the row's ` · PDF` and first-page thumbnail).
      Fix what is small — a dimension, a colour token, a spacing — **≤ 2 iterations**; log the rest to
      NOTES.md. Then DESIGN.md §8 gains a **"Documents"** subsection (after "The block treatments"): the
      strip, the pages, the separator, the toast, the two zooms, white pages in dark mode and why. Every new
      dimension is a `Dimens` token, named there.
      - Done: five PNGs named in the commit with a numbered critique and what changed; `./gradlew test`
        green and above G10's count; `35/35` on the pre-existing shots; DESIGN.md updated; pushed.
      - Rung: screenshot

- [ ] **G12 — Live gate 16: a real PDF URL becomes a titled, paged document on To-Read. Issue #72.**
      §0.9's *live gate 16* bullet is the decision, the URL included.
      Add the gate after gate 15 (`LiveAcceptanceTest.kt:1388`, the `captures.failures +=` idiom at `:1837`),
      then `./gradlew :app:testDebugUnitTest -Pperch.live=true --tests '*LiveAcceptance*'` in the
      **foreground**, ~90 s, **at most two runs**; paste every gate's line into the commit. Gate 1 has no
      quota (V12/#8); `quantpedia.com` stays excluded. If gate 16 fails on the network twice, mark the box
      `[BLOCKED: gate 16 — …]` with the output; if it fails on the *title* or the *row*, that is a G02/G04
      defect — fix it here only if it is one line, else BLOCKED and an issue.
      - Done: the live run's gate lines in the commit, 16 among them; `./gradlew test` green; #72 commented with the commit; pushed.
      - Rung: unit + one bounded live run

- [ ] **G13 — The review pass. The whole of v0.9.0, read at once.**
      Read `git diff v0.8.0..HEAD` — **the whole of it** — and answer, in the commit message and in NOTES.md
      where it outlives the plan:
      1. Does any doc still describe v0.8.0? README.md (the feature list and the gallery — add the reader
         shot if the README carries one per surface), SPEC.md (§1 version; §2 no new dependency; §3 tree —
         `data/document/{DocumentStore,PdfInfo,PageRasterizer,PdfRendererRasterizer}.kt`,
         `ui/article/document/{DocumentBody,DocumentZoom,TextColumn}.kt`, `model/Incoming.kt`,
         `ui/nav/IncomingShare.kt`; §4 schema 11 and the sweep; §5 documents; §6 the cap; §8 the page;
         §8a title-only search; §10 the intake), DESIGN.md (§8 Documents), NOTES.md, CLAUDE.md,
         `docs/RALPH.md`, `TECH_DEBT.md` (its "Next plan" section must name only what this plan did not do —
         and must now carry: an inverted-page setting for dark mode; API 35 text extraction for search and
         the title rung; sharing a local document through a `FileProvider`; decrypting RC4/AES titles).
      2. Did any task leave a helper, string, dimension or test tag orphaned? Scheduled to die:
         `CollectionScreen`'s `savingLink` (G09). Confirm with grep; confirm every new string and tag has
         a reader.
      3. Was any test weakened rather than rewritten? Name every changed assertion in
         `SavedLinkRepositoryTest` (G04, G09 touched its builder) and `ArticleViewModelTest` (G05) and say
         which is at least as strong as what it replaced.
      4. Is the suite above 2067, and did every task land with its RED in the commit?
      5. Does `11.json` match `MIGRATION_10_11` under Room's validation (name the test), does the §0.2 grep
         gate still return nothing, and does `grep -rn "PdfRenderer" app/src/test app/src/testDebug` return
         nothing but the null-on-non-PDF case?
      Fix what is small and mechanical **in this session**. Anything larger becomes an issue for the next
      plan and a line in `TECH_DEBT.md` "## Next plan" — do not start a feature in a review.
      - Done: the five answered in the commit message, each with the command that settled it;
        `./gradlew test` green; any new issue linked; pushed.
      - Rung: unit

- [ ] **G14 — Release v0.9.0.** Bump `perchVersionCode` 10 → **11** and `perchVersionName` `0.8.0` →
      **`0.9.0`** at `app/build.gradle.kts:12-13`, **the one place they live**. §0.1 settles the digit.
      - **Live acceptance first, bounded:** the G12 command in the **foreground**, ~90 s, **at most two
        runs**; paste every gate's count into the commit. If the second run still fails on a network gate,
        say which and release anyway, filing the failure as an issue — unless the failing gate is 16, in
        which case stop and mark the box `[BLOCKED: …]` with the output.
      - `./gradlew test assembleRelease` — **not `clean`** (runs `lintVitalRelease`). Signing from
        `~/.perch/signing.properties` (U02) — **absent it the build silently debug-signs**, so verify the
        certificate on the file, not the build log.
      - **Gradle writes `app-release.apk`; the rename to `perch-0.9.0.apk` is this task's own** (W12).
      - **The emulator proves the real renderer, only if it is already up** — §0.9's last bullet, verbatim:
        `check`; if running, `install`, the `am start … SEND` line with the NIST URL, `screenshot` To-Read,
        tap the row, `screenshot` the reader; look at both; name them in the commit. If it is not running,
        **do not boot it** — say so in the commit and let G12 stand as the proof of everything but the
        pixels, and add a `TECH_DEBT.md` line "verify `PdfRendererRasterizer` on a device" so the reader's first open is
        not the first render ever.
      - Release notes through `docs/RELEASE-NOTES.md`'s template; `scripts/release-notes.sh v0.8.0` drafts
        from #72 — write them in the reader's words: paste a PDF's link, share one from the browser or
        Files, or choose one from the phone; it is kept whole and read as pages; pinch, or double-tap to the
        text; the page you stopped on is remembered. Say plainly that SSRN links must be shared as files
        (§0.4). "Installing / upgrading": installs in place over v0.8.0 and keeps read state, likes and
        To-Read; the database moves 10 → 11 (one new column).
      - Tag `v0.9.0`, push, `gh release create v0.9.0` with the notes and `perch-0.9.0.apk`. Close **#72**
        naming the release. Then the turnover edits so the next session is not a loop session: CLAUDE.md's
        active-plan section says v0.9.0 shipped and there is no active plan; `loop.sh:19` and
        `scripts/progress.sh:11` keep naming `PLAN-13.md` (a stray launch fails loudly on the missing root
        file, as before); NOTES.md pruned under 100 lines with this version's floor and APK path. **Do not
        move this file** — the watching session archives it into `docs/plans/` after the loop reports complete.
      - Done: `gh release view v0.9.0 --json assets` lists the APK; `aapt2 dump badging` reads
        `versionCode='11' versionName='0.9.0'`; `apksigner verify --print-certs` prints U02's digest
        `61367c0499de5c49c824f4d7ba7b4e692d33960cc57c0622772227a8b7fce489`; `git status` clean and pushed;
        `gh issue list --state open` does not list #72.
      - Rung: build
