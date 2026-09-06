#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$PROJECT_ROOT/tools/env.sh"

ADB_SERIAL=
HARDWARE_DEVICE_PATH=
HARDWARE_BAUD_RATE=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --adb-serial)
            shift
            [ "$#" -gt 0 ] || { echo "ERROR: --adb-serial requires a value"; exit 64; }
            ADB_SERIAL=$1
            ;;
        --hardware-device-path)
            shift
            [ "$#" -gt 0 ] || { echo "ERROR: --hardware-device-path requires a value"; exit 64; }
            HARDWARE_DEVICE_PATH=$1
            ;;
        --hardware-baud-rate)
            shift
            [ "$#" -gt 0 ] || { echo "ERROR: --hardware-baud-rate requires a value"; exit 64; }
            HARDWARE_BAUD_RATE=$1
            ;;
        *)
            echo "ERROR: unknown argument: $1"
            exit 64
            ;;
    esac
    shift
done

[ -n "$ADB_SERIAL" ] || { echo "ERROR: --adb-serial is required"; exit 64; }
[ -n "$HARDWARE_DEVICE_PATH" ] || {
    echo "ERROR: --hardware-device-path is required"
    exit 64
}
[ -n "$HARDWARE_BAUD_RATE" ] || {
    echo "ERROR: --hardware-baud-rate is required"
    exit 64
}
case "$HARDWARE_BAUD_RATE" in
    9600|19200|38400|57600|115200|230400|460800|921600) ;;
    *)
        echo "ERROR: --hardware-baud-rate is not supported"
        exit 64
        ;;
esac
printf '%s\n' "$HARDWARE_DEVICE_PATH" | \
    rg -q '^/dev/tty(AS|S|USB|ACM)[0-9]{1,3}$' || {
        echo "ERROR: --hardware-device-path is not an approved real UART path"
        exit 64
    }

APP_PACKAGE=com.example.helmet
APP_TEST_PACKAGE=com.example.helmet.test
TEST_RUNNER=androidx.test.runner.AndroidJUnitRunner
SERVICE_COMPONENT=com.example.helmet/com.example.helmet.service.runtime.HelmetService
HARDWARE_PROVIDER_AUTHORITY=com.example.helmet.hardware
HARDWARE_PROCESS=com.example.helmet:hardware
HARDWARE_SELFTEST_COMPONENT=com.example.helmet/com.example.helmet.debug.HardwareSelfTestService
REAL_HARDWARE_MODE_SELECTOR=com.example.helmet.RealHardwareModePreparationInstrumentedTest#persistProvisionedRealHardwareMode
REAL_HARDWARE_MODE_CONFIRMATION=PERSIST_REAL_UART_CONFIGURATION
REAL_HARDWARE_MODE_RESULT=files/board-real-hardware-mode/result.properties
REAL_HARDWARE_MODE_STALE_RESULT=files/board-real-hardware-mode/result.properties.bak
REAL_HARDWARE_MODE_NEW_RESULT=files/board-real-hardware-mode/result.properties.new
HARDWARE_SELFTEST_RESULT=files/hardware-self-test/result.properties
HARDWARE_SELFTEST_STALE_RESULT=files/hardware-self-test/result.properties.stale
HARDWARE_SELFTEST_NEW_RESULT=files/hardware-self-test/result.properties.new
HARDWARE_SELFTEST_BACKUP_RESULT=files/hardware-self-test/result.properties.bak
HARDWARE_RECOVERY_RESULT=files/hardware-self-test/recovery.properties
HARDWARE_RECOVERY_STALE_RESULT=files/hardware-self-test/recovery.properties.stale
HARDWARE_RECOVERY_NEW_RESULT=files/hardware-self-test/recovery.properties.new
HARDWARE_RECOVERY_BACKUP_RESULT=files/hardware-self-test/recovery.properties.bak
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/helmet-connected-software.XXXXXX")
SUMMARY_FILE="$TEST_ROOT/module-counts.tsv"
SERVICE_RECOVERY_REQUIRED=false
HARDWARE_SELFTEST_ACTIVE=false
HARDWARE_SELFTEST_NONCE=
HARDWARE_SELFTEST_MAIN_PID=
HARDWARE_SELFTEST_APP_UID=
HARDWARE_PROVIDER_CRASH_RECOVERY_PENDING=false
HARDWARE_PROCESS_RESTART_REQUIRED=false
REAL_HARDWARE_MODE_RESTART_REQUIRED=false
REAL_HARDWARE_MODE_FORCE_STOP_REQUESTED=false
REAL_HARDWARE_MODE_FORCE_STOP_COMPLETED=false
REAL_HARDWARE_MODE_OLD_MAIN_PID=
REAL_HARDWARE_MODE_OLD_PROVIDER_PID=
REAL_HARDWARE_MODE_EXPECTED_UID=
REAL_HARDWARE_MODE_NEW_MAIN_PID=
REAL_HARDWARE_MODE_NEW_PROVIDER_PID=
TOTAL_TESTS=0

APP_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/debug/app-debug.apk"
DATA_LOCAL_APK="$PROJECT_ROOT/device-android/data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk"
FEATURE_CAMERA_APK="$PROJECT_ROOT/device-android/feature-camera/build/outputs/apk/androidTest/debug/feature-camera-debug-androidTest.apk"
FEATURE_CONNECTIVITY_APK="$PROJECT_ROOT/device-android/feature-connectivity/build/outputs/apk/androidTest/debug/feature-connectivity-debug-androidTest.apk"
FEATURE_LOCATION_APK="$PROJECT_ROOT/device-android/feature-location/build/outputs/apk/androidTest/debug/feature-location-debug-androidTest.apk"
SAFETY_DETECTION_APK="$PROJECT_ROOT/device-android/safety-detection/build/outputs/apk/androidTest/debug/safety-detection-debug-androidTest.apk"
WEBRTC_RUNTIME_APK="$PROJECT_ROOT/device-android/webrtc-runtime/build/outputs/apk/androidTest/debug/webrtc-runtime-debug-androidTest.apk"
SERVICE_RUNTIME_APK="$PROJECT_ROOT/device-android/service-runtime/build/outputs/apk/androidTest/debug/service-runtime-debug-androidTest.apk"
APP_TEST_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

TEST_PACKAGES='com.example.helmet.data.local.test
com.example.helmet.feature.camera.test
com.example.helmet.feature.connectivity.test
com.example.helmet.feature.location.test
com.example.helmet.safety.detection.test
com.example.helmet.webrtc.test
com.example.helmet.service.runtime.test
com.example.helmet.test'

SERVICE_RUNTIME_SELECTORS='com.example.helmet.service.runtime.BoardTestArgumentsInstrumentedTest,'\
'com.example.helmet.service.runtime.CallVolumeInstrumentedTest,'\
'com.example.helmet.service.runtime.DeviceStatusWorkerInstrumentedTest#outboxSurvivesRecreationAndQuarantinesIncompleteSnapshot,'\
'com.example.helmet.service.runtime.DurableAlarmAckInstrumentedTest,'\
'com.example.helmet.service.runtime.GeofenceAlertFactoryInstrumentedTest,'\
'com.example.helmet.service.runtime.HardwareCallToggleInstrumentedTest,'\
'com.example.helmet.service.runtime.HardwareKeyActionRecoveryInstrumentedTest,'\
'com.example.helmet.service.runtime.LocalIntercomControllerInstrumentedTest,'\
'com.example.helmet.service.runtime.PersistentPreferencesSnapshotFixtureInstrumentedTest,'\
'com.example.helmet.service.runtime.RtkCorrectionControllerInstrumentedTest,'\
'com.example.helmet.service.runtime.SafetyCheckpointCrashRecoveryInstrumentedTest,'\
'com.example.helmet.service.runtime.SafetyOutputRecoveryStoreInstrumentedTest,'\
'com.example.helmet.service.runtime.SafetySampleProcessorInstrumentedTest'

APP_MANIFEST_SELECTORS='com.example.helmet.BoardTestArgumentsInstrumentedTest,'\
'com.example.helmet.DirectRtkHardwareGatewayInstrumentedTest,'\
'com.example.helmet.DualSerialIsolationInstrumentedTest,'\
'com.example.helmet.H618BoardProfileInstrumentedTest,'\
'com.example.helmet.HslContractSimulatorInstrumentedTest,'\
'com.example.helmet.LocalConfigurationManifestInstrumentedTest'

remove_test_package() {
    PACKAGE=$1
    adb -s "$ADB_SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
}

