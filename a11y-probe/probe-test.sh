#!/usr/bin/env bash
# The probe's own device suite: installs and enables the probe, starts its
# built-in fixture activity (a lazy list, a text field and a dialog), and
# exercises every action against that screen instead of the terminal app.
#
#   bash a11y-probe/probe-test.sh            # build first: a11y-probe/build.sh
#   bash a11y-probe/probe-test.sh --list     # the check names, no device
#   bash a11y-probe/probe-test.sh --quiet    # failures and the summary only
#
# This is device-only. It is not part of a11y-probe/test.sh, which is the
# offline suite, and it is not run on a machine without a test device.
#
# The accessibility setting is saved before the run and restored after it, even
# when a check fails, by probe.sh's saved state. While the probe is enabled it
# can read every window on the device and perform actions: use a test device.
set -u

ADB=${ADB:-adb}
PKG=${PROBE_PACKAGE:-io.github.mbanucu.a11yprobe}
CONTROL=content://$PKG.control
FILES=/sdcard/Android/data/$PKG/files
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' \
    "$here/AndroidManifest.xml" | head -n 1)
APK=${PROBE_APK:-$root/out/a11y-probe-$version.apk}

LIST_ONLY=0
QUIET=0
case "${1:-}" in
    --list) LIST_ONLY=1 ;;
    --quiet) QUIET=1 ;;
esac

CHECKS="fixture dump find-text find-desc find-id find-index expect-exists
expect-absent expect-text-is expect-text-contains expect-failure clear enter
long-click scroll swipe dialog-back wait-idle wait-idle-timeout key-home"
FAIL=0
ENABLED=0

say() { printf '%s\n' "$*"; }
ok() { [ "$QUIET" = 1 ] || say "ok   $*"; }
bad() { say "FAIL $*"; FAIL=1; }
device() { "$ADB" "$@"; }

if [ "$LIST_ONLY" = 1 ]; then
    printf '%s\n' "$CHECKS" | tr ' ' '\n' | sed '/^$/d'
    exit 0
fi

cleanup() {
    if [ "$ENABLED" = 1 ]; then
        bash "$root/probe.sh" disable >/dev/null 2>&1 || true
    fi
    device shell "am force-stop $PKG" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- the provider's answer ----------------------------------------------------
probe() { # method [remote arguments...]
    local method=$1
    shift
    device shell "content call --uri $CONTROL --method $method \
        --extra pkg:s:$PKG $*" 2>/dev/null | tr -d '\r'
}

reply_code() {
    printf '%s\n' "$1" | sed -n 's/.*code=\([0-9][0-9]*\).*/\1/p' | head -n 1
}

reply_data() {
    printf '%s\n' "$1" | sed -n 's/^.*data=//; s/, state=[a-z]*$//; s/}]$//p'
}

assert_code() { # wanted reply what
    local want=$1 reply=$2 what=$3 code
    code=$(reply_code "$reply")
    if [ "$code" = "$want" ]; then
        ok "$what"
    else
        bad "$what (code=${code:-none}: $(reply_data "$reply"))"
    fi
}

assert_data() { # reply needle what
    local data
    data=$(reply_data "$1")
    case "$data" in
        *"$2"*) ok "$3" ;;
        *) bad "$3 (no '$2' in '$data')" ;;
    esac
}

assert_reply() { # reply needle what - the bundle itself, for state= and code=
    case "$1" in
        *"$2"*) ok "$3" ;;
        *) bad "$3 (no '$2' in '$1')" ;;
    esac
}

dump_tree() { # name
    device shell "content call --uri $CONTROL --method dump --extra pkg:s:$PKG \
        --arg '$1' >/dev/null 2>&1; cat '$FILES/$1.txt'" 2>/dev/null
}

start_fixture() {
    device shell "am start -W -n $PKG/.ProbeFixtureActivity --ez reset true" \
        >/dev/null 2>&1
    local i=0 reply
    while [ "$i" -lt 50 ]; do
        reply=$(probe find --arg "'id=$PKG:id/fixture_status'")
        [ "$(reply_code "$reply")" = 0 ] && return 0
        sleep 0.2
        i=$((i + 1))
    done
    return 1
}

# --- the checks ---------------------------------------------------------------

test_fixture() {
    if ! start_fixture; then
        bad "fixture: the built-in activity did not expose its status node"
        return
    fi
    local reply
    reply=$(probe find --arg "'text=Ready'")
    assert_code 0 "$reply" "fixture: the status node says Ready"
    assert_data "$reply" "id=$PKG:id/fixture_status" \
        "fixture: find prints the matched resource id"
    assert_data "$reply" "desc=Fixture status" \
        "fixture: find prints the matched content description"
}

