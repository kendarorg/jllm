# jllm-eval

Test-only module that evaluates the harness with real language models. This step is infrastructure only: no cases,
graders or reports yet. It is **not** part of the default build; it exists only under the `eval` profile.

## Running

For a `local.*` target, start the llama.cpp server first with docker compose (the eval never starts it; see
[Local server](#local-server-docker-compose-by-hand)):

```
cd core/jllm-eval/docker
docker compose --env-file strong.env up -d     # strong | alt | small
cd ../../..
```

Then run the tests from the repository root:

```
mvn -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=anthropic
mvn -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=remote.mac
mvn -Peval -pl core/jllm-eval -am test -Dgroups=smoke -Deval.target=local.strong
mvn -Peval -pl core/jllm-eval -am test -Dgroups=unit            # no model needed
```

- `-Dgroups`: `smoke`, `full`, `strategy`, `unit` (checks that need no model). Default `smoke,unit`.
- Everything runs strictly sequentially: one fork, no parallel classes or methods.
- Needs JDK 21 (`JAVA_HOME`).

## Configuration

All settings live in `src/test/resources/eval.properties`. Any key can be overridden by `-D<key>=value` or by the
environment variable made of the key in upper case with non-alphanumerics replaced by `_`
(`eval.target` -> `EVAL_TARGET`). Order: `-D`, environment, file. Startup validation reports **every** invalid or
missing key in one message.

Secrets (`*.apiKey`) come only from the environment or `-D`; a secret in the properties file is a validation error.
Secrets are masked in every `toString`, exception message, `run.json` and connection-check message.

| Key | Meaning |
|---|---|
| `eval.target` | `anthropic`, `remote.<name>` or `local.<strong\|alt\|small>` |
| `eval.anthropic.baseUrl` / `.model` / `.contextTokens` / `.timeoutSeconds` | Anthropic API. Model default `claude-haiku-5-5` |
| `eval.anthropic.apiKey` | `EVAL_ANTHROPIC_APIKEY`, `-D`, or `ANTHROPIC_API_KEY` |
| `eval.remote.<name>.protocol` | `openai` (OpenAI-compatible) or `anthropic` |
| `eval.remote.<name>.baseUrl` / `.model` / `.contextTokens` / `.smallModel` / `.timeoutSeconds` | Remote entry |
| `eval.remote.<name>.apiKey` | Optional credential (environment or `-D` only) |
| `eval.judge` | `anthropic` (default) or `openai` |
| `eval.judge.anthropic.model` | Judge model when `anthropic` (uses `eval.anthropic.*` connection and key) |
| `eval.judge.openai.baseUrl` / `.model` / `.contextTokens` / `.timeoutSeconds` / `.apiKey` | Judge endpoint when `openai`; independent of any target entry |
| `eval.fast` | `target` (default), `anthropic` or `remote.<name>` |
| `eval.fast.anthropic.model` | Fast model when `anthropic` |
| `eval.local.port` | Host port of the compose-run llama.cpp server |
| `eval.local.model.<k>.file` | GGUF file name in the compose models folder (k = `strong`, `alt`, `small`) |
| `eval.local.model.<k>.alias` | Model name the server exposes, **without quantization** (`--alias`) |
| `eval.local.model.<k>.contextTokens` | Context size passed to the server (`-c`) and to the harness; lower it (e.g. `65536`) to force a smaller window |
| `eval.local.model.<k>.maxContextTokens` | Optional ceiling; `contextTokens` above it is rejected |
| `eval.local.model.<k>.smallModel` | Use the harness small-model prompt |
| `eval.local.model.<k>.extraArgs` | Extra llama.cpp server arguments |
| `eval.timeout.connect` / `eval.timeout.warmup` | Seconds for reachability probes / warm-up |
| `eval.trials` | Default number of trials |
| `eval.outputDir` | Output folder; each run writes `run-<timestamp>/run.json` |

## Targets (exactly one per run)

- **anthropic**: Claude Haiku 5.5 (`claude-haiku-5-5`) through the Anthropic API.
- **remote.&lt;name&gt;**: an external OpenAI-compatible server managed by hand (reference: Qwen3.6-27B served natively
  on an M4 Pro Mac). The eval never starts, stops or reloads it; it only checks it and calls it.
- **local.&lt;strong|alt|small&gt;**: one llama.cpp server in Docker, started **by hand** with `docker/docker-compose.yml`
  (single request slot, only the target model loaded). The eval never starts or stops it.
  Planned models: strong = Qwen3.6-27B 4-bit, alt = gpt-oss-20b,
  small = an 8-12B 4-bit model with the small-model prompt. File names come from the properties.

## Judge and fast model

Exactly one judge per run (`eval.judge`), no fallback and no mixing, configured separately from the target even
when both point at the same server. `selfGraded` in `run.json` is true when judge and target are the same model on
the same server (same protocol, address and model id). The fast model defaults to the target itself.

## Connection check

Before any test, the target, the judge and a separate fast model are probed (`/models`, or the Anthropic model
endpoint) for reachability and for serving the configured model, then get one short warm-up call. Warm-up times are
stored in `run.json` apart from test timings. Any failure stops the run with a message naming the entry and address.

## Local server (docker compose, by hand)

```
cd core/jllm-eval/docker
docker compose --env-file strong.env up -d                    # load one model
docker compose --env-file alt.env up -d --force-recreate      # switch model
docker compose down
```

`docker/<k>.env` holds image (pinned), port, host folders, model file, alias (no quantization) and context size
(`CTX_TOKENS`, e.g. 65536). Keep file, alias and context in sync with `eval.local.model.<k>.*`. The eval treats the
server like a remote entry: the connection check fails with a clear message if it is down or serves another alias.
No Testcontainers and no container reaper are used.

## Code (all under `src/test/java/org/kendar/llm/eval`)

| Class | Role |
|---|---|
| `EvalConfig` | Loads, resolves and validates all settings |
| `ModelEndpoint` | One resolved endpoint; converts to harness `ProviderSettings` |
| `Secrets` | Secret registry and masking |
| `ConnectionCheck` | Reachability, served model, warm-up |
| `RunMetadata` | Run description, written as `run.json` |
| `EvalRun` | Per-run singleton: config, connection check, metadata |
| `EvalModels` | `target()`, `judge()`, `fast()`: harness `ModelClient`s built by `ProviderCatalog` |

## Not verified in this step

Live-model acceptance (smoke test with each target, the compose-run local servers) was not run: no model was connected
when this module was written. The model-free checks (`-Dgroups=unit`) pass.
