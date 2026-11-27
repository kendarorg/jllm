# SAML: use an IdP broker, no native SAML

## Decision

jllm does not implement SAML 2.0. A SAML-only IdP is reached through an identity broker (Keycloak or similar) placed
in front of it: the broker speaks SAML to the IdP and OIDC to jllm, and jllm signs users in through its existing OIDC
provider (`OidcApi`, `jllm.auth.mode` = `oidc | both`). No new code, no SAML/XML-signature dependency, no new attack
surface in jllm.

Replaces the earlier plan for native SP-initiated SAML (OpenSAML/Spring Security SAML, `SamlApi`, `methods` list). That
design is dropped, not postponed.

## Known limit

IdP group to role mapping exists (docs `docs/enterprise/idp-role-mapping.md`): off by
default; when enabled, the groups the broker puts in the ID token (Keycloak group membership mapper, "Add to ID token")
pick the role at each sign-in through the `idp` setting of each role, editable in the UI. Remaining limit: the role is
evaluated at sign-in only (bounded by `role-mapping.max-age`); no SCIM.

## Status

Task 2 holds (no code). Task 3 done: role mapping implemented, broker group-claim setup documented in
`docs/enterprise/idp-role-mapping.md`, and `identity-and-access.md` describes a broker in front of a SAML provider.
Task 1 open: `identity-and-access.md:49` still says "No SAML or SCIM. Put an OIDC bridge ... in front",
`roadmap-and-limits.md` (Identity row) and `securing-and-enterprise-setup.md` (gaps table) still say "no SAML"; the
Keycloak-as-broker setup note does not exist.

## Tasks

1. (open) Docs only. Replace "No SAML or SCIM. Put an OIDC bridge ... in front" with the supported approach and the role
   limit above in `docs/enterprise/identity-and-access.md`, `roadmap-and-limits.md`,
   `securing-and-enterprise-setup.md`; add a short Keycloak-as-broker setup note (SAML IdP in Keycloak, jllm as OIDC
   client, claims `email` and `name`, `allowed-domains`, redirect URI). Folds into `plans/secdoc.md`.
2. (holds) No code changes. `jllm.auth.mode` stays; no `methods` list.
3. (done) Depends on the IdP role mapping for removing the role-assignment limit; document the broker group claim
   setup there.
