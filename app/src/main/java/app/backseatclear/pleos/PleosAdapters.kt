package app.backseatclear.pleos

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.backseatclear.core.care.CareAction
import app.backseatclear.core.model.RestStop
import app.backseatclear.core.model.Seat
import app.backseatclear.core.session.TripEvent
import app.backseatclear.core.voice.Utterance
import app.backseatclear.port.EventSource
import app.backseatclear.port.NaviPort
import app.backseatclear.port.VehiclePort
import app.backseatclear.port.VoicePort
import java.util.Locale

private const val TAG = "BackseatClear"

/**
 * Vehicle SDK 2.3.2 의 좌석 탑승 Boolean 은 AAOS 원시 값(1=비어 있음, 2=탐)을 「1 이면 true」로 바꿔 준다.
 * 그래서 사람이 타면 false 가 온다(Pleos 에뮬레이터 Virtual Console 로 확인, 2026-10-28). SDK 가 고쳐지면 여기만 바꾼다.
 */
private const val SDK_OCCUPIED = false
private fun warn(where: String, e: Throwable) { Log.w(TAG, "$where: ${e.message}") }

/**
 * Pleos Vehicle SDK — 좌석·브레이크·조향·내외기 사건을 보내고, 공조·창문 조치를 실행한다.
 * 기능마다 capability 를 먼저 확인하고, 안 되는 종류는 supported 에서 뺀다(조치 생략 + 한 번 알림).
 */
