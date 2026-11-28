# Issue 12: security fixes

NO RETROCOMPATIBILITY

Source: https://github.com/kendarorg/jllm/issues/12 (branch `12-security-fixes`).

## Goal

- **CLI:** nothing from a project folder takes effect until the user trusts it; Docker mode cannot rewrite host
  configuration from inside the container.
- **Web:** all configuration is virtual (SQL or protected storage), admin-edited only, never read from chat
  workspaces. A user's commands reach only their own chat directory.

## Current state (relevant pieces)

| Area | Where | Note |
|---|---|---|
| Project trust | `core/jllm-harness/.../settings/ProjectMcpTrust`, `FileProjectMcpTrust`, `ProjectTrustFilter` | MCP-only trust today; filter is the tighten/loosen seam |
| SQL web store | `web/jllm-sql/.../SqlSettingsSource`, `SqlSkillRepository`, `SqlPluginStorage`, `SqlProjectMcpTrust`, `SqlStores` | Base for `SqlConfigProvider` |
| Web harness wiring | `web/jllm-web-commons/.../HarnessFactory` | Still uses `FileSettingsSource` (l.85), `SystemPromptFiles.load(projectDir)` (l.92), path-based prompt files |
| Sandbox | `core/jllm-harness/.../sandbox/` (`SandboxFactory`, `LocalSandboxExecutor`, `RemoteExecSandboxExecutor`, `ContainerRegistry`, `WorkspaceArchive`, `WorkspaceBackups`) | `ContainerRegistry` already has a `JLLM_AGENT_B64` path (l.227) but also a `~/.jllm/sandbox-agent` dir (l.567) |
| Env | `core/jllm-commons/.../util/EnvWhitelist` | Has `ALL = "*"`, no secret stripping |
| Run configs | `.run/*.run.xml`, `runConfig/{tmp,webauth,flows}` | Single unsafe config |
| Release | `release/template/{application.properties,settings,webauth}` | Single config |

## PR 0: run configurations (Preparation)

1. Move `runConfig/*` to `runConfigs/unsafe/*`. Update every `.run/*.run.xml` (`WORKING_DIRECTORY`,
   `--jllm.webauth-dir`, `--jllm.flows-seed-dir`).
2. Add `runConfigs/webSafe`: local storage (no SQL), random passwords generated on first run, admin password logged once.
   Uses the virtual-config provider of PR 2 (`RemoteFileConfigProvider` on a non-chat dir), `shared-local` sandbox.
3. Add `runConfigs/cliSafe`: CLI with folder trust on, no `--trust-project`, Docker or strict permissions.
4. IntelliJ runners, all for Apple / Cubi / Haiku:
   - unsafe: existing 3 CLI + 3 Web (+ Haiku JS) kept, re-pointed.
   - webSafe: `Web Safe Apple|Cubi|Haiku`.
   - cliSafe: `CLI Safe Apple|Cubi|Haiku`.
5. Release (`release/release.sh`, `release.ps1`, `release/template`): ship `configs/unsafe`, `configs/webSafe`,
   `configs/cliSafe` with matching `run-*` scripts or a config argument.
6. Remove committed `runConfig/tmp/.jllm/plugin-data` and root `.lock`; add to `.gitignore` (P2 repo hygiene, done here
   because the tree moves anyway).

7. Docs (README, release `README.md`, `docs/guides/`): describe the three configs. `unsafe` is for development and
   testing only: no folder trust, local shell with full host access, default/logged passwords, everything allowed. The
   model can read, change or destroy anything the user can. Never expose it on a network or run it on data you care
   about. `webSafe` / `cliSafe` are the starting points for real use.

Done when: all runners start from the new paths; release zip contains the three configs; docs state the `unsafe`
warning.

## PR 1: CLI folder trust and Docker protection (P0 CLI)

### Folder trust

- New `FolderTrust` (extends/replaces `ProjectMcpTrust`; `FileProjectMcpTrust` -> `FileFolderTrust` stored in
  `~/.jllm/trusted.json`, keyed by canonical project path).
- Trust record: path, SHA-256 of `.jllm/` content (sorted relative paths + bytes, symlinks not followed), timestamp,
  approved loosening set.
