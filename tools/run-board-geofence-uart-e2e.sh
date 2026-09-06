#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$PROJECT_ROOT/tools/env.sh"

ADB_SERIAL=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --adb-serial)
            shift
            [ "$#" -gt 0 ] || { echo "ERROR: --adb-serial requires a value"; exit 64; }
            ADB_SERIAL=$1
            ;;
        *)
            echo "ERROR: unknown argument: $1"
            exit 64
            ;;
    esac
    shift
done

[ -n "$ADB_SERIAL" ] || { echo "ERROR: --adb-serial is required"; exit 64; }

APP_PACKAGE=com.example.helmet
TEST_PACKAGE=com.example.helmet.test
TEST_RUNNER=androidx.test.runner.AndroidJUnitRunner
TEST_CLASS=com.example.helmet.AutomaticGeofenceAlertUploadInstrumentedTest
TEST_METHOD=uartNmeaTrackAndGeofenceEventsArePersistedAndAutomaticallyUploaded
SERVICE_COMPONENT=com.example.helmet/com.example.helmet.service.runtime.HelmetService
APP_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
generate_test_token() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32
    elif command -v uuidgen >/dev/null 2>&1; then
        uuidgen
    else
        echo "ERROR: openssl or uuidgen is required to create an ephemeral test token" >&2
        return 69
    fi
}
TEST_TOKEN=$(generate_test_token)
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/helmet-geofence-uart.XXXXXX")
BACKEND_PID=
RECOVERY_PENDING=false

restore_helmet_service() {
    if ! adb -s "$ADB_SERIAL" shell am start-foreground-service --user 0 \
        -n "$SERVICE_COMPONENT" >/dev/null 2>&1
    then
        echo "ERROR: failed to request HelmetService restart" >&2
        return 1
    fi

    ATTEMPT=0
    LAST_PID=
    LAST_SERVICE_DUMP=
    while [ "$ATTEMPT" -lt 20 ]; do
        ATTEMPT=$((ATTEMPT + 1))
        LAST_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)
        LAST_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
        if [ -n "$LAST_PID" ] && \
            printf '%s\n' "$LAST_SERVICE_DUMP" | rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService' && \
            printf '%s\n' "$LAST_SERVICE_DUMP" | rg -q 'isForeground=true'
        then
            printf 'helmet_service_pid=%s helmet_service_foreground=true\n' "$LAST_PID"
            return 0
        fi
        sleep 0.25
    done

    echo "ERROR: HelmetService did not recover as a foreground service" >&2
    printf 'helmet_service_pid=%s\n' "${LAST_PID:-missing}" >&2
    return 1
}

cleanup() {
    EXIT_STATUS=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$RECOVERY_PENDING" = true ] && \
        adb -s "$ADB_SERIAL" get-state 2>/dev/null | tr -d '\r' | rg -q '^device$'
    then
        if run_geofence_instrumentation cleanup-retry; then
            RECOVERY_PENDING=false
        else
            echo "WARNING: geofence cleanup retry did not complete" >&2
        fi
    fi
    adb -s "$ADB_SERIAL" reverse --remove tcp:18083 >/dev/null 2>&1 || true
    if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
        kill "$BACKEND_PID" >/dev/null 2>&1 || true
        wait "$BACKEND_PID" 2>/dev/null || true
    fi
    adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    if ! restore_helmet_service && [ "$EXIT_STATUS" -eq 0 ]; then
        EXIT_STATUS=1
    fi
    case "$TEST_ROOT" in
        "${TMPDIR:-/tmp}"/helmet-geofence-uart.*)
            if ! rm -rf -- "$TEST_ROOT" && [ "$EXIT_STATUS" -eq 0 ]; then
                EXIT_STATUS=1
            fi
            ;;
        *)
            echo "WARNING: refusing to remove unexpected test directory: $TEST_ROOT" >&2
            if [ "$EXIT_STATUS" -eq 0 ]; then
                EXIT_STATUS=1
            fi
            ;;
    esac
    exit "$EXIT_STATUS"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