class PleosVehicle(context: Context) : VehiclePort, EventSource {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var sink: (TripEvent) -> Unit = {}
    private val rear = listOf(Seat.REAR_LEFT to SeatValue.ROW_2_LEFT, Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT)
    // 공조 영역은 차마다 좌석 묶음이 다르다(에뮬레이터: 운전석 단독 영역 없음) → capability 가 알려 준 영역을 쓴다.
    // 뒷좌석이 들어간 영역을 먼저, 없으면 첫 영역.
    private var recircArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var tempArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var fanArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var rearWindow = WindowArea(WindowValue.ROW_2_LEFT)
    private val rearSeats = setOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT)
    private fun pick(areas: Collection<SeatArea>): SeatArea? = areas.firstOrNull { a -> a.values.any { it in rearSeats } } ?: areas.firstOrNull()

    private var baseTempC: Float? = null
    private var baseFan: Int? = null
    private var fanRange: IntRange? = null
    private var windowRange: IntRange? = null
    private var ok = false
    private val listeners = mutableListOf<() -> Unit>() // 해제 동작 모음

    override var supported: Set<CareAction.Kind> = emptySet(); private set

    init {
        ok = runCatching { vehicle.initialize() }.onFailure { warn("vehicle init", it) }.isSuccess
        if (ok) probe()
    }

    private fun probe() {
        val hvac = vehicle.getHVAC()
        val found = mutableSetOf<CareAction.Kind>()
        fun areaText(a: Any) = (a as? SeatArea)?.values?.joinToString("+") ?: (a as? WindowArea)?.values?.joinToString("+") ?: a.toString()
        hvac.checkHvacRecirculationCapabilities(onComplete = { _, areas ->
            Log.i(TAG, "cap recirc: ${areas.map(::areaText)}")
            pick(areas)?.let { recircArea = it; found += CareAction.Kind.AIR }; supported = found.toSet()
        }, onFailed = { warn("recirc cap", it) })
        hvac.checkHvacTemperatureCapabilities(onComplete = { _, m, _ ->
            Log.i(TAG, "cap temp: ${m.keys.map(::areaText)}")
            pick(m.keys)?.let { a ->
                tempArea = a; found += CareAction.Kind.TEMP
                hvac.getHvacTemperature(area = a, unit = TemperatureUnit.CELSIUS, onComplete = { v -> baseTempC = v; Log.i(TAG, "temp now $v") }, onFailed = { warn("temp get", it) })
            }; supported = found.toSet()
        }, onFailed = { warn("temp cap", it) })
        hvac.checkHvacFanSpeedCapabilities(onComplete = { _, m ->
            Log.i(TAG, "cap fan: ${m.map { (k, v) -> "${areaText(k)}=${v.minValue}..${v.maxValue}" }}")
            pick(m.keys)?.let { a ->
                fanArea = a; m[a]?.let { fanRange = it.minValue..it.maxValue }; found += CareAction.Kind.FAN
                hvac.getHvacFanSpeed(area = a, onComplete = { v -> baseFan = v; Log.i(TAG, "fan now $v") }, onFailed = { warn("fan get", it) })
            }; supported = found.toSet()
        }, onFailed = { warn("fan cap", it) })
        vehicle.getWindow().checkWindowPositionCapabilities(onComplete = { _, m ->
            Log.i(TAG, "cap window: ${m.map { (k, v) -> "${areaText(k)}=${v.minValue}..${v.maxValue}" }}")
            val w = m.keys.firstOrNull { k -> k.values.any { it == WindowValue.ROW_2_LEFT || it == WindowValue.ROW_2_RIGHT } }
            w?.let { rearWindow = it; m[it]?.let { b -> windowRange = b.minValue..b.maxValue }; found += CareAction.Kind.WINDOW }; supported = found.toSet()
        }, onFailed = { warn("window cap", it) })
    }

    override fun start(sink: (TripEvent) -> Unit) {
        if (!ok) return
        this.sink = { e -> main.post { sink(e) } }
        val seat = vehicle.getSeat()
        rear.forEach { (s, v) ->
            val area = SeatArea(v)
            var occupied = false; var belted = false
            val occ = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
                    occupied = occupancy == SDK_OCCUPIED
                    this@PleosVehicle.sink(TripEvent.SeatChanged(s, occupied, belted))
                }
                override fun onFailed(e: Exception) = warn("seat", e)
            }
            val belt = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { belted = state == true; if (occupied) this@PleosVehicle.sink(TripEvent.SeatChanged(s, true, belted)) } // 벨트는 Virtual Console 에 없어 확인 못 함
                override fun onFailed(e: Exception) = warn("belt", e)
            }
            runCatching { seat.registerSeatOccupancy(area, occ); seat.registerSeatBeltBuckledState(area, belt) }.onFailure { warn("seat reg", it) }
            listeners += { runCatching { seat.unregisterSeatOccupancy(area, occ); seat.unregisterSeatBeltBuckledState(area, belt) } }
        }
        // 운전석은 늘 탄 것으로 본다
        this.sink(TripEvent.SeatChanged(Seat.DRIVER, true, true))

        var lastBrake = false
        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                val now = status == true
                if (now && !lastBrake) this@PleosVehicle.sink(TripEvent.Brake) // 밟는 순간만 센다
                lastBrake = now
            }
            override fun onFailed(e: Exception) = warn("brake", e)
        }
        val steer = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) { angle?.let { this@PleosVehicle.sink(TripEvent.Steering(it.toDouble())) } }
            override fun onFailed(e: Exception) = warn("steer", e)
        }
        val recirc = object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) { state?.let { this@PleosVehicle.sink(TripEvent.Recirculation(it)) } }
            override fun onFailed(e: Exception) = warn("recirc", e)
        }
        runCatching { vehicle.getBrake().registerBrakePedalStatus(brake) }.onFailure { warn("brake reg", it) }
        runCatching { vehicle.getSteeringWheel().registerSteeringWheelAngle(steer) }.onFailure { warn("steer reg", it) }
        runCatching { vehicle.getHVAC().registerHvacRecirculationState(recircArea, recirc) }.onFailure { warn("recirc reg", it) }
        listeners += {
            runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brake) }
            runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steer) }
            runCatching { vehicle.getHVAC().unregisterHvacRecirculationState(recircArea, recirc) }
        }
    }

    override fun apply(action: CareAction) {
        if (!ok || action.kind !in supported) return
        val hvac = vehicle.getHVAC()
        runCatching {
            when (action) {
                CareAction.FreshAir -> hvac.setHvacRecirculation(area = recircArea, value = false, onFailed = { warn("fresh air", it) })
                is CareAction.TempOffset -> {
                    // 공조가 꺼져 있으면 설정 온도를 읽을 수 없다(에뮬레이터 확인) → 조치할 때 다시 읽고, 그래도 없으면 건너뛴다
                    val set = { base: Float -> hvac.setHvacTemperature(area = tempArea, value = base + action.offsetC, unit = TemperatureUnit.CELSIUS, onFailed = { e -> warn("temp", e) }) }
                    baseTempC?.let(set) ?: hvac.getHvacTemperature(area = tempArea, unit = TemperatureUnit.CELSIUS,
                        onComplete = { v -> v?.let { baseTempC = it; set(it) } }, onFailed = { warn("temp get (공조 꺼짐?)", it) })
                }
                is CareAction.RearFan -> {
                    val r = fanRange ?: return
                    val v = ((baseFan ?: r.first) + action.step).coerceIn(r)
                    hvac.setHvacFanSpeed(area = fanArea, value = v, onFailed = { warn("fan", it) })
                }
                is CareAction.RearWindow -> {
                    val r = windowRange ?: return
                    // 0% = 닫힘 쪽(최솟값)으로 가정 — 에뮬레이터에서 방향 확인 필요
                    val v = r.first + ((r.last - r.first) * action.percent / 100.0).toInt()
                    vehicle.getWindow().setWindowPosition(area = rearWindow, value = v, onFailed = { warn("window", it) })
                }
            }
        }.onFailure { warn("apply $action", it) }
    }

    override fun stop() { listeners.forEach { it() }; listeners.clear() }
    override fun release() { stop(); if (ok) runCatching { vehicle.release() } }
}

