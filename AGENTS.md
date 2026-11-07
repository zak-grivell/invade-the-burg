# Agent instructions

## Project

Reel Break is a JetBrains plugin whose product direction is a plugin plus ACP proxy that detects when an agent is working, needs permission, or is done: it opens a distraction while the agent works, pulls the developer back when needed, and shows time-saved stats. Diff → reel is a stretch goal. The current checkout implements Air pane monitoring, a configurable website overlay, and native browser playback; ACP proxy integration and time-saved stats are goals, not verified existing modules.

## Priorities

Working demo > correctness > code quality > new features.

## Commands

Run from the repository root. Use JDK 25 (the Gradle toolchain requirement), the checked-in Gradle 9.8.1 wrapper, and Node 24.22.1 (pinned in `.node-version`).

| Purpose | Exact command | Validation |
| --- | --- | --- |
| Build and Kotlin tests (CI) | `./gradlew build test --console=plain --no-daemon` | **verified in CI**: designated CI check; first result pending. Local verification explicitly skipped because IntelliJ downloads are blocked. |
| Build only | `./gradlew build --console=plain --no-daemon` | Included in the CI command above; pending CI. |
| Kotlin tests only | `./gradlew test --console=plain --no-daemon` | Included in the CI command above; pending CI. |
| Run plugin | `./gradlew runIde --console=plain` | Repository-provided task; not locally verified. Requires a graphical desktop and native browser prerequisites. |
| JS install (CI) | `npm ci` | Verified locally; no third-party npm dependencies. |
| JS playback tests (CI) | `node tests/media-visibility.test.cjs` | Verified locally: 13 scenarios pass. |

There is no configured lint/format task. Do not invent one or add a dependency without asking. `verifyPlugin` is an existing separate compatibility check; it is excluded from the fast CI workflow because it downloads and runs additional IDE tooling. Local Gradle/runIde verification is waived for this setup task; future agents must distinguish pending CI checks from successful results.

On the managed cloud environment, export `GRADLE_USER_HOME=/workspace/.gradle` to reuse the proxy configuration. Each cloud task is already isolated: use the existing checkout; do not create a worktree unless requested.

## Structure

- `src/main/kotlin/.../startup/`: plugin startup registration.
- `src/main/kotlin/.../services/`: Air pane activity detection, break state, and overlay lifecycle.
- `src/main/kotlin/.../ui/`: overlay, native browser integration, setup and preview dialogs.
- `src/main/kotlin/.../settings/`: persisted website preferences and settings UI.
- `src/main/resources/`: plugin manifest, browser playback script, third-party notices.
- `src/test/kotlin/`: Kotlin unit tests and opt-in native media smoke runner.
- `tests/`: dependency-free Node playback behavior tests.
- `gradle/`, `build.gradle.kts`, `settings.gradle.kts`: wrapper, toolchains and build configuration.
- `.github/workflows/`: CI and release automation; `.run/`: shared IDE run configurations.

## Rules

- Plan before coding. Make small, focused changes; no unrelated edits or refactors.
- Add or update tests for every logic change. Never weaken or delete tests to make them pass.
- After two failed attempts at the same operation or fix, stop and report the failures and blocker.
- Ask before adding any new dependency.
- Never commit secrets. Use `.env` or environment variables and keep `.env.example` updated. The plugin does not automatically load `.env`.
- Never push to main or force-push. Work on a feature branch and open a PR.
- Append a row to `AIcontribution.md` for every task; record human changes/rejections accurately and do not invent review outcomes.
