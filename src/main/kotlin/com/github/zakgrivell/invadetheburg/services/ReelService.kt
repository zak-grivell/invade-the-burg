package com.github.zakgrivell.invadetheburg.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.github.zakgrivell.invadetheburg.settings.ReelSettings
import com.github.zakgrivell.invadetheburg.settings.ReelSettingsConfigurable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.github.zakgrivell.invadetheburg.ui.PaneTransition
import com.github.zakgrivell.invadetheburg.ui.ReelOverlay
import com.github.zakgrivell.invadetheburg.services.eta.EtaAction
import com.github.zakgrivell.invadetheburg.services.eta.EtaMonitor
import com.github.zakgrivell.invadetheburg.services.eta.EtaView
import com.intellij.openapi.diagnostic.logger
import javax.swing.Timer
import com.github.zakgrivell.invadetheburg.settings.RecapMode
import com.github.zakgrivell.invadetheburg.ui.ChatRecapOverlay
import com.github.zakgrivell.invadetheburg.ui.renderSelectedRecap
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger

private val LOG = logger<ReelService>()

@Service(Service.Level.PROJECT)
class ReelService(private val project: Project) : Disposable {
    private val state = BreakState()
    private val browserView = BrowserViewState()
    private var viewEpoch = 0L
    private var renderedTarget = "chat"
    private var viewFile: VirtualFile? = null
    private var viewThread: String? = null
    private var viewEditor: javax.swing.JComponent? = null
    private var publishedActiveThread: String? = null
    private var queueAllowed: Boolean? = null
    private val transition = PaneTransition()
    private val retiredRecaps = mutableListOf<ChatRecapOverlay>()
    private val failedRecaps = mutableSetOf<VirtualFile>()
    private val completions = CompletionTracker()
    private val completedQueue = CompletedAgentQueue()
    private var queueFiles = emptyMap<String, VirtualFile>()
    private val queueListeners = mutableSetOf<(QueueView) -> Unit>()
    private var publishedQueue = emptyList<CompletedAgent>()