/** 차속: Vehicle SDK 에 없어 AAOS 표준 CarPropertyManager 로 읽는다 */
class CarSpeed(context: Context) : EventSource {
    private val car: Car? = runCatching { Car.createCar(context.applicationContext) }.getOrNull()
    private var cb: CarPropertyManager.CarPropertyEventCallback? = null

    override fun start(sink: (TripEvent) -> Unit) {
        val m = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        val c = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) { (v.value as? Float)?.let { sink(TripEvent.Speed(it * 3.6)) } } // m/s → km/h
            override fun onErrorEvent(propId: Int, zone: Int) = Unit
        }
        cb = c
        runCatching { m.registerCallback(c, VehiclePropertyIds.PERF_VEHICLE_SPEED, 1f) }.onFailure { warn("speed", it) }
    }

    override fun stop() {
        val m = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        cb?.let { runCatching { m.unregisterCallback(it) } }
    }
}

/**
 * Pleos NaviHelper — 경로 시작·진행·도착 사건, 회전 안내·혼잡·주의 구간으로 경로를 만든다.
 * 쉼터 근접: 「응」 뒤 내비에 경유지가 들어오면 그 좌표(getWaypointInfo)로 FusedLocation 근접 알림을 건다.
 * 좌표는 알림을 거는 데만 쓰고 저장하지 않는다. 내비의 경유지 도착 사건은 알림이 안 될 때의 보조.
 */
