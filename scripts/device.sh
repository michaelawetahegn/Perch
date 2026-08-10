#!/usr/bin/env bash
# device.sh — the WSL ↔ Windows Android device bridge.
#
# WHY THIS EXISTS: this host is Windows 10, where WSL2 cannot do nested
# virtualization, so /dev/kvm will never appear inside WSL and a Linux-side emulator
# would run under pure software emulation (unusably slow). The fix is to run the
# emulator on the WINDOWS side, where it is hardware-accelerated via WHPX, and drive
# it from WSL through interop.
#
#   Gradle / tests / git   → WSL   (native ext4, fast)
#   emulator + adb server  → Windows (WHPX-accelerated)
#
# This script is the ONLY place that knows how to cross that boundary. Never call
# adb directly from WSL — a Linux adb server and the Windows adb server will fight
# over the same emulator. Always go through `scripts/device.sh`.
#
# Usage:
#   scripts/device.sh check                 # environment report, exit 0 if usable
#   scripts/device.sh boot                  # boot the AVD if not already up
#   scripts/device.sh install <app.apk>     # linux path in, installs on device
#   scripts/device.sh screenshot <out.png>  # verified non-blank PNG at a linux path
#   scripts/device.sh shell <cmd...>        # adb shell passthrough
#   scripts/device.sh adb <args...>         # raw adb passthrough
#   scripts/device.sh stage <path>          # copy to Windows, echo the Windows path
#   scripts/device.sh reboot | stop
set -uo pipefail

WIN_SDK_UNIX=${WIN_SDK_UNIX:-/mnt/c/Android/Sdk}     # no spaces in the path, on purpose
WIN_SDK_WIN=${WIN_SDK_WIN:-'C:\Android\Sdk'}
STAGE_UNIX=${STAGE_UNIX:-/mnt/c/perch-stage}         # Windows-visible scratch dir
STAGE_WIN=${STAGE_WIN:-'C:\perch-stage'}
AVD=${AVD:-perch}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-900}                    # 15 min is generous once WHPX is on
SCREEN_SIZE=${SCREEN_SIZE:-1080x1920}                # AVD default is 320x640 — far too small.
SCREEN_DENSITY=${SCREEN_DENSITY:-420}                # 1080x1920 @420dpi = 411x731dp, a normal phone.
                                                     # `wm size` clamps to the AVD's 1:2 physical
                                                     # aspect, so 1080x2400 lands as 1080x1920 anyway.

ADB="$WIN_SDK_UNIX/platform-tools/adb.exe"
EMU="$WIN_SDK_UNIX/emulator/emulator.exe"

die() { echo "device.sh: $*" >&2; exit 1; }
have_sdk() { [ -x "$ADB" ]; }

_adb() { "$ADB" "$@"; }

booted() {
  have_sdk || return 1
  [ "$(_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" = "1" ]
}

whpx_state() {
  powershell.exe -NoProfile -Command \
    "(Get-CimInstance Win32_OptionalFeature -Filter \"Name='HypervisorPlatform'\").InstallState" \
    2>/dev/null | tr -d '\0\r\n'
}

cmd_check() {
  echo "── device bridge check ─────────────────────────────"
  local ok=0
  if have_sdk; then echo "✔ Windows SDK      $WIN_SDK_UNIX"
  else echo "✘ Windows SDK      missing at $WIN_SDK_UNIX (T03 installs it)"; ok=1; fi
  if [ -x "$EMU" ]; then echo "✔ emulator.exe     present"
  else echo "✘ emulator.exe     missing"; ok=1; fi
  case "$(whpx_state)" in
    1) echo "✔ WHPX             enabled — emulator is hardware-accelerated" ;;
    2) echo "✘ WHPX             DISABLED — see ACTION REQUIRED in NOTES.md."
       echo "                   The emulator will be very slow until this is fixed."; ok=1 ;;
    *) echo "? WHPX             could not determine state" ;;
  esac
  if have_sdk && [ -x "$EMU" ]; then
    echo "  AVDs:            $("$EMU" -list-avds 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
  fi
  if booted; then echo "✔ device           booted ($(_adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r'))"
  else echo "· device           not booted"; fi
  echo "────────────────────────────────────────────────────"
  return $ok
}

