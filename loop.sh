#!/usr/bin/env bash
# loop.sh — the Ralph loop for Perch.
#
# Runs Claude Code headlessly, one $PLAN task per session, until that plan has no
# unchecked `[ ]` tasks left. Survives usage limits by napping. Stops loudly if it
# stalls. Designed to be started once and left alone for days:
#
#     ./loop.sh              # run in the foreground
#     nohup ./loop.sh &      # or detach; watch with: tail -f loop.log
#
set -uo pipefail
cd "$(dirname "$0")"

# ── knobs ────────────────────────────────────────────────────────────────────
# The active plan. v0.1 (T01–T32) and v0.2 (U01–U16) are complete and archived in
# docs/plans/. v0.5 is four sequential slices — PLAN-5 (#22) is done and archived;
# PLAN-6 (#23) is active. Change this one line to move to the next slice.
PLAN=${PLAN:-PLAN-6.md}
PROMPT="Read CLAUDE.md and continue"
LOG=${LOG:-"loop-$(basename "$PLAN" .md | tr 'A-Z' 'a-z').log"}
SLEEP_BETWEEN=${SLEEP_BETWEEN:-30}        # pause between normal sessions (s)
LIMIT_SLEEP=${LIMIT_SLEEP:-600}           # nap on usage/rate limit (s) = 10 min.
                                          # A limited session fails instantly and costs
                                          # no quota, so probing often is nearly free and
                                          # picks work back up soon after the reset.
SESSION_TIMEOUT=${SESSION_TIMEOUT:-7200}  # hard cap on one session (s) = 2 h
STALL_LIMIT=${STALL_LIMIT:-3}             # consecutive no-commit sessions before stop
BOOT_TIMEOUT=${BOOT_TIMEOUT:-900}         # emulator boot cap (s); WHPX-accelerated on Windows
AVD=${AVD:-perch}
REBOOT_EVERY=${REBOOT_EVERY:-86400}       # nightly adb reboot as insurance (s)
REBOOT_STAMP=".loop_last_reboot"

# Claude Code needs JDK 17 on PATH for any Gradle work; system java is 8.
# ANDROID_HOME here is the WSL-side SDK used by Gradle (platforms + build-tools only).
# The emulator/adb live in the WINDOWS SDK and are reached via scripts/device.sh.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/.maestro/bin:$PATH"
export AVD
[ -d "$HOME/.jdks/temurin-17" ] && export JAVA_HOME="$HOME/.jdks/temurin-17" && export PATH="$JAVA_HOME/bin:$PATH"

# ── plumbing ─────────────────────────────────────────────────────────────────
ts()  { date '+%Y-%m-%d %H:%M:%S'; }
say() { printf '%s │ %s\n' "$(ts)" "$*" | tee -a "$LOG"; }
rule(){ printf '%s\n' "────────────────────────────────────────────────────────────────" | tee -a "$LOG"; }

# Unchecked tasks: `- [ ]` only. `- [x]` and `- [BLOCKED: …]` are both "not pending".
# NB: `grep -c` exits 1 on zero matches, so capture-then-default. A bare
# `grep -c … || echo 0` prints "0" TWICE and silently breaks the exit test.
count_matching() {
  local n
  n=$(grep -c "$1" "$PLAN" 2>/dev/null) || n=0
  printf '%s' "${n:-0}"
}
remaining() { count_matching '^- \[ \]'; }
blocked()   { count_matching '^- \[BLOCKED'; }
head_sha()  { git rev-parse HEAD 2>/dev/null || echo none; }

# A limit message means "come back later", not "the project failed".
# Keep this generous. A limit phrasing that is NOT matched here gets counted as a
# stall, and three of those stop the loop — which is exactly what happened on
# 2026-08-07 to "You've hit your session limit · resets 3pm". Under-matching costs a
# whole run; over-matching costs one wasted nap.
LIMIT_RE='usage limit|rate limit|limit reached|session limit|hit your [a-z0-9-]* ?limit|limit will reset|resets [0-9]|too many requests|quota exceeded|429|overloaded_error'
hit_limit() {                       # $1 = session output file, $2 = session exit code
  tail -c 8000 "$1" | grep -qiE "$LIMIT_RE" && return 0
  # "try again" alone is noisy; only trust it when the session actually failed.
  [ "$2" -ne 0 ] && tail -c 8000 "$1" | grep -qiE 'try again' && return 0
  return 1
}

# ── memory guard ─────────────────────────────────────────────────────────────
# This is what killed the first run. Gradle leaves a daemon behind, Kotlin leaves
# another, and WSL2's vmmem balloons to the .wslconfig cap and never hands memory
# back to Windows. After ~11 sessions the host (15.9 GB, also running the WHPX
# emulator) had nothing left and the entire desktop stopped responding.
# So: every session ends by putting the JVMs down, and we log the headroom on both
# sides of the boundary so a slow leak is visible in loop.log instead of fatal.
MEM_FLOOR_MB=${MEM_FLOOR_MB:-1200}          # WSL available RAM below which we refuse to start

wsl_free_mb() { awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo; }
win_free_mb() {
  powershell.exe -NoProfile -Command \
    '[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1KB)' 2>/dev/null \
    | tr -d '\0\r\n'
}

