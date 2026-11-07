package com.github.zakgrivell.invadetheburg.services.eta

internal enum class Baseline { UNCONDITIONAL, CONDITIONAL }

internal class EtaPredictor(
    stretchHistoryS: List<Double>,
    private val baseline: Baseline = Baseline.CONDITIONAL,
    private val minSamples: Int = 5,
) : EtaPredictorApi {
    private val history = stretchHistoryS.sorted()

    override fun predict(f: EtaFeatures): Eta {
        if (history.size < minSamples || history.isEmpty()) return Eta.Unknown

        val elapsed = f.stretchElapsedS
        val samples = when (baseline) {
            Baseline.UNCONDITIONAL -> history
            Baseline.CONDITIONAL -> history.filter { it >= elapsed }
        }
        if (samples.size < minSamples || samples.isEmpty()) return Eta.Unknown

        return Eta.Estimate(
            (percentileType7(samples, 0.2) - elapsed).coerceAtLeast(0.0),
            (percentileType7(samples, 0.5) - elapsed).coerceAtLeast(0.0),
            (percentileType7(samples, 0.8) - elapsed).coerceAtLeast(0.0),
        )
    }
}

/** R type 7: interpolate between sorted observations at zero-based position (n - 1) * p. */
internal fun percentileType7(values: List<Double>, p: Double): Double {
    require(values.isNotEmpty())
    require(p in 0.0..1.0)
    val sorted = values.sorted()
    val position = (sorted.size - 1) * p
    val lower = position.toInt()
    val fraction = position - lower
    return sorted[lower] + (sorted[minOf(lower + 1, sorted.lastIndex)] - sorted[lower]) * fraction
}
