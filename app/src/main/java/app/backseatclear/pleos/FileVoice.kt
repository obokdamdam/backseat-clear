package app.backseatclear.pleos

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import app.backseatclear.core.voice.Utterance
import app.backseatclear.port.VoicePort
import org.json.JSONObject

/**
 * 앱 안내 음성 = 개발자 본인 목소리로 미리 합성한 파일(assets/voice, tools/make_voice.py 가 만듦).
 * 문장 파일이 있으면 그 파일을 틀고, 없으면(휴게소 이름처럼 그때그때 바뀌는 말) [fallback](Pleos TTS)으로 말한다.
 * 듣기는 fallback 에 맡긴다. Pleos 에뮬레이터는 TTS 엔진이 오류라 이 방식이 시연에서도 소리를 낸다.
 */
class FileVoice(context: Context, private val fallback: VoicePort, private val onSpeak: (Utterance) -> Unit = {}) : VoicePort {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    private val index: Map<String, String> = runCatching {
        val o = JSONObject(app.assets.open("voice/index.json").bufferedReader(Charsets.UTF_8).readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrElse { Log.w("BackseatClear", "voice index: ${it.message}"); emptyMap() }
    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null

    override fun say(u: Utterance) {
        val file = index[u.text] ?: return fallback.say(u)
        onSpeak(u)
        queue += file
        if (player == null) playNext()
    }

    private fun playNext() {
        val file = queue.removeFirstOrNull() ?: run { audio.abandonAudioFocusRequest(focus); return }
        Log.i("BackseatClear", "음성 재생 $file") // 시연 영상에 소리를 맞춰 넣을 때 이 시각을 쓴다
        runCatching {
            audio.requestAudioFocus(focus)
            val fd = app.assets.openFd("voice/$file")
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setOnCompletionListener { it.release(); player = null; playNext() }
                setOnErrorListener { mp, _, _ -> mp.release(); player = null; playNext(); true }
                prepare()
                start()
            }
        }.onFailure { Log.w("BackseatClear", "voice play $file: ${it.message}"); player = null; playNext() }
    }

    override fun listen(seconds: Int, onText: (String) -> Unit, onTimeout: () -> Unit) = fallback.listen(seconds, onText, onTimeout)

    override fun release() {
        queue.clear()
        runCatching { player?.release() }
        player = null
        audio.abandonAudioFocusRequest(focus)
        fallback.release()
    }
}
