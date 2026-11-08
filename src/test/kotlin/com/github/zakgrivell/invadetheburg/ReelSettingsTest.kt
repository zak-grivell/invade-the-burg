package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.settings.DEFAULT_BREAK_URL
import com.github.zakgrivell.invadetheburg.settings.ReelSettings
import com.github.zakgrivell.invadetheburg.settings.normalizeBreakUrl
import com.github.zakgrivell.invadetheburg.settings.FavoriteWebsite
import org.junit.Assert.*
import org.junit.Test

class ReelSettingsTest {
    @org.junit.Test fun `recaps default to ask and preserve selected mode`() {
        val settings = com.github.zakgrivell.invadetheburg.settings.ReelSettings()
        org.junit.Assert.assertEquals(com.github.zakgrivell.invadetheburg.settings.RecapMode.ASK, settings.recapMode)
        com.github.zakgrivell.invadetheburg.settings.RecapMode.entries.forEach { mode ->
            settings.recapMode = mode
            settings.useEtaToTimeBreaks = false
            val restored = com.github.zakgrivell.invadetheburg.settings.ReelSettings()
            restored.loadState(settings.state)
            org.junit.Assert.assertEquals(mode, restored.recapMode)
            assertFalse("ETA preference must survive alongside recap mode", restored.useEtaToTimeBreaks)
        }
    }

    @Test fun `favorites persist with names and normalized URLs including empty lists`() {
        val settings = ReelSettings()
        settings.favorites = listOf(FavoriteWebsite(" Challenges ", " https://leetcode.com/problemset/ "))
        val restored = ReelSettings()
        restored.loadState(settings.state)
        assertEquals(listOf(FavoriteWebsite("Challenges", "https://leetcode.com/problemset/")), restored.favorites)
        settings.favorites = emptyList()
        restored.loadState(settings.state)
        assertTrue(restored.favorites.isEmpty())
    }
    @Test fun `invalid favorite edits leave saved favorites intact`() {
        val settings = ReelSettings()
        val original = settings.favorites
        listOf(
            listOf(FavoriteWebsite(" ", "https://example.com/")),
            listOf(FavoriteWebsite("Bad", "javascript:alert(1)")),
            listOf(FavoriteWebsite("One", DEFAULT_BREAK_URL), FavoriteWebsite("Two", DEFAULT_BREAK_URL))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { settings.favorites = invalid }
            assertEquals(original, settings.favorites)
        }
    }
    @Test fun `saved favorites discard invalid entries and duplicates without losing valid ones`() {
        val settings = ReelSettings()
        settings.loadState(ReelSettings.Options().apply {
            favorites = mutableListOf(
                FavoriteWebsite("Good", "https://example.com/"),
                FavoriteWebsite("Bad", "file:///tmp/file"),
                FavoriteWebsite("Duplicate", "https://example.com/")
            )
        })
        assertEquals(listOf(FavoriteWebsite("Good", "https://example.com/")), settings.favorites)
        val copy = settings.favorites
        copy[0].name = "Changed"
        assertEquals("Good", settings.favorites[0].name)
    }
    @Test fun `accepts full web addresses and preserves query and fragment`() {
        val url = "https://leetcode.com/problemset/?difficulty=EASY#problems"
        assertEquals(url, normalizeBreakUrl("  $url  "))
        assertEquals("http://localhost:8080/", normalizeBreakUrl("http://localhost:8080/"))
    }
    @Test fun `rejects incomplete or non web URLs`() {
        listOf("", "leetcode.com", "https://", "javascript:alert(1)", "file:///tmp/file", "https://bad host/").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeBreakUrl(it) }
        }
    }
    @Test fun `URL survives settings state round trip`() {
        val first = ReelSettings()
        first.url = "https://leetcode.com/problemset/"
        val restored = ReelSettings()
        restored.loadState(first.state)
        assertEquals(first.url, restored.url)
    }
    @Test fun `invalid saved configuration falls back to default`() {
        val settings = ReelSettings()
        settings.loadState(ReelSettings.Options().apply { url = "invalid" })
        assertEquals(DEFAULT_BREAK_URL, settings.url)
    }
    @Test fun `ETA break timing defaults on, persists, and old state without the field stays on`() {
        val settings = ReelSettings()
        assertTrue(settings.useEtaToTimeBreaks)
        settings.useEtaToTimeBreaks = false
        val restored = ReelSettings()
        restored.loadState(settings.state)
        assertFalse(restored.useEtaToTimeBreaks)
        restored.useEtaToTimeBreaks = true
        val again = ReelSettings()
        again.loadState(restored.state)
        assertTrue(again.useEtaToTimeBreaks)
        val old = ReelSettings()
        old.loadState(ReelSettings.Options())
        assertTrue(old.useEtaToTimeBreaks)
    }
}