- Startup order in `jllm-cli` `Main`: compute decision **before** reading `.jllm/`, starting MCP servers, hooks, or
  running git. Untrusted or hash changed -> prompt.
- User declines trust -> CLI exits with a warning (nothing from the folder loaded, no session started).
- Non-interactive (`--prompt`, stdin not a TTY, CI env): untrusted folder -> exit with a warning, unless
  `--trust-project` or global config `trustedProjects` lists it.
- Consequence: instruction files (`AGENTS.md`, `CLAUDE.md`, `KENDAR.md`) are only ever loaded from trusted folders.

### Loosening needs approval even when trusted

- `ProjectTrustFilter` classifies each project setting vs. global as tighten / loosen.
- Loosening keys: `permissions.allow`, `permissions.ask`, `sandbox.*`, `output.taintGating`, `output.sanitize`, `web.*`,
  `whitelistedEnvVars`, `toolsWhitelistedEnvVars`, `SYSTEM.md`.
- Loosenings shown as a diff, approved set stored in the trust record (re-asked when hash changes). Tightening applies
  silently. Non-interactive: loosenings not in the approved set are dropped.

### Docker mode

- Local CLI (`ContainerRegistry` l.217-218, project bind-mounted rw at `/workspace`): add nested mounts after it.
  Docker applies them parent-first, so the child hides that part of the parent. Only the parts that make the host
  execute code are read-only; git keeps working (add, commit, branch, checkout, merge, stash):
  - `.git/hooks` and `.git/config` read-only (hooks, `core.fsmonitor`, `core.hooksPath`, `core.sshCommand`, filter /
    diff drivers, aliases all live there). Same for `.git/modules/*/hooks` and `.git/modules/*/config`
    (submodules), enumerated at container start.
  - `.jllm` read-only, then `.jllm/scratchpad` re-mounted read-write on top (scratch lives there, `CwdLayout`).
    `.jllm/history` is written by the host only.
  - Use `--mount type=bind,src=...,dst=...,readonly`: it errors on a missing source instead of creating a root-owned
    dir like `-v`.
  - Missing on host (`.jllm`, `.git/hooks`): `--tmpfs <dst>:ro`, so the container can't create one that the host would
    read next time. Missing `.git` (not a repo): nothing to protect; a `.git` created in the container is a new repo
    the host never had, still checked by the post-run guard below.
  - Mount points can't be renamed or removed from inside (EBUSY); git's `config.lock` + rename fails, so
    `git config` / `git remote add` / upstream tracking writes fail inside the container. Documented.
  - Worktrees (`.git` is a file pointing outside the project): the real git dir isn't mounted, git doesn't work in the
    container; unchanged from today.
- Post-run guard (all modes): hash `.git/config`, `.git/hooks/**`, `.git/modules/*/{config,hooks}`, `.jllm/**` (minus
  scratchpad/history) before and after container use; on change, warn and restore from the snapshot.
- Remote / WEB (volume + upload, l.219-245): no host mount; `WorkspaceArchive.unpack` and
  `WorkspaceBackups.backup`/restore skip `.jllm/**` and `.git/**` when content comes back to the host.

### File tools ask before touching configuration

- In every mode (including `auto`/accept-edits) writes to `.jllm/**`, `.git/**`, `AGENTS.md`, `CLAUDE.md`, `KENDAR.md`,
  `SYSTEM.md` require explicit approval. Hook in `ToolExecutor.decide` / `PermissionGate`, path match after `PathJail`
  canonicalisation. Non-interactive: denied.

### Tests

- Declining trust exits with a warning; nothing from `.jllm/`, `SYSTEM.md` or instruction files was read, no MCP server
  or git command started.
- Changing a file in `.jllm/` re-triggers the prompt.
- Non-interactive on an untrusted folder exits with a warning; `--trust-project` and `trustedProjects` let it run.
- Trusted folder with unapproved loosening (allow rules, sandbox backend): loosening has no effect until approved.
- Container writes to `.jllm/` and `.git/hooks` don't reach the host (Docker test, tagged, user-run).
- Write to `.git/hooks/pre-commit` in auto mode asks.

