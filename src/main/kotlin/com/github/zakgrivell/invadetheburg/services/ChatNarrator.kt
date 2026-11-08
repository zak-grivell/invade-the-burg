package com.github.zakgrivell.invadetheburg.services

import com.github.zakgrivell.invadetheburg.settings.ReelSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import com.intellij.openapi.application.ApplicationManager
import com.github.zakgrivell.invadetheburg.settings.RecapMode
import com.github.zakgrivell.invadetheburg.ui.RecapMedia

/** One completion per observed processing cycle; changing tabs is not a completion. */
internal class CompletionTracker {
    private val running = mutableSetOf<String>()
    fun update(pane: PaneActivity): Boolean {
        if (pane.processing) { running.add(pane.identity); return false }
        val wasRunning = running.remove(pane.identity)
        return wasRunning && pane.completed
    }
    fun retain(identities: Set<String>) { running.retainAll(identities) }
}

internal class ChatNarrator(private val project: Project) : Disposable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Reel Break narrator").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val config = Path.of(System.getProperty("user.home"), ".config", "reel-break")

    fun completed(turn: ChatTurn, stillWanted: () -> Boolean, onUnavailable: () -> Unit = {}, onReady: (RecapMedia) -> Unit) {
        val settings = service<ReelSettings>()
        if (settings.recapMode == RecapMode.NEVER || closed) return
        val configuredVoice = settings.narrationVoiceId
        val videoSource = settings.recapVideoSource
        val autoPlay = true // Both Always and an accepted recap offer explicitly request playback.
        executor.submit {
            try {
                if (closed || service<ReelSettings>().recapMode == RecapMode.NEVER) return@submit
                val voice = configuredVoice.ifBlank {
                    System.getenv("ELEVENLABS_VOICE_ID") ?: readConfig("elevenlabs-voice-id")
                }.trim()
                check(voice.matches(Regex("[A-Za-z0-9_-]+"))) { "Set your Peter Griffin ElevenLabs voice ID in Settings > Tools > Reel Break." }
                val key = System.getenv("ELEVENLABS_API_KEY") ?: readConfig("elevenlabs-key")
                check(key.isNotBlank()) { "Configure an ElevenLabs API key in ELEVENLABS_API_KEY or ~/.config/reel-break/elevenlabs-key." }
                val text = chatRecap(turn)
                if (closed) return@submit
                val audio = ByteArrayOutputStream()
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                for (chunk in speechChunks(text)) {
                if (closed || service<ReelSettings>().recapMode == RecapMode.NEVER) return@submit
                val request = HttpRequest.newBuilder(URI("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=pcm_24000"))
                    .timeout(Duration.ofSeconds(45)).header("xi-api-key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(mapOf(
                        "text" to chunk, "model_id" to "eleven_multilingual_v2",
                        "voice_settings" to mapOf("stability" to 0.4, "similarity_boost" to 0.8, "style" to 0.5, "speed" to 1.12)
                    )))).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                check(response.statusCode() == 200) { "ElevenLabs returned HTTP ${response.statusCode()}. Check your API key, voice access and credit balance." }
                audio.write(response.body())
                }
                if (!closed) {
                    val media = RecapMedia(text, audio.toByteArray(), videoSource, autoPlay)
                    ApplicationManager.getApplication().invokeLater {
                        if (closed || project.isDisposed || service<ReelSettings>().recapMode == RecapMode.NEVER || !stillWanted()) media.close()
                        else onReady(media)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                ApplicationManager.getApplication().invokeLater { if (!closed && !project.isDisposed) onUnavailable() }
                if (!closed && !project.isDisposed) NotificationGroupManager.getInstance()
                    .getNotificationGroup("Reel Break Narration")
                    .createNotification("Chat recap unavailable", when (error) {
                        is IllegalStateException -> error.message ?: "Narration failed."
                        else -> "Could not generate or play the chat recap. Check your network and audio output."
                    }, NotificationType.WARNING).notify(project)
            }
        }
    }

    fun unavailable() {
        if (!closed && !project.isDisposed && service<ReelSettings>().recapMode == RecapMode.ALWAYS) NotificationGroupManager.getInstance()
            .getNotificationGroup("Reel Break Narration")
            .createNotification("Chat recap unavailable", "Could not read the completed turn from this Air pane. Recaps require Air's native chat transcript.", NotificationType.WARNING).notify(project)
    }

    private fun readConfig(name: String): String = config.resolve(name).let {
        if (Files.isRegularFile(it)) Files.readString(it).trim() else ""
    }
    override fun dispose() {
        closed = true
        executor.shutdownNow()
    }
}
