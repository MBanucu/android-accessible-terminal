#!/usr/bin/env bash
# End-to-end checks for the sample apps, driven the way a screen reader drives
# an app: an AccessibilityService performs the actions (ACTION_SET_TEXT, and the
# tree it dumps), and the assertions are the events and rows it is given. Nothing
# here taps a coordinate or types with `input text`.
#
#   bash test/local-build.sh --samples    # builds both APKs, then runs this suite
#   bash samples/e2e.sh                   # the fast checks (APKs already installed)
#   bash samples/e2e.sh --only tui-echo   # just one check
#   bash samples/e2e.sh --only pty,tui-bg # several checks, one install
#   bash samples/e2e.sh --list            # the check names
#   bash samples/e2e.sh --quiet           # failures and the summary only
#   bash samples/e2e.sh --no-record       # the fast path, no screen recording
#   STREAM=1 bash samples/e2e.sh          # pixel checks read the screen stream
#   VIDEO=1 STREAM=1 bash samples/e2e.sh  # and the run is recorded from its frames
#   bash samples/e2e.sh --restore         # put back what a dead run left changed
#
# The run is recorded unless --no-record is given: one video for the whole suite
# in samples/out/capture/, an event per check beside it, and no screenshot taken
# during the run at all. `bash test/harness.sh capture show` lists the moments
# and `frames` extracts one when asked; a failed check's frames are extracted
# at the end, into samples/out/frames/.
#
# The probe service is enabled for the run, and the accessibility settings are
# put back the way they were found - even when a check fails or the run is
# interrupted. The display's size is never changed: a window is resized through
# the app itself.
set -u

ADB=${ADB:-adb}
APP=io.github.mbanucu.samples.nativeterminal
PROBE=io.github.mbanucu.samples.a11yprobe
# The stream is its own repository and its own package; the probe only depends on
# it, so the component adb starts is named after the module, not after the app.
STREAM_ACTIVITY=io.github.mbanucu.screenstream.ScreenStreamActivity
APK=samples/out/native-terminal.apk
PROBE_APK=samples/out/a11y-probe.apk
FILES=/sdcard/Android/data/$APP/files
PROBE_FILES=/sdcard/Android/data/$PROBE/files
RESTORE_ONLY=0
LIST_ONLY=0
QUIET=0
ONLY=""
# The run is recorded from the first check to the last unless --no-record is
# given: one video, plus an event per check, and no screenshot taken during the
# run at all. `capture.py` maps an event to the frame that was on screen then
# and extracts it when somebody asks for it.
RECORD=1
# STREAM=1 (or --stream) starts the screenstream module's source once and lets
# every pixel check read it: the checks then see every changed frame instead of
# three screenshots a second. Off by default, because it needs the screen-capture
# permission, which the screenshots path does not.
STREAM=${STREAM:-0}
# VIDEO=1 (or --video, which also turns the stream on) records the run from the
# frames the stream carried, instead of screenrecord's own capture: the frames a
# pixel check is shown are then the frames of the file, by number. It needs the
# same permission the stream does, so it is off by default as well.
VIDEO=${VIDEO:-0}
VIDEO_PATH=""
FAILED_CHECKS=""
case "${1:-}" in
    --restore) RESTORE_ONLY=1 ;;
    --list) LIST_ONLY=1 ;;
    --quiet) QUIET=1 ;;
    --only) ONLY=${2:-} ;;
    --record) RECORD=1 ;;
    --no-record) RECORD=0 ;;
    --stream) STREAM=1 ;;
    --no-stream) STREAM=0 ;;
    --video) VIDEO=1; STREAM=1 ;;
    --no-video) VIDEO=0 ;;
esac
# A comma is how a shell user usually writes a list; spaces are how the loop reads it.
ONLY=${ONLY//,/ }
START=$SECONDS
# The wall clock the run began by, for the estimate: spent time includes the
# install and the device setup, not only the checks written down so far.
START_EPOCH=$(date +%s)
CHECKS_RUN=0
# In the order the `check` calls near the end of this file run them, because
# test/timings.sh takes this list as the run's order and names the check in
# progress from it; a name that is out of place makes the estimate claim the run
# is somewhere it is not.
CHECKS="cursor cursor-escapes colour tui tui-echo tui-caret tui-blink tui-bg tui-symbols tui-modes pty pty-flags pty-raw tty-tabs tty-scroll tty-edit tty-typing tui-fast-typing tui-fast-typing-load resize rows session session-resize"
FAIL=0
FAIL_COUNT=0

say() { printf '%s\n' "$*"; }
ok() { [ "$QUIET" = 1 ] || say "ok   $*"; }
bad() { say "FAIL $*"; FAIL=1; FAIL_COUNT=$((FAIL_COUNT + 1)); }

device() { $ADB "$@"; }

# --- the recording ------------------------------------------------------------
# One video for the run and an event beside every moment worth seeing again. An
# event costs no adb call: it is written on this side, and `capture.py` pairs
# this machine's clock with the device's at the start and at the end, so a moment
# becomes the frame that was on screen at that moment. No screenshot is taken
# while the checks run; `bash test/harness.sh capture frames` takes one out of
# the recording when somebody asks for it.
capture_py() { bash "$here/../test/harness.sh" capture "$@" --dir "$CAPTURE_DIR"; }
mark() { [ "$RECORD" = 1 ] || return 0; capture_py event "$@" >/dev/null 2>&1 || true; }

start_recording() {
    [ "$RECORD" = 1 ] || return 0
    if ! command -v ffmpeg >/dev/null; then
        say "recording: skipped, there is no ffmpeg to read it with later"
        RECORD=0
        return 0
    fi
    rm -rf "$CAPTURE_DIR"
    # CAPTURE_SIZE/CAPTURE_BIT_RATE are for a run that would rather be quick than
    # sharp: the frames are for looking at, while the checks watch the pixels
    # themselves, on the device.
    if capture_py start ${CAPTURE_SIZE:+--size "$CAPTURE_SIZE"} \
            ${CAPTURE_BIT_RATE:+--bit-rate "$CAPTURE_BIT_RATE"} >/dev/null 2>&1; then
        say "recording: $CAPTURE_DIR"
    else
        say "recording: could not start; the checks run without it"
        RECORD=0
    fi
}

# The last thing the run does. A failure is exactly when a frame is worth
# pulling out, so those are extracted here and nothing else is: what a screenshot
# is for is the check that did not pass.
stop_recording() {
    [ "$RECORD" = 1 ] || return 0
    # Nothing was started: no device, or a run that died before the install.
    [ -f "$CAPTURE_DIR/capture.json" ] || return 0
    local summary name
    if summary=$(capture_py stop 2>&1); then
        printf '%s\n' "$summary" | sed 's/^/  /'
    else
        say "recording: ${summary:-stop failed}"
        RECORD=0
        return 0
    fi
    for name in $FAILED_CHECKS; do
        capture_py frames --event "check:$name:failed" --out "$here/out/frames" \
            >/dev/null 2>&1 || true
    done
    [ -n "$FAILED_CHECKS" ] \
        && say "  frames of the failures: $here/out/frames" \
        && say "  every moment: bash test/harness.sh capture show --dir $CAPTURE_DIR"
    RECORD=0
}

# A dropped wireless session is not a failing check: ask mDNS for the current
# port before deciding the suite cannot run. The watcher (`bash test/harness.sh
# watch`) normally keeps this from being needed, but a suite started by hand
# should not require it.
ensure_device() {
    device get-state >/dev/null 2>&1 && return 0
    local connector
    connector=$(cd "$here/.." && pwd)/test/harness.sh
    [ -f "$connector" ] || return 1
    say "no device; asking mDNS for the wireless-debugging port"
    bash "$connector" connect 2>&1 | sed 's/^/  /'
    device get-state >/dev/null 2>&1
}

# --- device state -------------------------------------------------------------
# The suite changes one setting that belongs to the phone's owner: the
# accessibility service it drives, and switching it off turns a screen reader off
# for good when a run dies before its cleanup. Saving the value first is what
# makes "put it back" true.
#
# The saved values go to disk, not only into this shell, because a run killed
# outright never reaches its trap. The file is what lets the next run - or
# `--restore` - put it back, and it is only deleted once the phone is seen to be
# back. The display size is saved only so a state file written by an older run
# (which resized the display) is still repaired; this suite never changes it.

here=$(cd "$(dirname "$0")" && pwd)
CAPTURE_DIR=$here/out/capture
VIDEO_OUT=$here/out/video.mp4
# Every frame a check was shown, as "frame ts", written by watch_answer. A file
# rather than a variable because the answers are read inside command
# substitutions, and a subshell's assignment would be lost.
WATCHED=$here/out/watched.txt
STATE_FILE=$here/out/device-state.txt
RUN_STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TIMING_LOG=$here/out/e2e-timings.log
: > "$TIMING_LOG"
SAVED_SIZE=""
SAVED_SERVICES=""
SAVED_ACCESSIBILITY=""
STATE_SAVED=0

# The size in `wm size`'s "Override size:" line; empty when the display has none.
read_size_override() {
    device shell wm size 2>/dev/null | tr -d '\r' | sed -n 's/^Override size: *//p'
}

read_secure_setting() {
    device shell settings get secure "$1" 2>/dev/null | tr -d '\r'
}

# The three saved values, one per line; empty is a value too, so the order counts.
load_device_state() {
    [ -f "$STATE_FILE" ] || return 1
    SAVED_SIZE=$(sed -n '1p' "$STATE_FILE")
    SAVED_SERVICES=$(sed -n '2p' "$STATE_FILE")
    SAVED_ACCESSIBILITY=$(sed -n '3p' "$STATE_FILE")
    STATE_SAVED=1
}

persist_device_state() {
    mkdir -p "$(dirname "$STATE_FILE")" || return 1
    printf '%s\n%s\n%s\n' "$SAVED_SIZE" "$SAVED_SERVICES" "$SAVED_ACCESSIBILITY" \
        > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

save_device_state() {
    [ "$STATE_SAVED" = 1 ] && return 0
    # Nothing to save on an unreachable device, and nothing to change on it either.
    device get-state >/dev/null 2>&1 || return 0
    SAVED_SIZE=$(read_size_override)
    SAVED_SERVICES=$(read_secure_setting enabled_accessibility_services)
    SAVED_ACCESSIBILITY=$(read_secure_setting accessibility_enabled)
    STATE_SAVED=1
    persist_device_state || say "WARNING: could not write $STATE_FILE;" \
        "a run that is killed will not be able to put the device back"
}

# A secure setting back to what it was; "null" and empty are Android's "unset".
put_secure_setting() {
    case "${2:-}" in
        ''|null) device shell settings delete secure "$1" >/dev/null 2>&1 ;;
        *) device shell settings put secure "$1" "$2" >/dev/null 2>&1 ;;
    esac
}

