# LiteLLM integration

NO RETROCOMPATIBILITY

## Goal

Per-user model limits (spend budget, RPM, TPM) enforced by a LiteLLM proxy, with one virtual key per JLLM user and
limits chosen by role. JLLM builds no budgets, rate limiting or price tables.

## Status

Nothing implemented. Verified in code:
- `OpenAiCompatModelClient` (`core/jllm-harness`, `llm/`) already talks to a LiteLLM proxy (handles its "200 with error
  body" case; has `authProvider(...)`; retries `429` and `>=500` with backoff, honours `retry-after`).
- No per-user model credentials: every call uses the provider's global key (`ProviderSettings`;
  `docs/enterprise/roadmap-and-limits.md`, "Planned: per-user model credentials").
- `Role` (`core/jllm-harness/.../identity/Role.java`) has no `settings` field.
- No admin API or UI for providers. `SettingsSource` (`core/jllm-harness/.../settings`) is read-only; impls
  `FileSettingsSource` and `SqlSettingsSource` (`web/jllm-sql`).
- `Schema` (`web/jllm-sql`) has no migration mechanism.
- No `ProvidersPanel.js`; admin UI is `jllm-web/src/main/resources/static/components/AdminPanel.js` + `RoleEditor.js`.
- `UserCredentialResolver` (`web/jllm-web-commons/.../users`) covers tool credentials, not model calls.
- CLI is `jllm-cli` (`Main`, `LineCommands`); no `/providers` command.

## Design

- **No new provider type.** A LiteLLM provider is a `ProviderSettings` entry: openai-compatible, `baseUrl` = proxy
  `/v1`, `model` = LiteLLM alias, key = LiteLLM virtual key.
- **Scope**: model-call limits, per-user key provisioning, error mapping. Tool-call counts and sandbox time are out
  (existing caps: container CPU/memory/process, `fetchMaxBytes`, storage quotas).
- **Per-user keys**: provider setting `credentialMode: global | user-token` (default `global`) and
  `allowGlobalFallback` (default false). Per-user OAuth is not planned here.
  - Every model call (turns, title/summary/compaction helpers, classifier, sub-agents, flow runs) resolves the key from
    the run's user through a per-call `AuthProvider` lookup on one shared client (no client per user).
  - Stored in `TokenStore` (`core/jllm-commons/.../auth`), source `provider:<name>`, as an `OAuthToken` with access =
    key, no refresh/expiry.
  - No credential and no fallback: "connect your account" result. 401 never falls back to the global key.
- **Provisioning** (`LiteLlmKeyProvisioner`: interface in `web/jllm-web-commons`, impl in `jllm-web`):
  - Config `jllm.litellm.url`, `jllm.litellm.master-key` (`env:` reference only). The master key is the only
    high-privilege secret; never logged, never in the user store.
  - First sign-in without `provider:litellm` token: `POST /key/generate` (`user_id` = JLLM user id, `key_alias` = same,
    limits from role, metadata); idempotent (on conflict or lost key, delete by alias and regenerate).
  - Role change or role-limit edit: `POST /key/update`. User deleted/disabled: `POST /key/delete`, then
    `TokenStore.removeAll`. Admin re-sync of one or all users.
  - Verify endpoints and fields against the installed LiteLLM version before coding (`/key/generate`, `/user/new`,
    `/key/update`, `/key/delete`; user vs key limits; a key without team keeps its own budget).
- **Role settings**: `Role` gains `Map<String, JsonNode> settings` (free JSON, top key = feature, e.g. `litellm`;
  unknown keys preserved; size and depth caps). Change the constructor and fix all callers (`Role.admin`,
  repositories, `AdminApi`, tests); no compat overload. `LiteLlmLimits.of(role)` validates `max_budget`,
  `budget_duration` (e.g. `30d`), `rpm_limit`, `tpm_limit`, `models`, optional `team_id`; invalid values give 400 on save.
  - SQL: `roles.settings` TEXT column in the three `schema.sql` (`web/jllm-sql/src/main/resources/db/{sqlite,postgres,mysql}`),
    `SqlRoleRepository`, `SqlSeeder` (`web/jllm-sql-spring-starter`). Needs the versioned `Schema` migration (shared
    with `plans/backup-restore.md`).
  - Files: `FileRoleRepository` (`jllm-web`) writes a `settings` object.
  - Saving a role re-syncs its users' keys in the background (failures reported, not fatal).
  - Roles without a `litellm` entry use the provider-level default (`litellm: {url, masterKeyRef, defaultLimits}`).
