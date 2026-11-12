# Plan: per-user credentials and tool policy for the web front end

## Goal

Let an embedder running jllm-web (or any jllm-harness host) serve many users and many chats from one server, with:

- per-user credentials for MCP servers and REST APIs, resolved on every outgoing call;
- per-user tool sets and approval policy, fixed per chat;
- argument-level rules enforced in hooks, using the same principal.

The CLI stays single-user and keeps today's behavior through defaults.

## Non-goals

- Authentication in jllm-web. The sample stays authentication-free; a fake user picker only demonstrates the mechanism.
- Persisting credentials. Only the principal id is stored with a chat.

## Sample users (fake login, no passwords)

The sample keeps the non-goal above: no real authentication. The user picks an identity from a fake login screen. Each user is one file, `users/<id>.json`, holding the role and any credential references.

| User | Role | Tools | Approval |
|---|---|---|---|
| `guest` | guest | `run_js` (JS sandbox), web tools (`WebAgentTools`). No direct file tools: files are reachable only through the JS sandbox | default |
| `reader` | ro | read-only file tools only (`FileSystemAgentTools`, `ToolKind.READ_ONLY`) | n/a (no writes possible) |
| `admin` | almighty | all tools | off |

- No UI for editing users. Change the files by hand.
- `CredentialRepo` reads `users/<id>.json` and returns headers per source. Secrets are environment variable references (`env:NAME`), never literal values.
- `PolicyRepo` maps role to `UserPolicy`.
- The picker sends the chosen id as the principal (step 8). It is labeled as non-authentication in the UI and README.

## Existing seams (verified in code)

| Seam | Location | Gap |
|---|---|---|
| `AuthProvider.headers()` | `jllm-harness/.../auth/AuthProvider.java` | No context parameter; bound to one client, not to a user |
| `McpHttpClient` headers + `AuthProvider` | `jllm-harness/.../integration/McpHttpClient.java` | One `Mcp-Session-Id` per client; shared across chats if the client is shared |
| `ToolApprovalPolicy.requiresApproval(tool)` | `jllm-harness/.../permission/ToolApprovalPolicy.java` | No context; set once per harness via `AgentHarness.Builder.approvalPolicy` |
| `HookContext` | `jllm-harness/.../hooks/HookContext.java` | Has `chatId`, no principal; several constructors and factories |
| `ChatSessionData` / `ChatSessionStore` | `jllm-harness/.../session/` | Persists chat state; must store principal id only |

## Design

### Principal

```java
record HarnessPrincipal(String id, Set<String> roles, Map<String, Object> attrs) {}
```

- Supplied by the embedder when a chat is created or resumed. Never taken from model input.
- Stored on the chat session by `id` only. Roles and attributes are re-resolved on resume.

### Credential port (per call)

One port replaces the need for a second header concept:

```java
interface CredentialResolver {
    Map<String, String> headers(HarnessPrincipal user, String source); // "mcp:docs", "api:pets"
}
```

- Default: `CredentialResolver.staticHeaders(settings)`, which reproduces today's behavior.
- `AuthProvider` either delegates to this port or gains a default overload `headers(principal, source)` that calls `headers()`. Decide during step 2; avoid three header concepts.

### Tool and approval policy (per chat)

```java
interface UserPolicyResolver {
    UserPolicy resolve(HarnessPrincipal user); // called at chat creation
}
record UserPolicy(Predicate<HarnessTool> toolFilter, ToolApprovalPolicy approval) {}
```

- Default: `UserPolicyResolver.single(policy)`.
- The tool filter applies at chat creation, because the tool list must stay fixed for the prompt cache.
- Approval is evaluated per call, not fixed at creation. Only the tool list affects the prompt cache.
- `ToolApprovalPolicy` keeps its signature for the single-user path. The per-call approval path passes the principal through the permission gate.

### Enforcement

- The tool filter hides tools from the model. It is not the security boundary.
- The registry dispatch refuses tools outside the chat's filter, even if the model names them.
- Argument-level rules (for example `tenantId == principal.tenant`) live in a PRE_TOOL hook. The hook reads the principal from the chat session, found via `chatId`.

### MCP and REST isolation

- Key MCP clients and their tool discovery by (server, credential identity) when credentials differ per user. Otherwise `Mcp-Session-Id` and the `tools/list` result leak across users.
- Cache tool lists the same way.
- Build REST tools per chat or per principal with the resolver injected.