# The display as the run found it: its override if it had one, else no override.
restore_display() {
    if [ -n "$SAVED_SIZE" ]; then
        device shell wm size "$SAVED_SIZE" >/dev/null 2>&1
    else
        device shell wm size reset >/dev/null 2>&1
    fi
}

setting_matches() { # key saved-value
    local current
    current=$(read_secure_setting "$1")
    [ "${current:-null}" = "${2:-null}" ]
}

state_is_restored() {
    [ "$(read_size_override)" = "$SAVED_SIZE" ] || return 1
    setting_matches enabled_accessibility_services "$SAVED_SERVICES" || return 1
    setting_matches accessibility_enabled "$SAVED_ACCESSIBILITY"
}

# Puts the saved values back, and only forgets them once the phone agrees they
# are back. Retried: a connection that dropped during a resize comes back.
put_device_back() {
    local attempt
    for attempt in 1 2 3 4 5; do
        restore_display
        put_secure_setting enabled_accessibility_services "$SAVED_SERVICES"
        put_secure_setting accessibility_enabled "$SAVED_ACCESSIBILITY"
        if state_is_restored; then
            STATE_SAVED=0
            rm -f "$STATE_FILE"
            return 0
        fi
        sleep 1
    done
    return 1
}

# What an earlier run saved and did not get to put back, done before anything
# else, so a rerun repairs the phone instead of photographing the damage.
recover_previous_run() {
    load_device_state || return 0
    say "a previous run left the device changed; putting it back first"
    if put_device_back; then
        say "restored"
        return 0
    fi
    say "could not restore it; the saved state is in $STATE_FILE"
    return 1
}

# `--restore`: put back what a dead run saved, or, with nothing saved, undo the
# change this suite makes to a phone it knows nothing about.
restore_only() {
    # A run that died before its trap may have left the recorder holding the
    # display, which would make the next recording start with nothing to show.
    device shell pkill -INT screenrecord >/dev/null 2>&1 || true
    if ! device get-state >/dev/null 2>&1; then
        say "no device reachable (adb get-state failed)"
        return 1
    fi
    if load_device_state; then
        if put_device_back; then
            say "display and accessibility settings restored"
            return 0
        fi
        say "could not restore the saved state; it is still in $STATE_FILE"
        return 1
    fi
    say "no saved state; removing this suite's accessibility probe"
    local services remaining
    services=$(read_secure_setting enabled_accessibility_services)
    case "$services" in
        ''|null) ;;
        *"$PROBE/.ProbeService"*)
            remaining=$(printf '%s' "$services" | tr ':' '\n' \
                | grep -vxF "$PROBE/.ProbeService" | tr '\n' ' ')
            remaining=${remaining% }
            remaining=${remaining// /:}
            if [ -n "$remaining" ]; then
                device shell settings put secure enabled_accessibility_services \
                    "$remaining" >/dev/null 2>&1
            else
                device shell settings delete secure enabled_accessibility_services \
                    >/dev/null 2>&1
                device shell settings put secure accessibility_enabled 0 >/dev/null 2>&1
            fi
            ;;
    esac
    return 0
}

# Enables the probe without switching off a reader the owner already had on.
enable_probe() {
    local services=$SAVED_SERVICES
    case "$services" in
        ''|null) services="$PROBE/.ProbeService" ;;
        *"$PROBE/.ProbeService"*) ;;
        *) services="$services:$PROBE/.ProbeService" ;;
    esac
    device shell settings put secure enabled_accessibility_services "$services" >/dev/null
    device shell settings put secure accessibility_enabled 1 >/dev/null
}

# One command in the app's own external files, or the probe's.
evidence() { device shell "cat $FILES/evidence.txt" 2>/dev/null; }
probe_file() { device shell "cat $PROBE_FILES/$1.txt" 2>/dev/null; }

# --- pixels, watched on the device --------------------------------------------
# A pixel check used to pull a screenshot over adb and count it on this side,
# which made the screen a picture taken at some moment after the fact. The probe
# takes the screenshot itself now: the frame, its number and the device's time
# for it never leave the phone. Only frames where the screen changed are
# numbered, and a check passes on the first one that holds what it asked for.
# With STREAM=1 the frames come from the probe's stream instead - every changed
# frame at the rate the screen changes, not a screenshot every 333 ms - and the
# numbers and times a check reports are the source's own.
watch_pixels() { # name extras...
    local name=$1
    shift
    device shell "rm -f '$PROBE_FILES/$name.txt'; am broadcast -p $PROBE \
        -a $PROBE.COMMAND --es pkg $APP --es pixels '$name' $*" >/dev/null 2>&1
}

# The watch's answer, once it has one: polled on the device like `await`, so a
# hit costs one round trip. A pass is marked with the device time of its frame,
# which is the clock the recording's frames carry, so `capture.py frames
# --event pixel:NAME:pass` is that exact frame out of the video.
watch_answer() { # name seconds
    local name=$1 seconds=${2:-20} answer
    answer=$(timeout "$((seconds + 15))" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            grep -aq '^result=' '$PROBE_FILES/$name.txt' 2>/dev/null && break
            sleep 0.1
            i=\$((i + 1))
        done
        cat '$PROBE_FILES/$name.txt' 2>/dev/null")
    if printf '%s\n' "$answer" | grep -aq '^result=pass'; then
        mark "pixel:$name:pass" \
            --data "device_ns=$(watched "$answer" ts_ns)" \
            --data "frame=$(watched "$answer" frame)"
    fi
    # The frame and its time, for --video: the recording has to reach the last
    # frame a check passed on, or a check passed on a picture the file lacks.
    local shown ts
    shown=$(watched "$answer" frame)
    ts=$(watched "$answer" ts_ns)
    case "$shown$ts" in
        ''|*[!0-9]*) ;;
        *) printf '%s %s\n' "$shown" "$ts" >> "$WATCHED" ;;
    esac
    printf '%s\n' "$answer"
}

# One field of a watch's answer: a count, a cell mean, the frame or its time.
watched() { printf '%s\n' "$1" | sed -n "s/^$2=//p" | tail -1; }

# A watch's answer as one line, for the failure message.
watch_said() { printf '%s\n' "$1" | tr '\n' ' ' | sed 's/  */ /g; s/ *$//'; }

# --- the stream, when the run asks for its frames ------------------------------
# STREAM=1 starts the screenstream module's raw source once and every pixel check
# subscribes to it, so a check sees every changed frame instead of three
# screenshots a second. The source is a separate repository the probe depends on,
# which is why its state file and its activity are named after it rather than
# after the probe. Consent comes from the app-op, which starts the capture with no
# dialog; a device that refuses that route falls back to the probe clicking the
# dialog itself, which is why this runs after the service is enabled. It runs at
# native size because a check names its region in the display's own coordinates.
start_stream() {
    [ "$STREAM" = 1 ] || return 0
    device shell "appops set $PROBE PROJECT_MEDIA allow" >/dev/null 2>&1 || true
    local record_extra=""
    [ "$VIDEO" = 1 ] && record_extra="--ez record true"
    device shell "rm -f '$PROBE_FILES/screenstream.txt'; \
        am start -n $PROBE/$STREAM_ACTIVITY $record_extra" >/dev/null 2>&1
    local i=0 state
    while [ "$i" -lt 150 ]; do
        state=$(probe_file screenstream | sed -n 's/^state=//p' | head -1)
        case "$state" in
            streaming) return 0 ;;
            failed) return 1 ;;
        esac
        sleep 0.1
        i=$((i + 1))
    done
    return 1
}

# The app-op is a change this run made to the phone, so it goes back with the rest.
# The capture is stopped through the activity, not by force-stopping the probe:
# the accessibility service stays bound, so the next run does not pay for a rebind.
# It is checked, though: a capture that survives the stop keeps the port, and the
# next run (or any other app that wants 7100) then fails to bind with nothing but
# "address already in use" to go on. Force-stopping is the fallback, not the way.
stop_stream() {
    [ "$STREAM" = 1 ] || return 0
    device shell "am start -n $PROBE/$STREAM_ACTIVITY --ez stop true \
        >/dev/null 2>&1; appops set $PROBE PROJECT_MEDIA default" >/dev/null 2>&1
    local i=0 state
    while [ "$i" -lt 30 ]; do
        state=$(probe_file screenstream | sed -n 's/^state=//p' | head -1)
        [ "$state" = stopped ] && return 0
        sleep 0.1
        i=$((i + 1))
    done
    say "WARNING: the screen stream did not stop; force-stopping the probe"
    device shell "am force-stop $PROBE" >/dev/null 2>&1
}

# --video records the run from the stream's own frames. A projection allows one
# virtual display, so the recording cannot photograph the display a second time:
# it encodes the frames the stream has already copied. The file is not numbered
# like the stream - it starts wherever the capture was, and drops frames the
# encoder was busy for - but it carries the stream's times, so a check's ts_ns
# minus recording_from_ns is where its picture is.
start_video() {
    [ "$VIDEO" = 1 ] || return 0
    : > "$WATCHED"
    local i=0 path
    while [ "$i" -lt 80 ]; do
        path=$(probe_file screenstream | sed -n 's/^recording=//p' | head -1)
        case "$path" in
            ''|-) ;;
            *)
                VIDEO_PATH=$path
                say "video: recording the run to $VIDEO_PATH"
                return 0
                ;;
        esac
        sleep 0.1
        i=$((i + 1))
    done
    bad "video: the stream never started recording; this run has no video"
    VIDEO=0
}

