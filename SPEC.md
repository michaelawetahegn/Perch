# SPEC.md — Perch, a local-first Android RSS reader

Frozen decisions. **Future sessions do not re-deliberate anything in this file.**
If a pinned version fails to resolve, bump to the nearest available version, note it
in one line in NOTES.md, and move on.

---

## 1. Identity

| | |
|---|---|
| App name | **Perch** |
| Package / applicationId | `dev.mkiros.perch` |
| Module layout | single module `:app` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| Language | Kotlin, JVM target 17 |
| Distribution | sideloaded debug APK: `app/build/outputs/apk/debug/app-debug.apk` |

No server, no accounts, no cloud sync, no analytics, no crash reporting. All state in
Room/SQLite on-device. The app fetches feeds directly over OkHttp.

## 2. Pinned toolchain & libraries

Declared in `gradle/libs.versions.toml` (version catalog). Nothing else gets added
without a line in NOTES.md justifying it.

```
Gradle 8.11.1              AGP 8.7.3                 Kotlin 2.1.0
KSP 2.1.0-1.0.29           JDK 17 (Temurin)          compose plugin 2.1.0 (org.jetbrains.kotlin.plugin.compose)

androidx.core:core-ktx                      1.15.0
androidx.activity:activity-compose          1.9.3
androidx.lifecycle:lifecycle-*              2.8.7   (runtime-ktx, viewmodel-compose, runtime-compose)
androidx.navigation:navigation-compose      2.8.5
androidx.compose:compose-bom                2024.12.01   (material3, ui, ui-tooling, material-icons-extended)
androidx.room:room-{runtime,ktx,compiler}   2.6.1   (KSP)
androidx.work:work-runtime-ktx              2.10.0
androidx.datastore:datastore-preferences    1.1.1
androidx.browser:browser                    1.8.0   (Custom Tabs for open-in-browser)
com.squareup.okhttp3:okhttp                 4.12.0
org.jsoup:jsoup                             1.18.3
io.coil-kt:coil-compose                     2.7.0
org.jetbrains.kotlinx:kotlinx-coroutines-*  1.9.0

TEST: junit 4.13.2 · com.google.truth:truth 1.4.4 · app.cash.turbine:turbine 1.2.0
      org.robolectric:robolectric 4.14.1 · androidx.test:core 1.6.1 · androidx.test.ext:junit 1.2.1
      com.squareup.okhttp3:mockwebserver 4.12.0 · kotlinx-coroutines-test 1.9.0
      androidx.room:room-testing 2.6.1 · androidx.work:work-testing 2.10.0
UI FLOWS: Maestro (CLI, installed to ~/.maestro/bin)
```

**Deliberate omissions, do not add:** Hilt/Dagger (manual DI via `AppContainer`),
Retrofit (OkHttp is enough), WebView-based article rendering, Firebase, Compose
Multiplatform, any XML-layout UI.

### Why jsoup for parsing
One dependency covers all three parsing jobs — feed XML (`Parser.xmlParser()`,
lenient, never throws on malformed markup), homepage `<link rel="alternate">`
auto-discovery, and HTML entry-content sanitization. Critically it is **pure JVM**,
so the entire parser test corpus runs as fast `src/test` unit tests with no emulator
and no Robolectric. That is the backbone of the TDD loop.

## 3. Package structure

