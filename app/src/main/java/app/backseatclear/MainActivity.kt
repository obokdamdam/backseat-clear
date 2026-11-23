package app.backseatclear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.backseatclear.core.session.Phase
import app.backseatclear.ui.TripController
import app.backseatclear.ui.screens.BeforeDepartureScreen
import app.backseatclear.ui.screens.DrivingScreen
import app.backseatclear.ui.screens.PrivacyScreen
import androidx.compose.runtime.key
import app.backseatclear.ui.screens.FamilyScreen
import app.backseatclear.ui.screens.HomeScreen
import app.backseatclear.ui.screens.ReportScreen
import app.backseatclear.ui.theme.Dark
import app.backseatclear.ui.theme.Light
import app.backseatclear.ui.theme.LocalPalette

class MainActivity : ComponentActivity() {
    private val vm: TripController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 차에서는 내비가 앞에 있어도 계속 돌도록 포그라운드 서비스를 켠다
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) TripService.start(this)
        handle(intent)
        // 차속·마이크는 위험 권한 — 처음 한 번 묻는다(허용되면 다음 실행부터 차속을 받는다)
        val need = arrayOf("android.car.permission.CAR_SPEED", android.Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: android.content.Intent?) {
        intent?.extras?.keySet()?.takeIf { it.isNotEmpty() }?.let { android.util.Log.i("BackseatClear", "intent $it route=${intent.getStringExtra("route")}") }
        // 캡처·시연 자동화: adb shell am start -n app.backseatclear/.MainActivity --ef jump 41.6
        intent?.getFloatExtra("jump", -1f)?.takeIf { it >= 0 }?.let { vm.jumpTo(it.toDouble()) }
        if (intent?.getBooleanExtra("demo", false) == true) vm.startDemo()
        // 에뮬레이터 확인: adb shell am start -n app.backseatclear/.MainActivity --es route "37.4917,127.4875,양평군청"
        intent?.getStringExtra("care")?.let { vm.testCare(it) }
        // 경유지(내비 장소 검색) 확인: --es waypoint "덕평휴게소"
        intent?.getStringExtra("waypoint")?.let { vm.testWaypoint(it) }
        intent?.getStringExtra("nearAt")?.split(",")?.takeIf { it.size >= 2 }?.let { (la, lo) ->
            vm.testNearAt(la.toDouble(), lo.toDouble(), intent.getIntExtra("nearRadius", 500), intent.getIntExtra("nearAfter", 20))
        }
        // 마이크 대신 들은 말을 넣는다: --es heard "sick" (경로가 시작된 뒤 몇 초 후 실행)
        // 시연 녹화용 시간 예약: --ei heardAfter 25 (경로 시작 25초 뒤) · --ei routeAfter 8 (앱을 켜고 8초 뒤 경로 요청)
        intent?.getStringExtra("heard")?.let { vm.testHeard(it, intent.getIntExtra("heardAfter", 3)) }
        intent?.getStringExtra("route")?.split(",")?.takeIf { it.size >= 3 }?.let { (la, lo, n) ->
            vm.requestTestRoute(la.toDouble(), lo.toDouble(), n, intent.getIntExtra("routeAfter", 0))
        }
    }
}

@Composable
private fun App(vm: TripController) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val restricted by vm.restricted.collectAsStateWithLifecycle()
    var family by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(0) }
    val palette = if (isSystemInDarkTheme()) Dark else Light

    CompositionLocalProvider(LocalPalette provides palette) {
        Box(Modifier.fillMaxSize().background(palette.bg)) {
            when {
                family && privacy && !restricted -> {
                    BackHandler { privacy = false }
                    PrivacyScreen(onBack = { privacy = false })
                }
                family && !restricted -> {
                    BackHandler { family = false }
                    key(cleared) {
                        FamilyScreen(vm.family.load(), vm.trips.recent(), onBack = { family = false },
                            onClear = { vm.family.clear(); vm.trips.clear(); cleared++ }, onPrivacy = { privacy = true })
                    }
                }
                ui.phase == Phase.IDLE -> HomeScreen(
                    onDemo = { vm.startDemo() }, onJump = { vm.jumpTo(it) }, onFamily = { family = true }, recent = vm.trips.recent(),
                )
                ui.phase == Phase.BEFORE_DEPARTURE -> BeforeDepartureScreen(ui, restricted, onOk = {})
                ui.phase == Phase.ARRIVED && ui.report != null -> ReportScreen(ui.report!!, onOk = { vm.stopDemo() })
                else -> DrivingScreen(ui)
            }
        }
    }
}
