package app.backseatclear.core.forecast

import app.backseatclear.core.model.Reason
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Route
import app.backseatclear.core.model.RouteSegment
import app.backseatclear.core.model.Weather

data class SegmentForecast(
    val segment: RouteSegment,
    val score: Double,
    val weather: Weather,
    val reasons: Set<Reason>,
)

/** 같은 날씨가 이어지는 구간 묶음 — 화면의 「맑음 지금~40분」 카드와 예보 띠에 쓴다 */
data class WeatherSpan(val weather: Weather, val fromMinute: Double, val toMinute: Double, val fromKm: Double, val toKm: Double, val reasons: Set<Reason>)

/**
 * 출발 전 경로 예보. 구간마다 점수를 매기고 날씨로 바꾼다.
 * 규칙 기반이라 「왜 흐림인지」를 사람이 설명할 수 있다. 기준값은 실차 시험에서 조정한다(효과 수치는 약속하지 않음).
 */
object ForecastEngine {
    // 점수 → 날씨 경계. 가족 맞춤 「한 단계 일찍」이면 경계를 STEP 만큼 내린다.
    const val CLOUDY_FROM = 20.0
    const val OVERCAST_FROM = 32.0
    const val RAIN_FROM = 58.0
    const val STEP = 8.0

    // 출발 첫 1km 는 주차장·골목을 천천히 빠져나가는 구간 — 회전 안내가 몰려도(실제 내비 12회/km) 멀미 영향이 작다.
    // 굽은 길·정체 점수를 이만큼만 센다(에뮬레이터 실제 경로 확인 후 사용자 결정, 2026-10-29).
    const val DEPARTURE_KM = 1.0
    const val DEPARTURE_WEIGHT = 0.4

    fun scoreOf(s: RouteSegment, rider: Rider?): Pair<Double, Set<Reason>> {
        val reasons = mutableSetOf<Reason>()
        var score = 0.0
        val near = if (s.startKm < DEPARTURE_KM) DEPARTURE_WEIGHT else 1.0
        val curve = (s.turnsPerKm * 12.0).coerceAtMost(40.0) * near
        score += curve
        if (s.turnsPerKm >= 2.0 && near == 1.0) reasons += Reason.CURVES
        if (s.congested) { score += 34.0 * near; reasons += Reason.CONGESTION } // 가다서다만으로도 흐림
        if (s.caution) { score += 15.0; reasons += Reason.CAUTION }
        score += when {
            s.etaMinute >= 60 -> 10.0
            s.etaMinute >= 30 -> 5.0
            else -> 0.0
        }
        if (s.etaMinute >= 90) reasons += Reason.LONG_DRIVE
        val mult = when (rider?.sensitivity ?: 2) { 1 -> 0.85; 3 -> 1.15; else -> 1.0 }
        if ((rider?.sensitivity ?: 2) >= 3 && score >= 20) reasons += Reason.SENSITIVE
        return score * mult to reasons
    }

    fun weatherOf(score: Double, earlierSteps: Int = 0): Weather {
        val shift = earlierSteps * STEP
        return when {
            score >= RAIN_FROM - shift -> Weather.RAIN
            score >= OVERCAST_FROM - shift -> Weather.OVERCAST
            score >= CLOUDY_FROM - shift -> Weather.CLOUDY
            else -> Weather.CLEAR
        }
    }

    fun forecast(route: Route, rider: Rider?): List<SegmentForecast> = route.segments.map { s ->
        val (score, reasons) = scoreOf(s, rider)
        SegmentForecast(s, score, weatherOf(score, rider?.earlierSteps ?: 0), reasons)
    }

    fun spans(route: Route, forecast: List<SegmentForecast>): List<WeatherSpan> {
        val out = mutableListOf<WeatherSpan>()
        for (f in forecast) {
            val s = f.segment
            val end = s.etaMinute + route.minutesOf(s)
            val last = out.lastOrNull()
            if (last != null && last.weather == f.weather) {
                out[out.lastIndex] = last.copy(toMinute = end, toKm = s.endKm, reasons = last.reasons + f.reasons)
            } else {
                out += WeatherSpan(f.weather, s.etaMinute, end, s.startKm, s.endKm, f.reasons)
            }
        }
        return mergeShort(out)
    }

    /** 2분 미만 짧은 조각은 앞 조각에 붙여 띠가 깜빡이지 않게 한다 */
    private fun mergeShort(spans: List<WeatherSpan>): List<WeatherSpan> {
        if (spans.size < 3) return spans
        val out = mutableListOf<WeatherSpan>()
        for (sp in spans) {
            val last = out.lastOrNull()
            if (last != null && sp.toMinute - sp.fromMinute < 2.0 && sp !== spans.last()) {
                out[out.lastIndex] = last.copy(toMinute = sp.toMinute, toKm = sp.toKm)
            } else if (last != null && last.weather == sp.weather) {
                out[out.lastIndex] = last.copy(toMinute = sp.toMinute, toKm = sp.toKm, reasons = last.reasons + sp.reasons)
            } else out += sp
        }
        return out
    }
}
