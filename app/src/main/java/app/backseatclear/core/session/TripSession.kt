package app.backseatclear.core.session

import app.backseatclear.core.care.CareAction
import app.backseatclear.core.care.CarePlanner
import app.backseatclear.core.forecast.ForecastEngine
import app.backseatclear.core.forecast.LiveScorer
import app.backseatclear.core.forecast.SegmentForecast
import app.backseatclear.core.forecast.WeatherSpan
import app.backseatclear.core.model.Reason
import app.backseatclear.core.model.RestStop
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Route
import app.backseatclear.core.model.Seat
import app.backseatclear.core.model.Weather
import app.backseatclear.core.voice.Intent
import app.backseatclear.core.voice.IntentParser
import app.backseatclear.core.voice.Kind
import app.backseatclear.core.voice.Phrases
import app.backseatclear.core.voice.Utterance
import app.backseatclear.core.voice.VoicePolicy
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 차·내비·음성에서 들어오는 사건 */
sealed interface TripEvent {
    data class SeatChanged(val seat: Seat, val occupied: Boolean, val belted: Boolean) : TripEvent
    data class RouteStarted(val route: Route) : TripEvent
    /** 출발점에서 지금까지 달린 거리(km). 내비 남은 거리 또는 트립 거리에서 */
    data class Progress(val km: Double) : TripEvent
    data object Brake : TripEvent
    data class Speed(val kmh: Double) : TripEvent
    data class Steering(val deg: Double) : TripEvent
    data class Recirculation(val on: Boolean) : TripEvent
    /** 사람이 직접 공조·창문을 바꿈 */
    data class UserAdjusted(val kind: CareAction.Kind) : TripEvent
    data class NaviSpeaking(val on: Boolean) : TripEvent
    data class Heard(val text: String) : TripEvent
    data object ListenTimeout : TripEvent
    data object NearRest : TripEvent
    data object LeftRest : TripEvent
    data object Arrived : TripEvent
}

/** TripSession 이 내보내는 명령 — 포트(Pleos SDK 또는 모의)가 실행한다 */
sealed interface Command {
    data class Say(val u: Utterance) : Command
    data class Listen(val seconds: Int) : Command
    data class Apply(val action: CareAction) : Command
    data class AddWaypoint(val stop: RestStop) : Command
    data class WatchArrival(val stop: RestStop, val radiusM: Int = 500) : Command
    data class SaveTrip(val report: TripReport) : Command
}

enum class Phase { IDLE, BEFORE_DEPARTURE, DRIVING, RESTING, ARRIVED }

data class TripReport(
    val routeName: String,
    val minutes: Int,
    val rider: Rider?,
    val overall: Weather,
    /** 실제로 지난 날씨 띠 (분 단위) */
    val timeline: List<WeatherSpan>,
    val smoothRatio: Double?,
    val prepares: Int,
    val complaints: Int,
    val minutesToRecover: Int?,
    val rests: Int,
    val nextTime: String?,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val riders: Map<Seat, Rider> = emptyMap(),
    val routeName: String = "",
    val totalMinutes: Double = 0.0,
    val forecast: List<WeatherSpan> = emptyList(),
    val nowFraction: Double = 0.0,
    val weather: Weather = Weather.CLEAR,
    /** 큰 글자 위 작은 줄 — 「지금 뒷자리」 「2km 뒤 굽은 길」 */
    val over: String = "지금 뒷자리",
    /** 큰 글자 — 날씨 이름 또는 「속이 안 좋대요」 */
    val headline: String = Weather.CLEAR.label,
    val under: String = "",
    val chips: List<String> = emptyList(),
    val speaking: Utterance? = null,
    val listeningPrompt: String? = null,
    val question: String? = null,
    val notice: String? = null,
    val report: TripReport? = null,
)

/**
 * 뒷자리 맑음의 두뇌. 사건을 받아 화면 상태와 명령을 낸다. 안드로이드·SDK 를 모른다(단위 테스트 가능).
 * 시간은 호출하는 쪽이 nowMs 로 넣는다(단조 시계).
 */
