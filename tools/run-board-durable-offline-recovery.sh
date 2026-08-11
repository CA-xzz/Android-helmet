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

TEST_PACKAGE=com.example.helmet.service.runtime.test
TEST_RUNNER=androidx.test.runner.AndroidJUnitRunner
TEST_CLASS=com.example.helmet.service.runtime.DurableOfflineRecoveryInstrumentedTest
BATCH_TEST_CLASS=com.example.helmet.service.runtime.UploadBatchContinuationInstrumentedTest
MEDIA_BATCH_TEST_CLASS=com.example.helmet.service.runtime.MediaBatchContinuationInstrumentedTest
COMMUNICATION_RECOVERY_TEST_CLASS=com.example.helmet.service.runtime.DurableCommunicationRecoveryInstrumentedTest
TEST_APK="$PROJECT_ROOT/device-android/service-runtime/build/outputs/apk/androidTest/debug/service-runtime-debug-androidTest.apk"
TEST_TOKEN=stage3-board-integration-token
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/helmet-durable-recovery.XXXXXX")
BACKEND_PID=

cleanup() {
    adb -s "$ADB_SERIAL" reverse --remove tcp:18080 >/dev/null 2>&1 || true
    if [ -n "$BACKEND_PID" ] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
        kill "$BACKEND_PID" >/dev/null 2>&1 || true
        wait "$BACKEND_PID" 2>/dev/null || true
    fi
    adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    case "$TEST_ROOT" in
        "${TMPDIR:-/tmp}"/helmet-durable-recovery.*)
            rm -rf -- "$TEST_ROOT"
            ;;
        *)
            echo "WARNING: refusing to remove unexpected test directory: $TEST_ROOT" >&2
            ;;
    esac
}
trap cleanup EXIT HUP INT TERM

run_phase() {
    PHASE=$1
    OUTPUT="$TEST_ROOT/$PHASE.log"
    adb -s "$ADB_SERIAL" shell am instrument -w -r \
        -e durableRecoveryPhase "$PHASE" \
        -e class "$TEST_CLASS" \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$OUTPUT"
    rg -q 'OK \(1 test\)' "$OUTPUT" || {
        echo "ERROR: durable recovery $PHASE phase failed"
        exit 1
    }
}

run_batch_test() {
    OUTPUT="$TEST_ROOT/batch-continuation.log"
    adb -s "$ADB_SERIAL" shell am instrument -w -r \
        -e batchContinuation true \
        -e class "$BATCH_TEST_CLASS" \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$OUTPUT"
    rg -q 'OK \(1 test\)' "$OUTPUT" || {
        echo "ERROR: upload batch continuation test failed"
        exit 1
    }
}

run_media_batch_test() {
    OUTPUT="$TEST_ROOT/media-batch-continuation.log"
    adb -s "$ADB_SERIAL" shell am instrument -w -r \
        -e class "$MEDIA_BATCH_TEST_CLASS" \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$OUTPUT"
    rg -q 'OK \(1 test\)' "$OUTPUT" || {
        echo "ERROR: media batch continuation test failed"
        exit 1
    }
}

run_communication_recovery_phase() {
    PHASE=$1
    OUTPUT="$TEST_ROOT/communication-$PHASE.log"
    adb -s "$ADB_SERIAL" shell am instrument -w -r \
        -e durableCommunicationPhase "$PHASE" \
        -e class "$COMMUNICATION_RECOVERY_TEST_CLASS" \
        "$TEST_PACKAGE/$TEST_RUNNER" | tee "$OUTPUT"
    rg -q 'OK \(1 test\)' "$OUTPUT" || {
        echo "ERROR: durable communication recovery $PHASE phase failed"
        if [ -f "$TEST_ROOT/backend.log" ]; then
            tail -80 "$TEST_ROOT/backend.log"
        fi
        exit 1
    }
}

adb -s "$ADB_SERIAL" get-state | rg -q '^device$' || {
    echo "ERROR: device is not online: $ADB_SERIAL"
    exit 1
}

