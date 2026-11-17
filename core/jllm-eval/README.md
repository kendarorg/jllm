# jllm-eval

Evaluates the jllm harness against real language models. The module contains only tests and is left out of the
default build: it is available only through the `eval` Maven profile.

It provides the shared infrastructure for evaluations: configuration, access to the target, judge and fast models,
a connection check and run metadata.

## Quick start

1. Choose a target (see [Targets](#targets)) and, for a local model, start its server (see
   [Local models](#local-models)).
2. Run the tests from the repository root with JDK 21:

```
mvn -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=anthropic
mvn -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=remote.mac
mvn -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=local.strong
```

`-Dgroups` selects the test groups: `smoke`, `full` or `strategy`. The extra group `unit` holds the checks that need
no model at all (`mvn -Peval -pl core/jllm-eval -am test -Dgroups=unit`). The default is `smoke,unit`.

Tests, classes and methods always run one after the other, in a single JVM.

## Targets

Each run evaluates exactly one target, chosen with `eval.target`.

| Target | Description |
|---|---|
| `anthropic` | Claude Haiku 5.5 (`claude-haiku-5-5`) through the Anthropic API. |
| `remote.<name>` | An OpenAI-compatible server you run yourself, defined by a named entry in the properties file (for example Qwen3.6-27B served natively on a Mac). The eval only calls it: it never starts, stops or reloads it. |
| `local.<strong\|alt\|small>` | A llama.cpp server running in Docker on an NVIDIA GPU, started by you with docker compose. Three models are defined: `strong` (Qwen3.6-27B, 4-bit), `alt` (gpt-oss-20b) and `small` (an 8-12B model, 4-bit, run with the small-model prompt). |

## Configuration

All settings are in `src/test/resources/eval.properties`. Any key can be overridden with `-D<key>=<value>` or with an
environment variable: take the key, upper-case it and replace every character that is not a letter or digit with `_`
(`eval.target` becomes `EVAL_TARGET`). The command line wins over the environment, which wins over the file.

At startup every setting is validated, and a single error message lists all invalid or missing keys.

### Secrets

API keys and tokens (every key ending in `.apiKey`) are read only from the environment or the command line. A secret
found in the properties file is a validation error. Configured secrets are masked wherever they could be printed:
object descriptions, exception messages, connection-check output and `run.json`.

| Key | Where to set it |
|---|---|
| `eval.anthropic.apiKey` | `EVAL_ANTHROPIC_APIKEY`, `-Deval.anthropic.apiKey`, or `ANTHROPIC_API_KEY` |
| `eval.remote.<name>.apiKey` | Optional. Environment or command line. |
| `eval.judge.openai.apiKey` | Optional. Environment or command line. |

### Properties

| Key | Meaning |
|---|---|
| `eval.target` | `anthropic`, `remote.<name>` or `local.<strong\|alt\|small>`. |
| `eval.anthropic.baseUrl` | Anthropic API address. |
| `eval.anthropic.model` | Model identifier. Defaults to `claude-haiku-5-5`. |
| `eval.anthropic.contextTokens` | Context size used by the harness. |
| `eval.anthropic.timeoutSeconds` | Request timeout. |
| `eval.remote.<name>.protocol` | `openai` (OpenAI-compatible) or `anthropic`. |
| `eval.remote.<name>.baseUrl` | Server address, including `/v1` for OpenAI-compatible servers. |
| `eval.remote.<name>.model` | Model identifier the server serves. |
| `eval.remote.<name>.contextTokens` | Context size used by the harness. |
| `eval.remote.<name>.smallModel` | `true` to use the harness small-model prompt. |
| `eval.remote.<name>.timeoutSeconds` | Request timeout. |
| `eval.judge` | `anthropic` (default) or `openai`. |
| `eval.judge.anthropic.model` | Judge model when the judge is `anthropic`. It uses the `eval.anthropic.*` address and key. |
| `eval.judge.openai.baseUrl`, `.model`, `.contextTokens`, `.timeoutSeconds` | Judge endpoint when the judge is `openai`. |
| `eval.fast` | Fast model: `target` (default), `anthropic` or `remote.<name>`. |
| `eval.fast.anthropic.model` | Fast model when `eval.fast=anthropic`. |
| `eval.local.port` | Host port of the local llama.cpp server. |
| `eval.local.model.<k>.file` | GGUF file name (`<k>` is `strong`, `alt` or `small`). |
| `eval.local.model.<k>.alias` | Model name the server exposes, without the quantization. |
| `eval.local.model.<k>.contextTokens` | Context size for the server and the harness. |
| `eval.local.model.<k>.maxContextTokens` | Optional upper limit; a larger `contextTokens` is rejected. |
| `eval.local.model.<k>.smallModel` | `true` to use the harness small-model prompt. |
| `eval.local.model.<k>.extraArgs` | Extra llama.cpp server arguments. |
| `eval.embedding.modelDir` | Folder with the embedding model files (`model_quantized.onnx`, `vocab.txt`). Not committed; see below. |
| `eval.embedding.sha256` | Expected SHA-256 of `model_quantized.onnx`. The loader refuses the model when this is unset or differs. |
| `eval.timeout.connect` | Seconds allowed for a reachability probe. |
| `eval.timeout.warmup` | Seconds allowed for the warm-up call. |
| `eval.trials` | Default number of trials. |
| `eval.outputDir` | Results folder (git-ignored, survives `mvn clean`). Layout: `<model>/run-<timestamp>/` with `run.json`, `<feature>/<case>/<strategy>/result.json` and traces, plus `report.md` and `summary.json` written when the run ends; `<model>/baselines/`; `summary/merged.md` compares the latest run of every model; `history/` keeps one compact recap per run, `<date>_<model>.md` and `.json`, plus `INDEX.md` (not git-ignored: commit it to keep the eval history). |

## Embedding model

The embedding grader uses bge-small-en-v1.5 (int8 ONNX), run in-process with ONNX Runtime and a pure-Java WordPiece
tokenizer. The files are not committed. Download them into `eval.embedding.modelDir` (default
`core/jllm-eval/eval-models/bge-small-en-v1.5`):

```
https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx   (34014426 bytes)
https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/vocab.txt                   (30522 lines)
```

Expected SHA-256 of `model_quantized.onnx` (matches the Hugging Face LFS hash of the file):
`6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4`. The loader hashes the file and fails on a
mismatch, or when `eval.embedding.sha256` is unset. Tests that need the model are skipped when the files are missing.
Vectors are cached on disk under the output folder's `embedding-cache`.

## Judge and fast model

A run has exactly one judge, chosen with `eval.judge`: Haiku 5.5 through the Anthropic API (the default), or one
OpenAI-compatible endpoint. There is no fallback and no mixing. The judge is configured separately from the target,
even when both use the same server.

The run is marked as self-graded (`selfGraded` in `run.json`) when the judge and the target are the same model on the
same server.

The fast model is the target itself unless `eval.fast` selects Haiku 5.5 or a remote entry.

## Connection check

Before any test runs, the target, the judge and the fast model (when it is a separate endpoint) are each checked:
the server must be reachable and must serve the configured model. Each then receives one short warm-up call. Warm-up
times are stored in `run.json`, apart from test timings. If anything fails, the run stops with a message naming the
entry and its address.

## Local models

The local target is a llama.cpp server that you start yourself with docker compose. The eval treats it like a remote
server: it never starts, stops or restarts it, and the connection check fails with a clear message if the server is
down or serves a different model name.

The compose file and one environment file per model are in `docker/`:

```
cd core/jllm-eval/docker
docker compose --env-file strong.env up -d                  # start a model
docker compose --env-file alt.env up -d --force-recreate    # switch to another model
docker compose down                                         # stop
```

Each `docker/<k>.env` file sets the pinned image, the port, the host folders for models and download cache, the model
file, the alias and the context size (`CTX_TOKENS`). The server runs with a single request slot and loads only that
one model.

The alias is the model name the server reports, and it never includes the quantization. Set a smaller `CTX_TOKENS`
(for example `65536`) to force a shorter context in tests. The file name, alias and context size also appear under
`eval.local.model.<k>.*` in `eval.properties`; keep the two in sync.

## Run metadata

At startup the run writes `run.json` to its output folder. It records the target type and name, the model
identifier, the quantization when it can be read from the file name, the context size, the server or image
version, the judge, the fast model, the self-graded flag, the start time, the git commit and the warm-up times.

## Getting model clients

`EvalModels.target()`, `EvalModels.judge()` and `EvalModels.fast()` return ready-to-use harness model clients. They
are built with the harness's own provider classes.

## Classes

All classes are under `src/test/java/org/kendar/llm/eval`.

| Class | Role |
|---|---|
| `EvalConfig` | Loads, resolves and validates the settings. |
| `ModelEndpoint` | One resolved endpoint; converts to harness provider settings. |
| `Secrets` | Registers secrets and masks them in output. |
| `ConnectionCheck` | Reachability, served model and warm-up. |
| `RunMetadata` | Describes the run and writes `run.json`. |
| `EvalRun` | Sets up the run once and shares it with all tests. |
| `EvalModels` | Entry point for the target, judge and fast clients. |