run_geofence_instrumentation() {
    NAME=$1
    RAW_OUTPUT="$TEST_ROOT/$NAME.raw.log"
    OUTPUT="$TEST_ROOT/$NAME.log"
    if adb -s "$ADB_SERIAL" shell am instrument -w -r \
        -e backendBearerToken "$TEST_TOKEN" \
        -e class "$TEST_CLASS#$TEST_METHOD" \
        "$TEST_PACKAGE/$TEST_RUNNER" >"$RAW_OUTPUT" 2>&1
    then
        INSTRUMENT_STATUS=0
    else
        INSTRUMENT_STATUS=$?
    fi
    tr -d '\r' <"$RAW_OUTPUT" >"$OUTPUT"
    cat "$OUTPUT"
    OK_LINES=$(rg -c '^OK \(1 test\)$' "$OUTPUT" 2>/dev/null || true)
    if [ "$INSTRUMENT_STATUS" -ne 0 ] || [ "${OK_LINES:-0}" -ne 1 ] || \
        rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$OUTPUT"
    then
        return 1
    fi
}

adb -s "$ADB_SERIAL" get-state | rg -q '^device$' || {
    echo "ERROR: device is not online: $ADB_SERIAL"
    exit 1
}

(cd "$PROJECT_ROOT" && ./gradlew --no-daemon --console=plain \
    --project-cache-dir "$GRADLE_PROJECT_CACHE_DIR" \
    :device-android:app:assembleDebug :device-android:app:assembleDebugAndroidTest)
adb -s "$ADB_SERIAL" reverse --remove tcp:18083 >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" install -r "$APP_APK"
adb -s "$ADB_SERIAL" install -r -t "$TEST_APK"

mkdir -p "$TEST_ROOT/backend"
HELMET_MEDIA_TOKEN=$TEST_TOKEN \
    python3 "$PROJECT_ROOT/backend/media_service.py" \
    --data-dir "$TEST_ROOT/backend" \
    --host 127.0.0.1 \
    --port 18083 \
    >"$TEST_ROOT/backend.log" 2>&1 &
BACKEND_PID=$!

READY=false
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    if ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
        break
    fi
    if curl --fail --silent --show-error http://127.0.0.1:18083/ready >/dev/null 2>&1; then
        READY=true
        break
    fi
    sleep 0.25
done
[ "$READY" = true ] && kill -0 "$BACKEND_PID" >/dev/null 2>&1 || {
    echo "ERROR: isolated backend did not become ready"
    sed -n '1,200p' "$TEST_ROOT/backend.log"
    exit 1
}

adb -s "$ADB_SERIAL" reverse tcp:18083 tcp:18083
RECOVERY_PENDING=true
if ! run_geofence_instrumentation instrumentation; then
    echo "ERROR: UART geofence instrumentation failed"
    tail -100 "$TEST_ROOT/backend.log"
    exit 1
fi
RECOVERY_PENDING=false

TRACK_POSTS=$(rg -c 'request method=POST path=/v1/tracks:batch status=200' "$TEST_ROOT/backend.log" || true)
ALERT_POSTS=$(rg -c 'request method=POST path=/v1/alerts status=200' "$TEST_ROOT/backend.log" || true)
: "${TRACK_POSTS:=0}"
: "${ALERT_POSTS:=0}"
[ "$TRACK_POSTS" -ge 1 ] && [ "$ALERT_POSTS" -ge 2 ] || {
    printf 'backend_track_posts=%s backend_alert_posts=%s\n' "$TRACK_POSTS" "$ALERT_POSTS"
    echo "ERROR: backend did not observe the track batch and both geofence transitions"
    exit 1
}

printf 'PASS: repository-owned PTY drove UART NMEA through the production service\n'
printf 'PASS: geofence exit and return were persisted and uploaded\n'
printf 'backend_track_posts=%s backend_alert_posts=%s\n' "$TRACK_POSTS" "$ALERT_POSTS"
