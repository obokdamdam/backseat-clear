# 뒷자리 맑음 (Backseat Clear)

내비가 아는 앞길로 동승자의 멀미를 미리 예보하고, 차가 먼저 공기와 자리를 바꾸며, 운전자에게는 한마디로 부드럽게 알려 주는 **Pleos Connect 차량용 멀미 케어 앱** (현대자동차그룹 차량용 앱 개발 공모전 출품 데모).

## 구조
```
core/     판단 로직(안드로이드·SDK 무관, JVM 단위 테스트)
  forecast/  ForecastEngine — 경로 1km 구간 점수 → 맑음·구름·흐림·비 / LiveScorer — 브레이크·조향·차속·내기순환 보정
  care/      CarePlanner — 공조·창문 조치(폭 제한, 사람 우선, 미지원 생략)
  voice/     Phrases · IntentParser · VoicePolicy(내비 우선, 운전자 안내 10분 1회, 조용히 모드)
  session/   TripSession — 상태 기계(자동 시작 → 예보 → 준비 → 신고 → 쉼 → 도착 리포트)
port/     차·내비·음성 창구(인터페이스)
pleos/    Pleos Connect SDK 구현: Vehicle · NaviHelper · TTS · STT (+ AAOS 차속) · FileVoice(안내 음성 파일 우선)
sim/      시나리오 A(서울→양평) 재생 · 모의 포트 · 안드로이드 TTS
ui/       Compose 화면(주행 중: 아이콘+한 단어+한 줄, 터치 없음 / 정차 중: 예보·리포트·가족)
TripService  포그라운드 서비스 — 내비 화면이 앞에 있어도 차 신호를 계속 받는다
```

## Pleos Connect 에뮬레이터에서 확인한 것 (v2.1.0, Virtual Console)
| 확인 | 결과 |
|---|---|
| 뒷좌석 탑승 + 실제 내비 경로 → 자동 시작 | 「뒷자리에 누가 탔네요. 멀미 예보를 켤게요.」 |
| 실제 경로로 구간별 멀미 예보 | 실시간 정체 반영 |
| 출발 직후 나쁜 구간 → 미리 준비 | 외기 · 온도 −1℃ · 바람 +1 · 뒷창문 10% |
| 급제동 반복 | 바람 세기 실제 상승 |
| 「속이 안 좋아」 | 온도 −2℃ · 바람 +2 · 뒷창문 20% + 대답 |
| release APK | 새로 설치해 같은 흐름 확인 |

에뮬레이터에서만 드러난 것(고침): TTS·STT·내비 권한, SDK 좌석 탑승 값 방향, 공조 영역이 좌석 묶음, 공조가 꺼지면 설정 온도를 못 읽음, 내비가 앞에 오면 앱이 얼려짐(→ 포그라운드 서비스), 도심 출발 1km 의 과한 예보.
아직: 실제 경로 끝까지 달려 도착 리포트(에뮬레이터 내비 위치가 움직이지 않음).

## 안내 음성
출품 APK 는 정해진 안내 문장(881개)을 미리 합성한 음성 파일(`assets/voice/`, ElevenLabs · 상업 이용 가능 요금제)로 먼저 틀고, 휴게소 이름처럼 그때그때 바뀌는 말만 Pleos TTS 로 말합니다.
**공개 저장소에는 음성 파일을 넣지 않았습니다**(개발자 본인 목소리). 파일이 없으면 앱은 모든 안내를 Pleos TTS 로 말합니다.
문장을 고치면: `VoiceLines` 테스트로 문장 목록을 뽑고 `python tools/make_voice.py` (바뀐 문장만 새로 합성, 받아쓰기로 확인).

## 쓰는 Pleos SDK
| SDK | 기능 |
|---|---|
| Vehicle 2.3.2 | 좌석 탑승·벨트, 내외기·온도·풍량, 창문, 브레이크 페달, 조향각 |
| NaviHelper 3.0.5 | 경로 시작·도착, 회전 안내, 혼잡, 주의 구간, 경유지 도착 |
| TextToSpeech / SpeechToText 3.3.1 | 그때그때 바뀌는 안내(정해진 문장은 음성 파일), 「속이 안 좋아」·「응」 |
| FusedLocation 1.1.3 | 고른 휴게소 근접 알림 |
| AAOS CarPropertyManager · CarUxRestrictions | 차속, 주행 중 제한 |
생성형 AI 는 쓰지 않습니다(규칙 기반 — 예측 가능한 짧은 안내).

## 실행
- 자동차용 안드로이드(Pleos Connect)에서는 실제 차량·내비 사건으로 동작합니다.
- 어느 기기에서나 처음 화면의 **「시연: 양평 나들이」** 로 시나리오 A 를 20배속 재생합니다.
- 장면 바로 보기: `adb shell am start -n app.backseatclear/.MainActivity --ef jump 42.5`
- 에뮬레이터 시험(차의 사용자는 10번): `bash tools/emu_test.sh` · 시연 녹화 `bash tools/record_demo.sh`
- APK: 공모전 심사용 release APK 는 비공개 제출 경로로 냈습니다(공개 저장소에는 없음). 직접 빌드: `build.ps1 assembleRelease`

## 테스트
`gradle testDebugUnitTest` — 판단 로직·예보·상태 기계 + 화면 캡처 10개(Robolectric, 결과 `app/build/screens/`).

## 개인정보
기록은 차 안(앱 내부 파일)에만 저장합니다. 목소리는 알아들은 뒤 바로 버리고, 정확한 이동 경로는 남기지 않습니다.

## 라이선스
글꼴 Asta Sans — SIL Open Font License 1.1 (`OFL_AstaSans.txt`).

## 문서
- [개인정보 처리방침](docs/PRIVACY.md) · [이용 안내](docs/TERMS.md) · [이용권](docs/LICENSES.md)
- 화면 시안 `docs/design/` · 사용자 조사 요약 `docs/research/`
