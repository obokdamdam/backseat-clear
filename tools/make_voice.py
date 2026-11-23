"""앱 안내 음성 파일 만들기 — 개발자 본인 목소리(ElevenLabs eleven_v4, 유료 요금제).

입력: app/build/voice_lines.json  (단위 테스트 VoiceLines 가 앱 코드에서 뽑은 문장 목록)
출력: app/src/main/assets/voice/<해시>.mp3 + index.json {문장: 파일}
- 숫자는 우리말로 읽게 바꿔 합성(4분 → 사 분, 3km → 삼 킬로미터). 화면 글자는 그대로.
- 합성마다 ElevenLabs 받아쓰기로 확인, 0.95 미만이면 최대 4번 다시 뽑아 가장 가까운 것. 끝내 0.9 미만은 index 에서 빼고 report 에 남긴다(앱은 그 문장을 Pleos TTS 로 말함).
- 캐시·임시 파일은 tools/voice_cache (D:). 키는 ~/.lomon/keys/elevenlabs 읽기만.
쓰는 법: python tools/make_voice.py
"""
import difflib
import hashlib
import json
import re
import subprocess
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINES = ROOT / "app" / "build" / "voice_lines.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "voice"
CACHE = ROOT / "tools" / "voice_cache"
VOICE_ID = (Path(__file__).parent / "voice_id.txt").read_text(encoding="utf-8").strip()  # 목소리 ID 는 저장소에 올리지 않는다(.gitignore)
MODEL = "eleven_v4"
TAG = "warmly"

DIGIT = "영일이삼사오육칠팔구"


def sino(n: int) -> str:
    if n == 0:
        return "영"
    tens, ones = divmod(n, 10)
    s = ("" if tens == 0 else ("" if tens == 1 else DIGIT[tens]) + "십")
    return s + ("" if ones == 0 else DIGIT[ones])


def spoken(text: str) -> str:
    t = re.sub(r"(\d+)\s*km", lambda m: f"{sino(int(m.group(1)))} 킬로미터", text)
    t = re.sub(r"(\d+)분", lambda m: f"{sino(int(m.group(1)))} 분", t)
    return t.replace("오늘 뒷자리는", "오늘, 뒷자리는")


def norm(x: str) -> str:
    x = re.sub(r"(\d+)\s*(km|킬로미터)", lambda m: sino(int(m.group(1))) + "킬로미터", x)
    x = re.sub(r"(\d+)", lambda m: sino(int(m.group(1))), x)
    x = x.replace("뒤에요", "뒤예요")
    return re.sub(r"[^가-힣]", "", x)


def key() -> str:
    return (Path.home() / ".lomon" / "keys" / "elevenlabs").read_text(encoding="utf-8").strip()


def synth(said: str, mp3: Path):
    body = json.dumps({"text": said, "model_id": MODEL,
                       "voice_settings": {"stability": 0.5, "similarity_boost": 0.8, "use_speaker_boost": True}}).encode("utf-8")
    req = urllib.request.Request(f"https://api.elevenlabs.io/v1/text-to-speech/{VOICE_ID}?output_format=mp3_44100_128",
                                 data=body, headers={"xi-api-key": key(), "Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        mp3.write_bytes(r.read())


def stt(mp3: Path) -> str:
    txt = mp3.with_suffix(".txt")
    if txt.exists():
        return txt.read_text(encoding="utf-8")
    b = uuid.uuid4().hex
    nl = "\r\n"
    head = (f"--{b}{nl}Content-Disposition: form-data; name=\"model_id\"{nl}{nl}scribe_v1{nl}"
            f"--{b}{nl}Content-Disposition: form-data; name=\"language_code\"{nl}{nl}ko{nl}"
            f"--{b}{nl}Content-Disposition: form-data; name=\"file\"; filename=\"a.mp3\"{nl}Content-Type: audio/mpeg{nl}{nl}").encode()
    req = urllib.request.Request("https://api.elevenlabs.io/v1/speech-to-text", data=head + mp3.read_bytes() + f"{nl}--{b}--{nl}".encode(),
                                 headers={"xi-api-key": key(), "Content-Type": f"multipart/form-data; boundary={b}"}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as r:
        t = json.loads(r.read())["text"]
    txt.write_text(t, encoding="utf-8")
    return t


def make(text: str):
    said = f"[{TAG}] {spoken(text)}"
    best, best_score = None, -1.0
    for take in range(4):
        mp3 = CACHE / (hashlib.sha1(f"{VOICE_ID}|{MODEL}|{said}|{take}".encode()).hexdigest()[:16] + ".mp3")
        try:
            if not mp3.exists():
                synth(said, mp3)
            score = difflib.SequenceMatcher(None, norm(text), norm(stt(mp3))).ratio()
        except Exception as e:  # 네트워크 오류는 다음 테이크로
            print("  오류", text, e)
            continue
        if score > best_score:
            best, best_score = mp3, score
        if score >= 0.95:
            break
    if best is None:
        return text, None, 0.0
    name = hashlib.sha1(text.encode("utf-8")).hexdigest()[:12] + ".mp3"
    # 앱용: 모노 · 앞뒤 무음 정리 · 음량 맞춤(-16 LUFS)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(best), "-ac", "1", "-ar", "24000",
                    "-af", "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,loudnorm=I=-16:TP=-1.5",
                    "-b:a", "64k", str(OUT / name)], check=True)
    return text, name, best_score


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    lines = json.loads(LINES.read_text(encoding="utf-8"))
    with ThreadPoolExecutor(4) as ex:
        results = list(ex.map(make, lines))
    index = {t: n for t, n, s in results if n and s >= 0.9}
    low = [(t, s) for t, n, s in results if not n or s < 0.9]
    (OUT / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=0), encoding="utf-8")
    for f in OUT.glob("*.mp3"):  # index 에 없는 파일은 지운다(앱 용량)
        if f.name not in index.values():
            f.unlink()
    report = ROOT / "tools" / "voice_report.txt"
    report.write_text("\n".join(f"{s:.2f}\t{t}" for t, s in sorted(low, key=lambda x: x[1])), encoding="utf-8")
    size = sum(f.stat().st_size for f in OUT.glob("*.mp3")) / 1e6
    print(f"완성 {len(index)}/{len(lines)} 문장 · {size:.1f}MB · 빠진 것 {len(low)}개 → {report}")


if __name__ == "__main__":
    main()