reclaim() {
  [ -x ./gradlew ] && ./gradlew --stop >/dev/null 2>&1
  pkill -f KotlinCompileDaemon >/dev/null 2>&1
  sleep 2
  local w h
  w=$(wsl_free_mb); h=$(win_free_mb)
  say "memory after reclaim: WSL ${w}MB available · Windows ${h:-?}MB free"
  # A host that is this tight is minutes away from freezing again. Back off and let
  # Windows reclaim rather than starting another Gradle run on top of it.
  if [ -n "$h" ] && [ "$h" -lt 1500 ]; then
    say "⚠ Windows is under 1.5 GB free — pausing 120s to let the host recover"
    sleep 120
  fi
}

# The emulator lives on the Windows side (no nested virt on Windows 10 → no /dev/kvm
# in WSL). scripts/device.sh owns every detail of that bridge; the loop just asks.
DEV=./scripts/device.sh

emulator_online() { [ -x "$DEV" ] && BOOT_TIMEOUT=1 "$DEV" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' | grep -qx 1; }

boot_emulator() {
  [ -x "$DEV" ] || { say "scripts/device.sh missing — skipping emulator"; return 0; }
  if ! "$DEV" check >/dev/null 2>&1; then
    say "device bridge not ready yet (T03 pending, or WHPX still disabled — see NOTES.md). Continuing; nothing before T30 needs it."
    "$DEV" check 2>&1 | sed 's/^/         /' | tee -a "$LOG" >/dev/null
    return 0
  fi
  emulator_online && { say "emulator already up"; return 0; }
  say "booting the Windows-side emulator…"
  # Belt and braces: device.sh owns the boot timeout, but a *hung* boot (rather than a
  # slow one) is what actually stops this loop — see the 2026-08-09 entry in NOTES.md.
  # Nothing before V13 needs the device, so an unbootable emulator must cost minutes,
  # never the run.
  if BOOT_TIMEOUT="$BOOT_TIMEOUT" timeout $((BOOT_TIMEOUT + 180)) "$DEV" boot 2>&1 | tee -a "$LOG" | tail -1; then
    date +%s > "$REBOOT_STAMP"
  else
    say "!! emulator boot failed — continuing anyway (only T30 needs it)"
  fi
}

maybe_nightly_reboot() {
  emulator_online || return 0
  local last now
  last=$(cat "$REBOOT_STAMP" 2>/dev/null || echo 0)
  now=$(date +%s)
  [ $((now - last)) -lt "$REBOOT_EVERY" ] && return 0
  say "nightly emulator reboot (insurance against adb/emulator drift)"
  "$DEV" reboot 2>&1 | tail -1 | tee -a "$LOG" >/dev/null
  echo "$now" > "$REBOOT_STAMP"
}

# ── start ────────────────────────────────────────────────────────────────────
command -v claude >/dev/null 2>&1 || { echo "FATAL: 'claude' CLI not on PATH"; exit 1; }
[ -f "$PLAN" ] || { echo "FATAL: $PLAN not found in $(pwd)"; exit 1; }

rule
say "Ralph loop starting — $(remaining) task(s) pending in $PLAN"
reclaim
boot_emulator

session=0
stalls=0
naps=0

while :; do
  left=$(remaining)
  if [ "$left" -eq 0 ]; then
    rule
    say "✅ $PLAN has no unchecked tasks left. $(blocked) blocked. Loop complete."
    say "APK: see NOTES.md"
    rule
    exit 0
  fi

  maybe_nightly_reboot

  avail=$(wsl_free_mb)
  if [ "$avail" -lt "$MEM_FLOOR_MB" ]; then
    say "⚠ only ${avail}MB available in WSL — reclaiming before starting a session"
    reclaim
  fi

  session=$((session + 1))
  before=$(head_sha)
  next=$(grep -m1 '^- \[ \]' "$PLAN" | cut -c1-100)
  rule
  say "session #$session · $left task(s) left · next: ${next#- \[ \] }"

  out=$(mktemp)
  timeout "$SESSION_TIMEOUT" claude -p "$PROMPT" --dangerously-skip-permissions >"$out" 2>&1
  code=$?
  # Mirror the session transcript into loop.log with a timestamp on every line.
  while IFS= read -r line; do printf '%s │   %s\n' "$(ts)" "$line"; done <"$out" >>"$LOG"
  tail -n 40 "$out"

  if [ "$code" -eq 124 ]; then
    say "!! session #$session hit the ${SESSION_TIMEOUT}s timeout and was killed"
  fi

  if hit_limit "$out" "$code"; then
    naps=$((naps + 1))
    say "⏳ usage/rate limit detected (nap #$naps) — sleeping $((LIMIT_SLEEP / 60))m, then retrying. The loop never gives up on a limit."
    rm -f "$out"
    session=$((session - 1))   # a nap is not a real session
    sleep "$LIMIT_SLEEP"
    continue
  fi
  rm -f "$out"
  reclaim

  after=$(head_sha)
  if [ "$after" = "$before" ]; then
    stalls=$((stalls + 1))
    say "⚠ no new commit (exit $code) — consecutive stalls: $stalls/$STALL_LIMIT"
    if [ "$stalls" -ge "$STALL_LIMIT" ]; then
      rule
      say "🛑 STALLED: $STALL_LIMIT consecutive sessions produced no commit. Stopping."
      say "🛑 Silent thrash is worse than stopping. Check the tail of $LOG and NOTES.md,"
      say "🛑 then fix or mark the offending task [BLOCKED: …] in $PLAN and rerun ./loop.sh."
      rule
      printf '\a'
      exit 2
    fi
  else
    stalls=0
    say "✔ committed: $(git log -1 --oneline)"
  fi

  sleep "$SLEEP_BETWEEN"
done
