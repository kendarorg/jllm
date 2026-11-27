# Backup / restore of web settings, with maintenance mode

NO RETROCOMPATIBILITY

## Goal

An admin exports the configuration of a JLLM web installation as one archive and restores it later (same or another
instance). A maintenance mode blocks requests during the restore so none sees a half-applied state. Web only; the CLI
has no admin store.

## Status

Nothing implemented (checked: no backup, restore or maintenance code in `web/*`, `jllm-web`, `core/*`).
Prerequisites that exist: `RoleRepository`, `UserRepository`, `SkillRepository`, `LoginSessionRepository`
(`core/jllm-harness`, package `identity`), `TokenStore` (`core/jllm-commons`), `SettingsSource` (read-only, `layers()`
only), file stores in `jllm-web`, SQL stores in `web/jllm-sql`, `Gate` in `web/jllm-web-commons`.
Also done: `Role.settings` free map (keys `sandbox`, `idp`); versioned SQL migration (`Schema` steps 2-4); writable
settings sink (`SettingsSink`, file and SQL impls; stored sections such as `web.roleMapping`). Missing: everything in
this plan from task 3 on, and the maintenance-flag migration step.

## Design

Contents
- Included: global settings layer (providers, defaultProvider, tools, MCP/REST sources, permissions); stored settings
  sections of the sink (incl. `web.roleMapping`, the IdP role-mapping options); roles (incl. `settings`: `sandbox`,
  `idp`, later `litellm`); users (id, name, role, email, external id, password hash, `users.settings`: per-user
  sandbox overrides such as the docker host); sandbox backup records (`BackupRecordStore`, once in SQL; the images and
  zips they point to are not in the archive); global skills; per-user skills, plugin
  storage, project MCP trust.
- Opt-in `includeSecrets` (per-user tokens: LiteLLM keys, OAuth): encrypted with a passphrase given at backup time,
  never with the instance key. Wrong passphrase refuses the whole restore before any write.
- Opt-in `includeChats` via the chat store interfaces, separate `chats/` section so restore can skip it.
- Not included: workspaces/files (`plans/TODO.md`), login sessions, LiteLLM master key and `env:` secrets (reference
  name only).

Format: one `.zip`: `manifest.json` (format version, created at, app version, source store file/sql, sections, counts,
SHA-256 per entry) plus one JSON per section (`settings.json`, `roles.json`, `users.json`, `skills/...`,
`plugin-storage.json`, optional `secrets.enc`, optional `chats/`). Store-neutral (file <-> SQL) through the repository
interfaces. Fixed entry names; reject `..` and absolute entries (zip-slip).

Maintenance mode
- `MaintenanceState` in `web/jllm-web-commons`: `OFF`, `ENTERING` (draining), `ON`. Enforced centrally in `Gate`.
- Entering: chat runs and writes get `503` + `Retry-After` + JSON body; admin endpoints stay open (admin session).
  Running turns get `jllm.maintenance.drain-seconds` (default 60), then are cancelled; SSE streams get a final event;
  login page shows a message.
- In memory, mirrored to the store (SQL row / file marker) so a restart mid-restore comes back in maintenance.
  Multi-node SQL: nodes poll the stored flag. File store: single node.
- Left by an admin, or automatically after a successful restore (option). Never auto-leaves on failure.

Restore procedure
1. Upload (size cap); validate manifest, checksums, format version, section schemas; return a dry-run report (added,
   replaced, removed, conflicts such as the restoring admin's own user or missing `admin` role). Nothing written.
2. Admin confirms; maintenance enters and drains.
3. Automatic pre-restore backup to `jllm.backup.dir` (rollback point).
4. Apply per section. SQL: one transaction. Files: temp dir then atomic swap. Modes `replace` (default), `merge`
   (add/overwrite, no deletes).
5. Invariants before commit: `admin` role exists, at least one admin user, every user's role exists, provider names
   unique, `defaultProvider` exists. Failure: roll back (files: restore pre-restore backup).
6. Reload provider catalog and permission caches, revoke sessions of removed users, trigger LiteLLM key sync.
7. Leave maintenance (or stay, per option). Audit entry: who, when, archive checksum, result.

APIs (admin only, behind `Gate`)
- `POST /api/admin/backup` (sections, `includeChats`, `includeSecrets` + `passphrase`) streams the zip; optional
  scheduled backups (`jllm.backup.cron`, keep N).
- `GET /api/admin/backups`, `GET|DELETE /api/admin/backups/{id}`.
- `POST /api/admin/restore/validate` (upload or server id) -> dry-run report.
- `POST /api/admin/restore` (`mode`, `leaveMaintenance`) -> task id; `GET /api/admin/restore/{task}`.
- `GET|PUT /api/admin/maintenance`.

UI: "Backup and maintenance" tab beside `AdminPanel.js` in `jllm-web/src/main/resources/static/components`: backup
(section checkboxes, secrets toggle + passphrase), server backup list, upload + validate, restore with typed
confirmation and progress, maintenance banner for all users, manual toggle.

CLI
- `jllm-cli`: optional `/settings export|import <file>` for its own settings JSON only.
- Web jar subcommands (run without the HTTP server, honour the stored flag): `backup <file> [--include-chats]
  [--include-secrets --passphrase-env NAME]`, `restore <file> [--mode replace|merge] [--yes]`, `maintenance on|off`.

## Remaining tasks

1. Maintenance flag as a new `Schema` step (versioned migration itself done; no manual script).
2. (done) Writable settings sink next to `SettingsSource`.
3. `MaintenanceState` + `Gate` enforcement + store mirror + node polling.
4. Archive writer/reader (manifest, checksums, zip-slip guard), secrets encryption.
5. Restore service: dry-run, pre-restore backup, replace/merge, invariants, rollback, reload, audit.
6. Admin APIs, Spring routes in `web/jllm-web-spring-starter`, UI tab, web-jar CLI subcommands.
7. Tests: file -> archive -> SQL (sqlite) round trip incl. unknown role `settings` keys; maintenance (503, admin
   open, SSE end, restart survival, no auto-leave on failure); failures (corrupt, bad checksum, bad version, zip-slip,
   invariant) leave state unchanged with pre-restore backup present; secrets (absent by default, wrong passphrase
   writes nothing, never logged); chats skipped unless requested. Windows/Linux/macOS, no LLM, no Docker.
