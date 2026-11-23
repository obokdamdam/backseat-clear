package app.backseatclear.sim

import android.content.Context
import android.speech.tts.TextToSpeech
import app.backseatclear.core.care.CareAction
import app.backseatclear.core.model.RestStop
import app.backseatclear.core.voice.Utterance
import app.backseatclear.port.NaviPort
import app.backseatclear.port.VehiclePort
import app.backseatclear.port.VoicePort
import java.util.Locale

/** 모의 차량: 조치를 기록만 한다(화면 「차가 한 일」·시연용) */
class SimVehicle(
    override val supported: Set<CareAction.Kind> = CareAction.Kind.entries.toSet(),
    private val onApply: (CareAction) -> Unit = {},
) : VehiclePort {
    override fun apply(action: CareAction) = onApply(action)
    override fun release() = Unit
}

class SimNavi(private val onWaypoint: (RestStop) -> Unit = {}) : NaviPort {
    override fun addWaypoint(stop: RestStop) = onWaypoint(stop)
    override fun watchArrival(stop: RestStop, radiusM: Int) = Unit
}

/**
 * 안드로이드 기본 TTS 로 실제 소리를 낸다 — Pleos TTS 가 없는 기기·에뮬레이터에서 시연 영상을 찍을 때.
 * 듣기는 시나리오가 대신 넣어 주므로 아무것도 하지 않는다.
 */
class AndroidTtsVoice(context: Context, private val onSpeak: (Utterance) -> Unit = {}) : VoicePort {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.also { it.language = Locale.KOREAN }

    override fun say(u: Utterance) {
        onSpeak(u)
        if (ready) tts.speak(u.text, TextToSpeech.QUEUE_ADD, null, u.kind.name + System.nanoTime())
    }

    override fun listen(seconds: Int, onText: (String) -> Unit, onTimeout: () -> Unit) = Unit
    override fun release() { tts.stop(); tts.shutdown() }
}

/** 소리 없이 기록만 (단위 테스트·무음 시연) */
class SilentVoice(private val onSpeak: (Utterance) -> Unit = {}) : VoicePort {
    override fun say(u: Utterance) = onSpeak(u)
    override fun listen(seconds: Int, onText: (String) -> Unit, onTimeout: () -> Unit) = Unit
    override fun release() = Unit
}
