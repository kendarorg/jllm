# Authentication and authorization documentation

NO RETROCOMPATIBILITY

## Goal

One canonical page, `docs/enterprise/authn-and-authz.md` ("Authentication and authorization: how it fits together"),
for the pilot administrator: who is the user, what a role grants, what mode/approval then changes, which setting wins,
what is out of reach. Other pages link to it instead of repeating it. English, same style as the other enterprise
pages (short, tables, mermaid for sequences, no marketing). Split into several pages only if it exceeds about 400
lines (hard limit around 1k).

## Status

Not started: `docs/enterprise/authn-and-authz.md` does not exist and `docs/index.md` does not list it.
Scattered sources today: `docs/enterprise/{identity-and-access,securing-and-enterprise-setup,security-model,
governance-and-audit,roadmap-and-limits}.md`, `docs/guides/{tools,sandbox}.md`, `jllm-web/README.md`.
Implemented and documentable now: password and OIDC sign-in, sessions, roles (incl. free `settings`: `sandbox`,
`idp`), tool approval, global model key, IdP
group to role mapping (off by default; `docs/enterprise/idp-role-mapping.md`, `ROLE_CHANGED` hook as audit trail,
`role-mapping.max-age` bounds stale roles).
SAML is not implemented natively: documented as "IdP broker (Keycloak or similar) in front, jllm OIDC sign-in"
(`plans/saml.md`). Not implemented (their sections stay pending): LiteLLM keys and limits
(`plans/litellm.md`), backup/restore (`plans/backup-restore.md`).

Code starting points for the inventory:
- `web/jllm-web-commons/.../web/commons`: `auth/{AuthApi,OidcApi,OidcClient,OidcProperties,LoginThrottle,CookieAuthentication,PasswordHasher}`,
  `Gate`, `users/{Permissions,UserCredentialResolver}`, `admin/AdminApi`, `me/MeApi`.
- `core/jllm-harness`: `identity/{Role,LoginSessionRepository}`, `agent/ToolSelector`, `auth/OAuthCredentialResolver`.
- `core/jllm-commons`: `permission/ToolApprovalPolicy`, `auth/{UserPolicy,UserPolicyResolver,TokenStore}`.
- SQL and file repositories (`web/jllm-sql`, `jllm-web/.../users`); future LiteLLM classes.

## Design

Decided: one page; docs site only (not in the web UI); reader is the pilot administrator; `security-model.md` is
updated to match.

Page outline:
1. Chain at a glance (mermaid + table, request to effect): transport (bind, TLS/proxy, `public-url`, loopback
   exemption); authentication (password, OIDC; SAML via a broker); session (`jllm_session`, expiry, revocation, throttle); identity
   linking and provisioning (`issuer#subject`, email linking, `allowed-domains`, `auto-provision`, `default-role`);
   role (tools, `readOnly`, `approval`, `toolPermissions`, free `settings`); user narrowing (`disabledTools`, own
   skills); chat tool set (chosen on first message, grows only); mode and approval (`plan`/`edit`/`auto`, regex
   deny/ask/allow, per-chat grants, auto-mode classifier, role `approval: off`); containment (path jail, sandbox,
   egress and per-host approval, `fetchMaxBytes`, storage quotas); tool credentials (static header, per-user `env:`
   header, per-user OAuth, flow runs as starter); model access (global key today, per-user LiteLLM key later); audit.
2. Authentication in detail: per method, setup keys, what is verified/trusted/not done (no
   SCIM, no SLO, local logout only; role mapping only at sign-in), `/?authError=<code>` codes, lockout. Mode selection (`password | oidc | both`
   today), break-glass local admin and its risk. SAML section: broker approach (Keycloak or similar brokers SAML to
   OIDC), setup walkthrough, groups through the broker into the ID token for role mapping; limit: role evaluated at
   sign-in only (bounded by `role-mapping.max-age`).
3. Identity to user to role: linking flowchart (linked external id, else single unlinked email match, else domain
   check and auto-provision with `default-role` or the mapped role, else refuse); IdP role mapping (off by default:
   IdP never changes a role; on: groups pick the role at each OIDC sign-in, `onNoMatch`, `allowAdmin`, last-admin
   guard, password sign-in never maps, `ROLE_CHANGED` hook); unverified-email risk and
   `allowed-domains`; email change at the IdP; user deleted/disabled; role changes apply on next call.