restore_helmet_service() {
    adb -s "$ADB_SERIAL" shell am start-foreground-service --user 0 \
        -n "$SERVICE_COMPONENT" >/dev/null 2>&1 || true

    ATTEMPT=0
    LAST_PID=
    LAST_SERVICE_DUMP=
    LAST_PID_VALID=false
    while [ "$ATTEMPT" -lt 20 ]; do
        ATTEMPT=$((ATTEMPT + 1))
        LAST_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)
        LAST_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
        case "$LAST_PID" in
            ''|*[!0-9]*) LAST_PID_VALID=false ;;
            *) LAST_PID_VALID=true ;;
        esac
        if [ "$LAST_PID_VALID" = true ] && \
            printf '%s\n' "$LAST_SERVICE_DUMP" | rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService' && \
            printf '%s\n' "$LAST_SERVICE_DUMP" | rg -q 'isForeground=true'
        then
            printf 'helmet_service_pid=%s helmet_service_foreground=true\n' "$LAST_PID"
            return 0
        fi
        sleep 0.25
    done

    echo "ERROR: HelmetService did not recover as a foreground service" >&2
    case "$LAST_PID" in
        '') LAST_PID_SUMMARY=missing ;;
        *[!0-9]*) LAST_PID_SUMMARY=ambiguous ;;
        *) LAST_PID_SUMMARY=$LAST_PID ;;
    esac
    LAST_SERVICE_RECORD=false
    LAST_SERVICE_FOREGROUND=false
    if printf '%s\n' "$LAST_SERVICE_DUMP" | \
        rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService'
    then
        LAST_SERVICE_RECORD=true
    fi
    if printf '%s\n' "$LAST_SERVICE_DUMP" | rg -q 'isForeground=true'; then
        LAST_SERVICE_FOREGROUND=true
    fi
    printf 'helmet_service_pid=%s helmet_service_foreground=%s helmet_service_record=%s\n' \
        "$LAST_PID_SUMMARY" "$LAST_SERVICE_FOREGROUND" "$LAST_SERVICE_RECORD" >&2
    return 1
}

clear_hardware_selftest_result() {
    adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" rm -f \
        "$HARDWARE_SELFTEST_RESULT" "$HARDWARE_SELFTEST_STALE_RESULT" \
        "$HARDWARE_SELFTEST_NEW_RESULT" "$HARDWARE_SELFTEST_BACKUP_RESULT" \
        "$HARDWARE_RECOVERY_RESULT" "$HARDWARE_RECOVERY_STALE_RESULT" \
        "$HARDWARE_RECOVERY_NEW_RESULT" "$HARDWARE_RECOVERY_BACKUP_RESULT" \
        >/dev/null 2>&1
}

clear_real_hardware_mode_result() {
    adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" rm -f \
        "$REAL_HARDWARE_MODE_RESULT" "$REAL_HARDWARE_MODE_STALE_RESULT" \
        "$REAL_HARDWARE_MODE_NEW_RESULT" >/dev/null 2>&1 || true
}

clear_hardware_recovery_result() {
    adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" rm -f \
        "$HARDWARE_RECOVERY_RESULT" "$HARDWARE_RECOVERY_STALE_RESULT" \
        "$HARDWARE_RECOVERY_NEW_RESULT" "$HARDWARE_RECOVERY_BACKUP_RESULT" \
        >/dev/null 2>&1
}

stop_hardware_selftest() {
    adb -s "$ADB_SERIAL" shell am stopservice --user 0 \
        -n "$HARDWARE_SELFTEST_COMPONENT" >/dev/null 2>&1 || true

    SELFTEST_STOP_ATTEMPT=0
    while [ "$SELFTEST_STOP_ATTEMPT" -lt 40 ]; do
        SELFTEST_STOP_ATTEMPT=$((SELFTEST_STOP_ATTEMPT + 1))
        SELFTEST_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
            "$HARDWARE_SELFTEST_COMPONENT" 2>/dev/null | tr -d '\r' || true)
        if ! printf '%s\n' "$SELFTEST_DUMP" | \
            rg -q 'com\.example\.helmet/\.debug\.HardwareSelfTestService'
        then
            return 0
        fi
        sleep 0.25
    done
    echo "ERROR: debug hardware self-test service did not stop" >&2
    return 1
}

read_process_uid() {
    PROCESS_PID=$1
    case "$PROCESS_PID" in
        ''|*[!0-9]*)
            return 1
            ;;
    esac
    adb -s "$ADB_SERIAL" shell cat "/proc/$PROCESS_PID/status" 2>/dev/null | \
        tr -d '\r' | sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n 1
}

read_process_cmdline() {
    PROCESS_PID=$1
    case "$PROCESS_PID" in
        ''|*[!0-9]*)
            return 1
            ;;
    esac
    adb -s "$ADB_SERIAL" shell cat "/proc/$PROCESS_PID/cmdline" 2>/dev/null | \
        tr '\000' '\n' | head -n 1 | tr -d '\r'
}

wait_for_stable_foreground_main_process() {
    FOREGROUND_STABLE_REQUIRED=4
    FOREGROUND_STABLE_LIMIT=80
    FOREGROUND_STABLE_COUNT=0
    FOREGROUND_STABLE_ATTEMPT=0
    FOREGROUND_STABLE_SIGNATURE=
    STABLE_MAIN_PID=
    STABLE_MAIN_UID=
    STABLE_MAIN_COMMAND=

    while [ "$FOREGROUND_STABLE_ATTEMPT" -lt "$FOREGROUND_STABLE_LIMIT" ]; do
        FOREGROUND_STABLE_ATTEMPT=$((FOREGROUND_STABLE_ATTEMPT + 1))
        FOREGROUND_SAMPLE_VALID=false
        FOREGROUND_SAMPLE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
            2>/dev/null | tr -d '\r' || true)
        case "$FOREGROUND_SAMPLE_PID" in
            ''|*[!0-9]*) ;;
            *)
                FOREGROUND_SAMPLE_UID=$(read_process_uid "$FOREGROUND_SAMPLE_PID" || true)
                FOREGROUND_SAMPLE_COMMAND=$(read_process_cmdline "$FOREGROUND_SAMPLE_PID" || true)
                FOREGROUND_SAMPLE_SERVICE=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
                    "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
                case "$FOREGROUND_SAMPLE_UID" in
                    ''|*[!0-9]*) ;;
                    *)
                        if [ "$FOREGROUND_SAMPLE_COMMAND" = "$APP_PACKAGE" ] && \
                            printf '%s\n' "$FOREGROUND_SAMPLE_SERVICE" | \
                                rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService' && \
                            printf '%s\n' "$FOREGROUND_SAMPLE_SERVICE" | \
                                rg -q 'isForeground=true'
                        then
                            FOREGROUND_SAMPLE_VALID=true
                        fi
                        ;;
                esac
                ;;
        esac

        if [ "$FOREGROUND_SAMPLE_VALID" = true ]; then
            FOREGROUND_SAMPLE_SIGNATURE="$FOREGROUND_SAMPLE_PID:$FOREGROUND_SAMPLE_UID:$FOREGROUND_SAMPLE_COMMAND"
            if [ "$FOREGROUND_SAMPLE_SIGNATURE" = "$FOREGROUND_STABLE_SIGNATURE" ]; then
                FOREGROUND_STABLE_COUNT=$((FOREGROUND_STABLE_COUNT + 1))
            else
                FOREGROUND_STABLE_SIGNATURE=$FOREGROUND_SAMPLE_SIGNATURE
                FOREGROUND_STABLE_COUNT=1
            fi
            if [ "$FOREGROUND_STABLE_COUNT" -ge "$FOREGROUND_STABLE_REQUIRED" ]; then
                STABLE_MAIN_PID=$FOREGROUND_SAMPLE_PID
                STABLE_MAIN_UID=$FOREGROUND_SAMPLE_UID
                STABLE_MAIN_COMMAND=$FOREGROUND_SAMPLE_COMMAND
                printf 'foreground_main_stable=true pid=%s uid=%s samples=%s\n' \
                    "$STABLE_MAIN_PID" "$STABLE_MAIN_UID" "$FOREGROUND_STABLE_COUNT"
                return 0
            fi
        else
            FOREGROUND_STABLE_SIGNATURE=
            FOREGROUND_STABLE_COUNT=0
        fi
        sleep 0.25
    done
    return 1
}

validate_hardware_process_restart_directive() {
    EXPECTED_NONCE=$1
    DIRECTIVE_FILE=$2
    printf '%s\n' "$EXPECTED_NONCE" | rg -q '^[a-f0-9]{32}$' || return 1
    [ -f "$DIRECTIVE_FILE" ] || return 1
    [ "$(wc -l <"$DIRECTIVE_FILE" | tr -d ' ')" -eq 5 ] || return 1
    rg -q '^schema=1$' "$DIRECTIVE_FILE" && \
        rg -q "^nonce=$EXPECTED_NONCE$" "$DIRECTIVE_FILE" && \
        rg -q '^status=RESTART_REQUIRED$' "$DIRECTIVE_FILE" && \
        rg -q '^registered_callbacks=-1$' "$DIRECTIVE_FILE" && \
        rg -q '^error_code=(EXECUTOR_DRAIN_FAILED|PROCESS_RESTART_REQUIRED)$' \
            "$DIRECTIVE_FILE"
}

mark_hardware_process_restart_required() {
    EXPECTED_NONCE=$1
    DIRECTIVE_FILE=$2
    if validate_hardware_process_restart_directive "$EXPECTED_NONCE" "$DIRECTIVE_FILE"; then
        HARDWARE_PROCESS_RESTART_REQUIRED=true
        return 0
    fi
    return 1
}

detect_hardware_process_restart_directive() {
    EXPECTED_NONCE=$1
    DIRECTIVE_COPY=$2
    [ -n "$EXPECTED_NONCE" ] || return 1
    if adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" cat \
        "$HARDWARE_RECOVERY_RESULT" >"$DIRECTIVE_COPY.raw" 2>/dev/null
    then
        tr -d '\r' <"$DIRECTIVE_COPY.raw" >"$DIRECTIVE_COPY"
        if mark_hardware_process_restart_required "$EXPECTED_NONCE" "$DIRECTIVE_COPY"; then
            return 0
        fi
    fi
    return 1
}