test_dump() {
    dump_tree fixture >/dev/null
    local tree
    tree=$(dump_tree fixture)
    case "$tree" in
        *"id=$PKG:id/fixture_status"*) ok "dump: the text dump prints the resource id" ;;
        *) bad "dump: no fixture_status id in the text dump" ;;
    esac
    case "$tree" in
        *"desc=Fixture status"*) ok "dump: the text dump prints the content description" ;;
        *) bad "dump: no fixture status description in the text dump" ;;
    esac
    local reply
    reply=$(device shell "content call --uri $CONTROL --method dump --extra pkg:s:$PKG \
        --arg fixture-json --extra json:z:true" 2>/dev/null | tr -d '\r')
    assert_code 0 "$reply" "dump: the JSON dump answered"
    assert_data "$reply" "\"id\":\"$PKG:id/fixture_status\"" \
        "dump: the JSON node carries the resource id"
}

test_find_text() {
    local reply
    reply=$(probe find --arg "'text=Ready'")
    assert_code 0 "$reply" "find: text= matches"
    assert_data "$reply" "matched=text=Ready" "find: the answer names the selector"
}

test_find_desc() {
    local reply
    reply=$(probe find --arg "'desc=Fixture status'")
    assert_code 0 "$reply" "find: desc= matches"
    assert_data "$reply" "field=desc" "find: the answer names the description field"
}

test_find_id() {
    local reply
    reply=$(probe find --arg "'id=$PKG:id/fixture_status'")
    assert_code 0 "$reply" "find: id= matches"
    assert_data "$reply" "field=id" "find: the answer names the id field"
}

test_find_index() {
    local reply
    reply=$(probe find --arg "'index=0'")
    assert_code 0 "$reply" "find: index= picks the first node"
}

test_expect_exists() {
    local reply
    reply=$(probe expect --arg "'text=Ready'")
    assert_code 0 "$reply" "expect: exists returns code 0"
    assert_data "$reply" "expect exists text=Ready" \
        "expect: exists names the selector and the node"
}

test_expect_absent() {
    local reply
    reply=$(probe expect --arg "'absent=text=No such row'")
    assert_code 0 "$reply" "expect: absent returns code 0 for a miss"
}

test_expect_text_is() {
    local reply
    reply=$(probe expect --arg "'text_is=Ready'")
    assert_code 0 "$reply" "expect: text_is matches exactly"
}

test_expect_text_contains() {
    local reply
    reply=$(probe expect --arg "'text_contains=Ready'")
    assert_code 0 "$reply" "expect: text_contains matches"
}

test_expect_failure() {
    local reply
    reply=$(probe expect --arg "'text_contains=No such text'")
    assert_code 400 "$reply" "expect: a miss exits 400, not 0"
    assert_data "$reply" "closest:" \
        "expect: the 400 lists the closest candidates"
    assert_data "$reply" "text_contains=No such text" \
        "expect: the 400 names the assertion and selector"
}

test_clear() {
    if ! start_fixture; then
        bad "clear: the fixture did not start"
        return
    fi
    local field="id=$PKG:id/fixture_field" reply
    probe click --arg "'$field'" >/dev/null
    reply=$(device shell "content call --uri $CONTROL --method set_text \
        --extra pkg:s:$PKG --extra find:s:'$field' --arg 'Draft token'" 2>/dev/null \
        | tr -d '\r')
    assert_code 0 "$reply" "clear: set_text put a value in the field"
    probe expect --arg "'text_contains=Draft'" >/dev/null
    reply=$(probe clear --arg "'$field'")
    assert_code 0 "$reply" "clear: clear emptied the field and read it back"
    reply=$(probe expect --arg "'absent=text=Draft token'")
    assert_code 0 "$reply" "clear: the field no longer holds the value"
}

test_enter() {
    if ! start_fixture; then
        bad "enter: the fixture did not start"
        return
    fi
    local field="id=$PKG:id/fixture_field" reply
    probe click --arg "'$field'" >/dev/null
    device shell "content call --uri $CONTROL --method set_text --extra pkg:s:$PKG \
        --extra find:s:'$field' --arg 'typed'" >/dev/null 2>&1
    reply=$(probe key --arg "'enter'")
    assert_code 0 "$reply" "enter: ACTION_IME_ENTER reached the field"
    reply=$(probe expect --arg "'text_contains=Saved: typed'")
    assert_code 0 "$reply" "enter: the editor action saved the line"
}

test_long_click() {
    if ! start_fixture; then
        bad "long-click: the fixture did not start"
        return
    fi
    local reply
    reply=$(probe long_click --arg "'text=Item 02'")
    assert_code 0 "$reply" "long-click: the row accepted ACTION_LONG_CLICK"
    reply=$(probe expect --arg "'text_contains=Long-clicked Item 02'")
    assert_code 0 "$reply" "long-click: the status changed"
}

test_scroll() {
    if ! start_fixture; then
        bad "scroll: the fixture did not start"
        return
    fi
    local reply
    reply=$(probe expect --arg "'absent=text=Item 40'")
    assert_code 0 "$reply" "scroll: Item 40 starts off the lazily built list"
    reply=$(device shell "content call --uri $CONTROL --method scroll --extra pkg:s:$PKG \
        --arg down --extra until:s:'text=Item 40' --extra timeout:l:5000" 2>/dev/null \
        | tr -d '\r')
    assert_code 0 "$reply" "scroll: scrolling until Item 40 found it"
    assert_data "$reply" "found text=Item 40" "scroll: the answer names the found node"
    reply=$(probe click --arg "'text=Item 40'")
    assert_code 0 "$reply" "scroll: the found row can be clicked"
    reply=$(probe expect --arg "'text_contains=Picked Item 40'")
    assert_code 0 "$reply" "scroll: the click changed the status"
}