## PR 2: `ConfigProvider` and virtual web configuration (P0 Web)

### Interface (`core/jllm-harness`, settings package)

```java
public interface ConfigProvider {
    SettingsSource settings();
    InstructionRepository instructions();   // system prompt, global / role / user instructions
    AgentRepository agents();               // inline prompts only
    SkillRepository skills();               // harness skills folded in
    PluginStorages pluginStorages();
}
```

- `FileConfigProvider` (CLI): wraps `FileSettingsSource`, `SystemPromptFiles`, `InstructionLoader`, file skills.
  Folder-trust gated (PR 1).
- `SqlConfigProvider` (`web/jllm-sql`): from `SqlSettingsSource`, `SqlSkillRepository`, `SqlPluginStorage`, new
  `SqlInstructionRepository`, `SqlAgentRepository` + schema tables.
- `RemoteFileConfigProvider` (web, no SQL): JSON files under a server-only dir (outside `jllm.chats-dir`), for webSafe.

### Web mode

- `HarnessFactory` takes a `ConfigProvider`; remove `FileSettingsSource`, `SystemPromptFiles.load(projectDir)`,
  `InstructionLoader` on the working dir, `systemPromptFile`/`systemPromptReplace` paths.
- Startup fails unless the provider is `SqlConfigProvider` or `RemoteFileConfigProvider`.
- Reject path-based settings (`promptFile`, skill `path`, `instructionFiles`) on load and in admin API.
- No folder trust in web: there is no project folder, chat dirs are never a config source. Remove `ProjectMcpTrust`
  from `HarnessFactory`, delete `SqlProjectMcpTrust`, its `JllmSqlAutoConfiguration` bean and the `mcp_trust` table.
  MCP servers come only from the provider (admin-defined, per role).
- One-time import command (`jllm-web --import-config <dir>`) converting file settings/prompts/skills/agents into the
  provider.
- Admin API/UI (jllm-web) for instructions and agents; edit admin-only.
- `ScopedInstructions` off by default in web; if enabled per role, content labelled as user content.

### Server state vs chats

- New `jllm.chats-dir`. Startup fails if `webauth-dir`, SQLite file, provider dir, plugin dir or flows seed dir resolves
  inside it (canonical paths).

### Sandbox agent delivery

- `ContainerRegistry`: deliver only via `JLLM_AGENT_B64`; remove the `~/.jllm/sandbox-agent` bind mount (l.567).

### Tests

- With `SqlConfigProvider`, files (`.jllm/settings.json`, `SYSTEM.md`, `AGENTS.md`, `skills/`) in the working dir and
  chat dirs don't affect settings, system prompt, agents or skills.
- Startup fails with config paths inside `chats-dir`; fails without a provider.
- Path-based settings rejected.

## PR 3: `SharedLocalSandbox`, fallback, env (P0 Web)

### `SharedLocalSandbox` (`sandbox.backend=shared-local`)

- New runner in `sandbox/`, selected via `SandboxFactory`; config only from `ConfigProvider`.
- Environment: empty except `PATH`, `LANG`; `HOME` = chat dir, `TMPDIR` = chat scratch.
- Confinement: Linux `bwrap` (chat dir rw, `/usr`, `/bin`, `/lib*`, `/etc` minimal ro binds, private `/tmp`, `/proc`,
  `--die-with-parent`, `--new-session`); optional separate unprivileged uid. Landlock as alternative if available.
- `--unshare-net` by default; network per role (`role.sandbox.network=true`).
- Limits: CPU time, memory, pids (`prlimit`/cgroup via `systemd-run --user --scope` when present), output cap
  (`TailCapture`).
- Fail closed on Linux: if `bwrap` missing or not working (probe at startup), refuse to start.
- Windows/macOS: no confinement available; log a warning at startup and run with env/HOME/TMPDIR restrictions and
  limits only (testing use).
- Remove plain `local` from web roles (role validation rejects it in web mode).

### `sandbox.localFallback`

- Default false, per role, admin-only. May fall back only to `shared-local`, never plain host. Fix prompt text "as your
  user".

### Minimal environment

