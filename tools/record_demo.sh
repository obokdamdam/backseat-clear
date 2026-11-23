#!/bin/bash
# 실제 프로그램 시연 녹화 — Pleos 에뮬레이터 화면 + 앱 기록(시각). 소리는 compose_demo.py 가 기록 시각에 맞춰 넣는다.
# 쓰는 법: bash tools/record_demo.sh  (에뮬레이터가 켜져 있어야 함)
export MSYS_NO_PATHCONV=1
R="$(cd "$(dirname "$0")/.." && pwd)"
A="${ADB:-adb}"  # Pleos SDK 의 adb (PATH 에 없으면 ADB=경로)
V=http://127.0.0.1:18088
O="$R/tmp/demo_rec"
mkdir -p $O
p() { curl -s -o /dev/null -X PUT "$V$1"; }

$A wait-for-device
until [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
[ -z "$WARM" ] && sleep 90  # 내비가 준비될 때까지 (이미 켜 둔 지 오래면 WARM=1)
$A install -r "$R/app/build/outputs/apk/debug/app-debug.apk" | tail -1
$A shell pm grant --user 10 app.backseatclear android.car.permission.CAR_SPEED
$A shell pm grant --user 10 app.backseatclear android.permission.RECORD_AUDIO
$A forward tcp:18088 tcp:8088 >/dev/null
( for i in $(seq 1 200); do curl -s -X POST "$V/browser/session?client=cli" -o /dev/null; sleep 1; done ) &
HB=$!

# 출발 전 차 상태
p "/vehicle/gear?gear=Park"; p "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0"
p "/vehicle/hvac?power=true"; p "/vehicle/hvac?recirc=true"; p "/vehicle/hvac?fan_speed=2"; p "/vehicle/hvac?driver_temp=22"
p "/vehicle/body?window_rl=0"
p "/vehicle/seat?kind=occupied&value=false&area=rear_left"; p "/vehicle/seat?kind=occupied&value=false&area=rear_right"
$A shell am force-stop --user 10 app.backseatclear
sleep 2

$A logcat -G 16M >/dev/null; $A logcat -c
$A logcat -v epoch -s BackseatClear:I > $O/app.log &
LG=$!
# 앱을 켜 두고 10초 뒤 경로 요청, 경로 시작 30초 뒤 「속이 안 좋아」
$A shell am start --user 10 -n app.backseatclear/.MainActivity --es route "37.4917,127.4875,Yangpyeong" --ei routeAfter 10 --es heard sick --ei heardAfter 30 >/dev/null
sleep 3
$A shell date +%s.%N > $O/t0.txt
$A shell screenrecord --time-limit 75 --bit-rate 10000000 /sdcard/demo.mp4 &
REC=$!
sleep 4
p "/vehicle/seat?kind=occupied&value=true&area=rear_left"      # 뒷좌석 탑승
sleep 18
# 경로가 시작되면 내비 화면이 앞으로 나온다 → 우리 앱을 다시 앞으로(앱은 포그라운드 서비스로 계속 돈다)
$A shell am start --user 10 -n app.backseatclear/.MainActivity >/dev/null
sleep 2
p "/vehicle/gear?gear=Drive"; p "/vehicle/controls?speed=50&accelerator=30&brake=0"
sleep 5
for i in 1 2 3; do p "/vehicle/controls?speed=25&accelerator=0&brake=70"; sleep 1; p "/vehicle/controls?speed=45&accelerator=30&brake=0"; sleep 2; done
for d in 120 -120 150 -150 0; do p "/vehicle/controls?speed=40&accelerator=20&brake=0&steering=$d"; sleep 1; done
wait $REC
sleep 2
kill $LG $HB 2>/dev/null
$A pull /sdcard/demo.mp4 $O/screen.mp4 | tail -1
curl -s "$V/vehicle/state" > $O/state_end.json
echo "녹화 끝: $O"
