package app.backseatclear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.backseatclear.core.care.CareAction
import app.backseatclear.core.forecast.WeatherSpan
import app.backseatclear.core.model.Audience
import app.backseatclear.core.voice.Utterance
import app.backseatclear.ui.theme.P
import app.backseatclear.ui.theme.Type

@Composable
fun Txt(text: String, style: TextStyle, color: Color = P.text, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style.copy(color = color, fontFamily = style.fontFamily ?: app.backseatclear.ui.theme.AstaSans), overflow = TextOverflow.Ellipsis, maxLines = maxLines)

fun Modifier.card(border: Color? = null) = this.then(
    Modifier.clip(RoundedCornerShape(24.dp))
)

@Composable
fun Card(modifier: Modifier = Modifier, borderColor: Color? = null, bg: Color = P.surface, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .border(1.dp, borderColor ?: P.border, RoundedCornerShape(24.dp))
    ) { content() }
}

@Composable
fun AppBar(badge: String, driving: Boolean) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(40.dp)
        Spacer(Modifier.width(14.dp))
        Txt("뒷자리 맑음", TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(CircleShape).background(if (driving) P.tintAccent else P.surface2).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val c = if (driving) P.accent else P.muted
            Box(Modifier.size(10.dp).background(c, CircleShape))
            Spacer(Modifier.width(8.dp))
            Txt(badge, TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium), c)
        }
    }
}

/** 예보 띠: 구간별 날씨 막대 + 지금 위치 */
@Composable
fun ForecastStrip(spans: List<WeatherSpan>, totalMinutes: Double, nowFraction: Double?, modifier: Modifier = Modifier, height: Int = 22) {
    if (spans.isEmpty() || totalMinutes <= 0) return
    val p = P
    BoxWithConstraints(modifier.fillMaxWidth().height((height + 20).dp)) {
        val w = maxWidth
        Row(Modifier.fillMaxWidth().height(height.dp).offset(y = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            spans.forEach { sp ->
                val frac = ((sp.toMinute - sp.fromMinute) / totalMinutes).toFloat().coerceAtLeast(0.01f)
                Box(Modifier.weight(frac).height(height.dp).clip(RoundedCornerShape(6.dp)).background(p.graphic(sp.weather)))
            }
        }
        if (nowFraction != null) {
            Box(Modifier.offset(x = w * nowFraction.toFloat() - 2.dp).width(4.dp).height((height + 20).dp).clip(RoundedCornerShape(2.dp)).background(p.text))
        }
    }
}

private fun glyphOf(chip: String) = when {
    chip.contains("공기") -> Glyph.AIR
    chip.contains("℃") || chip.contains("온도") -> Glyph.TEMP
    chip.contains("창문") -> Glyph.WINDOW
    else -> Glyph.WIND
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionChips(chips: List<String>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chips.forEach { c ->
            Row(
                Modifier.clip(CircleShape).background(P.tintAccent).heightIn(min = 56.dp).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(glyphOf(c), P.accent)
                Spacer(Modifier.width(10.dp))
                Txt(c, Type.body.copy(fontSize = 22.sp))
            }
        }
    }
}

/** 말하는 중 / 듣는 중 — 주행 중 유일한 상호작용(버튼 아님) */
@Composable
fun VoiceBar(speaking: Utterance?, listening: String?, modifier: Modifier = Modifier) {
    if (speaking == null && listening == null) return
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (listening != null) {
                GlyphCircle(Glyph.MIC, P.accent, P.onAccent)
                Spacer(Modifier.width(18.dp))
                Column {
                    Txt("듣고 있어요", Type.label, P.muted)
                    Txt(listening, Type.body.copy(fontWeight = FontWeight.Bold))
                }
            } else if (speaking != null) {
                GlyphCircle(Glyph.SPEAK, P.tintAccent, P.accent)
                Spacer(Modifier.width(18.dp))
                Column {
                    Txt(when (speaking.to) { Audience.DRIVER -> "운전자에게"; Audience.REAR -> "뒷자리에"; Audience.ALL -> "모두에게" }, Type.label, P.muted)
                    Txt("「${speaking.text}」", Type.title, maxLines = 2)
                }
            }
        }
    }
}

/** 정차 중에만 보이는 버튼 (높이 76) */
@Composable
fun BigButton(text: String, primary: Boolean = true, glyph: Glyph? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (primary) P.accent else P.surface2
    val fg = if (primary) P.onAccent else P.text
    Row(
        modifier.heightIn(min = 76.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .semantics { role = Role.Button }.clickable(onClick = onClick).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) { GlyphIcon(glyph, fg, 28.dp); Spacer(Modifier.width(10.dp)) }
        Txt(text, TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold), fg, maxLines = 1)
    }
}

@Composable
fun Toast(text: String, glyph: Glyph = Glyph.INFO, modifier: Modifier = Modifier) {
    Card(modifier, bg = P.surface2) {
        Row(Modifier.padding(horizontal = 26.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, if (glyph == Glyph.CHECK) P.success else P.muted, 40.dp)
            Spacer(Modifier.width(18.dp))
            Txt(text, Type.body)
        }
    }
}

fun chipsOf(actions: List<CareAction>) = actions.map { it.chip }
