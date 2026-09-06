#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$PROJECT_ROOT/tools/env.sh"

cd "$PROJECT_ROOT"
exec ./gradlew --no-daemon --project-cache-dir "$GRADLE_PROJECT_CACHE_DIR" \
    :device-android:app:assembleDebug \
    :device-android:app:assembleRelease \
    :native-hardware-service:assembleDebug \
    :native-hardware-service:assembleRelease \
    "$@"
