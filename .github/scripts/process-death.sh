#!/bin/sh
# Step 4, piece 24: a saved place survives the process being killed and an update installed over
# the app. A test cannot kill its own process, so the steps run from here, against the debug build.
set -eu
APP=app.kamayoq.android.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"

# `am instrument` exits 0 even when a test fails, so its own summary decides.
step() {
    out=$(adb shell am instrument -w -e processDeath "$1" -e class app.kamayoq.android.ProcessDeathTest "$RUNNER")
    echo "$out"
    echo "$out" | grep -q '^OK ('
}

# connectedDebugAndroidTest uninstalls the app when it ends: install it and its tests again.
./gradlew :app:installDebug :app:installDebugAndroidTest --stacktrace
step save
adb shell am force-stop "$APP"
step check
# The same APK installed over the app is an update: the data must stay.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop "$APP"
step check