- `EnvWhitelist` default `PATH, HOME, LANG, TERM`; remove `ALL`.
- Always strip `JLLM_*`, `*API_KEY*`, `*SECRET*`, `*PASSWORD*`, `*KEY*` (case-insensitive), even if whitelisted. Applies
  to `whitelistedEnvVars` and `toolsWhitelistedEnvVars`.

### Tests

- `env` output contains no secrets; strip rules unit-tested.
- Reading `webauth/`, `jllm.db`, `/etc/passwd` fails; `curl` fails without network grant; startup fails without `bwrap`
  (Linux-only, skipped elsewhere).
- Windows/macOS: startup logs the warning and the sandbox runs.
- Docker down + fallback off -> tool call errors.

## PR 4: permissions and taint (P1, P2, P3)

- **Shell taint:** taint Shell output when the sandbox has network; option to taint document-reader output
  (`ToolExecutor.sanitizeOutput`).
- **Allowed modes per role:** role field `allowedModes`; enforce in `ChatApi.mode()` and `/auto`; `PermissionGate`
  honours role-level "always ask".
- **Shell guard message:** reword `ShellPaths.escapes()` message; document that only `..` paths are blocked.
- **Explicit command grants:** store a grant only when "remember" is ticked; document prefix coverage.
- **Spoofing characters:** strip U+200B-200F, U+202A-202E, U+2066-2069 in CLI `Renderer.clean` and the web approval
  dialog.
- **Tainted objects (P3):** interactive -> ask tainted vs cleaned; unattended -> reject.
- Tests: non-admin can't switch to a forbidden mode; grant not stored without remember; bidi chars stripped; regression
  tests via `ToolExecutor.decide`, `PathJail.beforeOpen`.

## Unattended runs (part of PR 4)

### Definition

A run is unattended when nobody can answer a question:

- CLI: `--prompt`, or stdin not a TTY (same check as `Main` l.242, moved into one `Attendance` helper used by folder
  trust, permissions and taint).
- Web: the chat's role has `unattended: true` (new `Role` field, admin-only), or a `jllm-workflows` flow runs with
  `"unattended": true`.

### Rule

Every permission request is denied automatically. Nothing is asked, nothing is upgraded to allow.

- `allow` rules (role `toolPermissions`, settings `permissions.allow`, stored grants) still run: they are decisions
  taken in advance.
- `ask` and anything the gate would ask (including the auto-mode classifier "ask") -> deny.
- The denial goes back to the model as the tool result (`denied: unattended run, no one can approve <tool>`), so it can
  adapt; it is logged and counted in `RunReport` (CLI) and the flow journal (web).
- To let an unattended run do more, pre-approve with `allow` rules (role or settings), or use the CLI bypass below.

### `--dangerously-skip-permissions` (CLI only)

- Every permission request that would ask is allowed (interactive and `--prompt` alike), including writes to
  `.jllm/**`, `.git/**` and instruction files (PR 1).
- Explicit `deny` rules (settings, global config) still deny.
- Does not trust the folder (still `--trust-project` / prompt) and does not change taint handling or the sandbox
  backend.
- Startup prints a warning banner; `RunReport` records that it was on.
- Web: not available. No property, no role field, no API; `HarnessFactory` has no path to set it.
- Tests: `--prompt` with the flag runs an `ask` tool; a `deny` rule still denies; web has no way to enable it.
- `jllm-workflows` `StepRunner` l.405-411: an approval request in an unattended flow becomes a denied tool result
  instead of failing the flow. The step still fails if the model can't complete it.
- `StepRunner` l.304 (clarification "ask" -> "proceed") stays: it is a question about the task, not a permission.

### Related rules

- Who can mark a flow unattended: only owners whose role has `unattended: true`, or admins. Checked at save and at run.
- Allowed modes (PR 4): an unattended run uses the role's mode limits; it can't select a mode the role forbids.
- Tainted content (P3): unattended -> the tainted content is rejected (`StepRunner` and CLI).
- Folder trust (PR 1): unattended CLI on an untrusted folder exits with a warning.

### Tests

- CLI `--prompt`: a tool with `ask` is denied, the model gets the denial, `RunReport` lists it; an `allow` tool runs.
- Web role `unattended`: same, through `ChatApi`.
- Unattended flow: approval request -> denied result, flow continues; non-admin without the role flag can't save an
  unattended flow.
