package app.backseatclear.core.voice

import app.backseatclear.core.care.CareAction
import app.backseatclear.core.model.Audience
import app.backseatclear.core.model.Weather

enum class Kind {
    AUTO_START, FORECAST, PREPARE, COACH, PRAISE, COMPLAINT_ACK, ASK_REST, CONFIRM_REST,
    NEAR_REST, RESUME, RESTORE, ARRIVE, UNSUPPORTED, NOT_HEARD, STATUS,
}

data class Utterance(val text: String, val to: Audience, val kind: Kind)

/** 앱이 하는 말. 한 문장 · 3초 안 · 운전자를 탓하지 않는다 */
object Phrases {
    fun autoStart() = Utterance("뒷자리에 누가 탔네요. 멀미 예보를 켤게요.", Audience.ALL, Kind.AUTO_START)

    fun forecast(now: Weather, nextBad: Weather?, inMinutes: Int?, where: String?): Utterance {
        val text = if (nextBad == null || inMinutes == null) "오늘 길은 ${now.isYo}."
        // 출발하자마자 나쁜 구간이면 「0분 뒤」 대신 「출발 직후」(에뮬레이터 실제 경로에서 나옴)
        // 지금 날씨와 겹치지 않게 「오늘 길은 흐림, … 흐림이에요」 대신 출발 직후만 말한다
        else if (inMinutes <= 0) "오늘 길은 출발 직후 ${where ?: "길"}이 ${nextBad.isYo}."
        else "오늘 길은 ${now.label}, ${inMinutes}분 뒤 ${where ?: "길"}은 ${nextBad.isYo}."
        return Utterance(text, Audience.ALL, Kind.FORECAST)
    }

    /** 이유에 맞춰 말한다 — 정체 구간인데 「굽은 길」이라 하던 것(에뮬레이터 실제 경로에서 발견) */
    fun prepare(minutesAhead: Int, jam: Boolean = false) =
        Utterance("${minutesAhead}분 뒤부터 ${if (jam) "가다 서는 길이에요" else "굽은 길이 이어져요"}. 미리 바람을 바꿔 둘게요.", Audience.REAR, Kind.PREPARE)

    fun coachCurve() = Utterance("살살 가면 뒷자리가 편해요.", Audience.DRIVER, Kind.COACH)
    fun coachCongestion() = Utterance("앞차와 거리를 조금 두면 덜 출렁여요.", Audience.DRIVER, Kind.COACH)
    fun praise() = Utterance("이번 구간 아주 부드러웠어요.", Audience.DRIVER, Kind.PRAISE)

    fun complaintAck() = Utterance("알았어요. 시원한 바람 넣고 창문 조금 열게요.", Audience.REAR, Kind.COMPLAINT_ACK)
    fun askRest(km: Int) = Utterance("${km}km 앞 쉼터에 들를까요? '응' 하시면 넣을게요.", Audience.DRIVER, Kind.ASK_REST)
    fun restAdded(minutes: Int) = Utterance("쉼터를 넣었어요. ${minutes}분 뒤예요.", Audience.ALL, Kind.CONFIRM_REST)
    fun nearRest() = Utterance("곧 쉼터예요. 바깥 공기 마시고 가요.", Audience.REAR, Kind.NEAR_REST)
    fun resume(curvyMinutesLeft: Int) =
        Utterance(if (curvyMinutesLeft > 0) "다시 출발해요. 산길은 ${curvyMinutesLeft}분만 남았어요." else "다시 출발해요.", Audience.REAR, Kind.RESUME)
    fun restore() = Utterance("창문 닫고 온도 원래대로 했어요.", Audience.ALL, Kind.RESTORE)
    fun arrive(w: Weather) = Utterance("도착! 오늘 뒷자리는 ${w.wasYo}.", Audience.ALL, Kind.ARRIVE)

