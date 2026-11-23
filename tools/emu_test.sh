#!/bin/bash
# 뒷자리 맑음 — Pleos 에뮬레이터 시나리오 시험 (Virtual Console HTTP 로 차량 신호를 넣는다)
# 쓰는 법: bash tools/emu_test.sh   (에뮬레이터가 켜져 있어야 함)
R="$(cd "$(dirname "$0")/.." && pwd)"
A="${ADB:-adb}"  # Pleos SDK 의 adb (PATH 에 없으면 ADB=경로)
V=http://127.0.0.1:18088
APK="$R/app/build/outputs/apk/debug/app-debug.apk"
OUT="$R/tmp/emu_test"
mkdir -p $OUT
put() { curl -s -o /dev/null -w "%{http_code} $1\n" -X PUT "$V$1"; }
log() { cat $OUT/app.log | grep -v -E "SeatChanged|cap |now " | grep -E "$1" | tail -${2:-15}; }
shot() { $A exec-out screencap -p > $OUT/$1.png; echo "화면 $1.png"; }

until [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "== 부팅 완료"
$A install -r $APK | tail -1
$A shell pm grant --user 10 app.backseatclear android.car.permission.CAR_SPEED
$A shell pm grant --user 10 app.backseatclear android.permission.RECORD_AUDIO
$A forward tcp:18088 tcp:8088 >/dev/null
( while true; do curl -s -X POST "$V/browser/session?client=backseatclear-cli" -o /dev/null; sleep 1; done ) &
HB=$!
trap "kill $HB 2>/dev/null" EXIT
sleep 2

echo "== 1. 출발 전: 공조 켜기·내기순환·창문 닫기 → 뒷좌석 왼쪽 탑승"
put "/vehicle/hvac?power=true"; put "/vehicle/hvac?recirc=true"; put "/vehicle/hvac?fan_speed=2"
put "/vehicle/body?window_rl=0"
put "/vehicle/seat?kind=occupied&value=false&area=rear_right"
put "/vehicle/seat?kind=occupied&value=true&area=rear_left"
$A logcat -c; $A logcat -G 16M >/dev/null
# 앱 기록만 따로 계속 받는다(차 시스템 로그가 많아 버퍼가 금방 넘친다)
$A logcat -s BackseatClear:I > $OUT/app.log &
LG=$!
trap "kill $HB $LG 2>/dev/null" EXIT
$A shell am force-stop --user 10 app.backseatclear
sleep 1

echo "== 2. 앱 시작 + 경로 요청(양평군청)"
$A shell am start --user 10 -n app.backseatclear/.MainActivity --es route "37.4917,127.4875,Yangpyeong" >/dev/null
sleep 25
log "경로|route|사건|명령|navi" 20
shot 1_route

echo "== 3. 주행: 기어 D, 60km/h, 급제동 3번, 조향"
put "/vehicle/gear?gear=Drive"
put "/vehicle/controls?speed=60&accelerator=30&brake=0&steering=0"
sleep 5
for i in 1 2 3; do put "/vehicle/controls?speed=30&accelerator=0&brake=70"; sleep 1; put "/vehicle/controls?speed=50&accelerator=30&brake=0"; sleep 2; done
for d in 120 -120 150 -150; do put "/vehicle/controls?speed=40&accelerator=20&brake=0&steering=$d"; sleep 1; done
sleep 5
log "사건|명령|Apply|warn|fan|temp|window|fresh" 25
shot 2_driving

echo "== 4. 차 상태(콘솔 기준)"
curl -s "$V/vehicle/state" | python -I -c "
import json,sys; s=json.load(sys.stdin); h=s['hvac']; b=s['body']
print('공조', {k:h.get(k) for k in ('power_on','recirc_on','fan_speed')}, '운전석', h.get('driver'), '창문', b.get('windows'))"
$A logcat -d > $OUT/logcat_full.txt
echo "== 끝 (전체 로그 $OUT/logcat_full.txt)"
