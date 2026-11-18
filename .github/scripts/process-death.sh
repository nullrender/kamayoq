#!/bin/sh
# Step 4, piece 24: a saved place survives the process being killed and an update installed over
# the app. A test cannot kill its own process, so the steps run from here, against the debug build.
set -eu
APP=app.kamayoq.android.debug
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"

# `am instrument` exits 0 even when a test fails, so its raw report decides. Each step runs one test
# and skips the other (status -4); "OK" alone would also pass a run that skipped both.
step() {
    out=$(adb shell am instrument -r -w -e processDeath "$1" -e class app.kamayoq.android.ProcessDeathTest "$RUNNER")
    echo "$out"
    echo "$out" | grep -q 'INSTRUMENTATION_CODE: -1'
    # A line negated with ! never stops a script under set -e, so the failure check exits by itself.
    if echo "$out" | grep -q 'INSTRUMENTATION_STATUS_CODE: -2'; then exit 1; fi
    [ "$(echo "$out" | grep -c 'INSTRUMENTATION_STATUS_CODE: -4')" -eq 1 ]
    [ "$(echo "$out" | grep -c 'INSTRUMENTATION_STATUS_CODE: 0')" -eq 1 ]
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
