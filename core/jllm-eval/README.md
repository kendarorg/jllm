# jllm-eval

Evaluates the jllm harness against real language models. The module contains only tests and is left out of the
default build: it is available only through the `eval` Maven profile.

It provides the shared infrastructure for evaluations: configuration, access to the target, judge and fast models,
a connection check and run metadata.

## Smoke script

`scripts/run-smoke.sh` (bash) and `scripts/run-smoke.ps1` (PowerShell) run the eval through Maven with a tested model
(target) and a judge that each pick their own provider, model, endpoint and key. The fast model is the target. The
output goes to the console and to `eval-results/smoke-<target provider>-<timestamp>.log`.

```
./scripts/run-smoke.sh                                   # choose a preset from scripts/envs/
./scripts/run-smoke.sh llamacpp-qwen-judge-sonnet.env    # use that preset
./scripts/run-smoke.sh none                              # no preset: environment and defaults only
.\scripts\run-smoke.ps1 nvidia.env                       # same from PowerShell
```

The script checks that each endpoint answers and serves the model before starting Maven.

### Providers

| Provider | API | Key | Default base | Default model |
|---|---|---|---|---|
| `anthropic` | Anthropic | required | `https://api.anthropic.com` | `claude-haiku-5-5` |
| `nvidia` | OpenAI-compatible (NVIDIA NIM) | required | `https://integrate.api.nvidia.com` | `meta/muse-glimmer-30b` |
| `ollama` | OpenAI-compatible | always `none` | `http://localhost:11434` | `qwen3:30b` |
| `llamacpp` | OpenAI-compatible (llama.cpp server) | always `none` | `http://localhost:8080` | `qwen-local` |

`*_BASE` is the machine address only (`scheme://host[:port]`); the script adds the API path `/v1`.

### Presets (`scripts/envs/*.env`)

A preset is a file of `NAME=VALUE` lines with the settings below. Without an argument the script lists the presets and
asks which one to use (`0` = none). A variable already set in the environment wins over the preset. Presets must not
contain keys: the script refuses any `*API_KEY*` line.

| Preset | Target | Judge |
|---|---|---|
| `anthropic.env` | Haiku 5.5 | Haiku 5.5 (self-graded) |
| `anthropic-sonnet.env` | Sonnet 5.5, medium effort | Sonnet 5.5 |
| `nvidia.env` | NVIDIA NIM muse-glimmer-30b | same model (self-graded) |
| `nvidia-judge-anthropic.env` | NVIDIA NIM muse-glimmer-30b | Haiku 5.5 |
| `ollama.env` | Ollama qwen3:30b, thinking minimal | same model (self-graded) |
| `llamacpp-qwen-judge-sonnet.env` | llama.cpp `qwen-local` (Qwen 27B) at `http://192.168.1.96:17001` | Sonnet 5.5, medium effort |

To add a setup, copy a preset and change it.

### Keys

Keys are never stored in a preset. For `anthropic` and `nvidia` the script reads them from the environment and, when
not set there, asks for them with hidden input:

- target: `TARGET_API_KEY`, then `ANTHROPIC_API_KEY` / `NVIDIA_API_KEY`
- judge: `JUDGE_API_KEY`, then `ANTHROPIC_API_KEY` / `NVIDIA_API_KEY`; when target and judge use the same provider an
  empty answer reuses the target key

`ollama` and `llamacpp` always use the key `none` (never read, never asked). Keys reach the JVM only through
environment variables, which the script removes when Maven ends.

### Settings

Per role, prefix `TARGET_` or `JUDGE_`:

| Setting | Meaning |
|---|---|
| `*_PROVIDER` | `anthropic`, `nvidia`, `ollama` or `llamacpp`. Target default `anthropic`; judge default = target provider. |
| `*_MODEL` | Model id. Judge default: the target model on the same provider, else the provider default. |
| `*_BASE` | Machine address, without `/v1`. |
| `*_CTX_TOKENS` | Context tokens (`anthropic` 200000, others 16000). Must not exceed what the server allocates. |
| `*_RPM` | Requests per minute; `0` = unpaced (`anthropic` 300, `nvidia` 10, others 0). |
| `*_THINKING` | `minimal`, `low`, `medium` or `high`; empty = the model's default (`ollama` defaults to `minimal`). |