test_swipe() {
    if ! start_fixture; then
        bad "swipe: the fixture did not start"
        return
    fi
    local reply bounds left top right bottom x from_y to_y
    reply=$(probe find --arg "'id=$PKG:id/fixture_list'")
    assert_code 0 "$reply" "swipe: the list node is in the tree"
    bounds=$(printf '%s\n' "$(reply_data "$reply")" | sed -n \
        's/.*bounds=\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\).*/\1 \2 \3 \4/p')
    if [ -z "$bounds" ]; then
        bad "swipe: the list node has no bounds"
        return
    fi
    read -r left top right bottom <<< "$bounds"
    x=$(( (left + right) / 2 ))
    from_y=$(( top + (bottom - top) * 3 / 4 ))
    to_y=$(( top + (bottom - top) / 4 ))
    reply=$(probe swipe --extra "from:s:$x,$from_y" --extra "to:s:$x,$to_y" \
        --extra duration:l:300)
    assert_code 0 "$reply" "swipe: dispatchGesture completed"
    assert_data "$reply" "-> completed" "swipe: the gesture was not cancelled"
}

test_dialog_back() {
    if ! start_fixture; then
        bad "dialog: the fixture did not start"
        return
    fi
    local reply
    probe click --arg "'id=$PKG:id/fixture_dialog_button'" >/dev/null
    reply=$(probe expect --arg "'text_contains=Fixture dialog'")
    assert_code 0 "$reply" "dialog: the dialog is present"
    reply=$(probe key --arg "'back'")
    assert_code 0 "$reply" "dialog: back was performed as a global action"
    probe wait_idle --extra idleMs:l:300 --extra timeout:l:3000 >/dev/null
    reply=$(probe expect --arg "'absent=Fixture dialog'")
    assert_code 0 "$reply" "dialog: back dismissed it"
}

test_wait_idle() {
    if ! start_fixture; then
        bad "wait-idle: the fixture did not start"
        return
    fi
    local reply
    reply=$(probe wait_idle --extra idleMs:l:300 --extra timeout:l:3000)
    assert_code 0 "$reply" "wait-idle: the call returned"
    assert_reply "$reply" "state=idle" "wait-idle: the screen settled"
    assert_data "$reply" "idle quiet=" "wait-idle: the answer reports the quiet time"
}

test_wait_idle_timeout() {
    if ! start_fixture; then
        bad "wait-idle-timeout: the fixture did not start"
        return
    fi
    local reply
    reply=$(probe wait_idle --extra idleMs:l:60000 --extra timeout:l:200)
    assert_code 0 "$reply" "wait-idle-timeout: a timeout is an answer, not a failure"
    assert_reply "$reply" "state=timeout" "wait-idle-timeout: the state is timeout"
    assert_data "$reply" "last=" "wait-idle-timeout: the last event is reported"
}

test_key_home() {
    local reply
    reply=$(probe key --arg "'home'")
    assert_code 0 "$reply" "key: home was performed as a global action"
    start_fixture >/dev/null || bad "key: the fixture did not come back after home"
}

# --- run ----------------------------------------------------------------------

if ! device get-state >/dev/null 2>&1; then
    say "FAIL no device (adb get-state failed); this suite is device-only"
    exit 1
fi
if [ ! -f "$APK" ]; then
    say "no $APK; run 'bash a11y-probe/build.sh' first"
    exit 1
fi
if ! bash "$root/probe.sh" install; then
    say "FAIL could not install the probe"
    exit 1
fi
if ! bash "$root/probe.sh" enable; then
    say "FAIL could not enable the probe"
    exit 1
fi
ENABLED=1

if ! start_fixture; then
    bad "the fixture activity never exposed a window"
fi

check() {
    local name=$1
    shift
    local before=$FAIL
    "$@"
    [ "$FAIL" = "$before" ] || say "  ($name failed)"
}

check fixture test_fixture
check dump test_dump
check find-text test_find_text
check find-desc test_find_desc
check find-id test_find_id
check find-index test_find_index
check expect-exists test_expect_exists
check expect-absent test_expect_absent
check expect-text-is test_expect_text_is
check expect-text-contains test_expect_text_contains
check expect-failure test_expect_failure
check clear test_clear
check enter test_enter
check long-click test_long_click
check scroll test_scroll
check swipe test_swipe
check dialog-back test_dialog_back
check wait-idle test_wait_idle
check wait-idle-timeout test_wait_idle_timeout
check key-home test_key_home

if [ "$FAIL" = 0 ]; then
    say "PASS the probe fixture suite"
else
    say "FAILURES in the probe fixture suite"
    exit 1
fi