class PleosNavi(context: Context) : NaviPort, EventSource {
    private val navi = NaviHelper(context.applicationContext)
    private val fused = FusedLocation(context.applicationContext)
    private var watching: Pair<RestStop, Int>? = null
    private var alertId: String? = null
    private var restNotified = false
    private var sink: (TripEvent) -> Unit = {}
    private var start: RouteStartInfo? = null
    private var tbt: List<TBTInfo> = emptyList()
    private var cautions: List<CautionPoint> = emptyList()
    private var traffic: RouteStateInfo? = null
    private var sent = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { start = routeStartInfo; sent = false; restNotified = false; navi.getTBTInfo(); navi.getRouteStateInfo() }
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) { tbt = tbtInfoList; maybeSend() }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { traffic = routeStateInfo; maybeSend() }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) { cautions = cautionPointList }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val s = start ?: return
            sink(TripEvent.Progress(((s.distance - drivingInfo.destination.distance) / 1000.0).coerceAtLeast(0.0)))
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = nearRest("내비 경유지 도착")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) { if (watching != null) navi.getWaypointInfo() }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = watchWaypoint(waypointInfoList)
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = sink(TripEvent.Arrived)
    }

    private fun maybeSend() {
        val s = start ?: return
        if (sent || tbt.isEmpty()) return
        sent = true
        val sharp = setOf(TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R, TurnType.TURN_UTURN)
        val skip = setOf(TurnType.TURN_STRAIGHT, TurnType.TURN_UNKNOWN, TurnType.TURN_ARRIVE, TurnType.TURN_TOLL, TurnType.TURN_TUNNEL)
        val rest = setOf(TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA)
        val cautionTypes = setOf(CautionPointType.DOWNHILL_CAUTION_AREA, CautionPointType.FALLING_ROCK_AREA, CautionPointType.NARROW_ROAD, CautionPointType.FOG_AREA, CautionPointType.SPEED_BUMP)
        val route = RouteBuilder.build(
            name = s.name, totalM = s.distance.toDouble(), totalSeconds = s.duration.toDouble(),
            turns = tbt.filter { it.type !in skip && it.type !in rest }.map { RouteBuilder.Turn(it.distance, it.type in sharp) },
            jams = traffic?.trafficStatus?.congestionItemList.orEmpty().map { RouteBuilder.Jam(it.offset, it.distance) },
            cautionAtM = cautions.filter { it.type in cautionTypes }.map { it.remainDistance.toDouble() },
            rests = tbt.filter { it.type in rest }.map { (it.description.ifBlank { "휴게소" }) to it.distance },
        )
        sink(TripEvent.RouteStarted(route))
    }

    override fun start(sink: (TripEvent) -> Unit) {
        this.sink = sink
        runCatching { navi.initialize(); navi.addListener(listener) }.onFailure { warn("navi init", it) }
        runCatching { fused.initialize() }.onFailure { warn("fused init", it) }
    }

    override fun stop() {
        clearAlert()
        runCatching { navi.removeListener(listener); navi.release() }
        runCatching { fused.release() }
    }

    private fun nearRest(why: String) {
        if (restNotified) return
        restNotified = true
        Log.i(TAG, "사건 쉼터 근접 ($why)")
        clearAlert()
        sink(TripEvent.NearRest)
    }

    /** 내비가 알려 준 경유지 중 제안한 쉼터(이름이 맞는 것, 없으면 마지막 경유지)에 근접 알림을 건다 */
    private fun watchWaypoint(list: List<WaypointInfo>) {
        val (stop, radius) = watching ?: return
        if (alertId != null || list.isEmpty()) return
        val pick = list.firstOrNull { stop.name.isNotBlank() && it.name.contains(stop.name.removeSuffix("휴게소").trim()) } ?: list.last()
        watchAt(pick.latitude, pick.longitude, radius, pick.name)
    }

    private fun watchAt(lat: Double, lon: Double, radiusM: Int, name: String) {
        if (runCatching { fused.isSupported() }.getOrDefault(false).not()) { Log.i(TAG, "근접 알림 미지원 → 내비 경유지 도착으로 대신"); return }
        val req = ProximityAlertRequest(lat, lon, radiusM.toFloat(), setOf(ProximityTransition.ENTER))
        runCatching {
            fused.addProximityAlert(req, object : ProximityAlertListener {
                override fun onRegistered(alertId: String) { this@PleosNavi.alertId = alertId; Log.i(TAG, "근접 알림 등록: $name 반경 ${radiusM}m") }
                override fun onTransition(event: ProximityAlertEvent) { if (event.transition == ProximityTransition.ENTER) nearRest("근접 알림") }
                override fun onError(e: Exception) = warn("proximity", e)
            })
        }.onFailure { warn("proximity", it) }
    }

    private fun clearAlert() {
        alertId?.let { id -> runCatching { fused.removeProximityAlert(id) } }
        alertId = null
        watching = null
    }

    /** 시험용: 지금 위치 근처에 바로 근접 알림을 건다(에뮬레이터 내비 위치는 움직이지 않으므로) */
    fun testWatchAt(lat: Double, lon: Double, radiusM: Int) {
        restNotified = false
        watching = RestStop("시험 쉼터", 0.0) to radiusM
        watchAt(lat, lon, radiusM, "시험 쉼터")
    }

    fun requestRoute(lat: Double, lon: Double, name: String) {
        Log.i(TAG, "경로 요청 $name ($lat, $lon)")
        runCatching { navi.requestRoute(RouteInfo(latitude = lat, longitude = lon, poiName = name, poiId = "", address = name, poiSubId = "")) }.onFailure { warn("route", it) }
    }

    /**
     * 「응」 → 경유지. 회전 안내에는 휴게소 좌표가 없고, 휴게소 좌표 공개 파일도 없다(도로공사 LOD 는 IC·영업소만, 2026-10-29 확인).
     * 그래서 Pleos 내비의 장소 검색 화면을 그 휴게소 이름으로 연다 → 내비가 찾은 곳을 내비 화면에서 경유지로 넣는다.
     */
    override fun addWaypoint(stop: RestStop) {
        val keyword = stop.name.takeIf { it.isNotBlank() && it != "휴게소" } ?: "휴게소"
        Log.i(TAG, "경유지 요청: 내비 장소 검색 「$keyword」")
        runCatching { navi.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }.onFailure { warn("waypoint", it) }
    }

    override fun watchArrival(stop: RestStop, radiusM: Int) {
        clearAlert()
        restNotified = false
        watching = stop to radiusM
        runCatching { navi.getWaypointInfo() } // 이미 들어간 경유지가 있으면 바로 건다
    }
}

