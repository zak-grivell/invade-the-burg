# Reel Break

Shows a configurable website **over the active AI pane** while the agent in your **currently selected JetBrains Air pane** is processing a prompt. As soon as the pane stops processing—including completion, cancellation, failure, or waiting for input—the overlay hides while keeping the browser and page state alive.

## Install

Install `build/distributions/Reel Break-1.2.2.zip` using **Settings → Plugins → ⚙ → Install Plugin from Disk**. Restart if requested. Continue using your existing Codex, Claude, Junie, or other agent pane in Air. No custom agent, Python, command wrapper, or agent configuration is required.

Requires a JetBrains 2026.3 IDE with Air. Choose the page in **Settings → Tools → Reel Break → URL**, or use the overlay’s **Settings** button. The default is Instagram Reels; LeetCode and other HTTP/HTTPS sites are supported.

## Operating systems

The same plugin ZIP bundles native browser binaries for **Windows, macOS, and Linux**, on **x86-64 and ARM64**. It does not depend on JetBrains’ JCEF codecs.

| System | Browser | Runtime requirements |
| --- | --- | --- |
| macOS | WKWebView | Included with the OS. H.264/AAC playback and page retention have been tested locally. |
| Windows 10/11 | Edge WebView2 | Install the [WebView2 Evergreen Runtime](https://developer.microsoft.com/microsoft-edge/webview2/) for your architecture if missing. |
| Linux | WebKitGTK, lightweight renderer | glibc 2.31+, WebKitGTK 4.1 (or 4.0), GStreamer media codecs, X11/XWayland. Native Wayland without XWayland is not supported. |

On Ubuntu 24.04+/Debian 13+ with WebKitGTK 4.1:

```sh
sudo apt install libwebkit2gtk-4.1-0 gstreamer1.0-libav gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
```

Under Wayland, launch the IDE with `GDK_BACKEND=x11` and ensure XWayland provides `DISPLAY`. Other Linux distributions use different package names; install the matching WebKitGTK and H.264/AAC GStreamer packages. Use glibc 2.31+ with a compatible WebKitGTK runtime; older distributions may require a native-library rebuild or upgrade.

Missing native libraries, unsupported display configurations, and runtime load errors show setup instructions. A missing JavaScript reply never disposes an already opened browser. No runtime is silently downloaded or installed by the plugin. Instagram login, autoplay, and video availability still depend on Instagram and the OS browser. Native browser cookies are separate from any previous JCEF login.

Windows/Linux execution is covered by the `Native browser compatibility` CI matrix, including actual H.264 playback, hidden-media pause, document retention beyond 20 seconds with dropped readiness replies, and repeated prompt cycles. These jobs must pass on their target runners before treating those platforms as verified; they cannot be run natively on this Mac.

## Behavior

Use **Settings → Tools → Reel Break → Setup** to open the entered website and sign in without running an agent request. Close the setup window with **Done**, and use **Apply** or **OK** to save any URL changes.

Manage **Favourites** in the same settings page: add a row, enter a name and complete website URL, and click **Apply** or **OK**. **Setup selected** opens the selected favourite for sign-in. Use the website dropdown at the top of the break overlay to switch between saved favourites. Your selection becomes the URL for future breaks. Switching navigates the existing browser, so the previous website's page position may be lost.

- Reads the selected Air editor pane's live `PROCESSING` activity every 200 ms on the UI thread. Uses Air's native state, not text matching or guesses based on elapsed time.
- Switching to a normal editor or an idle session hides the page. Switching to another processing session follows that pane.
- Press **Back to work** to dismiss the current break. The next processing turn can open it again.
- While the agent is still working, press **Back to Reels** in the bottom-right corner of the chat pane to reopen the same page.
- The overlay fills the selected AI editor pane, follows pane resizing, and leaves the original editor layout intact. Hidden overlays remain attached to the window so the native browser peer is preserved.
- Retains the same browser between prompts, preserving navigation, scroll position, and in-page work until the project or IDE closes.
- Pushes browser visibility directly on lifecycle events, without a JavaScript polling timer. Pauses HTML video/audio when hidden and resumes previously playing media when shown. Failed playback attempts remain available through **Play / Retry video**; browser autoplay policy may require clicking that button inside the page. Web Audio and site-specific players may behave differently.
- On Instagram Reels, automatically starts the largest visible video when scrolling or when Instagram swaps its source. If autoplay with sound is blocked, retries muted; use Instagram’s sound control to enable audio. Background Reels are excluded. After scrolling settles, waits for video data and makes at most four automatic attempts per selected Reel, recovering interrupted playback while preserving explicit click/keyboard pauses. Playback failures are kept in a bounded in-page diagnostic history and logged without website URLs. A stalled playback attempt releases its retry lock after eight seconds.
- Browser startup failures retain their underlying exception in the IDE logs and show the error beside setup instructions.
- Uses SwingWebView 1.13.0, including its Linux media improvements. Preloads native libraries off the IDE UI thread and offers **Retry browser** if initialization fails.
- Changing the configured URL navigates the retained browser on the next break (or immediately if a break is visible).
- Disposes the browser only when the project closes or the plugin unloads.
- Does not inspect prompts, change agent commands, or write session metadata to disk.
- The Air adapter uses reflection to read the pane's public methods because its classes live in private IDE modules. This version targets the inspected 2026.3 Air implementation; incompatible API changes fail closed.

## Development

**Settings → Tools → Reel Break → Preview multiple agents** opens an interactive mockup with sample Codex, Claude, and Junie sessions. Select agents, simulate working/input-needed/completed states, and toggle between sample chat and a break placeholder using saved favourites. This preview does not connect to agents, open websites, or change settings. Actual break detection continues to follow the selected Air pane.

```sh
JAVA_HOME="/path/to/jdk25" ./gradlew -PlocalPlatformPath="/path/to/IDE/Contents" test prepareSandbox buildPlugin verifyPluginStructure
```

Without `localPlatformPath`, Gradle downloads IntelliJ IDEA 2026.3. Tests cover activity mapping, incompatible panes, completion, manual dismissal, switching sessions, browser retention, and URL settings. Run `node tests/media-visibility.test.cjs` to check media pause/resume behavior. The opt-in `smokeTestMedia` Gradle task verifies actual platform-native H.264/AAC decoding and hide/show persistence in a separate test window. Download the W3C Sintel sample to `build/media-smoke/h264-aac.mp4` before running it. Instagram login still requires your account; the new system browser has separate cookies from JCEF, so sign in again once.