(cd "$PROJECT_ROOT" && ./gradlew --no-daemon --console=plain :device-android:service-runtime:assembleDebugAndroidTest)
adb -s "$ADB_SERIAL" reverse --remove tcp:18080 >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" install -r -t "$TEST_APK"

run_phase enqueue
adb -s "$ADB_SERIAL" shell am force-stop "$TEST_PACKAGE"

mkdir -p "$TEST_ROOT/backend"
HELMET_MEDIA_TOKEN=$TEST_TOKEN \
    python3 "$PROJECT_ROOT/backend/media_service.py" \
    --data-dir "$TEST_ROOT/backend" \
    --host 127.0.0.1 \
    --port 18080 \
    >"$TEST_ROOT/backend.log" 2>&1 &
BACKEND_PID=$!

READY=false
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    if curl --fail --silent --show-error http://127.0.0.1:18080/ready >/dev/null 2>&1; then
        READY=true
        break
    fi
    sleep 0.25
done
[ "$READY" = true ] || {
    echo "ERROR: isolated backend did not become ready"
    sed -n '1,200p' "$TEST_ROOT/backend.log"
    exit 1
}

adb -s "$ADB_SERIAL" reverse tcp:18080 tcp:18080
run_phase recover
run_batch_test
run_media_batch_test
run_communication_recovery_phase enqueue
adb -s "$ADB_SERIAL" shell am force-stop "$TEST_PACKAGE"
run_communication_recovery_phase recover

TRACK_POSTS=$(rg -c 'POST /v1/tracks:batch HTTP/1.1" 200' "$TEST_ROOT/backend.log" || true)
MEDIA_COMPLETES=$(rg -c 'POST /v1/media/sessions/.+/complete HTTP/1.1" 200' "$TEST_ROOT/backend.log" || true)
ALERT_POSTS=$(rg -c 'POST /v1/alerts HTTP/1.1" 200' "$TEST_ROOT/backend.log" || true)
CALL_POSTS=$(rg -c 'POST /v1/calls HTTP/1.1" 200' "$TEST_ROOT/backend.log" || true)
COMMAND_ACK_POSTS=$(rg -c 'POST /v1/device-commands/.*/ack HTTP/1.1" 200' "$TEST_ROOT/backend.log" || true)
BROADCAST_RECEIPT_POSTS=$(rg -c 'POST /v1/broadcasts/.*/receipts HTTP/1.1" 200' "$TEST_ROOT/backend.log" || true)
: "${TRACK_POSTS:=0}"
: "${MEDIA_COMPLETES:=0}"
: "${ALERT_POSTS:=0}"
: "${CALL_POSTS:=0}"
: "${COMMAND_ACK_POSTS:=0}"
: "${BROADCAST_RECEIPT_POSTS:=0}"
[ "$TRACK_POSTS" -ge 3 ] && [ "$MEDIA_COMPLETES" -ge 1 ] && [ "$ALERT_POSTS" -ge 102 ] && \
    [ "$CALL_POSTS" -ge 102 ] && [ "$COMMAND_ACK_POSTS" -ge 1 ] && [ "$BROADCAST_RECEIPT_POSTS" -ge 1 ] || {
    echo "ERROR: backend did not observe every recovered upload"
    exit 1
}
printf 'PASS: durable offline recovery completed after a forced test-process restart\n'
printf 'PASS: track, media, safety, and communication queues continued beyond one worker batch\n'
printf 'PASS: call, command acknowledgement, and broadcast receipt recovered after a forced test-process restart\n'
printf 'backend_track_posts=%s backend_media_completes=%s backend_alert_posts=%s\n' \
    "$TRACK_POSTS" "$MEDIA_COMPLETES" "$ALERT_POSTS"
printf 'backend_call_posts=%s\n' "$CALL_POSTS"
printf 'backend_command_ack_posts=%s backend_broadcast_receipt_posts=%s\n' \
    "$COMMAND_ACK_POSTS" "$BROADCAST_RECEIPT_POSTS"
