package app.backseatclear.core.forecast

/**
 * 달리는 동안의 실제 신호로 예보 점수를 보정한다.
 * 브레이크를 자주 밟거나, 급가감속·급조향이 이어지거나, 내기순환이 오래 이어지면 점수가 오른다.
 * 「부드럽게 지난 비율」(리포트·칭찬)도 여기서 센다.
 */
class LiveScorer {
    private val brakes = ArrayDeque<Long>()
    private val harsh = ArrayDeque<Long>()
    private var lastSpeed: Pair<Long, Double>? = null
    private var lastSteer: Pair<Long, Double>? = null
    private var recircSince: Long? = null

    private var curvyMs = 0L
    private var curvyHarshMs = 0L
    private var lastSample: Long? = null

    fun onBrake(nowMs: Long) { brakes += nowMs; trim(nowMs) }

    /** 차속(km/h). 1초에 2.5m/s² 넘는 변화를 급가감속으로 센다 */
    fun onSpeed(nowMs: Long, kmh: Double) {
        lastSpeed?.let { (t, v) ->
            val dt = (nowMs - t) / 1000.0
            if (dt in 0.2..3.0) {
                val accel = (kmh - v) / 3.6 / dt
                if (kotlin.math.abs(accel) >= 2.5) harsh += nowMs
            }
        }
        lastSpeed = nowMs to kmh
        trim(nowMs)
    }

    /** 조향각(도). 초당 90도 넘게 꺾으면 급조향으로 센다 */
    fun onSteering(nowMs: Long, deg: Double) {
        lastSteer?.let { (t, a) ->
            val dt = (nowMs - t) / 1000.0
            if (dt in 0.1..2.0 && kotlin.math.abs(deg - a) / dt >= 90.0) harsh += nowMs
        }
        lastSteer = nowMs to deg
        trim(nowMs)
    }

    fun onRecirculation(nowMs: Long, on: Boolean) {
        recircSince = if (on) recircSince ?: nowMs else null
    }

    fun recircMinutes(nowMs: Long): Double = recircSince?.let { (nowMs - it) / 60_000.0 } ?: 0.0

    fun brakesLastMinute(nowMs: Long): Int { trim(nowMs); return brakes.size }
    fun harshLastMinute(nowMs: Long): Int { trim(nowMs); return harsh.size }

    /** 지금 더할 점수 */
    fun adjustment(nowMs: Long): Double {
        trim(nowMs)
        var add = 0.0
        val b = brakes.size
        if (b > 2) add += ((b - 2) * 6.0).coerceAtMost(18.0)
        if (harsh.size >= 2) add += 8.0
        if (recircMinutes(nowMs) >= 30) add += 6.0
        return add
    }

    /** 굽은 길 구간에서 시간을 나눠 센다: 최근 1분 안에 급한 움직임이 없었으면 「부드럽게」 */
    fun sampleCurvy(nowMs: Long, inCurvySection: Boolean) {
        val prev = lastSample
        lastSample = nowMs
        if (prev == null || !inCurvySection) return
        val dt = nowMs - prev
        curvyMs += dt
        if (brakesLastMinute(nowMs) > 2 || harshLastMinute(nowMs) >= 2) curvyHarshMs += dt
    }

    /** 굽은 길을 부드럽게 지난 비율. 굽은 길이 없었으면 null */
    fun smoothRatio(): Double? = if (curvyMs <= 0) null else (curvyMs - curvyHarshMs).toDouble() / curvyMs

    /** 마지막으로 잰 뒤 주어진 시간 동안 급한 움직임이 없었나 (칭찬 판단) */
    fun calmFor(nowMs: Long): Boolean = brakesLastMinute(nowMs) <= 2 && harshLastMinute(nowMs) < 2

    private fun trim(nowMs: Long) {
        while (brakes.isNotEmpty() && nowMs - brakes.first() > 60_000) brakes.removeFirst()
        while (harsh.isNotEmpty() && nowMs - harsh.first() > 60_000) harsh.removeFirst()
    }
}
