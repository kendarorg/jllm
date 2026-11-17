# Deployment

Three shapes, from lightest to heaviest.

| Shape | What you run | When |
|---|---|---|
| **Embedded** | the `jllm-harness` jar inside your application | the agent is a feature of an existing service |
| **Web** | `jllm-web` (Spring Boot jar) | an internal chat for a team, behind your proxy |
| **Custom web** | `jllm-web-commons` in your own Spring Boot app | your own sign-in, UI and stores |

[`jllm-cli`](../../jllm-cli/README.md) is the terminal front end, for developers' machines.

## Requirements

Java 21. A model: Anthropic or any OpenAI-compatible endpoint, including local ones (Ollama, vLLM, llama.cpp). Docker,
only for the Docker sandbox.

## Hardening checklist

- [ ] Put `jllm-web` behind a reverse proxy with TLS and set `jllm.web.public-url` to the external URL. Event streams
      need proxy buffering off and no response timeout; set `spring.mvc.async.request-timeout=-1`.
- [ ] Use OIDC or your own `WebAuthentication`; turn off the sample password mode (`jllm.auth.mode=oidc`).
- [ ] Use the `docker` sandbox in daemon mode and give `bash` only to roles that need it. Run the JVM as an
      unprivileged user: MCP stdio servers inherit it.
- [ ] Set `JLLM_TOKEN_KEY` so OAuth tokens are encrypted at rest. Pass secrets by `env:` or `${VAR}`.
- [ ] Add `permissions.deny` rules for what must never run, and visibility lists on every MCP server and REST API.
- [ ] Mark read-only sources `readOnly` and give your own tools the honest `ToolKind`.
- [ ] Set `maxTurns`, `maxTokens` and `contextTokens`; set pricing to see cost.
- [ ] Decide where chat history lives and who can read it (`.jllm/history` files by default).
- [ ] Wire metrics ([Observability](observability.md)) and an audit hook ([Governance](governance-and-audit.md)).

## State and scaling

By default state is on local disk: chat history under `.jllm/history`, users, roles and skills as files, web sessions
in memory (a restart signs everyone out). That fits one instance. Beyond it:

- Chat, sub-agent and state stores are ports (`ChatSessionStore`, `SubAgentRunLog`, `StateStore`).
- Users, roles, skills and login sessions have repository interfaces in `jllm-web-commons`. A JDBC implementation is
  **not shipped**; the checklist is in the [jllm-web README](../../jllm-web/README.md#a-possible-extension-a-mysql-store).
- A `TokenStore` that reports `crossProcess()` can be shared between instances.
- A running chat is a virtual thread and an event buffer in one process. Route a chat to one instance (sticky
  routing) unless you supply your own stores and accept that a run does not move.
- Flows survive restarts: a waiting run resumes without repeating finished steps.

## Docker sandbox

`oneshot` starts a container per command, `daemon` keeps one per chat and removes it with the chat, and `remote` runs
the containers on another host and uploads each workspace. Settings and the daemon's security are in
[SANDBOX](../guides/sandbox.md).

## Build

```sh
mvn -pl jllm-cli -am package -DskipTests        # one shaded runnable jar
mvn -pl jllm-web -am package -DskipTests        # Spring Boot jar
```

Libraries are published as `org.kendar.llm` artifacts; see [PUBLISHING](../reference/publishing.md).
