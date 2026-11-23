# Plan: `eval/` folder (jllm-eval + jllm-harbor + jllm-swe)

Companion to `plans/harborswe.md` (which covers the CLI changes and the benchmark flow). This plan covers the
folder layout and the build wiring.

## Target layout

```
eval/
  README.md                 what each part does; which to use when; the "never during a model eval" rule
  pom.xml                   aggregator (packaging pom, parent = root); modules below
  jllm-eval/                MOVED from core/jllm-eval (own in-process suite: cases, graders, judge, baselines)
  jllm-harbor/              Terminal-Bench / Harbor runner (Python adapter, no Maven module)
  jllm-swe/                 SWE-bench runner (Python, no Maven module)
  common/                   shared by the two runners: jar+JRE installer, result-file reader, cost cap, results writer
  results/                  git-ignored raw output; only the Markdown summaries are tracked (as eval-results today)
```

Only `jllm-eval` is a Maven module (Java/JUnit). `jllm-harbor` and `jllm-swe` are Python projects: they do not
compile Java, they drive the shaded `jllm-cli` jar. Keeping them out of Maven keeps `mvn` builds unchanged.

## What goes in each part

### jllm-eval (moved, unchanged behaviour)
Everything in `core/jllm-eval` today: `src/test`, `scripts/`, `docker/`, `eval-models/`, `eval-results/`,
`FEATURES.md`, `FRAGILE_CASES.md`, `README.md`. Tests the harness in-process through `AgentHarness`. Own task set.

### jllm-harbor
```
jllm-harbor/
  README.md                 usage, required env, how to read results
  pyproject.toml            deps: harbor (pinned), nothing else
  jllm_harbor/
    agent.py                JllmAgent(BaseInstalledAgent): install(), run(), result parsing
    trajectory.py           session.json / messages.jsonl -> ATIF (phase 2, optional)
    install.sh              JRE 21 bootstrap + jar copy (from common/)
  jobs/
    smoke.yaml              3 to 5 tasks, one cheap model
    full.yaml               full Terminal-Bench set, pinned dataset version
  scripts/
    run-smoke.sh / .ps1     same style as jllm-eval/scripts (preset env files, hidden key prompt, no keys in presets)
    envs/                   presets (model, base URL, max turns, timeout); never keys
  tests/                    pytest: result-file parsing, command building (no Harbor, no network)
```
Role: adapter between Harbor and `java -jar jllm-cli-shaded.jar --mode auto --prompt ... --json-result ...`.

### jllm-swe
```
jllm-swe/
  README.md
  pyproject.toml            deps: swebench (pinned), datasets
  jllm_swe/
    run.py                  per instance: container at base_commit -> CLI -> git diff -> prediction
    prompt.py               fixed issue-text prompt template (same for all instances, recorded in results)
    predictions.py          writes predictions.jsonl (instance_id, model_name_or_path, model_patch)
    score.py                wraps the official swebench evaluation
  subsets/
    verified-20.txt         instance ids for the first runs
  scripts/ envs/ tests/     as in jllm-harbor
```
Role: produce patches with the CLI; scoring stays the official SWE-bench harness.

### common
Shared Python package (or plain files) used by both: `install.sh` (JRE + jar), `result.py` (read
`--json-result`: outcome, usage, cost), `budget.py` (hard cost/turn cap per task), `report.py` (summary Markdown +
history index, same columns style as `jllm-eval/eval-results/history/INDEX.md`).

## Build wiring (changes outside eval/)

1. `git mv core/jllm-eval eval/jllm-eval` (keeps history).
2. `eval/jllm-eval/pom.xml`: `<relativePath>../../pom.xml</relativePath>` is unchanged (still two levels deep). Check
   nothing else uses `core/`-relative paths (`scripts/run-smoke.*` use `$REPO_ROOT`; verify `REPO_ROOT` derivation).
3. Root `pom.xml`: profile `eval` module `core/jllm-eval` becomes `eval/jllm-eval`. No `eval/pom.xml` aggregator is
   needed if only one module lives there; add one only if a second Maven module appears. Recommendation: skip it.
4. `run-smoke.sh`: `-pl core/jllm-eval` becomes `-pl eval/jllm-eval`; `run-smoke.ps1` likewise.
5. Update references: `CONTRIBUTING.md:37`, `docs/guides/extending.md:81`, `docs/reference/history.md` (section 18),
   `docs/comparisons/harnesses-*.md`, `.idea/*` (ignore), `core/jllm-eval/docker/*` scripts, the eval-results reports
   (they only mention the module name; leave as history).
6. `.gitignore`: add `eval/results/`, `eval/**/__pycache__/`, `eval/**/.venv/`, `eval/**/*.egg-info/`.
7. `jllm-cli` stays at the repo root. The runners locate the jar by `JLLM_CLI_JAR` env, else
   `jllm-cli/target/jllm-cli-*-jllm.jar`.

## Steps

1. Move jllm-eval (step 1 to 6 above), then run `mvn -Peval -pl eval/jllm-eval validate` and a unit-only test
   (`TEST_GROUPS=unit`, no LLM) to confirm the move. Do not run during a model eval.
2. Implement the CLI contract from `plans/harborswe.md` phases 1 and 2 (exit codes, `--json-result`, `--timeout`, sandbox).
3. Create `eval/common`, then `eval/jllm-harbor` (smoke job first).
4. Create `eval/jllm-swe` after harbor has produced a first score.
5. Write `eval/README.md` and update the docs references.

## Cross-platform and rules

- Scripts in bash and PowerShell, as in jllm-eval. Python is 3.10+; no OS-specific paths.
- Presets never contain keys; keys come from env or a hidden prompt.
- No git hooks, no commits by Claude (user commits).
- Tests for the Python parts run without network, Docker or an LLM.

## Open questions

- Python tooling: `uv` or plain `venv` + `pip`? Recommendation: `uv`, with a `pip` fallback documented.
- One shared `common` package or duplicated small helpers? Recommendation: shared, kept tiny.
- Keep `eval/results/` untracked, or track Markdown summaries like jllm-eval does? Recommendation: track summaries only.
