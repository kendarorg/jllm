# Plan: standard harness benchmarks (Terminal-Bench/Harbor, SWE-bench)

Goal: run the jllm agent harness, unchanged, against public agent benchmarks and report comparable scores.
Not in scope: `/v1/chat/completions` facade, MCP server, changes to `core/jllm-eval` (its own in-process suite).

## How these benchmarks drive an agent

1. The benchmark starts a container with the task environment.
2. It installs and runs the agent inside it with a task prompt (no human, no approvals).
3. It checks the end state (tests, files). The agent's text is ignored.

Consequence: the entry point is the CLI (`jllm-cli --prompt`), run in `--mode auto`; the container is the sandbox.

## Current state (verified in code)

- `jllm-cli` one-shot: `--prompt`, `--mode default|plan|edit|auto`, `--workdir`, `--max-turns`, `--sandbox local|docker`,
  `--provider/--model/--base-url/--api-key`, `--settings`, `--system-prompt*`, pricing flags.
- Shaded jar: `jllm-cli/target/jllm-cli-<ver>-jllm.jar` (the shade plugin replaces the main jar) (main class `org.kendar.llm.cli.Main`).
- Harness emits `run_finished.outcome`: `completed | stuck | unverified | max_turns | truncated | refused | cancelled | failed`,
  and `usage` events (input/output/cacheRead/cacheCreation tokens). `HarnessPricing` gives cost.

## Gaps

| # | Gap | Where |
|---|---|---|
| 1 | `main` always `System.exit(0)`; outcome is lost | `Main.java:94` |
| 2 | No machine-readable result (outcome, usage, cost, turns, tool-call trajectory) | `Main.java`, `CliArgs.java` |
| 3 | No wall-clock `--timeout` | `Main.java` |
| 4 | `local` sandbox behaviour outside `--workdir` unverified (benchmarks use arbitrary paths) | `sandbox/*` |
| 5 | Task containers usually have no JRE | adapter |
| 6 | No Harbor adapter | new, Python |
| 7 | No SWE-bench runner | new |

## Phase 1: CLI headless contract (Java)

1. Exit codes: `0` completed; `1` failed/refused; `2` stuck; `3` max_turns/truncated/unverified; `4` cancelled/timeout;
   `64` usage error. Document in `USAGE` and `jllm-cli/README.md`.
2. `--json-result FILE`: write one JSON object on exit (also on failure):
   `{chatId, outcome, turns, durationMs, usage{input,output,cacheRead,cacheCreation}, costUsd, toolCalls[{name,input,isError}], error}`.
3. `--timeout SECONDS`: cancel the run (`harness.cancel`), write the result with outcome `cancelled`, exit 4.
4. Keep the interactive path unchanged (only the `--prompt` path gets exit codes).
5. Tests in `MainTest`/`CliArgsTest`: each outcome maps to its code; result file shape; timeout. Fake model, no network.

## Phase 2: sandbox for in-container use (Java, verify first)

1. Read `LocalShell`, `CwdSandboxExecutor`, `LocalDirSandboxExecutor`, `ScratchJailBackend`: can `--sandbox local` read/write
   outside `--workdir` and run arbitrary shell?
2. If jailed: add `--sandbox none` (or `--jail off`), allowed only with `--mode auto`, documented as "container is the boundary".
3. Never `--sandbox docker` inside the benchmark container.
4. Windows/Linux/macOS: code and tests must pass on all three (project rule).

## Phase 3: packaging

1. Release artifact: the shaded jar plus a pinned JRE 21 download URL per OS/arch (Temurin or Corretto).
2. `install.sh` for the adapter: install the JRE if `java -version` < 21, copy the jar to `/opt/jllm/`, no network beyond the JRE download.
3. Check the jar size and startup time in a clean Ubuntu and Debian container.

## Phase 4: Harbor adapter (Terminal-Bench)

Location: `eval/jllm-harbor/` (see `plans/evalfolder.md`) (Python, outside the Maven build).

1. `JllmAgent(BaseInstalledAgent)`: `install()` runs `install.sh`; `run()` executes
   `java -jar jllm.jar --mode auto --workdir <cwd> --prompt <instruction> --max-turns N --timeout T --json-result /logs/jllm-result.json`
   with `--provider/--model/--base-url` from Harbor env; the API key only via env, never on the command line.
2. Parse `jllm-result.json` into Harbor's token and cost fields.
3. Trajectory: convert `session.json`/`messages.jsonl` to ATIF (optional, after the first scores).
4. Smoke: 3 to 5 Terminal-Bench tasks with one cheap model; then the full set.
5. Record: model, jllm commit, `--max-turns`, timeout, pass rate, cost per task. Results go to `eval/results/harbor/`.

## Phase 5: SWE-bench

1. Runner modeled on mini-swe-agent: for each instance, check out the repo at `base_commit` in the instance container,
   run the CLI (`--mode auto --workdir /testbed`), then `git diff` as the model patch.
2. Emit `predictions.jsonl` (`instance_id`, `model_name_or_path`, `model_patch`); score with the official SWE-bench harness.
3. Start with a SWE-bench Verified subset (about 20 instances), then the full 500.
4. Check: no test-file edits leak into the patch (the standard harness replaces them anyway); the prompt includes only the issue text.

## Risks

- Java in arbitrary containers (JRE bootstrap, memory, startup).
- A long-context model and per-instance cost: set a hard cost cap in the adapter.
- Model-specific tuning (system prompt `--system-prompt*`, `--small-model`) must be fixed per run and recorded, not tuned per task.
- Comparability: same turn and time limits as published baselines; record deviations.
- Never run benchmark jobs during a `core/jllm-eval` run (project rule).

## Order and done criteria

1. Phase 1 done: exit codes plus result file, tests green on all three OS.
2. Phase 2 done: a task writing under `/tmp` and `/app` works in a plain container.
3. Phases 3 and 4 done: 5 Terminal-Bench tasks complete end to end with scores and cost.
4. Phase 5 done: 20 SWE-bench Verified instances scored.

## Open questions

- Which benchmark first: Terminal-Bench (simpler, Harbor) or SWE-bench (bigger name)? Recommendation: Terminal-Bench.
- Which model(s) and budget for the first runs?
- Is `--sandbox none` acceptable as a new flag, or should `local` be unjailed in auto mode?
