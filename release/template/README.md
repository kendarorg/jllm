# jllm: ready to run

Requirements: Java 21 or newer (`java -version`).

## Start

| | Linux / macOS | Windows |
|---|---|---|
| Terminal | `./run-cli.sh` | `run-cli.cmd` |
| Browser (http://localhost:8080) | `./run-web.sh` | `run-web.cmd` |

Both scripts take the configuration name as an optional first argument (`./run-web.sh unsafe`, `run-cli.cmd unsafe`).
Without it the safe one runs: `webSafe` for the web app, `cliSafe` for the terminal. See [Configurations](#configurations).

Safe web app (`webSafe`): there are no default passwords. Every user gets a random one at start and the administrator's is
in the startup log, shown once (`Generated password for administrator 'admin': ...`): copy it and change it after signing
in. The app listens on `127.0.0.1` only (`server.address` in `configs/webSafe/application.properties`) and each role runs in a
Docker sandbox, so Docker must be installed. To expose it you still need a TLS reverse proxy.

## Configurations

| Name | For | Summary |
|---|---|---|
| `webSafe` | web, real use (default of `run-web`) | loopback, random passwords, Docker sandbox per role (roles `admin`, `user`) |
| `cliSafe` | terminal, real use (default of `run-cli`) | `--mode default` (asks before writes and commands), Docker sandbox, no fallback to the host shell |
| `unsafe` | development and testing only | see below |

**`unsafe` is for development and testing only.** No folder trust, a local shell with full host access, default
passwords (`admin/admin`, `poweru/poweru`, `guest/guest`, the password is the login until the user picks another one;
requests from the local machine are exempt from the password change) and everything allowed. The model can read, change or
destroy anything your user account can. Never expose it on a network and never run it on data you care about. It must be
named explicitly: `./run-web.sh unsafe`, `run-cli.cmd unsafe`.

Features not released yet (folder trust for the CLI, the virtual configuration provider, the shared-local sandbox,
`jllm.chats-dir`) are applied to the safe configurations when they land.

| `unsafe` user | Can use |
|---|---|
| `admin` | every tool; manages global flows and users |
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
every start; `configs/<name>/settings.json` is merged on top and only enables the plugins.

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

or edit `configs/<name>/application.properties` (read at every start, no rebuild):

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

Symbolic links and junctions in the project whose target is outside it: `"files": { "externalLinks": "read" }` (the
default; `none` refuses them, `readwrite` also lets the file tools write through them; the CLI flag is
`--external-links`). The web app ignores this key and never follows such a link: it logs a warning at startup if the
key is set to anything but `none`, so leave it out of the settings file the web app reads.

## Contents

```
Release files (next to each other)         Inside the zip: jllm-<version>/
jllm-<version>.zip                         ├── jllm-cli.jar, jllm-web.jar
SHA256SUMS                                 ├── run-cli.sh/.cmd, run-web.sh/.cmd
setup.sh, setup.ps1                        ├── fetch-graaljs.sh/.cmd/.ps1, fetch-sql-driver.sh/.cmd/.ps1
                                           ├── lib-ext/            GraalJS and SQL drivers: empty until setup / fetch-*
                                           │   ├── sql/            the SQL store jars (shipped)
                                           │   └── manifests/      graaljs-community / graaljs-oracle, mysql, postgres
                                           ├── plugins/            js, pdf, docs, workflows, workflow-web
                                           ├── configs/            unsafe/, webSafe/, cliSafe/ (settings.json, README, webauth, application.properties)
                                           ├── examples/flows/
                                           └── README.md, LICENSE, NOTICE
```

| Path | What |
|---|---|
| `jllm-cli.jar`, `jllm-web.jar` | the two front ends (they do not contain GraalJS) |
| `lib-ext/` | the GraalJS libraries of `run_js`: empty in the zip, filled by `setup` / `fetch-graaljs`; holds the manifests of both editions |
| `fetch-sql-driver.sh`, `.cmd`, `.ps1` | download the MySQL / PostgreSQL JDBC driver into `lib-ext/` (SHA-256 checked; MySQL is GPL and asks to accept). SQLite is bundled. `setup --sql mysql\|postgres\|all\|none` does it too (default all) |
| `lib-ext/sql/` | the SQL store jars (shipped): with them users, chats and tokens live in SQL (SQLite file under the configuration's `webauth/` by default); `jllm.store=file` keeps the files; see `configs/<name>/application.properties` |
| `fetch-graaljs.sh`, `.cmd`, `.ps1` | fill or replace `lib-ext/` with the `community` or `oracle` (commercial) set from Maven Central |
| `plugins/` | `js` (`run_js`), `pdf` and `docs` (document readers), `jllm-workflows`, `jllm-workflow-web` |
| `configs/<name>/config/settings.json` | web (`unsafe`, `webSafe`; the CLI one is `configs/cliSafe/settings.json`): enables the plugins above (remove an entry to disable one) and sets `web.blockLocalHost=true`: `web_fetch` / `web_download` refuse loopback, link-local and private addresses (`false` to reach local services; `web.blockExtraAddresses` adds more) |
| `configs/<name>/application.properties` | web app properties (`unsafe`: provider, model, key, all commented out) |
| `configs/<name>/config/` | the web configuration as files when `jllm.store=file` (settings, instructions, agents); with SQL it is in the database, import it once with `java -jar jllm-web.jar --import-config <dir>`. Never inside the chats folder (`chats/<name>`) |
| `configs/<name>/webauth/` | web users and roles (`users/<id>.json`, `permissions/<role>.json`); edit freely, no restart |
| `examples/flows/writing-helper.json` | a first workflow with choices (see below) |

## JavaScript (`run_js`)

`run_js` runs model-written JavaScript in a sandbox. Its engine, GraalJS, is neither inside the jars nor in this zip:
`setup.sh` / `setup.ps1`, released next to the zip, unzip it, ask the edition (commercial or community; the commercial
terms are shown and must be accepted) and fill `lib-ext/`, which `run-cli` and `run-web` put on the class path. With an empty `lib-ext/` both
front ends start normally and `run_js` is simply not offered (one warning in the log says why). To restore the set or
switch edition: `./fetch-graaljs.sh community` or `./fetch-graaljs.sh oracle` (`fetch-graaljs.cmd` on Windows). It empties
`lib-ext/*.jar` first, downloads from Maven Central and checks the SHA-256 of every jar. The `oracle` set declares the
GraalVM Free Terms and Conditions: read them, then add `--accept-oracle-terms`; accepting them is your decision.
Licences of what is shipped are listed in `NOTICE`. Details:
[JS guide](https://github.com/kendarorg/jllm/blob/main/docs/guides/js.md).

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