With both roles on `anthropic`, the judge shares the target's base, context, timeout and rpm (there is one
`eval.anthropic.*` block); only `JUDGE_MODEL` and `JUDGE_THINKING` apply to it.

Shared:

| Setting | Default | Meaning |
|---|---|---|
| `TEST_GROUPS` | `all` | `all` or a comma separated list of `smoke`, `full`, `strategy`, `compaction`, `unit`. |
| `TESTS` | empty | Optional comma separated test classes (`-Dtest`). |
| `TRIALS` | `1` | Trials per case (`eval.trials`). |
| `MAX_TRIALS` | `2` | Cap on the trials of any case (`eval.maxTrials`). |
| `TIMEOUT_SECONDS` | `300` | Per model request, and warm-up. |
| `CASE_TIMEOUT_SECONDS` | `TIMEOUT_SECONDS` | Cap on each user turn of a case (`eval.caseTimeoutSeconds`). |
| `COMPACTION_CTX_TOKENS` | `65536` | Context of the `compaction` group (`eval.compaction.contextTokens`). |
| `PARALLELISM` | `anthropic` 20, `nvidia` 2, others 1 | Test classes run at once; `1` = strictly sequential. |
| `IGNORE_TEST_FAILURES` | `1` | `1`: failed cases never fail the Maven build, so the run always writes its summary. |
| `SYSTEM_PROMPT_EXTRA` | reminder for `nvidia`, else `none` | `eval.systemPrompt.extra`; `none` turns it off. |

The PowerShell script picks a JDK 21+ by itself (`JAVA_HOME` if it qualifies, else the usual install folders); the
bash script uses the `mvn` and JDK on the `PATH`.

### Progress and results

During the run the log shows the planned number of case runs and, after each one,
`progress <done>/<planned> done, <to do> to do: <passed> passed, <failed> failed`, plus `not run` for cases whose
group was skipped or stopped early. The count follows the JUnit selection (`TEST_GROUPS`, `TESTS`).

Each run adds a recap to `eval-results/history/` and a row to `eval-results/history/INDEX.md`. Only `INDEX.md` is
tracked by git; the per-run files are ignored.

## Quick start

