#!/bin/bash
# Builds and installs SuperAir, then starts the shell host from the installed APK.
#
# Once the app is paired with wireless debugging it restarts the host itself and no computer is
# involved at all; this script is for development, when a Mac is already attached.
set -euo pipefail
cd "$(dirname "$0")/.."
DIR=/data/local/tmp/superair
PKG=dev.superair
ADB=(adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"})

if [ "$("${ADB[@]}" get-state 2>&1 || true)" != "device" ]; then
  "${ADB[@]}" devices -l
  echo "No authorized phone." >&2
  exit 1
fi

./gradlew -q :app:assembleDebug
"${ADB[@]}" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null

# Lets an app that calls itself non-resizable fill a grid cell anyway.
"${ADB[@]}" shell settings put global enable_freeform_support 1
"${ADB[@]}" shell settings put global force_resizable_activities 1

APK=$("${ADB[@]}" shell pm path --user 0 "$PKG" | sed -n 's/^package://p' | tr -d '\r')
"${ADB[@]}" shell "pkill -f 'dev[.]superair[.]server[.]Main'; pkill -f '^superair-host'; rm -rf $DIR; mkdir -p $DIR && chmod 700 $DIR"
# A subshell with setsid, and the shell kept alive two seconds: adbd kills what a shell started if
# the shell exits first. Measured in FarGalaxy's HostController.
"${ADB[@]}" shell "(CLASSPATH=$APK setsid nohup app_process / --nice-name=superair-host dev.superair.server.Main >$DIR/log 2>&1 </dev/null &) ; sleep 2"

for _ in 1 2 3 4 5 6 7 8; do
  "${ADB[@]}" shell "grep -q 'ready on' $DIR/log" && break
  sleep 1
done
"${ADB[@]}" shell "cat $DIR/log"
