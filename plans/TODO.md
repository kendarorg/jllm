# TODO

NO RETROCOMPATIBILITY

This file directs the execution of everything open. Its sources are the plans in this folder and the items below; GitHub
issues are not a source and are ignored here. Items marked *(review)* come from an earlier security review and must be
re-checked against the code before work starts.

Plans: [sandbox_hard](sandbox_hard.md), [litellm](litellm.md), [backup-restore](backup-restore.md),
[docker-agent-tls](docker-agent-tls.md), [secdoc](secdoc.md), [saml](saml.md), [evalfolder](evalfolder.md),
[harborswe](harborswe.md). A plan's tasks are not repeated here, only their place in the order. Every decision that
shaped a step is written in the step as **Decided**, and in the owning plan.

## Rules

- Take the first step that is open (`[ ]`) and whose `needs` are all done. One step per run. `[~]` in progress, `[x]` done.
- A step is done only when code, tests and docs are updated, and its box is ticked here and in its plan.
- Never commit or push (the user does). Never add git hooks.
- Builds and tests that use no LLM and no Docker are fine; none while a model eval runs.
- Code and tests must build and pass on Windows, Linux and macOS.
- Fix code, not checks; change a test only when the test is provably wrong.
- No compatibility overloads or shims: change signatures and fix the callers.
- Plans and this file never reference each other's "TODO" sections from outside `plans/`.

## Order of execution

### Phase 1: sandbox and pilot blockers
Plan: [sandbox_hard](sandbox_hard.md), on top of the role settings and the settings sink (both exist).

**Decided for this phase**
- Docker socket: rootless Docker, a documented deployment requirement. No code.
- Network: documented only. `docker-isolated` keeps `network: none`; `docker-open` uses its own Docker network, and
  blocking metadata and internal ranges (`curl` in `bash` bypasses the SSRF guard) is the admin's firewall job.
- Allowed images per role: stored in a settings section (`web.*` through `SettingsSink`); digest optional (the admin UI
  warns on a bare tag); only admins add entries; no user switch in a chat, the role default applies.
- User deletion: a `UserLifecycle` service with registered cleanup steps, called by the admin delete API and the expiry
  sweep. Other plans (LiteLLM key delete, session revoke) attach their step to it.
- Multi-node: single node for the pilot, documented; registry, idle sweep and DNS pins are per JVM.
- `run_js` on the remote storage: implicit, through the chat runner; no new setting.

**Steps**
- [ ] P1.1 Main `docker_host` and per-user override (plan task 1).
- [ ] P1.2 Role sandbox settings: limits, clean-up policy, quotas (plan task 2).
- [ ] P1.3 SQL `BackupRecordStore` (plan task 3).
- [ ] P1.4 `UserLifecycle` service, `dropUser` on delete and expiry (plan task 4).
- [ ] P1.5 Allowed images per role, digest pinning of the default image (plan task 5).
- [ ] P1.6 `localFallback=false` for the web (default is true, so today it asks the chat user, who will click yes);
  document rootless Docker and the network rule (plan task 6).
- [ ] P1.7 Per-user allowed storages and picker (plan task 7).
- [ ] P1.8 Tarball download and permission (plan task 8).
- [ ] P1.9 Scope-change migration and warning (plan task 9).
- [ ] P1.10 Admin UI for the above (plan task 10).
- [ ] P1.11 `run_js` and the remote storage: verify the `fileJail` root against `Layout` (plan task 11).
- [ ] P1.12 Tests and docs, including the single-node limit in the sandbox guide (plan tasks 12 and 13).
- [ ] P1.13 Sandbox stdio MCP servers (CLI): run them inside the Docker sandbox or as a separate user, instead of the
  JVM user. (The web already ignores stdio servers.)

### Phase 2: model access
Plan: [litellm](litellm.md). Task 1 (per-user credentials) has no dependency and may start earlier; the key delete on
user removal attaches to the `UserLifecycle` service of P1.4.

**Decided for this phase**
- The ops team runs and pins the LiteLLM proxy; documented, jllm ships no bundle.
- Web users cannot paste their own provider key: provisioning only. The CLI keeps `/login provider:<name>`.
- No audit store. Credential source per call goes through hooks/events and the request metadata.

**Steps**
- [ ] P2.1 LiteLLM integration: per-user model keys and limits, provider management, CLI key storage (plan tasks 0-8).
- [ ] P2.2 Provider subscription logins (vendor-specific OAuth, one `OAuthLogin` per provider via `ServiceLoader`);
  check each vendor's terms for third-party clients first.
