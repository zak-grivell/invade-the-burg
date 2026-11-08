package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.*
import org.junit.Assert.*
import org.junit.Test

class ChatNarratorTest {
    @Test fun `narration uses hosted proxy without client credentials`() {
        val request = narrationRequest("test_voice-123", "Hello from Reel Break")
        assertEquals("https", request.uri().scheme)
        assertEquals("hack-the-burg-elevenlabs-eec39ae58d6f.herokuapp.com", request.uri().host)
        assertEquals("/v1/text-to-speech/test_voice-123", request.uri().path)
        assertEquals("output_format=pcm_24000", request.uri().query)
        assertEquals("POST", request.method())
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(""))
        assertFalse(request.headers().firstValue("xi-api-key").isPresent)
        assertFalse(request.headers().firstValue("Authorization").isPresent)
        assertTrue(request.bodyPublisher().isPresent)
    }
    @Test fun `narration rejects invalid voice paths`() {
        for (voice in listOf("", "../voices", "voice?redirect=other", "voice/other")) {
            assertThrows(IllegalStateException::class.java) { narrationRequest(voice, "Hello") }
        }
    }
    @Test fun `completion fires once and never on first idle observation`() {
        val tracker = CompletionTracker()
        assertFalse(tracker.update(PaneActivity("a", false, "READY")))
        assertFalse(tracker.update(PaneActivity("a", true)))
        assertTrue(tracker.update(PaneActivity("a", false, "READY")))
        assertFalse(tracker.update(PaneActivity("a", false, "READY")))
        assertFalse(tracker.update(PaneActivity("a", true)))
        assertTrue(tracker.update(PaneActivity("a", false, "READY")))
    }
    @Test fun `permission pause and closed panes do not narrate`() {
        val tracker = CompletionTracker()
        tracker.update(PaneActivity("a", true))
        assertFalse(tracker.update(PaneActivity("a", false, "NEEDS_INPUT")))
        assertFalse(tracker.update(PaneActivity("a", false, "READY")))
        tracker.update(PaneActivity("a", true))
        tracker.retain(emptySet())
        assertFalse(tracker.update(PaneActivity("a", false, "READY")))
    }
    @Test fun `background panes complete independently`() {
        val tracker = CompletionTracker()
        tracker.update(PaneActivity("a", true))
        tracker.update(PaneActivity("b", true))
        assertTrue(tracker.update(PaneActivity("a", false, "READY")))
        assertTrue(tracker.update(PaneActivity("b", false, "READY")))
    }
}
