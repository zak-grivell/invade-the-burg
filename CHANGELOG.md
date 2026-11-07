# invade-the-burg Changelog

## [Unreleased]

### Added
- In-pane 9:16 chat response videos with ElevenLabs narration and configurable Always, Never, and Ask modes (Ask by default).
- Local MP4/WebM or online background video, readiness-gated playback, and per-chat recap notifications.

### Fixed
- Read Air transcript state through the public coroutine interface.
- Serve WebM with the correct content type and stream large videos concurrently with voice audio.
- Keep blocked or cancelled playback from appearing as video-load failures.

### Changed
- Removed the permanent playback button below the embedded browser.
- Removed unused template test fixtures and replaced template documentation with project setup instructions.
