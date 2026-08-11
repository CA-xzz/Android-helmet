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

TEST_PACKAGE=com.example.helmet.test
TEST_RUNNER=androidx.test.runner.AndroidJUnitRunner
TEST_CLASS=com.example.helmet.AudioHardwareInstrumentedTest
APP_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
OUTPUT=$(mktemp "${TMPDIR:-/tmp}/helmet-audio-probe.XXXXXX")

cleanup() {
    adb -s "$ADB_SERIAL" shell am start-foreground-service \
        -n com.example.helmet/com.example.helmet.service.runtime.HelmetService >/dev/null 2>&1 || true
    adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    if [ -f "$OUTPUT" ]; then
        rm -f -- "$OUTPUT"
    fi
}
trap cleanup EXIT HUP INT TERM

adb -s "$ADB_SERIAL" get-state | rg -q '^device$' || {
    echo "ERROR: device is not online: $ADB_SERIAL"
    exit 1
}

(cd "$PROJECT_ROOT" && \
    HELMET_SOURCE_COMMIT=$(git rev-parse HEAD) \
    ./gradlew --no-daemon --console=plain \
        :device-android:app:assembleDebug \
        :device-android:app:assembleDebugAndroidTest)

adb -s "$ADB_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
adb -s "$ADB_SERIAL" install -r "$APP_APK"
adb -s "$ADB_SERIAL" install -r -t "$TEST_APK"

adb -s "$ADB_SERIAL" shell am instrument -w -r \
    -e hardwareAudioProbe true \
    -e class "$TEST_CLASS" \
    "$TEST_PACKAGE/$TEST_RUNNER" | tee "$OUTPUT"

rg -q 'OK \(2 tests\)' "$OUTPUT" || {
    echo "ERROR: H618 audio PCM probe failed"
    exit 1
}
rg -q 'audioCaptureSamples=16000' "$OUTPUT" || {
    echo "ERROR: H618 audio PCM probe did not report the expected capture"
    exit 1
}
rg -q 'audioPlaybackSamplesWritten=22050' "$OUTPUT" || {
    echo "ERROR: H618 audio PCM probe did not report the expected playback write"
    exit 1
}

echo "PASS: H618 Android audio HAL consumed playback PCM and returned microphone PCM"
