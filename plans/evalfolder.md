# Plan: `eval/` folder (jllm-eval + jllm-harbor + jllm-swe)

NO RETROCOMPATIBILITY

## Goal
Group the evaluation tooling under `eval/`: the existing in-process suite plus Python runners for Terminal-Bench/Harbor and SWE-bench.
CLI changes and benchmark flow: `plans/harborswe.md`.

## Status
Tasks 1-5 and 7 done; 6 partly done.
- 1-5: `eval/jllm-eval` moved (Maven module in the root `pom.xml`), references updated, `.gitignore` covers
  `eval/results/**` (Markdown summaries tracked), `eval/**/.venv/`, `eval/**/*.egg-info/`, `eval/**/.pytest_cache/`.
- 6: `eval/common` exists (pytest green on Windows, 22). `eval/jllm-swe` and `eval/jllm-harbor` removed on
  2026-11-25; to redo with `plans/harborswe.md` tasks 4-5.
- 7: `eval/README.md` describes `jllm-eval`, `common` and `results` only.
- Open: task 6 (`eval/jllm-swe`, `eval/jllm-harbor`, with `plans/harborswe.md` tasks 4-5); Linux/macOS pytest runs;
  `chmod +x` on `.sh` when committing.

## Design
```
eval/
  README.md          parts, when to use which, "never during a model eval" rule
  jllm-eval/         moved from core/jllm-eval (src/test, scripts/, docker/, eval-models/, eval-results/, FEATURES.md, FRAGILE_CASES.md, README.md)
  jllm-harbor/       Python: pyproject.toml (harbor pinned), jllm_harbor/{agent.py,trajectory.py,install.sh}, jobs/{smoke,full}.yaml,
                     scripts/ (+envs/ presets, never keys), tests/ (pytest, no network)
  jllm-swe/          Python: pyproject.toml (swebench pinned, datasets), jllm_swe/{run,prompt,predictions,score}.py,
                     subsets/verified-20.txt, scripts/ envs/ tests/
  common/            shared tiny package: install.sh (JRE+jar), result.py (read --json-result), budget.py (cost/turn cap), report.py (summary + history index)
  results/           git-ignored raw output; only Markdown summaries tracked
```
- Only `jllm-eval` is a Maven module; runners drive the shaded `jllm-cli` jar (`JLLM_CLI_JAR`, else `jllm-cli/target/jllm-cli-*-jllm.jar`). `jllm-cli` stays at repo root.
- No `eval/pom.xml` aggregator unless a second Maven module appears.
- Python tooling: `uv` (venv, deps, lockfile, running); no venv+pip path.
- Scripts in bash and PowerShell; Python 3.10+; presets never hold keys; Python tests need no network, Docker or LLM.

## Tasks
1. (done) `git mv core/jllm-eval eval/jllm-eval`; `relativePath ../../pom.xml` stays valid; verify `REPO_ROOT` derivation in `scripts/run-smoke.*` and `docker/*`.
2. (done) Root `pom.xml`: `core/jllm-eval` -> `eval/jllm-eval` (module and the profile comment). `run-smoke.sh/.ps1`: `-pl eval/jllm-eval`.
3. (done) Update references: `CONTRIBUTING.md:37`, `docs/guides/extending.md:81`, `docs/reference/history.md`, `docs/comparisons/harnesses-*.md` (leave eval-results reports as history).
4. (done) `.gitignore`: add `eval/results/`, `eval/**/.venv/`, `eval/**/*.egg-info/`.
5. (done) Validate: `mvn -Peval -pl eval/jllm-eval validate` and unit-only run (`TEST_GROUPS=unit`); not during a model eval.
6. (open; `eval/common` done) `eval/jllm-swe` (first benchmark), then `eval/jllm-harbor` (smoke first), with `plans/harborswe.md` tasks 4-5.
7. (done) Write `eval/README.md`.
