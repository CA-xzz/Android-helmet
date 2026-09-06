#!/bin/sh
set -eu

HELMET_ADB_SERIAL=
CONFIRM_OVERWRITE=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --adb-serial)
            shift
            [ "$#" -gt 0 ] || { echo "ERROR: --adb-serial requires a value" >&2; exit 64; }
            HELMET_ADB_SERIAL=$1
            ;;
        --confirm-overwrite-myautorun)
            CONFIRM_OVERWRITE=true
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
[ "$CONFIRM_OVERWRITE" = true ] || {
    echo "ERROR: this tool overwrites /data/myautorun.sh; pass --confirm-overwrite-myautorun explicitly" >&2
    exit 64
}
[ "${HELMET_ENABLE_SYSTEM_SCRIPT_WRITE:-}" = YES ] || {
    echo "ERROR: set HELMET_ENABLE_SYSTEM_SCRIPT_WRITE=YES after reviewing the target and backup" >&2
    exit 64
}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE_FILE="$SCRIPT_DIR/myautorun-with-helmet.sh"
DEVICE_FILE=/data/myautorun.sh
DEVICE_BACKUP=/data/myautorun.sh.vendor-backup
DEVICE_TEMP=/data/local/tmp/myautorun-with-helmet.sh

adb -s "$HELMET_ADB_SERIAL" get-state >/dev/null
adb -s "$HELMET_ADB_SERIAL" push "$SOURCE_FILE" "$DEVICE_TEMP" >/dev/null
adb -s "$HELMET_ADB_SERIAL" shell \
    "test -f '$DEVICE_BACKUP' || cp '$DEVICE_FILE' '$DEVICE_BACKUP'"
adb -s "$HELMET_ADB_SERIAL" shell \
    "cp '$DEVICE_TEMP' '$DEVICE_FILE' && chown root:root '$DEVICE_FILE' && chmod 700 '$DEVICE_FILE' && chcon u:object_r:system_data_root_file:s0 '$DEVICE_FILE'"
adb -s "$HELMET_ADB_SERIAL" shell sha256sum "$DEVICE_FILE" "$DEVICE_BACKUP"