cmd_boot() {
  have_sdk || die "Windows SDK not installed yet — T03 does that"
  booted && { echo "already booted"; return 0; }
  "$EMU" -list-avds 2>/dev/null | tr -d '\r' | grep -qx "$AVD" || die "AVD '$AVD' does not exist"

  [ "$(whpx_state)" = "1" ] || echo "device.sh: WARNING — WHPX is off, this boot will be slow" >&2
  echo "device.sh: booting AVD '$AVD' on the Windows side…"
  # `start` fully detaches the emulator into its own Windows process, so it outlives
  # this WSL command and survives between loop sessions.
  #
  # **Never wait on this call.** On 2026-08-09 it hung for 78 minutes: the emulator
  # booted normally within a couple of minutes, but the WSL interop wrapper around
  # `cmd.exe /c start /B` never returned, so this function never reached the polling
  # loop below and BOOT_TIMEOUT never got a chance to fire. Backgrounding it means the
  # boot's success is decided by `booted()` polling adb — the only thing that actually
  # knows — and `timeout` reaps the wrapper if it hangs again.
  timeout 120 cmd.exe /c start "" /B "$WIN_SDK_WIN\\emulator\\emulator.exe" \
      -avd "$AVD" -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot-save </dev/null >/dev/null 2>&1 &
  disown 2>/dev/null || true

  local t0 now; t0=$(date +%s)
  while ! booted; do
    now=$(date +%s)
    if [ $((now - t0)) -ge "$BOOT_TIMEOUT" ]; then
      echo "device.sh: emulator did not boot within ${BOOT_TIMEOUT}s" >&2
      return 1
    fi
    sleep 10
  done
  _adb shell wm size "$SCREEN_SIZE" >/dev/null 2>&1
  _adb shell wm density "$SCREEN_DENSITY" >/dev/null 2>&1
  _adb shell settings put global window_animation_scale 0 >/dev/null 2>&1
  _adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1
  _adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1
  echo "device.sh: booted in $(( $(date +%s) - t0 ))s"
}

# Copy a Linux file into the Windows-visible stage dir; echo its Windows path.
# adb.exe cannot reliably read \\wsl.localhost\… UNC paths, so we always stage.
cmd_stage() {
  local src=${1:?stage <path>}
  [ -e "$src" ] || die "no such file: $src"
  mkdir -p "$STAGE_UNIX"
  local base; base=$(basename "$src")
  cp -r "$src" "$STAGE_UNIX/$base" || die "could not stage $src"
  printf '%s\\%s\n' "$STAGE_WIN" "$base"
}

cmd_install() {
  local apk=${1:?install <app.apk>}
  booted || die "device not booted — run: scripts/device.sh boot"
  local win; win=$(cmd_stage "$apk") || exit 1
  _adb install -r -g "$win"
}

cmd_screenshot() {
  local out=${1:?screenshot <out.png>}
  booted || die "device not booted — run: scripts/device.sh boot"
  mkdir -p "$(dirname "$out")"
  # exec-out (not `shell`) keeps the byte stream binary-clean across the interop pipe.
  _adb exec-out screencap -p > "$out" 2>/dev/null
  # Verify it is a real PNG and not a blank/error frame — a corrupted capture that
  # silently "succeeds" is how a screenshot task wastes an entire session.
  local magic size
  magic=$(head -c 4 "$out" | od -An -tx1 | tr -d ' \n')
  size=$(stat -c %s "$out" 2>/dev/null || echo 0)
  [ "$magic" = "89504e47" ] || { echo "device.sh: not a PNG (magic=$magic) — capture failed" >&2; return 1; }
  [ "$size" -gt 10000 ] || { echo "device.sh: PNG only ${size}B — screen is probably blank" >&2; return 1; }
  echo "device.sh: $out (${size} bytes)"
}

cmd_reboot() {
  booted || { echo "not booted; nothing to reboot"; return 0; }
  _adb reboot; sleep 10; _adb wait-for-device >/dev/null 2>&1
  local t0; t0=$(date +%s)
  while ! booted; do
    [ $(( $(date +%s) - t0 )) -ge "$BOOT_TIMEOUT" ] && { echo "device.sh: reboot timed out" >&2; return 1; }
    sleep 10
  done
  _adb shell wm size "$SCREEN_SIZE" >/dev/null 2>&1
  _adb shell wm density "$SCREEN_DENSITY" >/dev/null 2>&1
  echo "device.sh: rebooted in $(( $(date +%s) - t0 ))s"
}

case "${1:-check}" in
  check)      cmd_check ;;
  boot)       cmd_boot ;;
  install)    shift; cmd_install "$@" ;;
  screenshot) shift; cmd_screenshot "$@" ;;
  stage)      shift; cmd_stage "$@" ;;
  shell)      shift; have_sdk || die "SDK missing"; _adb shell "$@" ;;
  adb)        shift; have_sdk || die "SDK missing"; _adb "$@" ;;
  reboot)     cmd_reboot ;;
  stop)       have_sdk && _adb emu kill >/dev/null 2>&1; echo "stopped" ;;
  *)          die "unknown command '$1' (check|boot|install|screenshot|stage|shell|adb|reboot|stop)" ;;
esac
