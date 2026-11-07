package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity
import com.github.zakgrivell.invadetheburg.services.eta.Eta
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
}
