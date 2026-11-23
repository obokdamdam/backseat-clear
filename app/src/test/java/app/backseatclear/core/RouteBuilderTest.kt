package app.backseatclear.core

import app.backseatclear.core.forecast.ForecastEngine
import app.backseatclear.core.model.Weather
import app.backseatclear.pleos.RouteBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내비 정보(회전 안내·혼잡·주의 구간·휴게소)로 만든 경로가 예보로 이어지는지 */
class RouteBuilderTest {
    @Test fun `회전이 촘촘한 구간과 막힌 구간이 흐림 이상으로 예보된다`() {
        // 30km, 30분. 10~15km 에 회전 20개(산길), 20~22km 정체, 25km 에 급경사, 18km 휴게소
        val turns = (0 until 20).map { RouteBuilder.Turn(10_000.0 + it * 250.0, sharp = it % 4 == 0) }
        val r = RouteBuilder.build(
            name = "테스트 길", totalM = 30_000.0, totalSeconds = 1_800.0, turns = turns,
            jams = listOf(RouteBuilder.Jam(20_000.0, 2_000.0)), cautionAtM = listOf(25_500.0),
            rests = listOf("휴게소" to 18_000.0),
        )
        assertEquals(30, r.segments.size)
        assertTrue(r.segments[12].turnsPerKm >= 4.0)
        assertTrue(r.segments[20].congested && r.segments[21].congested)
        assertTrue(r.segments[25].caution)
        assertEquals(18.0, r.restStops.single().km, 0.001)
        // 막힌 구간은 시간이 두 배
        assertEquals(2.0, r.segments[21].etaMinute - r.segments[20].etaMinute, 0.001)

        val f = ForecastEngine.forecast(r, null)
        assertEquals(Weather.CLEAR, f[2].weather)
        assertTrue(f[12].weather.atLeast(Weather.OVERCAST))
        assertTrue(f[20].weather.atLeast(Weather.OVERCAST))
    }

    @Test fun `출발 첫 1km 에 회전이 몰려도 흐림으로 예보하지 않는다`() {
        // 에뮬레이터 실제 경로처럼 첫 1km 에 회전 12개 + 정체, 그 뒤는 곧은 길
        val turns = (0 until 12).map { RouteBuilder.Turn(it * 80.0, sharp = false) }
        val r = RouteBuilder.build(
            name = "도심 출발", totalM = 10_000.0, totalSeconds = 600.0, turns = turns,
            jams = listOf(RouteBuilder.Jam(0.0, 1_000.0)), cautionAtM = emptyList(), rests = emptyList(),
        )
        val f = ForecastEngine.forecast(r, null)
        assertTrue(!f[0].weather.atLeast(Weather.OVERCAST))
        // 같은 회전·정체가 5km 지점이면 흐림 이상
        val later = RouteBuilder.build(
            name = "중간 산길", totalM = 10_000.0, totalSeconds = 600.0, turns = turns.map { it.copy(atM = it.atM + 5_000.0) },
            jams = listOf(RouteBuilder.Jam(5_000.0, 1_000.0)), cautionAtM = emptyList(), rests = emptyList(),
        )
        assertTrue(ForecastEngine.forecast(later, null)[5].weather.atLeast(Weather.OVERCAST))
    }
}
