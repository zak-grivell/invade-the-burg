package com.github.zakgrivell.invadetheburg.services

import java.awt.Component
import java.awt.Container
import kotlinx.coroutines.flow.StateFlow

internal data class ChatMessage(val id: String, val role: String, val text: String, val completed: Boolean = true)
internal data class ChatTurn(val id: String, val prompts: List<String>, val responses: List<String>)

/** Only the latest completed turn, including follow-up prompts, belongs in the recap. */
internal fun latestChatTurn(messages: List<ChatMessage>): ChatTurn? {
    val end = messages.indexOfLast { it.role in setOf("assistant", "failure") && it.completed && it.text.isNotBlank() }
    if (end < 0 || end != messages.lastIndex) return null
    val start = (end - 1 downTo 0).firstOrNull {
        messages[it].role in setOf("assistant", "failure") && messages[it].completed
    }?.plus(1) ?: 0
    val turn = messages.subList(start, end + 1)
    return ChatTurn(messages[end].id, turn.filter { it.role == "user" }.map { it.text },
        turn.filter { it.role in setOf("assistant", "failure") }.map { it.text })
}

/** Reads Air's native transcript model, never another editor, repository, tool output or reasoning. */
internal object AirChatContext {
    private const val PAGE = "com.intellij.air.frontend.session.view.gui.ui.ChatSessionPagePanel"

    fun read(editor: Component): ChatTurn? {
        val page = findPage(editor) ?: return null
        val model = page.javaClass.getDeclaredField("viewModel").apply { isAccessible = true }.get(page) ?: return null
        return latestChatTurn(readModel(model))
    }

    private fun findPage(component: Component): Component? {
        if (component.javaClass.name == PAGE) return component
        return (component as? Container)?.components?.firstNotNullOfOrNull(::findPage)
    }

    internal fun readModel(model: Any): List<ChatMessage> {
        val rows = value(call(model, "getMessageViewModels")) as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            row ?: return@mapNotNull null
            if (value(call(row, "isReverted")) == true) return@mapNotNull null
            val id = call(row, "getRenderRowId") as? String ?: return@mapNotNull null
            when (row.javaClass.simpleName) {
                "PromptViewModel" -> ChatMessage(id, "user", value(call(row, "getPrompt")) as? String ?: "")
                "AgentMessageViewModel" -> {
                    val response = call(row, "response") ?: return@mapNotNull null
                    ChatMessage(id, "assistant", call(response, "getMarkdown") as? String ?: "", call(row, "getTurnCompleted") == true)
                }
                "FailureMessageViewModel" -> ChatMessage(id, "failure", "The agent reported an error: " + (value(call(row, "getMessage")) as? String ?: "Unknown error."))
                else -> null
            }
        }
    }

    private fun call(target: Any, method: String): Any? = target.javaClass.getMethod(method).invoke(target)
    // ReadonlyStateFlow is package-private: reflecting getValue on its concrete class fails.
    // Invoke the public interface instead, as normal Kotlin coroutine consumers do.
    private fun value(flow: Any?): Any? = (flow as? StateFlow<*>)?.value
}

internal fun spokenText(markdown: String): String = markdown
    .replace(Regex("(?s)```.*?```|~~~.*?~~~"), " ")
    .replace(Regex("!?\\[([^]\\n]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), "link")
    .replace(Regex("(?m)^\\s*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+|>\\s*)"), "")
    // Common credential formats must never be sent to the speech provider or read aloud.
    .replace(Regex("\\b(?:sk[_-]|ghp_|github_pat_)[A-Za-z0-9_-]{12,}"), "redacted credential")
    .replace(Regex("(?i)(api[ _-]?key|password|token)\\s*[:=]\\s*[^\\s,;]+"), "$1 redacted")
    .replace(Regex("[`*_~]"), "")
    .replace(Regex("\\s+"), " ").trim()

/** Read the complete final answer, without a generated introduction or user-prompt narration. */
internal fun chatRecap(turn: ChatTurn): String = spokenText(turn.responses.joinToString("\n")).also {
    require(it.isNotBlank()) { "The completed chat turn contains no spoken response yet." }
}

/** Preserve every character while keeping requests below ElevenLabs' per-request text limit. */
internal fun speechChunks(text: String, limit: Int = 8000): List<String> {
    val chunks = mutableListOf<String>()
    var remaining = text
    while (remaining.length > limit) {
        val boundary = remaining.lastIndexOf(' ', limit).takeIf { it > 0 }?.plus(1) ?: limit
        chunks.add(remaining.take(boundary))
        remaining = remaining.drop(boundary)
    }
    if (remaining.isNotEmpty()) chunks.add(remaining)
    return chunks
}
