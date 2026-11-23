package app.backseatclear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.backseatclear.core.forecast.WeatherSpan
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Seat
import app.backseatclear.core.model.Weather
import app.backseatclear.core.session.Phase
import app.backseatclear.core.session.TripReport
import app.backseatclear.core.session.UiState
import app.backseatclear.data.TripLogStore
import app.backseatclear.ui.components.ActionChips
import app.backseatclear.ui.components.AppBar
import app.backseatclear.ui.components.BigButton
import app.backseatclear.ui.components.Card
import app.backseatclear.ui.components.ForecastStrip
import app.backseatclear.ui.components.Glyph
import app.backseatclear.ui.components.GlyphCircle
import app.backseatclear.ui.components.GlyphIcon
import app.backseatclear.ui.components.Txt
import app.backseatclear.ui.components.VoiceBar
import app.backseatclear.ui.components.WeatherBadge
import app.backseatclear.ui.theme.P
import app.backseatclear.ui.theme.Type
import kotlin.math.roundToInt

private val Pad = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)

// ───────────────────────── 처음 화면 (정차 중) ─────────────────────────
@Composable
fun HomeScreen(onDemo: () -> Unit, onJump: (Double) -> Unit, onFamily: () -> Unit, recent: List<TripLogStore.Entry>) {
    Column(Pad) {
        AppBar("정차 중", driving = false)
        Spacer(Modifier.height(36.dp))
        Txt("뒷자리에 누가 타고 경로를 시작하면", Type.title, P.muted)
        Txt("저절로 멀미 예보를 켜요", Type.headline)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton("시연: 양평 나들이", glyph = Glyph.PLAY, onClick = onDemo)
            BigButton("우리 가족", primary = false, onClick = onFamily)
        }
        Spacer(Modifier.height(28.dp))
        Txt("장면 바로 보기 (시연 영상·캡처용)", Type.label, P.muted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("출발 전" to 0.3, "맑음" to 12.0, "흐림 준비" to 42.5, "속이 안 좋아" to 56.0, "도착" to 120.0).forEach { (t, m) ->
                BigButton(t, primary = false, onClick = { onJump(m) })
            }
        }
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Txt("최근 길", Type.label, P.muted)
            recent.take(3).forEach { e ->
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    WeatherBadge(Weather.valueOf(e.overall), 36.dp, plain = true)
                    Spacer(Modifier.width(12.dp))
                    Txt(e.routeName, Type.body)
                }
            }
        }
    }
}

