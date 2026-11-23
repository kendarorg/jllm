# Deployment

Three shapes, from lightest to heaviest.

| Shape | What you run | When |
|---|---|---|
| **Embedded** | the `jllm-harness` jar inside your application | the agent is a feature of an existing service |
| **Web** | `jllm-web` (Spring Boot jar) | an internal chat for a team, behind your proxy |
| **Custom web** | `jllm-web-spring-starter` in your own Spring Boot app | your own sign-in, UI and stores |

[`jllm-cli`](../../jllm-cli/README.md) is the terminal front end, for developers' machines.

## Requirements

Java 21. A model: Anthropic or any OpenAI-compatible endpoint, including local ones (Ollama, vLLM, llama.cpp). Docker,
only for the Docker sandbox.

## Hardening checklist

- [ ] Put `jllm-web` behind a reverse proxy with TLS and set `jllm.web.public-url` to the external URL. Event streams
      need proxy buffering off and no response timeout; set `spring.mvc.async.request-timeout=-1`.
- [ ] Use OIDC or your own `WebAuthentication`; turn off the sample password mode (`jllm.auth.mode=oidc`).
- [ ] With the sample password mode, set `jllm.web.use-real-password=true` so no user keeps the default password; the admin password is in the startup log (WARN, once).
- [ ] The web app sends a `Content-Security-Policy` (same-origin only, no framing), `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. Tighten or replace the policy with `jllm.web.content-security-policy`; `off` removes the CSP header (do not do this when exposed). A reverse proxy must not strip these headers.
- [ ] Set `jllm.web.enforce-password-change-on-loopback=true` when anything on the host forwards remote traffic (reverse proxy, SSH or cloud tunnel, sidecar, port forward). The loopback exemption of the default-password enforcement is off when a forwarding header is present or `server.address` is unset, `0.0.0.0` or `::`; a proxy, tunnel or sidecar that adds no forwarding header makes remote requests look local, so the exemption must not be relied on there.
- [ ] Set `web.blockLocalHost=true` (settings file) so `web_fetch` and `web_download` cannot reach loopback, link-local or private addresses; add `web.blockExtraAddresses` for internal hosts. DNS rebinding is not covered.
- [ ] Use the `docker` sandbox in daemon mode and give `bash` only to roles that need it. Run the JVM as an
      unprivileged user: MCP stdio servers inherit it.
- [ ] Set `JLLM_TOKEN_KEY` (`openssl rand -base64 32`) so OAuth tokens are encrypted at rest. Without it the server
      refuses to start when `jllm.web.use-real-password=true` or `server.address` is not loopback (unset, `0.0.0.0`
      and `::` count as non-loopback), unless you set `jllm.web.allow-unencrypted-tokens=true`; on a local-only server
      it starts with a WARN. Pass secrets by `env:` or `${VAR}`.
- [ ] Add `permissions.deny` rules for what must never run, and visibility lists on every MCP server and REST API.
- [ ] Mark read-only sources `readOnly` and give your own tools the honest `ToolKind`.
- [ ] Set `maxTurns`, `maxTokens` and `contextTokens`; set pricing to see cost.
- [ ] Decide where chat history lives and who can read it (`.jllm/history` files in the library and CLI; the SQL database in the `jllm-web` release).
- [ ] Wire metrics ([Observability](observability.md)) and an audit hook ([Governance](governance-and-audit.md)).

## State and scaling

In the library and the CLI state is on local disk (chat history under `.jllm/history`). The `jllm-web` release keeps
users, roles, skills, sessions, tokens, settings and chats in a SQLite file by default (`jllm.store=file`: `webauth/`
files and sessions in memory, so a restart signs everyone out). That fits one instance. Beyond it:

- Chat, sub-agent and state stores are ports (`ChatSessionStore`, `SubAgentRunLog`, `StateStore`).
- Users, roles, skills and login sessions have repository interfaces in `jllm-harness`. `jllm-sql-spring-starter`
  ([README](../../web/jllm-sql-spring-starter/README.md)) implements them, and the chat, state, settings, trust and token
  stores, in SQL: SQLite (default, bundled), MySQL or PostgreSQL (both **experimental** until run against a real server),
  chosen by the scheme of `jllm.sql.url` alone; plain JDBC, no JPA/Hibernate/Spring Data. The release ships it in
  `lib-ext/sql` (SQL by default; `jllm.store=file` keeps the files). The MySQL (GPL) and PostgreSQL drivers are not
  bundled: `setup` / `fetch-sql-driver` download them with a pinned SHA-256. Set `JLLM_TOKEN_KEY` to encrypt stored tokens.
  `schema_version` records the schema version; there is no migration runner, changing the schema means deleting the database. Plugin storage (workflows included) is in SQL too (files under `.jllm/plugin-data` with `jllm.store=file`); workspace files stay on disk.
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
mvn -pl jllm-cli -am package        # one shaded runnable jar
mvn -pl jllm-web -am package        # Spring Boot jar
```

Libraries are published as `org.kendar.llm` artifacts; see [PUBLISHING](../reference/publishing.md).

Step-by-step hardening and enterprise setup: [Securing a jllm-web deployment](securing-and-enterprise-setup.md).
