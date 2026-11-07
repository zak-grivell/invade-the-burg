package com.github.zakgrivell.invadetheburg.services.eta

import com.github.zakgrivell.invadetheburg.services.PaneActivity
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * Read-only ETA plumbing for [com.github.zakgrivell.invadetheburg.services.ReelService]: feeds pane observations
 * to the tracker, writes the session log, keeps stretch history, and logs predictions. It never touches the UI.
 *
 * - Each stretch start is predicted once; when the stretch closes, `predicted_p50` is logged next to the actual duration.
 * - While WORKING, an advisory prediction is logged at debug level at most every [ADVISORY_INTERVAL_MS].
 * - History is the log directory plus seed file (loaded in the background) plus stretches closed since startup.
 */
internal class EtaMonitor(
    sink: LineSink,
    private val predictorFactory: (List<Double>) -> EtaPredictorApi = { StubEtaPredictor },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val info: (String) -> Unit = {},
    private val debug: (String) -> Unit = {},
) {
    private val tracker = AgentStateTracker()
    private var baseHistory = emptyList<Stretch>()
    private val closedSinceStart = mutableListOf<Stretch>()
    private var predictor: EtaPredictorApi = predictorFactory(emptyList())
    private var pendingPrediction: Pair<Long, Eta>? = null
    private var lastAdvisoryMs: Long? = null

    init {
        tracker.addListener(SessionLogWriter(sink))
        tracker.addListener(object : AgentStateListener {
            override fun onTransition(t: StateTransition) {
                if (t.to == AgentState.WORKING) pendingPrediction = t.tsMs to predictNow(t.tsMs)
            }
            override fun onSessionEnd(e: SessionEnd) {}
        })
        tracker.addListener(LiveStretches(::onStretchClosed))
    }

    @Synchronized
    fun observe(pane: PaneActivity?, nowMs: Long) {
        tracker.observe(pane, nowMs)
        val last = lastAdvisoryMs
        if (last == null || nowMs - last >= ADVISORY_INTERVAL_MS) {
            val features = tracker.features(nowMs, zone) ?: return
            lastAdvisoryMs = nowMs
            debug("Reel Break: ETA advisory: stretchElapsed=${features.stretchElapsedS}s eta=${predict(features)}")
        }
    }

    /** Ends any open session as abandoned; call on dispose. */
    @Synchronized
    fun close(nowMs: Long) = tracker.close(nowMs)

    /** Logs the resolved paths, then loads history on [executor]. */
    fun loadHistoryInBackground(
        executor: Executor = AppExecutorUtil.getAppExecutorService(),
        logDir: Path = SessionHistory.defaultLogDir(),
        seedFile: Path = SessionHistory.defaultSeedPath(),
    ) {
        info(SessionHistory.startupSummary(logDir, seedFile))
        executor.execute {
            val loaded = SessionHistory.load(logDir, seedFile)
            onHistoryLoaded(loaded)
            info("Reel Break: ETA history loaded: ${loaded.size} stretches")
        }
    }

    @Synchronized
    internal fun onHistoryLoaded(loaded: List<Stretch>) {
        baseHistory = loaded
        // A stretch closed before loading finished may already be in the log files that were just read.
        closedSinceStart.removeAll(loaded.toSet())
        rebuildPredictor()
    }

    @Synchronized
    private fun onStretchClosed(s: Stretch) {
        val predicted = pendingPrediction?.takeIf { Instant.ofEpochMilli(it.first) == s.startTs }?.second
        pendingPrediction = null
        val p50 = (predicted as? Eta.Estimate)?.let { "${it.p50}s" } ?: "unknown"
        info("Reel Break: ETA stretch closed: predicted_p50=$p50 actual=${s.durationS}s")
        closedSinceStart += s
        rebuildPredictor()
    }

    private fun rebuildPredictor() {
        val durations = (baseHistory + closedSinceStart).sortedBy { it.startTs }.map { it.durationS }
        predictor = predictorFactory(durations)
    }

    private fun predictNow(nowMs: Long): Eta = tracker.features(nowMs, zone)?.let(::predict) ?: Eta.Unknown

    // A faulty predictor must never break session logging.
    private fun predict(f: EtaFeatures): Eta = runCatching { predictor.predict(f) }.getOrDefault(Eta.Unknown)

    companion object {
        const val ADVISORY_INTERVAL_MS = 10_000L

        fun createDefault(): EtaMonitor {
            val log = logger<EtaMonitor>()
            return EtaMonitor(FileLineSink.createDefault(), info = { log.info(it) }, debug = { log.debug(it) })
        }
    }
}
