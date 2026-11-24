# TODO

Backlog of open work, grouped by theme and ordered by priority inside each group. `[ ]` open. Items marked *(review)* come from
an earlier security review and must be re-checked against the code before work starts.

Work that has its own plan is **not** listed here, only in the plan: [litellm](litellm.md) (per-user model keys and
limits, role settings, provider management, CLI), [saml](saml.md), [backup-restore](backup-restore.md) (settings
backup, maintenance mode, SQL schema migration), [secdoc](secdoc.md) (auth documentation, doc updates when those
features land), [evalfolder](evalfolder.md), [harborswe](harborswe.md), [sandbox_hard](sandbox_hard.md).

## Plans

- [litellm](litellm.md): LiteLLM integration (per-user model keys and limits, provider management, CLI key storage).
- [saml](saml.md): SAML 2.0 sign-in alongside the existing OIDC provider.
- [backup-restore](backup-restore.md): backup/restore of all web settings with maintenance mode.
- [secdoc](secdoc.md): documentation of how authentication and authorization fit together.
- [evalfolder](evalfolder.md): `eval/` folder layout and build wiring (jllm-eval, jllm-harbor, jllm-swe).
- [harborswe](harborswe.md): running the harness against Terminal-Bench/Harbor and SWE-bench.
- [sandbox_hard](sandbox_hard.md): rework and hardening of the sandbox executors (layout/runner split, factory, local mode, JS sandbox docs).

## 1. Pilot blockers (multi-user web)

- [ ] **Docker sandbox for web chats.** The web release template configures no sandbox: shell commands run on the host.
  - [ ] Set `localFallback=false` in the server settings: no Docker means no shell, never a host shell (today it asks
    the chat user, who will click yes).
  - [ ] Keep `network: none`, or attach containers to a filtered network. The SSRF guard covers only `web_fetch` and
    `web_download`; `curl` in `bash` bypasses it and can reach `169.254.169.254` and the internal network.
  - [ ] Treat the Docker socket as root: rootless Docker, gVisor (`--runtime=runsc`) or a separate host.
  - [ ] One container and volume per chat, removed on chat delete or idle timeout, with disk quotas.
  - [ ] Pin the sandbox image by digest, not by tag.

## 2. Security hardening

### SSRF *(review)*
- [ ] **Fail closed**: if the guard's DNS lookup fails, `AddressPolicy.check` returns and the request proceeds; an
  unresolvable host must be refused.
- [ ] **DNS rebinding**: the host is resolved for the check and again by the HTTP client. Options, best first:
  1. resolver plugged in at resolution time (`InetAddressResolverProvider`, JEP 418, JDK 18+) that drops internal
     addresses, scoped per thread/client so model clients and MCP servers on internal networks are not affected;
  2. egress proxy (Squid, Envoy) that enforces the block, also covering MCP and REST calls;
  3. connect by IP with the original host for TLS (awkward with the JDK `HttpClient`; not first choice).
- [ ] **Missing address ranges** in the local check: `100.64.0.0/10` (CGNAT, Tailscale, Alibaba metadata
  `100.100.100.200`), all of `0.0.0.0/8`, IPv6 forms embedding IPv4 (NAT64 `64:ff9b::/96`, 6to4 `2002::/16`: extract
  and check the embedded address), `198.18.0.0/15`, `192.0.0.0/24`, `240.0.0.0/4`, multicast, broadcast.

### Remote Docker daemon *(review)*
- [ ] The in-container daemon (`sandbox-agent.py`) speaks plain HTTP; the token is fixed for the container's lifetime
  and sent in clear. Make the daemon serve TLS itself (`JLLM_TLS_CERT` / `JLLM_TLS_KEY` into `ssl.SSLContext`),
  optional mutual TLS, then default `allowInsecure=false` for non-loopback hosts.
- [ ] Until then document remote mode as "SSH tunnel or trusted network only".
- [ ] The container port is published on `0.0.0.0` of the remote host: bind to a private interface by default.

### Web application
- [ ] Rate limiting beyond the sign-in lockout (per user/address request limits).
- [ ] TLS termination story for the reference front end (documented proxy recipe at minimum).

## 3. Identity and access

- [ ] **IdP role mapping**: map OIDC/SAML groups or claims to roles, default role or refusal on no match, applied at
  each sign-in; `admin` never granted by claim unless explicitly enabled.
- [ ] **SAML follow-ups** (after the base [saml](saml.md) plan lands): support for additional IdPs, single logout (SLO),
  encrypted assertions, IdP-initiated sign-in flag.
- [ ] Per-user tool-call counts / sandbox time (not needed for the pilot; revisit on abuse).
- [ ] SCIM: not planned.

## 4. Model access and cost

- [ ] Provider subscription logins (vendor-specific OAuth, one `OAuthLogin` per provider via `ServiceLoader`); check
  each vendor's terms for third-party clients first.
- [ ] Alternatives kept in mind, not planned: provider-side limits on per-user keys; an in-JLLM daily token cap
  (SQL store already records tokens per chat and user; needs a price table to cap money); other gateways
  (Envoy AI Gateway, Kong, Portkey).

## 5. Administration and operations

- [ ] Backup / restore of **chat workspaces and files** (not in the settings backup).
- [ ] Importer of an existing `webauth/` folder into the SQL store.
- [ ] MySQL and PostgreSQL stores are experimental until run against real servers.
- [ ] Observability: Micrometer/OpenTelemetry module (recipes only today); audit log beyond events and hooks.

## 6. Documentation

- [ ] Keep the sandbox guide in sync with the hardening items in section 1.

## 7. Features (not scheduled)

Taken from `docs/enterprise/roadmap-and-limits.md` and the harness comparison.

- [ ] **Messaging gateway** (Telegram, Discord, Slack, WhatsApp) as a separate module, e.g. `jllm-gateway`: map a chat
  to a `chatId`, relay approvals, allow-listed users only, split long answers, `steer` / `followUp` during a run.
- [ ] **Browser automation** tool set (Playwright) inside the sandbox; output through the sanitizer and taint
  gating; state-changing actions need approval; screenshots need image input in the model clients (also missing).
- [ ] **Custom prompt templates**: markdown files as slash commands with `$1`, `$@` placeholders, user and project
  level, same clash rules as skills and plugin commands.
- [ ] Image input in model clients.
- [ ] Harness Spring Boot starter and Micrometer/OTel module.
- [ ] `run_js` limits tuned with eval data.

## 8. Known limits (accepted, tracked in the docs)

Default sandbox has no isolation; MCP stdio servers run as the JVM user; path-jail residual race on Windows;
flow `who` label shown, not enforced; file mode is a single instance; no external security audit.
See `docs/enterprise/roadmap-and-limits.md`.

