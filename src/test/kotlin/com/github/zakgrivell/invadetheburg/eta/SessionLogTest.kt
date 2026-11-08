package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.AgentState
import com.github.zakgrivell.invadetheburg.services.eta.FileLineSink
import com.github.zakgrivell.invadetheburg.services.eta.SessionEnd
import com.github.zakgrivell.invadetheburg.services.eta.SessionLogEncoder
import com.github.zakgrivell.invadetheburg.services.eta.SessionLogWriter
import com.github.zakgrivell.invadetheburg.services.eta.SessionOutcome
import com.github.zakgrivell.invadetheburg.services.eta.StateTransition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executor

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val noon = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
    private val direct = Executor { it.run() }

    @Test fun `transition golden line`() {
        val t = StateTransition("s-1", noon, AgentState.WORKING, AgentState.NEEDS_PERMISSION)
        assertEquals(
            """{"type":"transition","session_id":"s-1","ts":"2026-11-07T12:00:00Z","from_state":"WORKING","to_state":"NEEDS_PERMISSION"}""",
            SessionLogEncoder.transition(t),
        )
    }

    @Test fun `session end golden lines`() {
        assertEquals(
            """{"type":"session_end","session_id":"s-1","ts":"2026-11-07T12:00:00Z","outcome":"done","total_duration_s":143.2}""",
            SessionLogEncoder.sessionEnd(SessionEnd("s-1", noon, SessionOutcome.DONE, 143.2)),
        )
        assertEquals(
            """{"type":"session_end","session_id":"s-2","ts":"2026-11-07T12:00:00.250Z","outcome":"abandoned","total_duration_s":60.0}""",
            SessionLogEncoder.sessionEnd(SessionEnd("s-2", noon + 250, SessionOutcome.ABANDONED, 60.0)),
        )
        assertEquals(
            """{"type":"session_end","session_id":"s-3","ts":"2026-11-07T12:00:00Z","outcome":"partial","total_duration_s":0.0}""",
            SessionLogEncoder.sessionEnd(SessionEnd("s-3", noon, SessionOutcome.PARTIAL, Double.NaN)),
        )
    }

    @Test fun `strings are escaped`() {
        assertEquals("\"plain\"", SessionLogEncoder.str("plain"))
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\bg\\fh\"", SessionLogEncoder.str("a\"b\\c\nd\re\tf\bg\u000Ch"))
        assertEquals("\"\\u0001\\u001f\"", SessionLogEncoder.str("\u0001\u001f"))
        assertEquals("\"café ✓\"", SessionLogEncoder.str("café ✓"))
        val line = SessionLogEncoder.transition(StateTransition("bad\"id\n", noon, AgentState.DONE, AgentState.WORKING))
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\"session_id\":\"bad\\\"id\\n\""))
    }

    @Test fun `writer sends one line per event to the sink`() {
        val lines = mutableListOf<String>()
        val w = SessionLogWriter { lines += it }
        w.onTransition(StateTransition("s", noon, AgentState.DONE, AgentState.WORKING))
        w.onSessionEnd(SessionEnd("s", noon, SessionOutcome.DONE, 1.5))
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("""{"type":"transition""""))
        assertTrue(lines[1].startsWith("""{"type":"session_end""""))
    }

    @Test fun `file sink appends dated jsonl files and creates the directory`() {
        val dir = tmp.root.toPath().resolve("reel-break/sessions")
        var day = LocalDate.parse("2026-11-07")
        val sink = FileLineSink(dir, direct) { day }
        sink.append("one")
        sink.append("two")
        day = LocalDate.parse("2026-11-08")
        sink.append("three")
        assertEquals(listOf("one", "two"), Files.readAllLines(dir.resolve("2026-11-07.jsonl")))
        assertEquals(listOf("three"), Files.readAllLines(dir.resolve("2026-11-08.jsonl")))
    }

    @Test fun `file sink swallows io failures and rejected executions`() {
        val blocker = tmp.newFile("not-a-dir").toPath()
        FileLineSink(blocker.resolve("sessions"), direct).append("x")
        FileLineSink(tmp.root.toPath(), { error("pool shut down") }).append("x")
    }
}
