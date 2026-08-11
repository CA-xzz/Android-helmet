#!/bin/sh
set -eu

HELMET_ADB_SERIAL="${HELMET_ADB_SERIAL:-2c001031774186e21d3}"
DEVICE_FILE=/data/myautorun.sh
DEVICE_BACKUP=/data/myautorun.sh.vendor-backup

adb -s "$HELMET_ADB_SERIAL" get-state >/dev/null
adb -s "$HELMET_ADB_SERIAL" shell test -f "$DEVICE_BACKUP"
adb -s "$HELMET_ADB_SERIAL" shell \
    "cp '$DEVICE_BACKUP' '$DEVICE_FILE' && chown root:root '$DEVICE_FILE' && chmod 700 '$DEVICE_FILE' && chcon u:object_r:system_data_root_file:s0 '$DEVICE_FILE'"
adb -s "$HELMET_ADB_SERIAL" shell sha256sum "$DEVICE_FILE"