```
app/src/main/java/dev/mkiros/perch/
├─ PerchApp.kt                     Application; builds AppContainer; schedules work
├─ MainActivity.kt                 single activity, edge-to-edge, hosts NavHost
├─ di/AppContainer.kt              manual DI: db, okhttp, repos, clock, dispatchers
├─ data/
│  ├─ db/  PerchDatabase.kt  FeedDao.kt  EntryDao.kt
│  │       entity/{FeedEntity,EntryEntity}.kt  Converters.kt
│  ├─ net/ HttpModule.kt  FeedFetcher.kt  FetchResult.kt      (conditional GET)
│  ├─ parse/ FeedParser.kt  RssParser.kt  AtomParser.kt  RdfParser.kt
│  │         ParsedFeed.kt  ParsedEntry.kt  DateParser.kt
│  │         FeedDiscovery.kt  HtmlSanitizer.kt  ContentBlocks.kt
│  ├─ opml/ OpmlImporter.kt  OpmlExporter.kt
│  └─ repo/ FeedRepository.kt  EntryRepository.kt  RefreshCoordinator.kt
│           SettingsRepository.kt   (DataStore)
├─ work/ RefreshWorker.kt  WorkScheduler.kt
└─ ui/
   ├─ theme/ Color.kt  Type.kt  Theme.kt  Dimens.kt
   ├─ nav/   PerchNavHost.kt  Routes.kt
   ├─ home/  HomeScreen.kt  HomeViewModel.kt  EntryRow.kt  SourceDrawer.kt
   ├─ source/ AddSourceSheet.kt  SourceViewModel.kt  ManageSourceDialogs.kt
   ├─ article/ ArticleScreen.kt  ArticleViewModel.kt  RichText.kt
   ├─ settings/ SettingsScreen.kt  SettingsViewModel.kt
   └─ components/ EmptyState.kt  ErrorBanner.kt  Loading.kt  UnreadBadge.kt
app/src/test/java/dev/mkiros/perch/…      JVM + Robolectric unit tests (primary)
app/src/androidTest/java/…                 minimal instrumentation smoke test only
app/src/debug/assets/seed/                 bundled fixture feeds for screenshots
fixtures/feeds.txt  fixtures/snapshots/*.xml
maestro/*.yaml
```

## 4. Data model

```kotlin
@Entity(tableName = "feeds", indices = [Index(value=["feedUrl"], unique=true)])
data class FeedEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val feedUrl: String,          // resolved feed URL (post-discovery, post-redirect)
  val siteUrl: String?,         // homepage
  val title: String,            // parsed title
  val customTitle: String?,     // user rename; display = customTitle ?: title
  val faviconUrl: String?,
  val etag: String?,            // conditional GET
  val lastModified: String?,    // conditional GET
  val lastFetchedAt: Long?,     // epoch millis
  val lastSuccessAt: Long?,
  val lastError: String?,       // null = healthy; non-null renders per-source error
  val consecutiveFailures: Int = 0,
  val addedAt: Long,
  val sortIndex: Int = 0,
)

@Entity(tableName="entries", foreignKeys=[…CASCADE on feedId…],
        indices=[Index(value=["feedId","guid"], unique=true), Index("publishedAt"), Index("isRead")])
data class EntryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val feedId: Long,
  val guid: String,             // identity, see §5
  val title: String,            // plain text, HTML-unescaped, never blank ("(untitled)")
  val link: String?,
  val author: String?,
  val publishedAt: Long,        // epoch millis; fallback chain in §5
  val publishedIsEstimated: Boolean,
  val summary: String?,         // plain-text snippet, ≤300 chars, for the list row
  val contentHtml: String?,     // sanitized HTML for the article screen
  val imageUrl: String?,        // lead image if the feed offers one
  val isRead: Boolean = false,
  val readAt: Long?,
  val isStarred: Boolean = false,   // schema only; no UI in v1
  val fetchedAt: Long,
)
```

Room schema exported to `app/schemas/`. Pre-1.0 migrations use
`fallbackToDestructiveMigration()`; **the moment the first APK is installed for daily
use (task T31), destructive migration is removed** and real `Migration` objects are
required.

## 5. Parsing contract (the standing tests defend this)

**Formats:** RSS 2.0 / RSS 0.9x, Atom 1.0, RDF (RSS 1.0). Dispatch on the root
element name/namespace, never on the URL or Content-Type.

**Never throw.** `FeedParser.parse(bytes, contentType, requestUrl)` returns
`ParseResult.Success(ParsedFeed)` or `ParseResult.Failure(reason)`. A malformed
document yields Failure or a partial feed — never an exception escaping the call.

**Encoding:** honor the XML declaration, then the HTTP `charset`, then UTF-8;
mis-declared bytes must not crash (jsoup byte-stream parse handles this).

**GUID fallback chain:** `<guid>`/`<id>` → `<link>` → `sha256(title + publishedRaw)`.
Stable across refetches — this is what prevents duplicate entries.

