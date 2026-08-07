# NOTES.md

Working memory for unattended sessions. **Keep under 100 lines.** Record environment
quirks, blocked-task diagnoses, excluded feeds, version bumps, residual polish items,
and the final APK path. Prune anything a future session no longer needs.

Not a diary. If a workaround now lives in a script or in CLAUDE.md, delete its note.

---

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466 · WSL 2.7.11 · kernel 6.18.33.2 · Ubuntu userland.
- Host: Intel i7-4790K (4c/8t, VT-x + EPT ✔), 15.9 GB RAM, 65 GB free on `C:`.
- WSL VM: 4 CPUs, 9 GB RAM, 899 GB free on `/`.
- System `java` = **OpenJDK 8**; Temurin **17.0.20** now at `~/.jdks/temurin-17`.
- `/dev/kvm` absent and will stay absent — Win10 has no WSL2 nested virtualization.
  The emulator runs Windows-side under WHPX; do not chase KVM inside WSL.
- WSL SDK at `~/Android/Sdk` (T01). Windows SDK at `C:\Android\Sdk` (T03). No physical device.
- WHPX **enabled** (human did the reboot). `emulator -accel-check` →
  `WHPX(10.0.19045) is installed and usable.`
- Windows-side **JDK 17** at `C:\jdk17` (Temurin 17.0.20). Required: `sdkmanager.bat` /
  `avdmanager.bat` need it, and T30's Maestro will too. Helper wrappers that set
  `JAVA_HOME`+`ANDROID_HOME` live at `C:\perch-stage\sdk.bat` and `avd.bat`.
- Windows gateway from WSL: `172.18.144.1` (only needed if the interop bridge fails
  and adb has to go over TCP instead).
- **2026-08-07 — host froze (session #11). Cause: host memory, not any task's code.**
  WSL2 balloons `vmmem` to its `.wslconfig` cap and never releases it, and sessions left
  Gradle+Kotlin daemons resident; with the Windows emulator too, the whole desktop
  stalled. Fixed in `.wslconfig` (7 GB + `autoMemoryReclaim=gradual`), `gradle.properties`
  (caps — see CLAUDE.md) and `loop.sh` (`reclaim()` between sessions, refuses to start
  below 1200 MB headroom). **The `.wslconfig` half needs a `wsl --shutdown` to take
  effect** — until then the loop-side reclaim is doing all the work.

## Log

- 2026-08-07 — T01–T03 done (toolchain, skeleton, Windows emulator; everything durable
  about them is in CLAUDE.md §Environment). Two gotchas worth keeping: Truth's package is
  `com.google.common.truth`, **not** `com.google.truth`; Room/KSP are wired but no
  `@Database` exists yet, so KSP is unexercised until T12.
- 2026-08-07 — T04 done: `scripts/harvest.sh` (re-runnable) → 42 manifest rows,
  **39 snapshots** (19 MB); homepage HTML for the four auto-discovery cases is in
  `fixtures/homepages/` — T11 needs it. **3 exclusions:**
  · `danluu.com` (11.1 MB) and `googleprojectzero.blogspot.com` → `projectzero.google`
    (13.2 MB) — both exceed SPEC.md §6's 8 MiB body cap, so the app would reject them
    live; the corpus must not contain feeds T09 requires to parse but T14 must refuse.
  · `research.nccgroup.com` — no longer publishes a feed anywhere. Homepage has zero
    `<link rel=alternate>` and /feed /feed/ /rss.xml /atom.xml /index.xml /feed.xml /rss/
    all soft-404 to the same 116 KB HTML page (HTTP 200). Kept as T11's negative case.
- 2026-08-07 — T05–T08 done: `DateParser` (29), `RssParser` (19), `AtomParser` (24),
  `RdfParser` (21) + `FeedParser` dispatch (20). Everything they learned is now pinned by
  those tests; what a *future* task still needs to know: shared plumbing lives in
  `data/parse/FeedXml.kt` (lenient parse, direct-child + local-name lookup, `plainText`,
  `resolveUrl`, `stableGuid`, `leadImageUrl`) — reuse it rather than reparsing.
  Date floor is 2000-01-01 (below → null, caller falls back); >now+24h clamps to now.
  **The corpus contains zero RSS 1.0 feeds** (39/39 `<rss>`/`<feed>`), so RDF is covered
  only by hand-written docs — the corpus test will never exercise it.
  `FeedParser` decides the format from an **8 KiB prefix scan**, not a parse tree, which
  is what makes the 5 MB-of-noise case ~ms. Charset: XML declaration → HTTP `charset` →
  UTF-8, and an unknown charset name falls through to UTF-8 rather than failing the feed.
- 2026-08-07 — T09 done: `FeedCorpusTest` (78 tests = 39 snapshots × 2), green with no
  production change. Parameterized per feed; `requestUrl` from `manifest.tsv` so relative
  links resolve as they will live; the parameter list `check`s ≥35 snapshots so it cannot
  go vacuous. Every corpus entry has a **non-null** `publishedAt`, so the contract asserts
  non-null — the `fetchedAt` fallback stays T15's responsibility.
- 2026-08-07 — T10 done: `HtmlSanitizer` (16 tests; suite now 208, 0 failures). jsoup's
  `Cleaner` + a from-scratch `Safelist` does the allowlist, and **`addProtocols` is also
  what resolves relative URLs** — jsoup rewrites a protocol-restricted attribute to its
  absolute form, so no manual `resolveUrl` pass is needed. Two things Cleaner alone gets
  wrong: it *unwraps* a disallowed element and keeps its children (right for `<div>`,
  wrong for `<script>`), so those go first; and a refused URL leaves the element behind,
  so `a:not([href])` is unwrapped and `img:not([src])` removed after.
  Tracking pixels must go **before** cleaning — `width`/`height` are not allowlisted.
  `sanitize` → null when nothing renderable survives. Parsers still hand out raw
  `contentHtml`; sanitizing is T15/T25's call.
- 2026-08-07 — T11 done: `FeedDiscovery` (13 tests; suite now 221, 0 failures). xania.org
  **does** declare its feed — the `<link>` is split across two lines, which is why
  `harvest.sh`'s line-based grep missed it and fell through to `/feed` → redirect to
  `/feed.atom`. Hence jsoup, not a regex: attribute order, `rel` token lists and `type`
  parameters all vary. Discovery **trusts a declared feed URL without fetching it** (T16
  fetches it to add the source, and that is where a broken declaration should be reported)
  but **verifies every path guess by parsing it** — nccgroup answers 200-with-HTML to all
  six guesses, so an unverified guess would hand back an address that never parses.
  Ports `PageFetcher`/`FetchedPage` (bytes + contentType + post-redirect finalUrl) are
  minimal on purpose; T14's `FeedFetcher` should adapt to them, not the reverse.
- 2026-08-07 — T12 done: Room schema v1 + DAOs (11 tests; suite now 232, 0 failures).
  Schema exported to `app/schemas/…PerchDatabase/1.json` (committed). No `@TypeConverter`s
  exist and none are needed — every §4 column is SQLite-native, dates are epoch millis.
  **Do not use Room's `@Upsert` for entries.** It recovers from the unique-index conflict
  by updating on the *primary key*, which is still 0 on a freshly parsed entry, so the row
  is silently dropped. `EntryDao.upsertAll` instead matches on `(feedId, guid)`, carries
  the existing row's id forward, and preserves `isRead`/`readAt`/`isStarred` — read state
  belongs to the reader, not the feed. It returns the count of genuinely new entries,
  which is what T15's "refetch inserts zero rows" assertion reads.
