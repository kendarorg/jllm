# Try it: terminal and browser

Two ready-made front ends over the same library. They are working examples, not mandatory parts.

## Build

Java 21 and Maven.

```sh
mvn -pl jllm-cli -am package
mvn -pl jllm-web -am package
```

Set a key first: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GROK_API_KEY` or `QWEN_API_KEY`. A local Ollama needs no key
(`--provider ollama --model qwen3:8b`).

## Terminal

```sh
java -jar jllm-cli/target/jllm-cli-1.4.0-jllm.jar --provider anthropic --mode edit
```

Flags, startup directories, slash commands and local models: [jllm-cli](../../jllm-cli/README.md).

## JavaScript (`run_js`)

The jars do not contain GraalJS: with `java -jar` the `run_js` tool is off (the harness starts, one WARN says why).
From a source checkout run `scripts/setup` once (it downloads the GraalJS set into `.downloaded-libs/graal/` and the MySQL/PostgreSQL JDBC drivers into `.downloaded-libs/<db>/`, `--sql none` skips them, and builds), then run the same jars on it:

```sh
scripts/run-cli.sh --provider anthropic --mode edit            # Windows: scripts\run-cli.cmd
scripts/run-web.sh --jllm.provider=anthropic                   # Windows: scripts\run-web.cmd
```

`scripts/setup --edition community` selects the community edition; the default is the commercial one, whose terms setup asks you to accept (see the
[JS guide](../guides/js.md#licensing)). The release zip has an empty `lib-ext/`: run the released `setup.sh` / `setup.ps1` next to it to unzip it and download the edition you choose.

## Browser

```sh
java -jar jllm-web/target/jllm-web-1.4.0-jllm.jar --provider anthropic
```

Open http://localhost:8080. The reference application has password and OpenID Connect sign-in, roles, skills, a flow
editor and a runs screen. It is meant for localhost or a trusted network; for anything else read the
[security model](../enterprise/security-model.md) and [Deployment](../enterprise/deployment.md). Properties, REST and
SSE API: [jllm-web](../../jllm-web/README.md).

## Permission modes

| Mode | Runs without asking |
|---|---|
| `default` | read-only tools |
| `plan` | reads; writes confined to a scratch directory |
| `edit` | reads and file edits |
| `auto` | everything a fast-model classifier judges safe |

`deny` rules apply in every mode.
