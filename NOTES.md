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

## Log

- 2026-08-07 — bootstrap: SPEC/DESIGN/PLAN/CLAUDE/loop.sh/device.sh/fixtures created.
- 2026-08-07 — T01 done: JDK 17.0.20, SDK (platform-tools 37.0.1, android-35,
  build-tools 35.0.0, emulator 37.1.11), Maestro 2.8.0.
- 2026-08-07 — T02 done: skeleton builds clean (`test assembleDebug` exit 0, 1 test).
  Wrapper generated from a one-off Gradle 8.11.1 unzipped to `/tmp` (no system gradle);
  `./gradlew` is self-sufficient now. Truth's package is `com.google.common.truth`,
  **not** `com.google.truth`. Room/KSP are wired (deps + `room.schemaLocation`) but no
  `@Database` exists yet, so KSP is unexercised until T12.
- 2026-08-07 — T03 done: Windows SDK (cmdline-tools 15859902, platform-tools, emulator,
  `system-images;android-35;google_apis;x86_64`), AVD `perch`, booted headless.
  Cold first boot ≈ 11 min; budget 15 min (`BOOT_TIMEOUT=900`). Gotchas, all now
  encoded in scripts so no future session re-derives them:
  · `avdmanager create avd -d pixel_6` fails (no `devices.xml` without a `platforms`
    package) — create the AVD with **no** `-d`, which defaults to a 320x640 screen.
  · So `device.sh` now applies `wm size 1080x1920` + `wm density 420` on every boot.
    `wm size` clamps to the AVD's 1:2 physical aspect, so asking for 1080x2400 yields
    1080x1920. At 320x640 a screenshot is ~5 KB and trips the >10 KB blank-guard.
  · License acceptance: copy `~/Android/Sdk/licenses/*` to the Windows SDK. Piping
    `y` into `sdkmanager.bat --licenses` through interop does not work.
  · One bad package name makes `sdkmanager` install **nothing** — quote
    `system-images;...` inside a `.bat`, not on the interop command line.
- 2026-08-07 — T04 done: `scripts/harvest.sh` (re-runnable) → 42 manifest rows,
  **39 snapshots** in `fixtures/snapshots/`, 19 MB total. Homepage HTML for the four
  auto-discovery cases is kept in `fixtures/homepages/` — T11 needs it.
  **3 exclusions:**
  · `danluu.com` (11.1 MB) and `googleprojectzero.blogspot.com` → `projectzero.google`
    (13.2 MB) — both exceed SPEC.md §6's 8 MiB body cap, so the app would reject them
    live; the corpus must not contain feeds T09 requires to parse but T14 must refuse.
  · `research.nccgroup.com` — no longer publishes a feed anywhere. Homepage has zero
    `<link rel=alternate>` and /feed /feed/ /rss.xml /atom.xml /index.xml /feed.xml /rss/
    all soft-404 to the same 116 KB HTML page (HTTP 200). Kept as T11's negative case.
