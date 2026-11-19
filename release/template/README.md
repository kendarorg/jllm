# jllm: ready to run

Requirements: Java 21 or newer (`java -version`).

## Start

| | Linux / macOS | Windows |
|---|---|---|
| Terminal | `./run-cli.sh` | `run-cli.cmd` |
| Browser (http://localhost:8080) | `./run-web.sh` | `run-web.cmd` |

Sign in to the web app with `admin/admin`, `poweru/poweru` or `guest/guest`. The password of each user is its login
until the user picks another one: change it before exposing the app beyond localhost.

| User | Can use |
|---|---|
| `admin` | every tool, no approvals; manages global flows and users |
| `poweru` | files, shell, web and scripts |
| `guest` | scripts and web only |

## Choose the model (read this first)

Nothing is preconfigured: you must pick a provider and a model, and a hosted provider charges you per token.
Providers: `anthropic`, `openai`, `openaiGpt62` (GPT-6.2 and later), `gemini`, `grok`, `qwen`, `ollama`, `llamacpp`
(any OpenAI-compatible server).
A local Ollama or llama.cpp needs no key and costs nothing.

### Where to put the key (token)

Never in a file you share or commit. Best first:

1. **An environment variable:** `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `GROK_API_KEY`,
   `QWEN_API_KEY`.
   - Windows (PowerShell, permanent): `[Environment]::SetEnvironmentVariable('ANTHROPIC_API_KEY','sk-...','User')`
   - Linux/macOS: `export ANTHROPIC_API_KEY=sk-...` (add it to `~/.bashrc` or `~/.zshrc`)
2. **A settings file**, with the key read from the environment: `"apiKey": "${ANTHROPIC_API_KEY}"`
3. **The command line**, for one session only: `--api-key sk-...` (visible in the process list)

### CLI: quick start, no file needed

```sh
./run-cli.sh --provider anthropic --model <model>
./run-cli.sh --provider ollama --model qwen3:8b --small-model
```

### CLI: settings file

So you do not retype it. Create `~/.jllm/settings.json` (Windows: `%USERPROFILE%\.jllm\settings.json`). It applies to
every start; `settings/settings.json` of this folder is merged on top and only enables the plugins.

```json
{
  "providers": [
    { "name": "claude", "type": "anthropic", "model": "<model>", "apiKey": "${ANTHROPIC_API_KEY}", "order": 1 },
    { "name": "gpt",    "type": "openai",    "model": "<model>", "apiKey": "${OPENAI_API_KEY}",    "order": 2 },
    { "name": "local",  "type": "ollama",    "baseUrl": "http://localhost:11434/v1", "model": "qwen3:8b",
      "requireApiKey": false, "contextTokens": 32000, "smallModel": true }
  ],
  "defaultProvider": "claude"
}
```

- `name` is yours; `type` is one of the providers above; `baseUrl` is only for local or self-hosted servers.
- `order` chains fallbacks: if `claude` fails after its retries, `gpt` is tried. Leave it out for no fallback.
- `${VAR}` must exist, or the start fails. In the chat, `/model` lists the providers and `/model <name>` switches.
- `contextTokens` is the model's real window; `smallModel` adds rules that help small local models.

### Web app

The web app reads properties, not the `providers` list. Either pass arguments:

```sh
./run-web.sh --jllm.provider=anthropic --jllm.model=<model>      # key from ANTHROPIC_API_KEY
./run-web.sh --jllm.provider=ollama --jllm.model=qwen3:8b --jllm.base-url=http://localhost:11434/v1
```

or edit `application.properties` in this folder (read at every start, no rebuild):

```properties
jllm.provider=anthropic
jllm.model=<model>
jllm.api-key=${ANTHROPIC_API_KEY}
```

### Tokens and cost

- `--context-tokens`: the model's window (default 100000). Web: `jllm.context-tokens`.
- `--max-tokens`: output limit of one answer. Web: `jllm.max-tokens`.
- `--max-turns`: model calls per request.
- `--pricing.input-per-million`, `--pricing.output-per-million`: USD, for the cost estimate. Web: `jllm.pricing.*`.

Long chats and agent loops use many tokens: start with a small, cheap model and watch the usage.

Other global settings in `~/.jllm/settings.json`: permissions (allow/ask/deny rules), hooks, MCP servers, context
strategy. Details: [usage](https://github.com/kendarorg/jllm/blob/main/docs/reference/usage.md).

## Contents

| Path | What |
|---|---|
| `jllm-cli.jar`, `jllm-web.jar` | the two front ends (the GraalJS libraries for `run_js` are inside) |
| `plugins/` | `js` (`run_js`), `pdf` and `docs` (document readers), `jllm-workflows`, `jllm-workflow-web` |
| `settings/settings.json` | enables the plugins above; remove an entry to disable one |
| `application.properties` | web app properties (provider, model, key); all commented out |
| `webauth/` | web users and roles (`users/<id>.json`, `permissions/<role>.json`); edit freely, no restart |
| `examples/flows/writing-helper.json` | a first workflow with choices (see below) |

## Example workflow

`writing-helper` asks the subject (text), the kind of text and the style (single choices), writes it, then lets you
pick: keep it, make it shorter, or translate it to Italian. Paste it in the JSON view of the Flows screen of the web
app, then start it from the chat with `/flow-start writing-helper`. More:
[workflows](https://github.com/kendarorg/jllm/blob/main/docs/guides/workflow.md).

## Security

The plugins directory runs with full application rights: do not let untrusted users write there. The CLI has no
isolation by default (shell commands run as you). Read the
[security model](https://github.com/kendarorg/jllm/blob/main/docs/enterprise/security-model.md) before using it on
anything important.