wait_for_hardware_selftest_resume() {
    EXPECTED_NONCE=$1
    RESULT_COPY=$2
    [ -n "$EXPECTED_NONCE" ] || return 1
    RESUME_ATTEMPT=0
    while [ "$RESUME_ATTEMPT" -lt 240 ]; do
        RESUME_ATTEMPT=$((RESUME_ATTEMPT + 1))
        if adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" cat \
            "$HARDWARE_SELFTEST_RESULT" >"$RESULT_COPY.raw" 2>/dev/null
        then
            tr -d '\r' <"$RESULT_COPY.raw" >"$RESULT_COPY"
            if rg -q "^nonce=$EXPECTED_NONCE$" "$RESULT_COPY" && \
                rg -q '^registered_callbacks_after_resume=1$' "$RESULT_COPY" && \
                rg -q '^error_type=[A-Z_]+$' "$RESULT_COPY"
            then
                return 0
            fi
        fi
        if detect_hardware_process_restart_directive "$EXPECTED_NONCE" \
            "$RESULT_COPY.recovery"
        then
            return 1
        fi
        sleep 0.25
    done
    return 1
}

recover_hardware_maintenance() {
    RECOVERY_RUN_LIMIT=2
    RECOVERY_RUN=0
    while [ "$RECOVERY_RUN" -lt "$RECOVERY_RUN_LIMIT" ]; do
        RECOVERY_RUN=$((RECOVERY_RUN + 1))
        stop_hardware_selftest || true
        clear_hardware_recovery_result || true
        RECOVERY_NONCE=$(LC_ALL=C od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
        printf '%s\n' "$RECOVERY_NONCE" | rg -q '^[a-f0-9]{32}$' || continue
        RECOVERY_START="$TEST_ROOT/hardware-recovery-$RECOVERY_RUN-start.log"
        if ! adb -s "$ADB_SERIAL" shell am start-foreground-service --user 0 \
            -n "$HARDWARE_SELFTEST_COMPONENT" --es nonce "$RECOVERY_NONCE" \
            --ez recovery_only true >"$RECOVERY_START" 2>&1
        then
            continue
        fi
        RECOVERY_COPY="$TEST_ROOT/hardware-recovery-$RECOVERY_RUN.properties"
        RECOVERY_READY=false
        RECOVERY_ATTEMPT=0
        while [ "$RECOVERY_ATTEMPT" -lt 240 ]; do
            RECOVERY_ATTEMPT=$((RECOVERY_ATTEMPT + 1))
            if adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" cat \
                "$HARDWARE_RECOVERY_RESULT" >"$RECOVERY_COPY.raw" 2>/dev/null
            then
                tr -d '\r' <"$RECOVERY_COPY.raw" >"$RECOVERY_COPY"
                if rg -q "^nonce=$RECOVERY_NONCE$" "$RECOVERY_COPY" && \
                    rg -q '^error_code=[A-Z_]+$' "$RECOVERY_COPY"
                then
                    RECOVERY_READY=true
                    break
                fi
            fi
            sleep 0.25
        done
        stop_hardware_selftest || true
        if [ "$RECOVERY_READY" = true ] && \
            mark_hardware_process_restart_required "$RECOVERY_NONCE" "$RECOVERY_COPY"
        then
            return 1
        fi
        if [ "$RECOVERY_READY" = true ] && \
            [ "$(wc -l <"$RECOVERY_COPY" | tr -d ' ')" -eq 5 ] && \
            rg -q '^schema=1$' "$RECOVERY_COPY" && \
            rg -q '^status=PASS$' "$RECOVERY_COPY" && \
            rg -q '^registered_callbacks=1$' "$RECOVERY_COPY" && \
            rg -q '^error_code=NONE$' "$RECOVERY_COPY"
        then
            printf 'hardware_maintenance_recovered=true recovery_attempt=%s\n' "$RECOVERY_RUN"
            return 0
        fi
    done
    echo "ERROR: independent hardware maintenance recovery failed" >&2
    return 1
}

recover_hardware_by_process_restart() {
    OLD_MAIN_PID=${HARDWARE_SELFTEST_MAIN_PID:-}
    if [ -z "$OLD_MAIN_PID" ]; then
        OLD_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
            2>/dev/null | tr -d '\r' || true)
    fi
    EXPECTED_UID=${HARDWARE_SELFTEST_APP_UID:-}
    VERIFIED_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
        2>/dev/null | tr -d '\r' || true)
    VERIFIED_MAIN_UID=$(read_process_uid "$VERIFIED_MAIN_PID" || true)
    VERIFIED_MAIN_COMMAND=$(read_process_cmdline "$VERIFIED_MAIN_PID" || true)
    OLD_HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
        2>/dev/null | tr -d '\r' || true)
    VERIFIED_HARDWARE_UID=$(read_process_uid "$OLD_HARDWARE_PID" || true)
    VERIFIED_HARDWARE_COMMAND=$(read_process_cmdline "$OLD_HARDWARE_PID" || true)
    if [ -z "$EXPECTED_UID" ] || [ "$VERIFIED_MAIN_PID" != "$OLD_MAIN_PID" ] || \
        [ "$VERIFIED_MAIN_UID" != "$EXPECTED_UID" ] || \
        [ "$VERIFIED_MAIN_COMMAND" != "$APP_PACKAGE" ] || \
        [ "$OLD_HARDWARE_PID" = "$VERIFIED_MAIN_PID" ] || \
        [ "$VERIFIED_HARDWARE_UID" != "$EXPECTED_UID" ] || \
        [ "$VERIFIED_HARDWARE_COMMAND" != "$HARDWARE_PROCESS" ]
    then
        echo "ERROR: refusing process recovery for an unverified application runtime" >&2
        return 1
    fi
    adb -s "$ADB_SERIAL" shell am force-stop --user 0 "$APP_PACKAGE" \
        >/dev/null 2>&1 || return 1

    FORCE_STOPPED=false
    FORCE_STOP_ATTEMPT=0
    while [ "$FORCE_STOP_ATTEMPT" -lt 40 ]; do
        FORCE_STOP_ATTEMPT=$((FORCE_STOP_ATTEMPT + 1))
        STOPPED_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
            2>/dev/null | tr -d '\r' || true)
        STOPPED_HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
            2>/dev/null | tr -d '\r' || true)
        STOPPED_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
            "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
        if [ -z "$STOPPED_MAIN_PID" ] && [ -z "$STOPPED_HARDWARE_PID" ] && \
            ! adb -s "$ADB_SERIAL" shell test -d "/proc/$OLD_MAIN_PID" && \
            ! adb -s "$ADB_SERIAL" shell test -d "/proc/$OLD_HARDWARE_PID" && \
            ! printf '%s\n' "$STOPPED_SERVICE_DUMP" | \
                rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService'
        then
            FORCE_STOPPED=true
            break
        fi
        sleep 0.25
    done
    [ "$FORCE_STOPPED" = true ] || return 1
    restore_helmet_service || return 1
    NEW_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
        2>/dev/null | tr -d '\r' || true)
    case "$NEW_MAIN_PID" in
        ''|*[!0-9]*) return 1 ;;
    esac
    if [ -n "$OLD_MAIN_PID" ] && [ "$NEW_MAIN_PID" = "$OLD_MAIN_PID" ]; then
        return 1
    fi
    HARDWARE_PROCESS_RESTART_REQUIRED=false
    recover_hardware_maintenance false || return 1
    FINAL_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
        2>/dev/null | tr -d '\r' || true)
    FINAL_HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
        2>/dev/null | tr -d '\r' || true)
    FINAL_MAIN_UID=$(read_process_uid "$FINAL_MAIN_PID" || true)
    FINAL_HARDWARE_UID=$(read_process_uid "$FINAL_HARDWARE_PID" || true)
    FINAL_HARDWARE_COMMAND=$(read_process_cmdline "$FINAL_HARDWARE_PID" || true)
    FINAL_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
        "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
    FINAL_PROVIDER_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity providers \
        2>/dev/null | tr -d '\r' || true)
    if [ "$FINAL_MAIN_PID" != "$NEW_MAIN_PID" ] || \
        [ "$FINAL_MAIN_UID" != "$EXPECTED_UID" ] || \
        [ "$FINAL_HARDWARE_UID" != "$EXPECTED_UID" ] || \
        [ "$FINAL_HARDWARE_PID" = "$OLD_HARDWARE_PID" ] || \
        [ "$FINAL_HARDWARE_PID" = "$FINAL_MAIN_PID" ] || \
        [ "$FINAL_HARDWARE_COMMAND" != "$HARDWARE_PROCESS" ] || \
        ! printf '%s\n' "$FINAL_SERVICE_DUMP" | rg -q 'isForeground=true' || \
        ! printf '%s\n' "$FINAL_PROVIDER_DUMP" | rg -Fq "$HARDWARE_PROVIDER_AUTHORITY"
    then
        return 1
    fi
    printf 'hardware_process_recovered=true old_main_pid=%s new_main_pid=%s old_hardware_pid=%s new_hardware_pid=%s callbacks=1\n' \
        "$OLD_MAIN_PID" "$NEW_MAIN_PID" "$OLD_HARDWARE_PID" "$FINAL_HARDWARE_PID"
    return 0
}

