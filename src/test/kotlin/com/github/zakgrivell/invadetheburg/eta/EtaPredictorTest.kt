package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.Baseline
import com.github.zakgrivell.invadetheburg.services.eta.Eta
import com.github.zakgrivell.invadetheburg.services.eta.EtaFeatures
import com.github.zakgrivell.invadetheburg.services.eta.EtaPredictor
import com.github.zakgrivell.invadetheburg.services.eta.percentileType7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaPredictorTest {
    private val history = listOf(10.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0, 300.0, 600.0)

    private fun features(elapsed: Double) = EtaFeatures(elapsed, 999.0, 42, 777.0, 23)

    private fun assertEstimate(expected: Eta.Estimate, actual: Eta, eps: Double = 1e-9) {
        assertTrue("Expected Eta.Estimate but was $actual", actual is Eta.Estimate)
        val estimate = actual as Eta.Estimate
        assertEquals(expected.p20, estimate.p20, eps)
        assertEquals(expected.p50, estimate.p50, eps)
        assertEquals(expected.p80, estimate.p80, eps)
    }

    @Test fun `unconditional estimates subtract elapsed and clamp at zero`() {
        val predictor = EtaPredictor(history, Baseline.UNCONDITIONAL)
        assertEstimate(Eta.Estimate(28.0, 75.0, 204.0), predictor.predict(features(0.0)))
        assertEstimate(Eta.Estimate(0.0, 25.0, 154.0), predictor.predict(features(50.0)))
        assertEstimate(Eta.Estimate(0.0, 0.0, 4.0), predictor.predict(features(200.0)))
    }

    @Test fun `conditional estimates use only stretches surviving elapsed time`() {
        val predictor = EtaPredictor(history)
        assertEstimate(Eta.Estimate(28.0, 75.0, 204.0), predictor.predict(features(0.0)))
        // At 50s, remaining samples are [10, 40, 70, 130, 250, 550]; p50 is (70 + 130) / 2 = 100.
        assertEstimate(Eta.Estimate(40.0, 100.0, 250.0), predictor.predict(features(50.0)))
        assertEquals(Eta.Unknown, predictor.predict(features(200.0)))
    }

    @Test fun `too little history or too few survivors is unknown`() {
        assertEquals(Eta.Unknown, EtaPredictor(history.take(4)).predict(features(0.0)))
        assertEquals(Eta.Unknown, EtaPredictor(history).predict(features(91.0)))
        assertEquals(Eta.Unknown, EtaPredictor(emptyList()).predict(features(0.0)))
    }

    @Test fun `percentile helper interpolates unsorted values and handles endpoints`() {
        val values = listOf(30.0, 10.0, 20.0, 40.0)
        assertEquals(10.0, percentileType7(values, 0.0), 0.0)
        assertEquals(17.5, percentileType7(values, 0.25), 0.0)
        assertEquals(25.0, percentileType7(values, 0.5), 0.0)
        assertEquals(40.0, percentileType7(values, 1.0), 0.0)
        assertEquals(7.0, percentileType7(listOf(7.0), 0.8), 0.0)
    }
}
