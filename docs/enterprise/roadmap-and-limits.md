# Roadmap and limits

## Known limits today

| Area | Limit |
|---|---|
| Identity | one OpenID Connect provider; no IdP group or claim to role mapping; no SAML or SCIM ([Identity and access](identity-and-access.md)) |
| Web application | a reference front end: no TLS termination, sign-in lockout is the only rate limit; run behind a proxy |
| Stores | memory and files in the library; `jllm-web` ships SQL stores (SQLite, MySQL, PostgreSQL); MySQL and PostgreSQL are experimental until run against real servers; no importer of an existing `webauth/` folder; one instance only (file mode), no shared run state |
| Network guard | `web.blockLocalHost` resolves the name itself and the HTTP client resolves it again: a DNS rebinding window remains (no address pinning). The remote Docker daemon accepts plain http unless `sandbox.docker.allowInsecure=false`; the in-container daemon speaks plain HTTP only (https needs a TLS-terminating proxy in front of it; native TLS is a TODO) |
| Integrations | no Spring Boot starter for the harness itself (`jllm-web-spring-starter` covers only the web REST and SSE API) and no Micrometer or OpenTelemetry module: [recipes](../getting-started/spring-boot.md) only |
| Model credentials | one global API key per provider; per-user credentials are [in development](#planned-per-user-model-credentials) |
| Flows | the `who` label of a question is shown, not enforced |
| Isolation | the web gives a role no sandbox by default (no files, no shell); the `local` sandbox has none; MCP stdio servers run as the JVM user |
| Path jail | on Windows, and on file systems without secure directory streams, a junction swapped in between check and use is a residual race (accepted: creating one needs a host shell, which has no jail); the shell tools are not covered ([tools guide](../guides/tools.md#links-that-leave-the-work-dir-filesexternallinks)) |
| `run_js` | the limits are guesses to tune with eval data (`maxInnerCalls` 200, `loop.innerCallsPerTurn` 10); GraalJS is not shipped in the jars and must be fetched ([JS guide](../guides/js.md)) |
| Assurance | no external security audit, no certification ([security model](security-model.md#6-what-it-does-not-cover)) |

## Planned: per-user model credentials

Today every model call uses the provider's global key and none of the following is implemented. The design below would let the administrator choose, per provider in
the settings, how the model is paid for and who is billed:

| Mode | Credential | Set by |
|---|---|---|
| Global (today's behaviour, the default) | `apiKey` or `<TYPE>_API_KEY` | administrator |
| Per-user token | each user's own API key | each user, once enabled |
| Per-user OAuth | each user's own OAuth login (`issuer`, `clientId`, same shape as a REST API's `oauth` block) | each user, once enabled |

How it behaves:

- **Whose credential**: every model call uses the credential of the user it runs for, never another user's: the chat
  owner for turns, helpers (title, summary, compaction, tool selection, the auto-mode classifier) and sub-agents; the
  user a run is for in flows, including unattended and resumed runs. A call with no user (startup probe) uses the
  global key only.
- **Resolution**: a user's stored credential first; a 401 refreshes once and never falls back to the global key.
  Without a credential, the global key is used only if the provider sets `allowGlobalFallback`. Otherwise the call
  fails with a "connect your account" result, which the user fixes with `/login provider:<name>` or in the web
  settings, then resumes.
- **Storage**: the same `TokenStore` as MCP and REST logins, source name `provider:<name>`.
- **Web**: user endpoints and a Connections tab.
- **Fast model**: a provider can be marked `fast` (at most one) for summaries and classification.

Not designed in detail yet: an administrator screen (providers are set in the settings file), a role controlling who may set their
own key, per-user cost attribution, a warning when `/model` switches to a provider needing a login, a shared OAuth or
service account for providers (`client_credentials` is rejected for providers), and hidden-input `/login` for tokens in
the CLI. Until it exists, treat model credentials as global.

## Future work

Features jllm does not have yet, taken from the gaps in [HARNESSES-COMPARE](../comparisons/harnesses-compare.md). Nothing here is
scheduled. Each item says what it is and how it could fit the harness. Model streaming, stuck detection and
verify-on-stop are implemented (see [HISTORY](../reference/history.md#12-loop-guards-and-streaming)).

## IdP role mapping

Map the groups or claims in the OpenID Connect token to JLLM roles, so that an administrator does not assign roles by
hand. Today the provider never changes a role: a user is linked or auto-provisioned with a fixed default role
([Identity and access](identity-and-access.md)).

- A settings entry maps a claim value (for example a `groups` entry) to a role, with a default for no match.
- The role is applied at each sign-in, so a change at the provider takes effect on the next login; no match means the
  default role or a refused sign-in, chosen in settings.
- The `admin` role stays fixed and is never granted through a claim unless the administrator opts in explicitly.
- Fits the existing `WebAuthentication` and role files; the harness is not involved.

## Messaging gateway (chat apps)

Talk to the agent from Telegram, Discord, Slack, WhatsApp and similar apps instead of the terminal or the web chat
(Hermes Agent has this).

- The relay is small with the existing API: map a chat-app conversation to a `chatId` (`sessions().create`), call
  `subscribe` and `start`, and send the answer back on `run_finished`. Lines starting with `/` can go through
  `SlashCommands`.
- The work is in the rest: approvals need a chat-side prompt (buttons, or a yes/no reply) that ends in
  `resolveApproval`; only allow-listed users may talk to it, because they run tools on the host; long answers must be
  split to the app's message limit; messages sent during a run go to `steer` or `followUp`.
- Best as a separate module (for example `jllm-gateway`), so the harness keeps no chat-app SDKs.

## Provider subscription logins

Log in to a model provider with a subscription account instead of an API key (Pi, OpenCode, Qwen Code and Hermes Agent
do). Per-user keys and generic OAuth per provider are [in development](#planned-per-user-model-credentials); a login for a specific vendor's subscription is separate work. jllm has the generic OAuth utilities, MCP and REST API OAuth (per user or shared), the `TokenStore` (file or OS secret store) and `/login`, but no provider login.

- Implement `OAuthLogin` per provider, discovered through `ServiceLoader` as now, and let a provider entry in the
  settings use the stored token instead of `apiKey`, refreshing it when it expires.
- Each provider has its own flow (device code or browser redirect) and its own terms of use for third-party clients;
  check both before building one.

## Browser automation

A tool group that drives a real browser: open pages, click, fill forms, read the rendered page, take screenshots
(Hermes Agent has this). Today there are `web_fetch` and `web_search` only, which do not run JavaScript.

- Ship it as an optional tool set (`HarnessToolSet`) backed by a browser driver, for example Playwright, so the core
  keeps its small dependency list.
- It should run inside the sandbox, not on the host browser profile.
- Pages are outside content: the output must go through the existing output sanitizer and taint gating, and
  state-changing actions (click, submit) need approval like other non-read-only tools.
- Screenshots need image input in the model clients, which is also missing.

## Custom prompt templates

User-defined commands such as `/review src/Foo.java`, stored as markdown files with placeholders (`$1`, `$@`), as in
Pi, OpenCode and Qwen Code. Today only Java plugins can add slash commands (`SlashCommands.register`).

- Load `*.md` files from a settings-defined directory (user and project level) and register each as a command with the
  file name as its name and the first line as its description.
- Expand `$1`, `$2`, `$@` from the typed arguments and send the result to the model as the user message.
- Reuse the same lookup rules and name-clash check that skills and plugin commands already use.
