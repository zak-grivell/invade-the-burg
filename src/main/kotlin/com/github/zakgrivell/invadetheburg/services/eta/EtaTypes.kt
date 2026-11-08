package com.github.zakgrivell.invadetheburg.services.eta

import java.time.Instant

// Shared contract between Lane A (tracker/log/history/wiring) and Lane B (predictor/decision/evaluation).
// Change only by agreement between both lanes.

/** Features describing the current WORKING stretch. ACP-derived fields will be added later as defaulted properties. */
internal data class EtaFeatures(
    val stretchElapsedS: Double,
    val sessionElapsedS: Double,
    val workWaitSwitches: Int,
    val sinceLastTransitionS: Double,
    val hourOfDay: Int,
)

/** Remaining time in the current WORKING stretch, in seconds. */
internal sealed interface Eta {
    data class Estimate(val p20: Double, val p50: Double, val p80: Double) : Eta
    data object Unknown : Eta
}

internal enum class EtaDecision { START_BREAK, SOFT_WARN, HOLD }

/** One fully observed WORKING stretch from the session log. */
internal data class Stretch(val startTs: Instant, val durationS: Double)

internal interface EtaPredictorApi {
    fun predict(f: EtaFeatures): Eta
}
