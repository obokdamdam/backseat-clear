package app.backseatclear.port

import app.backseatclear.core.care.CareAction
import app.backseatclear.core.model.RestStop
import app.backseatclear.core.session.TripEvent
import app.backseatclear.core.voice.Utterance

/** 말하기·듣기 — Pleos TTS/STT 또는 안드로이드 기본 TTS(데모) */
interface VoicePort {
    fun say(u: Utterance)
    /** 물을 때만 seconds 동안 듣는다. 늘 듣지 않는다 */
    fun listen(seconds: Int, onText: (String) -> Unit, onTimeout: () -> Unit)
    fun release()
}

/** 차량 조치 — Pleos Vehicle SDK 또는 모의 */
interface VehiclePort {
    /** 이 차가 지원하는 조치 종류 (capability 확인 결과) */
    val supported: Set<CareAction.Kind>
    fun apply(action: CareAction)
    fun release()
}

/** 내비 — 경유지 넣기 */
interface NaviPort {
    fun addWaypoint(stop: RestStop)
    fun watchArrival(stop: RestStop, radiusM: Int)
}

/** 차·내비에서 오는 사건 */
interface EventSource {
    fun start(sink: (TripEvent) -> Unit)
    fun stop()
}
