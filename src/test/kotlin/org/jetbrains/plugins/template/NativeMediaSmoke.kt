package org.jetbrains.plugins.template

import com.sun.net.httpserver.HttpServer
import org.jetbrains.plugins.template.ui.SystemBreakPage
import java.awt.BorderLayout
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Standalone opt-in smoke test. Uses a public H.264/AAC sample served only on loopback. */
object NativeMediaSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val sample = Files.readAllBytes(Path.of("build/media-smoke/h264-aac.mp4"))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mp4") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split("-")
            val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
            val end = (range?.getOrNull(1)?.toIntOrNull() ?: sample.lastIndex).coerceAtMost(sample.lastIndex)
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${sample.size}")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, (end - start + 1).toLong())
            exchange.responseBody.use { it.write(sample, start, end - start + 1) }
        }
        server.createContext("/") { exchange ->
            val html = """<!doctype html><title>Reel Break codec test</title><body style="background:#171717;color:white;font:18px sans-serif"><h2>Reel Break: native H.264/AAC playback test</h2><video id="probe" controls autoplay muted playsinline loop style="width:100%" src="/video.mp4"></video><p>This window closes when codec and persistence checks finish.</p></body>""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
        }
        server.start()
        lateinit var page: SystemBreakPage
        lateinit var frame: JFrame
        lateinit var container: JPanel
        var pageCreated = false
        var frameCreated = false
        fun eval(script: String): String {
            lateinit var future: java.util.concurrent.CompletableFuture<String>
            SwingUtilities.invokeAndWait { future = page.evaluate(script) }
            return future.get(5, TimeUnit.SECONDS)
        }
        try {
            val openedAt = System.nanoTime()
            SwingUtilities.invokeAndWait {
                page = SystemBreakPage("http://127.0.0.1:${server.address.port}/")
                pageCreated = true
                // Regression fixture: a document can drop a JS readiness reply while video still works.
                page.addDocumentStartScript("""
                    (function() {
                        const post = window.__webview_eval_post;
                        window.__webview_eval_post = function(id, ok, message) {
                            if (message === 'true') return;
                            return post(id, ok, message);
                        };
                    })();
                """.trimIndent())
                container = JPanel(BorderLayout()).apply { add(page.component) }
                frame = JFrame("Reel Break media smoke test").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    add(container)
                    setSize(900, 680)
                    isVisible = true
                }
                frameCreated = true
                page.setActive(true)
            }
            var playing = false
            repeat(60) {
                if (!playing) {
                    Thread.sleep(500)
                    playing = runCatching { eval("const v=document.getElementById('probe'); return (v && v.readyState >= 3 && v.currentTime > 0.2 && !v.paused) ? 1 : 0;") == "1" }.getOrDefault(false)
                }
            }
            check(playing) { "Native H.264 video did not start playing." }
            println("PASS: native H.264/AAC sample is decoding and playing after ${(System.nanoTime() - openedAt) / 1_000_000} ms.")
            check(eval("return typeof window.reelBreakIsActive;") == "\"undefined\"") { "Java visibility polling bridge is still installed." }
            println("PASS: no Java visibility polling bridge is installed.")
            println("Codec support: " + eval("const v=document.createElement('video'); return {h264:v.canPlayType('video/mp4; codecs=\"avc1.42E01E\"'),aac:v.canPlayType('audio/mp4; codecs=\"mp4a.40.2\"')};"))
            eval("window.retainedMarker = 'keep-this-document'; return 1;")
            SwingUtilities.invokeAndWait { page.setActive(false); container.isVisible = false }
            Thread.sleep(1000)
            check(eval("return document.getElementById('probe').paused ? 'paused' : 'playing';") == "\"paused\"") { "Hidden media did not pause." }
            val hiddenTime = eval("return document.getElementById('probe').currentTime;").toDouble()
            Thread.sleep(750)
            val stillHiddenTime = eval("return document.getElementById('probe').currentTime;").toDouble()
            check(kotlin.math.abs(hiddenTime - stillHiddenTime) < 0.1) { "Hidden media continued playing." }
            println("PASS: hidden native media is paused.")
            SwingUtilities.invokeAndWait { container.isVisible = true; page.setActive(true) }
            Thread.sleep(1000)
            check(eval("return window.retainedMarker;") == "\"keep-this-document\"") { "Hiding destroyed the page." }
            check(eval("return document.getElementById('probe').paused ? 'paused' : 'playing';") == "\"playing\"") { "Visible media did not resume." }
            println("PASS: same document survived hide/show and playback resumed.")
            val nextStarted = System.nanoTime()
            eval("""
                setTimeout(function() {
                    const old = document.getElementById('probe');
                    const next = document.createElement('video');
                    next.id = 'probe'; next.autoplay = true; next.muted = true; next.playsInline = true; next.loop = true;
                    next.style.width = '100%'; next.src = '/video.mp4?next';
                    old.replaceWith(next);
                }, 300);
                return 1;
            """.trimIndent())
            var nextPlaying = false
            repeat(30) {
                if (!nextPlaying) {
                    Thread.sleep(250)
                    nextPlaying = eval("const v=document.getElementById('probe'); return v && v.currentSrc.includes('?next') && v.currentTime > 0.1 && !v.paused ? 1 : 0;") == "1"
                }
            }
            check(nextPlaying) { "The next autoplay video did not start." }
            println("PASS: next video started after ${(System.nanoTime() - nextStarted) / 1_000_000} ms.")
            eval("""
                const v = document.getElementById('probe');
                const nativePlay = v.play.bind(v);
                let interruptions = 2;
                v.play = function () {
                    if (interruptions-- > 0) return Promise.reject(new DOMException('Controlled interruption', 'AbortError'));
                    return nativePlay();
                };
                v.pause();
                return 1;
            """.trimIndent())
            var recovered = false
            repeat(24) {
                if (!recovered) {
                    Thread.sleep(250)
                    recovered = eval("return window.__reelBreakMediaState.failures.filter(f => f.name === 'AbortError').length >= 2 && !document.getElementById('probe').paused ? 1 : 0;") == "1"
                }
            }
            check(recovered) { "Interrupted playback did not recover automatically." }
            eval("delete document.getElementById('probe').play; return 1;")
            println("PASS: native playback recovered automatically after two controlled interruptions without a click.")
            // Stay visible beyond the former 20-second watchdog, then exercise more prompt cycles.
            Thread.sleep(22_000)
            check(eval("return window.retainedMarker;") == "\"keep-this-document\"") { "Startup watchdog destroyed a working page." }
            repeat(3) {
                SwingUtilities.invokeAndWait { page.setActive(false); container.isVisible = false }
                Thread.sleep(300)
                SwingUtilities.invokeAndWait { container.isVisible = true; page.setActive(true) }
                Thread.sleep(300)
                check(eval("return window.retainedMarker;") == "\"keep-this-document\"") { "Page was lost on a later prompt." }
            }
            println("PASS: missing readiness replies do not destroy the page after 20 seconds or later prompt cycles.")
        } finally {
            SwingUtilities.invokeAndWait { if (pageCreated) page.dispose(); if (frameCreated) frame.dispose() }
            server.stop(0)
        }
    }
}
