package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.Eta
import com.github.zakgrivell.invadetheburg.services.eta.EtaDecision
import com.github.zakgrivell.invadetheburg.services.eta.decide
import org.junit.Assert.assertEquals
import org.junit.Test

class EtaDecisionFnTest {
    private fun estimate(p20: Double) = Eta.Estimate(p20, 100.0, 200.0)

    @Test fun `soft warn below 30`() {
        assertEquals(EtaDecision.SOFT_WARN, decide(estimate(29.9)))
    }

    @Test fun `hold at 30 and 60`() {
        assertEquals(EtaDecision.HOLD, decide(estimate(30.0)))
        assertEquals(EtaDecision.HOLD, decide(estimate(60.0)))
    }

    @Test fun `start break above 60`() {
        assertEquals(EtaDecision.START_BREAK, decide(estimate(60.1)))
    }

    @Test fun `hold when eta is unknown`() {
        assertEquals(EtaDecision.HOLD, decide(Eta.Unknown))
    }
}