1. Choose a target (see [Targets](#targets)). For a model served by llama.cpp in docker on a remote GPU machine,
   deploy it with `./docker/llm.sh up strong --host <address>` (bash) or `.\docker\llm.ps1 up strong -DockerHost
   <address>` (PowerShell): the script checks the remote docker host and GPU, loads the model, waits for it and prints
   the settings to use (see [Guided server setup](#guided-server-setup)).
2. Pick or write a preset in `scripts/envs/` and run the smoke script (see [Smoke script](#smoke-script)).

`TEST_GROUPS` selects the test groups: `smoke`, `full`, `strategy` or `compaction` (the long sessions that force
compaction; they run with `COMPACTION_CTX_TOKENS`). The extra group `unit` holds the checks that need no model at all.

Test classes run one after the other by default. With `PARALLELISM` above 1, up to that many test classes run at once;
the cases of one class stay sequential. All requests to a host still go through one shared `RequestPacer`, so the
rpm limits hold.

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
| `eval.anthropic.maxRpm` | Requests per minute allowed to the Anthropic API, `0` (default) = unpaced. Shared by target, judge and fast model; a 429 then pauses the host and is repeated (see `eval.rateLimit.*`). |
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
| `eval.maxTrials` | Cap on the trials of any case, including cases that set their own count. Default `10`. The smoke script sets it from `MAX_TRIALS` (presets: `2`). |
| `eval.compaction.contextTokens` | Context size for the cases tagged `compaction` only; every other case uses the target's `contextTokens`. Default `65536`. The smoke scripts set it from `COMPACTION_CTX_TOKENS`. |
| `eval.rateLimit.retries` / `waitSeconds` / `stepSeconds` | On an HTTP 429 from a paced endpoint (one with `maxRpm`) every call to that host pauses for `waitSeconds` (default 10), plus `stepSeconds` (default 5) per further repeat, and the call is repeated, up to `retries` times (default 5: 10 s, 15 s, 20 s, 25 s, 30 s), on top of the harness client's own short retry. |
| `eval.systemPrompt.extra` | Text added to the system prompt of every case (empty by default). The smoke script sets, by default when the target is `nvidia`, a reminder to call tools through the structured interface, because some hosted models write a tool call as plain text; `SYSTEM_PROMPT_EXTRA=none` turns it off. A case's own `systemPrompt` setup replaces it. Because it changes the prompt under test, results with and without it are not comparable. |
| `eval.caseTimeoutSeconds` | Cap on each user turn of a case, in seconds. `0` (default) keeps each case's own timeout. The smoke scripts set `300` (`CASE_TIMEOUT_SECONDS`, defaults to `TIMEOUT_SECONDS`), which also caps the `4-strategies` sessions (they declare 1800): on a slow model set `CASE_TIMEOUT_SECONDS=1800` for those runs. |
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

A run has exactly one judge, chosen with `eval.judge`: Sonnet 5.5 through the Anthropic API (the default, a different model than the Haiku 5.5 default target, so a default run is not self-graded), or one
OpenAI-compatible endpoint. There is no fallback and no mixing. The judge is configured separately from the target,
even when both use the same server.

The run is marked as self-graded (`selfGraded` in `run.json`) when the judge and the target are the same model on the
same server.

The fast model is the target itself unless `eval.fast` selects Haiku 5.5 or a remote entry. Context summaries are written by the fast model: with the target as fast model a slow target pays 30-180 s per summary, so select a faster one for the compaction cases. The `4-strategies` sessions compact at fixed points with `/compact` (outside any turn, so the summary does not count against the turn timeout); budget-driven compaction still happens inside turns.

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
5. Copy `scripts/envs/llamacpp-qwen-judge-sonnet.env` to a new preset and set `TARGET_BASE` to the machine address
   (`http://192.168.1.96:8080`, no `/v1`), `TARGET_MODEL` to that id and `TARGET_CTX_TOKENS` not larger than `n_ctx`.
6. The judge is Sonnet 5.5 through the Anthropic API: the script asks for its key unless `JUDGE_API_KEY` or
   `ANTHROPIC_API_KEY` is set. The target key is always `none`.
7. Run, in this order, so a setup mistake costs little (PowerShell; bash takes the same variables):

   ```
   $env:TEST_GROUPS = "unit";     .\scripts\run-smoke.ps1 <your preset>.env
   $env:TEST_GROUPS = "smoke";    .\scripts\run-smoke.ps1 <your preset>.env
   $env:TEST_GROUPS = "full";     .\scripts\run-smoke.ps1 <your preset>.env
   $env:TEST_GROUPS = "strategy"; .\scripts\run-smoke.ps1 <your preset>.env
   ```

   The script checks the endpoint first; if it stops, the message names the role and the address: unreachable
   (firewall, wrong IP, stack down) or a different model id (fix step 5).
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

The alias is the model name the server reports, and it never includes the quantization. The `docker/*.env` files allocate `98304` (96k) so the compaction cases can run at 64k while the other cases keep their
own `contextTokens`; set a smaller `CTX_TOKENS` to force a shorter context in tests. The file name, alias and context size also appear under
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
| `Secrets` | The secret values of one configuration (`EvalConfig.secrets()`); masks them in output. Per config, not global. |
| `ConnectionCheck` | Reachability, served model and warm-up. |
| `RunMetadata` | Describes the run and writes `run.json`. |
| `EvalRun` | Sets up the run once and shares it with all tests. |
| `EvalModels` | Entry point for the target, judge and fast clients. |
