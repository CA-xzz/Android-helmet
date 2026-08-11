#!/bin/sh

set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

JDK_FOUND=false
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JDK_FOUND=true
else
    for JDK_CANDIDATE in \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
        "$HOME/Library/Caches/android-helmet-tools/jdk-17/Contents/Home"
    do
        if [ -x "$JDK_CANDIDATE/bin/java" ]; then
            JAVA_HOME=$JDK_CANDIDATE
            JDK_FOUND=true
            break
        fi
    done
fi
if [ "$JDK_FOUND" != true ]; then
    if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
        echo "ERROR: set JAVA_HOME to a JDK 17 installation" >&2
        exit 1
    fi
fi

ANDROID_SDK_CANDIDATE=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$ANDROID_SDK_CANDIDATE" ]; then
    for SDK_CANDIDATE in \
        "$HOME/Library/Android/sdk" \
        "/opt/homebrew/share/android-commandlinetools"
    do
        if [ -f "$SDK_CANDIDATE/platforms/android-35/android.jar" ]; then
            ANDROID_SDK_CANDIDATE=$SDK_CANDIDATE
            break
        fi
    done
fi
if [ ! -f "$ANDROID_SDK_CANDIDATE/platforms/android-35/android.jar" ]; then
    echo "ERROR: set ANDROID_HOME to an SDK containing platform android-35" >&2
    exit 1
fi
export ANDROID_HOME=$ANDROID_SDK_CANDIDATE
export ANDROID_SDK_ROOT=$ANDROID_HOME

if [ -n "${XDG_CACHE_HOME:-}" ]; then
    DEFAULT_CACHE_ROOT=$XDG_CACHE_HOME
elif [ "$(uname -s)" = "Darwin" ]; then
    DEFAULT_CACHE_ROOT="$HOME/Library/Caches"
else
    DEFAULT_CACHE_ROOT="$HOME/.cache"
fi
HELMET_BUILD_CACHE_ROOT=${HELMET_BUILD_CACHE_ROOT:-"$DEFAULT_CACHE_ROOT/android-helmet"}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HELMET_BUILD_CACHE_ROOT/gradle-user-home"}
export GRADLE_PROJECT_CACHE_DIR=${GRADLE_PROJECT_CACHE_DIR:-"$HELMET_BUILD_CACHE_ROOT/project-cache"}

if [ "$JDK_FOUND" = true ]; then
    export JAVA_HOME
    PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH="$ANDROID_HOME/platform-tools:$PATH"
