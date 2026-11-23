package app.backseatclear.data

import android.content.Context
import app.backseatclear.core.model.Rider
import app.backseatclear.core.model.Seat
import app.backseatclear.core.session.TripReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 가족 프로필 — 차 안(앱 내부 파일)에만 저장. 실명 대신 별명 */
class FamilyStore(context: Context) {
    private val file = File(context.filesDir, "family.json")

    fun load(): List<Rider> {
        if (!file.exists()) return SEED
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Rider(Seat.valueOf(o.getString("seat")), o.getString("nickname"), o.optInt("sensitivity", 2), o.optInt("earlierSteps", 0))
            }
        }.getOrElse { SEED }
    }

    fun save(riders: List<Rider>) {
        val arr = JSONArray()
        riders.forEach { r ->
            arr.put(JSONObject().put("seat", r.seat.name).put("nickname", r.nickname).put("sensitivity", r.sensitivity).put("earlierSteps", r.earlierSteps))
        }
        file.writeText(arr.toString())
    }

    /** 신고가 있었던 사람은 다음에 한 단계 일찍(최대 2단계) */
    fun learnFrom(report: TripReport) {
        val rider = report.rider ?: return
        if (report.nextTime == null) return
        save(load().map { if (it.seat == rider.seat && it.nickname == rider.nickname) it.copy(earlierSteps = (it.earlierSteps + 1).coerceAtMost(2)) else it })
    }

    fun riderFor(seat: Seat): Rider = load().firstOrNull { it.seat == seat } ?: Rider(seat, seat.label)

    fun clear() { file.delete() }

    companion object {
        /** 화면 예시 데이터 */
        val SEED = listOf(
            Rider(Seat.REAR_LEFT, "콩이", sensitivity = 3),
            Rider(Seat.REAR_RIGHT, "할머니", sensitivity = 2),
        )
    }
}

/** 지난 길 기록 — 최근 30개. 목소리·정확한 경로는 저장하지 않는다 */
class TripLogStore(context: Context) {
    private val file = File(context.filesDir, "trips.json")

    data class Entry(val routeName: String, val overall: String, val endedAt: Long)

    fun add(r: TripReport, endedAt: Long = System.currentTimeMillis()) {
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        arr.put(
            JSONObject().put("routeName", r.routeName).put("overall", r.overall.name).put("minutes", r.minutes)
                .put("complaints", r.complaints).put("rests", r.rests).put("smooth", r.smoothRatio ?: JSONObject.NULL)
                .put("endedAt", endedAt),
        )
        val keep = JSONArray()
        val from = (arr.length() - 30).coerceAtLeast(0)
        for (i in from until arr.length()) keep.put(arr.get(i))
        file.writeText(keep.toString())
    }

    fun recent(): List<Entry> = runCatching {
        val arr = JSONArray(file.readText())
        (arr.length() - 1 downTo 0).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("routeName"), o.getString("overall"), o.getLong("endedAt"))
        }
    }.getOrElse { emptyList() }

    fun clear() { file.delete() }
}
