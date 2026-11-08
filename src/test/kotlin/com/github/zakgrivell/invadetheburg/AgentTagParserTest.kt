package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.services.tags.AgentTag
import com.github.zakgrivell.invadetheburg.services.tags.AgentTagParser
import com.github.zakgrivell.invadetheburg.services.tags.DEFAULT_AGENT_TAGS
import com.github.zakgrivell.invadetheburg.services.tags.agentTagMessage
import org.junit.Assert.*
import org.junit.Test

class AgentTagParserTest {
    private val allowed = AgentTagParser.parseTagList(DEFAULT_AGENT_TAGS)

    @Test fun `parses a valid tag and its question`() {
        val text = "Done with the layout.\n[[TAG:taste]] Should the empty state use an illustration or plain text?"
        assertEquals(AgentTag("taste", "Should the empty state use an illustration or plain text?"), AgentTagParser.parse(text, allowed))
    }

    @Test fun `tag name is case-insensitive and normalised to the configured spelling`() {
        assertEquals(AgentTag("blocked", "Need the API key."), AgentTagParser.parse("[[TAG:BLOCKED]] Need the API key.", allowed))
        assertEquals(AgentTag("review", "Look?"), AgentTagParser.parse("[[tag:Review]] Look?", allowed))
    }

    @Test fun `unknown tag is untagged`() {
        assertNull(AgentTagParser.parse("[[TAG:lunch]] Pizza?", allowed))
    }

    @Test fun `malformed markers are untagged and never throw`() {
        listOf(
            "[[TAG:taste] Missing bracket?", "[TAG:taste]] Missing bracket?", "[[TAG taste]] No colon?",
            "[[TAG:]] Empty name?", "[[TAG:ta ste]] Space in name?", "[[TAG:taste", "[[", "", "]]",
        ).forEach { assertNull(it, AgentTagParser.parse(it, allowed)) }
    }

    @Test fun `last marker wins when there are several`() {
        val text = "[[TAG:review]] First?\nMore text.\n[[TAG:direction]] Which approach?"
        assertEquals(AgentTag("direction", "Which approach?"), AgentTagParser.parse(text, allowed))
    }

    @Test fun `unknown last marker makes the message untagged`() {
        assertNull(AgentTagParser.parse("[[TAG:review]] First?\n[[TAG:lunch]] Pizza?", allowed))
    }

    @Test fun `no marker is untagged`() {
        assertNull(AgentTagParser.parse("All done, tests pass.", allowed))
    }

    @Test fun `tag with an empty question keeps an empty question`() {
        assertEquals(AgentTag("taste", ""), AgentTagParser.parse("Thoughts?\n[[TAG:taste]]", allowed))
        assertEquals(AgentTag("taste", ""), AgentTagParser.parse("[[TAG:taste]]   \nnext line is not the question", allowed))
    }

    @Test fun `question is only the rest of the marker line`() {
        assertEquals(AgentTag("blocked", "Can I delete it?"), AgentTagParser.parse("[[TAG:blocked]]  Can I delete it?  \nThanks", allowed))
    }

    @Test fun `empty allowed list means nothing is tagged`() {
        assertNull(AgentTagParser.parse("[[TAG:taste]] Q?", emptyList()))
    }

    @Test fun `parses comma-separated tag lists`() {
        assertEquals(listOf("blocked", "direction", "taste", "review"), allowed)
        assertEquals(listOf("a", "b", "c"), AgentTagParser.parseTagList(" A, b ,,c, a "))
        assertEquals(emptyList<String>(), AgentTagParser.parseTagList(" , "))
    }

    @Test fun `label formats the name in upper case with the question`() {
        assertEquals("TASTE: Which colour?", AgentTag("taste", "Which colour?").label)
        assertEquals("TASTE", AgentTag("taste", "").label)
    }

    @Test fun `balloon message prefixes the label`() {
        assertEquals("Agent needs you: TASTE: Which colour?", agentTagMessage(AgentTag("taste", "Which colour?")))
    }
}
