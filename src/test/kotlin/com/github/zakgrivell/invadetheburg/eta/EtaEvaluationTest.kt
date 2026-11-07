package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.Baseline
import com.github.zakgrivell.invadetheburg.services.eta.EvalMetrics
import com.github.zakgrivell.invadetheburg.services.eta.EvalPhase
import com.github.zakgrivell.invadetheburg.services.eta.Stretch
import com.github.zakgrivell.invadetheburg.services.eta.evaluate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEvaluationTest {
    private fun fixture(): List<Stretch> {
        val start = Instant.parse("2026-11-07T00:00:00Z")
        return listOf(30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 60.0, 90.0, 120.0)
            .mapIndexed { index, duration -> Stretch(start.plusSeconds(index * 60L), duration) }
    }

    private fun assertMetrics(
        row: EvalMetrics,
        n: Int,
        unknown: Int,
        maeS: Double?,
        coverage: Double?,
    ) {
        assertEquals(n, row.n)
        assertEquals(unknown, row.unknown)
        if (maeS == null) assertNull(row.maeS) else assertEquals(maeS, row.maeS!!, 1e-9)
        if (coverage == null) assertNull(row.coverage) else assertEquals(coverage, row.coverage!!, 1e-9)
    }

    @Test fun `ten stretch fixture has hand calculated errors and coverage`() {
        val report = evaluate(fixture(), minSamples = 5)
        fun row(baseline: Baseline, phase: EvalPhase) = report.metrics.single {
            it.baseline == baseline && it.phase == phase
        }

        // Train [30..90] has type-7 p20/p50/p80 = 42/60/78; at test D=90, middle true remaining is 45.
        // Unconditional p50 is 60-45=15 (error 30); conditional survivors [50..90] give p50=70-45=25 (error 20).
        assertMetrics(row(Baseline.UNCONDITIONAL, EvalPhase.OVERALL), 9, 0, 215.0 / 9.0, 3.0 / 9.0)
        assertMetrics(row(Baseline.UNCONDITIONAL, EvalPhase.EARLY), 3, 0, 30.0, 1.0 / 3.0)
        assertMetrics(row(Baseline.UNCONDITIONAL, EvalPhase.MIDDLE), 3, 0, 30.0, 1.0 / 3.0)
        assertMetrics(row(Baseline.UNCONDITIONAL, EvalPhase.LATE), 3, 0, 35.0 / 3.0, 1.0 / 3.0)
        assertMetrics(row(Baseline.CONDITIONAL, EvalPhase.OVERALL), 9, 3, 20.0, 3.0 / 6.0)
        assertMetrics(row(Baseline.CONDITIONAL, EvalPhase.EARLY), 3, 0, 30.0, 1.0 / 3.0)
        assertMetrics(row(Baseline.CONDITIONAL, EvalPhase.MIDDLE), 3, 1, 10.0, 1.0 / 2.0)
        assertMetrics(row(Baseline.CONDITIONAL, EvalPhase.LATE), 3, 2, 10.0, 1.0)
    }

    @Test fun `chronological train strictly precedes test even when input is reversed`() {
        val ordered = fixture()
        val report = evaluate(ordered.reversed(), minSamples = 5)
        assertEquals(ordered.take(7), report.train)
        assertEquals(ordered.drop(7), report.test)
        assertTrue(report.train.last().startTs < report.test.first().startTs)
    }

    @Test fun `markdown table matches golden output`() {
        val expected = """
            # ETA evaluation

            Train: 7 stretches; test: 3 stretches.

            | Baseline | Phase | n | Unknown | MAE (s) | P20–P80 coverage |
            | --- | --- | ---: | ---: | ---: | ---: |
            | unconditional | overall | 9 | 0 | 23.89 | 33.33% |
            | unconditional | early | 3 | 0 | 30.00 | 33.33% |
            | unconditional | middle | 3 | 0 | 30.00 | 33.33% |
            | unconditional | late | 3 | 0 | 11.67 | 33.33% |
            | conditional | overall | 9 | 3 | 20.00 | 50.00% |
            | conditional | early | 3 | 0 | 30.00 | 33.33% |
            | conditional | middle | 3 | 1 | 10.00 | 50.00% |
            | conditional | late | 3 | 2 | 10.00 | 100.00% |
        """.trimIndent()
        assertEquals(expected, evaluate(fixture(), minSamples = 5).toMarkdown())
    }

    @Test fun `no estimates leaves metrics undefined`() {
        val report = evaluate(fixture(), minSamples = 8)
        assertMetrics(report.metrics.first(), 9, 9, null, null)
        assertTrue(report.toMarkdown().contains("| unconditional | overall | 9 | 9 | — | — |"))
    }
}