    fun unsupported(kind: CareAction.Kind) =
        Utterance("이 차에서는 ${kind.label} 조절이 안 돼요. 다른 것만 바꿀게요.", Audience.ALL, Kind.UNSUPPORTED)
    fun notHeard() = Utterance("잘 못 들었어요. '응'이나 '아니'로 말해 주세요.", Audience.ALL, Kind.NOT_HEARD)
    fun status(now: Weather, next: String?) =
        Utterance(if (next == null) "지금 뒷자리 ${now.isYo}." else "지금 뒷자리 ${now.label}, $next", Audience.ALL, Kind.STATUS)
}

/** 들은 말 → 뜻 */
enum class Intent { COMPLAINT, YES, NO, STATUS, QUIET, STOP_CARE, UNKNOWN }

object IntentParser {
    private val complaint = listOf("속이", "멀미", "토할", "어지러", "울렁", "메스꺼")
    private val yes = listOf("응", "좋아", "그래", "넣어", "네", "예", "들르자", "들러")
    private val no = listOf("아니", "괜찮아", "됐어", "싫어", "안 들러")
    private val status = listOf("지금 어때", "날씨", "어때")
    private val quiet = listOf("조용히")
    private val stop = listOf("닫아", "그만", "꺼 줘", "꺼줘")

    fun parse(raw: String): Intent {
        val t = raw.replace(" ", "").trim()
        fun has(ws: List<String>) = ws.any { t.contains(it.replace(" ", "")) }
        return when {
            has(quiet) -> Intent.QUIET
            has(complaint) -> Intent.COMPLAINT
            has(no) -> Intent.NO // 「안 들러」 가 「들러」 보다 먼저
            has(stop) -> Intent.STOP_CARE
            has(yes) -> Intent.YES
            has(status) -> Intent.STATUS
            else -> Intent.UNKNOWN
        }
    }
}

/**
 * 말하기 규칙.
 * - 내비·안전 안내가 먼저: 내비가 말하는 중이면 기다렸다가, 30초 넘게 밀린 말은 버린다.
 * - 운전자 안내(COACH)는 10분에 최대 한 번.
 * - 같은 종류 안내는 한 구간(stretchId)에 한 번.
 * - 「조용히」 모드면 말하지 않는다(조치는 계속).
 */
class VoicePolicy {
    private data class Pending(val u: Utterance, val atMs: Long)

    private val queue = ArrayDeque<Pending>()
    private var naviSpeaking = false
    private var lastCoachMs: Long? = null
    private val saidInStretch = mutableSetOf<Pair<Kind, Int>>()
    var quiet = false
        private set

    fun setQuiet() { quiet = true; queue.clear() }

    /** 말할 것 넣기. 바로 말해도 되면 돌려준다. */
    fun offer(u: Utterance, nowMs: Long, stretchId: Int): List<Utterance> {
        if (quiet) return emptyList()
        if (u.kind == Kind.COACH) {
            val last = lastCoachMs
            if (last != null && nowMs - last < 10 * 60_000) return emptyList()
        }
        if (u.kind in ONCE_PER_STRETCH && !saidInStretch.add(u.kind to stretchId)) return emptyList()
        if (u.kind == Kind.COACH) lastCoachMs = nowMs
        if (naviSpeaking) { queue += Pending(u, nowMs); return emptyList() }
        return listOf(u)
    }

    /** 내비 안내 시작/끝. 끝나면 밀린 말 중 30초 안 된 것만 돌려준다 */
    fun naviSpeaking(on: Boolean, nowMs: Long): List<Utterance> {
        naviSpeaking = on
        if (on) return emptyList()
        val out = queue.filter { nowMs - it.atMs <= 30_000 }.map { it.u }
        queue.clear()
        return out
    }

    companion object {
        val ONCE_PER_STRETCH = setOf(Kind.PREPARE, Kind.COACH, Kind.PRAISE, Kind.RESTORE)
    }
}
