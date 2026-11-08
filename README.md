# invade-the-burg

## What it is

Reel Break is a JetBrains plugin that opens a configurable distraction over an active Air agent pane while the agent works and returns the developer when attention is needed. The product direction includes an ACP proxy for working / needs-permission / done signals and time-saved stats; diff → reel is a stretch goal. Those planned capabilities are not all implemented in this checkout.

## How to run

With JDK 25 and a graphical desktop, run `./gradlew runIde --console=plain` from the repository root. The project targets IntelliJ 2026.3. Linux browser playback requires WebKitGTK and GStreamer codecs; see `BrowserPlatform.kt` and the native-media workflow for platform prerequisites. This command has not been locally verified in the cloud environment because required downloads are blocked.

## How to test

Use Node 24.22.1 (`.node-version`). Run `npm ci`, then `node tests/media-visibility.test.cjs` (19 scenarios verified locally). No third-party npm packages are required.

Build and run Kotlin tests with `./gradlew build test --console=plain --no-daemon`. GitHub CI validates this command; local Gradle verification was waived due to blocked dependency downloads. The CI Kotlin job uses JDK 25 and Gradle caching. Cold IntelliJ downloads may exceed three minutes; the JS job has a three-minute limit. Native media smoke tests and full plugin compatibility verification remain separate workflows/checks.

## Architecture

Diagram placeholder: agent pane signals → break state → overlay / native browser → return to work. See [AGENTS.md](AGENTS.md) for the directory map, exact commands and shared engineering rules. ACP proxy and statistics components are planned.

## AI usage

All agents follow [AGENTS.md](AGENTS.md). Tools and task contributions are recorded in [AIcontribution.md](AIcontribution.md).

### Multi-agent workflow (Agent ETA)

- Lanes: Lane A (Claude Code, Opus for planning, Sonnet for implementation) built state tracking, session log, history and IDE wiring; Lane B (Codex 6 Sol) built the predictor, decision rule and evaluation in a separate git worktree, coding against a shared contract (EtaTypes.kt) committed first.
- Head-to-head (T2 EtaPredictor, same spec): Codex matched the spec exactly but its tests used exact float equality (2/4 failed, e.g. 204.00000000000003); Claude Sonnet wrote 15 passing tests with broader edge cases but clamped before taking percentiles, giving p80=20 instead of 4 at elapsed 200 — its tests encoded its own behaviour. Both caught by Sean's hand-calculated R type 7 values. Kept Codex's implementation, fixed its test tolerance, ported Claude's extra edge-case tests. Lesson: passing tests ≠ correct; expected values must come from the spec.
- Cross-review: Codex reviewed Lane A and found two valid issues (stretches crossing UTC midnight were lost; predictor rebuild held a lock on the 200 ms EDT poll). Both fixed by Claude, accepted by Sean.
- Human verification: Codex's sandbox couldn't run Gradle, so Sean ran all Lane B tests and evaluateEta locally, catching a runtime classpath bug (kotlin stdlib missing from the JavaExec task).
- Live testing changes driven by the human: skip made provisional (reopen when a stretch outlasts typical history); badge moved to its own row with a "Longer than usual" state after observing it hidden in a narrow pane.

Licensed under the [GNU General Public License v3.0](LICENSE) (SPDX: `GPL-3.0-only`).

