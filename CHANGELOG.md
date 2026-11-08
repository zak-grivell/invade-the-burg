# invade-the-burg Changelog

## [Unreleased]

### Added
- Completed agents sidebar with finish-order review queue, per-prompt elapsed time, acknowledgement/removal, manual reordering, and tab-aware next-agent handoff.
- Agent ETA groundwork: read-only agent state tracking, a local JSONL session log under the IDE system directory, stretch history with an optional seed file, and advisory ETA logging. No UI change.
- ETA-driven breaks (setting "Use ETA to time breaks", default on): skips the break for stretches predicted to end within seconds (and opens it late if the stretch runs longer than usual), shows an "~N min left" badge in the break header, and "Wrapping up…" when the agent is nearly done.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- In-pane 9:16 chat response videos with ElevenLabs narration and configurable Always, Never, and Ask modes (Ask by default).
- Local MP4/WebM or online background video, readiness-gated playback, and per-chat recap notifications.

### Fixed
- Release hidden macOS WebKit keyboard focus and restore the chat composer after returning from Reels, completion cards or recaps in the same pane.
- Reopen Reels after submitting the last queued thread's next prompt or removing the last completion, including when ETA would skip its break. Preserve draft and permission focus, and avoid reopening on repeated empty polls.
- Hide the completed-agent sidebar during scrolling and suspend native browser input immediately on thread or editor changes.
- Recover policy-blocked videos on all configured sites with one muted inline retry.
- Start new documents paused until the IDE supplies pane visibility, including codec failures during startup.
- Detect native media errors and let explicit retry select a playable declared source without resetting blob/MediaSource streams.
- Read Air transcript state through the public coroutine interface.
- Serve WebM with the correct content type and stream large videos concurrently with voice audio.
- Keep blocked or cancelled playback from appearing as video-load failures.

### Changed
- Keep the open completed thread first on queue clicks and editor-pane switches while retaining drag reordering for waiting threads below it.
- Drag completed-agent names to reorder the queue without opening a thread, with an insertion line and Escape cancellation.
- Simplify the completed-agent queue to clickable, current-highlighted rows with per-row remove crosses. Clicking promotes a thread; submitting its next prompt advances automatically. Remove duplicate queue headers and the action toolbar.
- Added native Retry video, Reload page, and Open in browser recovery controls for website breaks; recap controls remain separate.
- Removed unused template test fixtures and replaced template documentation with project setup instructions.
