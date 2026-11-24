# Plan: `eval/` folder (jllm-eval + jllm-harbor + jllm-swe)

## Goal
Group the evaluation tooling under `eval/`: the existing in-process suite plus Python runners for Terminal-Bench/Harbor and SWE-bench.
CLI changes and benchmark flow: `plans/harborswe.md`.

## Status
Nothing done. `eval/` does not exist; `jllm-eval` is still `core/jllm-eval` (root `pom.xml` profile `eval`, line ~152;
`core/jllm-eval/scripts/run-smoke.sh` uses `-pl core/jllm-eval`). `.gitignore` already has `__pycache__/`.

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

## Remaining tasks
1. `git mv core/jllm-eval eval/jllm-eval`; `relativePath ../../pom.xml` stays valid; verify `REPO_ROOT` derivation in `scripts/run-smoke.*` and `docker/*`.
2. Root `pom.xml`: `core/jllm-eval` -> `eval/jllm-eval` (module and the profile comment). `run-smoke.sh/.ps1`: `-pl eval/jllm-eval`.
3. Update references: `CONTRIBUTING.md:37`, `docs/guides/extending.md:81`, `docs/reference/history.md`, `docs/comparisons/harnesses-*.md` (leave eval-results reports as history).
4. `.gitignore`: add `eval/results/`, `eval/**/.venv/`, `eval/**/*.egg-info/`.
5. Validate: `mvn -Peval -pl eval/jllm-eval validate` and unit-only run (`TEST_GROUPS=unit`); not during a model eval.
6. After CLI contract (`plans/harborswe.md` task 1-2): create `eval/common`, then `eval/jllm-swe` (first benchmark), then `eval/jllm-harbor` (smoke first).
7. Write `eval/README.md`.
