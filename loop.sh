#!/usr/bin/env bash
# loop.sh — the Ralph loop for Perch.
#
# Runs Claude Code headlessly, one PLAN.md task per session, until PLAN.md has no
# unchecked `[ ]` tasks left. Survives usage limits by napping. Stops loudly if it
# stalls. Designed to be started once and left alone for days:
#
#     ./loop.sh              # run in the foreground
#     nohup ./loop.sh &      # or detach; watch with: tail -f loop.log
#
set -uo pipefail
cd "$(dirname "$0")"

# ── knobs ────────────────────────────────────────────────────────────────────
PROMPT="Read CLAUDE.md and continue"
LOG="loop.log"
SLEEP_BETWEEN=${SLEEP_BETWEEN:-30}        # pause between normal sessions (s)
LIMIT_SLEEP=${LIMIT_SLEEP:-1800}          # nap on usage/rate limit (s) = 30 min
SESSION_TIMEOUT=${SESSION_TIMEOUT:-7200}  # hard cap on one session (s) = 2 h
STALL_LIMIT=${STALL_LIMIT:-3}             # consecutive no-commit sessions before stop
BOOT_TIMEOUT=${BOOT_TIMEOUT:-2700}        # emulator boot cap (s) = 45 min (no KVM here)
AVD=${AVD:-perch}
REBOOT_EVERY=${REBOOT_EVERY:-86400}       # nightly adb reboot as insurance (s)
REBOOT_STAMP=".loop_last_reboot"

# Claude Code needs JDK 17 on PATH for any Gradle work; system java is 8.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/.maestro/bin:$PATH"
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
  n=$(grep -c "$1" PLAN.md 2>/dev/null) || n=0
  printf '%s' "${n:-0}"
}
remaining() { count_matching '^- \[ \]'; }
blocked()   { count_matching '^- \[BLOCKED'; }
head_sha()  { git rev-parse HEAD 2>/dev/null || echo none; }

# A limit message means "come back later", not "the project failed".
LIMIT_RE='usage limit|rate limit|limit reached|too many requests|quota exceeded|429|overloaded_error'
hit_limit() {                       # $1 = session output file, $2 = session exit code
  tail -c 8000 "$1" | grep -qiE "$LIMIT_RE" && return 0
  # "try again" alone is noisy; only trust it when the session actually failed.
  [ "$2" -ne 0 ] && tail -c 8000 "$1" | grep -qiE 'try again' && return 0
  return 1
}

emulator_online() {
  command -v adb >/dev/null 2>&1 || return 1
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

boot_emulator() {
  command -v emulator >/dev/null 2>&1 || { say "emulator not installed yet (T01/T03 pending) — skipping"; return 0; }
  emulator -list-avds 2>/dev/null | grep -qx "$AVD" || { say "AVD '$AVD' not created yet (T03 pending) — skipping"; return 0; }
  emulator_online && { say "emulator already up"; return 0; }

  say "booting AVD '$AVD' headless (no KVM on this host — this can take a long time)…"
  adb start-server >/dev/null 2>&1
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -gpu swiftshader_indirect -no-snapshot-save >>emulator.log 2>&1 &
  local t0 now
  t0=$(date +%s)
  while :; do
    emulator_online && break
    now=$(date +%s)
    if [ $((now - t0)) -ge "$BOOT_TIMEOUT" ]; then
      say "!! emulator did not boot within ${BOOT_TIMEOUT}s — continuing anyway (non-visual tasks don't need it)"
      return 1
    fi
    sleep 15
  done
  adb shell wm size 540x1200 >/dev/null 2>&1
  say "emulator booted in $(( $(date +%s) - t0 ))s"
  date +%s > "$REBOOT_STAMP"
}

maybe_nightly_reboot() {
  emulator_online || return 0
  local last now
  last=$(cat "$REBOOT_STAMP" 2>/dev/null || echo 0)
  now=$(date +%s)
  [ $((now - last)) -lt "$REBOOT_EVERY" ] && return 0
  say "nightly emulator reboot (insurance against adb/emulator drift)"
  adb reboot >/dev/null 2>&1
  sleep 10
  adb wait-for-device >/dev/null 2>&1
  local t0; t0=$(date +%s)
  while ! emulator_online; do
    [ $(( $(date +%s) - t0 )) -ge "$BOOT_TIMEOUT" ] && { say "!! reboot did not complete in time"; break; }
    sleep 15
  done
  adb shell wm size 540x1200 >/dev/null 2>&1
  echo "$now" > "$REBOOT_STAMP"
}

# ── start ────────────────────────────────────────────────────────────────────
command -v claude >/dev/null 2>&1 || { echo "FATAL: 'claude' CLI not on PATH"; exit 1; }
[ -f PLAN.md ] || { echo "FATAL: PLAN.md not found in $(pwd)"; exit 1; }

rule
say "Ralph loop starting — $(remaining) task(s) pending in PLAN.md"
boot_emulator

session=0
stalls=0
naps=0

while :; do
  left=$(remaining)
  if [ "$left" -eq 0 ]; then
    rule
    say "✅ PLAN.md has no unchecked tasks left. $(blocked) blocked. Loop complete."
    say "APK: see NOTES.md"
    rule
    exit 0
  fi

  maybe_nightly_reboot

  session=$((session + 1))
  before=$(head_sha)
  next=$(grep -m1 '^- \[ \]' PLAN.md | cut -c1-100)
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

  after=$(head_sha)
  if [ "$after" = "$before" ]; then
    stalls=$((stalls + 1))
    say "⚠ no new commit (exit $code) — consecutive stalls: $stalls/$STALL_LIMIT"
    if [ "$stalls" -ge "$STALL_LIMIT" ]; then
      rule
      say "🛑 STALLED: $STALL_LIMIT consecutive sessions produced no commit. Stopping."
      say "🛑 Silent thrash is worse than stopping. Check the tail of $LOG and NOTES.md,"
      say "🛑 then fix or mark the offending task [BLOCKED: …] in PLAN.md and rerun ./loop.sh."
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
