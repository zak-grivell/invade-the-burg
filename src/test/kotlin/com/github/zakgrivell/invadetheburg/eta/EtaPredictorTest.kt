package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.Baseline
import com.github.zakgrivell.invadetheburg.services.eta.Eta
import com.github.zakgrivell.invadetheburg.services.eta.EtaFeatures
import com.github.zakgrivell.invadetheburg.services.eta.EtaPredictor
import com.github.zakgrivell.invadetheburg.services.eta.percentile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EtaPredictorTest {
    private val history = listOf(10.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0, 300.0, 600.0)

    private fun features(elapsed: Double) = EtaFeatures(elapsed, 0.0, 0, 0.0, 12)

    private fun predict(baseline: Baseline, elapsed: Double, hist: List<Double> = history) =
        EtaPredictor(hist, baseline).predict(features(elapsed))

    private fun assertEstimate(p20: Double, p50: Double, p80: Double, actual: Eta) {
        val e = actual as Eta.Estimate
        assertEquals(p20, e.p20, 1e-9)
        assertEquals(p50, e.p50, 1e-9)
        assertEquals(p80, e.p80, 1e-9)
    }

    // Hand calculation, UNCONDITIONAL, elapsed 0 (n = 10, h = (n-1)*p, R type 7, 0-indexed):
    //   p20: h = 9*0.2 = 1.8 -> x[1] + 0.8*(x[2]-x[1]) = 20 + 0.8*10  = 28
    //   p50: h = 9*0.5 = 4.5 -> x[4] + 0.5*(x[5]-x[4]) = 60 + 0.5*30  = 75
    //   p80: h = 9*0.8 = 7.2 -> x[7] + 0.2*(x[8]-x[7]) = 180 + 0.2*120 = 204
    @Test fun `unconditional at elapsed 0`() =
        assertEstimate(28.0, 75.0, 204.0, predict(Baseline.UNCONDITIONAL, 0.0))

    // history - 50 = [-40,-30,-20,-5,10,40,70,130,250,550] clamped -> [0,0,0,0,10,40,70,130,250,550]
    //   p20: h=1.8 -> 0; p50: h=4.5 -> 10 + 0.5*30 = 25; p80: h=7.2 -> 130 + 0.2*120 = 154
    @Test fun `unconditional at elapsed 50`() =
        assertEstimate(0.0, 25.0, 154.0, predict(Baseline.UNCONDITIONAL, 50.0))

    // history - 200 clamped -> [0,0,0,0,0,0,0,0,100,400]; p80: h=7.2 -> 0 + 0.2*100 = 20
    @Test fun `unconditional at elapsed 200`() =
        assertEstimate(0.0, 0.0, 20.0, predict(Baseline.UNCONDITIONAL, 200.0))

    @Test fun `conditional at elapsed 0 keeps all survivors`() =
        assertEstimate(28.0, 75.0, 204.0, predict(Baseline.CONDITIONAL, 0.0))

    // survivors >= 50: [60,90,120,180,300,600] -> minus 50: [10,40,70,130,250,550] (n = 6)
    //   p20: h=1.0 -> 40; p50: h=2.5 -> 70 + 0.5*60 = 100; p80: h=4.0 -> 250
    @Test fun `conditional at elapsed 50`() =
        assertEstimate(40.0, 100.0, 250.0, predict(Baseline.CONDITIONAL, 50.0))

    // survivors >= 200: [300,600] -> only 2 < minSamples
    @Test fun `conditional at elapsed 200 has too few survivors`() =
        assertEquals(Eta.Unknown, predict(Baseline.CONDITIONAL, 200.0))

    @Test fun `conditional survivor on the boundary counts`() {
        // survivors >= 60: [60,90,120,180,300,600] -> minus 60: [0,30,60,120,240,540]; p50: h=2.5 -> 60+0.5*60 = 90
        assertEstimate(30.0, 90.0, 240.0, predict(Baseline.CONDITIONAL, 60.0))
    }

    @Test fun `conditional is the default baseline`() =
        assertEquals(Eta.Unknown, EtaPredictor(history).predict(features(200.0)))

    @Test fun `unknown when history shorter than minSamples`() {
        val short = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(Eta.Unknown, predict(Baseline.UNCONDITIONAL, 0.0, short))
        assertEquals(Eta.Unknown, predict(Baseline.CONDITIONAL, 0.0, short))
    }

    @Test fun `minSamples is configurable`() {
        val e = EtaPredictor(listOf(10.0, 20.0, 30.0), Baseline.UNCONDITIONAL, minSamples = 3).predict(features(0.0))
        assertEstimate(14.0, 20.0, 26.0, e)
    }

    @Test fun `empty history is unknown`() {
        assertEquals(Eta.Unknown, predict(Baseline.UNCONDITIONAL, 0.0, emptyList()))
        assertEquals(Eta.Unknown, predict(Baseline.CONDITIONAL, 0.0, emptyList()))
    }

    @Test fun `unsorted history is handled`() =
        assertEstimate(28.0, 75.0, 204.0, predict(Baseline.UNCONDITIONAL, 0.0, history.reversed()))

    @Test fun `unconditional never negative when elapsed exceeds all history`() =
        assertEstimate(0.0, 0.0, 0.0, predict(Baseline.UNCONDITIONAL, 10_000.0))

    @Test fun `percentile helper interpolates`() {
        val xs = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(10.0, percentile(xs, 0.0), 1e-9)
        assertEquals(25.0, percentile(xs, 0.5), 1e-9)
        assertEquals(19.0, percentile(xs, 0.3), 1e-9) // h = 0.9 -> 10 + 0.9*10... = 19
        assertEquals(40.0, percentile(xs, 1.0), 1e-9)
        assertEquals(7.0, percentile(listOf(7.0), 0.8), 1e-9)
    }

    @Test fun `percentile of empty list throws`() {
        assertThrows(IllegalArgumentException::class.java) { percentile(emptyList(), 0.5) }
    }
}
