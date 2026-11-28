# Configurations

Three ready-made configurations ship in the repository (`runConfigs/`) and in the release zip (`configs/`).

| Name | For | Runners |
|---|---|---|
| `unsafe` | development and testing only | IntelliJ `Web *` and `CLI *`; `run-web unsafe`, `run-cli unsafe` |
| `webSafe` | web application, real use | IntelliJ `Web Safe Apple\|Cubi\|Haiku`; `run-web` (default) |
| `cliSafe` | terminal, real use | IntelliJ `CLI Safe Apple\|Cubi\|Haiku`; `run-cli` (default) |

In the release zip `run-web.sh|.cmd` and `run-cli.sh|.cmd` take the configuration name as an optional first argument. Without
it the safe one runs; `unsafe` must be named explicitly.

## unsafe

**Development and testing only.**

- No folder trust.
- A local shell with full host access: no isolation.
- Default or logged passwords (`admin/admin`, `poweru/poweru`, `guest/guest`).
- Everything allowed.

The model can read, change or destroy anything the user running it can. Never expose it on a network and never use it on
data you care about.

## webSafe

- Loopback only (`server.address=127.0.0.1`).
- `jllm.web.use-real-password=true`: no default passwords; every user gets a random one at start and the administrators'
  are logged once.
- `jllm.store=file`.
- Roles `admin` and `user`, both with a `docker-isolated` sandbox (no network, container per user) and approval `ask`;
  see [Sandboxes](sandbox.md#the-web-a-sandbox-per-role). Docker must be installed.

## cliSafe

- `--mode default`: writes and shell commands ask first.
- `sandbox.backend: docker` with `localFallback: false`: commands run in a container, never on the host.
- `permissions.deny` for `.env` writes and `rm -rf`.
- No `--trust-project`.

## Not available yet

Applied to the safe configurations when they land: CLI folder trust, the virtual configuration provider
(`RemoteFileConfigProvider`), the `shared-local` sandbox and `jllm.chats-dir`.
