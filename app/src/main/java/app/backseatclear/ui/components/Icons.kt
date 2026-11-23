package app.backseatclear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.backseatclear.core.model.Weather
import app.backseatclear.ui.theme.P

/** 시안(board.html)에서 직접 그린 아이콘을 같은 좌표(64×64)로 옮긴 것 */
private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

private val CLOUD = path("M19 52h25a11 11 0 0 0 0-22 14 14 0 0 0-26.6 3.6A9.2 9.2 0 0 0 19 52z")
private val CLOUD_BACK = path("M30 34h20a9 9 0 0 0 0-18 11.5 11.5 0 0 0-21.8 3A7.5 7.5 0 0 0 30 34z")
private val CLOUD_FRONT = path("M15 54h25a11 11 0 0 0 0-22 14 14 0 0 0-26.6 3.6A9.2 9.2 0 0 0 15 54z")
private val CLOUD_RAIN = path("M17 40h26a11 11 0 0 0 0-22 14 14 0 0 0-26.6 3.6A9.2 9.2 0 0 0 17 40z")
private val SEAT = path("M15 12c-3 0-4.6 2.6-4 5.6l4.6 23.2c.8 4 3.6 6.2 7.6 6.2H45c3.3 0 5-1.8 5-4.6s-1.7-4.6-5-4.6H25.4L21 15.4C20.4 13 18.6 12 15 12z")

private fun DrawScope.in64(block: DrawScope.() -> Unit) {
    val k = size.minDimension / 64f
    scale(k, k, pivot = Offset.Zero) { block() }
}

private fun DrawScope.rays(c: Color, cx: Float, cy: Float, inner: Float, outer: Float, width: Float) {
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
        drawLine(c, Offset(cx + dx * inner, cy + dy * inner), Offset(cx + dx * outer, cy + dy * outer), width, StrokeCap.Round)
    }
}

fun DrawScope.drawWeather(w: Weather, c: Color) = in64 {
    when (w) {
        Weather.CLEAR -> { drawCircle(c, 12f, Offset(32f, 32f)); rays(c, 32f, 32f, 18f, 25f, 5f) }
        Weather.CLOUDY -> {
            drawCircle(c.copy(alpha = c.alpha * .55f), 9f, Offset(40f, 22f))
            drawPath(CLOUD, c)
        }
        Weather.OVERCAST -> { drawPath(CLOUD_BACK, c.copy(alpha = c.alpha * .5f)); drawPath(CLOUD_FRONT, c) }
        Weather.RAIN -> {
            drawPath(CLOUD_RAIN, c)
            listOf(22f, 33f, 44f).forEach { x -> drawLine(c, Offset(x, 47f), Offset(x - 3f, 55f), 4.5f, StrokeCap.Round) }
        }
    }
}

/** 동그란 바탕 위 날씨 아이콘 */
@Composable
fun WeatherBadge(w: Weather, size: Dp, modifier: Modifier = Modifier, plain: Boolean = false) {
    val p = P
    Box(
        modifier.size(size).semantics { contentDescription = "멀미 날씨 ${w.label}" }
            .then(if (plain) Modifier else Modifier.background(p.tint(w), CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(if (plain) size else size * 0.62f)) { drawWeather(w, p.graphic(w)) }
    }
}

/** 로고: 뒷좌석 + 작은 해 */
@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) {
    val p = P
    Canvas(modifier.size(size).semantics { contentDescription = "뒷자리 맑음" }) {
        in64 {
            drawPath(SEAT, p.accent)
            drawLine(p.accent, Offset(20f, 47f), Offset(17f, 54f), 4f, StrokeCap.Round)
            drawLine(p.accent, Offset(44f, 47f), Offset(47f, 54f), 4f, StrokeCap.Round)
            drawCircle(Color(0xFFFFCB4D), 6.5f, Offset(44f, 20f))
            rays(Color(0xFFFFCB4D), 44f, 20f, 10f, 12.5f, 3f)
        }
    }
}

/** 작은 선 아이콘(칩·음성 막대용) — 32×32 좌표 */
enum class Glyph(val d: String, val filled: Boolean = false) {
    AIR("M4 22c4-8 10-12 24-12M20 4l8 6-8 6M4 28h24"),
    TEMP("M12 5a3 3 0 0 1 6 0v12.5a6 6 0 1 1-6 0zM24 9v9M21 15l3 3 3-3"),
    WIND("M4 12h15a4 4 0 1 0-4-4M4 18h20a4 4 0 1 1-4 4M4 24h8"),
    WINDOW("M5 26V12l9-7h13v21zM8 15h16M16 22v-4M13 20l3-3 3 3"),
    MIC("M11 8a5 5 0 0 1 10 0v7a5 5 0 0 1-10 0zM6 15a10 10 0 0 0 20 0M16 25v4"),
    SPEAK("M4 12h5l7-6v20l-7-6H4zM21 11a6 6 0 0 1 0 10M24.5 7a11 11 0 0 1 0 18"),
    REST("M5 12h17v7a7 7 0 0 1-7 7h-3a7 7 0 0 1-7-7zM22 14h2a4 4 0 0 1 0 8h-2M10 3c-1 2 1 3 0 6M15 3c-1 2 1 3 0 6"),
    CHECK("M6 17l6 6L26 9"),
    INFO("M16 4a12 12 0 1 1 0 24a12 12 0 1 1 0-24zM16 14v8M16 9.5v0.1"),
    STAR("M16 4l3.6 7.6 8.2 1-6 5.7 1.6 8.2L16 22.5l-7.4 4 1.6-8.2-6-5.7 8.2-1z", filled = true),
    PLAY("M10 6l16 10-16 10z", filled = true),
}

@Composable
fun GlyphIcon(g: Glyph, color: Color, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    val pth = androidx.compose.runtime.remember(g) { path(g.d) }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 32f
        scale(k, k, pivot = Offset.Zero) {
            if (g.filled) drawPath(pth, color)
            else drawPath(pth, color, style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
fun GlyphCircle(g: Glyph, bg: Color, fg: Color, size: Dp = 56.dp) {
    Box(Modifier.size(size).background(bg, CircleShape).padding(size * 0.22f), contentAlignment = Alignment.Center) {
        GlyphIcon(g, fg, size * 0.56f)
    }
}
