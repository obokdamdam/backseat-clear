package app.backseatclear.ui

import android.app.Application
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.backseatclear.core.care.CareAction
import app.backseatclear.core.session.Command
import app.backseatclear.core.session.Phase
import app.backseatclear.core.session.TripEvent
import app.backseatclear.core.session.TripSession
import app.backseatclear.core.session.UiState
import app.backseatclear.data.FamilyStore
import app.backseatclear.data.TripLogStore
import app.backseatclear.pleos.CarSpeed
import app.backseatclear.pleos.FileVoice
import app.backseatclear.pleos.PleosNavi
import app.backseatclear.pleos.PleosVehicle
import app.backseatclear.pleos.PleosVoice
import app.backseatclear.port.EventSource
import app.backseatclear.port.NaviPort
import app.backseatclear.port.VehiclePort
import app.backseatclear.port.VoicePort
import app.backseatclear.sim.AndroidTtsVoice
import app.backseatclear.sim.ScenarioA
import app.backseatclear.sim.SimNavi
import app.backseatclear.sim.SimVehicle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 화면과 두뇌(TripSession)·포트를 잇는다.
 * 지금은 모의 포트(시나리오 A 재생 + 안드로이드 TTS)로 돈다. Pleos 어댑터가 붙으면 포트만 바꾼다.
 */
