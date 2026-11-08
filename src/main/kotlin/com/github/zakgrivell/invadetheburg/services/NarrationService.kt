package com.github.zakgrivell.invadetheburg.services

import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** The Heroku proxy holds the ElevenLabs credential; no local credential is read or sent. */
internal fun narrationRequest(
    voice: String,
    text: String,
    baseUri: URI = URI("https://hack-the-burg-elevenlabs-eec39ae58d6f.herokuapp.com")
): HttpRequest = HttpRequest.newBuilder(baseUri.resolve("/v1/text-to-speech/$voice?output_format=pcm_24000"))
    .timeout(Duration.ofSeconds(45))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(Gson().toJson(mapOf(
        "text" to text,
        "model_id" to "eleven_multilingual_v2",
        "voice_settings" to mapOf("stability" to 0.4, "similarity_boost" to 0.8, "style" to 0.5, "speed" to 1.12)
    ))))
    .build()
