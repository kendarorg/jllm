# eval

Evaluation tooling for jllm: an in-process suite that tests the harness features, and shared helpers for future runners
that put the unchanged harness through public agent benchmarks.

## Parts

| Folder | What it is | Built with |
|---|---|---|
| [`jllm-eval/`](jllm-eval/README.md) | In-process suite: one suite per harness feature, graded by deterministic checks and an LLM judge. | Maven (`eval` profile) |
| `common/` | Shared helpers for the Python runners: JRE and jar install, reading the CLI `--json-result` file, cost and turn caps, summaries and history index. | Python, `uv` |
| `results/` | Raw benchmark output. Git-ignored; only Markdown summaries are tracked. | - |

Only `jllm-eval` is a Maven module. Benchmark runners drive the shaded `jllm-cli` jar: `JLLM_CLI_JAR` when set,
otherwise `jllm-cli/target/jllm-cli-*-jllm.jar` (build it with `mvn -pl jllm-cli -am package`).

## When to use which

- **jllm-eval**: to check that a harness feature (agent loop, permissions, hooks, compaction, plugins, ...) works with a
  given model, or that a change did not make a feature worse. Results and history in `jllm-eval/eval-results/`.
  ```
  mvn -Peval -pl eval/jllm-eval -am test -Dgroups=unit      # no model needed
  eval/jllm-eval/scripts/run-smoke.sh                       # real model, presets in scripts/envs/
  ```

## Python tooling

The Python parts use [uv](https://docs.astral.sh/uv/) for the virtual environment, dependencies, lockfile and running:

```
cd eval/common
uv sync              # create .venv and install the locked dependencies
uv run pytest        # tests: no network, Docker or LLM
```

Python 3.10 or later.

## Rules

- Never run benchmark jobs while a model eval (`jllm-eval` against a real model) is running,
  and the other way round: they share rate limits, local model servers and machine resources, and skew each other's
  results.
- Raw output goes to `results/` (git-ignored); commit only the Markdown summaries.
