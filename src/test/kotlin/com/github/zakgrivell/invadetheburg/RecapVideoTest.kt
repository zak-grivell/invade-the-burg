package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class RecapVideoTest {
    @Test fun `recap serves playable wave audio and byte ranges only at its private URL`() {
        val pcm = ByteArray(48000)
        val client = HttpClient.newHttpClient()
        RecapMedia("Tests passed.", pcm, DEFAULT_RECAP_VIDEO).use { media ->
            val page = client.send(HttpRequest.newBuilder(URI(media.url)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, page.statusCode())
            assertTrue(page.body().contains(java.util.Base64.getEncoder().encodeToString("Tests passed.".toByteArray())))
            assertTrue(page.body().contains("<video"))
            assertEquals("no-referrer", page.headers().firstValue("Referrer-Policy").orElse(""))
            val audio = URI(media.url).resolve("voice.wav")
            val response = client.send(HttpRequest.newBuilder(audio).header("Range", "bytes=0-43").build(), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(206, response.statusCode())
            assertEquals(44, response.body().size)
            assertEquals("RIFF", String(response.body(), 0, 4))
            assertEquals(24000, ByteBuffer.wrap(response.body()).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
            val invalid = client.send(HttpRequest.newBuilder(audio).header("Range", "bytes=999999-").build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(416, invalid.statusCode())
            val wrong = client.send(HttpRequest.newBuilder(URI(media.url).resolve("../voice.wav")).build(), HttpResponse.BodyHandlers.discarding())
            assertEquals(404, wrong.statusCode())
        }
    }
    @Test fun `local gameplay file can replace stock video`() {
        val file = Files.createTempFile("recap-gameplay", ".webm")
        try {
            Files.write(file, byteArrayOf(1, 2, 3, 4))
            RecapMedia("Done", ByteArray(10), file.toString()).use { media ->
                val result = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(media.url).resolve("background.mp4")).header("Range", "bytes=1-2").build(), HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(206, result.statusCode())
                assertEquals("video/webm", result.headers().firstValue("Content-Type").orElse(""))
                assertArrayEquals(byteArrayOf(2, 3), result.body())
            }
        } finally { Files.delete(file) }
    }
    @Test fun `chat text cannot inject markup into the player`() {
        val html = recapVideoHtml("</script><img src=x onerror=alert(1)>", DEFAULT_RECAP_VIDEO)
        assertFalse(html.contains("</script><img"))
        assertTrue(html.contains("aspect-ratio:9/16"))
        assertTrue(html.contains("background:#000"))
        assertTrue(html.contains("voice.addEventListener('pause'"))
        assertTrue(html.contains("voice.addEventListener('ended'"))
    }
}
