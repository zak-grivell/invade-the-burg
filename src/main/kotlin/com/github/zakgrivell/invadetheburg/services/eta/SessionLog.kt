package com.github.zakgrivell.invadetheburg.services.eta

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executor

/** Hand-written JSON encoder for the two session-log line kinds; there is no JSON library in the plugin. */
internal object SessionLogEncoder {
    fun transition(t: StateTransition): String =
        """{"type":"transition","session_id":${str(t.sessionId)},"ts":${str(ts(t.tsMs))},""" +
            """"from_state":${str(t.from.name)},"to_state":${str(t.to.name)}}"""

    fun sessionEnd(e: SessionEnd): String =
        """{"type":"session_end","session_id":${str(e.sessionId)},"ts":${str(ts(e.tsMs))},""" +
            """"outcome":${str(e.outcome.name.lowercase())},"total_duration_s":${num(e.totalDurationS)}}"""

    private fun ts(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private fun num(d: Double): String = if (d.isFinite()) BigDecimal.valueOf(d).toPlainString() else "0.0"

    internal fun str(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

internal fun interface LineSink {
    fun append(line: String)
}

/** Writes each state change and session end as one JSON line. Contains no I/O of its own. */
internal class SessionLogWriter(private val sink: LineSink) : AgentStateListener {
    override fun onTransition(t: StateTransition) = sink.append(SessionLogEncoder.transition(t))
    override fun onSessionEnd(e: SessionEnd) = sink.append(SessionLogEncoder.sessionEnd(e))
}

/**
 * Appends lines to `<dir>/<yyyy-MM-dd>.jsonl` (UTC date) on [executor], keeping file I/O off the caller's
 * thread. Failures are logged and dropped: the log is best-effort and must never disturb the IDE.
 */
internal class FileLineSink(
    private val dir: Path,
    private val executor: Executor,
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
) : LineSink {
    override fun append(line: String) {
        val file = dir.resolve("${today()}.jsonl")
        try {
            executor.execute { write(file, line) }
        } catch (e: RuntimeException) {
            LOG.warn("Reel Break: could not queue session log write", e)
        }
    }

    private fun write(file: Path, line: String) {
        try {
            Files.createDirectories(dir)
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: IOException) {
            LOG.warn("Reel Break: could not write session log $file", e)
        }
    }

    companion object {
        private val LOG = logger<FileLineSink>()

        fun defaultDir(): Path = Path.of(PathManager.getSystemPath(), "reel-break", "sessions")

        /** Production sink: IDE system directory, written by a single-threaded application executor. */
        fun createDefault(): FileLineSink =
            FileLineSink(defaultDir(), AppExecutorUtil.createBoundedApplicationPoolExecutor("Reel Break session log", 1))
    }
}
