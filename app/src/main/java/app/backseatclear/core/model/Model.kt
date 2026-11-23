package app.backseatclear.core.model

/** 멀미 날씨 네 단계. 점수가 높을수록 멀미가 오기 쉽다. */
enum class Weather(val label: String) {
    CLEAR("맑음"), CLOUDY("구름"), OVERCAST("흐림"), RAIN("비");

    fun atLeast(other: Weather) = ordinal >= other.ordinal
    fun max(other: Weather) = if (other.ordinal > ordinal) other else this
    fun lower() = entries[(ordinal - 1).coerceAtLeast(0)]

    /** 받침 따라: 맑음이에요 · 비예요 / 맑음이었어요 · 비였어요 (「비이에요」 문법 오류를 음성 받아쓰기에서 발견) */
    val isYo get() = if (this == RAIN) "${label}예요" else "${label}이에요"
    val wasYo get() = if (this == RAIN) "${label}였어요" else "${label}이었어요"
}

enum class Seat(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒷자리 왼쪽"), REAR_RIGHT("뒷자리 오른쪽"), REAR_CENTER("뒷자리 가운데");

    val isRear get() = this == REAR_LEFT || this == REAR_RIGHT || this == REAR_CENTER
}

/** 누구에게 말하나. 차가 좌석별 소리(오디오 존)를 지원하면 그 자리로, 아니면 전체 스피커로. */
enum class Audience { DRIVER, REAR, ALL }

/** 경로의 1km 남짓 구간. 내비 TBT·혼잡·주의 구간에서 만든다. */
data class RouteSegment(
    val index: Int,
    val startKm: Double,
    val lengthKm: Double,
    /** 출발 후 이 구간에 들어서는 예상 시각(분) */
    val etaMinute: Double,
    /** 1km 당 회전 안내 수 */
    val turnsPerKm: Double,
    val congested: Boolean = false,
    val caution: Boolean = false,
) {
    val endKm get() = startKm + lengthKm
}

/** 경로 위 쉼터(휴게소·졸음쉼터). 내비 경로 정보에서 받는다 */
data class RestStop(val name: String, val km: Double)

data class Route(
    val name: String,
    val segments: List<RouteSegment>,
    val restStops: List<RestStop> = emptyList(),
) {
    /** km 지점의 예상 경과 시간(분) — 구간 안에서는 직선 보간 */
    fun minuteAt(km: Double): Double {
        val s = segmentAt(km) ?: return 0.0
        val frac = ((km - s.startKm) / s.lengthKm).coerceIn(0.0, 1.0)
        return s.etaMinute + minutesOf(s) * frac
    }

    val totalKm get() = segments.lastOrNull()?.endKm ?: 0.0
    val totalMinutes get() = segments.lastOrNull()?.let { it.etaMinute + minutesOf(it) } ?: 0.0

    fun segmentAt(km: Double): RouteSegment? =
        segments.firstOrNull { km >= it.startKm && km < it.endKm } ?: segments.lastOrNull()?.takeIf { km >= it.endKm }

    /** 구간 하나를 지나는 데 걸리는 예상 시간(다음 구간 eta 와의 차) */
    fun minutesOf(s: RouteSegment): Double {
        val next = segments.getOrNull(s.index + 1) ?: return s.lengthKm // 마지막 구간: 60km/h 가정
        return next.etaMinute - s.etaMinute
    }
}

enum class Reason(val text: String) {
    CURVES("굽은 길"), CONGESTION("가다서다"), CAUTION("주의 구간"), LONG_DRIVE("오래 달림"), SENSITIVE("멀미에 예민한 편"),
}

/** 탄 사람의 멀미 민감도와 「한 단계 일찍」 보정 */
data class Rider(
    val seat: Seat,
    val nickname: String,
    /** 1 잘 안 함 · 2 보통 · 3 예민 */
    val sensitivity: Int = 2,
    /** 가족 맞춤: 몇 단계 일찍 예보할지(지난 신고에서 배움) */
    val earlierSteps: Int = 0,
)
