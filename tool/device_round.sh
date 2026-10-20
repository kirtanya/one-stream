  # Settings from a known state (strip hidden, so OK brings it up with focus on play): the strip
  # hides 5 s after playback resumes; a rebuffer keeps it up longer.
  sleep 6
  local i
  for ((i = 0; i < 5; i++)); do ui_dump | grep -q 'content-desc="Settings"' || break; sleep 2; done
  step_ok || return
  key 23 22 22 22 22 23; sleep 1.2; shot 05-settings                # OK, 4 × right to Settings, OK
  if panel_open; then
    ACTIONS+=("settings")
    step_ok || return
    key 4; sleep 0.8; shot 06-settings-closed                     # Back closes the panel
  else
    # Not open: pressing Back now could leave the player instead.
    NOTES+=("TV settings panel not seen open")
    return
  fi
#!/usr/bin/env bash
# Automated device round (docs/MACHINE_FIRST_PLAN.md step 4): on every connected device that is idle,
# install the APK, play the legal Tears of Steel sample, drive the player like a person would (TV: D-pad;
# phone: taps, double-tap, swipe-scrub, settings sheet), screenshot each step, collect the streaming
# numbers + crashes / ANRs / problem reports, clean up, and compare with the previous round.
#
#   tool/device_round.sh                     every idle device, newest APKs in ~/Desktop/APKs
#   tool/device_round.sh --only phone|tv     one device
#   tool/device_round.sh --phone-apk F --tv-apk F
#   tool/device_round.sh --force-install     reinstall even when that version is already installed
#   tool/device_round.sh --dry-run           safety gate + plan only; installs, opens and presses nothing
#
# Output: rounds/<timestamp>/<device>/NN-<action>.png, reelstats.txt, crash.txt, meta.json;
# rounds/<timestamp>/summary.json; rounds/latest.json (last clean round per device; the comparison point).
# Exit 1 only for a crash, an ANR or playback that never started. Slow swarms never fail a round.
#
# Safety (CLAUDE.md "Never disturb playback"): a device is driven only when it is awake, unlocked,
# nothing plays, and the launcher or One Stream's home screen is on top. Re-checked before each step:
# the moment something else is on top the round stops pressing anything on that device.
set -uo pipefail   # no -e: one device failing must not stop the other's round or its cleanup
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
APKS="$HOME/Desktop/APKs"
PKG=com.kirtanya.reel
PLAYER="$PKG/.player.PlayerActivity"
HOME_ACT="$PKG/.MainActivity"
SAMPLE_INDEX=2                                           # ui/Samples.kt: Tears of Steel (Blender, CC-BY)
SAMPLE_HASH=209c8226b299b308beaf2b9cd3fb49212dbd13ec
SAMPLE_KEY="file:$SAMPLE_HASH:-"                         # its watch-history key
TV_ADDR=192.168.1.128:5555
TTFF_TIMEOUT=120
SEEK_TIMEOUT=60
REMOTE_REPORTS=/sdcard/Android/data/$PKG/files/reports

only="" phone_apk="" tv_apk="" force_install=0 dry=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --only) only=$2; shift ;;
    --phone-apk) phone_apk=$2; shift ;;
    --tv-apk) tv_apk=$2; shift ;;
    --force-install) force_install=1 ;;
    --dry-run) dry=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done

[[ -x "$ADB" ]] || { echo "adb not found at $ADB"; exit 2; }
ts=$(date +%Y%m%d-%H%M%S)
ROUND="rounds/$ts$([[ $dry == 1 ]] && echo -dry)"
mkdir -p "$ROUND"
t0=$(date +%s)
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

# ---------------------------------------------------------------- the round logic (:core, tested)
say "preparing the round logic (core/round)…"
if ! ./gradlew -q :core:deviceRoundClasspath > "$ROUND/gradle.log" 2>&1; then
  cat "$ROUND/gradle.log"; echo "could not build core/round"; exit 2
