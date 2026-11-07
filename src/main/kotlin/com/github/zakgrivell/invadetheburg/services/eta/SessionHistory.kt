package com.github.zakgrivell.invadetheburg.services.eta

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** One transition line as read back from the session log. */
internal data class LogTransition(val sessionId: String, val ts: Instant, val from: AgentState, val to: AgentState)

/**
 * Applies the single stretch rule, shared by log parsing and live tracking: a stretch is a transition
 * `X -> WORKING` with X in NEEDS_PERMISSION, DONE or IDLE, followed immediately, in the same session, by
 * `WORKING -> NEEDS_PERMISSION | DONE | IDLE`. Starts from UNKNOWN or UNOBSERVED and ends in UNKNOWN or UNOBSERVED
 * are dropped: WORKING -> UNKNOWN -> WORKING is usually a read glitch and would fake a short stretch.
 */
internal class StretchExtractor {
    private val pendingStart = HashMap<String, Instant>()

    fun accept(t: LogTransition): Stretch? {
        val start = pendingStart.remove(t.sessionId)
        var stretch: Stretch? = null
        if (start != null && t.from == AgentState.WORKING && t.to in STRETCH_ENDS) {
            val seconds = Duration.between(start, t.ts).toMillis() / 1000.0
            if (seconds >= 0) stretch = Stretch(start, seconds)
        }
        if (t.to == AgentState.WORKING && t.from in STRETCH_STARTS) pendingStart[t.sessionId] = t.ts
        return stretch
    }

    fun forget(sessionId: String) { pendingStart.remove(sessionId) }

    private companion object {
        val STRETCH_STARTS = setOf(AgentState.NEEDS_PERMISSION, AgentState.DONE, AgentState.IDLE)
        val STRETCH_ENDS = STRETCH_STARTS
    }
}

/** Feeds stretches closed while the plugin runs to [onStretch], using the same rule as [SessionHistory.parse]. */
internal class LiveStretches(private val onStretch: (Stretch) -> Unit) : AgentStateListener {
    private val extractor = StretchExtractor()

    @Synchronized
    override fun onTransition(t: StateTransition) {
        extractor.accept(LogTransition(t.sessionId, Instant.ofEpochMilli(t.tsMs), t.from, t.to))?.let(onStretch)
    }

    @Synchronized
    override fun onSessionEnd(e: SessionEnd) = extractor.forget(e.sessionId)
}

/** Reads past WORKING stretches from the session logs and an optional seed file. */
internal object SessionHistory {
    const val SEED_PROPERTY = "reelbreak.seedHistory"
    private val LOG = logger<SessionHistory>()

    /** Parses log lines into stretches ordered by start time. Lines that are not valid transitions are skipped. */
    fun parse(lines: List<String>): List<Stretch> {
        val extractor = StretchExtractor()
        return lines.mapNotNull(::parseTransition)
            .groupBy { it.sessionId }
            .values
            .flatMap { session -> session.sortedBy { it.ts }.mapNotNull(extractor::accept) }
            .sortedBy { it.startTs }
    }

    /** Stretches from every `*.jsonl` in [logDir] plus [seedFile], each parsed on its own. Missing paths are ignored. */
    fun load(logDir: Path, seedFile: Path): List<Stretch> {
        val files = buildList {
            if (Files.isDirectory(logDir)) {
                try {
                    Files.newDirectoryStream(logDir, "*.jsonl").use { addAll(it.sorted()) }
                } catch (e: IOException) {
                    LOG.warn("Reel Break: could not list session logs in $logDir", e)
                }
            }
            if (Files.isRegularFile(seedFile)) add(seedFile)
        }
        return files.flatMap { file ->
            try {
                parse(Files.readAllLines(file))
            } catch (e: IOException) {
                LOG.warn("Reel Break: could not read $file", e)
                emptyList()
            }
        }.sortedBy { it.startTs }
    }

    fun defaultLogDir(): Path = FileLineSink.defaultDir()

    /** [override] (the `reelbreak.seedHistory` property) wins; [systemPath] is only evaluated without one. */
    fun resolveSeedPath(override: String?, systemPath: () -> Path): Path =
        if (!override.isNullOrBlank()) Path.of(override) else systemPath().resolve("reel-break").resolve("seed-history.jsonl")

    fun defaultSeedPath(): Path =
        resolveSeedPath(System.getProperty(SEED_PROPERTY)) { Path.of(PathManager.getSystemPath()) }

    fun startupSummary(logDir: Path, seedFile: Path): String =
        "Reel Break: ETA history: logDir=$logDir, seed=$seedFile (${if (Files.isRegularFile(seedFile)) "found" else "not found"})"

    private fun parseTransition(line: String): LogTransition? {
        val s = line.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        if (field(s, "type") != "transition") return null
        return try {
            LogTransition(
                sessionId = field(s, "session_id") ?: return null,
                ts = Instant.parse(field(s, "ts") ?: return null),
                from = AgentState.valueOf(field(s, "from_state") ?: return null),
                to = AgentState.valueOf(field(s, "to_state") ?: return null),
            )
        } catch (_: DateTimeParseException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // Our own schema only: flat object, string values. Escaped characters are compared as written.
    private fun field(json: String, name: String): String? =
        Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)
}
