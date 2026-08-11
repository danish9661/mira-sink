#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"

CODEC="${1:-2}"   # 0=h264(forced) 1=hevc(forced) 2=auto
W="${2:-1280}"
H="${3:-720}"
FPS="${4:-30}"
DUR="${5:-10}"

echo "== Installing sink app =="
"$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo "== Installing test source app =="
"$ADB" install -r "$ROOT/testsource/build/outputs/apk/debug/testsource-debug.apk"

"$ADB" shell pm grant com.mira.sink android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true
"$ADB" shell pm grant com.mira.sink android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell pm grant com.mira.sink android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true

echo "== Clearing logcat =="
"$ADB" logcat -c

echo "== Starting test source (codec=$CODEC ${W}x${H}@${FPS} dur=${DUR}s) =="
"$ADB" shell am start -n com.mira.testsource/.MainActivity \
    --ei codec "$CODEC" --ei w "$W" --ei h "$H" --ei fps "$FPS" --ei dur "$DUR" --ez uibc true

echo "== Waiting 2s, then starting sink (kept in foreground for live surface) =="
sleep 2
"$ADB" shell am start -n com.mira.sink/.MainActivity
sleep 3
"$ADB" shell pidof com.mira.sink >/dev/null && echo "sink running"

echo "== Waiting for handshake+stream, then injecting touch =="
sleep 4
SIZE=$("$ADB" shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)
SW="${SIZE%x*}"
SH="${SIZE#*x}"
"$ADB" shell input swipe $((SW / 2)) $((SH * 7 / 10)) $((SW / 2)) $((SH * 3 / 10)) 400
sleep 1
"$ADB" shell input swipe $((SW / 4)) $((SH / 2)) $((SW * 3 / 4)) $((SH / 2)) 400
sleep 1
"$ADB" shell input tap $((SW / 2)) $((SH / 2))
echo "== Touch injected: swipe, swipe, tap =="

echo "== Waiting for stream to finish =="
sleep $((DUR + 4))

echo "== Pulling logs =="
"$ADB" logcat -d > "$ROOT/test/last-run.log"
grep -E "MiraTest|MiraCodec|MiraUDP|MiraRTSP|MiraUIBC" "$ROOT/test/last-run.log" | grep -v "WrongStateException" > "$ROOT/test/mira.log" || true

echo ""
echo "== TEST SOURCE (MiraTest) =="
grep "MiraTest" "$ROOT/test/mira.log" | sed 's/.*MiraTest: //' || true
echo ""
echo "== SINK DECODER (MiraCodec) =="
grep "MiraCodec" "$ROOT/test/mira.log" | sed 's/.*MiraCodec: //' | tail -8 || true
echo ""
echo "== SINK RTP RECEIVER (MiraUDP) =="
grep "MiraUDP" "$ROOT/test/mira.log" | sed 's/.*MiraUDP: //' | tail -4 || true

echo ""
echo "== SUMMARY =="
grep "MiraTest: SUMMARY" "$ROOT/test/mira.log" | sed 's/.*MiraTest: //' || echo "NO SUMMARY - test likely failed"