verify_provider_crash_recovery() {
    EXPECTED_MAIN_PID=$1
    OLD_HARDWARE_PID=$2
    EXPECTED_UID=$3
    VERIFIED_OLD_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
        2>/dev/null | tr -d '\r' || true)
    VERIFIED_OLD_HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
        2>/dev/null | tr -d '\r' || true)
    VERIFIED_OLD_HARDWARE_UID=$(read_process_uid "$OLD_HARDWARE_PID" || true)
    VERIFIED_OLD_HARDWARE_COMMAND=$(read_process_cmdline "$OLD_HARDWARE_PID" || true)
    if [ "$VERIFIED_OLD_MAIN_PID" != "$EXPECTED_MAIN_PID" ] || \
        [ "$VERIFIED_OLD_HARDWARE_PID" != "$OLD_HARDWARE_PID" ] || \
        [ "$OLD_HARDWARE_PID" = "$EXPECTED_MAIN_PID" ] || \
        [ "$VERIFIED_OLD_HARDWARE_UID" != "$EXPECTED_UID" ] || \
        [ "$VERIFIED_OLD_HARDWARE_COMMAND" != "$HARDWARE_PROCESS" ]
    then
        echo "ERROR: refusing to kill an unverified :hardware PID" >&2
        return 1
    fi
    HARDWARE_PROVIDER_CRASH_RECOVERY_PENDING=true
    adb -s "$ADB_SERIAL" shell kill -9 "$OLD_HARDWARE_PID" >/dev/null 2>&1 || {
        echo "ERROR: failed to terminate the old :hardware process" >&2
        return 1
    }

    CRASH_RECOVERY_ATTEMPT=0
    while [ "$CRASH_RECOVERY_ATTEMPT" -lt 120 ]; do
        CRASH_RECOVERY_ATTEMPT=$((CRASH_RECOVERY_ATTEMPT + 1))
        CURRENT_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
            2>/dev/null | tr -d '\r' || true)
        CURRENT_HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
            2>/dev/null | tr -d '\r' || true)
        case "$CURRENT_MAIN_PID:$CURRENT_HARDWARE_PID" in
            *[!0-9:]*|:*|*:)
                sleep 0.25
                continue
                ;;
        esac
        if [ "$CURRENT_MAIN_PID" != "$EXPECTED_MAIN_PID" ] || \
            [ "$CURRENT_HARDWARE_PID" = "$OLD_HARDWARE_PID" ] || \
            [ "$CURRENT_HARDWARE_PID" = "$CURRENT_MAIN_PID" ]
        then
            sleep 0.25
            continue
        fi
        CURRENT_HARDWARE_UID=$(read_process_uid "$CURRENT_HARDWARE_PID" || true)
        CURRENT_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
            "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
        CURRENT_PROVIDER_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity providers \
            2>/dev/null | tr -d '\r' || true)
        if [ "$CURRENT_HARDWARE_UID" = "$EXPECTED_UID" ] && \
            printf '%s\n' "$CURRENT_SERVICE_DUMP" | \
                rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService' && \
            printf '%s\n' "$CURRENT_SERVICE_DUMP" | rg -q 'isForeground=true' && \
            printf '%s\n' "$CURRENT_PROVIDER_DUMP" | \
                rg -Fq "$HARDWARE_PROVIDER_AUTHORITY" && \
            printf '%s\n' "$CURRENT_PROVIDER_DUMP" | rg -q 'HelmetHardwareProvider'
        then
            if ! recover_hardware_maintenance false; then
                echo "ERROR: replacement provider did not regain the production callback" >&2
                return 1
            fi
            VERIFIED_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
                2>/dev/null | tr -d '\r' || true)
            VERIFIED_HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
                2>/dev/null | tr -d '\r' || true)
            VERIFIED_HARDWARE_UID=$(read_process_uid "$VERIFIED_HARDWARE_PID" || true)
            VERIFIED_HARDWARE_COMMAND=$(read_process_cmdline "$VERIFIED_HARDWARE_PID" || true)
            VERIFIED_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
                "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
            if [ "$VERIFIED_MAIN_PID" != "$EXPECTED_MAIN_PID" ] || \
                [ "$VERIFIED_HARDWARE_PID" = "$OLD_HARDWARE_PID" ] || \
                [ "$VERIFIED_HARDWARE_PID" = "$VERIFIED_MAIN_PID" ] || \
                [ "$VERIFIED_HARDWARE_UID" != "$EXPECTED_UID" ] || \
                [ "$VERIFIED_HARDWARE_COMMAND" != "$HARDWARE_PROCESS" ] || \
                ! printf '%s\n' "$VERIFIED_SERVICE_DUMP" | rg -q 'isForeground=true'
            then
                echo "ERROR: callback verification changed the foreground main process" >&2
                return 1
            fi
            HARDWARE_PROVIDER_CRASH_RECOVERY_PENDING=false
            printf 'provider_crash_recovered=true main_pid=%s old_hardware_pid=%s new_hardware_pid=%s application_uid=%s\n' \
                "$VERIFIED_MAIN_PID" "$OLD_HARDWARE_PID" "$VERIFIED_HARDWARE_PID" \
                "$VERIFIED_HARDWARE_UID"
            return 0
        fi
        sleep 0.25
    done
    echo "ERROR: provider crash did not recover with an unchanged foreground main process" >&2
    return 1
}

cleanup() {
    EXIT_STATUS=$?
    trap - EXIT HUP INT TERM

    if [ "$HARDWARE_SELFTEST_ACTIVE" = true ]; then
        if ! stop_hardware_selftest && [ "$EXIT_STATUS" -eq 0 ]; then
            EXIT_STATUS=1
        fi
        SELFTEST_SAFE=false
        detect_hardware_process_restart_directive "$HARDWARE_SELFTEST_NONCE" \
            "$TEST_ROOT/cleanup-process-recovery-directive.properties" || true
        if [ "$HARDWARE_PROCESS_RESTART_REQUIRED" = true ]; then
            EXIT_STATUS=1
            if recover_hardware_by_process_restart; then
                SELFTEST_SAFE=true
            fi
        elif [ "$HARDWARE_PROVIDER_CRASH_RECOVERY_PENDING" != true ] && \
            wait_for_hardware_selftest_resume "$HARDWARE_SELFTEST_NONCE" \
            "$TEST_ROOT/cleanup-hardware-selftest.properties"
        then
            SELFTEST_SAFE=true
        else
            echo "ERROR: hardware self-test did not durably confirm production resume" >&2
            EXIT_STATUS=1
            if recover_hardware_maintenance; then
                SELFTEST_SAFE=true
            elif [ "$HARDWARE_PROCESS_RESTART_REQUIRED" = true ] && \
                recover_hardware_by_process_restart
            then
                SELFTEST_SAFE=true
            fi
        fi
        if [ "$SELFTEST_SAFE" = true ]; then
            clear_hardware_selftest_result || true
        fi
        HARDWARE_SELFTEST_ACTIVE=false
    fi

    for PACKAGE in $TEST_PACKAGES; do
        remove_test_package "$PACKAGE"
    done
    clear_real_hardware_mode_result

    if [ "$REAL_HARDWARE_MODE_RESTART_REQUIRED" = true ]; then
        if ! restart_helmet_application_in_real_hardware_mode && \
            [ "$EXIT_STATUS" -eq 0 ]
        then
            EXIT_STATUS=1
        fi
    fi

    if [ "$SERVICE_RECOVERY_REQUIRED" = true ] && ! restore_helmet_service; then
        if [ "$EXIT_STATUS" -eq 0 ]; then
            EXIT_STATUS=1
        fi
    fi

    case "$TEST_ROOT" in
        "${TMPDIR:-/tmp}"/helmet-connected-software.*)
            rm -rf -- "$TEST_ROOT"
            ;;
        *)
            echo "WARNING: refusing to remove unexpected test directory: $TEST_ROOT" >&2
            ;;
    esac
    exit "$EXIT_STATUS"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

parse_and_record_result() {
    LABEL=$1
    OUTPUT=$2
    OK_COUNTS=$(sed -n \
        -e 's/^OK (\([1-9][0-9]*\) test)$/\1/p' \
        -e 's/^OK (\([1-9][0-9]*\) tests)$/\1/p' \
        "$OUTPUT")
    OK_LINE_COUNT=$(printf '%s\n' "$OK_COUNTS" | sed '/^$/d' | wc -l | tr -d ' ')
    if [ "$OK_LINE_COUNT" -ne 1 ]; then
        echo "ERROR: $LABEL did not produce exactly one OK (N tests) result" >&2
        return 1
    fi
    case "$OK_COUNTS" in
        ''|*[!0-9]*)
            echo "ERROR: $LABEL produced an invalid test count" >&2
            return 1
            ;;
    esac
    if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$OUTPUT"; then
        echo "ERROR: $LABEL reported an instrumentation failure" >&2
        return 1
    fi

    printf '%s\t%s\n' "$LABEL" "$OK_COUNTS" >>"$SUMMARY_FILE"
    TOTAL_TESTS=$((TOTAL_TESTS + OK_COUNTS))
    printf 'PASS: %s (%s tests)\n' "$LABEL" "$OK_COUNTS"
}

