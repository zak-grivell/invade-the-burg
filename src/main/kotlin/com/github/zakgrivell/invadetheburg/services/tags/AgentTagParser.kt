package com.github.zakgrivell.invadetheburg.services.tags

internal const val DEFAULT_AGENT_TAGS = "blocked,direction,taste,review"

/** Why an agent needs the developer. [name] is the configured (lower-case) spelling. */
internal data class AgentTag(val name: String, val question: String) {
    val label: String get() = name.uppercase() + if (question.isEmpty()) "" else ": $question"
}

internal fun agentTagMessage(tag: AgentTag): String = "Agent needs you: ${tag.label}"

/** Deterministic `[[TAG:<name>]] <one-line question>` parsing; anything unexpected is untagged. */
internal object AgentTagParser {
    private val MARKER = Regex("""\[\[TAG:([A-Za-z0-9_-]+)]]([^\r\n]*)""", RegexOption.IGNORE_CASE)

    /** The last well-formed marker decides; an unknown name there means untagged. */
    fun parse(text: String, allowedTags: List<String>): AgentTag? {
        val last = MARKER.findAll(text).lastOrNull() ?: return null
        val name = last.groupValues[1].lowercase()
        if (name !in allowedTags) return null
        return AgentTag(name, last.groupValues[2].trim())
    }

    fun parseTagList(value: String): List<String> =
        value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
}