**Date fallback chain:** RFC-822 (all the broken real-world variants: missing
leading zero, `GMT`/`UT`/`EST`/`+0000`/`Z`, weekday mismatch) → ISO-8601/RFC-3339
(with and without millis, `Z` or offset) → a handful of observed junk formats →
**feed-level `<lastBuildDate>`/`<updated>`** → `fetchedAt` with
`publishedIsEstimated = true`. Never 1970, never a future date more than 24h out
(clamp to now).

**Titles:** HTML entities decoded, tags stripped, whitespace collapsed.

**Content:** `content:encoded` > `<content>` > `<summary>`/`<description>`.
Sanitized allowlist: `p br h1–h6 ul ol li blockquote pre code em strong b i a img
figure figcaption hr table thead tbody tr th td sub sup` + `a[href]` `img[src|alt]`.
Everything else stripped. Relative URLs resolved against the entry link. No scripts,
no iframes, no styles, no tracking pixels (`img` ≤ 1px dropped).

**Auto-discovery:** given a homepage, prefer
`link[rel=alternate][type=application/atom+xml]`, then `application/rss+xml`, then
`application/rdf+xml`, then common paths (`/feed`, `/rss.xml`, `/atom.xml`,
`/index.xml`, `/feed.xml`, `/feeds/all.atom.xml`). Resolve relative hrefs. If the
pasted URL already parses as a feed, skip discovery entirely.

## 6. Networking

- One shared `OkHttpClient`: 15s connect / 30s read, retry-on-failure, gzip (default),
  10 MiB disk cache at `cacheDir/http`, redirects followed (incl. cross-protocol).
- UA: `Perch/1.0 (Android; +local-first RSS reader)`.
- **Conditional GET is mandatory**: send `If-None-Match` from stored `etag` and
  `If-Modified-Since` from stored `lastModified`; a `304` is a success that touches
  `lastFetchedAt` only and does zero parsing/DB writes.
- Refresh concurrency: 4 feeds in flight max, per-feed failures isolated.
- Body cap 8 MiB; exceeding it is a per-source error, not a crash.

## 7. Refresh policy

- Manual pull-to-refresh refreshes all feeds in the current scope.
- WorkManager `PeriodicWorkRequest`, default 1h (user-selectable 15m/1h/3h/6h/manual
  in Settings), `NetworkType.CONNECTED`, `ExistingPeriodicWorkPolicy.UPDATE`,
  10s backoff → exponential.
- A feed with `consecutiveFailures >= 5` drops to a 6h floor until it succeeds.
- Retention: keep unread forever; delete read entries older than 30 days on each
  successful refresh, but never delete an entry still present in the current feed body.

## 8. Read state

- Opening an article marks it read (write-through, immediate; list row updates via Flow).
- Long-press a row → toggle read/unread.
- Overflow → **Mark all read** in the current scope (unified or single source),
  with an undo snackbar (5s window, single-level undo).
- Unread counts per source and a total, exposed as a Room `Flow<Map<Long,Int>>` —
  computed by SQL `COUNT`, never in Kotlin.

## 9. OPML

- **Export:** OPML 2.0, `<outline type="rss" text= title= xmlUrl= htmlUrl=>`, flat
  (no folders in v1), written via SAF `CreateDocument` → `perch-YYYYMMDD.opml`.
- **Import:** SAF `OpenDocument`, accepts nested outlines (flattened), dedupes against
  existing `feedUrl`, imports without fetching, then triggers one refresh.
  Reports `n added / m duplicates / k invalid`.
- Round-trip is a standing unit test: export → import → identical source set.

## 10. Navigation

Single activity, `NavHost`, 4 destinations:
`home` (unified/filtered list; drawer holds the source list) → `article/{entryId}`
→ `settings`. Add-source and rename/remove are bottom sheets and dialogs over `home`,
not destinations. Back from an article returns to the list with scroll position intact.

## 11. Definition of done (project level)

1. `./gradlew test assembleDebug` green from a clean checkout.
2. Every snapshot in `fixtures/snapshots/` parses to correct title/date/link/content
   (standing corpus test).
3. All 10 product requirements in PROMPT.md §3 work end-to-end against the real feed
   list, verified by tests and by screenshot review against DESIGN.md.
4. The Maestro regression flow passes: add source → refresh → read → filter → remove
   → OPML export.
5. APK path recorded in NOTES.md.
