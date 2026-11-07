# invade-the-burg

## What it is

Reel Break is a JetBrains plugin that opens a configurable distraction over an active Air agent pane while the agent works and returns the developer when attention is needed. The product direction includes an ACP proxy for working / needs-permission / done signals and time-saved stats; diff → reel is a stretch goal. Those planned capabilities are not all implemented in this checkout.

## How to run

With JDK 25 and a graphical desktop, run `./gradlew runIde --console=plain` from the repository root. The project targets IntelliJ 2026.3. Linux browser playback requires WebKitGTK and GStreamer codecs; see `BrowserPlatform.kt` and the native-media workflow for platform prerequisites. This command has not been locally verified in the cloud environment because required downloads are blocked.

## How to test

Use Node 24.22.1 (`.node-version`). Run `npm ci`, then `node tests/media-visibility.test.cjs` (13 scenarios verified locally). No third-party npm packages are required.

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
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [group](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml), [name](./src/main/resources/META-INF/plugin.xml), and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin [description](./src/main/resources/META-INF/plugin.xml) (see [Tips][docs:plugin-description]) and this README to describe what your plugin does.
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

This Fancy IntelliJ Platform Plugin is going to be your implementation of the brilliant ideas that you have.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "invade-the-burg"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/zak-grivell/invade-the-burg/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation

## Browser playback

Reel Break 1.2.3 leaves Reel selection, scrolling, and playback timing to Instagram's player. It no longer pauses videos based on their relative size or starts videos in response to scroll/readiness timers. A rejected Instagram `play()` request gets one muted inline fallback only for `NotAllowedError`; website cancellations retain their original promise behavior. Explicit site/user pauses cancel retained resumes. Hidden pages retain their document and pause media; showing the page resumes previously playing media only when the source is unchanged and still visible. A slow play request is never aborted by a plugin watchdog.

Validation: `node tests/media-visibility.test.cjs`; the `smokeTestMedia` Gradle task exercises native H.264 playback, 12 consecutive site-owned scroll-snap handoffs, manual pause, and repeated hide/show cycles. Native Windows/Linux execution still requires their respective runners.

## Agent ETA

Reel Break logs agent sessions locally and, when "Use ETA to time breaks" is on, uses past stretches to show time left and skip breaks for very short stretches. `src/test/resources/eta/seed-history.example.jsonl` and `seed-history.demo-short.jsonl` are synthetic demo data for trying the badge and skip behaviour, so any ETA evaluation (`./gradlew evaluateEta`) must use real session logs, not these files.
