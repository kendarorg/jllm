# LiteLLM integration

NO RETROCOMPATIBILITY

## Goal

Per-user model limits (spend budget, RPM, TPM) enforced by a LiteLLM proxy, with one virtual key per JLLM user and
limits chosen by role. JLLM builds no budgets, rate limiting or price tables.

## Status

LiteLLM itself not implemented. Shared prerequisites done (built for the IdP role mapping and
`plans/sandbox_hard.md`):
- `Role` carries `Map<String, JsonNode> settings` (free JSON, unknown keys preserved, caps); `sandbox` is one key read
  through `Role.sandbox()` / `RoleSandbox`. File and SQL role repositories store the whole map; `AdminApi` role
  endpoints accept and return `settings`.
- Versioned migration in `Schema` (`web/jllm-sql`, `Step` list: 2 settings columns, 3 `settings_section`, 4
  `login_sessions.not_after`); the ad-hoc `Schema.upgrade` is gone.
- Writable settings sink: `SettingsSink`, `AbstractSettingsSink`, `FileSettingsSink`, `SqlSettingsSink`.

Still true in code:
- `OpenAiCompatModelClient` (`core/jllm-harness`, `llm/`) already talks to a LiteLLM proxy (handles its "200 with error
  body" case; has `authProvider(...)`; retries `429` and `>=500` with backoff, honours `retry-after`).
- No per-user model credentials: every call uses the provider's global key (`ProviderSettings`;
  `docs/enterprise/roadmap-and-limits.md`, "Planned: per-user model credentials").
- No `LiteLlmLimits`, `LiteLlmKeyProvisioner`, `credentialMode`, provider admin API or provider UI.
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
- **Role limits**: stored under the `litellm` key of `Role.settings` (map, file and SQL storage done; see Status).
  `LiteLlmLimits.of(role)` validates `max_budget`, `budget_duration` (e.g. `30d`), `rpm_limit`, `tpm_limit`, `models`,
  optional `team_id`; invalid values give 400 on save.
  - Saving a role re-syncs its users' keys in the background (failures reported, not fatal).
  - Roles without a `litellm` entry use the provider-level default (`litellm: {url, masterKeyRef, defaultLimits}`).
- **Admin API** (`AdminApi` in `web/jllm-web-commons`, routes in `web/jllm-web-spring-starter`, admin only via `Gate`):
  - Role endpoints accept/return `settings`; `GET /api/admin/roles/{name}/settings`,
    `PUT|DELETE .../settings/{feature}`.
  - `POST /api/admin/litellm/sync[?user=<id>]`, `GET /api/admin/litellm/status`.
  - Providers: `GET/POST/PUT/DELETE /api/admin/providers[/{name}]`, `POST .../{name}/test`, default provider and
    `order` (fallback chain). Writes go through the existing `SettingsSink`. Validation per
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

## Gaps settled before task 1

- **Credentials not tied to LiteLLM.** `credentialMode: user-token` is generic, but only LiteLLM has a way to fill it
  (auto-provisioning). Decided: the web is provisioning-only (users cannot paste their own key); the CLI keeps
  `/login provider:<name>`. Task 1 must not depend on `LiteLlmKeyProvisioner`: it is the generic mechanism, LiteLLM is
  its first filler.
- **Whose key for runs without an interactive user**: flows, scheduled runs, sub-agents, title/summary/compaction
  helpers, classifier. Rule: the key of the chat owner (or the user who started the flow); a run with no user, or a
  background job whose owner was deleted/disabled, gets the "connect your account" error, never the global key unless
  `allowGlobalFallback`. State it in the code and the docs.
- **Shared chats** (hardening 4 shared deployments): the call is billed to the user who sends the turn, with that user's
  key; a user without a credential gets the connect error, the chat is not blocked for others.
- **"Connect your account" flow**: the tool/result must carry an actionable message and the web UI a place to act
  (user settings tab: state, add/replace/remove key); CLI: `/login provider:<name>`.
- **Storage**: `TokenStore` is already encrypted at rest (`TokenCipher`, AES-256-GCM, `JLLM_TOKEN_KEY`; `TokenKeyGuard`
  refuses to start a multi-user web without it). Rely on it; add a test that a provider key never reaches plain
  settings, logs, audit events or error messages.
- **Shared client, per-call key**: the `AuthProvider` lookup must not cache across users (no key in a field, no
  connection-level header reuse); test with two users interleaved on one client.
- **Audit**: record which credential source was used (`global`, `user-token`) per call, never the key; include in the
  per-request `metadata`.

## Open decisions

None. Answered 2026-11-27: the ops team runs and pins the LiteLLM proxy (documented); the web stays
provisioning-only (users cannot paste their own key; the CLI keeps `/login provider:<name>`).

## Remaining tasks

0. Smoke test, no code: provider `litellm` (openai-compatible, `baseUrl`, `model` alias, `apiKey` from env,
   `useMaxCompletionTokens` per backing model); confirm streaming with `stream_options.include_usage`, tool calls, 429/400.
1. Per-user model credentials (`credentialMode`, `allowGlobalFallback`, per-call `AuthProvider`, token storage).
2. `LiteLlmLimits` (validation of `settings.litellm`, 400 on save). `Role.settings`, file/SQL storage and the
   versioned `Schema` migration are done.
3. `LiteLlmKeyProvisioner` + sync/status endpoints.
4. Provider admin API (writes through the existing `SettingsSink`), atomic catalog reload, `ProvidersPanel.js`, role editor section.
5. CLI `/providers`.
6. Error mapping (budget vs rate limit, no fallthrough), audit metadata, optional cost display.
7. Tests (cross-platform; no real LiteLLM or Docker):
   - `settings.litellm` validation: 400 on invalid values, valid limits round trip in file and sqlite stores;
   - provider API: CRUD, secret never returned, reload does not disturb a running chat, non-admin 403;
   - provisioner against a fake LiteLLM (`FakeLlmServer` style, `eval/jllm-eval` fixtures): create, idempotent recreate, update, delete;
   - credential resolution never uses another user's key, no silent global fallback, 401 no fallback;
   - budget-exceeded not retried, RPM 429 retried with `retry-after`.
8. Docs: `docs/enterprise/roadmap-and-limits.md`, `identity-and-access.md`, per-role limit reference; remove litellm from
   `plans/TODO.md`; update the target page of `plans/secdoc.md`.
