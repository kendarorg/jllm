# Identity and access

Who may use the agent, which tools they get, and whose credentials a tool call carries. The library knows nothing
about sign-in; the web layer asks three small interfaces, so you can plug in your own identity system.

## Three interfaces

Defined in [jllm-web-commons](../../jllm-web-commons/README.md). Each has a deny-by-default fallback, so a missing
implementation locks the application instead of opening it.

| Interface | Question it answers | Fallback |
|---|---|---|
| `WebAuthentication` | who sent this request (`WebPrincipal`: `id()`, `admin()`) and which `/api` paths are open | nobody; 401 |
| `ChatAccess` | may this user see this chat | none; 404 |
| `HarnessIdentity` | the user's credentials, tool policy and principal; where OAuth tokens are kept | no credentials, no tools |

A chat a user may not see answers 404, as if it did not exist.

## The sample implementation (`jllm-web`)

- **Password** sign-in with a sliding session cookie; the user's other sessions are revoked on a password change;
  five failed attempts lock a login or address for 30 s, and an unknown login and a wrong password give the same answer.
- **OpenID Connect**: authorization code with PKCE against one provider (Entra, Keycloak, Okta, Google, ...). Optional
  `allowed-domains`, linking by verified email, optional auto-provisioning with a fixed default role. Modes
  `password`, `oidc` or `both`. Setup: [jllm-web](../../jllm-web/README.md#sign-in-with-openid-connect).
- **Roles** are files listing tool patterns (`read_file`, `mcp__docs__*`, `billing__*`), an optional `readOnly` flag and
  an `approval` setting. One role per user. `admin` is fixed and cannot be edited or removed.
- **Narrowing, never widening**: a user can switch off tools and skills their role allows, but cannot add any.
- **Own skills** per user, bounded in size and count.

## Whose rights a tool call uses

| Source | Credential |
|---|---|
| Your Java tools | whatever your code does; the tool context gives the chat id, from which the chat's principal is looked up (see [Spring Boot](../getting-started/spring-boot.md#your-services-as-tools)) |
| MCP servers, REST APIs | a static header (shared), a per-user `env:` header, or OAuth per user. A per-user OAuth call carries that user's token |
| Flows | a global flow runs as the user who starts it; roles can restrict Java actions, checked when the flow is saved |

## Not there yet

- One OIDC provider, and no group or claim to role mapping: the provider never changes a role, so an administrator
  assigns roles ([limits](roadmap-and-limits.md)).
- Model credentials are global today; per-user model keys and OAuth are [in development](roadmap-and-limits.md#in-development-per-user-model-credentials).
- No SAML or SCIM. Put an OIDC bridge (Keycloak, Entra, Okta) in front of a SAML provider.
- Users, roles, skills and sessions are stored as files or in memory. The repository interfaces are in
  `jllm-web-commons`, so a database implementation does not touch the controllers or the harness
  ([checklist](../../jllm-web/README.md#a-possible-extension-a-mysql-store)).

If you embed the harness in an application with its own users, skip `jllm-web` and pass your own principal to
`AgentHarness`; see [Embedding](../getting-started/embed-in-java.md) and
[Spring Boot](../getting-started/spring-boot.md).
