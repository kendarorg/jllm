# Governance and audit

How to see, approve, block and record what the agent does. Three mechanisms, all in the library: **events**, **hooks**
and **ports**.

## Events

A front end follows a chat with `subscribe(chatId, fromSeq, listener)`. Events are numbered, so a client that
reconnects asks for the ones it missed. Types include `text`, `tool_call`, `tool_result`, `approval_request` and
`run_finished`; the contract is in [USAGE](../reference/usage.md). An audit log or a message bus needs nothing more
than a listener.

## Approvals

`approval_request` carries the tool, a preview of the call and the kind of question (`tool`, `host`,
`insecure_cert`, `context`). Your UI answers with `resolveApproval(...)`, once or for the rest of the chat. Because
approvals are events, you can route them to a chat app, a ticket or a four-eyes procedure without changing the
harness. A run that cannot ask is refused, never auto-approved.

## Hooks

| Hook | Typical use |
|---|---|
| `PRE_TOOL` | deny by policy, rewrite input (proxy a URL, add a tenant id), redact |
| `POST_TOOL` | add context, record the result, flag errors |
| `POST_LLM` | audit trail of model output; `durationMs` and `usage` for tracing |
| `STOP` | verify before the agent may finish (run tests, an LLM judge) |
| `ROLE_CHANGED` | audit trail of role changes made at sign-in by the [IdP role mapping](idp-role-mapping.md) (`user`, `from`, `to`, `group`) |

```java
builder.beforeToolCall(ctx -> policy.allows(ctx.tool(), ctx.input())
                ? HookResult.allow() : HookResult.deny("blocked by policy 14"))
       .afterToolCall(ctx -> { audit.record(ctx.chatId(), ctx.tool(), ctx.input(), ctx.isError()); return HookResult.allow(); });
```

A denial reaches the model as `Denied by a hook: <reason>`, followed by an instruction not to reach the same result
another way (no other tool, no shell command) and to report the denial to the user.

## Rules in configuration

Controls that auditors read belong in the settings file, not in code: `permissions.deny/ask/allow`, visibility lists
on MCP servers and REST APIs, `readOnly` sources, per-role tool patterns. Settings are layered (global, project,
explicit), and a rule that does not compile fails the load, naming the rule.

## Ports for your stores

| Port | Holds |
|---|---|
| `ChatSessionStore` | messages, mode, usage and title of each chat |
| `SubAgentRunLog` | sub-agent prompt, transcript, answer, status, usage |
| `StateStore` | per-chat grants and todo lists |
| `TokenStore` | OAuth tokens |
| `ToolApprovalPolicy` | approval decisions |

Defaults are in memory (library) or files (CLI, web). Implement the port to write to your database with your
retention and encryption. See [EXTENDING](../guides/extending.md).

## Flows

When the path matters more than the conversation, use [flows](../guides/workflow.md): the engine, not the model,
decides what runs next. Decisions and model replies are journaled and reused on resume, every flow is validated when
saved, and a person is asked at defined points. Not enforced yet: the `who` label of a question is shown only.