run_instrumentation() {
    LABEL=$1
    TEST_PACKAGE=$2
    TEST_APK=$3
    SELECTOR_KIND=$4
    SELECTOR_VALUE=$5
    shift 5
    RAW_OUTPUT="$TEST_ROOT/$LABEL.raw.log"
    OUTPUT="$TEST_ROOT/$LABEL.log"

    remove_test_package "$TEST_PACKAGE"
    if ! adb -s "$ADB_SERIAL" install -r -t "$TEST_APK" >/dev/null; then
        remove_test_package "$TEST_PACKAGE"
        echo "ERROR: failed to install $LABEL test APK" >&2
        return 1
    fi

    if [ "$LABEL" = data-local ] && [ "$SELECTOR_KIND" = package ]; then
        REMOTE_PREFIX=/data/local/tmp/helmet-connected-data-local
        REMOTE_OUTPUT=$REMOTE_PREFIX.out
        REMOTE_STATUS=$REMOTE_PREFIX.status
        adb -s "$ADB_SERIAL" shell rm -f "$REMOTE_OUTPUT" "$REMOTE_STATUS" >/dev/null
        # This suite contains a database lineage test that can be silent for nearly one minute.
        # Some vendor ADB daemons close an otherwise healthy long shell session during that gap,
        # while instrumentation continues. Run it on-board and poll with bounded short sessions.
        adb -s "$ADB_SERIAL" shell \
            "(am instrument -w -r -e package '$SELECTOR_VALUE' '$TEST_PACKAGE/$TEST_RUNNER' > '$REMOTE_OUTPUT' 2>&1; echo \$? > '$REMOTE_STATUS') </dev/null >/dev/null 2>&1 &" \
            >/dev/null 2>&1 || true
        INSTRUMENT_STATUS=
        POLL_ATTEMPT=0
        while [ "$POLL_ATTEMPT" -lt 1800 ]; do
            POLL_ATTEMPT=$((POLL_ATTEMPT + 1))
            REMOTE_RESULT=$(adb -s "$ADB_SERIAL" shell \
                "test -f '$REMOTE_STATUS' && cat '$REMOTE_STATUS' || true" \
                2>/dev/null | tr -d '\r' || true)
            case "$REMOTE_RESULT" in
                0|1|2|3|4|5|6|7|8|9|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])
                    INSTRUMENT_STATUS=$REMOTE_RESULT
                    break
                    ;;
            esac
            sleep 0.25
        done
        if [ -z "$INSTRUMENT_STATUS" ]; then
            INSTRUMENT_STATUS=124
        fi
        adb -s "$ADB_SERIAL" shell cat "$REMOTE_OUTPUT" >"$RAW_OUTPUT" 2>/dev/null || true
        adb -s "$ADB_SERIAL" shell rm -f "$REMOTE_OUTPUT" "$REMOTE_STATUS" >/dev/null 2>&1 || true
    elif [ "$SELECTOR_KIND" = package ]; then
        if adb -s "$ADB_SERIAL" shell am instrument -w -r \
            -e package "$SELECTOR_VALUE" \
            "$@" \
            "$TEST_PACKAGE/$TEST_RUNNER" >"$RAW_OUTPUT" 2>&1
        then
            INSTRUMENT_STATUS=0
        else
            INSTRUMENT_STATUS=$?
        fi
    elif [ "$SELECTOR_KIND" = class ]; then
        if adb -s "$ADB_SERIAL" shell am instrument -w -r \
            -e class "$SELECTOR_VALUE" \
            "$@" \
            "$TEST_PACKAGE/$TEST_RUNNER" >"$RAW_OUTPUT" 2>&1
        then
            INSTRUMENT_STATUS=0
        else
            INSTRUMENT_STATUS=$?
        fi
    else
        remove_test_package "$TEST_PACKAGE"
        echo "ERROR: unsupported selector kind: $SELECTOR_KIND" >&2
        return 1
    fi

    tr -d '\r' <"$RAW_OUTPUT" >"$OUTPUT"
    cat "$OUTPUT"
    remove_test_package "$TEST_PACKAGE"
    if [ "$INSTRUMENT_STATUS" -ne 0 ]; then
        echo "ERROR: $LABEL instrumentation exited with $INSTRUMENT_STATUS" >&2
        return "$INSTRUMENT_STATUS"
    fi
    parse_and_record_result "$LABEL" "$OUTPUT"
}

require_real_hardware_mode_line() {
    EXPECTED_LINE=$1
    RESULT_FILE=$2
    MATCH_COUNT=$(rg -c "^${EXPECTED_LINE}$" "$RESULT_FILE" || true)
    : "${MATCH_COUNT:=0}"
    if [ "$MATCH_COUNT" -ne 1 ]; then
        echo "ERROR: real hardware mode result missing: $EXPECTED_LINE" >&2
        return 1
    fi
}

run_real_hardware_mode_preparation() {
    LABEL=app-real-hardware-mode-preparation
    RESULT_COPY="$TEST_ROOT/$LABEL.properties"
    PREPARATION_NONCE=$(LC_ALL=C od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    printf '%s\n' "$PREPARATION_NONCE" | rg -q '^[a-f0-9]{32}$' || {
        echo "ERROR: failed to generate real hardware mode preparation nonce" >&2
        return 1
    }

    clear_real_hardware_mode_result
    # The production API can commit before the runner reports a later test/evidence failure.
    # From this point the EXIT trap must rebuild the service so it cannot keep the old gateway.
    REAL_HARDWARE_MODE_RESTART_REQUIRED=true
    run_instrumentation "$LABEL" \
        "$APP_TEST_PACKAGE" "$APP_TEST_APK" class "$REAL_HARDWARE_MODE_SELECTOR" \
        -e realHardwareModeNonce "$PREPARATION_NONCE" \
        -e realHardwareModeConfirmation "$REAL_HARDWARE_MODE_CONFIRMATION" \
        -e realHardwareDevicePath "$HARDWARE_DEVICE_PATH" \
        -e realHardwareBaudRate "$HARDWARE_BAUD_RATE"

    if ! adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" cat \
        "$REAL_HARDWARE_MODE_RESULT" >"$RESULT_COPY.raw" 2>/dev/null
    then
        echo "ERROR: real hardware mode preparation produced no atomic result" >&2
        return 1
    fi
    tr -d '\r' <"$RESULT_COPY.raw" >"$RESULT_COPY"
    RESULT_LINE_COUNT=$(wc -l <"$RESULT_COPY" | tr -d ' ')
    if [ "$RESULT_LINE_COUNT" -ne 9 ]; then
        echo "ERROR: real hardware mode result has an unexpected field count" >&2
        return 1
    fi
    RESULT_KEYS=$(cut -d= -f1 "$RESULT_COPY" | LC_ALL=C sort)
    EXPECTED_KEYS=$(printf '%s\n' \
        changed hardware_baud_rate hardware_device_path nonce previous_revision \
        revision schema simulator_enabled status | LC_ALL=C sort)
    if [ "$RESULT_KEYS" != "$EXPECTED_KEYS" ]; then
        echo "ERROR: real hardware mode result contains unexpected fields" >&2
        return 1
    fi
    require_real_hardware_mode_line schema=1 "$RESULT_COPY"
    require_real_hardware_mode_line "nonce=$PREPARATION_NONCE" "$RESULT_COPY"
    require_real_hardware_mode_line status=PASS "$RESULT_COPY"
    require_real_hardware_mode_line "hardware_device_path=$HARDWARE_DEVICE_PATH" "$RESULT_COPY"
    require_real_hardware_mode_line "hardware_baud_rate=$HARDWARE_BAUD_RATE" "$RESULT_COPY"
    require_real_hardware_mode_line simulator_enabled=false "$RESULT_COPY"
    rg -q '^changed=(true|false)$' "$RESULT_COPY" || {
        echo "ERROR: real hardware mode result has an invalid changed field" >&2
        return 1
    }
    rg -q '^previous_revision=[1-9][0-9]*$' "$RESULT_COPY" || {
        echo "ERROR: real hardware mode result has an invalid previous revision" >&2
        return 1
    }
    rg -q '^revision=[1-9][0-9]*$' "$RESULT_COPY" || {
        echo "ERROR: real hardware mode result has an invalid revision" >&2
        return 1
    }
    cat "$RESULT_COPY"
    clear_real_hardware_mode_result
}

capture_and_force_stop_hardware_mode_generation() {
    if [ "$REAL_HARDWARE_MODE_FORCE_STOP_COMPLETED" = true ]; then
        return 0
    fi
    if [ "$REAL_HARDWARE_MODE_FORCE_STOP_REQUESTED" = true ]; then
        if wait_for_hardware_mode_generation_exit; then
            REAL_HARDWARE_MODE_FORCE_STOP_COMPLETED=true
            return 0
        fi
        echo "ERROR: force-stop was already requested; refusing to repeat it" >&2
        return 1
    fi
    DEVICE_UID=$(adb -s "$ADB_SERIAL" shell id -u 2>/dev/null | tr -d '\r' || true)
    if [ "$DEVICE_UID" != 0 ]; then
        echo "ERROR: controlled hardware mode restart requires a root ADB shell" >&2
        return 1
    fi
    if adb -s "$ADB_SERIAL" shell pm path "$APP_TEST_PACKAGE" 2>/dev/null | \
        tr -d '\r' | rg -q '^package:'
    then
        echo "ERROR: app instrumentation package must be removed before hardware mode restart" >&2
        return 1
    fi
    if ! wait_for_stable_foreground_main_process; then
        echo "ERROR: cannot identify a stable foreground application before hardware mode restart" >&2
        return 1
    fi

    REAL_HARDWARE_MODE_OLD_MAIN_PID=$STABLE_MAIN_PID
    REAL_HARDWARE_MODE_EXPECTED_UID=$STABLE_MAIN_UID
    VERIFIED_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
        2>/dev/null | tr -d '\r' || true)
    VERIFIED_MAIN_UID=$(read_process_uid "$VERIFIED_MAIN_PID" || true)
    VERIFIED_MAIN_COMMAND=$(read_process_cmdline "$VERIFIED_MAIN_PID" || true)
    if [ "$VERIFIED_MAIN_PID" != "$REAL_HARDWARE_MODE_OLD_MAIN_PID" ] || \
        [ "$VERIFIED_MAIN_UID" != "$REAL_HARDWARE_MODE_EXPECTED_UID" ] || \
        [ "$VERIFIED_MAIN_COMMAND" != "$APP_PACKAGE" ]
    then
        echo "ERROR: refusing hardware mode restart for an unverified main process" >&2
        return 1
    fi

    REAL_HARDWARE_MODE_OLD_PROVIDER_PID=$(adb -s "$ADB_SERIAL" shell pidof \
        "$HARDWARE_PROCESS" 2>/dev/null | tr -d '\r' || true)
    case "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" in
        '') ;;
        *[!0-9]*)
            echo "ERROR: refusing hardware mode restart for an ambiguous provider process" >&2
            return 1
            ;;
        *)
            VERIFIED_PROVIDER_UID=$(read_process_uid \
                "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" || true)
            VERIFIED_PROVIDER_COMMAND=$(read_process_cmdline \
                "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" || true)
            if [ "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" = "$VERIFIED_MAIN_PID" ] || \
                [ "$VERIFIED_PROVIDER_UID" != "$REAL_HARDWARE_MODE_EXPECTED_UID" ] || \
                [ "$VERIFIED_PROVIDER_COMMAND" != "$HARDWARE_PROCESS" ]
            then
                echo "ERROR: refusing hardware mode restart for an unverified provider process" >&2
                return 1
            fi
            ;;
    esac

    FORCE_STOP_OUTPUT="$TEST_ROOT/hardware-mode-force-stop.log"
    # Mark the one allowed request before crossing the host/device boundary. If ADB is
    # interrupted after the device acts, cleanup observes this state and never issues a second
    # force-stop. The device-side shell performs the final identity check immediately before the
    # exact package-level force-stop.
    REAL_HARDWARE_MODE_FORCE_STOP_REQUESTED=true
    if adb -s "$ADB_SERIAL" shell sh -s -- \
        "$REAL_HARDWARE_MODE_OLD_MAIN_PID" \
        "$REAL_HARDWARE_MODE_EXPECTED_UID" \
        "${REAL_HARDWARE_MODE_OLD_PROVIDER_PID:-0}" \
        >"$FORCE_STOP_OUTPUT.raw" 2>&1 <<'HARDWARE_MODE_FORCE_STOP'
