package com.github.zakgrivell.invadetheburg

import com.github.zakgrivell.invadetheburg.ui.BreakPage
import com.github.zakgrivell.invadetheburg.ui.RetainedPage
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JPanel

class RetainedPageTest {
    private class Page(var location: String) : BreakPage {
        override val component = JPanel()
        var loads = 0
        var disposals = 0
        val visibility = mutableListOf<Boolean>()
        override fun loadUrl(url: String) { location = url; loads++ }
        override fun setActive(active: Boolean) { visibility.add(active) }
        override fun dispose() { disposals++ }
    }
    @Test fun `completion hides without disposing or losing navigation`() {
        var creations = 0
        val page = Page("https://leetcode.com/problemset/")
        val retained = RetainedPage { creations++; page }
        assertSame(page, retained.show(page.location))
        page.location = "https://leetcode.com/problems/two-sum/"
        retained.hide()
        assertEquals(0, page.disposals)
        assertSame(page, retained.show("https://leetcode.com/problemset/"))
        assertEquals("https://leetcode.com/problems/two-sum/", page.location)
        assertEquals(0, page.loads)
        assertEquals(1, creations)
        assertEquals(listOf(true, false, true), page.visibility)
    }
    @Test fun `polling does not repeatedly reload or pause the page`() {
        val page = Page("https://example.com/")
        val retained = RetainedPage { page }
        repeat(10) { retained.show(page.location) }
        repeat(10) { retained.hide() }
        assertEquals(0, page.loads)
        assertEquals(listOf(true, false), page.visibility)
    }
    @Test fun `configuration change navigates the same browser once`() {
        val page = Page("https://example.com/")
        val retained = RetainedPage { page }
        retained.show(page.location)
        retained.hide()
        val newUrl = "https://leetcode.com/problemset/"
        assertSame(page, retained.show(newUrl))
        retained.show(newUrl)
        assertEquals(newUrl, page.location)
        assertEquals(1, page.loads)
    }
    @Test fun `project closure releases the retained browser once`() {
        val page = Page("https://example.com/")
        val retained = RetainedPage { page }
        retained.show(page.location)
        retained.dispose()
        retained.dispose()
        assertEquals(1, page.disposals)
        assertEquals(listOf(true, false), page.visibility)
    }
}
