# SAML 2.0 sign-in

## Goal

Let a SAML-only IdP sign users in directly, producing the same `jllm_session` and the same `User` linking as OIDC,
with no change to authorization (roles, permissions). Targeted IdPs: Okta and Keycloak. Entra ID, ADFS, Shibboleth:
not tested, not supported (must be stated in docs).
Out of scope: SCIM, group-to-role mapping (shared with OIDC, see `plans/TODO.md` "IdP role mapping"), single logout,
encrypted assertions, IdP-initiated SSO (all later).

## Status

Nothing implemented: no SAML code, no OpenSAML/Spring Security SAML dependency in any pom.
Existing base (`web/jllm-web-commons`, package `org.kendar.llm.web.commons.auth`): `OidcApi` (`login`, `callback`,
private `resolve`/`provision`/`sanitize`, binding cookie `jllm_oidc`, pending-state map), `OidcClient`,
`OidcProperties`, `OidcException`, `AuthApi`, `CookieAuthentication` (`openPaths()`), `LoginThrottle`. Spring routes:
`OidcController` in `web/jllm-web-spring-starter`; wiring: `WebAuthConfiguration` in `jllm-web`. Linking via
`UserRepository.findByExternal` / `externalKey(issuer, subject)`; sessions via `LoginSessionRepository` (both in
`core/jllm-harness`, package `identity`). Auth selected by `jllm.auth.mode` = `password | oidc | both`
(`jllm-web/README.md`, `docs/enterprise/securing-and-enterprise-setup.md`). Docs currently say "No SAML or SCIM"
(`docs/enterprise/identity-and-access.md`, `roadmap-and-limits.md`, `securing-and-enterprise-setup.md`).

## Design

- SP-initiated only: HTTP-Redirect AuthnRequest, HTTP-POST response. AuthnRequests unsigned by default (signing
  configurable). No IdP-initiated SSO. Local logout only.
- Use a library, never hand-written XML signature handling: Spring Security SAML2 or OpenSAML 5 directly; decided in
  phase 0 by whether `web-commons` may depend on it (it hosts the transport-neutral `Api` classes). Verify the
  artifacts resolve from Maven Central.
- New in `web-commons/auth`: `SamlApi`, `SamlClient` (validation, returns identity), `SamlProperties`, `SamlException`.
  Extract shared user resolution (`resolve`, `provision`, `sanitize`, binding cookie, throttle, failure redirect) from
  `OidcApi` into an `ExternalLogin` helper used by both. No compat shims; change signatures and fix callers.
- Identity: issuer = IdP entityID, subject = `NameID` -> `externalKey(issuer, subject)`. Email and display name from
  configurable attributes (default `email`/`mail`, NameID if emailAddress format). Signed assertion from the
  configured IdP counts as verified email; domain allow list still applies.
- IdP defaults: Okta: NameID `emailAddress`, attributes `email`, `firstName`/`lastName`. Keycloak: NameID `persistent`
  or `email`, mappers for `email` and `name`/`givenName`/`familyName` must be added to the client; metadata at
  `/realms/<realm>/protocol/saml/descriptor`. Okta metadata URL from the app's Sign On tab.
- Auth selection: replace `jllm.auth.mode` with a list `jllm.auth.methods: [password, oidc, saml]`; migrate values,
  fix callers and docs, no reading of the old key.
- Config `jllm.auth.saml.*`: `name`, `spEntityId`, `acsUrl` (derived or explicit override for proxies),
  `idp.metadataUrl` | `idp.metadataFile` (refresh, multiple certs for rollover), `signAuthnRequests` (false) with
  `spKeystore`/`spKeyAlias`/`spKeyPassword` as `env:` refs, `emailAttribute`, `nameAttribute`, `allowedDomains`,
  `autoProvision`, `defaultRole`, `clockSkewSeconds` (60), `forceAuthn`. Secrets only as `env:NAME`.
- Endpoints: `GET /auth/saml/login` (AuthnRequest; requestId + RelayState state bound to a `jllm_saml` cookie; 302),
  `POST /auth/saml/acs`, `GET /auth/saml/metadata`, `GET /auth/saml/config`. Add to `CookieAuthentication.openPaths()`;
  login page gets a SAML button.
- Binding cookie must be `SameSite=None; Secure` (ACS is a cross-site POST), so SAML requires HTTPS; document it.

Validation (all must pass, else refuse):
1. Signature valid against IdP metadata certs and covers the element used (reject wrapping); Assertion or Response
   signed.
2. `InResponseTo` = pending, unexpired, single-use requestId for this browser; state removed first.
3. `Issuer` = configured entityID. 4. `Destination` and `Recipient` = ACS URL. 5. `Audience` contains `spEntityId`.
6. `NotBefore`/`NotOnOrAfter` (and SubjectConfirmationData) within skew; `Status` Success.
7. Assertion ID not replayed (cache until expiry). 8. Unsolicited responses refused.
9. XML parsed with DTD, external entities, XInclude disabled; POST body size cap.
10. Failures log a code only, redirect to `/?authError=<code>`, count in `LoginThrottle`.

Risks: XML signature handling (library + wrapping/XXE tests); clock skew and cert rollover (skew setting, metadata
refresh, multiple certs); proxy URL mismatch (`acsUrl` override, `X-Forwarded-*`); dependency resolution from Central.

## Remaining tasks

0. Spike: pick library and module placement; Spring Boot compatibility; try against local Keycloak and an Okta
   developer tenant.
1. Refactor: extract `ExternalLogin` from `OidcApi`; introduce `methods` list; update `AuthApi` config output, login
   page, README, docs; `OidcTest` stays green.
2. SAML core: `SamlProperties`, `SamlClient`, `SamlApi`, metadata endpoint, pending-state store with TTL, replay
   cache, session via `LoginSessionRepository`.
3. Spring wiring: `SamlController` in `web/jllm-web-spring-starter`, beans in `WebAuthConfiguration`, open paths, body
   size cap, cookie attributes, forwarded headers.
4. UI + docs: button, per-code error messages; update `identity-and-access.md`, `roadmap-and-limits.md`,
   `securing-and-enterprise-setup.md` (replace "No SAML" with Okta/Keycloak supported, others not tested/unsupported),
   `jllm-web/README.md`, config reference, Okta and Keycloak setup note.
5. Add to `plans/TODO.md`: other IdPs (Entra ID, ADFS, Shibboleth), SLO, encrypted assertions, IdP-initiated SSO
   flag, group-to-role mapping (the last already listed).
6. Tests: test IdP built in-test (keypair, library-signed assertions): valid login plus one failing case per
   checklist item (bad signature, wrong issuer/audience/destination/recipient, expired, not yet valid, replayed ID,
   wrong InResponseTo, unsolicited, wrapped signature, XXE, oversized body); linking (existing external, single
   unlinked email, ambiguous refused, domain refused, auto-provision with `defaultRole`, id collision suffixes);
   throttle across login and ACS; metadata output contains ACS and entityID. Windows/Linux/macOS, no LLM/Docker.
   Keycloak container and Okta tenant tests are manual, outside the default build.
