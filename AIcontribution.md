# AI contribution record

## Tools used

- Claude Opus/Sonnet via Claude Code and JetBrains Air: available project tools; use on this task not recorded.
- Codex/GPT: used for this engineering setup.
- Junie: record here if used; use on this task not recorded.

## Log

| time (UTC) | who | agent/tool | task | what the human changed or rejected |
| --- | --- | --- | --- | --- |
| 2026-11-07 | Repository owner + Codex | Codex/GPT | Add agent guidance, contribution log, CI, local configuration safeguards, environment example and development documentation; verify JS playback tests. | Human waived local Gradle/Kotlin verification after blocked downloads and requested GitHub CI validation; no implementation review outcome recorded. |
| 2026-11-07 | Lane A (Claude) | Claude Code (Opus 5.5) | Agent ETA T0: temporary `Air activity seen` info log in `AirPaneActivity` to confirm Air activity enum names. | Pending human review. |
| 2026-11-07 | Lane A (Claude) | Claude Code (Opus 5.5) | Agent ETA T1: `PaneActivity.activity`, `AgentState` model/mapping, and shared `EtaTypes.kt` contract for Lane B; stricter `PaneActivityTest`, new `AgentStateTest`. | Pending human review. |
| 2026-11-07 | Lane B (Codex) | Codex/GPT | Agent ETA T2: conditional and unconditional percentile predictor with unit tests, tolerance fix, and additional edge-case tests. | Human hand-checked R type 7 values, found exact float-equality failures, and confirmed EtaPredictorTest passed locally after the tolerance fix; requested additional edge-case tests. |