set -eu
OLD_MAIN_PID=$1
EXPECTED_UID=$2
OLD_PROVIDER_PID=$3

read_uid() {
    sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
        "/proc/$1/status" | head -n 1
}

read_command() {
    tr '\000' '\n' <"/proc/$1/cmdline" | head -n 1
}

[ "$(pidof com.example.helmet 2>/dev/null || true)" = "$OLD_MAIN_PID" ]
[ "$(read_uid "$OLD_MAIN_PID")" = "$EXPECTED_UID" ]
[ "$(read_command "$OLD_MAIN_PID")" = com.example.helmet ]
CURRENT_PROVIDER_PID=$(pidof com.example.helmet:hardware 2>/dev/null || true)
if [ "$OLD_PROVIDER_PID" = 0 ]; then
    [ -z "$CURRENT_PROVIDER_PID" ]
else
    [ "$CURRENT_PROVIDER_PID" = "$OLD_PROVIDER_PID" ]
    [ "$OLD_PROVIDER_PID" != "$OLD_MAIN_PID" ]
    [ "$(read_uid "$OLD_PROVIDER_PID")" = "$EXPECTED_UID" ]
    [ "$(read_command "$OLD_PROVIDER_PID")" = com.example.helmet:hardware ]
fi
# Repeat the main-generation check after provider inspection to close the earlier host-side gap.
[ "$(pidof com.example.helmet 2>/dev/null || true)" = "$OLD_MAIN_PID" ]
[ "$(read_uid "$OLD_MAIN_PID")" = "$EXPECTED_UID" ]
[ "$(read_command "$OLD_MAIN_PID")" = com.example.helmet ]
am force-stop --user 0 com.example.helmet >/dev/null
printf 'force_stop_committed=true old_main_pid=%s old_provider_pid=%s application_uid=%s\n' \
    "$OLD_MAIN_PID" "$OLD_PROVIDER_PID" "$EXPECTED_UID"
HARDWARE_MODE_FORCE_STOP
    then
        FORCE_STOP_STATUS=0
    else
        FORCE_STOP_STATUS=$?
    fi
    tr -d '\r' <"$FORCE_STOP_OUTPUT.raw" >"$FORCE_STOP_OUTPUT"
    EXPECTED_FORCE_STOP_LINE="force_stop_committed=true old_main_pid=$REAL_HARDWARE_MODE_OLD_MAIN_PID old_provider_pid=${REAL_HARDWARE_MODE_OLD_PROVIDER_PID:-0} application_uid=$REAL_HARDWARE_MODE_EXPECTED_UID"
    FORCE_STOP_LINE_COUNT=$(rg -c "^${EXPECTED_FORCE_STOP_LINE}$" \
        "$FORCE_STOP_OUTPUT" || true)
    : "${FORCE_STOP_LINE_COUNT:=0}"
    if [ "$FORCE_STOP_STATUS" -ne 0 ] || [ "$FORCE_STOP_LINE_COUNT" -ne 1 ] || \
        [ "$(wc -l <"$FORCE_STOP_OUTPUT" | tr -d ' ')" -ne 1 ]
    then
        echo "ERROR: device-side identity gate did not confirm the one allowed force-stop" >&2
        return 1
    fi
    REAL_HARDWARE_MODE_FORCE_STOP_COMPLETED=true
    printf 'hardware_mode_generation_force_stopped=true old_main_pid=%s old_provider_pid=%s application_uid=%s\n' \
        "$REAL_HARDWARE_MODE_OLD_MAIN_PID" \
        "${REAL_HARDWARE_MODE_OLD_PROVIDER_PID:-none}" \
        "$REAL_HARDWARE_MODE_EXPECTED_UID"
}

read_hardware_mode_generation_state() {
    GENERATION_PID=$1
    case "$GENERATION_PID" in
        ''|*[!0-9]*) return 1 ;;
    esac
    adb -s "$ADB_SERIAL" shell \
        "if [ -d /proc/$GENERATION_PID ]; then echo ALIVE; else echo GONE; fi" \
        2>/dev/null | tr -d '\r'
}

wait_for_hardware_mode_generation_exit() {
    GENERATION_EXIT_ATTEMPT=0
    while [ "$GENERATION_EXIT_ATTEMPT" -lt 80 ]; do
        GENERATION_EXIT_ATTEMPT=$((GENERATION_EXIT_ATTEMPT + 1))
        OLD_MAIN_STATE=$(read_hardware_mode_generation_state \
            "$REAL_HARDWARE_MODE_OLD_MAIN_PID" || true)
        if [ -n "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" ]; then
            OLD_PROVIDER_STATE=$(read_hardware_mode_generation_state \
                "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" || true)
        else
            OLD_PROVIDER_STATE=GONE
        fi
        case "$OLD_MAIN_STATE:$OLD_PROVIDER_STATE" in
            GONE:GONE)
                return 0
                ;;
            ALIVE:ALIVE|ALIVE:GONE|GONE:ALIVE)
                ;;
            *)
                echo "ERROR: failed to read the old application generation state" >&2
                return 1
                ;;
        esac
        sleep 0.25
    done
    echo "ERROR: verified pre-transition application generation did not exit" >&2
    return 1
}

wait_for_replacement_real_hardware_runtime() {
    REPLACEMENT_ATTEMPT=0
    while [ "$REPLACEMENT_ATTEMPT" -lt 80 ]; do
        REPLACEMENT_ATTEMPT=$((REPLACEMENT_ATTEMPT + 1))
        CANDIDATE_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
            2>/dev/null | tr -d '\r' || true)
        CANDIDATE_PROVIDER_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
            2>/dev/null | tr -d '\r' || true)
        case "$CANDIDATE_MAIN_PID:$CANDIDATE_PROVIDER_PID" in
            *[!0-9:]*|:*|*:)
                sleep 0.25
                continue
                ;;
        esac
        if [ "$CANDIDATE_MAIN_PID" = "$REAL_HARDWARE_MODE_OLD_MAIN_PID" ] || \
            [ "$CANDIDATE_PROVIDER_PID" = "$CANDIDATE_MAIN_PID" ] || \
            { [ -n "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" ] && \
                [ "$CANDIDATE_PROVIDER_PID" = "$REAL_HARDWARE_MODE_OLD_PROVIDER_PID" ]; }
        then
            sleep 0.25
            continue
        fi

        CANDIDATE_MAIN_UID=$(read_process_uid "$CANDIDATE_MAIN_PID" || true)
        CANDIDATE_PROVIDER_UID=$(read_process_uid "$CANDIDATE_PROVIDER_PID" || true)
        CANDIDATE_MAIN_COMMAND=$(read_process_cmdline "$CANDIDATE_MAIN_PID" || true)
        CANDIDATE_PROVIDER_COMMAND=$(read_process_cmdline "$CANDIDATE_PROVIDER_PID" || true)
        CANDIDATE_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
            "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
        CANDIDATE_PROVIDER_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity providers \
            2>/dev/null | tr -d '\r' || true)
        if [ "$CANDIDATE_MAIN_UID" = "$REAL_HARDWARE_MODE_EXPECTED_UID" ] && \
            [ "$CANDIDATE_PROVIDER_UID" = "$REAL_HARDWARE_MODE_EXPECTED_UID" ] && \
            [ "$CANDIDATE_MAIN_COMMAND" = "$APP_PACKAGE" ] && \
            [ "$CANDIDATE_PROVIDER_COMMAND" = "$HARDWARE_PROCESS" ] && \
            printf '%s\n' "$CANDIDATE_SERVICE_DUMP" | \
                rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService' && \
            printf '%s\n' "$CANDIDATE_SERVICE_DUMP" | rg -q 'isForeground=true' && \
            printf '%s\n' "$CANDIDATE_PROVIDER_DUMP" | \
                rg -Fq "$HARDWARE_PROVIDER_AUTHORITY" && \
            printf '%s\n' "$CANDIDATE_PROVIDER_DUMP" | rg -q 'HelmetHardwareProvider'
        then
            REAL_HARDWARE_MODE_NEW_MAIN_PID=$CANDIDATE_MAIN_PID
            REAL_HARDWARE_MODE_NEW_PROVIDER_PID=$CANDIDATE_PROVIDER_PID
            return 0
        fi
        sleep 0.25
    done
    echo "ERROR: replacement real-hardware application generation did not become ready" >&2
    return 1
}