- Tainted output in an unattended run is rejected.

## PR 5: auth and web hardening (P1, P2)

- **Passwords:** `AdminApi.createUser`/`setPassword` require a password or generate a one-time one (forced change).
  Fix `PasswordHasher` timing (constant-time compare) and iteration-count TODOs, Javadoc.
- **Internal addresses:** `web.blockLocalHost=true` default; add `0.0.0.0/8`, `100.64.0.0/10`, `198.18.0.0/15`,
  `64:ff9b::/96` to `isLocal`; connect to the resolved IP that passed the check (no re-resolve).
- **MCP OAuth discovery:** HTTPS + issuer match in `McpOAuthLogin.discover`, reuse `OidcClient.requireSecure`.
- **Response caps:** bound `DaemonClient` reads (`/backup`), nonzero default `maxWorkspaceBytes`, cap `McpHttpClient`.
- **JS sandbox:** use `SandboxPolicy.ISOLATED` (today `ConstrainedProfile` uses CONSTRAINED) on Community (GraalVM
  25.1+, needs `js-isolate-community` + platform jar, shipped by `release/graaljs`) and on Oracle (`js-isolate`).
  Set the options ISOLATED requires (`engine.MaxIsolateMemory`, `sandbox.MaxCPUTime`, method scoping /
  `HostAccess.ISOLATED`, redirected out/err). Capture empirically the exception when the isolate jar is missing (docs
  are silent). Both editions (Community and Oracle/commercial) must work: detect the edition and pick the matching
  isolate artifact (`GraalEdition`, `release/graaljs` profiles), test both paths. Fail closed: `run_js` stays disabled for untrusted roles, with a clear message. No
  `catch (Throwable)` in the bridge.
- **Login throttling:** atomic check-and-increment, bounded map, trusted-proxy setting for `X-Forwarded-For`; lock user
  after 3 failures for 30 min; admin reset endpoint + UI.
- **CSRF backstop:** `Origin` / `Sec-Fetch-Site` check on state-changing requests (`ApiInterceptor`).
- **Session:** absolute lifetime in addition to the 7-day sliding one.
- **Encrypted tokens:** user id + source in AES-GCM associated data.
- **Shipped defaults:** admin password not at WARN except on first run; remove `allow-unencrypted-tokens=true` from
  shipped properties.
- **OIDC linking:** never auto-link privileged accounts by email; document `allowed-domains`.
- **Container networking:** standard-profile containers on their own network (per user or per chat).
- Tests per item.

## PR 6: dependencies, CI, docs, process

- `nimbus-jose-jwt` >= 9.37.4, Spring Boot latest 3.5.x, `json-schema-validator` latest.
- OWASP dependency-check as a Maven plugin (`dependency-check-maven`) in a Maven profile `owasp` (`mvn -Powasp verify`),
  not active by default. The report is always generated; the build fails on CVSS >= 7 (high). NVD API key from an
  environment variable. No CI for now: run locally. The JSON report is converted to `docs/owasp_dc.md` by a
  single-file Java program (`scripts/OwaspReport.java`, run by `exec-maven-plugin` in the same profile), written even
  when the threshold fails the build (check with `failBuildOnCVSS` after the conversion, e.g. plugin `check` goal
  run after the exec step, with the `analyze`-style goal producing the JSON first).
- Docs (CLI, documentation only): host shell sees environment and filesystem without Docker; command grants remembered,
  prefix grants cover arguments. Update `docs/guides/sandbox.md` for `shared-local`, fallback, env.
- `SECURITY.md` with disclosure contact `github@kendar.org`.

## Order and dependencies

PR 0 -> PR 1 -> PR 2 -> PR 3 (needs `ConfigProvider`) -> PR 4 -> PR 5 -> PR 6. PR 5 and PR 6 can run in parallel with
PR 3/4.

## Decisions

1. Untrusted folder on the CLI: exit with a warning (interactive decline and non-interactive alike).
2. `SharedLocalSandbox` on Windows/macOS: log a warning and start (unconfined, for testing). Linux without `bwrap`
   still fails closed.