# Ends the recording with the capture - the stream's stop does both - and checks
# the file against what the phone said it wrote and what the checks were shown:
# a video that is shorter than the frame a check passed on is the one failure
# this mode exists to catch. The cleanup's stop_stream then has nothing to stop.
stop_video() {
    [ "$VIDEO" = 1 ] || return 0
    [ -n "$VIDEO_PATH" ] || return 0
    device shell "am start -n $PROBE/$STREAM_ACTIVITY --ez stop true" >/dev/null 2>&1
    local i=0 state
    while [ "$i" -lt 50 ]; do
        state=$(probe_file screenstream | sed -n 's/^state=//p' | head -1)
        [ "$state" = stopped ] && break
        sleep 0.1
        i=$((i + 1))
    done
    # The encoder writes the file's index after its last frame, so the size goes
    # still a moment after the state does.
    local last="" size stable=0 tries=0
    while [ "$tries" -lt 50 ]; do
        size=$(device shell "wc -c < '$VIDEO_PATH'" 2>/dev/null | tr -d ' \r')
        [ "${size:-0}" = "$last" ] && stable=$((stable + 1)) || stable=0
        [ "$stable" -ge 3 ] && break
        last=${size:-0}
        sleep 0.1
        tries=$((tries + 1))
    done
    # The status the encoder writes when it has finished follows the state, so the
    # numbers are waited for instead of read once.
    local status frames ms from last_ts at seen width height
    i=0
    while [ "$i" -lt 50 ]; do
        status=$(probe_file screenstream)
        frames=$(printf '%s\n' "$status" | sed -n 's/^recorded_frames=//p' | head -1)
        [ "${frames:-0}" -gt 0 ] && break
        sleep 0.1
        i=$((i + 1))
    done
    mkdir -p "$(dirname "$VIDEO_OUT")"
    if ! $ADB pull "$VIDEO_PATH" "$VIDEO_OUT" >/dev/null 2>&1; then
        bad "video: could not pull $VIDEO_PATH off the phone"
        return 1
    fi
    VIDEO=0
    ms=$(printf '%s\n' "$status" | sed -n 's/^recorded_ms=//p' | head -1)
    from=$(printf '%s\n' "$status" | sed -n 's/^recording_from_ns=//p' | head -1)
    last_ts=$(awk '$2 + 0 > m { m = $2 + 0 } END { print m + 0 }' "$WATCHED" 2>/dev/null)
    at=$([ "${from:-0}" -gt 0 ] && [ "${last_ts:-0}" -gt "$from" ] \
        && echo $(( (last_ts - from) / 1000000 )) || echo "")
    if ! command -v ffprobe >/dev/null; then
        say "video: $VIDEO_OUT, ${frames:-0} frames, ${ms:-0} ms (no ffprobe to check it)"
        return 0
    fi
    seen=$(ffprobe -v error -count_frames -select_streams v:0 \
        -show_entries stream=nb_read_frames,width,height -of default=nw=1 "$VIDEO_OUT" \
        2>/dev/null | sed -n 's/^nb_read_frames=//p')
    width=$(ffprobe -v error -select_streams v:0 -show_entries stream=width \
        -of default=nw=1 "$VIDEO_OUT" 2>/dev/null | sed -n 's/^width=//p')
    height=$(ffprobe -v error -select_streams v:0 -show_entries stream=height \
        -of default=nw=1 "$VIDEO_OUT" 2>/dev/null | sed -n 's/^height=//p')
    if [ "${seen:-0}" -le 0 ]; then
        bad "video: $VIDEO_OUT is not a readable MP4"
        return 1
    fi
    [ "${frames:-0}" = "$seen" ] \
        || bad "video: the phone wrote ${frames:-0} frames, the file has $seen"
    if [ -n "$at" ]; then
        [ "$at" -le "${ms:-0}" ] \
            || bad "video: a check was shown a frame ${at} ms in, and the file is ${ms:-0} ms"
    fi
    say "video: $seen frames, ${width}x${height}, \
$(awk "BEGIN { printf \"%.1f\", ${ms:-0} / 1000 }")s${at:+ (the last pass at ${at} ms inside it)}, $VIDEO_OUT"
}

# The probe walks the target window and writes the summary itself; the suite only
# reads values such as rows= and prompt.actions=.
inspect() {
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --es inspect '$1' >/dev/null 2>&1; cat $PROBE_FILES/$1.txt" 2>/dev/null
}

# Waits for the probe to see at least `minimum` row nodes. The view publishes its
# rows as the program draws them, so a dump taken the moment the launch evidence
# line appears can arrive before the first screen does; the row nodes are what
# this check is about, so they are what it waits for.
await_rows() {
    local minimum=${1:-5} seconds=${2:-10} i=0 summary rows
    while [ "$i" -lt $((seconds * 5)) ]; do
        summary=$(inspect e2e-rows)
        rows=$(printf '%s\n' "$summary" | sed -n 's/^rows=//p')
        [ "${rows:-0}" -ge "$minimum" ] && return 0
        sleep 0.2
        i=$((i + 1))
    done
    return 1
}

# Waits for an accessibility event without polling: the probe blocks on a new
# event off its main thread, and this streams the answer file until the match.
# $1 needle, $2 seconds, $3 optional event type (WINDOW_STATE, ANNOUNCEMENT...).
wait_event() {
    local needle=$1 seconds=${2:-15} type=${3:-}
    timeout "$seconds" "$ADB" shell ": > '$PROBE_FILES/wait.txt'; \
        am broadcast -p $PROBE -a $PROBE.COMMAND --es wait '$needle' \
            --el waitMs $((seconds * 1000))${type:+ --es event $type} >/dev/null 2>&1; \
        tail -n +1 -f '$PROBE_FILES/wait.txt' | grep -q -m1 '^matched'"
}

# Sets text and waits for the accessibility event the action causes, all in one
# adb shell: the wait is armed first, then the action runs, then the answer file
# is streamed until it matches. The probe does the matching, off its main thread.
type_wait() { # text needle seconds
    local text=$1 needle=$2 seconds=${3:-12}
    timeout "$seconds" "$ADB" shell ": > '$PROBE_FILES/wait.txt'; \
        am broadcast -p $PROBE -a $PROBE.COMMAND --es wait '$needle' \
            --el waitMs $((seconds * 1000)) >/dev/null 2>&1; \
        am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
            --es text '$text\\n' >/dev/null 2>&1; \
        tail -n +1 -f '$PROBE_FILES/wait.txt' | grep -q -m1 '^matched'"
}

# The service writes status.txt when it binds; nothing it performs works before
# that, and a reinstall or a settings change both unbind it.
await_probe() {
    local tries=${1:-45}
    # grep runs on the device: when it matches, the shell pipeline ends and the
    # adb call returns, instead of staying open until the host timeout.
    timeout "$tries" "$ADB" shell "touch '$PROBE_FILES/status.txt'; \
        tail -n +1 -F '$PROBE_FILES/status.txt' | grep -q -m1 connected"
}

# Types one line into the app's own window the way a service does, and waits for
# the answer. Retried: a window can take a moment to appear in the service's
# window list, and the probe refuses to type anywhere else.
#
# `type_in_app` wants the answer as the *start* of an evidence line, which is
# right for a shell prompt and wrong for a box drawn in the middle of the
# screen; `type_until` takes any pattern in the evidence.
type_until() {
    local text=$1 pattern=$2 attempt=0
    while [ "$attempt" -lt 5 ]; do
        attempt=$((attempt + 1))
        device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
            --es text '$text\\n'" >/dev/null 2>&1
        if await "$pattern" 6; then
            return 0
        fi
    done
    return 1
}

type_in_app() {
    local text=$1 answer=$2 attempt=0
    while [ "$attempt" -lt 5 ]; do
        attempt=$((attempt + 1))
        # One backslash before the n: two make the shell see `echo x\` and
        # treat the line as a continuation instead of running it.
        device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
            --es text '$text\\n'" >/dev/null 2>&1
        if await_line "$answer" 6; then
            return 0
        fi
    done
    return 1
}

# Bound is not able to see: right after the accessibility settings are written
# the connection can be up with no windows in it yet, and every action then has
# nothing to act on. The service's own window count is the witness.
#
# The count comes from `inspect`, not `dump`: both report `windows=`, but a dump
# walks every window's tree and writes it out, which on this device cost 32 s of
# the run's 161 s at the top of every suite. The summary is 140 ms.
await_windows() {
    local tries=${1:-12} i=0 summary
    while [ "$i" -lt "$tries" ]; do
        summary=$(inspect e2e-ready)
        if printf '%s\n' "$summary" | grep -aqE '^windows=[1-9]'; then
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}

# Waits for a line in the evidence file; most of these checks are "the app will
# say this within N seconds, or it will not".
#
# The device polls its own file every 100 ms and exits on the match, so a hit
# costs one adb round trip instead of a `cat` per second - the same trade the
# instrumented tests make by reading the file the app writes rather than running
# `logcat -d` on a timer. The host `timeout` is a backstop for a shell that never
# runs the loop at all.
await() {
    local pattern=$1 seconds=${2:-15}
    timeout "$seconds" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            grep -aqF -- '$pattern' '$FILES/evidence.txt' 2>/dev/null && exit 0
            sleep 0.1
            i=\$((i + 1))
        done
        exit 1" >/dev/null 2>&1
}

expect() {
    if evidence | grep -aqF -- "$1"; then ok "$2"; else bad "$2 (no '$1' in evidence)"; fi
}

# Shortens the app's own terminal view, the way the keyboard does. The display's
# size belongs to the phone's owner: `wm size` made the same window change, but a
# run that died before its trap left the screen at that size.
resize_window() {
    local fraction=${1:-0.3}
    device shell "am broadcast -p $APP -a $APP.action.RESIZE \
        --ef resize_fraction $fraction" >/dev/null 2>&1
}

# The newest grid the view reported, as CxR.
last_grid() { evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://'; }

# How many bytes the evidence has now: a wait that must be answered only by what
# is written next passes this to grid_changed.
evidence_bytes() {
    device shell "wc -c < '$FILES/evidence.txt' 2>/dev/null" | tr -d ' \r'
}

# Waits until a grid line written after `mark` is something other than `before`:
# a window change is a layout, so this is also the wait for the app to have
# noticed it. The mark is taken before the action, because a broadcast is fast
# enough that the new grid can already be in the file by the time a wait starts.
grid_changed() {
    local before=$1 mark=${2:-0} seconds=${3:-12}
    case $mark in ''|*[!0-9]*) mark=0 ;; esac
    timeout "$seconds" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            tail -c +$((mark + 1)) '$FILES/evidence.txt' 2>/dev/null \
                | grep -a -o '### grid:[0-9]*x[0-9]*' \
                | grep -a -q -m1 -v -F -- '### grid:$before' && exit 0
            sleep 0.1
            i=\$((i + 1))
        done
        exit 1" >/dev/null 2>&1
}

# The grid once two reads two seconds apart agree. A window resize with the
# keyboard opening gives the view more than one size before it stops, and the
# pty follows the last of them: reading the first is how a check comes to expect
# a size the program never ends up with.
settled_grid() {
    local first second tries=${1:-20} i=0
    first=$(last_grid)
    while [ "$i" -lt "$tries" ]; do
        # The grid change was already witnessed before this is called, so the
        # second read follows the first closely; 0.5 s only found the view having
        # stopped one read later anyway, while a resize can still be landing.
        sleep 0.25
        second=$(last_grid)
        if [ "$second" = "$first" ]; then
            printf '%s' "$second"
            return 0
        fi
        first=$second
        i=$((i + 1))
    done
    printf '%s' "$first"
    return 1
}

# A line the evidence has that *starts* with the pattern: output, rather than the
# shell echoing the command back (which contains the same text). Polled on the
# device for the same reason `await` is.
await_line() {
    local pattern=$1 seconds=${2:-15}
    timeout "$seconds" "$ADB" shell "i=0
        while [ \$i -lt $((seconds * 10)) ]; do
            grep -aqE -- '^$pattern' '$FILES/evidence.txt' 2>/dev/null && exit 0
            sleep 0.1
            i=\$((i + 1))
        done
        exit 1" >/dev/null 2>&1
}

