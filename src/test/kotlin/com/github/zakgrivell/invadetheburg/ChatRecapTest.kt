package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRecapTest {
    @Test fun `recap follows conversation content without any repository`() {
        val turn = latestChatTurn(listOf(
            ChatMessage("u", "user", "Help plan a birthday party."),
            ChatMessage("a", "assistant", "Created a party plan with games and pizza. Still need the guest count.")
        ))!!
        val recap = chatRecap(turn)
        assertFalse(recap.contains("birthday party"))
        assertTrue(recap.contains("games and pizza"))
        assertTrue(recap.contains("Still need the guest count"))
        assertFalse(recap.contains("Git"))
    }
    @Test fun `only current turn and its follow up prompts are captured`() {
        val turn = latestChatTurn(listOf(
            ChatMessage("old-user", "user", "Old task"), ChatMessage("old-agent", "assistant", "Old result"),
            ChatMessage("u1", "user", "Fix the login"), ChatMessage("u2", "user", "Also add logout"),
            ChatMessage("a", "assistant", "Fixed login and added logout. Tests passed.")
        ))!!
        assertEquals(listOf("Fix the login", "Also add logout"), turn.prompts)
        assertFalse(chatRecap(turn).contains("Old result"))
        assertTrue(chatRecap(turn).contains("Tests passed"))
    }
    @Test fun `delayed final response cannot replay preceding turn`() {
        assertNull(latestChatTurn(listOf(
            ChatMessage("old", "assistant", "Already narrated"),
            ChatMessage("u", "user", "New task"),
            ChatMessage("a", "assistant", "Still working", false)
        )))
        assertNull(latestChatTurn(listOf(ChatMessage("old", "assistant", "Already narrated"), ChatMessage("u", "user", "New task"))))
    }
    @Test fun `failure reports stay failures`() {
        val turn = latestChatTurn(listOf(ChatMessage("u", "user", "Build it"), ChatMessage("f", "failure", "Build failed. Missing dependency.")))!!
        assertTrue(chatRecap(turn).contains("Build failed"))
        assertFalse(chatRecap(turn).contains("passed"))
    }
    @Test fun `speech excludes markup code links and credentials`() {
        val text = spokenText("# Result\n**Done**. [Report](https://example.com/a).\n```sh\necho private-content\n```\nKey sk_abcdefghijklmnopqrstuvwxyz and token=abc123.")
        assertFalse(text.contains("private-content"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(text.contains("abc123"))
        assertTrue(text.contains("Report"))
    }
    @Test fun `reads the entire final answer without prompts intro or truncation`() {
        val answer = "Updated the layout. " + "Some details were changed. ".repeat(100) + "Still need a production API key."
        val turn = ChatTurn("a", listOf("Do not narrate this prompt"), listOf(answer))
        assertEquals(answer, chatRecap(turn))
        assertFalse(chatRecap(turn).contains("quick chat recap"))
    }
    @Test fun `long responses are spoken in order without dropping text`() {
        val text = "An extended response. ".repeat(1500)
        val parts = speechChunks(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 8001 })
        assertEquals(text, parts.joinToString(""))
    }
    class PromptViewModel(private val id: String, private val text: String) {
        fun isReverted() = MutableStateFlow(false).asStateFlow()
        fun getRenderRowId() = id
        fun getPrompt() = MutableStateFlow(text).asStateFlow()
    }
    class Response(private val text: String) { fun getMarkdown() = text }
    class AgentMessageViewModel(private val id: String, private val text: String, private val done: Boolean, private val reverted: Boolean = false) {
        fun isReverted() = MutableStateFlow(reverted).asStateFlow()
        fun getRenderRowId() = id
        fun response() = Response(text)
        fun getTurnCompleted() = done
    }
    class Model(private val rows: List<Any>) { fun getMessageViewModels() = MutableStateFlow(rows).asStateFlow() }
    @Test fun `Air model adapter reads package private ReadonlyStateFlow and excludes reverted rows`() {
        // asStateFlow returns the same non-public ReadonlyStateFlow wrapper Air uses.
        val flowClass = Model(emptyList()).getMessageViewModels().javaClass
        assertEquals("kotlinx.coroutines.flow.ReadonlyStateFlow", flowClass.name)
        assertFalse(java.lang.reflect.Modifier.isPublic(flowClass.modifiers))
        val messages = AirChatContext.readModel(Model(listOf(
            PromptViewModel("u", "Explain the design"),
            AgentMessageViewModel("deleted", "Reverted result", true, true),
            AgentMessageViewModel("a", "Explained the architecture and tradeoffs.", true)
        )))
        assertEquals(2, messages.size)
        val recap = chatRecap(latestChatTurn(messages)!!)
        assertTrue(recap.contains("architecture and tradeoffs"))
        assertFalse(recap.contains("Reverted"))
    }
}
