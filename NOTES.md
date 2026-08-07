# NOTES.md

Working memory for unattended sessions. **Keep under 100 lines.** Record environment
quirks, blocked-task diagnoses, excluded feeds, version bumps, residual polish items,
and the final APK path. Prune anything a future session no longer needs.

Not a diary. If a workaround now lives in a script or in CLAUDE.md, delete its note.

---

## ACTION REQUIRED (human, one-time — the loop works without it, just slower)

`/dev/kvm` is **absent** in this WSL2 instance, so the Android emulator runs
unaccelerated on 4 cores: boots take tens of minutes and may fail outright. Nothing in
tasks T01–T28 or T31 needs it (all verification is JVM + Robolectric), but T29
(screenshot polish) and T30 (Maestro) do.

To fix, on the **Windows** side create/edit `C:\Users\<you>\.wslconfig`:

```ini
[wsl2]
nestedVirtualization=true
memory=12GB
processors=6
```

then run `wsl --shutdown` in PowerShell and reopen the shell. Verify with
`ls -l /dev/kvm`. (Requires Windows 11 / recent Windows 10 + WSL2. On Intel hosts also
confirm VT-x is on in firmware; on AMD, SVM.) If `/dev/kvm` appears, T03 will pick the
accelerated x86_64 image automatically.

## Environment facts (measured at bootstrap, 2026-08-07)

- WSL2, kernel 6.18.33.2-microsoft-standard-WSL2, Ubuntu userland.
- System `java` = **OpenJDK 8** → T01 installs Temurin 17; Gradle must use 17.
- 4 CPUs, 9 GB RAM, 899 GB free on `/`.
- No Android SDK installed yet. No physical device.

## Log

- 2026-08-07 — bootstrap session: SPEC/DESIGN/PLAN/CLAUDE/loop.sh/fixtures created.
  Nothing built yet. Next: T01.