class TripSession(
    private val riderFor: (Seat) -> Rider = { Rider(it, it.label) },
    /** 이 차가 지원하는 조치 — 차량 capability 확인이 비동기라 그때그때 묻는다 */
    supported: () -> Set<CareAction.Kind> = { CareAction.Kind.entries.toSet() },
) {
    var state = UiState(); private set

    private val planner = CarePlanner(supported)
    private val voice = VoicePolicy()
    private val live = LiveScorer()

    private val riders = linkedMapOf<Seat, Rider>()
    private var route: Route? = null
    private var forecast: List<SegmentForecast> = emptyList()
    private var spans: List<WeatherSpan> = emptyList()
    private var startMs = 0L
    private var km = 0.0
    private var weather = Weather.CLEAR
    private var stretchId = 0

    private val prepared = mutableSetOf<Int>()      // 준비를 마친 span 번호
    private val coached = mutableSetOf<Int>()
    private val intensified = mutableSetOf<Int>()
    private val closed = mutableSetOf<Int>()        // 끝나고 칭찬·원래대로 한 span
    private var prepares = 0

    private var complaintAtMs: Long? = null
    private var complaints = 0
    private var complaintSpan: Int? = null
    private var recoveredAtMs: Long? = null
    private var easedSpan: Int? = null              // 쉰 뒤 한 단계 낮춰 보는 span
    private var asking: RestStop? = null
    private var askDeadlineMs = 0L
    private var notHeardOnce = false
    private var rests = 0

    private val history = mutableListOf<Pair<Double, Weather>>() // (경과 분, 날씨)

    private val rearRider get() = riders.values.firstOrNull { it.seat.isRear }

    fun handle(e: TripEvent, nowMs: Long): List<Command> {
        val out = mutableListOf<Command>()
        // 켜지기 전(뒷자리 탑승 + 경로 시작 전)에는 좌석·경로 사건만 받는다
        if (route == null && e !is TripEvent.SeatChanged && e !is TripEvent.RouteStarted) return out
        when (e) {
            is TripEvent.SeatChanged -> onSeat(e)
            is TripEvent.RouteStarted -> onRoute(e.route, nowMs, out)
            is TripEvent.Progress -> onProgress(e.km, nowMs, out)
            TripEvent.Brake -> { live.onBrake(nowMs); reevaluate(nowMs, out) }
            is TripEvent.Speed -> { live.onSpeed(nowMs, e.kmh); reevaluate(nowMs, out) }
            is TripEvent.Steering -> { live.onSteering(nowMs, e.deg); reevaluate(nowMs, out) }
            is TripEvent.Recirculation -> live.onRecirculation(nowMs, e.on)
            is TripEvent.UserAdjusted -> planner.userAdjusted(e.kind)
            // 밀렸던 말은 이미 규칙을 통과했으니 그대로 내보낸다
            is TripEvent.NaviSpeaking -> voice.naviSpeaking(e.on, nowMs).forEach { out += Command.Say(it); state = state.copy(speaking = it); spokeAtMs = nowMs }
            is TripEvent.Heard -> onHeard(e.text, nowMs, out)
            TripEvent.ListenTimeout -> { asking = null; state = state.copy(listeningPrompt = null, question = null) }
            TripEvent.NearRest -> onNearRest(nowMs, out)
            TripEvent.LeftRest -> onLeftRest(nowMs, out)
            TripEvent.Arrived -> onArrived(nowMs, out)
        }
        return out
    }

    // ───────── 좌석 · 자동 시작 ─────────
    private fun onSeat(e: TripEvent.SeatChanged) {
        if (e.occupied) riders[e.seat] = riderFor(e.seat) else riders.remove(e.seat)
        state = state.copy(riders = riders.toMap())
    }

    private fun onRoute(r: Route, nowMs: Long, out: MutableList<Command>) {
        if (rearRider == null) return // 뒷자리에 아무도 없으면 켜지지 않는다
        route = r
        startMs = nowMs
        km = 0.0
        forecast = ForecastEngine.forecast(r, rearRider)
        spans = ForecastEngine.spans(r, forecast)
        weather = forecast.firstOrNull()?.weather ?: Weather.CLEAR
        history += 0.0 to weather
        say(Phrases.autoStart(), out, nowMs)
        val bad = spans.firstOrNull { it.weather.atLeast(Weather.OVERCAST) }
        say(Phrases.forecast(spans.first().weather, bad?.weather, bad?.fromMinute?.roundToInt(), bad?.let { placeOf(it) }), out, nowMs)
        state = state.copy(
            phase = Phase.BEFORE_DEPARTURE, routeName = r.name, totalMinutes = r.totalMinutes,
            forecast = spans, weather = weather,
        )
        refresh()
        // 내비가 진행 거리를 경로 시작보다 먼저 보내는 경우가 있다(Pleos 에뮬레이터) → 출발 지점 기준으로 한 번 확인해
        // 출발 직후 나쁜 구간이면 바로 「미리 준비」한다
        onProgress(0.0, nowMs, out)
    }

    // ───────── 달리는 중 ─────────
    private fun onProgress(newKm: Double, nowMs: Long, out: MutableList<Command>) {
        val r = route ?: return
        if (state.phase == Phase.ARRIVED) return
        km = newKm
        if (state.phase == Phase.BEFORE_DEPARTURE && km > 0.05) state = state.copy(phase = Phase.DRIVING)
        val span = spanIndexAt(km)
        live.sampleCurvy(nowMs, span?.let { spans[it].weather.atLeast(Weather.OVERCAST) && Reason.CURVES in spans[it].reasons } == true)

        // 다음 나쁜 구간 2km 전: 차가 먼저 준비
        spans.forEachIndexed { i, sp ->
            if (sp.weather.atLeast(Weather.OVERCAST) && i !in prepared && km >= sp.fromKm - 2.0 && km < sp.toKm) {
                prepared += i
                prepares++
                stretchId++
                planner.newStretch()
                val plan = planner.prepare()
                plan.actions.forEach { out += Command.Apply(it) }
                plan.newlyUnsupported.forEach { say(Phrases.unsupported(it), out, nowMs) }
                val ahead = ceil(r.minuteAt(sp.fromKm) - r.minuteAt(km)).toInt().coerceAtLeast(1)
                say(Phrases.prepare(ahead, jam = Reason.CONGESTION in sp.reasons && Reason.CURVES !in sp.reasons), out, nowMs)
            }
            // 나쁜 구간 바로 앞(0.3km): 운전자에게 한마디
            if (sp.weather.atLeast(Weather.OVERCAST) && i !in coached && km >= sp.fromKm - 0.3 && km < sp.toKm) {
                coached += i
                say(if (Reason.CURVES in sp.reasons) Phrases.coachCurve() else Phrases.coachCongestion(), out, nowMs)
            }
            // 나쁜 구간을 다 지남: 칭찬 + 원래대로
            if (sp.weather.atLeast(Weather.OVERCAST) && i in prepared && i !in closed && km >= sp.toKm) {
                closed += i
                if (live.calmFor(nowMs)) say(Phrases.praise(), out, nowMs)
                val plan = planner.restore()
                if (plan.actions.isNotEmpty()) {
                    plan.actions.forEach { out += Command.Apply(it) }
                    say(Phrases.restore(), out, nowMs)
                }
                complaintAtMs = null
                easedSpan = null
            }
        }
        reevaluate(nowMs, out)
    }

    /** 지금 날씨 다시 계산 */
    private fun reevaluate(nowMs: Long, out: MutableList<Command>) {
        val r = route ?: return
        if (state.phase == Phase.ARRIVED) return
        val seg = forecast.firstOrNull { km >= it.segment.startKm && km < it.segment.endKm } ?: forecast.lastOrNull() ?: return
        val spanIdx = spanIndexAt(km)
        val add = live.adjustment(nowMs)
        var w = ForecastEngine.weatherOf(seg.score + add, rearRider?.earlierSteps ?: 0)
        if (spanIdx != null && spanIdx == easedSpan) w = w.lower()
        if (complaintAtMs != null && state.phase != Phase.RESTING && recoveredAtMs == null) w = Weather.RAIN
        // 점수가 예보보다 오르면 바람 한 단계 더(말 없이)
        if (spanIdx != null && add >= 6 && seg.weather.atLeast(Weather.OVERCAST) && spanIdx !in intensified) {
            intensified += spanIdx
            planner.intensify().actions.forEach { out += Command.Apply(it) }
        }
        if (w != weather) {
            weather = w
            history += minuteNow(nowMs) to w
        }
        refresh(nowMs)
    }

    // ───────── 음성 ─────────
    private fun onHeard(text: String, nowMs: Long, out: MutableList<Command>) {
        val intent = IntentParser.parse(text)
        val stop = asking
        if (stop != null) {
            when (intent) {
                Intent.YES -> {
                    asking = null
                    out += Command.AddWaypoint(stop)
                    out += Command.WatchArrival(stop)
                    val r = route
                    val minutes = if (r != null) ceil(r.minuteAt(stop.km) - r.minuteAt(km)).toInt().coerceAtLeast(1) else 3
                    say(Phrases.restAdded(minutes), out, nowMs)
                    state = state.copy(listeningPrompt = null, question = null)
                    return
                }
                Intent.NO -> { asking = null; state = state.copy(listeningPrompt = null, question = null); return }
                Intent.UNKNOWN -> {
                    if (!notHeardOnce) { notHeardOnce = true; say(Phrases.notHeard(), out, nowMs); out += Command.Listen(8) }
                    else { asking = null; state = state.copy(listeningPrompt = null, question = null) }
                    return
                }
                else -> asking = null // 다른 말이면 질문은 접고 그 말을 처리
            }
        }
        when (intent) {
            Intent.COMPLAINT -> onComplaint(nowMs, out)
            Intent.STATUS -> {
                val next = spans.firstOrNull { it.fromKm > km && it.weather != weather }
                val r = route
                val nextText = if (next != null && r != null) "${ceil(r.minuteAt(next.fromKm) - r.minuteAt(km)).toInt()}분 뒤 ${next.weather.isYo}." else null
                say(Phrases.status(weather, nextText), out, nowMs)
            }
            Intent.QUIET -> { voice.setQuiet(); state = state.copy(notice = "이번 길은 말없이 조치만 할게요") }
            Intent.STOP_CARE -> {
                planner.userAdjusted(CareAction.Kind.WINDOW); planner.userAdjusted(CareAction.Kind.FAN)
                out += Command.Apply(CareAction.RearWindow(0)); out += Command.Apply(CareAction.RearFan(0))
            }
            else -> Unit
        }
        refresh(nowMs)
    }

    private fun onComplaint(nowMs: Long, out: MutableList<Command>) {
        complaints++
        complaintAtMs = nowMs
        recoveredAtMs = null
        complaintSpan = spanIndexAt(km)
        weather = Weather.RAIN
        history += minuteNow(nowMs) to Weather.RAIN
        planner.newStretch()
        val plan = planner.relieve()
        plan.actions.forEach { out += Command.Apply(it) }
        plan.newlyUnsupported.forEach { say(Phrases.unsupported(it), out, nowMs) }
        say(Phrases.complaintAck(), out, nowMs)
        val stop = route?.restStops?.firstOrNull { it.km > km && it.km - km <= 6.0 }
        if (stop != null) {
            asking = stop
            notHeardOnce = false
            askDeadlineMs = nowMs + 8_000
            val ask = Phrases.askRest(ceil(stop.km - km).toInt())
            say(ask, out, nowMs)
            out += Command.Listen(8)
            state = state.copy(question = ask.text.substringBefore(" '"), listeningPrompt = "「응」 또는 「아니」")
        }
        refresh(nowMs)
    }

    // ───────── 쉼터 ─────────
    private fun onNearRest(nowMs: Long, out: MutableList<Command>) {
        say(Phrases.nearRest(), out, nowMs)
        rests++
        state = state.copy(phase = Phase.RESTING)
        refresh(nowMs)
    }

    private fun onLeftRest(nowMs: Long, out: MutableList<Command>) {
        recoveredAtMs = nowMs
        complaintAtMs = null
        state = state.copy(phase = Phase.DRIVING)
        val idx = spanIndexAt(km)
        easedSpan = idx
        val r = route
        val left = if (idx != null && r != null && spans[idx].weather.atLeast(Weather.OVERCAST))
            ceil(r.minuteAt(spans[idx].toKm) - r.minuteAt(km)).toInt() else 0
        say(Phrases.resume(left), out, nowMs)
        reevaluate(nowMs, out)
    }

    // ───────── 도착 ─────────
    private fun onArrived(nowMs: Long, out: MutableList<Command>) {
        val r = route ?: return
        val restore = planner.restore()
        restore.actions.forEach { out += Command.Apply(it) }
        val minutes = minuteNow(nowMs)
        history += minutes to weather
        val timeline = timelineOf(minutes)
        val overall = timeline.groupBy { it.weather }
            .maxByOrNull { (_, l) -> l.sumOf { it.toMinute - it.fromMinute } }?.key ?: Weather.CLEAR
        say(Phrases.arrive(overall), out, nowMs)
        val rider = rearRider
        val learned = complaints > 0 && complaintSpan?.let { Reason.CURVES in spans[it].reasons } == true
        val report = TripReport(
            routeName = r.name, minutes = minutes.roundToInt(), rider = rider, overall = overall, timeline = timeline,
            smoothRatio = live.smoothRatio(), prepares = prepares, complaints = complaints,
            minutesToRecover = complaintStartMinute?.let { start -> recoveredMinute?.let { (it - start).roundToInt() } },
            rests = rests,
            nextTime = if (learned && rider != null) "다음에는 ${rider.nickname} 산길 예보를 한 단계 일찍 잡을게요." else null,
        )
        out += Command.SaveTrip(report)
        state = state.copy(phase = Phase.ARRIVED, report = report, weather = overall, chips = emptyList(), question = null, listeningPrompt = null)
    }

    private var complaintStartMinute: Double? = null
    private var recoveredMinute: Double? = null

    // ───────── 도움 함수 ─────────
    private fun say(u: Utterance, out: MutableList<Command>, nowMs: Long = 0L) {
        if (u.kind == Kind.COMPLAINT_ACK) complaintStartMinute = minuteNow(nowMs)
        if (u.kind == Kind.RESUME) recoveredMinute = minuteNow(nowMs)
        voice.offer(u, nowMs, stretchId).forEach { out += Command.Say(it); state = state.copy(speaking = it); spokeAtMs = nowMs }
    }

    private var spokeAtMs = 0L

    private fun minuteNow(nowMs: Long) = (nowMs - startMs) / 60_000.0

    private fun spanIndexAt(k: Double): Int? = spans.indexOfFirst { k >= it.fromKm && k < it.toKm }.takeIf { it >= 0 }

    private fun placeOf(sp: WeatherSpan) = when {
        Reason.CURVES in sp.reasons -> "산길"
        Reason.CONGESTION in sp.reasons -> "정체 구간"
        else -> "길"
    }

    private fun timelineOf(endMinute: Double): List<WeatherSpan> {
        val out = mutableListOf<WeatherSpan>()
        history.forEachIndexed { i, (m, w) ->
            val to = history.getOrNull(i + 1)?.first ?: endMinute
            if (to <= m) return@forEachIndexed
            val last = out.lastOrNull()
            if (last != null && last.weather == w) out[out.lastIndex] = last.copy(toMinute = to)
            else out += WeatherSpan(w, m, to, 0.0, 0.0, emptySet())
        }
        return out
    }

    private fun refresh(nowMs: Long = 0L) {
        val r = route ?: return
        val idx = spanIndexAt(km)
        val nextBad = spans.withIndex().firstOrNull { (i, sp) -> sp.weather.atLeast(Weather.OVERCAST) && sp.fromKm > km && i !in closed }?.value
        val distToBad = nextBad?.let { it.fromKm - km }
        val inBad = idx != null && spans[idx].weather.atLeast(Weather.OVERCAST)
        val over: String
        val headline: String
        val under: String
        when {
            weather == Weather.RAIN && complaintAtMs != null -> {
                over = rearRider?.let { "${it.seat.label} · ${it.nickname}" } ?: "뒷자리"
                headline = "속이 안 좋대요"; under = ""
            }
            distToBad != null && distToBad <= 2.0 -> {
                over = "${distToBad.coerceAtLeast(0.1).let { if (it >= 1) "${it.roundToInt()}km" else "${(it * 1000).roundToInt() / 100 * 100}m" }} 뒤 ${placeOf(nextBad).replace("산길", "굽은 길")}"
                headline = nextBad.weather.label; under = "차가 먼저 준비했어요"
            }
            inBad -> { over = "지금 ${placeOf(spans[idx!!])}"; headline = weather.label; under = if (planner.active().isNotEmpty()) "차가 돕고 있어요" else "" }
            nextBad != null -> {
                over = "지금 뒷자리"; headline = weather.label
                under = "${ceil(r.minuteAt(nextBad.fromKm) - r.minuteAt(km)).toInt()}분 뒤 ${placeOf(nextBad)}은 ${nextBad.weather.label} 예보"
            }
            else -> { over = "지금 뒷자리"; headline = weather.label; under = "남은 길은 ${weather.label}" }
        }
        // 말한 지 12초가 지나면 화면의 말 막대를 내린다
        val speaking = state.speaking?.takeIf { nowMs - spokeAtMs <= 12_000 }
        state = state.copy(
            speaking = speaking,
            weather = if (distToBad != null && distToBad <= 2.0 && complaintAtMs == null) nextBad.weather.max(weather) else weather,
            over = over, headline = headline, under = under,
            chips = planner.active().map { it.chip },
            nowFraction = if (r.totalKm > 0) (r.minuteAt(km) / r.totalMinutes).coerceIn(0.0, 1.0) else 0.0,
        )
    }
}
