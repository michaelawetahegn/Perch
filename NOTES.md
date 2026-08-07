# NOTES.md

Working memory for unattended sessions. **Keep under 100 lines.** Record environment
quirks, blocked-task diagnoses, excluded feeds, version bumps, residual polish items,
and the final APK path. Prune anything a future session no longer needs.

Not a diary. If a workaround now lives in a script or in CLAUDE.md, delete its note.

---

## ACTION REQUIRED (human, one-time, ~2 min + reboot)

**Enable Windows Hypervisor Platform.** Measured 2026-08-07:
`HypervisorPlatform = Disabled`, `VirtualMachinePlatform = Enabled`,
`Microsoft-Hyper-V-Hypervisor = Enabled`. WHPX is the one the Android emulator needs.

In an **Administrator** PowerShell on Windows:

```powershell
dism.exe /Online /Enable-Feature /FeatureName:HypervisorPlatform /All /NoRestart
Restart-Computer
```

(Or: Turn Windows features on or off → tick **Windows Hypervisor Platform**.)

Verify afterwards from WSL: `./scripts/device.sh check` should print `✔ WHPX enabled`.

**Do not chase `/dev/kvm` inside WSL — it is impossible on this host.** WSL2 nested
virtualization is a Windows 11 feature; this box is Windows 10 Pro 19045. The emulator
therefore runs on the *Windows* side (accelerated by WHPX) and is driven from WSL
through `scripts/device.sh`. Gradle, tests and git stay in WSL.

Until WHPX is on, T30 (Maestro) is the only task that truly suffers. T29 captures its
screenshots via Robolectric on the JVM, and T01–T28 + T31 never touch a device.

## Environment facts (measured at bootstrap, 2026-08-07)

- Windows 10 Pro 19045.6466 · WSL 2.7.11 · kernel 6.18.33.2 · Ubuntu userland.
- Host: Intel i7-4790K (4c/8t, VT-x + EPT ✔), 15.9 GB RAM, 65 GB free on `C:`.
- WSL VM: 4 CPUs, 9 GB RAM, 899 GB free on `/`.
- System `java` = **OpenJDK 8**; Temurin **17.0.20** now at `~/.jdks/temurin-17`.
- `/dev/kvm` absent and will stay absent (see above).
- WSL SDK installed at `~/Android/Sdk` (T01). No Windows SDK yet (that is T03).
  No physical device.
- Windows gateway from WSL: `172.18.144.1` (only needed if the interop bridge fails
  and adb has to go over TCP instead).

## Log

- 2026-08-07 — bootstrap: SPEC/DESIGN/PLAN/CLAUDE/loop.sh/device.sh/fixtures created.
- 2026-08-07 — T01 done: JDK 17.0.20, SDK (platform-tools 37.0.1, android-35,
  build-tools 35.0.0, emulator 37.1.11), Maestro 2.8.0. Next: T02.
