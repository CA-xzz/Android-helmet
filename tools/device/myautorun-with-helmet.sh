#!/system/bin/sh

HELMET_PACKAGE=com.example.helmet
HELMET_SERVICE=com.example.helmet/com.example.helmet.service.runtime.HelmetService
HELMET_RECOVERY_ACTION=com.example.helmet.action.RECOVERY
HELMET_WATCHDOG_INTERVAL_SECONDS=${HELMET_WATCHDOG_INTERVAL_SECONDS:-10}
HELMET_WATCHDOG_STARTUP_GRACE_SECONDS=${HELMET_WATCHDOG_STARTUP_GRACE_SECONDS:-3}
HELMET_WATCHDOG_FAILURE_LIMIT=${HELMET_WATCHDOG_FAILURE_LIMIT:-3}
HELMET_WATCHDOG_BACKOFF_SECONDS=${HELMET_WATCHDOG_BACKOFF_SECONDS:-60}
HELMET_WATCHDOG_PID_FILE=${HELMET_WATCHDOG_PID_FILE:-/data/helmet-runtime-watchdog.pid}
HELMET_WATCHDOG_LOG=${HELMET_WATCHDOG_LOG:-/dev/console}

WATCHDOG_ONLY=0
WATCHDOG_ONCE=0
for ARGUMENT in "$@"; do
    case "$ARGUMENT" in
        --watchdog-only) WATCHDOG_ONLY=1 ;;
        --once) WATCHDOG_ONCE=1 ;;
    esac
done

log_watchdog() {
    printf '%s\n' "helmet-watchdog: $*" >> "$HELMET_WATCHDOG_LOG"
}

helmet_package_installed() {
    pm path "$HELMET_PACKAGE" >/dev/null 2>&1
}

helmet_service_running() {
    dumpsys activity services "$HELMET_SERVICE" 2>/dev/null |
        grep -q 'com.example.helmet/.service.runtime.HelmetService'
}

start_helmet_runtime() {
    RECOVERY_SOURCE=$1
    RECOVERY_ATTEMPT=$2
    am start-foreground-service --user 0 \
        -a "$HELMET_RECOVERY_ACTION" \
        -n "$HELMET_SERVICE" \
        --es recovery_source "$RECOVERY_SOURCE" \
        --ei recovery_attempt "$RECOVERY_ATTEMPT" >/dev/null 2>&1
}

release_watchdog_pid() {
    if [ -f "$HELMET_WATCHDOG_PID_FILE" ] &&
        [ "$(cat "$HELMET_WATCHDOG_PID_FILE" 2>/dev/null)" = "$$" ]; then
        rm -f "$HELMET_WATCHDOG_PID_FILE"
    fi
}

terminate_watchdog() {
    release_watchdog_pid
    exit 0
}

acquire_watchdog_pid() {
    EXISTING_PID=$(cat "$HELMET_WATCHDOG_PID_FILE" 2>/dev/null)
    if [ -n "$EXISTING_PID" ] && kill -0 "$EXISTING_PID" 2>/dev/null; then
        if tr '\000' ' ' < "/proc/$EXISTING_PID/cmdline" 2>/dev/null |
            grep -q 'myautorun'; then
            log_watchdog "already running pid=$EXISTING_PID"
            return 1
        fi
    fi
    printf '%s\n' "$$" > "$HELMET_WATCHDOG_PID_FILE"
    trap release_watchdog_pid EXIT
    trap terminate_watchdog HUP INT TERM
    return 0
}

supervise_helmet_runtime() {
    RECOVERY_SOURCE=$1
    CONSECUTIVE_FAILURES=0

    acquire_watchdog_pid || return 0
    log_watchdog "supervisor started pid=$$ interval=${HELMET_WATCHDOG_INTERVAL_SECONDS}s"

    while true; do
        if ! helmet_package_installed; then
            log_watchdog "package unavailable"
        elif helmet_service_running; then
            CONSECUTIVE_FAILURES=0
        else
            CONSECUTIVE_FAILURES=$((CONSECUTIVE_FAILURES + 1))
            log_watchdog "service missing source=$RECOVERY_SOURCE attempt=$CONSECUTIVE_FAILURES"
            if start_helmet_runtime "$RECOVERY_SOURCE" "$CONSECUTIVE_FAILURES"; then
                sleep "$HELMET_WATCHDOG_STARTUP_GRACE_SECONDS"
                if helmet_service_running; then
                    log_watchdog "service recovered source=$RECOVERY_SOURCE attempt=$CONSECUTIVE_FAILURES"
                    CONSECUTIVE_FAILURES=0
                else
                    log_watchdog "service absent after successful start command"
                fi
            else
                log_watchdog "start command failed attempt=$CONSECUTIVE_FAILURES"
            fi

            if [ "$CONSECUTIVE_FAILURES" -ge "$HELMET_WATCHDOG_FAILURE_LIMIT" ]; then
                log_watchdog "failure limit reached; backing off ${HELMET_WATCHDOG_BACKOFF_SECONDS}s"
                sleep "$HELMET_WATCHDOG_BACKOFF_SECONDS"
                CONSECUTIVE_FAILURES=0
            fi
        fi

        RECOVERY_SOURCE=bsp_watchdog
        if [ "$WATCHDOG_ONCE" = "1" ]; then
            break
        fi
        sleep "$HELMET_WATCHDOG_INTERVAL_SECONDS"
    done
}

if [ "$WATCHDOG_ONLY" = "0" ]; then
    # Preserve the vendor GPIO initialization and launcher action.
    echo 203 > /sys/class/gpio/export
    echo out > /sys/class/gpio/gpio203/direction
    echo 1 > /sys/class/gpio/gpio203/value

    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 1
    done

    sleep 5

    printf '%s\n' 'Myutorun!*******' > /dev/console
    am start com.hy.yesobox/com.hy.yesobox.NoScreenActivity
fi

# The H618 Android 12 firmware omits data-partition applications from its
# BOOT_COMPLETED receiver list. Keep supervising the foreground service from
# the existing vendor boot hook after PackageManager has completed startup.
if [ "$WATCHDOG_ONLY" = "1" ]; then
    supervise_helmet_runtime bsp_watchdog
else
    supervise_helmet_runtime bsp_boot
fi
