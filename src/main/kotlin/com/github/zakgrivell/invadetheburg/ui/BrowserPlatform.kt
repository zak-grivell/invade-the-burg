package com.github.zakgrivell.invadetheburg.ui

import ca.weblite.webview.swing.WebViewComponent
import java.util.Locale

internal enum class BrowserPlatform(val engine: String, val mode: WebViewComponent.Mode, val setup: String) {
    MAC("WebKit", WebViewComponent.Mode.HEAVYWEIGHT, "macOS includes WebKit. Restart the IDE if the browser cannot start."),
    WINDOWS("WebView2", WebViewComponent.Mode.HEAVYWEIGHT,
        "Install Microsoft Edge WebView2 Evergreen Runtime for your CPU architecture, then restart the IDE.\nhttps://developer.microsoft.com/microsoft-edge/webview2/"),
    LINUX("WebKitGTK", WebViewComponent.Mode.LIGHTWEIGHT,
        "Install WebKitGTK 4.1 (or 4.0) and GStreamer H.264/AAC codecs.\nUbuntu/Debian: sudo apt install libwebkit2gtk-4.1-0 gstreamer1.0-libav gstreamer1.0-plugins-good gstreamer1.0-plugins-bad\nRequires glibc 2.31+ and X11/XWayland. Under Wayland, launch the IDE with GDK_BACKEND=x11 and an available DISPLAY.");

    fun environmentError(environment: Map<String, String>): String? {
        if (this != LINUX) return null
        if (environment["DISPLAY"].isNullOrBlank()) return "An X11/XWayland DISPLAY is required.\n$setup"
        if (!environment["WAYLAND_DISPLAY"].isNullOrBlank() && environment["GDK_BACKEND"]?.split(',')?.firstOrNull() != "x11") {
            return "Launch the IDE with GDK_BACKEND=x11 to use WebKitGTK under Wayland.\n$setup"
        }
        return null
    }

    fun nativeResource(arch: String): String? {
        val suffix = when (arch.lowercase(Locale.ROOT)) {
            "amd64", "x86_64" -> "64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
        return when (this) {
            MAC -> "/osx_$suffix/libwebview.dylib"
            WINDOWS -> "/windows_$suffix/webview.dll"
            LINUX -> "/linux_$suffix/libwebview.so"
        }
    }

    companion object {
        fun detect(os: String): BrowserPlatform? {
            val name = os.lowercase(Locale.ROOT)
            return when {
                name.startsWith("mac") || name == "darwin" -> MAC
                name.startsWith("windows") -> WINDOWS
                name.startsWith("linux") -> LINUX
                else -> null
            }
        }
        fun current() = detect(System.getProperty("os.name", ""))
    }
}