4. Authorization in detail: exact evaluation order (pattern match, deny over ask over allow, then `approval`; `admin`;
   `readOnly`; wildcards); precedence table of role rules, global permission rules, mode, per-chat grants, classifier
   (which can only restrict, which can relax); "narrowing, never widening" invariant and where it is enforced; chat
   visibility (owner; admins see all); sub-agents and flows (which user/role, unattended and resumed runs).
5. Whose credential is used where: matrix model call / MCP / REST / Java tool / flow / sandbox process, with source
   and who is billed.
6. Model cost and rate limits (after LiteLLM): `role.settings.litellm`, virtual key mapping, budget vs RPM/TPM
   behaviour, gap for tool activity.
7. Administration (after backup-restore): admin-only role/provider/settings APIs via `Gate`; maintenance mode;
   backup/restore contents and who may run them.
8. Worked scenarios, each ending with a table (authn result, role, effective tools, approvals, model limits, cost
   owner): SAML guest via Keycloak broker (role from mapped group) asks for `bash`; user removed from an IdP group mid-session; Keycloak OIDC `poweru` in `auto` mode with deny on `rm`; admin with
   `approval: off`; role changed or user deleted mid-chat/run; LiteLLM budget exhausted in an unattended flow; local
   break-glass admin with IdP down.
9. Troubleshooting (symptom, cause, fix): `authError` codes, unknown_user, domain refused, broker redirect URI
   mismatch behind a proxy, 403 vs 404 on chats, tool missing (role vs chat
   group vs user switch-off), "connect your account", role not changing at sign-in (claim missing, `no_role`).
10. Known gaps and non-goals (single source of truth replacing lists in `roadmap-and-limits.md`,
    `identity-and-access.md`, `securing-and-enterprise-setup.md`): role mapping at sign-in only, no SCIM, no SLO, no
    per-user tool-call quotas, global model key until `plans/litellm.md` task 1.
11. Pilot checklist: ordered, copyable, linking to exact settings.

Method (the page must be true):
- Every statement checked against code, not existing docs. Inconsistencies found are bugs fixed in code first (tests
  follow code; change a test only when provably wrong).
- Precedence table generated from a test running combinations (role allow + global deny, role deny + per-chat grant,
  `auto` + classifier, `approval: off` + `ask` rule, read-only role + write tool, narrowed tool).
- Each scenario backed by a small test using existing infrastructure (fake IdP/LiteLLM, no real LLM, no Docker);
  scenario IDs in test names and page.
- Test or script scanning the page for `jllm.*` keys and role/setting names; fails on keys missing in code.
- Review by someone who did not write the code, completing the Keycloak (OIDC and SAML-broker) setup from the page alone.

Edits to existing docs: shorten `identity-and-access.md` to interfaces and sample implementation; keep settings in
`securing-and-enterprise-setup.md` section 2 and link its "Known gaps" here; link from `security-model.md` ("Tool
control", "Web application") and update its threat mapping; point the identity row of `roadmap-and-limits.md` here and
drop duplicated gap lists; link from `jllm-web/README.md` sign-in sections; add to `docs/index.md`; reword "No SAML or
SCIM" to the broker approach and the sign-in-only role limit. Each of `saml.md`, `litellm.md`, `backup-restore.md`
carries a docs task pointing here.

Risks: doc drift (executable scenarios, key-scan test); documenting a bug (found in task 0); overlong page (keep a map
with links, detail stays in the guides).

## Remaining tasks

0. Inventory of decision points and precedence tests.
1. Write sections 1, 3, 4, 5, 10 (current behaviour); useful on its own, does not wait for LiteLLM.
2. Section 2 SAML-via-broker part and Keycloak walkthrough (current behaviour, no wait); closes `plans/saml.md` task 1
   together with task 5.
3. Sections 6 and 7 (with LiteLLM, role settings, provider management, backup/restore).
4. Scenarios, troubleshooting, checklist, executable-example tests, key-scan test, admin review.
5. Trim the other pages, update `docs/index.md` and `security-model.md`.
