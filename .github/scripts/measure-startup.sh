#!/usr/bin/env bash
# Cold-start time of one build on the connected emulator or device.
#   measure-startup.sh <apk> <application id> <label>
# Appends a section to speed-report.txt. Fails when the build cannot be installed or is not running after a launch
# (an R8-optimized build that crashes at start must not pass).
set -u
apk="$1"; pkg="$2"; label="$3"; out="speed-report.txt"; failed=0
{
  echo "== $label =="
  if [ ! -f "$apk" ]; then echo "APK missing: not measured"; echo; exit 1; fi
  echo "APK: $(( $(stat -c %s "$apk") / 1048576 )) MB"
  if ! adb install -r "$apk" > install.log 2>&1; then echo "INSTALL FAILED: $(tail -1 install.log)"; echo; exit 1; fi
  launcher=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r' | tail -1)
  adb logcat -c
  times=()
  for i in 1 2 3 4 5 6; do
    adb shell am force-stop "$pkg"; sleep 2
    t=$(adb shell am start -W -n "$launcher" | tr -d '\r' | awk -F': ' '/^TotalTime/{print $2}')
    sleep 5
    if [ -z "$(adb shell pidof "$pkg" | tr -d '\r')" ]; then echo "start $i: the app is NOT running 5 s after launch (crashed)"; failed=1
    else echo "start $i: ${t:-?} ms"; [ "$i" -gt 1 ] && [ -n "$t" ] && times+=("$t"); fi
  done
  # the first start after an install does one-time work, so it is listed above but left out of the median
  if [ "${#times[@]}" -gt 0 ]; then
    median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
    echo "cold start, median of starts 2 to 6: $median ms"
  fi
  echo "the app's own trace (one line per start):"
  adb logcat -d -s StartupTrace:I | sed -E 's/^.*StartupTrace: /  /' | grep -v '^-----' | tail -6
  if [ "$failed" = 1 ]; then echo "crash trace:"; adb logcat -d -b crash | tail -40; fi
  echo
} >> "$out"
exit $failed
