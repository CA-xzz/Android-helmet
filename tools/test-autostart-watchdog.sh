#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WATCHDOG_SCRIPT="$SCRIPT_DIR/device/myautorun-with-helmet.sh"
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/helmet-watchdog-test.XXXXXX")
FAKE_BIN="$TEST_ROOT/bin"
STATE_DIR="$TEST_ROOT/state"

cleanup() {
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$FAKE_BIN" "$STATE_DIR"

cat > "$FAKE_BIN/pm" <<'EOF'
#!/bin/sh
[ "${FAKE_PACKAGE_INSTALLED:-0}" = "1" ] || exit 1
printf '%s\n' 'package:/data/app/com.example.helmet/base.apk'
EOF

cat > "$FAKE_BIN/dumpsys" <<'EOF'
#!/bin/sh
if [ -f "$FAKE_STATE_DIR/service-running" ]; then
    printf '%s\n' '  * ServiceRecord{test u0 com.example.helmet/.service.runtime.HelmetService}'
fi
EOF

cat > "$FAKE_BIN/am" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_STATE_DIR/am.log"
[ "${FAKE_AM_FAIL:-0}" = "0" ] || exit 1
touch "$FAKE_STATE_DIR/service-running"
EOF

cat > "$FAKE_BIN/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF

chmod +x "$FAKE_BIN/pm" "$FAKE_BIN/dumpsys" "$FAKE_BIN/am" "$FAKE_BIN/sleep"

fail() {
    printf '%s\n' "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    grep -F -- "$2" "$1" >/dev/null || fail "$1 does not contain: $2"
}

run_watchdog() {
    CASE_NAME=$1
    rm -f "$STATE_DIR/service-running" "$STATE_DIR/am.log" "$STATE_DIR/watchdog.log" "$STATE_DIR/watchdog.pid"
    FAKE_PACKAGE_INSTALLED=$2
    FAKE_AM_FAIL=$3
    if [ "$4" = "1" ]; then
        touch "$STATE_DIR/service-running"
    fi
    export FAKE_PACKAGE_INSTALLED FAKE_AM_FAIL
    export FAKE_STATE_DIR="$STATE_DIR"
    export HELMET_WATCHDOG_PID_FILE="$STATE_DIR/watchdog.pid"
    export HELMET_WATCHDOG_LOG="$STATE_DIR/watchdog.log"
    export HELMET_WATCHDOG_STARTUP_GRACE_SECONDS=0
    PATH="$FAKE_BIN:/usr/bin:/bin" /bin/sh "$WATCHDOG_SCRIPT" --watchdog-only --once
    printf '%s\n' "PASS: $CASE_NAME"
}

/bin/sh -n "$WATCHDOG_SCRIPT"

run_watchdog healthy 1 0 1
[ ! -f "$STATE_DIR/am.log" ] || fail "healthy service was restarted"

run_watchdog missing 1 0 0
[ -f "$STATE_DIR/service-running" ] || fail "missing service was not started"
assert_contains "$STATE_DIR/am.log" 'start-foreground-service --user 0'
assert_contains "$STATE_DIR/am.log" '-a com.example.helmet.action.RECOVERY'
assert_contains "$STATE_DIR/am.log" '--es recovery_source bsp_watchdog'
assert_contains "$STATE_DIR/am.log" '--ei recovery_attempt 1'
assert_contains "$STATE_DIR/watchdog.log" 'service recovered source=bsp_watchdog attempt=1'

run_watchdog package-missing 0 0 0
[ ! -f "$STATE_DIR/am.log" ] || fail "uninstalled package was started"
assert_contains "$STATE_DIR/watchdog.log" 'package unavailable'

run_watchdog start-failed 1 1 0
assert_contains "$STATE_DIR/watchdog.log" 'start command failed attempt=1'
[ ! -f "$STATE_DIR/service-running" ] || fail "failed start reported a running service"

rm -f "$STATE_DIR/watchdog.pid" "$STATE_DIR/watchdog.log"
FAKE_PACKAGE_INSTALLED=1
FAKE_AM_FAIL=0
export FAKE_PACKAGE_INSTALLED FAKE_AM_FAIL
export FAKE_STATE_DIR="$STATE_DIR"
export HELMET_WATCHDOG_PID_FILE="$STATE_DIR/watchdog.pid"
export HELMET_WATCHDOG_LOG="$STATE_DIR/watchdog.log"
export HELMET_WATCHDOG_INTERVAL_SECONDS=30
PATH="$FAKE_BIN:/usr/bin:/bin" /bin/sh "$WATCHDOG_SCRIPT" --watchdog-only &
WATCHDOG_TEST_PID=$!
for UNUSED in 1 2 3 4 5; do
    [ -f "$STATE_DIR/watchdog.pid" ] && break
    /bin/sleep 0.1
done
[ "$(cat "$STATE_DIR/watchdog.pid")" = "$WATCHDOG_TEST_PID" ] || fail "watchdog pid was not acquired"
kill "$WATCHDOG_TEST_PID"
wait "$WATCHDOG_TEST_PID"
[ ! -f "$STATE_DIR/watchdog.pid" ] || fail "watchdog pid file survived termination"
printf '%s\n' 'PASS: termination'

printf '%s\n' 'Autostart watchdog tests passed: 5/5'
