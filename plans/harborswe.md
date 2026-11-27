# Plan: standard harness benchmarks (Terminal-Bench/Harbor, SWE-bench)

## Goal
Run the unchanged jllm agent harness against public agent benchmarks and report comparable scores.
Out of scope: `/v1/chat/completions` facade, MCP server, changes to `core/jllm-eval` (own in-process suite).
Folder layout and build wiring: `plans/evalfolder.md`.

## Status
Tasks 1-2 done and committed; 3 coded; 4-6 not done (the `eval/jllm-swe` and `eval/jllm-harbor` runners were removed
on 2026-11-25, nothing ran end to end).
- 1 done: exit codes, `--json-result`, `--timeout` in `jllm-cli` (`RunReport`, `CliArgs.checkHeadless`), documented in
  `jllm-cli/README.md` (Headless runs) and the `USAGE` string. `--prompt` runs answer every approval with no.
- 2 done: `--sandbox none` (only with `--mode auto`, else exit 64). Not checked in a real container.
- 3 coded: `eval/common/install.sh` (Temurin `jdk-21.0.13.1+2`, SHA-256 pinned, `JLLM_CACHE_DIR`, launcher
  `DEST/jllm`). Download, size and startup not checked in a container.
- 4, 5, 6: not done. `eval/common` keeps the helpers they need: `result.py` (reads `--json-result`), `budget.py`
  (cost/turn caps), `report.py` (summary, history index), `runinfo.py` (`find_jar`, repo root, results dir),
  `dockerhost.py` (daemon probe).
- Decided (D11): the hard cost cap lives in the runner only (`eval/common/budget.py`), not in `jllm-cli`. Open: the adapter
  does not enforce it yet; turn/time defaults and cache-write prices to align with published baselines;
  Linux/macOS test runs.

## Design
Benchmark starts a container, installs and runs the agent with a task prompt (no human, no approvals), checks end state (agent text ignored).
Entry point: `jllm-cli --prompt` in `--mode auto`; the container is the sandbox. Never `--sandbox docker` inside it.
Fixed per run and recorded (not tuned per task): model, `--system-prompt*`, `--small-model`, max turns, timeout, jllm commit.
Model(s) and budget are not fixed in the plan: passed as run parameters and recorded with each run.
Hard per-task cost cap in the adapter. Same turn/time limits as published baselines; record deviations.

## Tasks
1. (done) CLI headless contract (jllm-cli, `--prompt` path only; interactive unchanged):
   - Exit codes: 0 completed; 1 failed/refused; 2 stuck; 3 max_turns/truncated/unverified; 4 cancelled/timeout; 64 usage error. Document in `USAGE` and `jllm-cli/README.md`.
   - `--json-result FILE` (also on failure): `{chatId, outcome, turns, durationMs, usage{input,output,cacheRead,cacheCreation}, costUsd, toolCalls[{name,input,isError}], error}`.
   - `--timeout SECONDS`: `harness.cancel`, outcome `cancelled`, exit 4.
   - Tests in `MainTest`/`CliArgsTest` (fake model, no network): outcome-to-code, result shape, timeout.
2. (done, not checked in a real container) Sandbox for in-container use: inspect `LocalShell`, `CwdLayout`, `LocalSandboxExecutor`, `ScratchJailBackend`
   (core/jllm-harness `.../harness/sandbox/`) for access outside `--workdir`. If jailed, add `--sandbox none`, only with `--mode auto`
   (decided: new flag, `local` keeps its jail). Done when a task writing under `/tmp` and `/app` works in a plain container.
3. (coded, not checked in a container) Packaging: shaded jar plus pinned JRE 21 URL per OS/arch; `install.sh` installs JRE if `java -version` < 21, copies jar to `/opt/jllm/`.
   Check size and startup in clean Ubuntu and Debian containers.
4. (open) SWE-bench runner (`eval/jllm-swe/`), first benchmark: per instance, repo at `base_commit`, CLI `--mode auto --workdir /testbed`,
   `git diff` as patch; write `predictions.jsonl`; score with official harness; Verified subset (~20) then 500.
   Prompt contains only the issue text.
5. (open) Harbor adapter (`eval/jllm-harbor/`), second: `JllmAgent(BaseInstalledAgent)`; `run()` executes
   `java -jar jllm.jar --mode auto --workdir <cwd> --prompt <instruction> --max-turns N --timeout T --json-result /logs/jllm-result.json`;
   key via env only; parse result into Harbor token/cost fields; optional ATIF trajectory later; smoke 3-5 tasks, then full set.
   Record model, commit, limits, pass rate, cost per task in `eval/results/harbor/`.
6. (open) Done criteria: tests green on all three OS (1); 20 SWE-bench Verified instances scored (4); 5 Terminal-Bench tasks end to end with score and cost (3, 5).

## Rules and risks
- Never run benchmark jobs during a `core/jllm-eval` run.
- Main risk: Java in arbitrary containers (memory, startup).
- Windows with dockerd inside WSL reached through `localhost:2375` (`wslrelay.exe`): API calls work, but output of
  `docker run`/`docker exec`/`docker compose exec` comes back empty with exit 0 (`docker logs` has it). Runners must
  run inside WSL (unix socket), or WSL must use `networkingMode=mirrored`; the daemon probe should reject an endpoint
  that loses attached output (`docker run --rm IMAGE echo` must print).
- Scripts must not leave run ids or keys in the calling PowerShell session: new run id per run unless given, key asked
  on every run.
