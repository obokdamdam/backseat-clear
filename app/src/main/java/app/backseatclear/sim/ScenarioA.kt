package app.backseatclear.sim

import app.backseatclear.core.model.RestStop
import app.backseatclear.core.model.Route
import app.backseatclear.core.model.RouteSegment
import app.backseatclear.core.model.Seat
import app.backseatclear.core.session.TripEvent

/** 시간(ms)이 붙은 사건 */
data class Timed(val atMs: Long, val event: TripEvent)

/**
 * 시나리오 A — 서울 → 양평 캠핑장. 운전자 + 뒷자리 왼쪽 아이.
 * 앞 45km 고속도로(40분) → 3km 진입로(5분) → 15km 산길(30분) → 5km 마을길(5분).
 * 55분에 「속이 좀 이상해」, 56km 쉼터에서 9분 쉼.
 * 에뮬레이터·Virtual Console 없이 같은 흐름을 재현하는 데 쓴다(시연 영상·단위 테스트 공용).
 */
object ScenarioA {
    const val REST_KM = 56.0
    const val REST_MINUTES = 9.0
    const val COMPLAINT_ROUTE_MINUTE = 55.0

    val route: Route by lazy {
        val segs = mutableListOf<RouteSegment>()
        var eta = 0.0
        fun add(count: Int, minutesEach: Double, turns: Double) {
            repeat(count) {
                segs += RouteSegment(segs.size, segs.size.toDouble(), 1.0, eta, turns)
                eta += minutesEach
            }
        }
        add(45, 40.0 / 45, 0.3)   // 고속도로
        add(3, 5.0 / 3, 1.2)      // 진입로
        add(15, 2.0, 3.5)         // 산길
        add(5, 1.0, 0.5)          // 마을길
        Route("서울 → 양평 캠핑장", segs, listOf(RestStop("양평 쉼터", REST_KM)))
    }

    /** 경로 시간(분) → 달린 거리(km) */
    fun kmAt(routeMinute: Double): Double {
        val r = route
        for (s in r.segments) {
            val end = s.etaMinute + r.minutesOf(s)
            if (routeMinute < end) return s.startKm + s.lengthKm * ((routeMinute - s.etaMinute) / (end - s.etaMinute)).coerceIn(0.0, 1.0)
        }
        return r.totalKm
    }

    /**
     * 전체 사건 목록. stepSeconds 마다 진행 사건을 넣는다.
     * @param answerRest 쉼터 제안에 할 대답(null 이면 대답 없음)
     */
    fun events(stepSeconds: Int = 15, answerRest: String? = "응"): List<Timed> {
        val out = mutableListOf<Timed>()
        fun at(min: Double, e: TripEvent) { out += Timed((min * 60_000).toLong(), e) }

        at(0.0, TripEvent.SeatChanged(Seat.DRIVER, true, true))
        at(0.0, TripEvent.SeatChanged(Seat.REAR_LEFT, true, true))
        at(0.0, TripEvent.Recirculation(true))
        at(0.05, TripEvent.RouteStarted(route))

        val step = stepSeconds / 60.0
        var real = 0.5          // 실제 경과 분
        var routeMin = 0.0      // 경로 시간(쉬는 동안 멈춤)
        var complained = false
        var braked = false
        var naviTalk = false
        var rested = false
        var restUntil: Double? = null
        while (true) {
            if (restUntil != null) {
                if (real >= restUntil) { at(real, TripEvent.LeftRest); restUntil = null; rested = true }
                real += step
                continue
            }
            routeMin += step
            real += step
            val km = kmAt(routeMin)
            // 굽은 길 바로 앞에서 내비가 회전 안내를 말한다(오디오 포커스)
            if (!naviTalk && km >= 47.7) { naviTalk = true; at(real, TripEvent.NaviSpeaking(true)); at(real + 0.1, TripEvent.NaviSpeaking(false)) }
            at(real, TripEvent.Progress(km))
            at(real, TripEvent.Speed(if (km in 48.0..63.0) 30.0 else 67.0))
            // 50분: 1분 안에 브레이크 세 번
            if (!braked && routeMin >= 50.0) {
                braked = true
                at(real, TripEvent.Brake); at(real + 0.2, TripEvent.Brake); at(real + 0.4, TripEvent.Brake)
            }
            if (!complained && routeMin >= COMPLAINT_ROUTE_MINUTE) {
                complained = true
                at(real, TripEvent.Heard("속이 좀 이상해"))
                if (answerRest != null) at(real + 0.1, TripEvent.Heard(answerRest)) else at(real + 0.15, TripEvent.ListenTimeout)
            }
            if (!rested && answerRest == "응" && km >= REST_KM - 0.01) {
                at(real, TripEvent.NearRest)
                restUntil = real + REST_MINUTES
            }
            if (km >= route.totalKm - 0.001) { at(real + step, TripEvent.Arrived); break }
        }
        return out.sortedBy { it.atMs }
    }
}
