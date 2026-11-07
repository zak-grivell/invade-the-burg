package com.github.zakgrivell.invadetheburg.services.eta

internal enum class Baseline { UNCONDITIONAL, CONDITIONAL }

/**
 * Empirical remaining-time predictor over past WORKING stretch durations.
 * Uses only [EtaFeatures.stretchElapsedS].
 */
internal class EtaPredictor(
    stretchHistoryS: List<Double>,
    private val baseline: Baseline = Baseline.CONDITIONAL,
    private val minSamples: Int = 5,
) : EtaPredictorApi {
    private val history = stretchHistoryS.sorted()

    override fun predict(f: EtaFeatures): Eta {
        if (history.size < minSamples) return Eta.Unknown
        val elapsed = f.stretchElapsedS
        val remaining = when (baseline) {
            Baseline.UNCONDITIONAL -> history.map { (it - elapsed).coerceAtLeast(0.0) }
            Baseline.CONDITIONAL -> {
                val survivors = history.filter { it >= elapsed }
                if (survivors.size < minSamples) return Eta.Unknown
                survivors.map { it - elapsed }
            }
        }
        // `remaining` stays sorted: subtracting a constant and clamping at 0 are monotonic.
        return Eta.Estimate(
            p20 = percentile(remaining, 0.20),
            p50 = percentile(remaining, 0.50),
            p80 = percentile(remaining, 0.80),
        )
    }
}

/** Percentile by linear interpolation (R type 7). [sorted] must be ascending and non-empty; [p] in 0..1. */
internal fun percentile(sorted: List<Double>, p: Double): Double {
    require(sorted.isNotEmpty()) { "percentile of empty list" }
    val h = (sorted.size - 1) * p
    val lo = kotlin.math.floor(h).toInt()
    val hi = kotlin.math.ceil(h).toInt()
    return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
}
