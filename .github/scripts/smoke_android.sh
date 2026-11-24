#!/usr/bin/env bash
set -eu

adb install -r "$1"
adb shell am start -S -W -n com.andychen.magpie_android/.MainActivity
screen=$(mktemp)
trap 'rm -f "$screen"' EXIT
for attempt in {1..10}; do
  if adb shell uiautomator dump /data/local/tmp/magpie-ui.xml >/dev/null &&
     adb exec-out cat /data/local/tmp/magpie-ui.xml > "$screen" &&
     grep -Fq 'resource-id="nav_more"' "$screen"; then
    exit 0
  fi
  sleep 1
done
echo "The app did not display its main navigation." >&2
exit 1