![Build](https://github.com/zak-grivell/invade-the-burg/workflows/Build/badge.svg)

## Installation and settings

Build the plugin and install the ZIP from `build/distributions` using **Settings → Plugins → ⚙ → Install Plugin from Disk**, then restart the IDE.

Use **Settings → Tools → Reel Break** to choose a preset, set a custom website URL, manage favourites, or open **Setup** to sign in. Built-in presets include X, Instagram Reels, TikTok, LeetCode, and Duolingo. These also appear in the break website switcher on existing installations; your saved favourites keep their names and take precedence over matching presets. The settings page groups website, recap, and break behaviour controls above the favourites list, so quick dropdown changes stay at the top. The overlay follows the selected Air pane automatically. **Back to work** dismisses it; **Back to Reels** returns to the retained page while the agent is working.

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

Set the Peter Griffin voice ID in Reel Break settings, `ELEVENLABS_VOICE_ID`, or `~/.config/reel-break/elevenlabs-voice-id`. Narration uses the [hosted Heroku service](https://hack-the-burg-elevenlabs-eec39ae58d6f.herokuapp.com/health), which holds the ElevenLabs API key server-side. No local API key is required, read or sent by the plugin. Spoken response text passes through this service to ElevenLabs and consumes the hosted account's API credits. Private reasoning, raw tool output, attachments, reverted messages and older turns are excluded.

The recap always uses bundled footage from the original Prism Courier recording. A compressed 90-second excerpt loops during narration. No video URL, local file or source setting is needed. The footage is served from the plugin through its private loopback media URL.

Validation: `test` covers speech text, defaults/persistence, transcript reading, media ranges and bundled recording playback. `smokeTestRecapVideo` checks native voice/video playback, portrait dimensions, black surroundings, pause and end behavior.

Old video-source settings and local overrides are ignored.

Narration and bundled footage start automatically once both are ready. A Play fallback appears if autoplay is blocked. Blocked/cancelled playback requests do not display a video-load error. Media requests are served concurrently so large gameplay transfers do not block voice loading. Validate readiness behavior with `node tests/recap-player.test.cjs`.

## Agent ETA

Reel Break logs agent sessions locally and, when "Use ETA to time breaks" is on, uses past stretches to show time left and skip breaks for very short stretches. `src/test/resources/eta/seed-history.example.jsonl` and `seed-history.demo-short.jsonl` are synthetic demo data for trying the badge and skip behaviour, so any ETA evaluation (`./gradlew evaluateEta`) must use real session logs, not these files.

## Completed agent queue

The **Completed agents** tool window on the right queues newly completed prompts, even in background tabs. New background results join in finish order. Each entry shows its thread name and elapsed prompt time (including permission waits). `~` marks a prompt that was already running when first observed, so its earlier time is unknown. Existing completed conversations are not added on startup.

Click an entry to open its thread and move it to the top of the queue. Switching editor panes also brings the open completed thread to the top. That highlighted current row stays first; drag the waiting names above or below each other to change the review order without switching threads. Dropping above the current row inserts directly below it. A blue insertion line shows the drop position, and Escape cancels the drag. Viewing a result alone never acknowledges or removes it. Sending the next prompt in the selected queued thread removes that completion and immediately opens the next waiting thread; starting a prompt in a background thread does not steal focus. The **×** beside a name dismisses only that completion, without submitting anything or permanently excluding the thread. A later completed prompt on that thread is added again. Closing a thread removes its queued result; the queue never reopens closed tabs.

The queue is project-local and lasts for the IDE session. Completions seen in the same 200 ms observation use open-tab order as a tie-breaker. No prompt is submitted automatically. A running turn must be observed before its completion can be queued.

Sending the final queued thread's next prompt, or removing the last completion with its cross, reopens Reels automatically. This also overrides an ETA skip for that run. Empty startup queues and background activity do not trigger this handoff, and a draft, permission request or regular code pane stays visible.

The completed-agent sidebar is visible only alongside chat and recap playback. It stays hidden while browsing Reels, even if a background agent finishes. The sidebar contains only thread rows and their remove crosses. Starting a new prompt still returns you to chat when that prompt finishes. Switching editor tabs, split panes, or threads immediately suspends the old browser and cancels its pending fade. A newly selected working thread stays in chat until Resume, while a new prompt started from a draft may open its normal break. Hidden browsers disable focus/input and ignore page keyboard events; navigation returns focus to the selected editor's input.

## Agent tags

A coding agent can say *why* it needs you. When the last response of a completed turn contains a marker `[[TAG:<name>]] <one-line question>`, Reel Break shows an IDE balloon "Agent needs you: TASTE: <question>" with an **Open chat** action, and the **Completed agents** row gets a `[TASTE]` label with the question as its tooltip. Supported tags are `blocked`, `direction`, `taste` and `review` (case-insensitive, currently fixed). If there are several markers, the last one wins; unknown or malformed markers are ignored, and untagged completions behave exactly as before. The tag clears when that agent starts working again. All tags currently get the same handling; pull-back timing is unchanged.

Paste this line into your own project's `AGENTS.md`:

```
When you need a human decision, end your message with [[TAG:blocked|direction|taste|review]] followed by a one-line question.
```

Tags are read from Air's native chat view; terminal-surface sessions are not supported yet (planned: read the agent's own transcript). Diagnostics are logged to `idea.log` with the prefix `ReelBreak tags:`.
