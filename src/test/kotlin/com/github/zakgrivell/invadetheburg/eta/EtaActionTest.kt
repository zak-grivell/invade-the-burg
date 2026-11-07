package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.Eta
import com.github.zakgrivell.invadetheburg.services.eta.EtaAction
import com.github.zakgrivell.invadetheburg.services.eta.EtaDecision
import com.github.zakgrivell.invadetheburg.services.eta.decide
import com.github.zakgrivell.invadetheburg.services.eta.etaAction
import com.github.zakgrivell.invadetheburg.services.eta.etaBadgeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.github.zakgrivell.invadetheburg.services.eta.EtaFeatures
import com.github.zakgrivell.invadetheburg.services.eta.EtaPredictor
import com.github.zakgrivell.invadetheburg.services.eta.SessionHistory
import java.nio.file.Path

class EtaActionTest {
    private fun estimate(p20: Double, p50: Double = p20 + 10) = Eta.Estimate(p20, p50, p50 + 10)
    private fun action(eta: Eta, showing: Boolean) = etaAction(decide(eta), showing, eta)

    @Test fun `unknown and HOLD behave exactly as today`() {
        listOf(false, true).forEach { showing ->
            assertEquals(EtaAction.SHOW_AS_TODAY, action(Eta.Unknown, showing))
            assertEquals(EtaAction.SHOW_AS_TODAY, action(estimate(30.0), showing))
            assertEquals(EtaAction.SHOW_AS_TODAY, action(estimate(60.0), showing))
            // Defensive: even a stray decision cannot act on an Unknown estimate.
            assertEquals(EtaAction.SHOW_AS_TODAY, etaAction(EtaDecision.SOFT_WARN, showing, Eta.Unknown))
        }
    }

    @Test fun `START_BREAK shows the break as today`() {
        assertEquals(EtaAction.SHOW_AS_TODAY, action(estimate(60.1), breakShowing(false)))
        assertEquals(EtaAction.SHOW_AS_TODAY, action(estimate(60.1), breakShowing(true)))
    }

    @Test fun `p20 under 30 skips the break when none is showing`() {
        assertEquals(EtaAction.SKIP_BREAK, action(estimate(29.9), breakShowing(false)))
        assertEquals(EtaAction.SKIP_BREAK, action(estimate(0.0), breakShowing(false)))
    }

    @Test fun `p20 under 30 while a break is showing gives the wrapping up hint`() {
        assertEquals(EtaAction.WRAPPING_UP_HINT, action(estimate(29.9), breakShowing(true)))
    }

    @Test fun `badge text rounds p50 to seconds or minutes`() {
        fun text(p50: Double) = etaBadgeText(estimate(0.0, p50), EtaAction.SHOW_AS_TODAY)
        assertEquals("~1 s left", text(0.2))
        assertEquals("~45 s left", text(45.0))
        assertEquals("~59 s left", text(59.4))
        assertEquals("~1 min left", text(59.6))
        assertEquals("~1 min left", text(60.0))
        assertEquals("~2 min left", text(100.0))
        assertEquals("~2 min left", text(135.0))
        assertEquals("~10 min left", text(600.0))
    }

    @Test fun `badge is hidden when unknown and says wrapping up on the hint`() {
        assertNull(etaBadgeText(Eta.Unknown, EtaAction.SHOW_AS_TODAY))
        assertNull(etaBadgeText(Eta.Unknown, EtaAction.NONE))
        assertEquals("Wrapping up…", etaBadgeText(estimate(10.0, 20.0), EtaAction.WRAPPING_UP_HINT))
    }

    private fun demoEta(seed: String, elapsedS: Double): Eta {
        val stretches = SessionHistory.loadSeed(Path.of(javaClass.getResource("/eta/$seed")!!.toURI()))
        return EtaPredictor(stretches.map { it.durationS }).predict(EtaFeatures(elapsedS, elapsedS, 0, elapsedS, 12))
    }

    @Test fun `demo-short seed skips a new stretch and the example seed does not`() {
        val short = demoEta("seed-history.demo-short.jsonl", 0.0)
        assertEquals(EtaAction.SKIP_BREAK, action(short, breakShowing(false)))
        assertEquals(EtaAction.SHOW_AS_TODAY, action(demoEta("seed-history.example.jsonl", 0.0), breakShowing(false)))
    }

    @Test fun `demo-short seed has fifteen stretches of 20 to 90 seconds`() {
        val path = Path.of(javaClass.getResource("/eta/seed-history.demo-short.jsonl")!!.toURI())
        val durations = SessionHistory.loadSeed(path).map { it.durationS }
        assertEquals(15, durations.size)
        assertEquals(20.0, durations.min(), 0.0)
        assertEquals(90.0, durations.max(), 0.0)
    }

    private fun breakShowing(value: Boolean) = value
}
