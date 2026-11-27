# Identity provider role mapping

At each OpenID Connect sign-in, the groups in the ID token pick the user's role, so an administrator no longer assigns
roles by hand. It works with any OIDC provider, including a broker (Keycloak or similar) in front of a SAML provider.
Authorization itself does not change: the role still decides the tools, the sandbox and the approvals
([Identity and access](identity-and-access.md)).

**Off by default.** With `enabled=false` nothing changes: no claim is read, no role is set at sign-in, new users get
`jllm.auth.oidc.default-role`, and roles set by an administrator are never overwritten. The groups stored on the roles
are kept but inactive. Only the preview works, as a dry run.

**One role per user.** When several roles match, one is picked: the lowest priority wins, ties go to the role name.
There is no multi-role support.

## Setup, in order

### 1. Put the groups in the ID token

Only the verified ID token is read: no userinfo call, no unsigned data, only tokens of the configured issuer.

- **Keycloak**: in the client, *Client scopes* > the dedicated scope > *Add mapper* > *Group Membership*; token claim
  name `groups`, *Full group path* off (or match `/team/*` paths), **Add to ID token** on. For realm roles instead, use
  the claim path `realm_access.roles` (also add the realm roles mapper to the ID token).
- **Okta**: in the authorization server (or the app's *Sign On* tab), add a `groups` claim to the **ID token** with a
  filter such as *Matches regex* `.*`, or a narrower one.
- **Entra ID**: *Token configuration* > *Add groups claim*, ID token. Entra sends group object IDs unless configured
  otherwise, so the role patterns must match IDs. Above 200 groups Entra puts `_claim_names`/`_claim_sources` in
  the token instead of `groups` (overage): jllm logs `claim truncated` and applies `onNoMatch`; it never calls Graph.
  Avoid overage with *Groups assigned to the application*, or with app roles (claim path `roles`).
- **SAML behind a broker**: map the SAML group attribute to a user attribute or group in the broker, then put it in the
  ID token as above.

### 2. Enable and choose the claim and the no-match rule

Either in properties (initial values):

```properties
jllm.auth.oidc.role-mapping.enabled=true
jllm.auth.oidc.role-mapping.claim=groups          # dotted path for nested claims: realm_access.roles
jllm.auth.oidc.role-mapping.on-no-match=default   # default | keep | refuse
jllm.auth.oidc.role-mapping.allow-admin=false
jllm.auth.oidc.role-mapping.max-age=8h            # optional, see below
```

or in the admin screen, tab **Role mapping**. Options saved there are stored with the other runtime settings (the
`stored-settings.json` file in file mode, the `settings_section` table in SQL mode) and win over the properties;
*Use the properties* drops them.

### 3. Give each role its groups

Roles tab > the role > **Sign-in mapping**: one group per line, `*` matches any text (case-sensitive), and a priority.
The same as JSON, under the role setting `idp`:

```json
{"idp": {"groups": ["engineering", "team-*"], "priority": 10}}
```

`PUT /api/admin/roles/dev/settings/idp` with `{"groups": [...], "priority": 10}` does the same.

### 4. Test with the preview

Role mapping tab > **Preview**: type groups as the token would carry them. The answer is the role picked, every matching
role with its priority and pattern, roles skipped (the admin role without `allowAdmin`), and what a sign-in would do
without a match. It works while the mapping is disabled.

### 5. Sign in and check

Sign in through the provider and check the role in the Users tab. Each change is logged:

```
Role mapping: role of ann changed from reader to dev (group engineering, matched)
```

and raised to the harness hooks as `ROLE_CHANGED` (`user`, `from`, `to`, `group`, `reason`) for an audit trail
([Governance and audit](governance-and-audit.md)).

## Reference

| Key (property / JSON) | Default | Meaning |
|---|---|---|
| `enabled` / `enabled` | `false` | map at every sign-in |
| `claim` / `claim` | `groups` | claim path in the ID token; a dotted path walks nested objects. A claim whose name contains dots (`https://example.com/groups`) is found by its full name first. The value is a string array, or a string of space or comma separated groups |
| `on-no-match` / `onNoMatch` | `default` | `default`: the default role, for new and existing users (needs `jllm.auth.oidc.default-role`); `keep`: the current role stays (a new user gets the default role); `refuse`: the sign-in is denied with `no_role` and no new user is created |
| `allow-admin` / `allowAdmin` | `false` | without it a group never grants the `admin` role: a match on it is skipped (logged) and the next match applies |
| `max-age` / `maxAge` | none | an identity provider session older than this must sign in again (5m to 30d, e.g. `8h`). Only while the mapping is enabled; password sessions are not affected |
| role setting `idp.groups` | `[]` | exact names or `*` globs, case-sensitive; at most 100, each 1-256 characters |
| role setting `idp.priority` | `100` | integer; lower wins, ties by role name |

Invalid values are refused: a bad property stops the server naming the key; a bad role setting or option answers 400.

Admin API (admins only):

| Call | Body | Answer |
|---|---|---|
| `GET /api/admin/auth/role-mapping` | | the options in force, `stored`, `editable`, `defaultRole`, `properties` |
| `PUT /api/admin/auth/role-mapping` | options; an absent key keeps its value | the same as GET; 400 invalid |
| `DELETE /api/admin/auth/role-mapping` | | drops the stored options; the properties apply |
| `POST /api/admin/auth/role-mapping/preview` | `{"groups": [...]}` or `{"groups": "a, b"}` | `role`, `group`, `pattern`, `matches`, `outcome`, `notes` |

The users list has `roleManagedByIdp: true` for the users linked to the provider while the mapping is enabled, with or
without a password: their next provider sign-in sets the role (the UI shows *managed by IdP*).

## Rules

- **Who is mapped**: the user who signs in through the provider, at each such sign-in, whether or not that user also
  has a password. A linked user's role is set at each provider sign-in; a password sign-in never runs the mapping and
  does not change the role.
- **Break-glass admin**: keep a local admin that signs in with its password, or that is not linked to the provider. A
  provider sign-in of a linked admin applies the mapping like for anyone else (only the last-admin guard below holds).
- **Last admin**: the mapping never demotes the last admin (skipped and logged).
- **Overwrite**: while the mapping is enabled, a role set by hand on a mapped user is overwritten at the next sign-in,
  unless `onNoMatch` is `keep` and no group matches.
- **Claim caps**: over 200 groups, or a group over 256 characters, makes the claim unusable (logged); `onNoMatch`
  applies. Nothing is cut.
- **When it applies**: at sign-in only. A user removed from a group at the provider keeps the role until their next
  provider sign-in, so the delay is the session lifetime (7 days sliding). Set `max-age` to bound it. There is no
  back-channel logout and no token refresh.

## Troubleshooting

| Symptom | Cause |
|---|---|
| log `OIDC claim 'groups' missing from the ID token; claims seen: [...]` | the provider does not put the claim in the ID token (step 1), or the claim path is wrong: pick one of the claims listed |
| log `OIDC claim truncated` | Entra overage, see step 1 |
| sign-in page says the account is in no group that gives access (`no_role`) | `onNoMatch=refuse` and no role matched: check with the preview |
| the role does not change | mapping disabled; the user signed in with the password (the mapping runs on provider sign-ins only); the user is the last admin; `onNoMatch=keep` and no match; the admin role without `allowAdmin`; the change applies at the next provider sign-in, not to the open session |
| a role is ignored with a note in the log | its `idp` setting is invalid (saved before validation or edited by hand); save it again |
