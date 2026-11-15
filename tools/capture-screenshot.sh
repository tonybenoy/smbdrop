#!/usr/bin/env bash
# Capture a screenshot from the connected device and make it safe + store-ready:
#   1. crop the system status bar (carrier name, notification icons, clock cannot leak)
#   2. pad the sides with the app background so the aspect ratio is at most 2:1 (Google Play limit)
# Use ONLY with demo data on screen, never your real server.
# usage: tools/capture-screenshot.sh <name> [adb-binary] [out-dir]   e.g. tools/capture-screenshot.sh 1_home adb.exe
set -euo pipefail
name="${1:?name, e.g. 1_home}"; ADB="${2:-adb}"
out="${3:-fastlane/metadata/android/en-US/images/phoneScreenshots}"; mkdir -p "$out"
tmp="$(mktemp --suffix=.png)"; trap 'rm -f "$tmp"' EXIT
"$ADB" exec-out screencap -p > "$tmp"
w=$(identify -format %w "$tmp"); h=$(identify -format %h "$tmp")
top=$(( h * 45 / 1000 )); h2=$(( h - top ))                   # status bar ≈ 4.5 % of the height
max_h=$(( w * 2 ))
if [ "$h2" -gt "$max_h" ]; then                                # too tall for Play → scale to 2:1 and pad the sides
  nw=$(( w * max_h / h2 ))
  pad=$(( (w - nw) / 2 ))
  magick "$tmp" -crop "${w}x${h2}+0+${top}" +repage -resize "x${max_h}" -virtual-pixel Edge -set option:distort:viewport "${w}x${max_h}-${pad}+0" -distort SRT 0 +repage "$out/$name.png"
else
  magick "$tmp" -crop "${w}x${h2}+0+${top}" +repage "$out/$name.png"
fi
echo "saved $out/$name.png ($(identify -format '%wx%h' "$out/$name.png"))"
