# Plan: standard harness benchmarks (Terminal-Bench/Harbor, SWE-bench)

## Goal
Run the unchanged jllm agent harness against public agent benchmarks and report comparable scores.
Out of scope: `/v1/chat/completions` facade, MCP server, changes to `core/jllm-eval` (own in-process suite).
Folder layout and build wiring: `plans/evalfolder.md`.

## Status
Nothing implemented (checked against code). Verified current state:
- `jllm-cli` one-shot flags exist: `--prompt`, `--mode plan|edit|auto`, `--workdir`, `--max-turns`, `--sandbox local|docker`,
  `--provider/--model/--base-url/--api-key`, `--system-prompt*`, pricing flags (`jllm-cli/.../cli/CliArgs.java`).
- Main class `org.kendar.llm.cli.Main`; shaded jar `jllm-cli/target/jllm-cli-<ver>-jllm.jar`.
- Harness emits `run_finished.outcome` (`completed|stuck|unverified|max_turns|truncated|refused|cancelled|failed`) and `usage` events;
  `HarnessPricing` (core/jllm-harness) gives cost.
- Gaps still open: `Main.java:94` always `System.exit(0)`; no `--json-result`; no `--timeout`; no unjailed sandbox option;
  no JRE bootstrap; no Harbor adapter; no SWE-bench runner.

## Design
Benchmark starts a container, installs and runs the agent with a task prompt (no human, no approvals), checks end state (agent text ignored).
Entry point: `jllm-cli --prompt` in `--mode auto`; the container is the sandbox. Never `--sandbox docker` inside it.
Fixed per run and recorded (not tuned per task): model, `--system-prompt*`, `--small-model`, max turns, timeout, jllm commit.
Model(s) and budget are not fixed in the plan: passed as run parameters and recorded with each run.
Hard per-task cost cap in the adapter. Same turn/time limits as published baselines; record deviations.

## Remaining tasks
1. CLI headless contract (jllm-cli, `--prompt` path only; interactive unchanged):
   - Exit codes: 0 completed; 1 failed/refused; 2 stuck; 3 max_turns/truncated/unverified; 4 cancelled/timeout; 64 usage error. Document in `USAGE` and `jllm-cli/README.md`.
   - `--json-result FILE` (also on failure): `{chatId, outcome, turns, durationMs, usage{input,output,cacheRead,cacheCreation}, costUsd, toolCalls[{name,input,isError}], error}`.
   - `--timeout SECONDS`: `harness.cancel`, outcome `cancelled`, exit 4.
   - Tests in `MainTest`/`CliArgsTest` (fake model, no network): outcome-to-code, result shape, timeout.
2. Sandbox for in-container use: inspect `LocalShell`, `CwdSandboxExecutor`, `LocalDirSandboxExecutor`, `ScratchJailBackend`
   (core/jllm-harness `.../harness/sandbox/`) for access outside `--workdir`. If jailed, add `--sandbox none`, only with `--mode auto`
   (decided: new flag, `local` keeps its jail). Done when a task writing under `/tmp` and `/app` works in a plain container.
3. Packaging: shaded jar plus pinned JRE 21 URL per OS/arch; `install.sh` installs JRE if `java -version` < 21, copies jar to `/opt/jllm/`.
   Check size and startup in clean Ubuntu and Debian containers.
4. SWE-bench runner (`eval/jllm-swe/`), first benchmark: per instance, repo at `base_commit`, CLI `--mode auto --workdir /testbed`,
   `git diff` as patch; write `predictions.jsonl`; score with official harness; Verified subset (~20) then 500.
   Prompt contains only the issue text.
5. Harbor adapter (`eval/jllm-harbor/`), second: `JllmAgent(BaseInstalledAgent)`; `run()` executes
   `java -jar jllm.jar --mode auto --workdir <cwd> --prompt <instruction> --max-turns N --timeout T --json-result /logs/jllm-result.json`;
   key via env only; parse result into Harbor token/cost fields; optional ATIF trajectory later; smoke 3-5 tasks, then full set.
   Record model, commit, limits, pass rate, cost per task in `eval/results/harbor/`.
6. Done criteria: tests green on all three OS (1); 20 SWE-bench Verified instances scored (4); 5 Terminal-Bench tasks end to end with score and cost (3, 5).

## Rules and risks
- Never run benchmark jobs during a `core/jllm-eval` run.
- Main risk: Java in arbitrary containers (memory, startup).
