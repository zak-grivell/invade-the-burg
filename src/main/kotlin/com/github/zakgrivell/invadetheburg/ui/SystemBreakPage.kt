package com.github.zakgrivell.invadetheburg.ui

import ca.weblite.webview.swing.WebViewComponent
import java.awt.BorderLayout
import java.awt.Toolkit
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Retains the native browser and pushes visibility on lifecycle events, never on a JS timer. */
internal class SystemBreakPage(url: String, private val ownsPlaybackControls: Boolean = false, private val platform: BrowserPlatform = BrowserPlatform.current()
    ?: error("Supported operating systems: Windows, macOS, and Linux.")) : BreakPage {
    override var ready: Boolean = !ownsPlaybackControls
        private set
    private var finished: () -> Unit = {}
    override fun onFinished(callback: () -> Unit) { finished = callback }
    private var browser: WebViewComponent? = null
    private val container = JPanel(BorderLayout())
    private val mediaScript = javaClass.getResource("/browser/media-visibility.js")!!.readText()
    private val pendingScripts = mutableListOf<String>()
    private var requestedUrl = url
    private var initializing = false
    @Volatile private var active = false
    @Volatile private var disposed = false

    init { initialize() }

    private fun initialize() {
        if (disposed || initializing || browser != null) return
        initializing = true
        container.removeAll()
        container.add(JLabel("Opening browser…", JLabel.CENTER), BorderLayout.CENTER)
        NativeBrowserRuntime.prepare(platform).whenComplete { _, error ->
            SwingUtilities.invokeLater {
                initializing = false
                if (disposed) return@invokeLater
                if (error != null) showError(error)
                else try { createBrowser() } catch (failure: Exception) { showError(failure) } catch (failure: LinkageError) { showError(failure) }
            }
        }
    }

    private fun createBrowser() {
        val view = WebViewComponent.create(platform.mode)
        browser = view
        view.addJavascriptCallback("reelBreakRecapRequestPlayback") {
            SwingUtilities.invokeLater {
                if (!disposed && active && ownsPlaybackControls && browser === view) {
                    // WKWebView's native evaluateJavaScript call supplies a gesture context.
                    // play() must run synchronously inside it, not in a timer/RAF continuation.
                    view.eval("if (window.__reelBreakStartRecapFromHost) window.__reelBreakStartRecapFromHost();")
                }
            }
        }
        view.addJavascriptCallback("reelBreakRecapFinished") {
            SwingUtilities.invokeLater { if (!disposed && ownsPlaybackControls) finished() }
        }
        view.addJavascriptCallback("reelBreakRecapReady") { SwingUtilities.invokeLater { if (!disposed) ready = true } }
        // One-way document lifecycle notification; no page code awaits a Java Promise.
        view.addJavascriptCallback("reelBreakDocumentReady") {
            SwingUtilities.invokeLater { if (!disposed) pushVisibility() }
        }
        view.addJavascriptCallback("reelBreakPlaybackFailure") { details ->
            Logger.getLogger(SystemBreakPage::class.java.name).log(Level.INFO, "Break playback failure: {0}", details)
        }
        view.addOnBeforeLoad("""
            (function () {
                window.__reelBreakOwnPlaybackControls = $ownsPlaybackControls;
                window.__reelBreakPaneActive = false;
                ${if (ownsPlaybackControls) "" else mediaScript.replace("/*HIDDEN*/", "document.hidden")}
                const ready = function () {
                    try { if (typeof window.reelBreakDocumentReady === 'function') window.reelBreakDocumentReady(''); } catch (_) {}
                };
                document.addEventListener('DOMContentLoaded', ready, {once: true});
                window.addEventListener('pageshow', ready);
                document.addEventListener('visibilitychange', function () {
                    if (document.hidden) { ${if (ownsPlaybackControls) "" else mediaScript.replace("/*HIDDEN*/", "true")} }
                    ready();
                });
                ready();
            })();
        """.trimIndent())
        pendingScripts.forEach { view.addOnBeforeLoad(it) }
        pendingScripts.clear()
        view.setUrl(requestedUrl)
        container.removeAll()
        container.add(view, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
        pushVisibility()
    }

    private fun showError(failure: Throwable) {
        ready = true
        val cause = (failure as? java.util.concurrent.CompletionException)?.cause ?: failure
        Logger.getLogger(SystemBreakPage::class.java.name).log(Level.WARNING, "Native break browser initialization failed", cause)
        try { browser?.dispose() } catch (cleanup: Exception) { cause.addSuppressed(cleanup) }
        browser = null
        container.removeAll()
        container.add(JTextArea("Browser unavailable.\n\n${platform.setup}\n\n${cause.javaClass.simpleName}: ${cause.message.orEmpty()}").apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
        }, BorderLayout.CENTER)
        container.add(JButton("Retry browser").apply { addActionListener { initialize() } }, BorderLayout.SOUTH)
        container.revalidate()
        container.repaint()
    }

    override val component: JComponent get() = container
    internal fun evaluate(script: String): CompletableFuture<String> = browser?.evalAsync(script)
        ?: CompletableFuture.failedFuture(IllegalStateException("Browser is initializing"))
    internal fun addDocumentStartScript(script: String) {
        val view = browser
        if (view == null) pendingScripts.add(script) else view.addOnBeforeLoad(script)
    }
    override fun loadUrl(url: String) {
        requestedUrl = url
        if (!disposed) browser?.setUrl(url)
    }
    override fun setActive(active: Boolean) { this.active = active; if (!disposed) pushVisibility() }
    private fun pushVisibility() {
        if (ownsPlaybackControls) {
            // The recap owns paired playback. Generic retained-media resume could start
            // either track before the selected pane is revealed, or race a first seek.
            browser?.eval("window.__reelBreakPaneActive = $active; if (window.__reelBreakActivateRecap) window.__reelBreakActivateRecap($active);")
        } else browser?.eval(mediaScript.replace("/*HIDDEN*/", (!active).toString()))
    }
    override fun dispose() {
        disposed = true
        active = false
        browser?.dispose()
        browser = null
        pendingScripts.clear()
    }
}

/** Native library loading must not hold up the IDE event thread or AppKit event processing. */
private object NativeBrowserRuntime {
    private var ready: CompletableFuture<Unit>? = null
    @Synchronized fun prepare(platform: BrowserPlatform): CompletableFuture<Unit> {
        val existing = ready
        if (existing != null && !existing.isCompletedExceptionally) return existing
        return CompletableFuture.supplyAsync {
            platform.environmentError(System.getenv())?.let { error(it) }
            val resource = platform.nativeResource(System.getProperty("os.arch", ""))
                ?: error("Reel Break supports x86-64 and ARM64.")
            check(SystemBreakPage::class.java.getResource(resource) != null) { "Native browser binary missing: $resource" }
            Toolkit.getDefaultToolkit()
            Class.forName("ca.weblite.webview.WebViewNative", true, SystemBreakPage::class.java.classLoader)
            Unit
        }.also { ready = it }
    }
}
