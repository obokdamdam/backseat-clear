package app.backseatclear.core

import app.backseatclear.core.care.CareAction
import app.backseatclear.core.model.Weather
import app.backseatclear.core.voice.Phrases
import org.junit.Test
import java.io.File

/**
 * 앱이 할 수 있는 문장을 모두 뽑아 build/voice_lines.json 으로 쓴다 → tools/make_voice.py 가 개발자 본인 목소리로 미리 합성한다.
 * 숫자가 바뀌는 문장은 자주 나오는 범위만(분 0~30, km 1~10). 여기 없는 문장은 앱이 Pleos TTS 로 말한다.
 */
class VoiceLines {
    @Test fun `앱 안내 문장 목록 만들기`() {
        val lines = linkedSetOf<String>()
        val all = Weather.entries
        val bad = listOf(Weather.OVERCAST, Weather.RAIN)
        val places = listOf("산길", "정체 구간", "길")
        lines += Phrases.autoStart().text
        all.forEach { lines += Phrases.forecast(it, null, null, null).text }
        for (now in listOf(Weather.CLEAR, Weather.CLOUDY)) for (b in bad) for (p in places) for (m in 0..30)
            lines += Phrases.forecast(now, b, m, p).text
        for (now in bad) for (b in bad) for (p in places) for (m in 1..30) // 지금도 흐림·비인 경로(정체 출발 등)
            lines += Phrases.forecast(now, b, m, p).text
        for (m in 1..30) { lines += Phrases.prepare(m).text; lines += Phrases.prepare(m, jam = true).text }
        lines += Phrases.coachCurve().text; lines += Phrases.coachCongestion().text; lines += Phrases.praise().text
        lines += Phrases.complaintAck().text
        for (k in 1..10) lines += Phrases.askRest(k).text
        for (m in 1..30) lines += Phrases.restAdded(m).text
        lines += Phrases.nearRest().text
        for (m in 0..30) lines += Phrases.resume(m).text
        lines += Phrases.restore().text
        all.forEach { lines += Phrases.arrive(it).text; lines += Phrases.status(it, null).text }
        CareAction.Kind.entries.forEach { lines += Phrases.unsupported(it).text }
        lines += Phrases.notHeard().text
        val json = lines.joinToString(",\n", "[\n", "\n]\n") { "  \"" + it.replace("\"", "\\\"") + "\"" }
        File("build/voice_lines.json").writeText(json, Charsets.UTF_8)
        println("문장 ${lines.size}개")
    }
}