restart_helmet_application_in_real_hardware_mode() {
    capture_and_force_stop_hardware_mode_generation || return 1
    wait_for_hardware_mode_generation_exit || return 1
    restore_helmet_service || return 1
    wait_for_replacement_real_hardware_runtime || return 1
    if ! recover_hardware_maintenance; then
        echo "ERROR: real-hardware HelmetService did not register exactly one provider callback" >&2
        return 1
    fi
    POST_RECOVERY_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" \
        2>/dev/null | tr -d '\r' || true)
    POST_RECOVERY_PROVIDER_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" \
        2>/dev/null | tr -d '\r' || true)
    POST_RECOVERY_SERVICE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
        "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
    if [ "$POST_RECOVERY_MAIN_PID" != "$REAL_HARDWARE_MODE_NEW_MAIN_PID" ] || \
        [ "$POST_RECOVERY_PROVIDER_PID" != "$REAL_HARDWARE_MODE_NEW_PROVIDER_PID" ] || \
        ! printf '%s\n' "$POST_RECOVERY_SERVICE_DUMP" | rg -q 'isForeground=true'
    then
        echo "ERROR: replacement application generation changed during callback verification" >&2
        return 1
    fi
    REAL_HARDWARE_MODE_RESTART_REQUIRED=false
    printf 'real_hardware_mode_ready=true main_pid=%s provider_pid=%s application_uid=%s foreground=true registered_callbacks=1 device_path=%s baud_rate=%s\n' \
        "$REAL_HARDWARE_MODE_NEW_MAIN_PID" "$REAL_HARDWARE_MODE_NEW_PROVIDER_PID" \
        "$REAL_HARDWARE_MODE_EXPECTED_UID" "$HARDWARE_DEVICE_PATH" "$HARDWARE_BAUD_RATE"
}

require_hardware_selftest_line() {
    EXPECTED_LINE=$1
    RESULT_FILE=$2
    MATCH_COUNT=$(rg -c "^${EXPECTED_LINE}$" "$RESULT_FILE" || true)
    : "${MATCH_COUNT:=0}"
    if [ "$MATCH_COUNT" -ne 1 ]; then
        echo "ERROR: debug hardware self-test result missing: $EXPECTED_LINE" >&2
        return 1
    fi
}

run_debug_hardware_selftest() {
    LABEL=app-hardware-provider-safe
    OUTPUT="$TEST_ROOT/$LABEL.log"
    START_OUTPUT="$TEST_ROOT/$LABEL-start.log"

    DEVICE_UID=$(adb -s "$ADB_SERIAL" shell id -u 2>/dev/null | tr -d '\r' || true)
    if [ "$DEVICE_UID" != 0 ]; then
        echo "ERROR: private debug hardware self-test requires a root ADB shell" >&2
        return 1
    fi
    if adb -s "$ADB_SERIAL" shell pm path "$APP_TEST_PACKAGE" 2>/dev/null | \
        tr -d '\r' | rg -q '^package:'
    then
        echo "ERROR: app instrumentation package must be removed before hardware self-test" >&2
        return 1
    fi

    if ! wait_for_stable_foreground_main_process; then
        echo "ERROR: foreground main process did not become stable before self-test" >&2
        return 1
    fi
    MAIN_PID_BEFORE_SELFTEST=$STABLE_MAIN_PID
    HARDWARE_SELFTEST_MAIN_PID=$MAIN_PID_BEFORE_SELFTEST
    HARDWARE_SELFTEST_APP_UID=$STABLE_MAIN_UID
    MAIN_COMMAND_BEFORE_SELFTEST=$STABLE_MAIN_COMMAND

    HARDWARE_SELFTEST_ACTIVE=true
    stop_hardware_selftest
    clear_hardware_selftest_result

    NONCE=$(LC_ALL=C od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    printf '%s\n' "$NONCE" | rg -q '^[a-f0-9]{32}$' || {
        echo "ERROR: failed to generate hardware self-test nonce" >&2
        return 1
    }
    HARDWARE_SELFTEST_NONCE=$NONCE

    if ! adb -s "$ADB_SERIAL" shell am start-foreground-service --user 0 \
        -n "$HARDWARE_SELFTEST_COMPONENT" --es nonce "$NONCE" >"$START_OUTPUT" 2>&1
    then
        tr -d '\r' <"$START_OUTPUT" >&2
        echo "ERROR: failed to start private debug hardware self-test" >&2
        return 1
    fi
    tr -d '\r' <"$START_OUTPUT" >"$START_OUTPUT.normalized"
    START_LINE_COUNT=$(wc -l <"$START_OUTPUT.normalized" | tr -d ' ')
    START_SUCCESS_COUNT=$(rg -c \
        '^Starting service: Intent \{ cmp=com\.example\.helmet/(\.debug\.HardwareSelfTestService|com\.example\.helmet\.debug\.HardwareSelfTestService) \(has extras\) \}$' \
        "$START_OUTPUT.normalized" || true)
    : "${START_SUCCESS_COUNT:=0}"
    if [ "$START_LINE_COUNT" -ne 1 ] || [ "$START_SUCCESS_COUNT" -ne 1 ]; then
        cat "$START_OUTPUT.normalized" >&2
        echo "ERROR: private debug hardware self-test returned an unexpected start result" >&2
        return 1
    fi
    cat "$START_OUTPUT.normalized"

    RESULT_READY=false
    RESULT_ATTEMPT=0
    while [ "$RESULT_ATTEMPT" -lt 160 ]; do
        RESULT_ATTEMPT=$((RESULT_ATTEMPT + 1))
        if adb -s "$ADB_SERIAL" shell run-as "$APP_PACKAGE" cat \
            "$HARDWARE_SELFTEST_RESULT" >"$OUTPUT.raw" 2>/dev/null
        then
            tr -d '\r' <"$OUTPUT.raw" >"$OUTPUT"
            if rg -q '^error_type=[A-Z_]+$' "$OUTPUT"; then
                RESULT_READY=true
                break
            fi
        fi
        if detect_hardware_process_restart_directive "$NONCE" \
            "$TEST_ROOT/$LABEL-recovery-directive.properties"
        then
            echo "ERROR: hardware self-test executor drain requires process recovery" >&2
            return 1
        fi
        sleep 0.25
    done
    if [ "$RESULT_READY" != true ]; then
        echo "ERROR: timed out waiting for atomic debug hardware self-test result" >&2
        return 1
    fi

    RESULT_LINE_COUNT=$(wc -l <"$OUTPUT" | tr -d ' ')
    if [ "$RESULT_LINE_COUNT" -ne 25 ]; then
        echo "ERROR: debug hardware self-test result has an unexpected field count" >&2
        return 1
    fi
    RESULT_KEYS=$(cut -d= -f1 "$OUTPUT" | LC_ALL=C sort)
    EXPECTED_KEYS=$(printf '%s\n' \
        concurrent_bytes_observed concurrent_frames_pass concurrent_read_failure \
        concurrent_writes_completed decoder_crc_errors decoder_discarded_bytes \
        decoder_length_errors decoder_version_errors error_code error_type fail \
        frames_expected frames_observed \
        generation_isolation_pass generation_new_frames generation_old_frames invalid_path_pass \
        nonce pass policy_pass pty_open_pass registered_callbacks_after_resume \
        registered_callbacks_before_pty schema status | \
        LC_ALL=C sort)
    if [ "$RESULT_KEYS" != "$EXPECTED_KEYS" ]; then
        echo "ERROR: debug hardware self-test result contains unexpected fields" >&2
        return 1
    fi
    cat "$OUTPUT"
    for EXPECTED_RESULT_LINE in \
        schema=3 \
        "nonce=$NONCE" \
        status=PASS \
        pass=5 \
        fail=0 \
        policy_pass=1 \
        pty_open_pass=1 \
        concurrent_frames_pass=1 \
        generation_isolation_pass=1 \
        invalid_path_pass=1 \
        frames_expected=96 \
        frames_observed=96 \
        concurrent_writes_completed=96 \
        concurrent_read_failure=NONE \
        decoder_crc_errors=0 \
        decoder_length_errors=0 \
        decoder_version_errors=0 \
        decoder_discarded_bytes=0 \
        registered_callbacks_before_pty=0 \
        registered_callbacks_after_resume=1 \
        generation_old_frames=0 \
        generation_new_frames=1 \
        error_code=NONE \
        error_type=NONE
    do
        require_hardware_selftest_line "$EXPECTED_RESULT_LINE" "$OUTPUT"
    done
    if ! rg -q '^concurrent_bytes_observed=[1-9][0-9]*$' "$OUTPUT"; then
        echo "ERROR: debug hardware self-test observed no concurrent frame bytes" >&2
        return 1
    fi

    HARDWARE_PID=$(adb -s "$ADB_SERIAL" shell pidof "$HARDWARE_PROCESS" 2>/dev/null | tr -d '\r' || true)
    MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)
    case "$HARDWARE_PID:$MAIN_PID" in
        *[!0-9:]*|:*|*:)
            echo "ERROR: main or :hardware process PID is missing or ambiguous" >&2
            return 1
            ;;
    esac
    MAIN_UID=$(read_process_uid "$MAIN_PID" || true)
    HARDWARE_UID=$(read_process_uid "$HARDWARE_PID" || true)
    if [ -z "$MAIN_UID" ] || [ "$MAIN_UID" != "$HARDWARE_UID" ]; then
        echo "ERROR: main and :hardware processes do not have the same application UID" >&2
        printf 'main_pid=%s main_uid=%s hardware_pid=%s hardware_uid=%s\n' \
            "$MAIN_PID" "${MAIN_UID:-missing}" "$HARDWARE_PID" \
            "${HARDWARE_UID:-missing}" >&2
        return 1
    fi
    if [ "$MAIN_PID" != "$MAIN_PID_BEFORE_SELFTEST" ]; then
        echo "ERROR: main process changed while hardware self-test was running" >&2
        return 1
    fi
    SERVICE_DUMP_AFTER_SELFTEST=$(adb -s "$ADB_SERIAL" shell dumpsys activity services \
        "$SERVICE_COMPONENT" 2>/dev/null | tr -d '\r' || true)
    if ! printf '%s\n' "$SERVICE_DUMP_AFTER_SELFTEST" | \
        rg -q 'com\.example\.helmet/\.service\.runtime\.HelmetService' || \
        ! printf '%s\n' "$SERVICE_DUMP_AFTER_SELFTEST" | rg -q 'isForeground=true'
    then
        echo "ERROR: HelmetService lost foreground state during hardware self-test" >&2
        return 1
    fi

    printf 'main_pid=%s hardware_provider_pid=%s provider_authority=%s application_uid=%s remote_binder=true\n' \
        "$MAIN_PID" "$HARDWARE_PID" "$HARDWARE_PROVIDER_AUTHORITY" "$MAIN_UID"

    stop_hardware_selftest
    verify_provider_crash_recovery "$MAIN_PID" "$HARDWARE_PID" "$MAIN_UID"

    printf '%s\t5\n' "$LABEL" >>"$SUMMARY_FILE"
    TOTAL_TESTS=$((TOTAL_TESTS + 5))
    printf 'PASS: %s (5 checks)\n' "$LABEL"

    clear_hardware_selftest_result
    restore_helmet_service
    SERVICE_RECOVERY_REQUIRED=false
    HARDWARE_SELFTEST_ACTIVE=false
    HARDWARE_SELFTEST_NONCE=
}

