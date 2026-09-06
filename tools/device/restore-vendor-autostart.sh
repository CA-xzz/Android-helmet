#!/bin/sh
set -eu

HELMET_ADB_SERIAL=
CONFIRM_RESTORE=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --adb-serial)
            shift
            [ "$#" -gt 0 ] || { echo "ERROR: --adb-serial requires a value" >&2; exit 64; }
            HELMET_ADB_SERIAL=$1
            ;;
        --confirm-restore-myautorun)
            CONFIRM_RESTORE=true
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            exit 64
            ;;
    esac
    shift
done

[ -n "$HELMET_ADB_SERIAL" ] || {
    echo "ERROR: --adb-serial is required" >&2
    exit 64
}
[ "$CONFIRM_RESTORE" = true ] || {
    echo "ERROR: this tool overwrites /data/myautorun.sh; pass --confirm-restore-myautorun explicitly" >&2
    exit 64
}
[ "${HELMET_ENABLE_SYSTEM_SCRIPT_WRITE:-}" = YES ] || {
    echo "ERROR: set HELMET_ENABLE_SYSTEM_SCRIPT_WRITE=YES after reviewing the target and backup" >&2
    exit 64
}
DEVICE_FILE=/data/myautorun.sh
DEVICE_BACKUP=/data/myautorun.sh.vendor-backup

adb -s "$HELMET_ADB_SERIAL" get-state >/dev/null
adb -s "$HELMET_ADB_SERIAL" shell test -f "$DEVICE_BACKUP"
adb -s "$HELMET_ADB_SERIAL" shell \
    "cp '$DEVICE_BACKUP' '$DEVICE_FILE' && chown root:root '$DEVICE_FILE' && chmod 700 '$DEVICE_FILE' && chcon u:object_r:system_data_root_file:s0 '$DEVICE_FILE'"
adb -s "$HELMET_ADB_SERIAL" shell sha256sum "$DEVICE_FILE"
