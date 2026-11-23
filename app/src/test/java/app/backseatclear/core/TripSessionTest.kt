package app.backseatclear.core

import app.backseatclear.core.care.CareAction
import app.backseatclear.core.forecast.ForecastEngine
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Seat
import app.backseatclear.core.model.Weather
import app.backseatclear.core.session.Command
import app.backseatclear.core.session.Phase
import app.backseatclear.core.session.TripEvent
import app.backseatclear.core.session.TripSession
import app.backseatclear.core.voice.Intent
import app.backseatclear.core.voice.IntentParser
import app.backseatclear.core.voice.Kind
import app.backseatclear.sim.ScenarioA
import app.backseatclear.sim.Timed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSessionTest {
    private val kong = Rider(Seat.REAR_LEFT, "콩이", sensitivity = 3)
    private fun session(supported: Set<CareAction.Kind> = CareAction.Kind.entries.toSet()) =
        TripSession(riderFor = { if (it == Seat.REAR_LEFT) kong else Rider(it, it.label) }, supported = { supported })

    private fun run(s: TripSession, events: List<Timed>): List<Pair<Long, Command>> =
        events.flatMap { t -> s.handle(t.event, t.atMs).map { t.atMs to it } }

    private fun says(cmds: List<Pair<Long, Command>>) = cmds.mapNotNull { (it.second as? Command.Say)?.u }

    @Test fun `경로 예보 — 고속도로 맑음, 산길 흐림`() {
        val f = ForecastEngine.forecast(ScenarioA.route, kong)
        val spans = ForecastEngine.spans(ScenarioA.route, f)
        assertEquals(listOf(Weather.CLEAR, Weather.CLOUDY, Weather.OVERCAST, Weather.CLEAR), spans.map { it.weather })
        val bad = spans[2]
        assertEquals(45.0, bad.fromMinute, 0.01)
        assertEquals(75.0, bad.toMinute, 0.01)
    }

    @Test fun `덜 예민한 사람에게는 산길이 덜 나쁘다`() {
        val calm = Rider(Seat.REAR_LEFT, "아빠", sensitivity = 1)
        val spans = ForecastEngine.spans(ScenarioA.route, ForecastEngine.forecast(ScenarioA.route, calm))
        assertTrue(spans.none { it.weather == Weather.RAIN })
        // 한 단계 일찍 잡으면 같은 길이 더 나쁘게 예보된다
        val earlier = kong.copy(earlierSteps = 1)
        val worse = ForecastEngine.spans(ScenarioA.route, ForecastEngine.forecast(ScenarioA.route, earlier))
        assertTrue(worse.any { it.weather == Weather.RAIN })
    }

    @Test fun `시나리오 A — 말이 정해진 순서대로 나온다`() {
        val s = session()
        val cmds = run(s, ScenarioA.events())
        val kinds = says(cmds).map { it.kind }
        assertEquals(
            listOf(
                Kind.AUTO_START, Kind.FORECAST, Kind.PREPARE, Kind.COACH, Kind.COMPLAINT_ACK, Kind.ASK_REST,
                Kind.CONFIRM_REST, Kind.NEAR_REST, Kind.RESUME, Kind.PRAISE, Kind.RESTORE, Kind.ARRIVE,
            ),
            kinds,
        )
        assertEquals("오늘 길은 맑음, 45분 뒤 산길은 흐림이에요.", says(cmds)[1].text)
        assertEquals("3km 앞 쉼터에 들를까요? '응' 하시면 넣을게요.", says(cmds).first { it.kind == Kind.ASK_REST }.text)
    }

    @Test fun `시나리오 A — 차가 먼저 준비하고, 신고에 더 강하게, 끝나면 원래대로`() {
        val cmds = run(session(), ScenarioA.events())
        val applied = cmds.mapNotNull { it.second as? Command.Apply }.map { it.action }
        // 흐림 2km 전 준비 4가지
        assertEquals(
            listOf(CareAction.FreshAir, CareAction.TempOffset(-1), CareAction.RearFan(1), CareAction.RearWindow(10)),
            applied.take(4),
        )
        assertTrue(CareAction.RearFan(2) in applied)           // 브레이크 반복 → 바람 한 단계 더
        assertTrue(CareAction.TempOffset(-2) in applied)       // 신고 → 더 시원하게
        assertTrue(CareAction.RearWindow(20) in applied)       // 신고 → 창문 조금 더
        assertEquals(CareAction.RearWindow(0), applied.last { it is CareAction.RearWindow }) // 끝나면 닫음
        // 창문은 20% 넘게 열지 않고, 온도는 2도 넘게 내리지 않는다
        assertTrue(applied.filterIsInstance<CareAction.RearWindow>().all { it.percent <= 20 })
        assertTrue(applied.filterIsInstance<CareAction.TempOffset>().all { it.offsetC >= -2 })
        // 쉼터를 경유지로 넣었다
        assertEquals(1, cmds.count { it.second is Command.AddWaypoint })
    }

    @Test fun `시나리오 A — 운전자 안내는 내비 안내가 끝난 뒤에`() {
        val events = ScenarioA.events()
        val naviOff = events.first { it.event == TripEvent.NaviSpeaking(false) }.atMs
        val cmds = run(session(), events)
        val coachAt = cmds.first { (it.second as? Command.Say)?.u?.kind == Kind.COACH }.first
        assertEquals(naviOff, coachAt)
    }

    @Test fun `시나리오 A — 도착 리포트`() {
        val s = session()
        run(s, ScenarioA.events())
        val r = s.state.report
        assertNotNull(r)
        r!!
        assertEquals(Phase.ARRIVED, s.state.phase)
        assertEquals(Weather.CLEAR, r.overall)
        assertEquals(1, r.complaints)
        assertEquals(1, r.rests)
        assertEquals(1, r.prepares)
        assertEquals("다음에는 콩이 산길 예보를 한 단계 일찍 잡을게요.", r.nextTime)
        assertNotNull(r.smoothRatio)
        assertTrue(r.minutesToRecover!! in 10..20)
        // 실제 지난 띠에 비가 들어 있다
        assertTrue(r.timeline.any { it.weather == Weather.RAIN })
    }

    @Test fun `뒷자리에 아무도 없으면 켜지지 않는다`() {
        val s = session()
        val events = ScenarioA.events().filterNot { it.event is TripEvent.SeatChanged && (it.event as TripEvent.SeatChanged).seat == Seat.REAR_LEFT }
        val cmds = run(s, events)
        assertTrue(says(cmds).isEmpty())
        assertEquals(Phase.IDLE, s.state.phase)
    }

    @Test fun `창문을 지원하지 않는 차 — 한 번만 알리고 나머지만 바꾼다`() {
        val cmds = run(session(supported = CareAction.Kind.entries.toSet() - CareAction.Kind.WINDOW), ScenarioA.events())
        assertEquals(1, says(cmds).count { it.kind == Kind.UNSUPPORTED })
        assertFalse(cmds.any { (it.second as? Command.Apply)?.action is CareAction.RearWindow })
    }

    @Test fun `사람이 바람을 직접 바꾸면 그 구간에서는 손대지 않는다`() {
        val events = ScenarioA.events().toMutableList()
        events += Timed(30 * 60_000L, TripEvent.UserAdjusted(CareAction.Kind.FAN))
        events.sortBy { it.atMs }
        // 준비(41분 무렵)에서 새 구간이 시작되므로 사람 우선은 풀린다 → 같은 구간 안에서 확인
        val s = session()
        val until = events.takeWhile { it.atMs < 50 * 60_000L }
        run(s, until)
        val more = s.handle(TripEvent.UserAdjusted(CareAction.Kind.FAN), 50 * 60_000L) +
            s.handle(TripEvent.Brake, 50 * 60_000L + 1) + s.handle(TripEvent.Brake, 50 * 60_000L + 2) +
            s.handle(TripEvent.Brake, 50 * 60_000L + 3)
        assertFalse(more.any { (it as? Command.Apply)?.action is CareAction.RearFan })
    }

    @Test fun `조용히 해 줘 — 그 뒤로는 말하지 않는다`() {
        val events = ScenarioA.events().toMutableList()
        events += Timed(20 * 60_000L, TripEvent.Heard("조용히 해 줘"))
        events.sortBy { it.atMs }
        val cmds = run(session(), events)
        assertTrue(says(cmds.filter { it.first > 20 * 60_000L }).isEmpty())
        assertTrue(cmds.any { (it.second as? Command.Apply)?.action == CareAction.FreshAir }) // 조치는 계속
    }

    @Test fun `대답이 없으면 다시 묻지 않는다`() {
        val cmds = run(session(), ScenarioA.events(answerRest = null))
        val kinds = says(cmds).map { it.kind }
        assertFalse(Kind.CONFIRM_REST in kinds)
        assertFalse(Kind.NOT_HEARD in kinds)
        assertEquals(1, kinds.count { it == Kind.ASK_REST })
    }

    @Test fun `못 알아들으면 딱 한 번만 다시 묻는다`() {
        val s = session()
        val events = ScenarioA.events(answerRest = "음 글쎄").toMutableList()
        val askAt = events.first { (it.event as? TripEvent.Heard)?.text == "음 글쎄" }.atMs
        events += Timed(askAt + 3_000, TripEvent.Heard("뭐라고"))
        events.sortBy { it.atMs }
        val kinds = says(run(s, events)).map { it.kind }
        assertEquals(1, kinds.count { it == Kind.NOT_HEARD })
        assertFalse(Kind.CONFIRM_REST in kinds)
    }

    @Test fun `내비 안내가 30초 넘게 이어지면 밀린 말은 버린다`() {
        val s = session()
        val events = ScenarioA.events().filterNot { it.event == TripEvent.NaviSpeaking(false) }.toMutableList()
        val on = events.first { it.event == TripEvent.NaviSpeaking(true) }.atMs
        events += Timed(on + 40_000, TripEvent.NaviSpeaking(false))
        events.sortBy { it.atMs }
        val kinds = says(run(s, events)).map { it.kind }
        assertFalse(Kind.COACH in kinds)
    }

    @Test fun `들은 말 알아듣기`() {
        assertEquals(Intent.COMPLAINT, IntentParser.parse("엄마 속이 이상해"))
        assertEquals(Intent.COMPLAINT, IntentParser.parse("토할 것 같아"))
        assertEquals(Intent.YES, IntentParser.parse("응"))
        assertEquals(Intent.YES, IntentParser.parse("좋아 넣어 줘"))
        assertEquals(Intent.NO, IntentParser.parse("아니 괜찮아"))
        assertEquals(Intent.NO, IntentParser.parse("안 들러"))
        assertEquals(Intent.QUIET, IntentParser.parse("조용히 해 줘"))
        assertEquals(Intent.STATUS, IntentParser.parse("지금 어때?"))
        assertEquals(Intent.STOP_CARE, IntentParser.parse("창문 닫아 줘"))
        assertEquals(Intent.UNKNOWN, IntentParser.parse("음 글쎄"))
    }
}
