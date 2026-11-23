package app.backseatclear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * 내비 화면이 앞에 있어도 앱이 계속 차 신호를 받도록 하는 포그라운드 서비스.
 * 이게 없으면 경로가 시작돼 내비가 앞으로 나올 때 안드로이드가 앱을 얼려서(cached app freezer)
 * 급제동·「속이 안 좋아」에 반응하지 못한다(Pleos 에뮬레이터 녹화에서 발견, 2026-10-29).
 * 판단·조치는 그대로 TripController 가 하고, 이 서비스는 프로세스를 살려 두기만 한다.
 */
class TripService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "뒷자리 맑음 예보", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("뒷자리 맑음")
            .setContentText("뒷자리 멀미 예보를 켜 두었어요")
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "trip"
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TripService::class.java)) }
        }
    }
}