class TripController(app: Application) : AndroidViewModel(app) {
    val family = FamilyStore(app)
    val trips = TripLogStore(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** 차가 주행 중이라 버튼을 숨겨야 하나 (CarUxRestrictions, 없으면 앱 상태로 판단) */
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted

    private val _actions = MutableStateFlow<List<CareAction>>(emptyList())
    val actions: StateFlow<List<CareAction>> = _actions

    var demoSpeed = 20.0
    private var demoJob: Job? = null

    private val simVehicle = SimVehicle(onApply = { a -> _actions.value = _actions.value + a })
    private val simVoice: VoicePort by lazy { FileVoice(app, AndroidTtsVoice(app)) }
    private var vehicle: VehiclePort = simVehicle
    private var navi: NaviPort = SimNavi()
    private var voice: VoicePort = simVoice
    private var session = newSession()

    private var car: Car? = null
    private var carRestricted: Boolean? = null

    /** 차(자동차용 안드로이드)면 Pleos SDK 로 실제 사건을 받는다 */
    private val isCar = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private var live: List<EventSource> = emptyList()
    private var liveVehicle: PleosVehicle? = null
    private var liveNavi: PleosNavi? = null
    private var liveVoice: VoicePort? = null

    init {
        connectUxRestrictions(app)
        if (isCar) startLive(app)
    }

    private fun startLive(app: Application) {
        runCatching {
            val v = PleosVehicle(app).also { liveVehicle = it }
            val n = PleosNavi(app).also { liveNavi = it }
            // 안내 음성: 미리 합성한 음성 파일 우선, 없는 문장만 Pleos TTS
            liveVoice = FileVoice(app, PleosVoice(app))
            useLivePorts()
            live = listOf(v, n, CarSpeed(app))
            live.forEach { src -> src.start { e -> if (demoJob?.isActive != true) { trace(e); dispatch(e, nowMs()) } } }
        }
    }

    private fun useLivePorts() {
        vehicle = liveVehicle ?: simVehicle
        navi = liveNavi ?: SimNavi()
        voice = liveVoice ?: simVoice
    }

    private fun useSimPorts() { vehicle = simVehicle; navi = SimNavi(); voice = simVoice }

    private fun newSession() = TripSession(riderFor = family::riderFor, supported = { vehicle.supported })

    /** 시나리오 A 를 처음부터 재생 (speed 배속) */
    fun startDemo(speed: Double = demoSpeed) {
        demoJob?.cancel()
        useSimPorts()
        session = newSession()
        _actions.value = emptyList()
        _ui.value = UiState()
        demoJob = viewModelScope.launch {
            var last = 0L
            for (t in ScenarioA.events()) {
                val wait = ((t.atMs - last) / speed).toLong()
                if (wait > 0) delay(wait)
                last = t.atMs
                dispatch(t.event, t.atMs)
            }
        }
    }

    /** 시연·캡처용: 시나리오 A 를 minute 분까지 바로 감는다(소리 없이) */
    fun jumpTo(minute: Double) {
        demoJob?.cancel()
        useSimPorts()
        session = newSession()
        _actions.value = emptyList()
        val until = (minute * 60_000).toLong()
        ScenarioA.events().takeWhile { it.atMs <= until }.forEach { session.handle(it.event, it.atMs).forEach { c -> execute(c, silent = true) } }
        publish()
    }

    /** 에뮬레이터 확인용: 내비에 목적지 경로를 요청한다(실제 내비가 경로를 시작하면 앱이 사건을 받는다) */
    fun requestTestRoute(lat: Double, lon: Double, name: String, afterSec: Int = 0) {
        viewModelScope.launch { delay(afterSec * 1000L); liveNavi?.requestRoute(lat, lon, name) }
    }

    fun testWaypoint(name: String) {
        viewModelScope.launch { delay(5000); liveNavi?.addWaypoint(app.backseatclear.core.model.RestStop(name, 0.0)) }
    }

    /** 시험용: 지금 위치에 쉼터 근접 알림(FusedLocation)을 건다 */
    fun testNearAt(lat: Double, lon: Double, radiusM: Int, afterSec: Int) {
        viewModelScope.launch { delay(afterSec * 1000L); liveNavi?.testWatchAt(lat, lon, radiusM) }
    }

    /** 에뮬레이터 확인용: 경로가 시작되기를 기다렸다가(최대 60초) afterSec 뒤 들은 말 사건을 넣는다. sick = 「속이 안 좋아」, yes = 「응」 */
    fun testHeard(what: String, afterSec: Int = 3) {
        val text = when (what) { "sick" -> "속이 안 좋아"; "yes" -> "응"; else -> what }
        viewModelScope.launch {
            repeat(90) { if (session.state.phase != Phase.IDLE) return@repeat; delay(1000) }
            delay(afterSec * 1000L)
            android.util.Log.i("BackseatClear", "시험 들은 말 「$text」 (단계 ${session.state.phase})")
            dispatch(TripEvent.Heard(text), nowMs())
        }
    }

    /** 에뮬레이터 확인용: 조치 하나를 실제 차에 바로 실행한다 (window=뒷창문 30%, fresh=외기, fan=바람+1, temp=−1℃) */
    fun testCare(what: String) {
        val a = when (what) {
            "window" -> CareAction.RearWindow(30); "fresh" -> CareAction.FreshAir
            "fan" -> CareAction.RearFan(1); "temp" -> CareAction.TempOffset(-1); else -> return
        }
        // 차 기능 확인(capability)이 끝난 뒤에 실행
        viewModelScope.launch {
            delay(5000)
            android.util.Log.i("BackseatClear", "시험 조치 $a (지원: ${liveVehicle?.supported})")
            liveVehicle?.apply(a)
        }
    }

    fun stopDemo() { demoJob?.cancel(); useLivePorts(); session = newSession(); _actions.value = emptyList(); _ui.value = UiState(); publish() }

    fun dispatch(e: TripEvent, nowMs: Long) {
        session.handle(e, nowMs).forEach { execute(it, silent = false) }
        publish()
    }

    /** 실차·에뮬레이터 확인용 기록(adb logcat -s BackseatClear). 조향·차속은 너무 잦아 뺀다 */
    private fun trace(e: TripEvent) {
        if (e !is TripEvent.Steering && e !is TripEvent.Speed) android.util.Log.i("BackseatClear", "사건 $e")
    }

    private fun execute(c: Command, silent: Boolean) {
        if (!silent && demoJob?.isActive != true) android.util.Log.i("BackseatClear", "명령 $c")
        when (c) {
            is Command.Say -> if (!silent) voice.say(c.u)
            is Command.Apply -> vehicle.apply(c.action)
            is Command.Listen -> voice.listen(c.seconds, { dispatch(TripEvent.Heard(it), nowMs()) }, { dispatch(TripEvent.ListenTimeout, nowMs()) })
            is Command.AddWaypoint -> navi.addWaypoint(c.stop)
            is Command.WatchArrival -> navi.watchArrival(c.stop, c.radiusM)
            is Command.SaveTrip -> if (!silent) { trips.add(c.report); family.learnFrom(c.report) }
        }
    }

    private fun nowMs() = android.os.SystemClock.elapsedRealtime()

    private fun publish() {
        _ui.value = session.state
        val s = session.state.phase
        // 차가 제한 중이거나, (데모로) 앱이 주행 중이라고 보면 버튼을 숨긴다
        _restricted.value = carRestricted == true || s == Phase.DRIVING
    }

    /** AAOS 의 주행 중 제한 상태를 받는다. 차가 아닌 기기에서는 앱 상태(주행 중)로 대신한다 */
    private fun connectUxRestrictions(app: Application) {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        runCatching {
            val c = Car.createCar(app) ?: return
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            fun apply(r: CarUxRestrictions?) {
                carRestricted = r?.isRequiresDistractionOptimization ?: false
                publish()
            }
            apply(m.currentCarUxRestrictions)
            m.registerListener { apply(it) }
        }
    }

    override fun onCleared() {
        demoJob?.cancel()
        live.forEach { runCatching { it.stop() } }
        runCatching { liveVoice?.release() }; runCatching { liveVehicle?.release() }
        if (isCar.not() || liveVoice == null) runCatching { simVoice.release() }
        runCatching { car?.disconnect() }
    }
}
