package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.ui.*
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

object RecapVideoSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val media = RecapMedia("Okay, quick chat recap! The agent added a video underneath your summary. Your gameplay can replace this stock clip in Settings.", ByteArray(48000 * 10), args.firstOrNull() ?: DEFAULT_RECAP_VIDEO)
        lateinit var page: SystemBreakPage
        lateinit var frame: JFrame
        lateinit var editor: JPanel
        lateinit var overlay: ChatRecapOverlay
        val transition = PaneTransition()
        var completed = false
        fun eval(script: String): String {
            lateinit var future: java.util.concurrent.CompletableFuture<String>
            SwingUtilities.invokeAndWait { future = page.evaluate(script) }
            return future.get(5, TimeUnit.SECONDS)
        }
        try {
            SwingUtilities.invokeAndWait {
                editor = JPanel()
                frame = JFrame("Chat recap overlay playback test").apply {
                    add(editor); setSize(440, 740); isVisible = true
                }
                overlay = ChatRecapOverlay(media, { completed = true; overlay.hide() }, { url ->
                    SystemBreakPage(url, ownsPlaybackControls = true).also { page = it }
                })
                transition.show("recap", editor) { overlay.showOver(editor) }
            }
            var loaded = false
            repeat(20) {
                if (!loaded) {
                    Thread.sleep(500)
                    loaded = runCatching { eval("return document.getElementById('voice') && document.getElementById('voice').readyState >= 2 && document.getElementById('background').readyState >= 2 ? 1 : 0;") == "1" }.getOrDefault(false)
                }
            }
            check(loaded) { "Recap voice/page did not load" }
            check(eval("const r=document.querySelector('.reel').getBoundingClientRect(); return Math.abs(r.width/r.height-9/16)<0.01 ? 1 : 0;") == "1")
            check(eval("return getComputedStyle(document.body).backgroundColor === 'rgb(0, 0, 0)' ? 1 : 0;") == "1")
            println("PASS: native recap mounts in the real layered overlay after the fade")
            check(eval("return !voice.controls && getComputedStyle(voice).display === 'none' ? 1 : 0;") == "1")
            println("PASS: audio timeline stays hidden")
            // No click and no muting: exercise real automatic narration playback.
            var playing = false
            repeat(20) {
                if (!playing) {
                    Thread.sleep(500)
                    playing = eval("return !voice.paused && voice.currentTime > 0.1 && !video.paused && video.currentTime > 0.1 ? 1 : 0;") == "1"
                }
            }
            check(playing) { "Voice and online stock video did not play together" }
            println("PASS: native browser autoplays unmuted narration and footage without a click")
            SwingUtilities.invokeAndWait { overlay.hide() }
            Thread.sleep(300)
            check(eval("return voice.paused && video.paused ? 1 : 0;") == "1")
            SwingUtilities.invokeAndWait { overlay.showOver(editor) }
            Thread.sleep(500)
            check(eval("return !voice.paused && !video.paused ? 1 : 0;") == "1")
            println("PASS: hide/show pauses and resumes recap playback")
            check(eval("return !voice.loop ? 1 : 0;") == "1")
            eval("voice.currentTime=voice.duration-0.2; return 1;")
            repeat(20) { if (!completed) Thread.sleep(250) }
            check(completed) { "Narration ended without closing the recap" }
            check(eval("return voice.paused && video.paused ? 1 : 0;") == "1")
            println("PASS: narration end automatically closes the recap and stops both tracks")
            Thread.sleep(1000)
        } finally {
            SwingUtilities.invokeAndWait { transition.dispose(); overlay.dispose(); frame.dispose() }
            media.close()
        }
        kotlin.system.exitProcess(0)
    }
}