### Web front end

- Each REST/SSE request carries the principal from the embedder's auth layer (the sample uses a header from a fake picker).
- Approval requests are routed to the browser session of the chat's owner, not to a shared prompt.
- Chat listing and access are filtered by principal.

## Steps

1. **Principal and session.** Add `HarnessPrincipal`. Attach the principal id to chat creation and resume in `ChatSessionService` and `ChatSessionData`. Default: no principal (single-user behavior).
2. **Credential port.** Add `CredentialResolver` with `staticHeaders(settings)`. Route `McpHttpClient` and REST calls through it with the principal and source. Decide the `AuthProvider` relationship here.
3. **MCP keying.** Key MCP clients and tool lists by (server, credential identity). Test that two principals with different tokens get separate sessions and tool lists.
4. **Per-chat policy.** Add `UserPolicyResolver`, `UserPolicy`, and `single(policy)`. Apply the tool filter in `HarnessToolRegistry` construction at chat creation.
5. **Dispatch enforcement.** Refuse filtered tools at dispatch. Test a model-named tool outside the filter.
6. **Per-call approval.** Pass the principal into the permission gate and `ApprovalService`. Test that approval requests reach only the owner's chat.
7. **Hooks.** Expose the principal to hooks through the chat session. Do not change `HookContext`'s constructors unless a hook needs it on every event.
8. **Web sample.** Add a fake user picker that sends a principal header. Route approvals and chat listing by principal. Keep it labeled as non-authentication.
9. **Docs.** Add a "Multi-user embedding" section to the docs, about 20 lines: attach a user to a session, resolve a user's token for an MCP server, restrict tools by role.

## Tests

- Single-user defaults: CLI and existing tests unchanged.
- Two principals, same server, different tokens: separate headers, separate MCP sessions, separate tool lists.
- Tool filter at creation and refusal at dispatch.
- Approval routed to the owning chat only.
- Resume with a changed role: the new role applies.
- Prompt cache: tool block stays byte-identical within one chat.

## Open questions

- Should the MCP tool list be per principal, or per credential identity (principals with the same token share)?
- Should `AuthProvider` be deprecated or kept as the static implementation of `CredentialResolver`?
- How are per-user docker sandboxes keyed: per chat (today) or per principal?
- Where does the principal come from in production: an embedder-supplied filter, or a Spring Security integration in jllm-web?

## Risks

- Shared MCP clients leaking sessions or tool lists across users (step 3 is the mitigation).
- Filter mistaken for enforcement (step 5 is the mitigation).
- Stored chats outliving role changes (re-resolve on resume).

## Status

| Step | State | Where |
|---|---|---|
| 1. Principal on the chat | done | `ChatSessionData.principal`, `ChatSessionService.create(title, principal)`, `principal(chatId)` |
| 2. Credential port | done | `CredentialResolver`, `Identity`, `McpHttpClient.callTool(..., principalId, headers)`, `RestApiToolProvider` |
| 3. MCP keying | partial | Sessions are per principal in `McpHttpClient`. Tool lists are discovered once, without a principal (not done) |
| 4. Per-chat policy | done | `UserPolicy`, `UserPolicyResolver`; the filter applies in `AgentLoop.chatTools` |
| 5. Dispatch enforcement | done | `AgentLoop.authorize`: model calls only; script calls covered by the `run_js` grant |
| 6. Per-call approval | done | `AgentLoop.authorize` uses the user's approval policy when one is set. Routing to the owner is by the web ownership check |
| 7. Hooks | done as planned | No `HookContext` change; use `sessions().principal(chatId)` |
| 8. Web sample | done | `UserDirectory`, `UserPolicies`, `FileCredentialResolver`, `UserInterceptor`, `AuthController`, `SignIn.js` |
| 9. Docs | done | `docs/USAGE.md` "Multi-user embedding", `jllm-web/README.md` "Sample users and sign-in" |

Tests: harness 507 run, 0 failures, 3 to 5 skipped (Docker/WSL integration; the skip count varied between runs) (new: `UserIdentityTest`, `ChatSessionServiceTest` principal case). Web: 15 run, 0 failures in the final run (includes `SampleUsersTest`, new). JS 49, CLI 4, all passing.
Live check: started the web jar and confirmed 401 without sign-in, owner and admin access, 404 for other users.

Not verified: the sign-in page in a browser.
