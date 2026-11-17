# jllm-eval

Evaluates the jllm harness against real language models. The module contains only tests and is left out of the
default build: it is available only through the `eval` Maven profile.

It provides the shared infrastructure for evaluations: configuration, access to the target, judge and fast models,
a connection check and run metadata.

## Quick start

1. Choose a target (see [Targets](#targets)). For a model served by llama.cpp in docker on a remote GPU machine,
   deploy it with `./docker/llm.sh up strong --host <address>` (bash) or `.\docker\llm.ps1 up strong -DockerHost
   <address>` (PowerShell): the script checks the remote docker host and GPU, loads the model, waits for it and prints
   the settings to use (see [Guided server setup](#guided-server-setup)).
2. Run the tests from this folder (`core/jllm-eval`) with JDK 21:

```
mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=anthropic
mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=remote.mac
mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=local.strong
```

`-Dgroups` selects the test groups: `smoke`, `full` or `strategy`. The extra group `unit` holds the checks that need
no model at all (`mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test -Dgroups=unit`). The default is `smoke,unit`.

Tests, classes and methods always run one after the other, in a single JVM.

## Targets

Each run evaluates exactly one target, chosen with `eval.target`.

| Target | Description |
|---|---|
| `anthropic` | Claude Haiku 5.5 (`claude-haiku-5-5`) through the Anthropic API. |
| `remote.<name>` | An OpenAI-compatible server you run yourself, defined by a named entry in the properties file (for example Qwen3.6-27B served natively on a Mac, or in docker on a GPU machine such as `remote.gpu`; `docker/llm.sh` and `docker/llm.ps1` set up the latter). The eval only calls it: it never starts, stops or reloads it. |
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

## Guided server setup

`docker/llm.sh` (bash) and `docker/llm.ps1` (PowerShell) deploy the llama.cpp server of `docker/docker-compose.yml`
step by step. They assume the docker host is a **remote machine** with the GPU: they run on your machine, from this
folder, and drive the remote docker daemon. Nothing is checked locally except the docker CLI.

```
./docker/llm.sh up strong --host 192.168.1.96          .\docker\llm.ps1 up strong -DockerHost 192.168.1.96
./docker/llm.sh status --host 192.168.1.96             .\docker\llm.ps1 status -DockerHost 192.168.1.96
./docker/llm.sh down --host 192.168.1.96               .\docker\llm.ps1 down -DockerHost 192.168.1.96
```

- **Host:** `192.168.1.96`, `user@192.168.1.96` or `ssh://user@host` reach the daemon over ssh (key-based login, and the
  docker CLI plus `docker compose` installed here). `tcp://host:2376` works too. The script always asks for the host;
  `--host`/`-DockerHost`, `LLM_DOCKER_HOST` or `DOCKER_HOST` only provide the default, which Enter accepts. Without a
  terminal the default is used as is.
- **Model** is `strong`, `alt` or `small` (default asks).
- **GPU:** checked through docker, by running `nvidia-smi` in a container on the remote host, not on this machine.
- **Model file:** checked through docker too. `MODELS_DIR` and `CACHE_DIR` in `docker/<k>.env` are paths **on the
  docker host**, and the `.gguf` must already be there. The env files carry example paths: set `MODELS_DIR` in the
  environment to override one, or the script asks. If the file is missing it prints a Hugging Face download hint to run
  on the docker host.
- **Deploy:** pulls the image (it is not built) and starts the container with one request slot, then waits for the
  model to load, polling the docker host's address, not `localhost`.
- **Result:** prints the model id, `n_ctx`, slot count and the `eval.remote.gpu.*` values and Maven command for the eval
  machine. Open the port (8080 by default) in the docker host's firewall if the eval machine cannot reach it.

For a server on the machine that runs the eval, use the compose commands in [Local models](#local-models) and
`-Deval.target=local.<k>` instead.

## Remote llama.cpp server (example: docker host 192.168.1.96)

Use this when the model runs in docker on another machine, such as a GPU box on the LAN. The eval never starts or
stops it; you manage the stack there. The target is a named `remote.<name>` entry (`remote.gpu` ships in
`eval.properties`), not `local.*`, because `local.*` always talks to this machine.

All commands below are run from the `core/jllm-eval` folder.

If the GPU machine runs this repo's `docker/` stack, deploy it from here with `docker/llm.sh` or `docker/llm.ps1` and
`--host` (see [Guided server setup](#guided-server-setup)) and continue at step 4. If it runs a different compose stack (presets
chosen with `LLM_ENV`), follow all the points:

**On the GPU machine** (docker compose stack with `LLM_ENV` set to the Qwen3.6-27B preset):

1. Keep `NP=1` (one slot) and leave `CACHE_RAM` empty. Cache-ratio and strategy results are only valid on a single
   slot; with several slots or a host prompt cache they are flagged unreliable.
2. Keep `BIND_ADDR=0.0.0.0` (the default) and open `LLAMA_PORT` (8080) in the host firewall.
3. Leave `API_KEY` at `none`. If you set a real one, give the eval the same value in `EVAL_REMOTE_GPU_APIKEY`.

**On the machine running the eval:**

4. Check the server answers and note two values:
   `curl http://192.168.1.96:8080/v1/models` shows the model id; `curl http://192.168.1.96:8080/props` shows `n_ctx`.
5. In `eval.properties` (or with `-D`), make `eval.remote.gpu.model` equal that id and `eval.remote.gpu.contextTokens`
   not larger than `n_ctx`. A different address or entry name works the same way: define `eval.remote.<name>.*` and
   select it with `-Deval.target=remote.<name>`.
6. Set the judge key in the environment, never in a file or on a shared command line. The default judge is Haiku 5.5
   through the Anthropic API, so the key is for that, not for the target (PowerShell):

   ```
   $env:ANTHROPIC_API_KEY = "<your key>"
   $env:JAVA_HOME = "$HOME\.jdks\corretto-21.0.9"
   ```

7. Run, in this order, so a setup mistake costs little:

   ```
   mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test "-Dgroups=unit"
   mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test "-Dgroups=smoke" "-Deval.target=remote.gpu"
   mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test "-Dgroups=full" "-Deval.target=remote.gpu"
   mvn -f ../../pom.xml -Peval -pl core/jllm-eval -am test "-Dgroups=strategy" "-Deval.target=remote.gpu"
   ```

   The connection check runs first. If it stops the run, the message names the entry and the address: unreachable
   (firewall, wrong IP, stack down) or a different model id (fix step 5). Use `-Deval.trials=1` for a quick first pass.
8. Read the results in `eval-results/qwen3.6-27b/run-<timestamp>/report.md`. The recap is also copied to
   `eval-results/history/`.

The judge differs from the target here, so the run is not self-graded. A 27B model on one slot is slow: the default
request timeout is 1800 s, and the `full` and `strategy` groups can take hours.

## Local models

The local target is a llama.cpp server that you start yourself with docker compose. The eval treats it like a remote
server: it never starts, stops or restarts it, and the connection check fails with a clear message if the server is
down or serves a different model name.

The easiest way is the guided script described in [Guided server setup](#guided-server-setup). The compose file and
one environment file per model are in `docker/`, to run by hand:

```
cd docker
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