    internal fun onQueueChanged(listener: (QueueView) -> Unit): () -> Unit {
        queueListeners.add(listener)
        listener(QueueView(completedQueue.entries, selectedThread()))
        return { queueListeners.remove(listener) }
    }
    private fun publishQueue() {
        val entries = completedQueue.entries
        val activeThread = selectedThread()
        if (entries == publishedQueue && activeThread == publishedActiveThread) return
        publishedActiveThread = activeThread
        publishedQueue = entries
        queueListeners.toList().forEach { it(QueueView(entries, activeThread)) }
    }
    private fun selectedFile(): VirtualFile? = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).currentWindow?.selectedFile
        ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
    private fun selectedThread(): String? = AirPaneActivity.read(selectedFile())?.identity
    private fun openQueued(thread: String?) {
        val file = queueFiles[thread] ?: return
        val manager = FileEditorManager.getInstance(project)
        if (!manager.openFiles.contains(file)) return
        manager.openFile(file, true)
    }
    fun reviewCompleted(id: String) { openQueued(completedQueue.review(id)); publishQueue(); pollPane() }
    fun removeCompleted(id: String) { openQueued(completedQueue.remove(id, selectedThread())); publishQueue(); pollPane() }
    fun reorderCompleted(id: String, targetId: String, after: Boolean) { completedQueue.reorder(id, targetId, after); publishQueue() }
    private val narrator = ChatNarrator(project)
    private val recapViews = mutableMapOf<VirtualFile, ChatRecapOverlay>()
    private val recapOffers = mutableMapOf<VirtualFile, Notification>()
    private val latestTurns = mutableMapOf<VirtualFile, String>()
    private val requestedTurns = mutableMapOf<VirtualFile, String>()

    private fun clearRecap(file: VirtualFile) {
        recapViews.remove(file)?.let { it.pause(); retiredRecaps.add(it) }
        recapOffers.remove(file)?.expire()
        latestTurns.remove(file)
        requestedTurns.remove(file)
        failedRecaps.remove(file)
    }

    private fun offerRecap(file: VirtualFile, turn: ChatTurn) {
        clearRecap(file)
        latestTurns[file] = turn.id
        fun wanted() = !disposed && !project.isDisposed && latestTurns[file] == turn.id &&
            FileEditorManager.getInstance(project).openFiles.contains(file) && AirPaneActivity.read(file)?.processing == false
        fun watch(focus: Boolean) {
            if (!wanted() || service<ReelSettings>().recapMode == RecapMode.NEVER || requestedTurns[file] == turn.id) return
            requestedTurns[file] = turn.id
            if (focus) FileEditorManager.getInstance(project).openFile(file, true)
            narrator.completed(turn, ::wanted, onUnavailable = {
                if (wanted()) { failedRecaps.add(file); pollPane() }
            }) { media ->
                recapViews.remove(file)?.let { it.pause(); retiredRecaps.add(it) }
                recapViews[file] = ChatRecapOverlay(media, dismiss = { clearRecap(file); pollPane() })
                pollPane()
            }
        }
        when (service<ReelSettings>().recapMode) {
            RecapMode.NEVER -> clearRecap(file)
            RecapMode.ALWAYS -> watch(false)
            RecapMode.ASK -> {
                val toast = NotificationGroupManager.getInstance().getNotificationGroup("Reel Break Narration")
                    .createNotification("Want a recap?", "Watch this response with Peter Griffin and background video.", NotificationType.INFORMATION)
                toast.addAction(NotificationAction.createSimpleExpiring("Watch recap") { recapOffers.remove(file); watch(true) })
                recapOffers[file] = toast
                toast.notify(project)
            }
        }
    }
    private data class PendingRecap(val previousTurn: String?, val readyAt: Long, var failure: Throwable? = null)
    private val processingTurns = mutableMapOf<String, String?>()
    private val pendingRecaps = mutableMapOf<String, PendingRecap>()
    private val timer = Timer(200) { pollPane() }
    private val overlay = ReelOverlay(
        dismiss = { dismiss() },
        resume = { resumeScrolling() },
        settings = { ShowSettingsUtil.getInstance().showSettingsDialog(project, ReelSettingsConfigurable::class.java) }
    )
    private var disposed = false
    private var finishingIdentity: String? = null

    // Read-only ETA tracking and logging; it must never stop the overlay from working.
    private val eta: EtaMonitor? = runCatching { EtaMonitor.createDefault() }
        .onFailure { LOG.warn("Reel Break: ETA tracking unavailable", it) }.getOrNull()
    private var etaFailureLogged = false
    private var etaHistoryRequested = false

    private var queueSelectionConnected = false
    fun start() {
        if (disposed || timer.isRunning) return
        if (!queueSelectionConnected) {
            queueSelectionConnected = true
            project.messageBus.connect(this).subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                    override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                        suspendForNavigation(event.newFile)
                    }
                })
        }
        timer.start(); startEtaHistory(); pollPane()
    }
    fun resumeScrolling() {
        if (selectedThread() == null) return
        browserView.resume(); state.resume(); etaGuard { eta?.forceBreak() }; pollPane()
    }
    fun dismiss() {
        browserView.suspend()
        state.dismiss()
        val file = selectedFile()
        if (file != null && AirPaneActivity.read(file)?.completed == true) {
            AirPaneActivity.read(file)?.identity?.let { pendingRecaps.remove(it) }
            clearRecap(file)
            failedRecaps.add(file)
        }
        pollPane()
    }

    private fun suspendForNavigation(file: VirtualFile?) {
        val hadBrowser = renderedTarget == "break" || renderedTarget == "recap"
        val epoch = ++viewEpoch
        browserView.viewChanged(AirPaneActivity.read(file)?.processing == true)
        transition.dispose()
        overlay.hide()
        recapViews.values.forEach { it.hide() }
        renderedTarget = "chat"
        val manager = FileEditorManager.getInstance(project)
        viewFile = file
        viewThread = AirPaneActivity.read(file)?.identity
        viewEditor = manager.selectedEditor?.component
        javax.swing.SwingUtilities.invokeLater {
            if (disposed || project.isDisposed || epoch != viewEpoch || selectedFile() != file) return@invokeLater
            if (hadBrowser) {
                val editor = manager.selectedEditor
                val preferred = editor?.preferredFocusedComponent
                val focus = com.github.zakgrivell.invadetheburg.ui.chatFocusTarget(preferred, editor?.component)
                if (focus != null) com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(focus, true)
            }
            pollPane()
        }
    }
    private fun updateQueueVisibility(target: String, pane: PaneActivity?, editor: javax.swing.JComponent?) {
        val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Completed agents") ?: return
        val allowed = queueVisibleFor(target, pane, editor?.isShowing == true)
        val entering = queueAllowed != allowed
        queueAllowed = allowed
        if (window.isAvailable != allowed) window.setAvailable(allowed, null)
        if (!allowed && window.isVisible) window.hide()
        if (allowed && entering && !window.isVisible) window.show()
    }

    private fun pollPane() {
        if (disposed || project.isDisposed) return
        val manager = FileEditorManager.getInstance(project)
        val currentFile = selectedFile()
        val currentEditor = manager.selectedEditor?.component
        if (viewFile != currentFile || viewThread != AirPaneActivity.read(currentFile)?.identity || viewEditor !== currentEditor) {
            suspendForNavigation(currentFile)
            return
        }
        val panes = manager.openFiles.mapNotNull { file -> AirPaneActivity.read(file)?.let { file to it } }.distinctBy { it.second.identity }
        queueFiles = panes.associate { (file, activity) -> activity.identity to file }
        val advanceTo = completedQueue.observe(panes.map { (file, activity) -> QueuePane(activity, file.presentableName) }, selectedThread(), System.nanoTime() / 1_000_000)
        publishQueue()
        if (advanceTo != null) openQueued(advanceTo)
        (recapViews.keys + recapOffers.keys + latestTurns.keys).toSet().filter {
            it !in manager.openFiles || service<ReelSettings>().recapMode == RecapMode.NEVER
        }.forEach(::clearRecap)
        val identities = panes.map { it.second.identity }.toSet()
        completions.retain(identities)
        processingTurns.keys.retainAll(identities)
        pendingRecaps.keys.retainAll(identities)
        val now = System.nanoTime()
        val completedIdentities = mutableSetOf<String>()
        panes.forEach { (file, activity) ->
            fun turn(): ChatTurn? = runCatching {
                manager.getEditors(file).firstNotNullOfOrNull { AirChatContext.read(it.component) }
            }.onFailure { pendingRecaps[activity.identity]?.failure = it }.getOrNull()
            if (activity.processing) {
                if (latestTurns.containsKey(file)) clearRecap(file)
                pendingRecaps.remove(activity.identity)
                if (!processingTurns.containsKey(activity.identity)) processingTurns[activity.identity] = turn()?.id
            }
            if (completions.update(activity)) {
                completedIdentities.add(activity.identity)
                pendingRecaps[activity.identity] = PendingRecap(processingTurns[activity.identity], now)
            }
            if (!activity.processing) processingTurns.remove(activity.identity)
            pendingRecaps[activity.identity]?.let { pending ->
                // Completion metadata can arrive before the final transcript publication.
                val elapsedMs = (now - pending.readyAt) / 1_000_000
                if (elapsedMs >= 600) {
                    val snapshot = turn()
                    if (snapshot != null && snapshot.id != pending.previousTurn) {
                        pendingRecaps.remove(activity.identity)
                        offerRecap(file, snapshot)
                    } else if (elapsedMs >= 5_000) {
                        pendingRecaps.remove(activity.identity)
                        pending.failure?.let { Logger.getInstance(ReelService::class.java).warn("Could not read Air transcript for completed turn", it) }
                        failedRecaps.add(file)
                        narrator.unavailable()
                    }
                }
            }
        }
        val selectedFile = selectedFile()
        val pane = AirPaneActivity.read(selectedFile)
        val editor = manager.selectedEditor?.component
        eta?.let { monitor -> etaGuard { monitor.observe(pane, System.currentTimeMillis()) } }
        val view = etaView()
        overlay.setEtaBadge(view?.badge)
        val allowedByView = browserView.showBrowser(pane)
        val showBreak = allowedByView && (state.update(pane) || pane?.processing != true) && (view?.action != EtaAction.SKIP_BREAK || pane?.processing != true)
        val always = service<ReelSettings>().recapMode == RecapMode.ALWAYS
        val selectedRecap = recapViews[selectedFile]
        val waiting = always && pane?.completed == true && selectedFile !in failedRecaps &&
            (pendingRecaps.containsKey(pane.identity) || requestedTurns.containsKey(selectedFile)) &&
            selectedRecap == null
        val justCompleted = pane?.identity in completedIdentities
        val completing = !always && editor != null && pane?.completed == true &&
            ((justCompleted && overlay.isVisible) || (finishingIdentity == pane.identity && overlay.isFinishing))
        if (completing) finishingIdentity = pane.identity
        else if (!overlay.isFinishing) finishingIdentity = null
        // Preparing narration never reveals chat between the break and an Always recap.
        val keepBreak = waiting && overlay.isVisible
        if (keepBreak) overlay.pauseMedia()
        val target = when {
            showBreak || keepBreak -> "break"
            completing -> "completion"
            selectedRecap != null -> "recap"
            else -> "chat"
        }
        if (target == "break") updateQueueVisibility(target, pane, editor)
        if (target != "break") overlay.pauseMedia()
        val epoch = viewEpoch
        val key: Any = if (target == "chat") "chat" else Triple(selectedFile, target, if (target == "recap") selectedRecap else null)
        transition.show(key, editor) {
            if (epoch != viewEpoch || selectedFile() != selectedFile || manager.selectedEditor?.component !== editor || AirPaneActivity.read(selectedFile)?.identity != pane?.identity) return@show
            renderedTarget = target
            updateQueueVisibility(target, pane, editor)
            retiredRecaps.toList().forEach { it.dispose() }
            retiredRecaps.clear()
            renderSelectedRecap(recapViews.values, if (target == "recap") selectedRecap else null, editor)
            when (target) {
                "break" -> if (editor != null && !keepBreak && !overlay.showOver(editor, service<ReelSettings>().url)) overlay.hide()
                "completion" -> if (editor != null) overlay.finishOver(editor)
                else -> {
                    overlay.hide()
                    if (pane?.processing == true && editor != null) overlay.showReturnButton(editor)
                }
            }
        }
    }

    /** Null means "behave exactly as without ETA": the setting is off, ETA is unavailable, or it failed. */
    private fun etaView(): EtaView? {
        val monitor = eta ?: return null
        if (!service<ReelSettings>().useEtaToTimeBreaks) return null
        return runCatching { monitor.evaluate(System.currentTimeMillis(), overlay.isShowing) }
            .onFailure { logEtaFailureOnce(it) }.getOrNull()
    }

    private fun startEtaHistory() {
        if (etaHistoryRequested) return
        etaHistoryRequested = true
        eta?.let { monitor -> etaGuard { monitor.loadHistoryInBackground() } }
    }

    private fun etaGuard(block: () -> Unit) {
        runCatching(block).onFailure { logEtaFailureOnce(it) }
    }

    private fun logEtaFailureOnce(failure: Throwable) {
        if (!etaFailureLogged) { etaFailureLogged = true; LOG.warn("Reel Break: ETA tracking failed", failure) }
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        queueListeners.clear()
        narrator.dispose()
        eta?.let { monitor -> etaGuard { monitor.close(System.currentTimeMillis()) } }
        val application = ApplicationManager.getApplication()
        val cleanup = {
            transition.dispose()
            (recapViews.keys + recapOffers.keys).toSet().forEach(::clearRecap)
            retiredRecaps.forEach { it.dispose() }
            retiredRecaps.clear()
            overlay.dispose()
        }
        if (application.isDispatchThread) cleanup() else application.invokeLater { cleanup() }
    }
}
