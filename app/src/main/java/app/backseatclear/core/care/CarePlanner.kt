package app.backseatclear.core.care

/** 차가 하는 조치. 폭은 작게: 온도 최대 −2℃, 창문 최대 20%. */
sealed interface CareAction {
    val kind: Kind
    /** 화면 칩에 쓰는 짧은 말 */
    val chip: String

    data object FreshAir : CareAction { override val kind = Kind.AIR; override val chip = "바깥 공기로" }
    data class TempOffset(val offsetC: Int) : CareAction {
        override val kind = Kind.TEMP
        override val chip = if (offsetC == 0) "온도 원래대로" else "${-offsetC}℃ 낮춤"
    }
    data class RearFan(val step: Int) : CareAction {
        override val kind = Kind.FAN
        override val chip = when { step <= 0 -> "바람 원래대로"; step == 1 -> "뒷자리 바람 조금 세게"; else -> "시원한 바람" }
    }
    data class RearWindow(val percent: Int) : CareAction {
        override val kind = Kind.WINDOW
        override val chip = when { percent <= 0 -> "창문 닫음"; percent <= 10 -> "뒷창문 조금"; else -> "창문 조금 더" }
    }

    enum class Kind(val label: String) { AIR("바람 방향"), TEMP("온도"), FAN("바람"), WINDOW("창문") }
}

/**
 * 날씨 변화·신고에 맞는 조치를 정한다.
 * - 이미 한 조치는 다시 하지 않는다.
 * - 사람이 직접 바꾼 종류는 그 구간 동안 손대지 않는다(사람 우선).
 * - 이 차가 지원하지 않는 종류는 건너뛰고, 한 번만 알린다.
 */
class CarePlanner(private val supportedNow: () -> Set<CareAction.Kind> = { CareAction.Kind.entries.toSet() }) {
    private val current = mutableMapOf<CareAction.Kind, CareAction>()
    private val userHeld = mutableSetOf<CareAction.Kind>()
    private val toldUnsupported = mutableSetOf<CareAction.Kind>()

    data class Plan(val actions: List<CareAction>, val newlyUnsupported: List<CareAction.Kind>)

    /** 흐림 2km 전: 미리 준비 */
    fun prepare(): Plan = plan(listOf(CareAction.FreshAir, CareAction.TempOffset(-1), CareAction.RearFan(1), CareAction.RearWindow(10)))

    /** 실시간 점수가 오를 때: 바람 한 단계 더 */
    fun intensify(): Plan = plan(listOf(CareAction.RearFan(2)))

    /** 「속이 안 좋아」: 더 강하게 */
    fun relieve(): Plan = plan(listOf(CareAction.FreshAir, CareAction.TempOffset(-2), CareAction.RearFan(2), CareAction.RearWindow(20)))

    /** 굽은 길이 끝나고 맑아지면: 창문·온도 원래대로 (바깥 공기는 유지) */
    fun restore(): Plan {
        val list = mutableListOf<CareAction>()
        if ((current[CareAction.Kind.WINDOW] as? CareAction.RearWindow)?.percent ?: 0 > 0) list += CareAction.RearWindow(0)
        if ((current[CareAction.Kind.TEMP] as? CareAction.TempOffset)?.offsetC ?: 0 != 0) list += CareAction.TempOffset(0)
        if ((current[CareAction.Kind.FAN] as? CareAction.RearFan)?.step ?: 0 > 0) list += CareAction.RearFan(0)
        return plan(list)
    }

    fun userAdjusted(kind: CareAction.Kind) { userHeld += kind }

    /** 새 구간(날씨가 바뀜)에 들어서면 사람 우선 표시를 푼다 */
    fun newStretch() = userHeld.clear()

    fun active(): List<CareAction> = current.values.filter {
        when (it) {
            is CareAction.TempOffset -> it.offsetC != 0
            is CareAction.RearFan -> it.step > 0
            is CareAction.RearWindow -> it.percent > 0
            CareAction.FreshAir -> true
        }
    }

    private fun plan(wanted: List<CareAction>): Plan {
        val out = mutableListOf<CareAction>()
        val unsupported = mutableListOf<CareAction.Kind>()
        for (a in wanted) {
            if (a.kind !in supportedNow()) {
                if (toldUnsupported.add(a.kind)) unsupported += a.kind
                continue
            }
            if (a.kind in userHeld) continue
            if (current[a.kind] == a) continue
            current[a.kind] = a
            out += a
        }
        return Plan(out, unsupported)
    }
}
