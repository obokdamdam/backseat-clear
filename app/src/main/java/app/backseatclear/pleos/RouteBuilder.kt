package app.backseatclear.pleos

import app.backseatclear.core.model.RestStop
import app.backseatclear.core.model.Route
import app.backseatclear.core.model.RouteSegment

/**
 * 내비가 주는 정보로 1km 구간 경로를 만든다. (SDK 타입을 모르는 순수 함수 → 단위 테스트 가능)
 * 단위 가정: 거리 m, 시간 초 — Pleos 에뮬레이터에서 실측해 확인할 것(build-log 에 기록).
 */
object RouteBuilder {
    data class Turn(val atM: Double, val sharp: Boolean)
    data class Jam(val fromM: Double, val lengthM: Double)

    fun build(
        name: String,
        totalM: Double,
        totalSeconds: Double,
        turns: List<Turn>,
        jams: List<Jam>,
        cautionAtM: List<Double>,
        rests: List<Pair<String, Double>>,
    ): Route {
        val totalKm = (totalM / 1000.0).coerceAtLeast(1.0)
        val n = kotlin.math.ceil(totalKm).toInt()
        val minutesPerKm = (totalSeconds / 60.0) / totalKm
        val segs = (0 until n).map { i ->
            val from = i * 1000.0; val to = from + 1000.0
            val turnScore = turns.filter { it.atM >= from && it.atM < to }.sumOf { if (it.sharp) 1.5 else 1.0 }
            val jammed = jams.any { it.fromM < to && it.fromM + it.lengthM > from }
            val caution = cautionAtM.any { it >= from && it < to }
            RouteSegment(
                index = i, startKm = i.toDouble(), lengthKm = if (i == n - 1) totalKm - i else 1.0,
                // 막힌 구간은 시간이 더 걸린다(혼잡 평균 속도를 모를 때 2배로 본다)
                etaMinute = 0.0, turnsPerKm = turnScore, congested = jammed, caution = caution,
            )
        }
        // eta 를 앞에서부터 누적 (막힌 구간 2배)
        var eta = 0.0
        val withEta = segs.map { s -> s.copy(etaMinute = eta).also { eta += minutesPerKm * s.lengthKm * if (s.congested) 2.0 else 1.0 } }
        return Route(name, withEta, rests.map { (nm, m) -> RestStop(nm, m / 1000.0) })
    }
}
