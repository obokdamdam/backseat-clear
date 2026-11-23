package app.backseatclear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.backseatclear.core.care.CareAction
import app.backseatclear.core.model.Audience
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Seat
import app.backseatclear.core.model.Weather
import app.backseatclear.core.session.Command
import app.backseatclear.core.session.Phase
import app.backseatclear.core.session.TripEvent
import app.backseatclear.core.session.TripSession
import app.backseatclear.core.session.UiState
import app.backseatclear.core.voice.Kind
import app.backseatclear.core.voice.Utterance
import app.backseatclear.sim.ScenarioA
import app.backseatclear.ui.components.Glyph
import app.backseatclear.ui.components.GlyphIcon
import app.backseatclear.ui.components.Logo
import app.backseatclear.ui.components.Txt
import app.backseatclear.ui.components.WeatherBadge
import app.backseatclear.ui.screens.BeforeDepartureScreen
import app.backseatclear.ui.screens.DrivingScreen
import app.backseatclear.ui.screens.ReportScreen
import app.backseatclear.ui.theme.Dark
import app.backseatclear.ui.theme.LocalPalette
import app.backseatclear.ui.theme.Type
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * 시연 영상용 장면 프레임(1920×1080) — 시나리오 A 를 TripSession 으로 실제로 돌리며
 * 말이 나오는 순간마다 그때의 앱 화면을 그린다. 결과: build/video/kNN.png + keys.json
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class VideoFrames {
    data class Key(
        val minute: Double, val ui: UiState, val caption: String,
        val say: Utterance? = null, val heard: String? = null, val who: String? = null,
    )

    private val kong = Rider(Seat.REAR_LEFT, "콩이", sensitivity = 3)

    private fun captionOf(k: Kind) = when (k) {
        Kind.AUTO_START -> "뒷좌석 탑승 + 내비 경로 시작 → 앱이 저절로 켜집니다"
        Kind.FORECAST -> "내비가 아는 앞길로 멀미 날씨를 예보합니다"
        Kind.PREPARE -> "흐림 2km 전, 아무도 누르지 않아도 차가 먼저 공기·온도·창문을 바꿉니다"
        Kind.COACH -> "운전자에게는 탓하지 않는 한마디 — 내비 안내가 끝난 뒤에 말합니다"
        Kind.COMPLAINT_ACK -> "「속이 안 좋아」 한마디에 바로 더 시원하게"
        Kind.ASK_REST -> "앞쪽 쉼터를 제안하고, 대답은 말로만 8초 동안 듣습니다"
        Kind.CONFIRM_REST -> "「응」 한마디로 쉼터를 경유지에 넣습니다"
        Kind.NEAR_REST -> "쉼터 도착 — 바깥 공기 마시고 쉬어 갑니다"
        Kind.RESUME -> "쉬고 나면 남은 산길은 한 단계 낮춰(구름) 봅니다"
        Kind.PRAISE -> "굽은 길을 잘 지나면 칭찬도 합니다"
        Kind.RESTORE -> "굽은 길이 끝나면 창문·온도를 원래대로"
        Kind.ARRIVE -> "도착 리포트 — 다음에는 우리 아이에게 맞춰 한 단계 일찍 예보합니다"
        else -> ""
    }

    private fun collect(): List<Key> {
        val s = TripSession(riderFor = { if (it == Seat.REAR_LEFT) kong else Rider(it, it.label) })
        val keys = mutableListOf<Key>()
        var addedCruise = false
        var addedIntensify = false
        for (t in ScenarioA.events()) {
            val e = t.event
            val m = t.atMs / 60_000.0
            if (e is TripEvent.Heard) {
                val who = if (e.text.contains("속이")) "뒷자리 콩이" else "운전자"
                val cmds = s.handle(e, t.atMs)
                keys += Key(m, s.state.copy(speaking = null), if (who == "운전자") "운전자가 대답합니다" else "뒷자리 아이가 말합니다", heard = e.text, who = who)
                cmds.filterIsInstance<Command.Say>().forEach {
                    // 한 사건에서 말이 여러 개 나오면, 질문 전 말은 질문 카드가 뜨기 전 화면으로
                    val ui = if (it.u.kind == Kind.ASK_REST || it.u.kind == Kind.CONFIRM_REST) s.state.copy(speaking = it.u)
                    else s.state.copy(question = null, listeningPrompt = null, speaking = it.u)
                    keys += Key(m, ui, captionOf(it.u.kind), say = it.u)
                }
                continue
            }
            val cmds = s.handle(e, t.atMs)
            cmds.filterIsInstance<Command.Say>().forEach { keys += Key(m, s.state, captionOf(it.u.kind), say = it.u) }
            if (!addedCruise && m >= 12.0 && s.state.phase == Phase.DRIVING) {
                addedCruise = true
                keys += Key(m, s.state, "주행 중 화면은 날씨 하나와 한 줄 — 터치할 것이 없습니다")
            }
            if (!addedIntensify && cmds.any { (it as? Command.Apply)?.action == CareAction.RearFan(2) } && cmds.none { it is Command.Say }) {
                addedIntensify = true
                keys += Key(m, s.state.copy(speaking = null), "브레이크가 잦아지면 말없이 바람만 한 단계 더 — 잔소리하지 않습니다")
            }
        }
        return keys
    }

    @Composable
    private fun Frame(k: Key) {
        val p = Dark
        CompositionLocalProvider(LocalPalette provides p) {
            Column(Modifier.fillMaxSize().background(Color(0xFF070C0D))) {
                Row(Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 160.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(44.dp); Spacer(Modifier.width(14.dp))
                    Txt("뒷자리 맑음 · 시연", Type.body.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.weight(1f))
                    val mm = k.minute.roundToInt()
                    Row(Modifier.clip(CircleShape).background(p.surface2).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Txt("서울 → 양평 캠핑장 · 출발 후 ${if (mm >= 60) "${mm / 60}시간 ${mm % 60}분" else "${mm}분"}", Type.label, p.muted)
                    }
                }
                Box(Modifier.fillMaxWidth().height(900.dp), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.size(1600.dp, 900.dp).clip(RoundedCornerShape(28.dp)).background(p.bg).border(2.dp, p.border, RoundedCornerShape(28.dp))) {
                        Box(Modifier.requiredSize(1280.dp, 720.dp).graphicsLayer(scaleX = 1.25f, scaleY = 1.25f, transformOrigin = TransformOrigin(0f, 0f)).align(Alignment.TopStart)) {
                            when {
                                k.ui.phase == Phase.ARRIVED && k.ui.report != null -> ReportScreen(k.ui.report!!, onOk = {})
                                k.ui.phase == Phase.BEFORE_DEPARTURE -> BeforeDepartureScreen(k.ui, restricted = false, onOk = {})
                                else -> DrivingScreen(k.ui)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 160.dp), verticalAlignment = Alignment.CenterVertically) {
                    val driving = k.ui.phase == Phase.DRIVING || k.ui.phase == Phase.RESTING
                    val onScreen = k.say != null && driving && (
                        (k.say.kind == Kind.ASK_REST && k.ui.question != null) ||
                            (k.ui.question == null && k.ui.chips.isNotEmpty() && k.ui.speaking?.text == k.say.text)
                        )
                    val line = when {
                        k.heard != null -> "${k.who} 「${k.heard}」"
                        k.say != null && !onScreen -> "${when (k.say.to) { Audience.DRIVER -> "운전자에게"; Audience.REAR -> "뒷자리에"; Audience.ALL -> "모두에게" }} 「${k.say.text}」"
                        else -> null
                    }
                    Column(Modifier.weight(1f)) {
                        if (line != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(if (k.heard != null) Glyph.MIC else Glyph.SPEAK, p.accent, 24.dp); Spacer(Modifier.width(10.dp))
                            Txt(line, Type.label.copy(fontSize = 22.sp, lineHeight = 30.sp), p.accent, maxLines = 1)
                        }
                        Txt(k.caption, Type.body.copy(fontSize = 26.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold), p.text, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun TitleCard(title: String, sub: String, lines: List<String>) {
        val p = Dark
        CompositionLocalProvider(LocalPalette provides p) {
            Box(Modifier.fillMaxSize().background(Color(0xFF070C0D)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Logo(120.dp); Spacer(Modifier.width(28.dp))
                        Txt(title, Type.giant.copy(fontSize = 96.sp))
                    }
                    Txt(sub, Type.headline, p.accent)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Weather.entries.forEach { w ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { WeatherBadge(w, 110.dp); Txt(w.label, Type.title, p.text(w)) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    lines.forEach { Txt(it, Type.title.copy(fontWeight = FontWeight.Medium), p.muted) }
                }
            }
        }
    }


    @Composable
    private fun Stage(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalPalette provides Dark) {
            Box(Modifier.fillMaxSize().background(Color(0xFF070C0D)).padding(horizontal = 160.dp, vertical = 110.dp), contentAlignment = Alignment.CenterStart) { content() }
        }
    }

    @Composable
    private fun ProblemCard() = Stage {
        val p = Dark
        Column(verticalArrangement = Arrangement.spacedBy(30.dp)) {
            Txt("아이 멀미, 왜 매번 당할까요?", Type.giant.copy(fontSize = 84.sp, lineHeight = 96.sp))
            Spacer(Modifier.height(10.dp))
            listOf(
                Triple(Weather.RAIN, "4~13세에 가장 잦습니다", "6개월~18세 조사에서 9.2%가 멀미에 취약 (Huppert 외, J Neurol 2019)"),
                Triple(Weather.OVERCAST, "원인의 큰 몫은 운전 — 굽은 길·가다서다", "그런데 운전자는 뒷자리에서 무슨 일이 일어나는지 모릅니다"),
                Triple(Weather.CLOUDY, "폰 앱은 화면을 보는 한 사람만 돕습니다", "아이는 폰 대신 창밖을 보거나 잠듭니다 — 증상이 생긴 뒤에야 대응"),
            ).forEach { (w, t, d) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherBadge(w, 120.dp); Spacer(Modifier.width(36.dp))
                    Column { Txt(t, Type.headline); Txt(d, Type.title.copy(fontWeight = FontWeight.Medium), p.muted) }
                }
            }
        }
    }

    @Composable
    private fun SolutionCard() = Stage {
        val p = Dark
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(96.dp); Spacer(Modifier.width(24.dp))
                Txt("차가 이미 아는 것으로, 미리", Type.giant.copy(fontSize = 80.sp, lineHeight = 92.sp))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                listOf(
                    Triple("내비 앞길", "멀미 예보", "굽은 길·정체 → 맑음·구름·흐림·비"),
                    Triple("공조·창문", "차가 먼저 준비", "흐림 2km 전 바깥 공기·온도·바람·창문"),
                    Triple("음성", "한마디로", "운전자 코칭 · 쉼터 제안"),
                ).forEach { (from, to, d) ->
                    Box(Modifier.weight(1f).height(250.dp).clip(RoundedCornerShape(28.dp)).background(p.surface).border(2.dp, p.border, RoundedCornerShape(28.dp)).padding(36.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Txt(from, Type.title, p.muted)
                            Txt("→ $to", Type.headline.copy(fontSize = 52.sp, lineHeight = 62.sp), p.accent)
                            Txt(d, Type.title.copy(fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 40.sp), p.text)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Txt("아이는 아무것도 하지 않아도 됩니다. 휴대폰이 아니라 차만 할 수 있는 일입니다.", Type.title, p.muted)
        }
    }

    @Test fun frames() {
        val dir = File("build/video").apply { mkdirs() }
        val keys = collect()
        val arr = JSONArray()
        captureRoboImage("build/video/a0.png") {
            TitleCard("뒷자리 맑음", "멀미, 이제 예보하고 피하세요.", listOf("Pleos Connect 차량용 앱 · 시연 영상"))
        }
        arr.put(JSONObject().put("file", "a0.png").put("kind", "title"))
        captureRoboImage("build/video/a1.png") { ProblemCard() }
        arr.put(JSONObject().put("file", "a1.png").put("kind", "card").put("narration",
            "아이 멀미는 네 살부터 열세 살 사이에 가장 잦습니다. 원인의 큰 몫은 굽은 길과 가다서다인데, 운전자는 뒷자리를 볼 수 없고, 아이는 휴대폰을 보지 않습니다."))
        captureRoboImage("build/video/a2.png") { SolutionCard() }
        arr.put(JSONObject().put("file", "a2.png").put("kind", "card").put("narration",
            "뒷자리 맑음은 차가 이미 아는 앞길로 멀미를 예보하고, 공기와 창문을 먼저 바꾸고, 운전자에게 한마디로 알려 줍니다. 7살 아이와 양평 캠핑장에 가는 길을 따라가 보겠습니다."))
        keys.forEachIndexed { i, k ->
            val name = "k%02d.png".format(i + 1)
            captureRoboImage("build/video/$name") { Frame(k) }
            arr.put(
                JSONObject().put("file", name).put("minute", k.minute).put("caption", k.caption)
                    .put("say", k.say?.text ?: JSONObject.NULL).put("to", k.say?.to?.name ?: JSONObject.NULL)
                    .put("heard", k.heard ?: JSONObject.NULL).put("who", k.who ?: JSONObject.NULL),
            )
        }
        val last = "k%02d.png".format(keys.size + 1)
        captureRoboImage("build/video/$last") {
            TitleCard(
                "뒷자리 맑음", "차만 할 수 있는 멀미 케어",
                listOf(
                    "Pleos Connect SDK 5종 — Vehicle · NaviHelper · TTS · STT · FusedLocation",
                    "생성형 AI 없이 규칙 기반 예보 — 예측 가능한 짧은 안내 · 기록은 차 안에만",
                    "이 영상은 모의 차량 신호로 시나리오를 재생해 그린 실제 앱 화면입니다",
                ),
            )
        }
        arr.put(JSONObject().put("file", last).put("kind", "outro").put("narration", "멀미, 이제 예보하고 피하세요. 뒷자리 맑음."))
        File(dir, "keys.json").writeText(arr.toString(2))
    }
}