- **Admin API** (`AdminApi` in `web/jllm-web-commons`, routes in `web/jllm-web-spring-starter`, admin only via `Gate`):
  - Role endpoints accept/return `settings`; `GET /api/admin/roles/{name}/settings`,
    `PUT|DELETE .../settings/{feature}`.
  - `POST /api/admin/litellm/sync[?user=<id>]`, `GET /api/admin/litellm/status`.
  - Providers: `GET/POST/PUT/DELETE /api/admin/providers[/{name}]`, `POST .../{name}/test`, default provider and
    `order` (fallback chain). Writes go through a new writable sink next to `SettingsSource`. Validation per
    `ProviderSettings` (unique name, known type, URL).
  - Keys are write-only (`env:NAME` reference or encrypted token store); GET shows `configured: true/false`. Confirm
    how `apiKey` is held in settings today; keep literals out of plain settings JSON.
  - `ProviderCatalog` reload swaps atomically; running chats keep their client.
- **UI** (`jllm-web/.../static/components`): new `ProvidersPanel.js` (list with status, add/edit, Test, default,
  reorder); `RoleEditor.js` gets a "Model limits" section plus raw JSON editor for `settings`; user settings tab shows
  key state and budget used/remaining (`/user/info`).
- **CLI**: single user, no roles or provisioning. One `litellm` provider with a key from env or `TokenStore`
  (`/login provider:litellm`); limits are LiteLLM's; budget errors shown as plain messages. New read-only `/providers`
  command. Admin operations are web/API only (headless subcommands: `plans/backup-restore.md`). Keystore-backed key
  storage on Windows/Linux/macOS is optional.
- **Errors and visibility**:
  - Budget exceeded must not be retried (distinguish `ExceededBudget` from rate-limit 429 by body); RPM/TPM 429 honours
    `retry-after`. User message e.g. "monthly budget used up, resets <date>".
  - Fallback chain (`order`) must not fall through on a budget block.
  - Keep `HarnessPricing` estimate; optionally read `x-litellm-response-cost` or `/user/info`.
  - Pass `metadata`/`user` per request so LiteLLM logs match JLLM user and chat id.
- **Deployment**: run LiteLLM isolated, holding only provider keys; JLLM holds master key and virtual keys. Pin the
  exact version or image digest; check current supply-chain advisories before choosing one.
- **Decisions**: master key held by JLLM via env var; limits per role (free-JSON `settings`) with provider-level
  default; automatic provisioning only (users cannot paste their own key); proxy ownership still to be named by the user.

## Remaining tasks

0. Smoke test, no code: provider `litellm` (openai-compatible, `baseUrl`, `model` alias, `apiKey` from env,
   `useMaxCompletionTokens` per backing model); confirm streaming with `stream_options.include_usage`, tool calls, 429/400.
1. Per-user model credentials (`credentialMode`, `allowGlobalFallback`, per-call `AuthProvider`, token storage).
2. `Role.settings` + `LiteLlmLimits` + file/SQL storage + `Schema` migration.
3. `LiteLlmKeyProvisioner` + sync/status endpoints.
4. Provider admin API, atomic catalog reload, `ProvidersPanel.js`, role editor section.
5. CLI `/providers`.
6. Error mapping (budget vs rate limit, no fallthrough), audit metadata, optional cost display.
7. Tests (cross-platform; no real LiteLLM or Docker):
   - role settings round trip in file and sqlite stores, unknown keys preserved, caps, 400 on invalid, migration of a DB without the column;
   - provider API: CRUD, secret never returned, reload does not disturb a running chat, non-admin 403;
   - provisioner against a fake LiteLLM (`FakeLlmServer` style, `core/jllm-eval` fixtures): create, idempotent recreate, update, delete;
   - credential resolution never uses another user's key, no silent global fallback, 401 no fallback;
   - budget-exceeded not retried, RPM 429 retried with `retry-after`.
8. Docs: `docs/enterprise/roadmap-and-limits.md`, `identity-and-access.md`, per-role limit reference, first line of
   `plans/TODO.md` ("Add litellm provider"); update the target page of `plans/secdoc.md`.
