#!/usr/bin/env bash
# End-to-end loopback verification of the Mira cast (RTSP/RTP) and control (UIBC)
# protocols. Runs sink + testsource on the same emulator/device, then asserts on
# logcat evidence. Exit code 0 = all checks passed.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"

CODEC="${1:-2}"   # 0=h264(forced) 1=hevc(forced) 2=auto
W="${2:-1280}"
H="${3:-720}"
FPS="${4:-30}"
DUR="${5:-10}"

PASS_COUNT=0
FAIL_COUNT=0
LOG="$ROOT/test/last-run.log"

pass() { PASS_COUNT=$((PASS_COUNT + 1)); echo "  PASS: $1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); echo "  FAIL: $1"; }

logcat() { "$ADB" logcat -d 2>/dev/null || true; }

wait_for_log() {  # $1=tag $2=pattern $3=timeout_s
    local tag="$1" pat="$2" tmo="${3:-30}"
    local end=$((SECONDS + tmo))
    while [ "$SECONDS" -lt "$end" ]; do
        if logcat | grep -E "$tag.*$pat" | grep -q .; then return 0; fi
        sleep 2
    done
    return 1
}

ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
SINK_APK="$ROOT/app/build/outputs/apk/debug/app-${ABI}-debug.apk"
TS_APK="$ROOT/testsource/build/outputs/apk/debug/testsource-${ABI}-debug.apk"
[ -f "$SINK_APK" ] || SINK_APK="$ROOT/app/build/outputs/apk/debug/app-universal-debug.apk"
[ -f "$TS_APK" ] || TS_APK="$ROOT/testsource/build/outputs/apk/debug/testsource-universal-debug.apk"
for f in "$SINK_APK" "$TS_APK"; do
    if [ ! -f "$f" ]; then
        echo "ERROR: missing $f (run ./gradlew :app:assembleDebug :testsource:assembleDebug first)"
        exit 2
    fi
done

echo "== Device: $("$ADB" shell getprop ro.product.model | tr -d '\r') abi=$ABI sdk=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r') =="

"$ADB" shell am force-stop com.mira.sink 2>/dev/null || true
"$ADB" shell am force-stop com.mira.testsource 2>/dev/null || true
sleep 1

echo "== Installing sink app =="
"$ADB" install -r "$SINK_APK" >/dev/null
echo "== Installing test source app =="
"$ADB" install -r "$TS_APK" >/dev/null

"$ADB" shell pm grant com.mira.sink android.permission.NEARBY_WIFI_DEVICES 2>/dev/null || true
"$ADB" shell pm grant com.mira.sink android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell pm grant com.mira.sink android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true

echo "== Clearing logcat =="
"$ADB" logcat -c 2>/dev/null || true

echo "== Starting test source (codec=$CODEC ${W}x${H}@${FPS} dur=${DUR}s) =="
"$ADB" shell am start -n com.mira.testsource/.MainActivity \
    --ei codec "$CODEC" --ei w "$W" --ei h "$H" --ei fps "$FPS" --ei dur "$DUR" --ez uibc true

echo "== Waiting 2s, then starting sink =="
sleep 2
"$ADB" shell am start -n com.mira.sink/.MainActivity

echo "== Waiting for RTSP handshake (60s) =="
if wait_for_log MiraTest "HANDSHAKE.*done" 60; then
    pass "RTSP M1-M7 handshake completed"
else
    fail "RTSP handshake (no '[HANDSHAKE] done' within 60s)"
fi

echo "== Waiting for decode pipeline (60s) =="
if wait_for_log MiraUDP "traffic:.*frames=" 60; then
    pass "decoder pipeline streaming"
else
    fail "decoder pipeline (no 'traffic:' stats within 60s)"
fi

echo "== Live-rendering check (2 screenshots 3s apart, during stream) =="
"$ADB" exec-out screencap -p > /tmp/mira_live_a.png 2>/dev/null
sleep 3
"$ADB" exec-out screencap -p > /tmp/mira_live_b.png 2>/dev/null
if [ -s /tmp/mira_live_a.png ] && [ -s /tmp/mira_live_b.png ] && \
    ! cmp -s /tmp/mira_live_a.png /tmp/mira_live_b.png; then
    pass "live rendering (screenshots differ)"
else
    fail "live rendering (screenshots identical/empty)"
fi

echo "== Waiting for UIBC client connect (30s) =="
if wait_for_log MiraTest "UIBC.*connected" 30; then
    pass "UIBC client connected to sink :7237"
else
    fail "UIBC client did not connect to sink"
fi

echo "== Injecting touch gestures on sink (UIBC sink->source path) =="
for i in 1 2 3; do
    "$ADB" shell am start -n com.mira.sink/.MainActivity >/dev/null 2>&1
    sleep 1
    if "$ADB" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus=.*com.mira.sink"; then
        break
    fi
    "$ADB" shell am force-stop com.android.chrome >/dev/null 2>&1 || true
done
"$ADB" shell dumpsys window 2>/dev/null | grep mCurrentFocus || true
SIZE=$("$ADB" shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)
SW="${SIZE%x*}"
SH="${SIZE#*x}"
"$ADB" shell input swipe $((SW / 2)) $((SH * 7 / 10)) $((SW / 2)) $((SH * 3 / 10)) 300
"$ADB" shell input swipe $((SW / 4)) $((SH / 2)) $((SW * 3 / 4)) $((SH / 2)) 300
"$ADB" shell input tap $((SW / 2)) $((SH / 2))
sleep 2

echo "== Waiting for sink touch events to reach test source (30s) =="
if wait_for_log MiraTest "UIBC.*first input" 30; then
    pass "sink->source UIBC touch events delivered"
else
    fail "no UIBC input event received by test source"
fi

echo "== Waiting for stream end + SUMMARY =="
sleep $((DUR + 2))

logcat > "$LOG"
grep -E "MiraTest|MiraCodec|MiraUDP|MiraRTSP|MiraUIBC" "$LOG" | grep -v WrongStateException > "$ROOT/test/mira.log" || true

SUMMARY=$(grep "MiraTest: SUMMARY" "$ROOT/test/mira.log" | tail -1 || true)
if [ -n "$SUMMARY" ]; then
    echo "  $SUMMARY"
    FRAMES=$(echo "$SUMMARY" | grep -o 'frames=[0-9]*' | grep -o '[0-9]*')
    RTP_PKTS=$(echo "$SUMMARY" | grep -o 'rtp_pkts=[0-9]*' | grep -o '[0-9]*')
    UIBC_PKTS=$(echo "$SUMMARY" | grep -o 'uibc_pkts=[0-9]*' | grep -o '[0-9]*')
    UIBC_INPUTS=$(echo "$SUMMARY" | grep -o 'uibc_inputs=[0-9]*' | grep -o '[0-9]*')
    [ "${FRAMES:-0}" -ge 20 ] && pass "streamed frames >= 20 (got $FRAMES)" \
        || fail "streamed frames < 20 (got ${FRAMES:-0})"
    [ "${RTP_PKTS:-0}" -gt 0 ] && pass "RTP packets sent ($RTP_PKTS)" \
        || fail "no RTP packets sent"
    [ "${UIBC_PKTS:-0}" -gt 0 ] && pass "UIBC packets received by source ($UIBC_PKTS)" \
        || fail "no UIBC packets received by source"
    [ "${UIBC_INPUTS:-0}" -ge 1 ] && pass "UIBC touch events received >= 1 (got $UIBC_INPUTS)" \
        || fail "no UIBC touch events received"
else
    fail "no SUMMARY from test source (test crashed?)"
fi

PIPE=$(grep "MiraUDP.*traffic:" "$ROOT/test/mira.log" | tail -1 || true)
if [ -n "$PIPE" ]; then
    PFRAMES=$(echo "$PIPE" | grep -o 'frames=[0-9]*' | grep -o '[0-9]*')
    [ "${PFRAMES:-0}" -ge 20 ] && pass "decoded/rendered frames >= 20 (got $PFRAMES)" \
        || fail "decoded/rendered frames < 20 (got ${PFRAMES:-0})"
else
    fail "no traffic stats from sink receiver"
fi

echo ""
echo "========================================"
echo "RESULT: $PASS_COUNT passed, $FAIL_COUNT failed"
echo "Full log: $LOG"
echo "========================================"
[ "$FAIL_COUNT" -eq 0 ]
