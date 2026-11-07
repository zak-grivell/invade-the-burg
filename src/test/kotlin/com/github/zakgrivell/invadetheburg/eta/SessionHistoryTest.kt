package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.AgentState
import com.github.zakgrivell.invadetheburg.services.eta.AgentStateTracker
import com.github.zakgrivell.invadetheburg.services.eta.LiveStretches
import com.github.zakgrivell.invadetheburg.services.PaneActivity
import com.github.zakgrivell.invadetheburg.services.eta.SessionHistory
import com.github.zakgrivell.invadetheburg.services.eta.SessionLogEncoder
import com.github.zakgrivell.invadetheburg.services.eta.SessionLogWriter
import com.github.zakgrivell.invadetheburg.services.eta.Stretch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SessionHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): Path = Path.of(javaClass.getResource("/eta/$name")!!.toURI())
    private fun stretch(ts: String, s: Double) = Stretch(Instant.parse(ts), s)

    private val fixtureStretches = listOf(
        stretch("2026-11-07T12:00:00Z", 10.0),  // a: DONE -> WORKING -> NEEDS_PERMISSION
        stretch("2026-11-07T12:00:30Z", 30.0),  // a: NEEDS_PERMISSION -> WORKING -> DONE
        // d: WORKING -> UNKNOWN and the UNKNOWN -> WORKING -> DONE that follows are both dropped
        stretch("2026-11-07T12:40:00Z", 45.0),  // e: lines out of time order
    )

    @Test fun `fixture yields exactly the fully observed stretches`() {
        assertEquals(fixtureStretches, SessionHistory.parse(Files.readAllLines(resource("history.jsonl"))))
    }

    @Test fun `stretches ending in UNKNOWN or UNOBSERVED or starting from UNKNOWN or UNOBSERVED are excluded`() {
        fun line(s: String, from: AgentState, to: AgentState, ts: String) = SessionLogEncoder.str(s).let {
            """{"type":"transition","session_id":$it,"ts":"$ts","from_state":"$from","to_state":"$to"}"""
        }
        val lines = listOf(
            line("u", AgentState.DONE, AgentState.WORKING, "2026-11-07T10:00:00Z"),
            line("u", AgentState.WORKING, AgentState.UNKNOWN, "2026-11-07T10:00:10Z"),
            line("o", AgentState.DONE, AgentState.WORKING, "2026-11-07T10:01:00Z"),
            line("o", AgentState.WORKING, AgentState.UNOBSERVED, "2026-11-07T10:01:10Z"),
            line("s", AgentState.UNOBSERVED, AgentState.WORKING, "2026-11-07T10:02:00Z"),
            line("s", AgentState.WORKING, AgentState.DONE, "2026-11-07T10:02:10Z"),
            line("k", AgentState.UNKNOWN, AgentState.WORKING, "2026-11-07T10:03:00Z"),
            line("k", AgentState.WORKING, AgentState.DONE, "2026-11-07T10:03:10Z"),
        )
        assertEquals(emptyList<Stretch>(), SessionHistory.parse(lines))
    }

    @Test fun `malformed lines are skipped`() {
        val lines = listOf(
            "", "garbage", "{", """{"type":"transition"}""",
            """{"type":"transition","session_id":"x","ts":"nope","from_state":"DONE","to_state":"WORKING"}""",
            """{"type":"transition","session_id":"x","ts":"2026-11-07T10:00:00Z","from_state":"DONE","to_state":"WORKING"}""",
            """{"type":"transition","session_id":"x","ts":"2026-11-07T10:00:05Z","from_state":"WORKING","to_state":"IDLE"}""",
        )
        assertEquals(listOf(stretch("2026-11-07T10:00:00Z", 5.0)), SessionHistory.parse(lines))
    }

    @Test fun `seed file is merged with the logs in start order`() {
        val logs = tmp.newFolder("sessions").toPath()
        Files.copy(resource("history.jsonl"), logs.resolve("2026-11-07.jsonl"))
        val result = SessionHistory.load(logs, resource("seed-history.example.jsonl"))

        assertEquals(13, result.size)
        assertEquals(result.sortedBy { it.startTs }, result)
        assertEquals(listOf(20.0, 45.0, 60.0, 90.0, 120.0, 150.0, 240.0, 300.0, 420.0, 600.0), result.take(10).map { it.durationS })
        assertEquals(fixtureStretches, result.drop(10))
    }

    @Test fun `same session ids in different files do not mix`() {
        val logs = tmp.newFolder("sessions").toPath()
        val a = """{"type":"transition","session_id":"dup","ts":"2026-11-07T10:00:00Z","from_state":"DONE","to_state":"WORKING"}"""
        val b = """{"type":"transition","session_id":"dup","ts":"2026-11-07T10:00:09Z","from_state":"WORKING","to_state":"DONE"}"""
        Files.writeString(logs.resolve("1.jsonl"), a + "\n")
        Files.writeString(logs.resolve("2.jsonl"), b + "\n")
        assertEquals(emptyList<Stretch>(), SessionHistory.load(logs, tmp.root.toPath().resolve("none.jsonl")))
    }

    @Test fun `missing log directory and seed file are ignored`() {
        val nothing = tmp.root.toPath().resolve("missing")
        assertEquals(emptyList<Stretch>(), SessionHistory.load(nothing, nothing.resolve("seed.jsonl")))
        val logs = tmp.newFolder("sessions").toPath()
        Files.copy(resource("history.jsonl"), logs.resolve("x.jsonl"))
        assertEquals(fixtureStretches, SessionHistory.load(logs, nothing.resolve("seed.jsonl")))
    }

    @Test fun `seed path defaults under the system dir and the property overrides it`() {
        val sys = Path.of("/ide/system")
        assertEquals(sys.resolve("reel-break/seed-history.jsonl"), SessionHistory.resolveSeedPath(null) { sys })
        assertEquals(sys.resolve("reel-break/seed-history.jsonl"), SessionHistory.resolveSeedPath("  ") { sys })
        assertEquals(Path.of("/tmp/custom.jsonl"), SessionHistory.resolveSeedPath("/tmp/custom.jsonl") { error("unused") })

        val previous = System.getProperty(SessionHistory.SEED_PROPERTY)
        try {
            System.setProperty(SessionHistory.SEED_PROPERTY, "/tmp/from-property.jsonl")
            assertEquals(Path.of("/tmp/from-property.jsonl"), SessionHistory.defaultSeedPath())
        } finally {
            if (previous == null) System.clearProperty(SessionHistory.SEED_PROPERTY)
            else System.setProperty(SessionHistory.SEED_PROPERTY, previous)
        }
    }

    @Test fun `startup summary names the paths and whether the seed exists`() {
        val seed = resource("seed-history.example.jsonl")
        val logs = Path.of("/logs")
        assertEquals("Reel Break: ETA history: logDir=/logs, seed=$seed (found)", SessionHistory.startupSummary(logs, seed))
        assertTrue(SessionHistory.startupSummary(logs, Path.of("/nope.jsonl")).endsWith("(not found)"))
    }

    @Test fun `live stretches match what the log would later parse to`() {
        val lines = mutableListOf<String>()
        val live = mutableListOf<Stretch>()
        val tracker = AgentStateTracker { "live" }
        tracker.addListener(SessionLogWriter { lines += it })
        tracker.addListener(LiveStretches { live += it })
        fun pane(a: String) = PaneActivity("p", a == "PROCESSING", a)

        val base = Instant.parse("2026-11-07T09:00:00Z").toEpochMilli()
        tracker.observe(pane("READY"), base)
        tracker.observe(pane("PROCESSING"), base + 1_000)
        tracker.observe(pane("NEEDS_INPUT"), base + 11_000)
        tracker.observe(pane("PROCESSING"), base + 31_000)
        tracker.observe(pane("SOMETHING_NEW"), base + 41_000)  // WORKING -> UNKNOWN: not a stretch
        tracker.observe(pane("PROCESSING"), base + 51_000)
        tracker.observe(pane("READY"), base + 81_000)

        // The UNKNOWN -> WORKING -> DONE tail is not a stretch either.
        val expected = listOf(stretch("2026-11-07T09:00:01Z", 10.0))
        assertEquals(expected, live)
        assertEquals(expected, SessionHistory.parse(lines))
    }
}