echo "Building the current debug application and selected non-empty instrumentation APKs"
(cd "$PROJECT_ROOT" && ./gradlew --no-daemon --console=plain \
    --project-cache-dir "$GRADLE_PROJECT_CACHE_DIR" \
    :device-android:app:assembleDebug \
    :device-android:data-local:assembleDebugAndroidTest \
    :device-android:feature-camera:assembleDebugAndroidTest \
    :device-android:feature-connectivity:assembleDebugAndroidTest \
    :device-android:feature-location:assembleDebugAndroidTest \
    :device-android:safety-detection:assembleDebugAndroidTest \
    :device-android:webrtc-runtime:assembleDebugAndroidTest \
    :device-android:service-runtime:assembleDebugAndroidTest \
    :device-android:app:assembleDebugAndroidTest)

for ANDROID_APK in \
    "$APP_APK" \
    "$DATA_LOCAL_APK" \
    "$FEATURE_CAMERA_APK" \
    "$FEATURE_CONNECTIVITY_APK" \
    "$FEATURE_LOCATION_APK" \
    "$SAFETY_DETECTION_APK" \
    "$WEBRTC_RUNTIME_APK" \
    "$SERVICE_RUNTIME_APK" \
    "$APP_TEST_APK"
do
    [ -f "$ANDROID_APK" ] || { echo "ERROR: missing APK: $ANDROID_APK"; exit 1; }
done
jar tf "$APP_APK" | rg -q '^lib/arm64-v8a/libhelmet_serial\.so$' || {
    echo "ERROR: main debug APK does not contain the arm64 native hardware provider runtime" >&2
    exit 1
}

adb -s "$ADB_SERIAL" get-state | rg -q '^device$' || {
    echo "ERROR: device is not online: $ADB_SERIAL"
    exit 1
}
adb -s "$ADB_SERIAL" shell pm path "$APP_PACKAGE" | tr -d '\r' | rg -q '^package:' || {
    echo "ERROR: main application is not installed: $APP_PACKAGE"
    exit 1
}

SERVICE_RECOVERY_REQUIRED=true
APP_INSTALL_RAW="$TEST_ROOT/app-install.raw.log"
APP_INSTALL_OUTPUT="$TEST_ROOT/app-install.log"
if adb -s "$ADB_SERIAL" install -r "$APP_APK" >"$APP_INSTALL_RAW" 2>&1; then
    APP_INSTALL_STATUS=0
else
    APP_INSTALL_STATUS=$?
fi
tr -d '\r' <"$APP_INSTALL_RAW" >"$APP_INSTALL_OUTPUT"
cat "$APP_INSTALL_OUTPUT"
APP_INSTALL_SUCCESS_LINES=$(rg -c '^Success$' "$APP_INSTALL_OUTPUT" || true)
: "${APP_INSTALL_SUCCESS_LINES:=0}"
if [ "$APP_INSTALL_STATUS" -ne 0 ] || [ "$APP_INSTALL_SUCCESS_LINES" -ne 1 ]
then
    echo "ERROR: current main debug APK was not installed successfully" >&2
    exit 1
fi

APP_PACKAGE_DUMP=$(adb -s "$ADB_SERIAL" shell dumpsys package "$APP_PACKAGE" 2>/dev/null | tr -d '\r' || true)
printf '%s\n' "$APP_PACKAGE_DUMP" | rg -q 'versionCode=3([[:space:]]|$)' || {
    echo "ERROR: installed main application versionCode is not 3" >&2
    exit 1
}
printf '%s\n' "$APP_PACKAGE_DUMP" | rg -q 'versionName=0\.3\.0([[:space:]]|$)' || {
    echo "ERROR: installed main application versionName is not 0.3.0" >&2
    exit 1
}
printf '%s\n' "$APP_PACKAGE_DUMP" | rg -q '(pkgFlags|flags)=\[[^]]*DEBUGGABLE' || {
    echo "ERROR: installed main application is not debuggable" >&2
    exit 1
}
restore_helmet_service
printf 'main_app_version_code=3 main_app_version_name=0.3.0 main_app_debuggable=true\n'

run_instrumentation data-local \
    com.example.helmet.data.local.test "$DATA_LOCAL_APK" package \
    com.example.helmet.data.local
run_instrumentation feature-camera \
    com.example.helmet.feature.camera.test "$FEATURE_CAMERA_APK" package \
    com.example.helmet.feature.camera
run_instrumentation feature-connectivity \
    com.example.helmet.feature.connectivity.test "$FEATURE_CONNECTIVITY_APK" package \
    com.example.helmet.feature.connectivity
run_instrumentation feature-location \
    com.example.helmet.feature.location.test "$FEATURE_LOCATION_APK" package \
    com.example.helmet.feature.location
run_instrumentation safety-detection \
    com.example.helmet.safety.detection.test "$SAFETY_DETECTION_APK" package \
    com.example.helmet.safety.detection
run_instrumentation webrtc-runtime \
    com.example.helmet.webrtc.test "$WEBRTC_RUNTIME_APK" package \
    com.example.helmet.webrtc
run_instrumentation service-runtime-offline \
    com.example.helmet.service.runtime.test "$SERVICE_RUNTIME_APK" class \
    "$SERVICE_RUNTIME_SELECTORS"
run_instrumentation app-manifest-policy \
    "$APP_TEST_PACKAGE" "$APP_TEST_APK" class "$APP_MANIFEST_SELECTORS"

run_real_hardware_mode_preparation
restart_helmet_application_in_real_hardware_mode
run_debug_hardware_selftest
restore_helmet_service

echo "Connected software test counts:"
cat "$SUMMARY_FILE"
printf 'total\t%s\n' "$TOTAL_TESTS"
printf 'PASS: main debug APK was updated with -r; the main application was not uninstalled or cleared; persisted simulator mode was safely corrected to real hardware mode\n'
