package app.backseatclear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Seat
import app.backseatclear.core.session.TripSession
import app.backseatclear.core.session.UiState
import app.backseatclear.data.FamilyStore
import app.backseatclear.sim.ScenarioA
import app.backseatclear.ui.screens.BeforeDepartureScreen
import app.backseatclear.ui.screens.DrivingScreen
import app.backseatclear.ui.screens.FamilyScreen
import app.backseatclear.ui.screens.PrivacyScreen
import app.backseatclear.ui.screens.HomeScreen
import app.backseatclear.ui.screens.ReportScreen
import app.backseatclear.ui.theme.Dark
import app.backseatclear.ui.theme.Light
import app.backseatclear.ui.theme.LocalPalette
import app.backseatclear.ui.theme.Palette
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 실제 앱 화면(Compose)을 에뮬레이터 없이 PNG 로 그린다 → app/build/screens/
 * 시나리오 A 를 그 분까지 감은 TripSession 상태를 그대로 쓴다(가짜 화면 아님).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
class ScreenShots {
    private val kong = Rider(Seat.REAR_LEFT, "콩이", sensitivity = 3)

    private fun stateAt(minute: Double): UiState {
        val s = TripSession(riderFor = { if (it == Seat.REAR_LEFT) kong else Rider(it, it.label) })
        val until = (minute * 60_000).toLong()
        ScenarioA.events().takeWhile { it.atMs <= until }.forEach { s.handle(it.event, it.atMs) }
        return s.state
    }

    private fun shot(name: String, palette: Palette = Dark, content: @Composable () -> Unit) =
        captureRoboImage("build/screens/$name.png") {
            CompositionLocalProvider(LocalPalette provides palette) {
                Box(Modifier.fillMaxSize().background(palette.bg)) { content() }
            }
        }

    @Test fun s0_home() = shot("S0_처음") { HomeScreen({}, {}, {}, emptyList()) }
    @Test fun s1_before() = shot("S1_출발전") { BeforeDepartureScreen(stateAt(0.3), restricted = false, onOk = {}) }
    @Test fun s2_clear() = shot("S2_주행중_맑음") { DrivingScreen(stateAt(12.0)) }
    @Test fun s3_prepare() = shot("S3_주행중_흐림준비") { DrivingScreen(stateAt(42.5)) }
    @Test fun s4_complaint() = shot("S4_주행중_속이안좋아") { DrivingScreen(stateAt(56.0)) }
    @Test fun s4b_resting() = shot("S4b_쉬는중") { DrivingScreen(stateAt(64.0)) }
    @Test fun s7_report() = shot("S7_도착리포트") { ReportScreen(stateAt(200.0).report!!, onOk = {}) }
    @Test fun s8_family() = shot("S8_우리가족") { FamilyScreen(FamilyStore.SEED, emptyList(), onBack = {}, onClear = {}, onPrivacy = {}) }
    @Test fun s9_privacy() = shot("S9_개인정보안내") { PrivacyScreen(onBack = {}) }

    @Config(qualifiers = "w640dp-h720dp-port-mdpi")
    @Test fun s5_narrow() = shot("S5_세로창") { DrivingScreen(stateAt(42.5)) }

    @Config(qualifiers = "w640dp-h720dp-port-mdpi")
    @Test fun s6_light() = shot("S6_낮테마", Light) { DrivingScreen(stateAt(12.0)) }
}