- Not planned, kept in mind: provider-side limits on per-user keys; an in-JLLM daily token cap (needs a price table);
  other gateways (Envoy AI Gateway, Kong, Portkey).

### Phase 3: settings backup
Plan: [backup-restore](backup-restore.md). After phases 1 and 2 so the archive covers the final role settings, backup
records, role-mapping options and LiteLLM keys and providers.

**Decided for this phase**
- No audit store: the restore entry (who, when, archive checksum, result) goes through hooks/events.
- Single node: maintenance-mode polling across nodes is not needed for the pilot.
- Revoking sessions of removed users is a step of the `UserLifecycle` service (P1.4).

**Steps**
- [ ] P3.1 Backup/restore of all web settings with maintenance mode (plan tasks).

### Phase 4: remote Docker TLS
Plan: [docker-agent-tls](docker-agent-tls.md). After phase 1, where the per-user `docker_host` makes remote hosts a normal
setup. Until then the interim documentation is "SSH tunnel or trusted network only".

**Decided for this phase**
- The agent certificate and key are generated with openssl inside the container; the key never leaves it, jllm reads
  only the certificate and its fingerprint.

**Steps**
- [ ] P4.1 TLS (and optional mutual TLS) in the in-container sandbox daemon, certificate lifecycle, private bind address
  (plan tasks 1-6).

### Phase 5: operations
No dependencies on each other.
- [ ] P5.1 Document the single-node limit (done with P1.12 if it comes first).
- [ ] P5.2 Backup/restore of chat workspaces and files (not in the settings backup).
- [ ] P5.3 Importer of an existing `webauth/` folder into the SQL store.
- [ ] P5.4 Rate limiting beyond the sign-in lockout (per user/address request limits).
- [ ] P5.5 TLS termination story for the reference front end (documented proxy recipe at minimum).
- [ ] P5.6 Observability: Micrometer/OpenTelemetry module (recipes only today).
- [ ] P5.7 Per-user tool-call counts / sandbox time (revisit on abuse).

### In parallel, any time
- [ ] [secdoc](secdoc.md): documentation of how authentication and authorization fit together. Tasks 0-2 now (current
  behaviour, SAML via broker); task 3 after phases 1 and 3.
- [ ] [saml](saml.md): SAML via an IdP broker (Keycloak or similar) and the existing OIDC sign-in; docs only, folds into
  secdoc.
- Eval track, independent of the above, never during a model eval:
  - [ ] [evalfolder](evalfolder.md): `eval/` folder layout and build wiring.
  - [ ] [harborswe](harborswe.md): Terminal-Bench/Harbor and SWE-bench. **Decided:** the hard cost cap lives in the
    runner only (`eval/common/budget.py`), `jllm-cli` stays unchanged. Add the "NO RETROCOMPATIBILITY" header to the plan.

### Phase 6: features (not scheduled)
Taken from `docs/enterprise/roadmap-and-limits.md` and the harness comparison.
- [ ] P6.1 Harness Spring Boot starter and Micrometer/OTel module.
- [ ] P6.2 **Messaging gateway** (Telegram, Discord, Slack, WhatsApp) as a separate module, e.g. `jllm-gateway`: map a
  chat to a `chatId`, relay approvals, allow-listed users only, split long answers, `steer` / `followUp` during a run.
- [ ] P6.3 **Browser automation** tool set (Playwright) inside the sandbox; output through the sanitizer and taint
  gating; state-changing actions need approval; screenshots need image input in the model clients (also missing).
- [ ] P6.4 **Custom prompt templates**: markdown files as slash commands with `$1`, `$@` placeholders, user and project
  level, same clash rules as skills and plugin commands.
- [ ] P6.5 Image input in model clients.
- [ ] P6.6 `run_js` limits tuned with eval data.

## Known limits (accepted, tracked in the docs)

Default sandbox has no isolation; MCP stdio servers run as the JVM user on the CLI (the web ignores them); path-jail
residual race on Windows; flow `who` label shown, not enforced; file mode is a single instance; one web node; no
external security audit; SCIM not planned. See `docs/enterprise/roadmap-and-limits.md`.

## Done log

Completed steps, newest first, with the date. Completed plans are deleted from this folder.

- 2026-11-27: decisions D1-D12 answered and written into the steps and plans.
- 2026-11-27: DNS pinning for the SSRF guard; role settings map and versioned SQL schema migration; writable settings
  sink (`SettingsSink`); IdP group-to-role mapping (docs `docs/enterprise/idp-role-mapping.md`). Their plans are removed.
