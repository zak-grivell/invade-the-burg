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
 * - Rebuilding the predictor sorts the whole history, so it runs on [rebuildExecutor] and is swapped in through a
 *   volatile reference; the lock held by [observe] only ever covers O(1) work.
 */
internal class EtaMonitor(
    sink: LineSink,
    private val rebuildExecutor: Executor = Executor { it.run() },
    private val predictorFactory: (List<Double>) -> EtaPredictorApi = { EtaPredictor(it) },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val info: (String) -> Unit = {},
    private val debug: (String) -> Unit = {},
) {
    private val tracker = AgentStateTracker()
    private var baseHistory = emptyList<Stretch>()
    private val closedSinceStart = mutableListOf<Stretch>()
    @Volatile private var predictor: EtaPredictorApi = predictorFactory(emptyList())
    private var requestedVersion = 0L
    private var installedVersion = 0L
    private var pendingPrediction: Pair<Long, Eta>? = null
    private var lastAdvisoryMs: Long? = null

    // Per-stretch ETA behaviour. Only stretches whose start we saw are trusted: a pane that was already WORKING when
    // first observed would otherwise look like elapsed = 0 and be wrongly skipped or hinted.
    private var reliableStretch = false
    private var skipStretch = false
    private var longerThanUsual = false
    private var lastBadgeLabel: String? = null
    private var cachedView: CachedView? = null

    private class CachedView(val atMs: Long, val breakShowing: Boolean, val view: EtaView)

    init {
        tracker.addListener(SessionLogWriter(sink))
        tracker.addListener(object : AgentStateListener {
            override fun onTransition(t: StateTransition) {
                if (t.to == AgentState.WORKING) {
                    val eta = predictNow(t.tsMs)
                    pendingPrediction = t.tsMs to eta
                    reliableStretch = t.from in CLEAN_STRETCH_STARTS
                    skipStretch = reliableStretch && etaAction(decide(eta), breakShowing = false, eta = eta) == EtaAction.SKIP_BREAK
                    longerThanUsual = false
                    lastBadgeLabel = null
                    cachedView = null
                }
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

    /**
     * What the overlay should do right now. A skip is decided at the start of a trusted stretch but is only provisional:
     * while the stretch is still WORKING the estimate is re-evaluated with the current elapsed time, and the break opens
     * as soon as it is no longer short (including an Unknown estimate, i.e. too few past stretches lasted this long).
     * A reopened break is never skipped again; later in the stretch only a "wrapping up" hint can appear, and only while
     * a break is showing. Recomputed at most every [VIEW_INTERVAL_MS].
     */
    @Synchronized
    fun evaluate(nowMs: Long, breakShowing: Boolean): EtaView {
        val features = tracker.features(nowMs, zone) ?: return noted(EtaView.NONE, "not in a working stretch")
        if (!reliableStretch) return noted(EtaView(EtaAction.SHOW_AS_TODAY, null), "stretch start not observed")
        cachedView?.takeIf { nowMs - it.atMs < VIEW_INTERVAL_MS && it.breakShowing == breakShowing }?.let { return it.view }
        val eta = predict(features)
        val view = if (skipStretch && etaAction(decide(eta), breakShowing = false, eta = eta) == EtaAction.SKIP_BREAK) {
            EtaView(EtaAction.SKIP_BREAK, null)
        } else {
            if (skipStretch) {
                skipStretch = false
                // Reopened because nothing in the history lasted this long: say so rather than showing no badge.
                longerThanUsual = eta !is Eta.Estimate
                info("Reel Break: ETA reopened break after ${Math.round(features.stretchElapsedS)}s (stretch longer than usual)")
            }
            // A skip is only ever decided from the start of a stretch; later it would also block a break the user asked for.
            val action = etaAction(decide(eta), breakShowing, eta).let { if (it == EtaAction.SKIP_BREAK) EtaAction.SHOW_AS_TODAY else it }
            val badge = etaBadgeText(eta, action) ?: LONGER_THAN_USUAL_BADGE.takeIf { longerThanUsual && eta !is Eta.Estimate }
            EtaView(action, badge)
        }
        cachedView = CachedView(nowMs, breakShowing, view)
        return noted(view, if (view.action == EtaAction.SKIP_BREAK) "break skipped" else if (eta !is Eta.Estimate) "Unknown" else "no badge")
    }

    /** Logs the badge whenever it changes, so a missing badge can be explained from idea.log. */
    private fun noted(view: EtaView, hiddenReason: String): EtaView {
        val label = view.badge?.let { "\"$it\"" } ?: "hidden: $hiddenReason"
        if (label != lastBadgeLabel) {
            lastBadgeLabel = label
            info("Reel Break: ETA badge: $label")
        }
        return view
    }

    /** The user explicitly asked for the break back: stop skipping it for the rest of this stretch. */
    @Synchronized
    fun forceBreak() {
        skipStretch = false
        cachedView = null
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

    internal fun onHistoryLoaded(loaded: List<Stretch>) {
        val loadedSet = loaded.toSet()
        synchronized(this) {
            baseHistory = loaded
            // A stretch closed before loading finished may already be in the log files that were just read.
            closedSinceStart.removeAll(loadedSet)
            requestedVersion++
        }
        rebuildExecutor.execute(::rebuildPredictor)
    }

    @Synchronized
    private fun onStretchClosed(s: Stretch) {
        val predicted = pendingPrediction?.takeIf { Instant.ofEpochMilli(it.first) == s.startTs }?.second
        pendingPrediction = null
        val p50 = (predicted as? Eta.Estimate)?.let { "${it.p50}s" } ?: "unknown"
        info("Reel Break: ETA stretch closed: predicted_p50=$p50 actual=${s.durationS}s")
        closedSinceStart += s
        requestedVersion++
        rebuildExecutor.execute(::rebuildPredictor)
    }

    /** Builds from a snapshot without holding the lock; an older build never replaces a newer one. */
    private fun rebuildPredictor() {
        val version: Long
        val history: List<Stretch>
        synchronized(this) {
            version = requestedVersion
            history = baseHistory + closedSinceStart
        }
        val built = predictorFactory(history.sortedBy { it.startTs }.map { it.durationS })
        synchronized(this) {
            if (version > installedVersion) {
                installedVersion = version
                predictor = built
            }
        }
    }

    private fun predictNow(nowMs: Long): Eta = tracker.features(nowMs, zone)?.let(::predict) ?: Eta.Unknown

    // A faulty predictor must never break session logging.
    private fun predict(f: EtaFeatures): Eta = runCatching { predictor.predict(f) }.getOrDefault(Eta.Unknown)

    companion object {
        const val ADVISORY_INTERVAL_MS = 10_000L
        const val VIEW_INTERVAL_MS = 1_000L

        fun createDefault(): EtaMonitor {
            val log = logger<EtaMonitor>()
            return EtaMonitor(
                FileLineSink.createDefault(),
                rebuildExecutor = AppExecutorUtil.getAppExecutorService(),
                predictorFactory = { EtaPredictor(it) },  // CONDITIONAL baseline
                info = { log.info(it) },
                debug = { log.debug(it) },
            )
        }
    }
}
