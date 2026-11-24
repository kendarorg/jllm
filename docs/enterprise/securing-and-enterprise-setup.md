# Securing a jllm-web deployment

A practical order of work for exposing `jllm-web` to more than one person. Every setting below exists in the code or the
release template; the reasoning is in [Security model](security-model.md), [Deployment](deployment.md) and
[Identity and access](identity-and-access.md). Property reference: [jllm-web README](../../jllm-web/README.md#configuration).
Anything not listed here is not implemented: see [Known gaps](#known-gaps).

## 1. Bind and expose

The release template ships `server.address=127.0.0.1`: local only. The app does not terminate TLS.

| Setting | Use |
|---|---|
| `server.address` | keep `127.0.0.1` when a proxy runs on the same host; `0.0.0.0` only on a private network behind the proxy. Unset, `0.0.0.0` and `::` count as exposed everywhere below |
| `jllm.web.public-url` | external base URL, `https://jllm.example.com`. Needed for OIDC and for per-user OAuth sources (callback `<public-url>/oauth/callback`) |
| `spring.mvc.async.request-timeout=-1` | SSE streams must not time out |
| `server.servlet.context-path` | standard Spring property; authentication and the default-password gate apply under a context path too (covered by `DefaultPasswordEnforcementTest`) |
| `jllm.web.content-security-policy` | blank = built-in policy (`default-src 'self'`, no framing, `object-src 'none'`); any text replaces it; `off` removes it (do not, when exposed). `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` are always sent |
| `JLLM_UPLOAD_MAX_FILE_SIZE`, `JLLM_UPLOAD_MAX_REQUEST_SIZE` | upload limits (env, default 100MB / 200MB) |

Session cookie `jllm_session`: HttpOnly, SameSite=Strict, Secure on HTTPS. The proxy must pass the original scheme
(`X-Forwarded-Proto`) and Spring must honour it (`server.forward-headers-strategy=framework` is Spring Boot's own
property; verify the cookie carries `Secure` behind your proxy).

Proxy requirements: TLS, no response buffering and no read timeout on `/api/chat/*/events`, do not strip the security
headers, add `X-Forwarded-For`.

```nginx
server {
  listen 443 ssl;
  server_name jllm.example.com;
  ssl_certificate     /etc/ssl/jllm.crt;
  ssl_certificate_key /etc/ssl/jllm.key;
  client_max_body_size 200m;
  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_read_timeout 1h;
  }
}
```

```caddy
jllm.example.com {
  reverse_proxy 127.0.0.1:8080 { flush_interval -1 }
}
```

Caddy sets `X-Forwarded-For` and `X-Forwarded-Proto` itself.

### Loopback exemption caveat

A user still on the default password is blocked from everything except changing it (`403 password_change_required`),
but requests from the loopback address are exempt. A proxy on the same host that adds no forwarding header, an SSH or
cloud tunnel, or a sidecar makes remote requests look local. The exemption is already off when `X-Forwarded-For`,
`Forwarded` or `X-Real-IP` is present, or when `server.address` is not loopback. Behind anything else set
`jllm.web.enforce-password-change-on-loopback=true`.

## 2. Identity

| Setting | Effect |
|---|---|
| `jllm.web.use-real-password=true` | at startup every user on the default password (password = login) gets a random 24-character one. The admin password is logged once at WARN; other users are only logged as generated, reset them in the admin screen. Never regenerated; OIDC-linked users skipped. Also counts as "exposed" for the token key check |
| `jllm.auth.mode` | `password` (default), `oidc`, `both` |
| `jllm.auth.oidc.*` | `name`, `issuer`, `client-id`, `client-secret` (`env:NAME` only), `scopes`, `allowed-domains`, `auto-provision`, `default-role` |

Passwords are PBKDF2 (210000 iterations); 5 failed attempts lock a login or address for 30 s (429); changing a password
signs out the user's other sessions; minimum 8 characters.

OIDC (authorization code + PKCE, one provider):

```properties
jllm.web.public-url=https://jllm.example.com
jllm.auth.mode=oidc
jllm.auth.oidc.issuer=https://idp.example.com/realms/x
jllm.auth.oidc.client-id=jllm
jllm.auth.oidc.client-secret=env:JLLM_OIDC_SECRET
jllm.auth.oidc.allowed-domains=example.com
jllm.auth.oidc.auto-provision=true
jllm.auth.oidc.default-role=guest
```

Register `<public-url>/auth/oidc/callback` exactly. Discovery and token endpoints must be HTTPS; startup fails naming the
key if something is missing. Users link by `issuer#sub`, or on first login by a verified email equal to exactly one
unlinked user's email. The provider never changes a role: no group-to-role mapping, an administrator assigns roles.
With `auto-provision` use a least-privilege `default-role`. Mode `oidc` disables `/api/login`.

### Roles and permissions

Roles are `permissions/<role>.json` (files) or rows (SQL): `{tools, readOnly?, approval?, toolPermissions?}`. One role
per user; `admin` always has every tool and cannot be deleted.

| Role (shipped) | Tools |
|---|---|
| `admin` | everything |
| `poweru` | `run_js`, `bash`, file tools, web tools |
| `guest` | `run_js`, `web_search`, `wikipedia_search`, `web_fetch`, `web_download`; `bash` denied |
| `reader` | `run_js`, read-only file tools |

- `tools` accept patterns (`mcp__docs__*`, `billing__*`). `toolPermissions` sets `allow`, `ask` or `deny` per tool.
- `approval: "off"` removes approval prompts for the role. Never set it on `admin` or on any role with `bash`, write or
  web tools. The template admin has no `approval` key; keep it that way.
- Users can switch tools off for themselves (`disabledTools`), never on. Give `bash` only to roles that need it.
- Per-user MCP/REST credentials: `"credentials": {"mcp:docs": {"Authorization": "env:DOCS_TOKEN"}}`; only `env:` values
  are read. Per-user OAuth is the default for sources with `oauth` (needs `jllm.web.public-url` and a token key).
- Chats belong to their creator (others get 404); admins see all chats.

## 3. Secrets

| Item | Rule |
|---|---|
| `JLLM_TOKEN_KEY` | base64 of 32 random bytes: `openssl rand -base64 32`. Encrypts stored OAuth tokens (AES-GCM) in the file and SQL stores. Same value on every instance sharing a store. Losing it makes stored tokens unreadable |
| Rotation | set the new key in `JLLM_TOKEN_KEY` and the old one in `JLLM_TOKEN_KEY_OLD`. An invalid key stops the start |
| `TokenKeyGuard` | no key and exposed (`jllm.web.use-real-password=true` or non-loopback or unset `server.address`): refuses to start. No key and local-only: starts with a loud WARN |
| `jllm.web.allow-unencrypted-tokens=true` | overrides the refusal; clear-text tokens, WARN stays. Do not use on a shared host |
| Provider API key | `jllm.api-key=${ANTHROPIC_API_KEY}` or the `<PROVIDER>_API_KEY` env var; never a literal in a file. One key for all users (per-user model keys are not available) |
| `jllm.sql.password` | `${JLLM_SQL_PASSWORD}` |
| OIDC secret, user credentials | `env:NAME` references only |

Keep secrets in the service manager's environment, not in `application.properties`. Tool output is not redacted by the
harness; add a `POST_TOOL` hook if tools can return secrets. Chat history is not encrypted at rest: use disk encryption.

## 4. Storage

The release defaults to SQL (`lib-ext/sql`); `jllm.store=file` keeps `webauth/` files. File mode: one instance only,
sessions in memory (a restart signs everyone out).

| Database | `jllm.sql.url` | Driver | Status |
|---|---|---|---|
| SQLite (default) | none, or `jdbc:sqlite:/var/lib/jllm/jllm.db` | bundled | supported; default file `<jllm.webauth-dir>/jllm.db`, else `data/jllm.db`; pool 1 |
| MySQL 8+ | `jdbc:mysql://host:3306/jllm?useUnicode=true&characterEncoding=utf8` | download, GPL | experimental: DDL never run against a real server by the project |
| PostgreSQL | `jdbc:postgresql://host:5432/jllm` | download | experimental, same caveat |

The dialect comes from the URL scheme alone. Other settings: `jllm.sql.user`, `jllm.sql.password`, `jllm.sql.pool`
(default 10 for non-SQLite), `jllm.sql.seed` (default `true`: a new database gets admin, poweru, guest with password =
name; combine with `jllm.web.use-real-password=true` or create users and set `seed=false`), `jllm.sql.driver-dir`.
Details: [jllm-sql-spring-starter](../../web/jllm-sql-spring-starter/README.md).

Drivers: run `fetch-sql-driver.sh|.cmd|.ps1 <mysql|postgres>` (or `setup`). In a release zip the jar lands in `lib-ext/`;
in a source checkout in `.downloaded-libs/<db>/<version>`. The SHA-256 is checked against a pinned manifest at download
and at load; a mismatch is refused. MySQL asks to accept the GPL terms. Only the driver of the URL is used.

Multi-instance:
- Same database, same `JLLM_TOKEN_KEY` everywhere; the schema is created at startup when missing (race-safe across instances).
- A running chat lives in one process (virtual thread plus event buffer): use sticky routing per chat.
- Not on SQL: workspace files (chat directories, uploads). Share or pin them per instance. Plugin storage (workflows'
  flows and runs included) is in SQL; with `jllm.store=file` it is `.jllm/plugin-data`.
- No importer from an existing `webauth/` folder.

Backups: no built-in procedure yet. Back up the database (SQLite: copy `jllm.db` while the app is stopped, or use the
SQLite online backup), the chat workspace directories, and keep `JLLM_TOKEN_KEY` separately from the backup. Test a
restore yourself.

## 5. Tool safety

### Web access (`settings.json`, section `web`)

| Key | Default | Effect |
|---|---|---|
| `web.blockLocalHost` | `false` (the web release template sets `true`) | `web_fetch` and `web_download` refuse loopback, link-local (169.254.169.254), private, any-local and IPv6 `fc00::/7`; checked on the first URL and every redirect |
| `web.blockExtraAddresses` | `[]` | more refused entries: IP, CIDR, host, `*.suffix` |

Gap: the name is resolved by the guard and again by the HTTP client, so DNS rebinding is not covered. Also: every new
host asks for approval (grantable per chat with subdomains); https to http redirects are refused; invalid certificates
ask separately. To remove web access, add `deny` rules or leave the tools out of the role.

### Sandbox

`bash` in the default `local` sandbox runs as the JVM user with no limits. For shared use:

```properties
jllm.sandbox=docker
```

| Setting | Note |
|---|---|
| `sandbox.docker.profile` | `standard` (docker's default network) or `hardened` (no network, limits per user and chat; for temporary users) |
| `sandbox.docker.network` | `hardened` only: `none` (default) or `default`; `standard` ignores it |
| `sandbox.docker.scope` | `chat` (default) or `user`; `user` shares one container across a user's chats, so untrusted chats use `chat` |
| remote daemon | `jllm.docker-remote=true`, `jllm.docker-remote-host`. The daemon protocol is plain HTTP by default |
| `sandbox.docker.allowInsecure=false` | refuses plain http to a non-loopback remote host (default `true`: WARN only). Set it on shared setups |
| `remoteHost: "https://..."` | TLS to a TLS-terminating proxy in front of the daemon; `sandbox.docker.trustStore` / `trustStorePassword` for a private CA |
| alternative | SSH tunnel with `remoteHost` = `127.0.0.1` |

The daemon listens on loopback, checks a per-container token and strips `JLLM_*` variables. It has no TLS and does not
protect against code already inside the container. Details: [Sandboxes](../guides/sandbox.md#security). Run the JVM as an
unprivileged user: MCP stdio servers are children of it, outside any sandbox.

### Permissions

- Modes `default`, `plan`, `edit`, `auto`; kinds `READ_ONLY`, `FILE_EDIT`, `OTHER` (shell and network writes ask outside `auto`).
- `permissions.deny` regex rules apply in every mode and cannot be overridden by a chat grant. A regex on a shell
  command is a guard rail, not a boundary; use the Docker sandbox for containment.
- Taint tracking: after untrusted output (web, MCP) has entered a chat, a host that was not granted still asks. Taint of
  compacted results lasts until the next user message.
- File tools are jailed to the chat directory; real paths are resolved, so a symlink into a denied path is caught. The web
  app forces `files.externalLinks=none`.
- Put visibility lists (`allowList`/`denyList`) on every MCP server and REST API; mark read-only sources `readOnly`.
- Prompt-injection scanning of skills, instruction files and settings text is a warning only: the file still loads.
  Review shared skills like code.

Example settings fragment (rule syntax: [usage](../reference/usage.md#5-settings-file)):

```json
{
  "web": { "blockLocalHost": true, "blockExtraAddresses": ["10.0.0.0/8", "*.corp.example.com"] }
}
```

## 6. Operations

- Logging: startup WARN with the admin password once (`use-real-password`), WARN on missing `JLLM_TOKEN_KEY`, on a
  non-loopback plain-http remote sandbox, on `files.externalLinks` set to another value, and on missing GraalJS. Tool calls
  and approvals are events, not a log: forward them with a hook ([Governance](governance-and-audit.md)). Protect the
  startup log: it holds the admin password.
- SSE: each subscriber has a bounded queue; a slow client is dropped (`subscriber_dropped`) and reconnects with
  `?after=<seq>` to replay from the buffer. Text deltas are live only.
- Limits: `jllm.max-turns` (40), `jllm.max-tokens` (32000), `jllm.context-tokens` (100000), uploads, 50 own skills of
  64 KB per user, approval wait 600 s.
- Cost: `jllm.pricing.*` gives an estimate only; nothing enforces it.
- Updates: dependencies are pinned in the poms, SQL drivers and GraalJS by SHA-256 manifests; no vulnerability scan is
  shipped. The SQL schema has no upgrade path: changing it means deleting the database.
- Metrics and tracing: recipes in [Observability](observability.md).

## Known gaps

Not available yet, from the other pages of this section:

| Gap | Today |
|---|---|
| Rate limits and spend caps per user | none; only sign-in lockout (30 s) |
| Audit log | none built in; use hooks and events |
| Health and metrics endpoints | no `/api/health`; no shipped metrics module |
| Backups with tested restore | not documented or tested beyond the notes above |
| Dependency scanning in CI | not set up |
| DNS pinning for the SSRF guard | rebinding window open |
| IdP group/role mapping, SAML, SCIM | not available; one OIDC provider |
| Per-user model credentials | model key is global |
| MySQL and PostgreSQL | experimental |
| Plugin storage, workspace files | on disk, not in SQL |
| External security audit | none |

## Checklist

```properties
# application.properties
server.address=127.0.0.1                  # proxy on the same host; 0.0.0.0 only on a private network
server.port=8080
spring.mvc.async.request-timeout=-1
server.forward-headers-strategy=framework
jllm.web.public-url=https://jllm.example.com
jllm.web.use-real-password=true
jllm.web.enforce-password-change-on-loopback=true
jllm.auth.mode=oidc                       # plus jllm.auth.oidc.* with an env: secret
jllm.sandbox=docker
jllm.store=sql
jllm.sql.url=jdbc:sqlite:/var/lib/jllm/jllm.db
jllm.max-turns=40
```

- [ ] TLS proxy in front; SSE unbuffered, no read timeout; security headers kept; `X-Forwarded-For` set.
- [ ] `JLLM_TOKEN_KEY` set (same on all instances) and stored apart from backups; `allow-unencrypted-tokens` not set.
- [ ] No default passwords: `use-real-password=true` or OIDC only; admin password taken from the log and the log protected.
- [ ] `enforce-password-change-on-loopback=true` when anything forwards traffic without a forwarding header.
- [ ] No role has `approval: "off"`; `bash` only for `poweru`-type roles; `auto-provision` default role is least privilege.
- [ ] `web.blockLocalHost=true` and `blockExtraAddresses` for internal hosts.
- [ ] Docker sandbox (daemon); remote daemon over https or SSH tunnel with `allowInsecure=false`; JVM runs unprivileged.
- [ ] `permissions.deny` rules; visibility lists on every MCP/REST source.
- [ ] SQL store with driver fetched and verified; database credentials from `${ENV}`; sticky routing if several instances.
- [ ] Hook for audit events; backup and restore of database, workspaces and key tested.
- [ ] Known gaps above reviewed and accepted.
