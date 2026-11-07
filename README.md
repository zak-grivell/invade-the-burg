# invade-the-burg

Reel Break is a JetBrains 2026.3 plugin that shows a configurable website over the selected Air chat while its agent is working. The browser hides when the agent stops or needs input, retaining the page for the next prompt.

Licensed under the [GNU General Public License v3.0](LICENSE) (SPDX: `GPL-3.0-only`).

![Build](https://github.com/zak-grivell/invade-the-burg/workflows/Build/badge.svg)

## Installation and settings

Build the plugin and install the ZIP from `build/distributions` using **Settings → Plugins → ⚙ → Install Plugin from Disk**, then restart the IDE.

Use **Settings → Tools → Reel Break** to set the website URL, manage favourites, or open **Setup** to sign in. The overlay follows the selected Air pane automatically. **Back to work** dismisses it; **Back to Reels** returns to the retained page while the agent is working.

The plugin uses WebKit on macOS, WebView2 on Windows, and WebKitGTK on Linux. Windows needs the WebView2 Runtime; Linux needs WebKitGTK, GStreamer H.264/AAC codecs, and X11/XWayland. Native binaries are bundled for x86-64 and ARM64.

## Development

Use JDK 25 and the Gradle wrapper:

```sh
./gradlew test buildPlugin verifyPluginStructure
node tests/media-visibility.test.cjs
```

To use an installed IDE instead of downloading one, add `-PlocalPlatformPath="/path/to/IDE/Contents"` to the Gradle command. The `.run` configurations provide IDE launch, test, and verification tasks. GitHub workflows run builds, native browser checks, and releases; `CHANGELOG.md` supplies release notes.

## Browser playback

Reel Break leaves Reel selection, scrolling, and playback timing to Instagram's player. It no longer pauses videos based on their relative size or starts videos in response to scroll/readiness timers. A rejected video `play()` request on any configured website gets one muted inline fallback only for `NotAllowedError`; website cancellations retain their original promise behavior. Explicit site/user pauses cancel retained resumes. Hidden pages retain their document and pause media; showing the page resumes previously playing media only when the source is unchanged and still visible. A slow play request is never aborted by a plugin watchdog.

Validation: `node tests/media-visibility.test.cjs`; the `smokeTestMedia` Gradle task exercises native H.264 playback, 12 consecutive site-owned scroll-snap handoffs, manual pause, and repeated hide/show cycles. Native Windows/Linux execution still requires their respective runners.

Decoder/network failures are captured even when native autoplay never calls JavaScript `play()`. **Retry source** reloads failed direct media and selects a declared alternative the engine reports as playable. It never resets a site-owned blob/MediaSource stream automatically. **Reload page** restarts a stuck website; **Open in browser** opens the configured website in your default browser when the embedded engine lacks a codec (sign-in may be separate). The plugin cannot install missing OS decoders. New documents start paused until the IDE supplies the pane's actual visibility.

The native **Retry video** control also runs recovery through the host browser call. For a manual IDE decoder test, run `node tests/ide-media-fixture.cjs /path/to/h264.mp4`, open its printed URL using Setup, then click Retry video. The fixture starts with invalid media and a valid MP4 alternative; verify that error 4 clears and playback time advances. Automated playback coverage includes 19 JavaScript scenarios and the native smoke task above.

## Chat response videos

**Settings > Tools > Reel Break > Show chat recap** offers **Always**, **Never**, and **Ask**. The default is **Ask**, including when upgrading from older automatic playback versions. Ask shows a “Want a recap?” toast after a turn completes; **Watch recap** opens that specific chat and starts generation. No speech request or player starts before that click. Always opens the player automatically in the originating pane; Never suppresses recap offers, generation and playback.

The player fills the chat editor with a centered **9:16 portrait video and black sides**, with captions over the footage. It is mounted in that pane, without a floating window. **Back to chat** dismisses it. Switching tabs pauses hidden playback; closing the pane, starting another turn, or selecting Never removes it. Multiple panes keep their own response association.

The ElevenLabs voice reads the **complete final agent response** directly. No generated introduction, user request, summarizing, sentence selection or word limit is added. Markdown formatting, code blocks and common credential patterns are removed for speech. Long responses are split across speech requests and reassembled in order. Phrase caption timing is estimated.

Set the Peter Griffin voice ID in Reel Break settings, `ELEVENLABS_VOICE_ID`, or `~/.config/reel-break/elevenlabs-voice-id`. Set `ELEVENLABS_API_KEY` in the IDE environment or `~/.config/reel-break/elevenlabs-key` (permissions `0600`). Spoken response text is sent to ElevenLabs and consumes API credits. Private reasoning, raw tool output, attachments, reverted messages and older turns are excluded.

The temporary background is MDN's [CC0 flower footage](https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4). **Recap video URL or file** accepts a direct HTTP/HTTPS video URL or an absolute path to a local gameplay MP4. Local media is served through a random-token loopback URL; chat text/audio remain in memory. Closing the player stops its browser and media server.

Validation: `test` covers speech text, defaults/persistence, transcript reading, media ranges and local video replacement. `smokeTestRecapVideo` checks native voice/video playback, portrait dimensions, black surroundings, pause and end behavior.

An optional `~/.config/reel-break/recap-video-source` file supplies a local default video URL/path when the stock default is selected. An explicit video chosen in settings takes precedence. WebM and MP4 local video are supported.

The Play button is hidden until both video and speech audio can play. Playback starts from Play, rather than an autoplay attempt. Blocked/cancelled playback requests do not display a video-load error. Media requests are served concurrently so large gameplay transfers do not block voice loading. Validate readiness behavior with `node tests/recap-player.test.cjs`.
