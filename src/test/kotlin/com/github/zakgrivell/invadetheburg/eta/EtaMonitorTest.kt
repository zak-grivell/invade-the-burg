package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity
import com.github.zakgrivell.invadetheburg.services.eta.Eta
import com.github.zakgrivell.invadetheburg.services.eta.EtaAction
import com.github.zakgrivell.invadetheburg.services.eta.EtaFeatures
import com.github.zakgrivell.invadetheburg.services.eta.EtaMonitor
import com.github.zakgrivell.invadetheburg.services.eta.EtaPredictorApi
import com.github.zakgrivell.invadetheburg.services.eta.Stretch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor

class EtaMonitorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val lines = mutableListOf<String>()
    private val infos = mutableListOf<String>()
    private val debugs = mutableListOf<String>()
    private val histories = mutableListOf<List<Double>>()
    private val t0 = Instant.parse("2026-11-07T09:00:00Z").toEpochMilli()

    private fun pane(a: String) = PaneActivity("p", a == "PROCESSING", a)

    private fun monitor(predictor: (List<Double>) -> EtaPredictorApi = { h ->
        histories += h
        object : EtaPredictorApi { override fun predict(f: EtaFeatures) = Eta.Unknown }
    }) = EtaMonitor(
        sink = { lines += it },
        predictorFactory = predictor,
        zone = ZoneOffset.UTC,
        info = { infos += it },
        debug = { debugs += it },
    )

    private fun fixed(p50: Double) = { _: List<Double> ->
        object : EtaPredictorApi { override fun predict(f: EtaFeatures) = Eta.Estimate(p50 / 2, p50, p50 * 2) }
    }

    private fun EtaMonitor.runOneStretch(seconds: Long) {
        observe(pane("READY"), t0)
        observe(pane("PROCESSING"), t0 + 1_000)
        observe(pane("READY"), t0 + 1_000 + seconds * 1_000)
    }

    @Test fun `stretch close logs predicted p50 next to the actual duration`() {
        monitor(fixed(42.0)).runOneStretch(30)
        assertEquals(listOf("Reel Break: ETA stretch closed: predicted_p50=42.0s actual=30.0s"), infos)
    }

    @Test fun `unknown prediction is logged as unknown`() {
        monitor().runOneStretch(30)
        assertEquals(listOf("Reel Break: ETA stretch closed: predicted_p50=unknown actual=30.0s"), infos)
    }

    @Test fun `prediction uses features from the moment the stretch starts`() {
        val seen = mutableListOf<EtaFeatures>()
        val m = monitor { _ -> object : EtaPredictorApi {
            override fun predict(f: EtaFeatures): Eta { seen += f; return Eta.Unknown }
        } }
        m.observe(pane("READY"), t0)
        m.observe(pane("PROCESSING"), t0 + 1_000)
        assertEquals(0.0, seen.first().stretchElapsedS, 0.0)
        assertEquals(9, seen.first().hourOfDay)
    }

    @Test fun `session log lines are written for transitions and the session end`() {
        monitor().runOneStretch(30)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"from_state\":\"DONE\",\"to_state\":\"WORKING\""))
        assertTrue(lines[2].contains("\"outcome\":\"done\""))
    }

    @Test fun `close abandons the open session`() {
        val m = monitor()
        m.observe(pane("READY"), t0)
        m.observe(pane("PROCESSING"), t0 + 1_000)
        m.close(t0 + 5_000)
        assertTrue(lines.last().contains("\"outcome\":\"abandoned\""))
        assertTrue(infos.isEmpty())
    }

    @Test fun `advisory debug log is throttled to once per interval while working`() {
        val m = monitor()
        m.observe(pane("READY"), t0)
        assertTrue(debugs.isEmpty())
        m.observe(pane("PROCESSING"), t0 + 1_000)
        m.observe(pane("PROCESSING"), t0 + 1_200)
        m.observe(pane("PROCESSING"), t0 + 10_999)
        assertEquals(1, debugs.size)
        m.observe(pane("PROCESSING"), t0 + 11_000)
        assertEquals(2, debugs.size)
        assertTrue(debugs[1].contains("stretchElapsed=10.0s"))
    }

    @Test fun `a throwing predictor never breaks logging`() {
        val m = monitor { _ -> object : EtaPredictorApi { override fun predict(f: EtaFeatures): Eta = error("boom") } }
        m.runOneStretch(30)
        assertEquals(3, lines.size)
        assertEquals(listOf("Reel Break: ETA stretch closed: predicted_p50=unknown actual=30.0s"), infos)
    }

    @Test fun `background load logs paths, feeds history and live stretches extend it`() {
        val dir = tmp.newFolder("sessions").toPath()
        val seed = Path.of(javaClass.getResource("/eta/seed-history.example.jsonl")!!.toURI())
        val direct = Executor { it.run() }
        val m = monitor()
        m.loadHistoryInBackground(direct, dir, seed)

        assertEquals("Reel Break: ETA history: logDir=$dir, seed=$seed (found)", infos[0])
        assertEquals("Reel Break: ETA history loaded: 10 stretches", infos[1])
        assertEquals(listOf(20.0, 45.0, 60.0, 90.0, 120.0, 150.0, 240.0, 300.0, 420.0, 600.0), histories.last())

        m.runOneStretch(30)
        assertEquals(11, histories.last().size)
        assertEquals(30.0, histories.last().last(), 0.0)  // newest stretch sorts last
    }

    @Test fun `a stretch closed before the load finished is not counted twice`() {
        val m = monitor()
        m.runOneStretch(30)
        val closed = Stretch(Instant.ofEpochMilli(t0 + 1_000), 30.0)
        m.onHistoryLoaded(listOf(Stretch(Instant.parse("2026-11-01T10:00:00Z"), 60.0), closed))
        assertEquals(listOf(60.0, 30.0), histories.last())
    }

    @Test fun `missing history paths load nothing`() {
        val m = monitor()
        val none = tmp.root.toPath().resolve("missing")
        m.loadHistoryInBackground(Executor { it.run() }, none, none.resolve("seed.jsonl"))
        assertTrue(infos[0].endsWith("(not found)"))
        assertEquals("Reel Break: ETA history loaded: 0 stretches", infos[1])
        assertTrue(Files.notExists(none))
    }

    @Test fun `predictor rebuild runs on the executor, not inside observe, and stale builds never win`() {
        val queue = ArrayDeque<Runnable>()
        val m = EtaMonitor(
            sink = { lines += it },
            rebuildExecutor = { queue.addLast(it) },
            predictorFactory = fixedBySize(),
            zone = ZoneOffset.UTC,
            info = { infos += it },
            debug = { debugs += it },
        )
        fun stretchAt(offsetS: Long) {
            val t = t0 + offsetS * 1_000
            m.observe(pane("READY"), t)
            m.observe(pane("PROCESSING"), t + 1_000)
            m.observe(pane("READY"), t + 11_000)
        }

        stretchAt(0)
        stretchAt(100)
        assertEquals(2, queue.size)           // nothing was built inside observe()
        val first = queue.removeFirst()
        val second = queue.removeFirst()
        second.run()                          // newest snapshot (2 stretches) installs first...
        first.run()                           // ...and the older one (also built from 2 here) must not regress it
        stretchAt(200)
        assertEquals("Reel Break: ETA stretch closed: predicted_p50=2.0s actual=10.0s", infos.last())
    }

    private fun fixedBySize() = { h: List<Double> ->
        object : EtaPredictorApi { override fun predict(f: EtaFeatures) = Eta.Estimate(0.0, h.size.toDouble(), 0.0) }
    }

    // ---- ETA-driven behaviour (T9) ----

    private fun monitorWith(fn: (EtaFeatures) -> Eta) = monitor { _ ->
        object : EtaPredictorApi { override fun predict(f: EtaFeatures) = fn(f) }
    }

    private fun EtaMonitor.startStretch() {
        observe(pane("READY"), t0)
        observe(pane("PROCESSING"), t0 + 1_000)
    }

    @Test fun `p20 under 30 at stretch start skips the break and forceBreak lifts it`() {
        val m = monitorWith { Eta.Estimate(10.0, 20.0, 40.0) }
        m.startStretch()
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 1_000, breakShowing = false).action)
        assertNull(m.evaluate(t0 + 1_000, false).badge)
        m.forceBreak()
        val view = m.evaluate(t0 + 1_000, breakShowing = true)
        assertEquals(EtaAction.WRAPPING_UP_HINT, view.action)
        assertEquals("Wrapping up…", view.badge)
    }

    @Test fun `long estimate shows the break with a minutes badge`() {
        val m = monitorWith { Eta.Estimate(100.0, 100.0, 200.0) }
        m.startStretch()
        val view = m.evaluate(t0 + 1_000, breakShowing = false)
        assertEquals(EtaAction.SHOW_AS_TODAY, view.action)
        assertEquals("~2 min left", view.badge)
    }

    @Test fun `unknown estimate behaves as today with no badge`() {
        val m = monitorWith { Eta.Unknown }
        m.startStretch()
        assertEquals(com.github.zakgrivell.invadetheburg.services.eta.EtaView(EtaAction.SHOW_AS_TODAY, null), m.evaluate(t0 + 1_000, false))
    }

    @Test fun `no WORKING stretch means no ETA input`() {
        val m = monitorWith { Eta.Estimate(1.0, 1.0, 1.0) }
        assertEquals(EtaAction.NONE, m.evaluate(t0, false).action)
        m.observe(pane("READY"), t0)
        assertEquals(EtaAction.NONE, m.evaluate(t0, false).action)
    }

    @Test fun `a stretch whose start was not observed is never skipped or hinted`() {
        val m = monitorWith { Eta.Estimate(1.0, 2.0, 3.0) }
        m.observe(pane("PROCESSING"), t0)  // first sighting mid-WORKING: elapsed 0 would be a lie
        val view = m.evaluate(t0, breakShowing = true)
        assertEquals(EtaAction.SHOW_AS_TODAY, view.action)
        assertNull(view.badge)
    }

    @Test fun `mid-stretch only a hint can appear, never a skip`() {
        val m = monitorWith { Eta.Estimate(100.0 - it.stretchElapsedS, 120.0 - it.stretchElapsedS, 150.0) }
        m.startStretch()
        assertEquals(EtaAction.SHOW_AS_TODAY, m.evaluate(t0 + 1_000, false).action)
        val late = t0 + 81_000  // elapsed 80 s: p20 = 20
        assertEquals(EtaAction.WRAPPING_UP_HINT, m.evaluate(late, breakShowing = true).action)
        assertEquals(EtaAction.SHOW_AS_TODAY, m.evaluate(late + 2_000, breakShowing = false).action)
    }

    @Test fun `a new stretch after a permission wait is decided afresh`() {
        var p20 = 10.0
        val m = monitorWith { Eta.Estimate(p20, p20 + 5, p20 + 10) }
        m.startStretch()
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 1_000, false).action)
        m.observe(pane("NEEDS_INPUT"), t0 + 5_000)
        p20 = 100.0
        m.observe(pane("PROCESSING"), t0 + 9_000)
        assertEquals(EtaAction.SHOW_AS_TODAY, m.evaluate(t0 + 9_000, false).action)
    }

    @Test fun `the view is recomputed at most once per second`() {
        var calls = 0
        val m = monitorWith { calls++; Eta.Estimate(100.0, 100.0, 200.0) }
        m.startStretch()
        m.evaluate(t0 + 2_000, false)
        val after = calls
        m.evaluate(t0 + 2_300, false)
        m.evaluate(t0 + 2_900, false)
        assertEquals(after, calls)
        m.evaluate(t0 + 3_000, false)
        assertEquals(after + 1, calls)
    }

    @Test fun `a throwing predictor yields today's behaviour`() {
        val m = monitorWith { error("boom") }
        m.startStretch()
        val view = m.evaluate(t0 + 1_000, false)
        assertEquals(EtaAction.SHOW_AS_TODAY, view.action)
        assertNull(view.badge)
    }

    // ---- Provisional skip ----

    private fun reopened() = infos.filter { it.contains("reopened") }

    @Test fun `a skipped stretch reopens the break when the conditional ETA rises`() {
        var p20 = 10.0
        val m = monitorWith { f -> Eta.Estimate(if (f.stretchElapsedS < 10) 10.0 else p20, 60.0, 90.0) }
        p20 = 100.0
        m.startStretch()                                   // stretch starts at t0 + 1 s
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 1_000, false).action)
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 6_000, false).action)   // elapsed 5 s
        assertTrue(reopened().isEmpty())

        val view = m.evaluate(t0 + 11_000, breakShowing = false)                    // elapsed 10 s
        assertEquals(EtaAction.SHOW_AS_TODAY, view.action)
        assertEquals("~1 min left", view.badge)
        assertEquals(listOf("Reel Break: ETA reopened break after 10s (stretch longer than usual)"), reopened())

        p20 = 5.0   // a reopened break is never skipped again, and the log line is not repeated
        assertEquals(EtaAction.SHOW_AS_TODAY, m.evaluate(t0 + 13_000, breakShowing = false).action)
        assertEquals(EtaAction.WRAPPING_UP_HINT, m.evaluate(t0 + 15_000, breakShowing = true).action)
        assertEquals(1, reopened().size)
    }

    @Test fun `a skipped stretch reopens when the estimate becomes unknown`() {
        val m = monitorWith { f -> if (f.stretchElapsedS < 20) Eta.Estimate(10.0, 15.0, 30.0) else Eta.Unknown }
        m.startStretch()
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 1_000, false).action)
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 15_000, false).action)
        val view = m.evaluate(t0 + 21_000, false)                                   // elapsed 20 s
        assertEquals(EtaAction.SHOW_AS_TODAY, view.action)
        assertEquals("Longer than usual", view.badge)
        assertEquals(listOf("Reel Break: ETA reopened break after 20s (stretch longer than usual)"), reopened())
    }

    @Test fun `a short skipped stretch stays skipped until it ends`() {
        val m = monitorWith { Eta.Estimate(10.0, 15.0, 30.0) }
        m.startStretch()
        (1..8).forEach { assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 1_000L + it * 1_000L, false).action) }
        m.observe(pane("READY"), t0 + 10_000)
        assertEquals(EtaAction.NONE, m.evaluate(t0 + 10_000, false).action)
        assertTrue(reopened().isEmpty())
    }

    @Test fun `forceBreak on a skipped stretch opens it without the late-reopen log`() {
        val m = monitorWith { Eta.Estimate(10.0, 15.0, 30.0) }
        m.startStretch()
        m.forceBreak()
        m.evaluate(t0 + 5_000, true)
        assertTrue(reopened().isEmpty())
    }

    @Test fun `skipped stretches are re-evaluated at most once per second`() {
        var calls = 0
        val m = monitorWith { calls++; Eta.Estimate(10.0, 15.0, 30.0) }
        m.startStretch()
        m.evaluate(t0 + 2_000, false)
        val after = calls
        m.evaluate(t0 + 2_400, false)
        m.evaluate(t0 + 2_900, false)
        assertEquals(after, calls)
        m.evaluate(t0 + 3_000, false)
        assertEquals(after + 1, calls)
    }

    // ---- Badge logging and "Longer than usual" ----

    private fun badgeLogs() = infos.filter { it.startsWith("Reel Break: ETA badge:") }

    @Test fun `badge changes are logged once each, including why it is hidden`() {
        var eta: Eta = Eta.Unknown
        val m = monitorWith { eta }
        m.evaluate(t0, false)                                    // not working
        m.startStretch()
        m.evaluate(t0 + 1_000, false)
        m.evaluate(t0 + 3_000, false)                            // unchanged: no new line
        eta = Eta.Estimate(100.0, 100.0, 200.0)
        m.evaluate(t0 + 5_000, false)
        m.evaluate(t0 + 7_000, false)                            // unchanged
        assertEquals(
            listOf(
                "Reel Break: ETA badge: hidden: not in a working stretch",
                "Reel Break: ETA badge: hidden: Unknown",
                "Reel Break: ETA badge: \"~2 min left\"",
            ),
            badgeLogs(),
        )
    }

    @Test fun `a skipped break and an untrusted stretch explain themselves in the log`() {
        val skipped = monitorWith { Eta.Estimate(10.0, 15.0, 30.0) }
        skipped.startStretch()
        skipped.evaluate(t0 + 1_000, false)
        assertEquals("Reel Break: ETA badge: hidden: break skipped", badgeLogs().last())

        infos.clear()
        val untrusted = monitorWith { Eta.Estimate(10.0, 15.0, 30.0) }
        untrusted.observe(pane("PROCESSING"), t0)
        untrusted.evaluate(t0, true)
        assertEquals(listOf("Reel Break: ETA badge: hidden: stretch start not observed"), badgeLogs())
    }

    @Test fun `each new stretch logs its first badge again`() {
        val m = monitorWith { Eta.Unknown }
        m.startStretch()
        m.evaluate(t0 + 1_000, false)
        m.observe(pane("READY"), t0 + 5_000)
        m.observe(pane("PROCESSING"), t0 + 6_000)
        m.evaluate(t0 + 6_000, false)
        assertEquals(2, badgeLogs().count { it == "Reel Break: ETA badge: hidden: Unknown" })
    }

    @Test fun `longer than usual is shown after an unknown reopen until an estimate returns`() {
        var eta: Eta = Eta.Estimate(10.0, 15.0, 30.0)
        val m = monitorWith { eta }
        m.startStretch()
        assertEquals(EtaAction.SKIP_BREAK, m.evaluate(t0 + 1_000, false).action)
        eta = Eta.Unknown
        assertEquals("Longer than usual", m.evaluate(t0 + 3_000, false).badge)
        assertEquals("Longer than usual", m.evaluate(t0 + 9_000, true).badge)
        assertEquals("Reel Break: ETA badge: \"Longer than usual\"", badgeLogs().last())
        eta = Eta.Estimate(100.0, 100.0, 200.0)
        assertEquals("~2 min left", m.evaluate(t0 + 11_000, true).badge)
    }

    @Test fun `no longer than usual label when the reopen came from a rising estimate or no skip happened`() {
        var eta: Eta = Eta.Estimate(10.0, 15.0, 30.0)
        val rising = monitorWith { eta }
        rising.startStretch()
        eta = Eta.Estimate(100.0, 100.0, 200.0)
        assertEquals("~2 min left", rising.evaluate(t0 + 3_000, false).badge)
        eta = Eta.Unknown
        assertNull(rising.evaluate(t0 + 5_000, true).badge)

        val plain = monitorWith { Eta.Unknown }
        plain.startStretch()
        assertNull(plain.evaluate(t0 + 3_000, false).badge)
    }
}
