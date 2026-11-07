# invade-the-burg Changelog

## [Unreleased]

### Added
- In-pane 9:16 chat response videos with ElevenLabs narration and configurable Always, Never, and Ask modes (Ask by default).
- Local MP4/WebM or online background video, readiness-gated playback, and per-chat recap notifications.

### Fixed
- Recover policy-blocked videos on all configured sites with one muted inline retry.
- Start new documents paused until the IDE supplies pane visibility, including codec failures during startup.
- Detect native media errors and let explicit retry select a playable declared source without resetting blob/MediaSource streams.
- Read Air transcript state through the public coroutine interface.
- Serve WebM with the correct content type and stream large videos concurrently with voice audio.
- Keep blocked or cancelled playback from appearing as video-load failures.

### Changed
- Added native Retry video, Reload page, and Open in browser recovery controls for website breaks; recap controls remain separate.
- Removed unused template test fixtures and replaced template documentation with project setup instructions.
