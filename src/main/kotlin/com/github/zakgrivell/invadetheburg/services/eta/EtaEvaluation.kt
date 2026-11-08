package com.github.zakgrivell.invadetheburg.services.eta

import java.util.Locale
import kotlin.math.abs

internal enum class EvalPhase { OVERALL, EARLY, MIDDLE, LATE }

internal data class EvalMetrics(
    val baseline: Baseline,
    val phase: EvalPhase,
    val n: Int,
    val unknown: Int,
    val maeS: Double?,
    val coverage: Double?,
)

internal data class EvalReport(
    val train: List<Stretch>,
    val test: List<Stretch>,
    val metrics: List<EvalMetrics>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("# ETA evaluation")
        appendLine()
        appendLine("Train: ${train.size} stretches; test: ${test.size} stretches.")
        appendLine()
        appendLine("| Baseline | Phase | n | Unknown | MAE (s) | P20–P80 coverage |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: |")
        for (row in metrics) {
            val mae = row.maeS?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
            val coverage = row.coverage?.let { String.format(Locale.US, "%.2f%%", it * 100.0) } ?: "—"
            appendLine("| ${row.baseline.name.lowercase(Locale.ROOT)} | ${row.phase.name.lowercase(Locale.ROOT)} | ${row.n} | ${row.unknown} | $mae | $coverage |")
        }
    }.trimEnd()
}

/** Evaluate only future stretches against a predictor trained on the first 70% chronologically. */
internal fun evaluate(stretches: List<Stretch>, minSamples: Int): EvalReport {
    val ordered = stretches.sortedBy { it.startTs }
    val split = (ordered.size.toLong() * 7 / 10).toInt()
    val train = ordered.take(split)
    val test = ordered.drop(split)

    val metrics = Baseline.entries.flatMap { baseline ->
        val predictor = EtaPredictor(train.map { it.durationS }, baseline, minSamples)
        val observations = test.flatMap { stretch ->
            listOf(
                EvalPhase.EARLY to stretch.durationS / 6.0,
                EvalPhase.MIDDLE to stretch.durationS / 2.0,
                EvalPhase.LATE to stretch.durationS * 5.0 / 6.0,
            ).map { (phase, elapsed) ->
                Observation(
                    phase,
                    predictor.predict(EtaFeatures(elapsed, 0.0, 0, 0.0, 0)),
                    stretch.durationS - elapsed,
                )
            }
        }
        EvalPhase.entries.map { phase ->
            val selected = if (phase == EvalPhase.OVERALL) observations else observations.filter { it.phase == phase }
            val estimates = selected.filter { it.eta is Eta.Estimate }
            EvalMetrics(
                baseline = baseline,
                phase = phase,
                n = selected.size,
                unknown = selected.size - estimates.size,
                maeS = estimates.takeIf { it.isNotEmpty() }?.map { abs((it.eta as Eta.Estimate).p50 - it.trueRemainingS) }?.average(),
                coverage = estimates.takeIf { it.isNotEmpty() }?.count {
                    val eta = it.eta as Eta.Estimate
                    it.trueRemainingS in eta.p20..eta.p80
                }?.toDouble()?.div(estimates.size),
            )
        }
    }

    return EvalReport(train, test, metrics)
}

private data class Observation(val phase: EvalPhase, val eta: Eta, val trueRemainingS: Double)