// ───────────────────────── S1 출발 전 예보 (정차 중) ─────────────────────────
@Composable
fun BeforeDepartureScreen(ui: UiState, restricted: Boolean, onOk: () -> Unit) {
    Column(Pad) {
        AppBar(if (restricted) "주행 중 · 음성으로만" else "정차 중", driving = restricted)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            SeatCard(ui.riders, Modifier.width(340.dp).fillMaxHeight())
            Column(Modifier.weight(1f)) {
                Txt("${ui.routeName} · ${minutesText(ui.totalMinutes)}", Type.label, P.muted)
                Txt("오늘 길 멀미 예보", Type.headline)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ui.forecast.take(4).forEach { sp -> SpanCard(sp, Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(24.dp))
                ForecastStrip(ui.forecast, ui.totalMinutes, null)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Txt("출발", Type.label, P.muted); Spacer(Modifier.weight(1f)); Txt("도착", Type.label, P.muted)
                }
                Spacer(Modifier.height(16.dp))
                ui.forecast.firstOrNull { it.weather.atLeast(Weather.OVERCAST) }?.let { bad ->
                    Txt("· ${bad.fromMinute.roundToInt().let { if (it <= 0) "출발 직후부터" else "${it}분 뒤부터" }} ${(bad.toMinute - bad.fromMinute).roundToInt()}분 동안 ${bad.reasons.joinToString { it.text }}", Type.body, P.muted)
                    Txt("· 그 2km 전에 바람과 온도를 미리 바꿔 둘게요", Type.body, P.muted)
                }
                Spacer(Modifier.weight(1f))
                if (!restricted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BigButton("알겠어요", onClick = onOk)
                        Spacer(Modifier.width(20.dp))
                        Txt("출발하면 자동으로 지켜 드려요", Type.label, P.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpanCard(sp: WeatherSpan, modifier: Modifier) {
    Card(modifier, borderColor = if (sp.weather.atLeast(Weather.OVERCAST)) P.graphic(sp.weather) else null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherBadge(sp.weather, 52.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Txt(sp.weather.label, Type.title, P.text(sp.weather))
                Txt(if (sp.fromMinute < 1) "~${sp.toMinute.roundToInt()}분" else "${sp.fromMinute.roundToInt()}~${sp.toMinute.roundToInt()}분", Type.label, P.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SeatCard(riders: Map<Seat, Rider>, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(26.dp)) {
            Txt("누가 탔나요", Type.label, P.muted)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { SeatBox(Seat.DRIVER, riders); SeatBox(Seat.PASSENGER, riders) }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { SeatBox(Seat.REAR_LEFT, riders); SeatBox(Seat.REAR_RIGHT, riders) }
                }
            }
            riders.values.firstOrNull { it.seat.isRear }?.let { r ->
                Spacer(Modifier.height(14.dp))
                Txt("${r.seat.label}에\n${r.nickname}${if (r.nickname.last().code.let { (it - 0xAC00) % 28 == 0 }) "가" else "이"} 탔어요", Type.body)
                Txt(when (r.sensitivity) { 3 -> "멀미에 예민한 편"; 1 -> "멀미를 잘 안 하는 편"; else -> "멀미 보통" }, Type.label, P.muted)
            }
        }
    }
}

@Composable
private fun SeatBox(seat: Seat, riders: Map<Seat, Rider>) {
    val r = riders[seat]
    val on = r != null && seat.isRear
    Box(
        Modifier.size(96.dp, 104.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) P.tintAccent else P.surface2)
            .border(3.dp, if (on) P.accent else P.border, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            when { seat == Seat.DRIVER -> "운전석"; r == null -> "빈자리"; else -> r.nickname },
            Type.label.copy(fontWeight = FontWeight.Bold), if (on) P.text else P.muted,
        )
    }
}

// ───────────────────────── S2·S3·S4 주행 중 ─────────────────────────
@Composable
fun DrivingScreen(ui: UiState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 900.dp
        Column(Pad.then(if (narrow) Modifier.padding(0.dp) else Modifier)) {
            AppBar(if (ui.phase == Phase.RESTING) "쉬는 중" else if (narrow) "주행 중" else "주행 중 · 음성으로만", driving = ui.phase != Phase.RESTING)
            Spacer(Modifier.height(if (narrow) 28.dp else 32.dp))
            val big = if (ui.headline.length <= 3) Type.giant.copy(fontSize = if (narrow) 96.sp else 120.sp) else Type.hero
            val badgeSize = when { narrow -> 200.dp; ui.question != null -> 220.dp; ui.chips.isNotEmpty() -> 260.dp; else -> 300.dp }
            if (narrow) {
                WeatherBadge(ui.weather, badgeSize)
                Spacer(Modifier.height(22.dp))
                Txt(ui.over, Type.title, P.muted)
                Txt(ui.headline, big, P.text(ui.weather), maxLines = 1)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherBadge(ui.weather, badgeSize)
                    Spacer(Modifier.width(56.dp))
                    Column {
                        Txt(ui.over, Type.title, P.muted)
                        Txt(ui.headline, big, P.text(ui.weather), maxLines = 1)
                        if (ui.under.isNotEmpty() && ui.chips.isEmpty()) Txt(ui.under, Type.title, modifier = Modifier.padding(top = 10.dp))
                        else if (ui.under.isNotEmpty()) Txt(ui.under, Type.body, P.muted)
                    }
                }
            }
            if (ui.chips.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                ActionChips(ui.chips.take(if (narrow) 3 else 4))
            }
            Spacer(Modifier.weight(1f))
            when {
                ui.question != null -> QuestionCard(ui.question, ui.listeningPrompt)
                ui.speaking != null && ui.chips.isNotEmpty() -> VoiceBar(ui.speaking, null)
                else -> {
                    ForecastStrip(ui.forecast, ui.totalMinutes, ui.nowFraction)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.MIC, P.accent, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Txt("음성 버튼 누르고 「속이 안 좋아」 하면 바로 도와요", Type.label, P.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(q: String, listening: String?) {
    Card(Modifier.fillMaxWidth(), borderColor = P.accent) {
        Row(Modifier.padding(horizontal = 28.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphCircle(Glyph.REST, P.tintAccent, P.accent, 72.dp)
            Spacer(Modifier.width(24.dp))
            Txt(q, Type.headline, modifier = Modifier.weight(1f))
            if (listening != null) VoiceBar(null, listening, Modifier.width(320.dp))
        }
    }
}

// ───────────────────────── S7 도착 리포트 (정차 중) ─────────────────────────
@Composable
fun ReportScreen(r: TripReport, onOk: () -> Unit) {
    Column(Pad) {
        AppBar("도착 · 정차 중", driving = false)
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherBadge(r.overall, 120.dp)
            Spacer(Modifier.width(28.dp))
            Column {
                Txt("도착! 오늘 뒷자리는 ${r.overall.wasYo}", Type.headline)
                Txt("${r.routeName} · ${minutesText(r.minutes.toDouble())}${r.rider?.let { " · ${it.nickname}" } ?: ""}", Type.body, P.muted)
            }
        }
        Spacer(Modifier.height(26.dp))
        ForecastStrip(r.timeline, r.timeline.lastOrNull()?.toMinute ?: 1.0, null)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat(r.smoothRatio?.let { "${(it * 100).roundToInt()}%" } ?: "—", "굽은 길을 부드럽게", Modifier.weight(1f), accent = true)
            Stat("${r.prepares}번", "차가 먼저 준비", Modifier.weight(1f))
            Stat(r.minutesToRecover?.let { "${it}분" } ?: "${r.complaints}번", if (r.minutesToRecover != null) "「속이 안 좋아」 뒤 다시 맑음" else "「속이 안 좋아」", Modifier.weight(1f))
            Stat("${r.rests}번", "쉬어 감", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth(), bg = P.surface2) {
            Row(Modifier.padding(horizontal = 26.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.STAR, P.accent, 34.dp)
                Spacer(Modifier.width(18.dp))
                Txt(r.nextTime ?: "오늘 길도 기록해 두었어요. 다음 예보가 더 정확해져요.", Type.body, modifier = Modifier.weight(1f))
                BigButton("확인", onClick = onOk)
            }
        }
    }
}

@Composable
private fun Stat(num: String, label: String, modifier: Modifier, accent: Boolean = false) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Txt(num, Type.headline, if (accent) P.accent else P.text)
            Txt(label, Type.label, P.muted)
        }
    }
}

// ───────────────────────── S8 우리 가족 (정차 중) ─────────────────────────
@Composable
fun FamilyScreen(riders: List<Rider>, recent: List<TripLogStore.Entry>, onBack: () -> Unit, onClear: () -> Unit, onPrivacy: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Pad) {
        AppBar("정차 중", driving = false)
        Spacer(Modifier.height(20.dp))
        Txt("우리 가족", Type.headline)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            riders.forEach { r ->
                Card(Modifier.weight(1f), borderColor = if (r.sensitivity >= 3) P.accent else null) {
                    Column(Modifier.padding(26.dp)) {
                        Txt("${r.seat.label}에 자주", Type.label, P.muted)
                        Txt(r.nickname, Type.title)
                        Spacer(Modifier.height(14.dp))
                        Txt("멀미 민감도", Type.label.copy(fontWeight = FontWeight.Bold))
                        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { i ->
                                Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (i < r.sensitivity) (if (r.sensitivity >= 3) P.overcastG else P.cloudG) else P.surface2))
                            }
                        }
                        Txt(when (r.sensitivity) { 3 -> "예민한 편"; 1 -> "잘 안 하는 편"; else -> "보통" }, Type.label, P.muted)
                        if (r.earlierSteps > 0) Txt("예보를 ${r.earlierSteps}단계 일찍 잡아요", Type.label, P.accent)
                    }
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(26.dp)) {
                    Txt("최근 길", Type.label, P.muted)
                    if (recent.isEmpty()) Txt("아직 기록이 없어요", Type.body, P.muted, Modifier.padding(top = 12.dp))
                    recent.take(4).forEach { e ->
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            WeatherBadge(Weather.valueOf(e.overall), 36.dp, plain = true)
                            Spacer(Modifier.width(12.dp))
                            Txt(e.routeName, Type.body, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Txt("기록은 이 차 안에만 저장돼요 · 이름 대신 별명을 써도 돼요", Type.label, P.muted)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton(if (confirm) "한 번 더 누르면 지워요" else "기록 모두 지우기", primary = false, modifier = Modifier.weight(1f)) {
                if (confirm) { onClear(); confirm = false } else confirm = true
            }
            BigButton("개인정보 안내", primary = false, modifier = Modifier.weight(1f), onClick = onPrivacy)
            BigButton("돌아가기", primary = false, modifier = Modifier.weight(1f), onClick = onBack)
        }
    }
}

// ───────────────────────── 개인정보 안내 (정차 중) ─────────────────────────
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Column(Pad) {
        AppBar("정차 중", driving = false)
        Spacer(Modifier.height(20.dp))
        Txt("개인정보 안내", Type.headline)
        Spacer(Modifier.height(18.dp))
        listOf(
            "앱은 서버로 아무것도 보내지 않아요. 기록은 이 차 안에만 저장돼요.",
            "저장하는 것: 자리별 별명·멀미 민감도, 최근 30번 길의 목적지 이름·걸린 시간·멀미 날씨·「속이 안 좋아」 횟수.",
            "위치·이동 경로는 저장하지 않아요. 예보는 내비가 준 경로로 그때그때 계산해요.",
            "목소리는 「속이 안 좋아」 같은 대답을 알아듣는 데만 쓰고 저장하지 않아요.",
            "아이 이름이나 나이는 받지 않아요. 「뒷자리 왼쪽」 같은 자리 이름이나 별명을 써요.",
            "「우리 가족」 화면의 「기록 모두 지우기」로 언제든 전부 지울 수 있어요.",
        ).forEach { Txt("· $it", Type.body, modifier = Modifier.padding(vertical = 6.dp)) }
        Spacer(Modifier.height(18.dp))
        Txt("만든 곳: 오복담담소프트(개인 개발) · 무료 앱, 결제·광고 없음", Type.label, P.muted)
        Txt("개인정보 보호 담당: 오복담담소프트 운영자 · 문의 seoobok991@gmail.com", Type.label, P.muted)
        Spacer(Modifier.weight(1f))
        BigButton("돌아가기", primary = false, onClick = onBack)
    }
}

private fun minutesText(m: Double): String {
    val t = m.roundToInt()
    return if (t >= 60) "${t / 60}시간 ${t % 60}분" else "${t}분"
}