/** Pleos TTS·STT — 물을 때만 듣는다 */
class PleosVoice(context: Context, private val onSpeak: (Utterance) -> Unit = {}) : VoicePort {
    private val tts = TextToSpeech(context.applicationContext)
    private val stt = SpeechToText(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var pending: Pair<(String) -> Unit, () -> Unit>? = null
    private val timeout = Runnable { pending?.second?.invoke(); pending = null; runCatching { stt.stop() } }

    init {
        runCatching { tts.initialize(); tts.setLocale(Locale.KOREAN) }.onFailure { warn("tts init", it) }
        runCatching { stt.initialize(); stt.setLocale(Locale.KOREAN) }.onFailure { warn("stt init", it) }
        runCatching {
            tts.addEventListener(object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = Unit
                override fun onDone() = Unit
                override fun onStop() = Unit
                override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) { Log.w(TAG, "tts $errorType") }
                override fun onUpdatedRms(rms: Double) = Unit
            })
            stt.addListener(object : ResultListener {
                override fun onReady() = Unit
                override fun onStartedRecognition() = Unit
                override fun onUpdated(stt: String, completed: Boolean) {
                    if (!completed) return
                    main.removeCallbacks(timeout)
                    val p = pending; pending = null
                    main.post { p?.first?.invoke(stt) }
                }
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onEndedRecognition() = Unit
                override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { Log.w(TAG, "stt $errorType") }
                override fun onUpdatedRms(rms: Double) = Unit
            })
        }.onFailure { warn("voice listeners", it) }
    }

    override fun say(u: Utterance) {
        onSpeak(u)
        runCatching { tts.speak(u.text) }.onFailure { warn("speak", it) }
    }

    override fun listen(seconds: Int, onText: (String) -> Unit, onTimeout: () -> Unit) {
        pending = onText to onTimeout
        runCatching { stt.request() }.onFailure { warn("listen", it) }
        main.postDelayed(timeout, seconds * 1000L)
    }

    override fun release() {
        main.removeCallbacks(timeout)
        runCatching { tts.stop(); tts.release() }
        runCatching { stt.stop(); stt.release() }
    }
}