fi
CP=$(cat core/build/device-round.classpath)
cli() { "$JAVA_HOME/bin/java" -cp "$CP" com.kirtanya.reel.core.round.DeviceRoundCli "$@"; }

# macOS has no `timeout`: run "$@" for at most $1 seconds.
with_timeout() {
  local s=$1; shift
  "$@" & local p=$!
  ( sleep "$s"; kill "$p" 2> /dev/null ) & local w=$!
  wait "$p"; local rc=$?
  kill "$w" 2> /dev/null; wait "$w" 2> /dev/null
  return $rc
}

# ---------------------------------------------------------------- find the devices
# TV: adb over the network on a fixed address (unreachable when it's asleep / off).
with_timeout 8 "$ADB" connect "$TV_ADDR" > /dev/null 2>&1
# Phone: wireless adb; already connected when paired, otherwise its mDNS address.
if ! "$ADB" devices | awk 'NR > 1 && $2 == "device"' | grep -q tls-connect; then
  for addr in $(with_timeout 5 "$ADB" mdns services 2> /dev/null | awk '/_adb-tls-connect/ { print $3 }'); do
    with_timeout 8 "$ADB" connect "$addr" > /dev/null 2>&1
  done
fi
serials=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')

# bash 3.2 (macOS): no associative arrays, so one serial per kind (there is one TV and one phone).
TV_SERIAL="" PHONE_SERIAL=""
for s in $serials; do
  if [[ $("$ADB" -s "$s" shell pm has-feature android.software.leanback 2> /dev/null | tr -d "\r") == true ]]; then TV_SERIAL=$s; else PHONE_SERIAL=$s; fi
done
for want in tv phone; do
  found=$([[ $want == tv ]] && echo "$TV_SERIAL" || echo "$PHONE_SERIAL")
  if [[ -z $found && ( -z $only || $only == "$want" ) ]]; then
    mkdir -p "$ROUND/$want"
    why=$([[ $want == tv ]] && echo "not reachable at $TV_ADDR (asleep or off the network)" || echo "not connected (wireless adb off or phone away)")
    jq -n --arg d "$want" --arg r "$why" '{device: $d, status: "skipped", reason: $r}' > "$ROUND/$want/meta.json"
    say "$want: skipped — $why"
  fi
done

