package com.github.zakgrivell.invadetheburg.ui

import java.util.Base64
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors

internal const val DEFAULT_RECAP_VIDEO = "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"

internal fun normalizeRecapVideo(value: String): String {
    val source = value.trim()
    if (source.startsWith("https://") || source.startsWith("http://")) {
        require(!runCatching { URI(source).host }.getOrNull().isNullOrBlank()) { "Enter a complete video URL or an absolute local video path." }
    } else {
        require(Path.of(source).isAbsolute && Files.isRegularFile(Path.of(source))) { "Choose an existing local video file or enter an HTTP/HTTPS video URL." }
    }
    return source
}

internal fun pcmWave(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
    putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(pcm.size); put(pcm)
}.array()

/** In-memory page/audio, with access limited to an unguessable loopback URL. */
internal class RecapMedia(private val text: String, audio: ByteArray, videoSource: String, private val autoPlay: Boolean = true) : AutoCloseable {
    private val token = UUID.randomUUID().toString()
    private val audio = pcmWave(audio)
    private val video = normalizeRecapVideo(videoSource)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val workers = Executors.newFixedThreadPool(4) { task -> Thread(task, "Reel recap media").apply { isDaemon = true } }
    val url: String get() = "http://127.0.0.1:${server.address.port}/$token/index.html"
    init {
        server.executor = workers
        server.createContext("/$token/") { exchange ->
            try {
                val route = exchange.requestURI.path.removePrefix("/$token/")
                val localVideo = if (route == "background.mp4" && !video.startsWith("http")) Path.of(video) else null
                val (type, data) = when (route) {
                    "index.html" -> "text/html; charset=utf-8" to recapVideoHtml(text, if (video.startsWith("http")) video else "background.mp4", autoPlay).toByteArray()
                    "voice.wav" -> "audio/wav" to this.audio
                    "background.mp4" -> if (localVideo != null) (if (video.lowercase().endsWith(".webm")) "video/webm" else "video/mp4") to null else null to null
                    else -> null to null
                }
                if ((data == null && localVideo == null) || exchange.requestMethod !in setOf("GET", "HEAD")) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.set("Content-Type", type)
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
                    exchange.responseHeaders.set("Accept-Ranges", "bytes")
                    val size = localVideo?.let(Files::size) ?: data!!.size.toLong()
                    val range = exchange.requestHeaders.getFirst("Range")
                    val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                    val start = match?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val end = (match?.groupValues?.get(2)?.toLongOrNull() ?: (size - 1)).coerceAtMost((size - 1))
                    if (range != null && (match == null || start > end || start >= size)) {
                        exchange.responseHeaders.set("Content-Range", "bytes */${size}")
                        exchange.sendResponseHeaders(416, -1)
                    } else {
                        val length = end - start + 1
                        if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${size}")
                        exchange.responseHeaders.set("Content-Length", length.toString())
                        exchange.sendResponseHeaders(if (range == null) 200 else 206, if (exchange.requestMethod == "HEAD") -1 else length)
                        if (exchange.requestMethod != "HEAD") {
                            if (localVideo == null) exchange.responseBody.write(data!!, start.toInt(), length.toInt())
                            else Files.newByteChannel(localVideo).use { channel ->
                                channel.position(start)
                                val buffer = ByteBuffer.allocate(64 * 1024)
                                var remaining = length
                                while (remaining > 0) {
                                    buffer.clear(); buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                                    val count = channel.read(buffer)
                                    if (count < 0) break
                                    exchange.responseBody.write(buffer.array(), 0, count)
                                    remaining -= count
                                }
                            }
                        }
                    }
                }
            } finally { exchange.close() }
        }
        server.start()
    }
    override fun close() { server.stop(0); workers.shutdownNow() }
}

internal fun recapVideoHtml(text: String, video: String, autoPlay: Boolean = true): String {
    val template = RecapMedia::class.java.getResource("/browser/chat-recap.html")!!.readText()
    fun encoded(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    return template.replace("/*RECAP_DATA*/", "{autoPlay:$autoPlay,text:decodeBase64('${encoded(text)}'),video:decodeBase64('${encoded(video)}')}")
}

