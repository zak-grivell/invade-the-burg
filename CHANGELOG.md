<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# invade-the-burg Changelog

## [Unreleased]
### Added
- Agent ETA groundwork: read-only agent state tracking, a local JSONL session log under the IDE system directory, stretch history with an optional seed file, and advisory ETA logging. No UI change.
- ETA-driven breaks (setting "Use ETA to time breaks", default on): skips the break for stretches predicted to end within seconds, shows an "~N min left" badge in the break header, and "Wrapping up…" when the agent is nearly done.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