# ---------------------------------------------------------------- per-device helpers (S = serial)
sh_() { "$ADB" -s "$S" shell "$@" 2> /dev/null | tr -d '\r'; }
top_activity() { sh_ dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -1; }
ours_on_top() { [[ $(top_activity) == "$PLAYER" ]]; }
key() { sh_ input keyevent "$@" > /dev/null; }
shot() {   # shot NN-name
  "$ADB" -s "$S" exec-out screencap -p > "$DIR/$1.png" 2> /dev/null
  [[ -s "$DIR/$1.png" ]] && say "$NAME: $1" || say "$NAME: $1 (screenshot failed)"
}
stats() { sh_ logcat -d -v brief -s ReelStats:I; }
seek_count() { stats | grep -c 'ev=seek ' || true; }
wait_seek() {   # wait_seek <count before>: true once a new seek finished (player ready again)
  local before=$1 i
  for ((i = 0; i < SEEK_TIMEOUT; i += 2)); do
    (( $(seek_count) > before )) && return 0
    sleep 2
  done
  return 1
}
ui_dump() { with_timeout 20 "$ADB" -s "$S" exec-out uiautomator dump /dev/tty 2> /dev/null | tr -d '\r'; }
bounds_centre() {   # bounds_centre <content-desc>: "x y" of that view in a fresh UI dump, or nothing
  ui_dump | grep -o "<node [^>]*content-desc=\"$1\"[^>]*>" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | tr -c '0-9\n' ' ' \
    | awk '{ printf "%d %d\n", ($1 + $3) / 2, ($2 + $4) / 2 }'
}
panel_open() { ui_dump | grep -q 'text="Speed"'; }
gate() {   # prints "idle" or "busy: why"
  {
    echo "### media_session"; sh_ dumpsys media_session
    echo "### top"; sh_ dumpsys activity activities | grep topResumedActivity
    echo "### window"; sh_ dumpsys window | grep -E 'isKeyguardShowing|mShowingDream'
    echo "### power"; sh_ dumpsys power | grep -m1 'mWakefulness='
    echo "### home"; sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
  } > "$DIR/gate.txt"
  cli gate "$DIR/gate.txt"
}
abort() {   # stop driving this device: someone else is using it
  STATUS=aborted; REASON=$1
  say "$NAME: ABORTED — $1 (no more input to this device)"
}
step_ok() {   # before every input: is our player still the thing on top?
  [[ $STATUS == ran ]] || return 1
  ours_on_top && return 0
  local top; top=$(top_activity)
  if [[ $top == "$PKG"/* ]]; then STATUS=ran; REASON=""; NOTES+=("player closed early (top: $top)"); return 1; fi
  abort "something else came to the front ($top)"; return 1
}

# ---------------------------------------------------------------- the actions
tv_actions() {
  local n
  step_ok || return
  key 23; sleep 0.8; shot 02-strip                                # OK: strip up, focus on play
  step_ok || return
  n=$(seek_count)
  # Play → ⏪ → ⏩ → subtitles → settings → seek bar (5 × right), then scrub 6 × right; one call
  # because the seek bar commits a scrub 0.7 s after the last key.
  key 22 22 22 22 22 22 22 22 22 22 22; sleep 0.3; shot 03-scrub
  key 23                                                          # OK: commit (no-op if it already did)
  if wait_seek "$n"; then ACTIONS+=("scrub"); else NOTES+=("TV scrub: no seek within ${SEEK_TIMEOUT}s"); fi
  shot 04-after-seek
  # Settings from a known state (strip hidden, so OK brings it up with focus on play): the strip
  # hides 5 s after playback resumes; a rebuffer keeps it up longer.
  sleep 6
  local i
  for ((i = 0; i < 5; i++)); do ui_dump | grep -q 'content-desc="Settings"' || break; sleep 2; done
  step_ok || return
  key 23 22 22 22 22 23; sleep 1.2; shot 05-settings              # OK, 4 × right to Settings, OK
  if ! panel_open; then
    NOTES+=("TV settings panel not seen open")
    return                                                        # Back now could leave the player instead
  fi
  ACTIONS+=("settings")
  step_ok || return
  key 4; sleep 0.8; shot 06-settings-closed                       # Back closes the panel
  step_ok || return
  key 4; sleep 0.8; shot 07-strip-hidden                          # Back hides the strip
  ACTIONS+=("back")
}

phone_actions() {
  local size w h n sx sy xy
  size=$(sh_ wm size | awk -F': ' '/Override size/ { o = $2 } /Physical size/ { p = $2 } END { print (o != "" ? o : p) }')
  w=${size%x*}; h=${size#*x}
  (( w < h )) && { local t=$w; w=$h; h=$t; }                      # the player is always landscape
  local cx=$((w / 2)) ty=$((h * 3 / 10)) my=$((h / 2))            # ty: on the picture, clear of top bar + centre buttons
  step_ok || return
  sh_ input tap $cx $ty; sleep 0.6; shot 02-controls               # tap: controls up
  xy=$(bounds_centre Settings)                                     # where the settings button really is
  [[ -n $xy ]] || NOTES+=("phone: UI dump failed; settings button by layout maths")
  sleep 5                                                          # controls hide 3.5 s after the last touch
  step_ok || return
  n=$(seek_count)
  sh_ "input tap $((w * 5 / 6)) $my; input tap $((w * 5 / 6)) $my"; sleep 0.3; shot 03-double-tap   # right third: +10 s
  if wait_seek "$n"; then ACTIONS+=("double-tap"); else NOTES+=("phone double-tap: no seek (taps too slow for a double-tap?)"); fi
  sleep 2
  step_ok || return
  n=$(seek_count)
  sh_ input swipe $((w * 3 / 10)) $my $((w * 7 / 10)) $my 800; sleep 0.1; shot 04-swipe-scrub   # 0.4 width = +48 s
  if wait_seek "$n"; then ACTIONS+=("swipe-scrub"); else NOTES+=("phone swipe-scrub: no seek within ${SEEK_TIMEOUT}s"); fi
  shot 05-after-seek
  sleep 5
  step_ok || return
  if [[ -n $xy ]]; then sx=${xy% *}; sy=${xy#* }
  else # PhoneControls: Settings is the last 48 dp button of the top bar, 8 dp in (cutout side unknown).
    local dp=$(( $(sh_ wm density | awk -F': ' '{ d = $2 } END { print d }') / 160 ))
    sx=$((w - 32 * dp)); sy=$((32 * dp))
  fi
  # Show + tap Settings in ONE shell call: the controls hide 3.5 s after a touch.
  sh_ "input tap $cx $ty; sleep 0.4; input tap $sx $sy"; sleep 1; shot 06-settings
  if panel_open; then ACTIONS+=("settings")
  else
    # The controls may still have been up (the first tap hid them): try the button once more.
    step_ok || return
    sh_ input tap $sx $sy; sleep 1; shot 06b-settings-retry
    if panel_open; then ACTIONS+=("settings"); NOTES+=("phone settings opened on the 2nd try"); else NOTES+=("phone settings sheet not seen open"); fi
  fi
  step_ok || return
  sh_ input tap $((w / 5)) $ty; sleep 0.8; shot 07-settings-closed   # a tap on the picture closes the sheet
  ACTIONS+=("close-settings")
}

# Back out of the player to our home screen, then HOME: the device is left on its launcher.
leave_player() {
  local i top
  for ((i = 0; i < 5; i++)); do
    top=$(top_activity)
    [[ $top == "$PLAYER" ]] || break
    key 4; sleep 1.5
  done
  top=$(top_activity)
  if [[ $top == "$PKG"/* ]]; then key 3; sleep 1; fi
  shot 99-left-on-launcher
}

# Continue watching as it was: the sample removed, or the user's own position on it put back.
restore_history() {
  if [[ -n $PRIOR_POS ]]; then
    sh_ am broadcast -n "$PKG/.data.SourcesProbe" --es setPosition "$SAMPLE_KEY" --el ms "$((PRIOR_POS * 1000))" > /dev/null
    NOTES+=("Tears of Steel was already in Continue watching; its position (${PRIOR_POS}s) was put back")
  else
    sh_ am broadcast -n "$PKG/.data.SourcesProbe" --es removeTitle "$SAMPLE_KEY" > /dev/null
  fi
  sleep 1.5
  sh_ logcat -c
  sh_ am broadcast -n "$PKG/.data.SourcesProbe" --ez history true > /dev/null
  sleep 2
  if [[ -z $PRIOR_POS ]] && sh_ logcat -d -s ReelProbe:I | grep -q "history $SAMPLE_KEY "; then
    NOTES+=("sample still in watch history after removeTitle")
  fi
}

write_meta() {
  local end; end=$(date +%s)
  jq -n --arg device "$NAME" --arg serial "$S" --arg model "$MODEL" --arg status "$STATUS" --arg reason "$REASON" \
    --arg apk "$APK" --arg version "$VER" --arg installed "$INSTALLED" --arg sampleKey "$SAMPLE_HASH" \
    --argjson durationSec "$((end - START))" \
    --argjson actions "$(printf '%s\n' "${ACTIONS[@]+"${ACTIONS[@]}"}" | jq -R . | jq -sc 'map(select(length > 0))')" \
    --argjson notes "$(printf '%s\n' "${NOTES[@]+"${NOTES[@]}"}" | jq -R . | jq -sc 'map(select(length > 0))')" \
    --argjson newReports "$(printf '%s\n' "${NEW_REPORTS[@]+"${NEW_REPORTS[@]}"}" | jq -R . | jq -sc 'map(select(length > 0))')" \
    '{device: $device, serial: $serial, model: $model, status: $status, reason: $reason, apk: $apk, version: $version,
      install: $installed, sampleKey: $sampleKey, durationSec: $durationSec, actions: $actions, notes: $notes, newReports: $newReports}' \
    > "$DIR/meta.json"
}

CLEANUP_NEEDED=0
on_interrupt() {
  if [[ $CLEANUP_NEEDED == 1 ]]; then
    say "$NAME: interrupted — leaving the player and cleaning up"
    ours_on_top && leave_player
    restore_history
    STATUS=aborted; REASON="interrupted (Ctrl-C)"; write_meta
  fi
  exit 130
}
trap on_interrupt INT TERM

# ---------------------------------------------------------------- one device
run_device() {
  S=$1; NAME=$2; DIR="$ROUND/$NAME"; mkdir -p "$DIR"
  MODEL=$(sh_ getprop ro.product.model); START=$(date +%s)
  STATUS=ran REASON="" APK="" VER="" INSTALLED="" PRIOR_POS=""
  ACTIONS=() NOTES=() NEW_REPORTS=()

  # 1. Safety gate.
  local g; g=$(gate)
  if [[ $g != idle ]]; then
    STATUS=skipped; REASON="${g#busy: }"; write_meta
    say "$NAME ($MODEL): skipped — $REASON"; return
  fi
  # 2. APK: newest OneStream-<ver>-<phone|tv>.apk unless one was given.
  APK=$([[ $NAME == tv ]] && echo "$tv_apk" || echo "$phone_apk")
  [[ -n $APK ]] || APK=$(ls -t "$APKS"/OneStream-*-"$NAME".apk 2> /dev/null | head -1)
  [[ -f $APK ]] || { STATUS=skipped; REASON="no OneStream-*-$NAME.apk in $APKS"; write_meta; say "$NAME: skipped — $REASON"; return; }
  VER=$(basename "$APK" | sed -E "s/^OneStream-(.*)-$NAME\.apk$/\1/")
  local have; have=$(sh_ dumpsys package $PKG | grep -m1 versionName= | cut -d= -f2)
  if [[ $dry == 1 ]]; then
    say "$NAME ($MODEL): idle. Would install $(basename "$APK") (installed: ${have:-none}), play Tears of Steel, $([[ $NAME == tv ]] && echo "OK / scrub / settings / Back" || echo "tap / double-tap / swipe-scrub / settings sheet"), clean up."
    STATUS=skipped; REASON="dry run (gate: idle)"; write_meta; return
  fi
  say "$NAME ($MODEL): idle — starting the round"
  if [[ $have == "$VER" && $force_install == 0 ]]; then
    INSTALLED="already $VER"
  else
    say "$NAME: installing $(basename "$APK") (was ${have:-not installed})…"
    if with_timeout 300 "$ADB" -s "$S" install -r "$APK" > "$DIR/install.txt" 2>&1; then INSTALLED="installed $VER"
    else STATUS=aborted; REASON="install failed: $(tail -1 "$DIR/install.txt")"; write_meta; say "$NAME: $REASON"; return; fi
    g=$(gate)   # the install took a while: still nobody using it?
    [[ $g == idle ]] || { STATUS=skipped; REASON="${g#busy: } (after install)"; write_meta; say "$NAME: skipped — $REASON"; return; }
  fi
  local before_reports; before_reports=$(sh_ "ls -1 $REMOTE_REPORTS 2>/dev/null")

  # 3. The user's Continue watching before, then a clean log, the app in front, the sample.
  CLEANUP_NEEDED=1
  sh_ logcat -c
  sh_ am broadcast -n "$PKG/.data.SourcesProbe" --ez history true > /dev/null
  sleep 2
  PRIOR_POS=$(sh_ logcat -d -s ReelProbe:I | grep -m1 "history $SAMPLE_KEY " | sed -E 's/.*history [^ ]+ ([0-9]+)s\/.*/\1/')
  sh_ logcat -b all -c
  sh_ am start -W -n "$HOME_ACT" > /dev/null
  sleep 2
  if [[ $(top_activity) != "$HOME_ACT" ]]; then abort "One Stream didn't come to the front ($(top_activity))"
  else
    shot 00-app
    sh_ am broadcast -n "$PKG/.data.SourcesProbe" --ei playSample $SAMPLE_INDEX > /dev/null
    say "$NAME: playing Tears of Steel, waiting for the first frame (≤ ${TTFF_TIMEOUT}s)…"
    # 4. First frame.
    local i ttff="" top
    for ((i = 0; i < TTFF_TIMEOUT; i += 2)); do
      sleep 2
      ttff=$(stats | grep -m1 'ev=ttff' | sed -E 's/.* t=([0-9]+).*/\1/')
      [[ -n $ttff ]] && break
      top=$(top_activity)
      if (( i >= 6 )) && [[ $top != "$PLAYER" ]]; then
        [[ $top == "$PKG"/* ]] && { NOTES+=("player closed before the first frame"); break; }
        abort "something else came to the front ($top)"; break
      fi
      sh_ logcat -d -b crash | grep -q "Process: $PKG" && { NOTES+=("crashed before the first frame"); break; }
    done
    if [[ -n $ttff ]]; then
      say "$NAME: first frame after $((ttff / 1000)).$(((ttff % 1000) / 100)) s"
      sleep 4; shot 01-playing
      if [[ $NAME == tv ]]; then tv_actions; else phone_actions; fi
    elif [[ $STATUS == ran ]]; then
      say "$NAME: no first frame within ${TTFF_TIMEOUT}s"; shot 01-no-first-frame
    fi
  fi

  # 7. Clean up: stop playback, home screen, Continue watching as before. Only press keys while our
  #    app is the one on top (an aborted round leaves the device to whoever picked it up).
  if [[ $STATUS == ran ]]; then leave_player
  elif sh_ dumpsys activity activities | grep -q "$PLAYER"; then
    # Someone took over but our sample still plays behind them (picture-in-picture or background):
    # stop our own app instead of pressing anything on their screen.
    sh_ am force-stop $PKG
    NOTES+=("sample player was still open behind the other app: One Stream force-stopped")
  fi
  sleep 3                                                          # stopStream writes the ev=end line
  # 6. Collect.
  stats > "$DIR/reelstats.txt"
  {
    sh_ logcat -d -b crash | grep -E "Process: $PKG([,: ]|$)|>>> $PKG[: <]"
    sh_ logcat -d -b system,events | grep -E "ANR in $PKG([ :(]|$)|am_anr.*$PKG"
  } > "$DIR/crash.txt"
  sh_ logcat -d -v threadtime -s ReelStats:I ReelProbe:I AndroidRuntime:E PlayerActivity:I > "$DIR/logcat.txt"
  restore_history
  CLEANUP_NEEDED=0
  local after_reports r; after_reports=$(sh_ "ls -1 $REMOTE_REPORTS 2>/dev/null")
  for r in $after_reports; do grep -qx "$r" <<< "$before_reports" || NEW_REPORTS+=("$r"); done
  write_meta
  say "$NAME: done in $(($(date +%s) - START)) s (${#ACTIONS[@]} actions, ${#NEW_REPORTS[@]} new report(s))"
}

[[ -n $TV_SERIAL && ( -z $only || $only == tv ) ]] && run_device "$TV_SERIAL" tv
[[ -n $PHONE_SERIAL && ( -z $only || $only == phone ) ]] && run_device "$PHONE_SERIAL" phone

# Problem reports saved during the round → reports/<model>/ (decoded stacks), read them first.
if cat "$ROUND"/*/meta.json | jq -se "map(.newReports // [] | length) | add > 0" > /dev/null 2>&1; then
  say "pulling the problem reports saved during the round…"
  tool/pull_reports.sh || true
fi

# 8. Compare with the previous round, write summary.json, update latest.json.
cli report "$ROUND" rounds/latest.json
rc=$?
echo
say "round took $(($(date +%s) - t0)) s; screenshots + logs in $ROUND/"
exit $rc
