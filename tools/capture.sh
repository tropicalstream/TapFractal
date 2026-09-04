#!/bin/zsh
# Timed on-device capture for the picture-level check (tools/apl.py).
#   tools/capture.sh <out.png> idle          — HUD hidden, capture now
#   tools/capture.sh <out.png> flick [ms]    — schedule a real flick, capture <ms> (default 300) after it
#   tools/capture.sh <out.png> pulse [ms]    — schedule a visual-only hit pulse/flash, capture after it
# The schedule uses the glasses' own clock (tapfractal_debug "pulse=<epoch ms>", polled every ~100 ms by
# the app); the capture is taken by screencap on the device at T+<ms>. The debug flag is left as "nohud"
# afterwards (delete it with: adb shell settings delete global tapfractal_debug).
set -e
SERIAL=${SERIAL:-A06B4A96A733283}
OUT=$1; KIND=${2:-idle}; DELAY=${3:-300}
ADB=(adb -s "$SERIAL")
if [[ $KIND == idle ]]; then
  $ADB shell "settings put global tapfractal_debug nohud; sleep 0.6; screencap -p /data/local/tmp/cap.png"
else
  # mksh arithmetic is 32-bit, so the epoch-ms schedule is computed here from the glasses' clock and
  # the device loop compares 13-digit strings lexicographically.
  NOW=$($ADB shell date +%s%N); NOW=${NOW:0:13}
  T=$(( NOW + 1200 )); TC=$(( T + DELAY ))
  $ADB shell "settings put global tapfractal_debug nohud,$KIND=$T; \
    while :; do N=\$(date +%s%N); N=\${N%??????}; [[ \$N > $TC ]] && break; sleep 0.02; done; \
    echo capture at +\$(( \${N#????????} - ${T#????????} )) ms; screencap -p /data/local/tmp/cap.png"
fi
$ADB pull /data/local/tmp/cap.png "$OUT" >/dev/null
$ADB shell settings put global tapfractal_debug nohud
