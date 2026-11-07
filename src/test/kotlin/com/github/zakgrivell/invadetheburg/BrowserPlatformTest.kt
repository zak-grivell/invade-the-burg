package com.github.zakgrivell.invadetheburg

import ca.weblite.webview.swing.WebViewComponent
import com.github.zakgrivell.invadetheburg.ui.BrowserPlatform
import org.junit.Assert.*
import org.junit.Test

class BrowserPlatformTest {
    @Test fun `selects all supported operating systems without confusing Darwin and Windows`() {
        assertEquals(BrowserPlatform.MAC, BrowserPlatform.detect("Mac OS X"))
        assertEquals(BrowserPlatform.MAC, BrowserPlatform.detect("Darwin"))
        assertEquals(BrowserPlatform.WINDOWS, BrowserPlatform.detect("Windows 11"))
        assertEquals(BrowserPlatform.WINDOWS, BrowserPlatform.detect("Windows 10"))
        assertEquals(BrowserPlatform.LINUX, BrowserPlatform.detect("Linux"))
        assertNull(BrowserPlatform.detect("FreeBSD"))
    }
    @Test fun `Linux uses lightweight rendering and other systems use heavyweight rendering`() {
        assertEquals(WebViewComponent.Mode.LIGHTWEIGHT, BrowserPlatform.LINUX.mode)
        assertEquals(WebViewComponent.Mode.HEAVYWEIGHT, BrowserPlatform.WINDOWS.mode)
        assertEquals(WebViewComponent.Mode.HEAVYWEIGHT, BrowserPlatform.MAC.mode)
    }
    @Test fun `native binaries are bundled for both CPU architectures on every OS`() {
        BrowserPlatform.entries.forEach { platform ->
            listOf("x86_64", "amd64", "aarch64", "arm64").forEach { arch ->
                val resource = platform.nativeResource(arch)
                assertNotNull(resource)
                assertNotNull("Missing $resource", WebViewComponent::class.java.getResource(resource!!))
            }
        }
    }
    @Test fun `missing X11 and unsupported Wayland configuration produce setup instructions`() {
        assertNotNull(BrowserPlatform.LINUX.environmentError(emptyMap()))
        assertNotNull(BrowserPlatform.LINUX.environmentError(mapOf("DISPLAY" to ":0", "WAYLAND_DISPLAY" to "wayland-0")))
        assertNull(BrowserPlatform.LINUX.environmentError(mapOf("DISPLAY" to ":0")))
        assertNull(BrowserPlatform.LINUX.environmentError(mapOf("DISPLAY" to ":0", "WAYLAND_DISPLAY" to "wayland-0", "GDK_BACKEND" to "x11")))
        assertNull(BrowserPlatform.WINDOWS.environmentError(emptyMap()))
        assertNull(BrowserPlatform.MAC.environmentError(emptyMap()))
    }
    @Test fun `unsupported architectures fail explicitly`() {
        BrowserPlatform.entries.forEach { assertNull(it.nativeResource("x86")) }
    }
    @Test fun `runtime instructions cover Windows runtime and Linux codecs and display requirements`() {
        assertTrue(BrowserPlatform.WINDOWS.setup.contains("WebView2"))
        assertTrue(BrowserPlatform.LINUX.setup.contains("WebKitGTK"))
        assertTrue(BrowserPlatform.LINUX.setup.contains("gstreamer1.0-libav"))
        assertTrue(BrowserPlatform.LINUX.setup.contains("X11"))
    }
}