# Every screen section in the evidence, without the raw pty transcript - that
# one is full of the escape bytes the view consumed, on purpose.
all_screens() {
    evidence | awk '/^--- screen ---/{f=1;next} /^--- transcript ---/{f=0} f'
}

# The newest screen section, for checks about one drawn frame rather than every
# frame so far. The transcript sections are skipped, not collected.
last_screen() {
    evidence | awk '
        /^--- screen ---/ { inside = 1; block = ""; next }
        /^--- transcript ---/ { if (inside) { last = block } ; inside = 0; next }
        inside { block = block $0 "\n" }
        END { printf "%s", last }'
}

# The screen the view had at one step of the cursor demo.
screen_at() {
    evidence | sed -n "/^### cursor:$1 /,/^--- transcript ---/p" \
        | sed -n '/^--- screen ---/,/^--- transcript ---/p' | sed '1d;$d'
}

has_line() {
    printf '%s\n' "$1" | grep -qxF -- "$2"
}

# Installs only when the on-device APK differs, so an unchanged probe keeps its
# accessibility binding instead of paying the rebind time on every run.
install_if_changed() { # apk package label
    local apk=$1 package=$2 label=$3 remote hash local_hash
    remote=$(device shell pm path "$package" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')
    if [ -n "$remote" ]; then
        hash=$(device shell md5sum "$remote" 2>/dev/null | awk '{print $1}' | tr -d '\r')
    fi
    local_hash=$(md5sum "$apk" 2>/dev/null | awk '{print $1}')
    if [ -z "$hash" ] || [ "$hash" != "$local_hash" ]; then
        device install -r "$apk" >/dev/null 2>&1 || {
            bad "$label did not install; build it first"
            return 1
        }
    fi
}

install_everything() {
    save_device_state
    install_if_changed "$APK" "$APP" "the native-terminal APK" || exit 1
    install_if_changed "$PROBE_APK" "$PROBE" "the a11y-probe APK" || exit 1
    # Reinstalling the probe kills its service, and the settings below only ask
    # for it back: the actions go nowhere until it has bound. Old logs would also
    # let a later check match a line from a previous run.
    device shell rm -f "$PROBE_FILES/actions.txt" "$PROBE_FILES/wait.txt" >/dev/null 2>&1
    # Reinstalling the APK already replaces the process; force-stopping an
    # accessibility service here makes the manager take tens of seconds to bind
    # it again. A fresh install gets the fresh window connection instead.
    device logcat -c >/dev/null 2>&1
    # Appended, not substituted: a reader the owner had on keeps working while
    # the probe drives the app, and the saved value puts the list back untouched.
    enable_probe
    if ! await_windows 30; then
        bad "the probe service never exposed a window"
    fi
}

# A clean app and a granted Termux permission. pm clear revokes runtime
# permissions, so the grant has to come after it, not before. One adb round trip
# for all four: this runs once per check, and four calls cost 379 ms here against
# 163 ms for the four statements in one call.
launch() {
    local attempt
    for attempt in 1 2; do
        device shell "am force-stop $APP; pm clear $APP >/dev/null; \
            pm grant $APP com.termux.permission.RUN_COMMAND; \
            am start -n $APP/.MainActivity $*" >/dev/null 2>&1
        if await "activity:created" 15; then
            return 0
        fi
    done
    # A launch that quietly did nothing turns every later check into its own
    # mystery; say it once, here, with the log lines that explain it.
    bad "launch: the app did not start after two attempts (adb: $(device get-state 2>/dev/null))"
    device logcat -d -t 400 2>/dev/null \
        | grep -a 'FATAL\|nativeterminal' | tail -6 | sed 's/^/     /'
}

# Asks the app for a snapshot of the screen now. The app writes one every 1.5 s
# on its own, so a check that read `last_screen` after typing had to sleep until
# the next periodic one; a snapshot on demand is one round trip and is the frame
# the typing caused.
request_dump() {
    device shell "am broadcast -p $APP -a $APP.action.DUMP >/dev/null 2>&1" 2>/dev/null
}

fresh_screen() {
    request_dump
    last_screen
}

# The newest screen, once it holds the line. A snapshot asked for before the
# program's first paint is a blank grid, which is not the same answer as a line
# that never appears, so this asks again instead of failing on the first look.
await_screen() {
    local pattern=$1 seconds=${2:-5} i=0 screen
    while [ "$i" -lt $((seconds * 5)) ]; do
        request_dump
        screen=$(last_screen)
        printf '%s\n' "$screen" | grep -qxF -- "$pattern" && return 0
        sleep 0.1
        i=$((i + 1))
    done
    return 1
}

dump() {
    # am broadcast waits for the receiver, so the file is complete when cat runs;
    # one adb round trip instead of broadcast-then-read.
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --es dump '$1' >/dev/null 2>&1; cat $PROBE_FILES/$1.txt" 2>/dev/null
}

# Kills a process and everything under it. A plain kill is not enough for a
# session: `script` puts the shell it runs into a new session, so killing the
# wrapper leaves that shell, its program and the pty helper running.
kill_tree() {
    local kid
    for kid in $(ps -eo pid,ppid 2>/dev/null | awk -v p="$1" '$2 == p { print $1 }'); do
        kill_tree "$kid"
    done
    kill -9 "$1" 2>/dev/null
}

# The Termux side of an inline session outlives the app that started it: a
# force-stop kills this app, not the `bash -lc` wrapper Termux ran for it, and
# that wrapper holds `script`, a shell and the program open forever because
# nothing ever closes the pty. Every check that opens a session leaves five
# such processes behind. Android allows 32 processes per uid for an app like
# Termux and then kills all of them at once - logged as "Trimming phantom
# processes" - which is how a full run ends up killing the terminal that is
# running it. Reap after each check so the count cannot creep.
reap_sessions() {
    local pid
    for pid in $(ps -eo pid,args 2>/dev/null \
            | awk '/native-terminal\/pty/ && !/awk/ { print $1 }'); do
        kill_tree "$pid"
    done
    # A launch that failed before the app connected its control socket leaves a
    # session with nothing to report the hangup to: its pty is not the one in
    # the file, so the wrapper's own teardown names a terminal that no longer
    # exists. What survives is the page program, reparented and still drawing
    # into a pty nobody reads.
    for pid in $(ps -eo pid,args 2>/dev/null \
            | awk '$2 == "python3" && $3 == "-c" && $4 == "import" { print $1 }'); do
        kill_tree "$pid"
    done
}

restore() {
    # An interrupted run is when the video is worth most: stop and pull it here
    # too, before the device is put back.
    stop_recording
    stop_stream
    reap_sessions
    if [ "$STATE_SAVED" = 1 ]; then
        put_device_back || say "WARNING: the device is still changed;" \
            "run 'bash samples/e2e.sh --restore' when it is reachable"
    fi
    device shell am force-stop "$APP" >/dev/null 2>&1
}
trap restore EXIT
# An interrupted run goes through the same cleanup: bash runs EXIT after these.
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

# --- the app-owned pty --------------------------------------------------------

test_pty_is_a_terminal() {
    launch
    if await "pty:master-fd:" 10; then
        ok "pty: the app owns the master descriptor"
    else
        bad "pty: no master descriptor in the evidence"
        return
    fi
    if ! await "stdin-is-tty"; then
        bad "pty: the shell never reported a terminal"
        return
    fi
    expect "/dev/pts/" "pty: the shell is on a pty device"
    expect "stdout-is-tty" "pty: stdout is a terminal"
}

# --- cursor jumps -------------------------------------------------------------

# Every kind of jump a program uses, asserted twice: on the rows the service can
# read, and on the cursor the view drew.
test_cursor_jumps() {
    launch --ez cursor true
    if ! await "cursor:main-again" 15; then
        # A fresh install can leave the first demo without its session.
        launch --ez cursor true
        if ! await "cursor:main-again" 15; then
            bad "cursor: the demo never finished"
            return
        fi
    fi

    local screen
    screen=$(screen_at absolute)
    if has_line "$screen" "CC" && has_line "$screen" "  AA" \
            && has_line "$screen" "      BB"; then
        ok "cursor: CUP placed three markers at their rows and columns"
    else
        bad "cursor: CUP did not place the markers (got: $screen)"
    fi
    expect "cursor:absolute cursor=0,2" "cursor: the cursor followed CUP to row 1, column 3"

    screen=$(screen_at relative)
    has_line "$screen" "CC    DD" \
        && ok "cursor: CUU/CUF moved up and right from the addressed cell" \
        || bad "cursor: the relative move landed elsewhere (got: $screen)"
    expect "cursor:relative cursor=0,8" "cursor: two up and four right left the cursor at column 9"

    screen=$(screen_at column-row)
    has_line "$screen" "         EE" \
        && ok "cursor: VPA/CHA jumped to a row and a column directly" \
        || bad "cursor: the row/column jump landed elsewhere (got: $screen)"
    expect "cursor:column-row cursor=3,11" "cursor: the vertical and column jumps put the cursor at row 4, column 12"

    screen=$(screen_at save-restore)
    if has_line "$screen" "         EEFF" && has_line "$screen" " X"; then
        ok "cursor: CSI s/u restored the cursor and the text went there"
    else
        bad "cursor: the saved cursor was not restored (got: $screen)"
    fi
    expect "cursor:save-restore cursor=3,13" "cursor: after the restore the cursor is where it was saved"

    screen=$(screen_at alternate)
    if has_line "$screen" "    TUI" && ! printf '%s\n' "$screen" | grep -qF "CC"; then
        ok "cursor: the alternate screen hid the main one and the jump applied there"
    else
        bad "cursor: the alternate screen did not hide the main grid (got: $screen)"
    fi
    expect "cursor:alternate cursor=1,7" "cursor: the jump into the alternate screen put the cursor there"

    screen=$(screen_at main-again)
    if has_line "$screen" "CC    DD" && ! printf '%s\n' "$screen" | grep -qF "TUI"; then
        ok "cursor: leaving the alternate screen brought the main grid and cursor back"
    else
        bad "cursor: the main grid did not come back (got: $screen)"
    fi
    expect "cursor:main-again cursor=3,13" "cursor: the cursor came back with the main screen"
}

# --- colours ------------------------------------------------------------------

# The rows prove the SGR was consumed; the pixels prove it was drawn.
test_colour_escapes_render() {
    launch --ez colors true
    if ! await "cursor:colors" 12; then
        bad "colour: the demo never drew"
        return
    fi
    # The evidence is written as the grid changes; the frame carrying the colours
    # is the next one, and RED-TEXT arriving in the file is that frame's write.
    await "RED-TEXT" 5 || true

    local screen
    screen=$(screen_at colors)
    # CUP 3;3 red, CUP 3;20 green: the row has both, nine columns apart, and no
    # escape sequence of its own.
    if printf '%s\n' "$screen" | grep -qE '^  RED-TEXT {9}GREEN-TEXT$'; then
        ok "colour: SGR text landed at its columns and printed no escape"
    else
        bad "colour: the coloured row is not what the SGR asked for (got: $screen)"
    fi
    if printf '%s\n' "$screen" | grep -qE '\[[0-9;]*m'; then
        bad "colour: an escape sequence leaked into the row text"
    else
        ok "colour: no escape sequence leaked into the row text"
    fi
    local marker
    for marker in BRIGHT-BLUE ORANGE-256 plain-after-reset; do
        has_line "$screen" "  $marker" \
            && ok "colour: $marker is on its row" \
            || bad "colour: $marker is missing from the grid (got: $screen)"
    done

    # The pixels are counted on the device now: the probe takes the screen there
    # and answers with the first frame that had all three colours, in the band the
    # old screenshot counted (1440 wide, 900 high, from y=150).
    local answer
    watch_pixels colour --es region "0,150,1440,900" --es colours \
        "red,150,255,0,109,0,109,200:green,0,109,120,255,0,109,200:blue,0,129,0,255,151,255,200"
    answer=$(watch_answer colour 20)
    if printf '%s\n' "$answer" | grep -aq '^result=pass'; then
        ok "colour: the red, green and bright-blue SGRs reached the pixels" \
            "(red $(watched "$answer" count.red), green $(watched "$answer" count.green)," \
            "blue $(watched "$answer" count.blue), on frame $(watched "$answer" frame))"
    else
        bad "colour: the SGRs did not reach the pixels ($(watch_said "$answer"))"
    fi
}

# --- a curses TUI, driven through the accessibility prompt ---------------------

test_tui_renders_and_responds() {
    launch --ez tui true
    if ! await "BOX-TUI" 30; then
        bad "tui: the program never drew its box"
        return
    fi
    local tree rows widths
    tree=$(dump e2e-tui)
    rows=$(printf '%s\n' "$tree" | grep -acE 'android.widget.TextView editable=false')
    [ "$rows" -ge 8 ] \
        && ok "tui: the box is $rows row nodes, not one blob of text" \
        || bad "tui: only $rows row nodes in the tree"
    widths=$(all_screens | grep -E '^ [|+]' | awk '{print length($0)}' | sort -u | tr '\n' ' ')
    [ "$widths" = "41 " ] \
        && ok "tui: every box row is 40 columns wide" \
        || bad "tui: the box rows are not aligned (widths: $widths)"
    if printf '%s\n' "$tree" | grep -aqF '[File] [Edit] [View] [Help]'; then
        ok "tui: the menu row is in the tree"
    else
        bad "tui: the menu row is missing from the tree"
    fi
    if all_screens | grep -aqF '[?1049h'; then
        bad "tui: an escape sequence leaked into the grid"
    else
        ok "tui: the alternate-screen sequence was consumed, not printed"
    fi

    # One character per line: the service types it, Enter submits it, curses reads
    # it as a key. This is the interactive path, not a screenshot of a static box.
    if type_in_app "n" ' \|  status: next'; then
        ok "tui: 'n' reached the program through the service, which redrew"
    else
        bad "tui: 'n' was not processed"
    fi
    evidence | grep -aqF '> [ ] two' \
        && ok "tui: 'n' moved the selection to the second row" \
        || bad "tui: the selection did not move"

    if type_in_app "t" ' \|  status: toggle'; then
        ok "tui: 't' reached the program"
    else
        bad "tui: 't' was not processed"
    fi
    await '[x] two' 2 \
        && ok "tui: 't' marked the selected row" \
        || bad "tui: the toggle did not show"

    if type_in_app "q" 'tui left the alternate screen'; then
        ok "tui: 'q' left the alternate screen"
    else
        bad "tui: 'q' did not leave the alternate screen"
    fi
}

# --- a second TUI: an input box, and the line echoed above it ------------------

# The program is `assets/tui-echo.py`: an input box centred in the screen and an
# output box directly above it. A service types a word and Enter; the word has to
# appear in the box above, and the input box has to be empty again.
test_tui_echoes_the_line_above() {
    launch --ez echo true
    if ! await "echo-tui:started" 30; then
        bad "echo tui: the program was never sent to the session"
        return
    fi
    if ! await '|  > ' 25; then
        bad "echo tui: the input box never appeared"
        return
    fi
    ok "echo tui: the input box is on the screen"

    # Centred: the box starts where an equal margin on both sides puts it. The
    # grid is the view's own report, so this is the screen the program was given,
    # not an assumption about the device.
    local screen cols width left indent
    cols=$(evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://; s/x.*//')
    if [ -z "$cols" ]; then
        bad "echo tui: the view never reported its grid"
        return
    fi
    width=$((cols - 2))
    [ "$width" -gt 40 ] && width=40
    left=$(( (cols - width) / 2 ))
    screen=$(last_screen)
    indent=$(printf '%s\n' "$screen" | awk '/\|  > /{ match($0, /^ */); print RLENGTH; exit }')
    if [ "${indent:-x}" = "$left" ]; then
        ok "echo tui: the input box is centred in $cols columns (starts at column $((left + 1)))"
    else
        bad "echo tui: the input box is not centred (indent '${indent:-x}', wanted $left in $cols columns)"
    fi

    if ! type_until "hello" '|  hello'; then
        bad "echo tui: the submitted word was not echoed"
        return
    fi
    ok "echo tui: the word a service typed reached the program"

    # The frame after Enter, asked for rather than waited for.
    screen=$(fresh_screen)
    local echo_row input_row
    echo_row=$(printf '%s\n' "$screen" | grep -nF '|  hello' | head -1 | cut -d: -f1)
    input_row=$(printf '%s\n' "$screen" | grep -nE '\|  > ' | head -1 | cut -d: -f1)
    if [ -n "$echo_row" ] && [ -n "$input_row" ] && [ "$echo_row" -lt "$input_row" ]; then
        ok "echo tui: the line is echoed in the box above the input"
    else
        bad "echo tui: no echo above the input box (echo row '${echo_row:-none}', input row '${input_row:-none}')"
    fi
    if printf '%s\n' "$screen" | grep -qE '\|  > +\|$'; then
        ok "echo tui: the input box is empty again after Enter"
    else
        bad "echo tui: the input box still holds the line"
    fi

    if type_until "/quit" "echo tui left the alternate screen"; then
        ok "echo tui: '/quit' left the alternate screen"
    else
        bad "echo tui: '/quit' did not leave the alternate screen"
    fi
}

# --- the caret the view draws (not the program) --------------------------------

# A TUI positions the cursor and leaves drawing it to the terminal. The echo TUI
# asks for a visible caret (curses leaves DEC mode 25 on) and puts it in its empty
# input box, so the pixels here are the view's block, not a glyph: bright cell,
# dark cell four columns along.
test_tui_caret_is_visible() {
    launch --ez echo true
    if ! await "echo-tui:started" 30 || ! await '|  > ' 25; then
        bad "caret: the echo TUI never drew its input box"
        return
    fi
    # The row's own bounds are the cell geometry: a column is the view's width
    # over the grid's. Waiting for the grid to settle first keeps a keyboard that
    # is still opening from moving the row between the dump and the pixels.
    settled_grid 16 >/dev/null || true
    local tree row_line row_text bounds left top right bottom cols caret_col
    tree=$(dump e2e-caret)
    row_line=$(printf '%s\n' "$tree" | grep -aF '|  > ' | head -1)
    bounds=$(printf '%s\n' "$row_line" | sed -n \
        's/.*bounds=\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\).*/\1 \2 \3 \4/p')
    if [ -z "$row_line" ] || [ -z "$bounds" ]; then
        bad "caret: the input box is not a row node with bounds"
        return
    fi
    read -r left top right bottom <<< "$bounds"
    # The probe writes the text unquoted: ` text=<rows> desc=<desc>`.
    row_text=$(printf '%s\n' "$row_line" | sed -n 's/.* text=\(.*\) desc=.*/\1/p')
    cols=$(evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://; s/x.*//')
    if [ -z "$cols" ] || [ "$cols" -le 0 ]; then
        bad "caret: the view never reported its grid"
        return
    fi
    # The caret sits after "  > " inside the box, i.e. five columns in from the
    # border; nothing has been typed, so it is over a blank cell.
    indent=$(printf '%s\n' "$row_text" | awk '{ match($0, /^ */); print RLENGTH }')
    caret_col=$((indent + 5))

    # One frame has to hold both cells: the caret's own cell lit and the cell four
    # columns along blank. A frame caught mid-repaint does not, and the watch keeps
    # looking for one that does - which is what five screenshots and "the most lit
    # sample" were standing in for. The block covers the glyph cell, not the
    # leading between rows, so a lit cell averages well below the block colour
    # (about 150 of 255): measured here at 96 against a blank 25, which is where
    # 80 and 60 come from.
    local answer
    watch_pixels caret --es cellgrid "$left,$top,$right,$bottom,$cols" \
        --es cells "$caret_col,80,255:$((caret_col + 4)),0,60"
    answer=$(watch_answer caret 15)
    if printf '%s\n' "$answer" | grep -aq '^result=pass'; then
        ok "caret: the view draws a block at the input box's cursor" \
            "(cell $(watched "$answer" "cell.$caret_col") against" \
            "$(watched "$answer" "cell.$((caret_col + 4))"), on frame $(watched "$answer" frame))"
    else
        bad "caret: no frame had a block at the cursor and a blank cell beside it" \
            "($(watch_said "$answer"); row '$row_text', indent '${indent:-?}', cols '$cols'," \
            "cell '$caret_col', bounds '$bounds')"
    fi
}

# The box TUI asks for a blinking block (DECSCUSR 1), which only the terminal can
# draw. Both phases have to reach the pixels: always-on is a steady caret, not a
# blinking one, and always-off is no caret at all.
test_tui_blinks_the_caret() {
    launch --ez tui true
    if ! await "BOX-TUI" 30; then
        bad "blink: the box TUI never drew"
        return
    fi
    settled_grid 16 >/dev/null || true
    local tree row_line row_text bounds left top right bottom cols indent caret_col
    tree=$(dump e2e-blink)
    # The prompt row is the one whose text is " >" - the '>' sits in column 1,
    # so the row starts with a space. The box's selection markers have two
    # spaces and more text after them.
    row_line=$(printf '%s\n' "$tree" | grep -aE ' text= > desc=' | head -1)
    bounds=$(printf '%s\n' "$row_line" | sed -n \
        's/.*bounds=\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\).*/\1 \2 \3 \4/p')
    if [ -z "$row_line" ] || [ -z "$bounds" ]; then
        bad "blink: the prompt is not a row node with bounds"
        return
    fi
    read -r left top right bottom <<< "$bounds"
    row_text=$(printf '%s\n' "$row_line" | sed -n 's/.* text=\(.*\) desc=.*/\1/p')
    indent=$(printf '%s\n' "$row_text" | awk '{ match($0, /^ */); print RLENGTH }')
    # The prompt is "> " at that indent, so the caret is two columns further.
    caret_col=$((indent + 2))
    cols=$(evidence | grep -a '^### grid:' | tail -1 | sed 's/.*grid://; s/x.*//')
    if [ -z "$cols" ] || [ "$cols" -le 0 ]; then
        bad "blink: the view never reported its grid"
        return
    fi

    # Blinking is two different frames: one with the cell lit, one with it dark.
    # Neither has to be the first, and the answer says which frame showed each,
    # so a caret that is simply on or simply off cannot pass this.
    local answer
    watch_pixels blink --es cellgrid "$left,$top,$right,$bottom,$cols" \
        --es cells "$caret_col,120,255:$caret_col,0,60" --ez across true --el waitMs 12000
    answer=$(watch_answer blink 15)
    if printf '%s\n' "$answer" | grep -aq '^result=pass'; then
        ok "blink: the box TUI's caret blinks" \
            "(cell $(watched "$answer" "met.1.cell.$caret_col") at frame $(watched "$answer" met.1.frame)," \
            "cell $(watched "$answer" "met.2.cell.$caret_col") at frame $(watched "$answer" met.2.frame))"
    else
        bad "blink: the caret did not blink in $(watched "$answer" frames) changed frame(s)" \
            "($(watch_said "$answer"))"
    fi
}

# --- background colours --------------------------------------------------------

# A TUI's box is spaces with a colour behind them, so the grid has to keep the
# background as well as the glyph. The proof is the pixels: both fills the
# program asked for (SGR 44 and 43) have to be on screen. Counting the whole
# frame keeps this independent of where the IME leaves the view between the
# dump and the frame the watch passes on.
test_tui_background_box() {
    launch --ez background true
    if ! await "BG-TUI" 30; then
        bad "background: the TUI never drew"
        return
    fi
    settled_grid 16 >/dev/null || true
    local tree answer
    tree=$(dump e2e-background)
    if ! printf '%s\n' "$tree" | grep -aqF 'yellow background'; then
        bad "background: the yellow box is not in the tree"
        return
    fi
    # Each fill is tens of thousands of pixels, and the watch counts the screen
    # the device is showing: a fill that is not there counts as nothing.
    watch_pixels background \
        --es colours "blue,42,62,91,111,154,174,20000:yellow,186,206,150,170,0,10,20000"
    answer=$(watch_answer background 20)
    if printf '%s\n' "$answer" | grep -aq '^result=pass'; then
        ok "background: the blue and yellow fills reached the pixels" \
            "(blue $(watched "$answer" count.blue), yellow $(watched "$answer" count.yellow)," \
            "on frame $(watched "$answer" frame))"
    else
        bad "background: no frame had both box fills ($(watch_said "$answer"))"
    fi
}

# The same escapes as a program writes them: pty, reader, grid, row nodes.
test_cursor_escapes_from_a_program() {
    launch --ez sizeProbes false
    if ! await "stdout-is-tty" 15; then
        bad "cursor: no shell to print the escapes"
        return
    fi
    # The app's own probes end with `echo still-alive; stty size`, half a second
    # after the line above. Waiting for that answer instead of sleeping 4 s is
    # what keeps a probe from being typed between this check's printf and its
    # Enter: they share the pty, and two submits landing together interleave.
    await "still-alive" 6
    # `printf` is the program; the CUP sequences are its output. Clear first, so
    # the rows the jumps land on hold nothing but what the program put there.
    if type_in_app "printf \"\\033[2J\\033[10;3HPTY-JUMP\\033[3;40HROW-JUMP\"" \
            "  PTY-JUMP"; then
        ok "cursor: a program's CUP reached the pty, the grid and the row nodes"
    else
        bad "cursor: the program's first jump did not reach a row"
        return
    fi
    if await_line " {39}ROW-JUMP" 10; then
        ok "cursor: the second jump landed at row 3, column 40"
    else
        bad "cursor: the second jump did not land at row 3, column 40"
    fi
}

test_grid_resize_reaches_the_pty() {
    # The size question is the service's own: a timed probe can run out while a
    # window is being resized, and then this check waits for an answer that is
    # never asked for.
    launch --ez sizeProbes false
    if ! type_in_app "echo size-now=\$(stty size)" "size-now="; then
        bad "resize: the shell never answered stty size"
        return
    fi
    local before mark
    before=$(last_grid)
    mark=$(evidence_bytes)
    resize_window 0.3

    if ! grid_changed "$before" "$mark" 12; then
        bad "resize: the view's grid did not follow the window (was $before)"
        return
    fi
    local after cols rows
    after=$(settled_grid)
    cols=${after%%x*}
    rows=${after##*x}
    ok "resize: the view's grid is ${before} -> ${after}"

    # ioctl(TIOCSWINSZ) is the app's own path here, and the shell has to see it.
    if type_in_app "echo size-now=\$(stty size)" "size-now=$rows $cols"; then
        ok "resize: the pty reports $rows $cols, the grid the view has"
    else
        bad "resize: the pty did not follow the view (wanted 'size-now=$rows $cols')"
    fi
}

# The other transport: the pty is Termux's, so the size has to travel over the
# control port and `stty -F`; the program's own `stty size` is the witness.
test_session_resize_follows_the_view() {
    launch --ez termux true --ez sizeProbes false
    if ! await "termux:connected:true" 20; then
        bad "session resize: Termux never connected back"
        return
    fi
    # Same socket as the app's demo: type once the demo's last line is answered.
    if ! await "termux:after-ticks" 20; then
        bad "session resize: the demo's output never finished"
        return
    fi
    # Asked through the service, not by a timed probe: a fixed number of probes
    # runs out, and this check then waits for an answer nobody is going to ask
    # for. With the timer off, the service's own write cannot interleave either.
    if ! type_in_app "echo size-now=\$(stty size)" "size-now="; then
        bad "session resize: the session never answered stty size"
        return
    fi
    local before mark after cols rows
    before=$(last_grid)
    mark=$(evidence_bytes)
    resize_window 0.3
    if ! grid_changed "$before" "$mark" 12; then
        bad "session resize: the view's grid did not follow the window (was $before)"
        return
    fi
    after=$(settled_grid)
    cols=${after%%x*}
    rows=${after##*x}
    if type_in_app "echo size-now=\$(stty size)" "size-now=$rows $cols"; then
        ok "session resize: Termux's pty reports $rows $cols, the grid the view has"
    else
        bad "session resize: Termux's pty did not follow (wanted 'size-now=$rows $cols')"
    fi
}

# --- what a service can read --------------------------------------------------

test_rows_and_events() {
    launch
    # The old poll gave the view a second to draw; the wait for launch evidence
    # does not, so the rows this check reads are waited for explicitly.
    await_rows 5 10
    local summary rows changed announced actions needle
    summary=$(inspect e2e-rows)
    rows=$(printf '%s\n' "$summary" | sed -n 's/^rows=//p')
    changed=$(printf '%s\n' "$summary" | sed -n 's/^events\.CONTENT_CHANGED=//p')
    announced=$(printf '%s\n' "$summary" | sed -n 's/^events\.ANNOUNCEMENT=//p')
    actions=$(printf '%s\n' "$summary" | sed -n 's/^prompt\.actions=//p')

    [ "${rows:-0}" -ge 5 ] && ok "rows: the probe's own summary counts $rows row nodes" \
        || bad "rows: only ${rows:-0} row nodes in the summary"
    [ "${changed:-0}" -gt 0 ] && ok "events: $changed content-changed events" \
        || bad "events: no TYPE_WINDOW_CONTENT_CHANGED reached the service"
    [ "${announced:-0}" -gt 0 ] && ok "events: $announced announcements" \
        || bad "events: no TYPE_ANNOUNCEMENT reached the service"
    case "$actions" in
        *SET_TEXT*) ok "a11y: the prompt exposes ACTION_SET_TEXT ($actions)" ;;
        *) bad "a11y: the prompt does not expose ACTION_SET_TEXT (got '$actions')" ;;
    esac

    # TalkBack's swipe, performed by the service; the probe chooses the row.
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --ez focusRow true" >/dev/null 2>&1
    if probe_file actions | grep -aq 'A11Y_FOCUS_FIRST .* -> true'; then
        ok "a11y: ACTION_ACCESSIBILITY_FOCUS focused a terminal row"
    else
        bad "a11y: the focus action did not succeed (got: $(probe_file actions | grep A11Y_FOCUS_FIRST | tail -1))"
        return
    fi
    summary=$(inspect e2e-focus)
    focused=$(printf '%s\n' "$summary" | sed -n 's/^focusedA11y=//p')
    changed=$(printf '%s\n' "$summary" | sed -n 's/^events\.A11Y_FOCUSED=//p')
    if [ -n "$focused" ] && [ "$focused" != "-" ] && [ "${changed:-0}" -gt 0 ]; then
        ok "a11y: the service reads the focused row back ('$focused')"
    else
        bad "a11y: accessibility focus was not visible to the service (focused '$focused', events '${changed:-0}')"
    fi
}

# --- a Termux session, driven through accessibility ---------------------------

test_service_types_into_a_termux_session() {
    # No size questions while the service types: both writes go to the same
    # socket, and two submits landing together become one mangled line.
    launch --ez termux true --ez sizeProbes false
    if ! await "termux:connected:true" 20; then
        bad "session: Termux never connected back"
        return
    fi
    ok "session: Termux connected and the view is receiving its output"
    # The app's own demo lines go to the same socket; typing before its last one
    # (three ticks, answered in about three seconds) is what made the service's
    # line lose a race and need a second attempt.
    if ! await "termux:after-ticks" 20; then
        bad "session: the demo's output never finished"
        return
    fi

    local token="E2E-$$-$RANDOM"
    # The action a screen reader or Voice Access performs; the trailing newline
    # is "and press enter", because a service cannot press Enter any other way.
    # Asserted on a line that starts with the answer, not on the token anywhere:
    # the terminal echoes the command back, so the token alone proves nothing.
    if type_in_app "echo RESULT-$token" "RESULT-$token"; then
        ok "session: ACTION_SET_TEXT reached Termux, which ran the line"
    else
        bad "session: the line the service typed never came back"
        device shell "tail -c 240 $PROBE_FILES/actions.txt" 2>/dev/null | sed 's/^/     /'
        dump e2e-session-fail 2>/dev/null | grep -a 'windows=\|-- window' | sed 's/^/     /'
    fi
    if probe_file actions | grep -aq "package=$APP"; then
        ok "session: the service performed the action on this app's window"
    else
        bad "session: the action did not report this app's package"
    fi
}

# --- the opencode entry page's glyphs and modes -------------------------------

# The page was captured from its own pty at 100x30: six non-ASCII glyphs and
# truecolor SGR only. `assets/tui-symbols.py` is the page in miniature. The rows
# prove the parser carried every glyph; the pixels prove a truecolor
# background reached the cells, which the row text cannot show.
test_entry_page_symbols() {
    launch --ez symbols true
    if ! await "symbols-tui:started" 30 || ! await "SYMBOLS" 25; then
        bad "symbols: the entry-page TUI never drew"
        return
    fi
    local tree
    tree=$(dump e2e-symbols)
    if printf '%s\n' "$tree" | grep -aqF 'SYMBOLS ▀ █ ▄ ┃ ╹ … ·'; then
        ok "symbols: all seven entry-page glyphs reached the row nodes"
    else
        bad "symbols: a glyph is missing from the row nodes"
    fi
    if printf '%s\n' "$tree" | grep -aqF '█▀▀█ █▀▀█ █▀▀█'; then
        ok "symbols: the block logo is in the tree"
    else
        bad "symbols: the block logo is missing"
    fi
    if printf '%s\n' "$tree" | grep -aqF 'Ask anything…'; then
        ok "symbols: the composer's ellipsis reached the tree"
    else
        bad "symbols: the composer text is missing"
    fi
    settled_grid 16 >/dev/null || true
    local answer
    # #0A0A0A is the page's canvas and #1E1E1E its composer: two truecolors the
    # row text cannot show, counted where they are drawn.
    watch_pixels symbols \
        --es colours "canvas,4,16,4,16,4,16,20000:panel,24,36,24,36,24,36,10000"
    answer=$(watch_answer symbols 20)
    if printf '%s\n' "$answer" | grep -aq '^result=pass'; then
        ok "symbols: the page's truecolor fills reached the pixels" \
            "(canvas $(watched "$answer" count.canvas), panel $(watched "$answer" count.panel)," \
            "on frame $(watched "$answer" frame))"
    else
        bad "symbols: no frame had both truecolor fills ($(watch_said "$answer"))"
    fi
    if type_until "q" "symbols tui left the alternate screen"; then
        ok "symbols: 'q' left the alternate screen"
    else
        bad "symbols: 'q' did not leave the alternate screen"
    fi
}

# The page also enables modes that draw nothing and asks the terminal questions.
# `assets/tui-modes.py` sends the whole set and draws one sentinel: a payload
# that leaked is text in the tree, and a mode that swallowed input never quits.
test_entry_page_modes() {
    launch --ez modes true
    if ! await "modes-tui:started" 30 || ! await "MODES-OK" 25; then
        bad "modes: the entry-page TUI never drew"
        return
    fi
    local tree text
    tree=$(dump e2e-modes)
    text=$(printf '%s\n' "$tree" | sed -n 's/.* text=\(.*\) desc=.*/\1/p')
    if printf '%s\n' "$text" | grep -aqF 'MODES-OK'; then
        ok "modes: the sentinel row is in the tree"
    else
        bad "modes: the sentinel row is missing"
    fi
    if printf '%s\n' "$text" | grep -aqE '1049|2026|2004|2031|P\+q4d73|1337|\[\?u'; then
        bad "modes: an escape payload leaked into the row text"
    else
        ok "modes: every mode and query was consumed, not printed"
    fi
    if type_until "q" "modes tui left the alternate screen"; then
        ok "modes: 'q' left the alternate screen"
    else
        bad "modes: 'q' did not leave the alternate screen"
    fi
}

# --- the pty and the line discipline ------------------------------------------

# `assets/pty-flags.py` reports what `script`'s pty gave the program: the device,
# the three descriptors, the line discipline's flags and the size it is told.
test_pty_reports_its_device() {
    launch --ez ptyFlags true
    if ! await "PTY-FLAGS" 30; then
        bad "pty flags: the program never reported"
        return
    fi
    local screen
    request_dump
    screen=$(all_screens)
    printf '%s\n' "$screen" | grep -qE '^tty: /dev/pts/[0-9]+$' \
        && ok "pty flags: stdin is a pty device" \
        || bad "pty flags: no pty device name in the grid"
    has_line "$screen" "isatty: 1 1 1" \
        && ok "pty flags: all three standard descriptors are terminals" \
        || bad "pty flags: a standard descriptor is not a terminal"
    has_line "$screen" "termios: icanon=1 echo=1 isig=1" \
        && ok "pty flags: cooked mode, with echo and signals" \
        || bad "pty flags: the line discipline is not in its default mode"
    printf '%s\n' "$screen" | grep -qE '^winsize: 80x[0-9]+$' \
        && ok "pty flags: TIOCGWINSZ reports the view's 80 columns" \
        || bad "pty flags: TIOCGWINSZ does not match the view"
}

# `assets/pty-raw.py` clears ICANON, ECHO and ISIG and reads a single byte. In
# cooked mode the byte would wait for Enter and be echoed by the kernel instead.
test_pty_raw_mode_reads_a_key() {
    launch --ez ptyRaw true
    if ! await "raw: icanon=0 echo=0 isig=0" 30; then
        bad "pty raw: tty.setraw did not clear ICANON, ECHO and ISIG"
        return
    fi
    ok "pty raw: tty.setraw cleared ICANON, ECHO and ISIG"
    if type_in_app "x" "raw-got: 120"; then
        ok "pty raw: the key arrived without waiting for Enter"
    else
        bad "pty raw: the key did not reach the program"
    fi
}

# --- tabs, regions and insert/delete ------------------------------------------

# `assets/tty-tabs.py` writes two tab-separated lines: a tab that was dropped
# would leave the second letter one column after the first.
test_tab_stops() {
    launch --ez tabs true
    if ! await "TTY-TABS" 30; then
        bad "tabs: the program never drew"
        return
    fi
    local screen
    request_dump
    screen=$(all_screens)
    has_line "$screen" "A       B" \
        && ok "tabs: HT moved B to the 9th column" \
        || bad "tabs: the first tab did not land on a stop"
    has_line "$screen" "ab      cd" \
        && ok "tabs: a tab from mid-line landed on the same stops" \
        || bad "tabs: the second tab did not land on a stop"
}

# Rows 1 and 4 are outside the DECSTBM region: they are what proves the line
# feed at the bottom of the region scrolled only rows 2 and 3.
test_scroll_region() {
    launch --ez scroll true
    if ! await "TTY-SCROLL" 30; then
        bad "scroll: the program never drew"
        return
    fi
    local screen
    screen=$(fresh_screen)
    [ "$(printf '%s\n' "$screen" | sed -n '1p')" = "top" ] \
        && ok "scroll: the row above the region did not move" \
        || bad "scroll: the row above the region moved (got '$(printf '%s\n' "$screen" | sed -n '1p')')"
    [ "$(printf '%s\n' "$screen" | sed -n '2p')" = "in-2" ] \
        && ok "scroll: the line feed scrolled the region" \
        || bad "scroll: the region did not scroll (got '$(printf '%s\n' "$screen" | sed -n '2p')')"
    [ -z "$(printf '%s\n' "$screen" | sed -n '3p')" ] \
        && ok "scroll: the vacated row inside the region is blank" \
        || bad "scroll: the vacated row still holds text"
    [ "$(printf '%s\n' "$screen" | sed -n '4p')" = "bottom" ] \
        && ok "scroll: the row below the region did not move" \
        || bad "scroll: the row below the region moved"
}

# `assets/tty-edit.py` leaves four results on the screen at once: IL, DL, ICH
# and DCH each moved or opened exactly the cells they were asked to.
test_insert_and_delete() {
    launch --ez edit true
    if ! await "TTY-EDIT" 30; then
        bad "edit: the program never drew"
        return
    fi
    local screen
    screen=$(fresh_screen)
    [ -z "$(printf '%s\n' "$screen" | sed -n '2p')" ] \
        && ok "edit: IL opened a blank row at 2" \
        || bad "edit: IL left text in the row it inserted"
    [ "$(printf '%s\n' "$screen" | sed -n '4p')" = "line-two" ] \
        && ok "edit: IL pushed line-two down to row 4" \
        || bad "edit: IL did not move the lines below it"
    [ "$(printf '%s\n' "$screen" | sed -n '11p')" = "d-four" ] \
        && ok "edit: DL pulled d-four up to row 11" \
        || bad "edit: DL did not move the lines below it (got '$(printf '%s\n' "$screen" | sed -n '11p')')"
    [ "$(printf '%s\n' "$screen" | sed -n '16p')" = "A  BCDE" ] \
        && ok "edit: ICH opened two cells at column 2" \
        || bad "edit: ICH did not shift the rest of the row (got '$(printf '%s\n' "$screen" | sed -n '16p')')"
    [ "$(printf '%s\n' "$screen" | sed -n '17p')" = "ACDE" ] \
        && ok "edit: DCH closed one cell at column 2" \
        || bad "edit: DCH did not shift the rest of the row (got '$(printf '%s\n' "$screen" | sed -n '17p')')"
}

# --- typing under load ---------------------------------------------------------

# One ACTION_SET_TEXT and Enter, with no retry: this check counts what the
# program received, so a retried submit would look like a duplicate.
submit_text() {
    device shell "am broadcast -p $PROBE -a $PROBE.COMMAND --es pkg $APP \
        --es text '$1\\n'" >/dev/null 2>&1
}

# Long lines, submitted back to back with no pause, so the second lands while
# the first is still being drawn. The program numbers and measures what it read:
# a swallowed letter shortens the line, a duplicate adds a row, and a reorder
# swaps the two. A screen reader typing fast is the same path.
test_fast_typing_keeps_every_letter() {
    launch --ez typing true
    if ! await "TTY-TYPING" 30; then
        bad "typing: the program never started"
        return
    fi
    local first="THE-QUICK-BROWN-FOX-0123456789-JUMPS-OVER-THE-LAZY-DOG"
    local second="PACK-MY-BOX-WITH-FIVE-DOZEN-LIQUOR-JUGS-9876543210"
    submit_text "$first"
    submit_text "$second"
    if ! await "got 2 " 30; then
        bad "typing: the second line never arrived"
        return
    fi
    local screen got
    screen=$(fresh_screen)
    got=$(printf '%s\n' "$screen" | grep -c '^got ')
    [ "$got" = 2 ] \
        && ok "typing: exactly two lines arrived for two submits" \
        || bad "typing: $got lines arrived for two submits"
    printf '%s\n' "$screen" | grep -qxF "got 1 ${#first} $first" \
        && ok "typing: the first line arrived whole, in order" \
        || bad "typing: the first line is not the one that was sent"
    printf '%s\n' "$screen" | grep -qxF "got 2 ${#second} $second" \
        && ok "typing: the second line arrived whole, in order" \
        || bad "typing: the second line is not the one that was sent"
}

# --- fast typing into a curses TUI --------------------------------------------

# `assets/tui-typing.py` reads keys one at a time, then prints each submitted
# line with its length and a count of the keys it consumed. The two lines go in
# back to back: a dropped letter shortens a row, a duplicate adds a key, and two
# lines that changed places swap the rows.
test_curses_fast_typing() {
    launch --ez tuiTyping true
    if ! await "tui-typing:started" 30; then
        bad "curses typing: the TUI was never sent to the session"
        return
    fi
    if ! await "CURSES-TYPING" 25; then
        bad "curses typing: the TUI never drew"
        return
    fi
    # Nothing typed yet, so nothing read: a count that starts above zero means a
    # byte was already waiting when the program started, and the last check would
    # then report it as a letter that arrived twice.
    if await_screen "keys: 0" 10; then
        ok "curses typing: the program started with no key waiting"
    else
        bad "curses typing: the program already had a key before anything was typed"
    fi
    local first="THE-QUICK-BROWN-FOX-0123456789-JUMPS-OVER-THE-LAZY-DOG"
    local second="PACK-MY-BOX-WITH-FIVE-DOZEN-LIQUOR-JUGS-9876543210"
    local third="done"
    submit_text "$first"
    submit_text "$second"
    submit_text "$third"
    if ! await "got 3 " 30; then
        bad "curses typing: not all three lines reached the TUI"
        return
    fi
    local screen expected keys
    screen=$(fresh_screen)
    printf '%s\n' "$screen" | grep -qxF "got 1 ${#first} $first" \
        && ok "curses typing: the first line arrived whole, in order" \
        || bad "curses typing: the first line is not the one that was sent"
    printf '%s\n' "$screen" | grep -qxF "got 2 ${#second} $second" \
        && ok "curses typing: the second line arrived whole, in order" \
        || bad "curses typing: the second line is not the one that was sent"
    printf '%s\n' "$screen" | grep -qxF "got 3 ${#third} $third" \
        && ok "curses typing: the short line after them arrived whole" \
        || bad "curses typing: the short line after them did not"
    expected=$(( ${#first} + ${#second} + ${#third} + 3 ))
    keys=$(printf '%s\n' "$screen" | sed -n 's/^keys: //p' | tail -1)
    [ "${keys:-0}" = "$expected" ] \
        && ok "curses typing: $keys keys, one per character and per Enter" \
        || bad "curses typing: $keys keys for $expected characters and Enters"
}

# The same three lines into a program that never stops repainting. Curses reads
# its keys between frames there, and each frame hands bytes to the view, so this
# is where a keystroke is easiest to drop or to overtake the one before it.
test_curses_fast_typing_under_load() {
    launch --ez tuiTypingLoad true
    if ! await "tui-typing-load:started" 30; then
        bad "curses typing under load: the TUI was never sent to the session"
        return
    fi
    if ! await "CURSES-LOAD-TYPING" 25; then
        bad "curses typing under load: the TUI never drew"
        return
    fi
    if await_screen "keys: 0" 10; then
        ok "curses typing under load: the program started with no key waiting"
    else
        bad "curses typing under load: it already had a key before anything was typed"
    fi
    local first="THE-QUICK-BROWN-FOX-0123456789-JUMPS-OVER-THE-LAZY-DOG"
    local second="PACK-MY-BOX-WITH-FIVE-DOZEN-LIQUOR-JUGS-9876543210"
    local third="done"
    submit_text "$first"
    submit_text "$second"
    submit_text "$third"
    if ! await "got 3 " 30; then
        bad "curses typing under load: not all three lines reached the TUI"
        return
    fi
    local screen expected keys frames
    screen=$(fresh_screen)
    printf '%s\n' "$screen" | grep -qxF "got 1 ${#first} $first" \
        && ok "curses typing under load: the first line arrived whole, in order" \
        || bad "curses typing under load: the first line is not the one sent"
    printf '%s\n' "$screen" | grep -qxF "got 2 ${#second} $second" \
        && ok "curses typing under load: the second line arrived whole, in order" \
        || bad "curses typing under load: the second line is not the one sent"
    printf '%s\n' "$screen" | grep -qxF "got 3 ${#third} $third" \
        && ok "curses typing under load: the short line after them arrived whole" \
        || bad "curses typing under load: the short line after them did not"
    expected=$(( ${#first} + ${#second} + ${#third} + 3 ))
    keys=$(printf '%s\n' "$screen" | sed -n 's/^keys: //p' | tail -1)
    [ "${keys:-0}" = "$expected" ] \
        && ok "curses typing under load: $keys keys, one per character and per Enter" \
        || bad "curses typing under load: $keys keys for $expected characters and Enters"
    frames=$(printf '%s\n' "$screen" | sed -n 's/^frame: //p' | tail -1)
    [ "${frames:-0}" -gt 2 ] \
        && ok "curses typing under load: it kept repainting ($frames frames) while typed into" \
        || bad "curses typing under load: it stopped repainting ($frames) while typed into"
}

# --- run ----------------------------------------------------------------------

# One check by name, or all of them: the install and the device state are the
# same either way, because a check that runs alone starts from the same phone.
wanted() {
    case " $ONLY " in
        *" $1 "*) return 0 ;;
    esac
    return 1
}

# How far the run has come and how much longer it needs. What is left is the
# median of each remaining check from test/.timings.db; what is spent is this
# run's wall clock, so the install and setup it has already paid for count and
# the estimate does not look too short at the start. Silent for `--only`, where
# there is no run to estimate, and until there is history to estimate from.
eta_line() {
    [ -n "$ONLY" ] && return 0
    local line
    line=$(bash "$here/../test/timings.sh" eta --class samples.e2e \
        --run "$RUN_STARTED" --log "$TIMING_LOG" --db "$here/../test/.timings.db" \
        --order "$CHECKS" --started "$START_EPOCH" 2>/dev/null) || return 0
    case "$line" in
        "eta: no timing history"*) return 0 ;;
    esac
    printf '  %s\n' "$line"
}

check() {
    local name=$1
    shift
    if [ -n "$ONLY" ] && ! wanted "$name"; then
        return 0
    fi
    local started=$SECONDS before=$FAIL_COUNT seconds
    CHECKS_RUN=$((CHECKS_RUN + 1))
    mark "check:$name:start"
    "$@"
    if [ "$FAIL_COUNT" != "$before" ]; then
        FAILED_CHECKS="$FAILED_CHECKS $name"
        mark "check:$name:failed"
    fi
    mark "check:$name:end"
    reap_sessions
    seconds=$((SECONDS - started))
    say "  ($name: ${seconds}s)"
    # The log line the timing store already knows: class#test duration outcome.
    printf 'timing: samples.e2e#%s %dms %s\n' "$name" "$((seconds * 1000))" \
        "$([ "$FAIL_COUNT" = "$before" ] && echo passed || echo failed)" >> "$TIMING_LOG"
    eta_line
}

# Records this run in the suite's existing timing database.
record_run() {
    [ -s "$TIMING_LOG" ] || return 0
    bash "$here/../test/timings.sh" record --log "$TIMING_LOG" \
        --run "$RUN_STARTED" --db "$here/../test/.timings.db" >/dev/null 2>&1 \
        || say "WARNING: could not record this run in test/.timings.db"
}

if [ "$RESTORE_ONLY" = 1 ]; then
    restore_only
    exit $?
fi

if [ "$LIST_ONLY" = 1 ]; then
    printf '%s\n' "$CHECKS"
    exit 0
fi

for name in $ONLY; do
    case " $CHECKS " in
        *" $name "*) ;;
        *)
            printf 'no such check: %s\nchecks: %s\n' "$name" "$CHECKS" >&2
            exit 2
            ;;
    esac
done

if ! ensure_device; then
    say "FAIL no device reachable, and mDNS did not name a working port"
    FAIL=1
    record_run
    exit 1
fi

# A dead run never reached its trap; repair what it left before changing more.
if ! recover_previous_run; then
    say "FAIL the previous run's device state could not be restored; not starting"
    FAIL=1
    record_run
    exit 1
fi

install_everything
if [ "$STREAM" = 1 ] && ! start_stream; then
    say "FAIL the screen stream did not start ($(probe_file screenstream | tr '\n' ' '))"
    FAIL=1
    record_run
    exit 1
fi
start_video
start_recording
check cursor test_cursor_jumps
check cursor-escapes test_cursor_escapes_from_a_program
check colour test_colour_escapes_render
check tui test_tui_renders_and_responds
check tui-echo test_tui_echoes_the_line_above
check tui-caret test_tui_caret_is_visible
check tui-blink test_tui_blinks_the_caret
check tui-bg test_tui_background_box
check tui-symbols test_entry_page_symbols
check tui-modes test_entry_page_modes
check pty test_pty_is_a_terminal
check pty-flags test_pty_reports_its_device
check pty-raw test_pty_raw_mode_reads_a_key
check tty-tabs test_tab_stops
check tty-scroll test_scroll_region
check tty-edit test_insert_and_delete
check tty-typing test_fast_typing_keeps_every_letter
check tui-fast-typing test_curses_fast_typing
check tui-fast-typing-load test_curses_fast_typing_under_load
check resize test_grid_resize_reaches_the_pty
check rows test_rows_and_events
check session test_service_types_into_a_termux_session
check session-resize test_session_resize_follows_the_view

# The recording ends with the last check: the gate and the summary are not
# pictures of the app. With --video this also ends the stream and checks the file.
stop_recording
stop_video

# The regression gate: a check that still passes but took much longer than its
# own recent median is the slowdown this suite exists to notice quickly, and CI
# fails on it rather than on a reading of the log afterwards. Not for `--only`:
# a partial run is compared with the same checks in full runs otherwise, and
# the install shapes the first check of a subset differently.
if [ "$FAIL" = 0 ] && [ -z "$ONLY" ]; then
    if ! gate=$(bash "$here/../test/timings.sh" check --class samples.e2e \
            --log "$TIMING_LOG" --run "$RUN_STARTED" \
            --db "$here/../test/.timings.db" 2>&1); then
        printf '%s\n' "$gate" | sed 's/^/  /'
        FAIL=1
    else
        printf '  %s\n' "$gate"
    fi
fi

if [ "$FAIL" = 0 ]; then
    say "PASS $CHECKS_RUN checks in $((SECONDS - START))s"
    record_run
else
    say "FAILURES after $((SECONDS - START))s"
    record_run
    exit 1
fi
