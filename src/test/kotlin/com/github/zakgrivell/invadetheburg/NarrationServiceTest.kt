package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.narrationRequest
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NarrationServiceTest {
    @Test fun `production speech requests use Heroku without client credentials`() {
        val request = narrationRequest("voice_123", "Test response")
        assertEquals("https", request.uri().scheme)
        assertEquals("hack-the-burg-elevenlabs-eec39ae58d6f.herokuapp.com", request.uri().host)
        assertEquals("/v1/text-to-speech/voice_123", request.uri().path)
        assertEquals("output_format=pcm_24000", request.uri().query)
        assertEquals("POST", request.method())
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null))
        assertFalse(request.headers().firstValue("xi-api-key").isPresent)
        assertFalse(request.headers().firstValue("Authorization").isPresent)
    }

    @Test fun `proxy receives speech JSON without credentials and returns unchanged PCM audio`() {
        data class Received(val path: String, val headers: Set<String>, val body: String)
        val received = CompletableFuture<Received>()
        val pcm = byteArrayOf(0, 1, 127, -128, -1, 0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received.complete(Received(exchange.requestURI.toString(), exchange.requestHeaders.keys.map { it.lowercase() }.toSet(),
                exchange.requestBody.use { String(it.readAllBytes(), Charsets.UTF_8) }))
            exchange.responseHeaders.add("Content-Type", "audio/pcm")
            exchange.sendResponseHeaders(200, pcm.size.toLong())
            exchange.responseBody.use { it.write(pcm) }
        }
        server.start()
        try {
            val text = "A response with \"quotes\", a newline\nand café 🎉."
            val request = narrationRequest("voice_123", text, URI("http://127.0.0.1:${server.address.port}"))
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, response.statusCode())
            assertArrayEquals(pcm, response.body())
            val sent = received.get(1, TimeUnit.SECONDS)
            assertEquals("/v1/text-to-speech/voice_123?output_format=pcm_24000", sent.path)
            assertFalse("xi-api-key" in sent.headers)
            assertFalse("authorization" in sent.headers)
            val json = JsonParser.parseString(sent.body).asJsonObject
            assertEquals(text, json["text"].asString)
            assertEquals("eleven_multilingual_v2", json["model_id"].asString)
            assertTrue(json["voice_settings"].isJsonObject)
        } finally { server.stop(0) }
    }
}
