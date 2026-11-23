package app.backseatclear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.backseatclear.core.model.Weather

/** 디자인 시안 tokens.json 그대로 (대비 검사 34쌍 통과한 값) */
@Immutable
data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val border: Color,
    val text: Color, val muted: Color, val accent: Color, val onAccent: Color,
    val sun: Color, val cloud: Color, val overcast: Color, val rain: Color,
    /** 아이콘·띠 같은 그림용 색 (낮 테마에서는 글자색보다 밝다) */
    val sunG: Color, val cloudG: Color, val overcastG: Color, val rainG: Color,
    val tintSun: Color, val tintCloud: Color, val tintOvercast: Color, val tintRain: Color, val tintAccent: Color,
    val success: Color,
) {
    fun text(w: Weather) = when (w) { Weather.CLEAR -> sun; Weather.CLOUDY -> cloud; Weather.OVERCAST -> overcast; Weather.RAIN -> rain }
    fun graphic(w: Weather) = when (w) { Weather.CLEAR -> sunG; Weather.CLOUDY -> cloudG; Weather.OVERCAST -> overcastG; Weather.RAIN -> rainG }
    fun tint(w: Weather) = when (w) { Weather.CLEAR -> tintSun; Weather.CLOUDY -> tintCloud; Weather.OVERCAST -> tintOvercast; Weather.RAIN -> tintRain }
}

val Dark = Palette(
    bg = Color(0xFF0D1517), surface = Color(0xFF152124), surface2 = Color(0xFF1D2C30), border = Color(0xFF2A3B40),
    text = Color(0xFFEEF6F6), muted = Color(0xFFA3B4B7), accent = Color(0xFF43D3CB), onAccent = Color(0xFF05302D),
    sun = Color(0xFFFFCB4D), cloud = Color(0xFFA9DCEA), overcast = Color(0xFFFFA24A), rain = Color(0xFFA6A3FF),
    sunG = Color(0xFFFFCB4D), cloudG = Color(0xFFA9DCEA), overcastG = Color(0xFFFFA24A), rainG = Color(0xFFA6A3FF),
    tintSun = Color(0x24FFCB4D), tintCloud = Color(0x24A9DCEA), tintOvercast = Color(0x29FFA24A), tintRain = Color(0x29A6A3FF),
    tintAccent = Color(0x2443D3CB), success = Color(0xFF6FDC9A),
)

val Light = Palette(
    bg = Color(0xFFF2F7F7), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFE6EFF0), border = Color(0xFFCFDCDE),
    text = Color(0xFF0E1A1C), muted = Color(0xFF4A5C60), accent = Color(0xFF007F79), onAccent = Color(0xFFFFFFFF),
    sun = Color(0xFF8A5A00), cloud = Color(0xFF1F6F86), overcast = Color(0xFFA94F00), rain = Color(0xFF4F49C9),
    sunG = Color(0xFFF2A900), cloudG = Color(0xFF5BB6D0), overcastG = Color(0xFFF08A2C), rainG = Color(0xFF7D78F0),
    tintSun = Color(0xFFFFF3D1), tintCloud = Color(0xFFDEF2F8), tintOvercast = Color(0xFFFFE6CF), tintRain = Color(0xFFE7E6FF),
    tintAccent = Color(0xFFD9F3F1), success = Color(0xFF1F7A4D),
)

val LocalPalette = staticCompositionLocalOf { Dark }

/** Pleos 시스템 글꼴 Asta Sans (SIL OFL 1.1 — 라이선스 원문은 저장소 OFL_AstaSans.txt) */
val AstaSans = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(app.backseatclear.R.font.asta_sans_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(app.backseatclear.R.font.asta_sans_bold, FontWeight.Bold),
    androidx.compose.ui.text.font.Font(app.backseatclear.R.font.asta_sans_extrabold, FontWeight.ExtraBold),
)

/** 글자 단계: 주행 중에는 Body(24) 이상만 */
object Type {
    val label = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold)
    val headline = TextStyle(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold)
    val hero = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold)
    val giant = TextStyle(fontSize = 120.sp, lineHeight = 128.sp, fontWeight = FontWeight.ExtraBold)
}

val P: Palette @Composable get() = LocalPalette.current
