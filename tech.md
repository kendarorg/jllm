# jllm: what I discovered building an embeddable agent harness in Java

Java 21, Maven multi-module. Library `jllm-harness` plus two front ends (`jllm-cli`, `jllm-web`) and optional document readers (`jllm-pdf`, `jllm-docs`).

---

## 1. The core idea

The agent loop runs **inside your process**. Your Java code, MCP servers and REST APIs are the tools; a permission system decides what needs a human.

```
host app ──start(chatId, prompt)──► AgentHarness ──► virtual thread (one run per chat)
    ▲                                    │
    └──── events (text, tool_call, approval_request, usage, run_finished) ◄──┘
```

- `start` returns at once; everything is reported as events.
- Any front end (terminal, SSE, queue, desktop) follows, approves and cancels through the same API.
- Events are buffered per chat (2000 max, with sequence numbers). `subscribe(afterSeq)` replays the buffer, then streams live, so a front end can reconnect without missing anything.
- Finding: an event-based facade keeps the library free of any UI assumption. CLI and web are just two subscribers.

## 2. Architecture in one picture

```
AgentHarness (facade + builder)
 ├─ AgentService      runs, events, cancel
 │    └─ AgentLoop    ReAct loop
 │         ├─ ContextStrategy      what the next request carries
 │         ├─ ToolSelector         which tool groups are visible
 │         ├─ PermissionGate       mode × tool kind → allow / ask
 │         ├─ ApprovalService      pending dialogs, grants
 │         ├─ ToolClassifier       fast-model verdict in auto mode
 │         ├─ Hooks                observe, deny, rewrite
 │         ├─ SandboxExecutor      where things run
 │         └─ ModelClient          Anthropic | OpenAI-compatible
 ├─ HarnessToolRegistry   Java tools + MCP + REST, sorted, immutable snapshots
 ├─ ExternalSources       MCP / OpenAPI discovery, health, toggles
 ├─ SkillCatalog, SlashCommands
 └─ ChatSessionService → ChatSessionStore
```

- `SubAgentTool` needs the loop and the loop needs the tool: broken with a one-element array captured by a `Supplier`.
- `task` and `request_tool_group` are **not in the registry**: they cannot be disabled, sub-agents never see them, no name clashes.
- Build-time failures are loud (no model client, duplicate tool/skill/agent names). Runtime problems are quiet (unreachable MCP source, missing skill file are only logged).

## 3. The agent loop

```
user msg → hooks → append + save
loop (max 40 turns):
   tools = chatTools()          re-read every turn
   view  = strategy.prepare()   the context for this request
   PRE_LLM → model.complete(pinned, extra + per-turn note, view, tools) → POST_LLM
   append assistant msg, save
   tool_use?  ─► run all tool calls in parallel (virtual threads)
              ─► ONE user message with all results (in order), save
   end_turn / stop_sequence / refusal → return
```

- Tool selection in two passes: group shortlist (fast model), then dependency order; `request_tool_group` lets the model ask for more groups.
- Cancel (`CancelToken`) aborts the in-flight HTTP request, the backoff sleep, the wait for a model slot and running tools.
- At `maxTurns` an interactive run asks "continue?" as an approval; otherwise it stops with a warning.
- In `plan` mode the final answer is emitted as `plan_ready`.

### The log invariant

> The message log is valid for the next request **at every save point**.

- A failed or denied model call appends nothing.
- Every `tool_use` gets a `tool_result`, even if cancelled, unknown, denied, failed or truncated.
- After a crash, `InterruptedTurnRepair` closes an unanswered `tool_use` with a synthetic result.
- Finding: this one rule makes cancel, restart and error handling simple.

## 4. Stop reasons: the unglamorous part

Most of the loop's real complexity is handling what a model does when it does *not* end cleanly.

| Case | What the harness does |
|---|---|
| `max_tokens`, no tool call | user message "response cut off, continue"; up to 3 times in a row; parts are joined |
| `max_tokens`, cut `tool_use` | complete calls still run; the cut call gets an error result asking for smaller output |
| cut `write_file` | complete lines kept in `<scratch>/partial/<callId>-<file>`; result tells the model how to finish with `append: true` |
| response is only a cut call | retried once; twice in a row ends the run |
| `max_tokens` with output < 90% of limit | the *server's context window* is full, not the output limit (llama.cpp reports both as `length`); say so, don't continue |
| context-overflow error | `strategy.onOverflow()` once, retry the same turn; second rejection is an error |
| `pause_turn` | next turn with the assistant content as is |

- Local servers cut off mid-call return raw `<tool_call>…` text with no closing tag, or broken JSON arguments. The OpenAI-compatible client turns an unclosed text call into a `tool_use` block; `PartialToolCall` reads `path` and `content` leniently from JSON or Qwen XML.
- Finding: small and local models fail in ways hosted APIs hide. Recovery beats prevention.

## 5. The central constraint: prompt-cache stability

Everything else bends around this: **system prompt, tool list and message prefix must be byte-identical between turns.**

```
request = tools[]            sorted by (group, name), cache breakpoint on the last
        + system[0] pinned   "chatId=…; agentId=…"      never changes in a chat
        + system[1] extra    base prompt + addendum + instruction files
                             + per-turn note (date, OS/shell, dirs, mode)   ← LAST, so it's cheap to change
        + messages[]         append-only
```

- Tool set fixed on the chat's first message; changes only by an explicit group request or toggle.
- Mode changes go into the per-turn note, not the system prompt.
- Approval grants never enter the model context.
- Anthropic: two breakpoints, so a mode switch invalidates the system entry but the tool entry still hits; 1 h TTL.
- OpenAI-compatible / local: prefix caching covers *everything up to the first changed byte*, history included. A mode switch re-processes the conversation from the note on.
- Enforced by tests (`CacheBoundaryTest`, `CacheUsageBoundaryTest`).

### Helper requests share the prefix

Summaries and handoff notes are built as **the agent's own request plus one instruction**, not a fresh prompt.

```
turn N:   prefix + messages[0..k]                    → prefix cached
helper:   same prefix + messages[0..k] + "write the summary"   → cache hit
```

- A fast-model call is used only when a separate fast model is configured.
- Finding: the cheapest summary is the one that reuses the cache you already paid for.

### Serializing model calls

With `maxParallel = 1`, one model call is in flight for the whole harness and sub-agents run depth-first. A local server with a single KV-cache slot is never evicted by a concurrent request.

## 6. Three tool sources

| Source | Config | Result |
|---|---|---|
| Java code | `HarnessTool` / `SimpleTool` (name, description, schema, group, kind, lambda) | whatever you register |
| MCP server | `mcpServers`: streamable HTTP or stdio child process, headers with `${ENV}` | `mcp__<server>__<tool>` from `tools/list` |
| REST API | `restApis`: OpenAPI URL, base URL, `allowedPaths` | `<api>__<operationId>`, schema from the spec |

- REST allowlist: nothing outside `allowedPaths` is reachable. GET/HEAD are read-only; other methods need approval.
- A dead MCP server never blocks startup; sources have health checks and runtime on/off menus (`/tools`, `/mcp`, `/apis`) that work in any front end.
- Registry: rejects duplicates, sorts by group then name, immutable snapshots swapped through a `volatile`. The order the host passes tools in does not matter.
- Built-ins: files (read, write, edit, glob, grep), shell, web search/fetch, `task`, skills. PDF and docx readers are optional plugins.

## 7. Permissions

```
tool call
  → PRE_TOOL hooks (deny / rewrite)
  → deny rules                       apply in every mode
  → mode × ToolKind matrix           ALLOW or ASK
  → classifier (auto mode only)      SAFE / UNSAFE / UNSURE
  → approval event → user            or a remembered grant
```

- `ToolKind`: `READ_ONLY`, `FILE_EDIT`, `OTHER`. The kind is a **promise by the tool author**; read-only sub-agents only receive `READ_ONLY` tools.
- Modes:

| Mode | Working dir | Asks |
|---|---|---|
| `plan` | read-only | writes outside scratch denied |
| `edit` | writable | file edits auto, shell asks |
| `auto` | writable | nothing, deny rules still apply |

- A grant ("allow for this chat") only replaces the question. Hooks, plan confinement and the classifier are still evaluated first.
- Sub-agents inherit the parent's mode and can never be less restricted.

### Approvals

- One dialog per chat at a time, main agent and sub-agents alike (per-chat fair lock), labelled with the agent name.
- Timeout (600 s, starts when the dialog is shown) or cancel = rejected.
- The same service carries the context-rewrite approval, with a `proposed` text the user can edit.

### The auto-mode classifier

- Only with a fast model, only for non-read-only tools.
- Input: working directory, newest plain user message (clipped), tool name and input. **Never tool output**, so a tool result cannot talk the classifier into approving.
- Below 0.8 confidence, unparseable, error or 20 s timeout → `UNSURE` → ask the human.
- Finding: fail towards asking.

## 8. Sandboxing

| Executor | Used by | Work dir | Isolation |
|---|---|---|---|
| `CwdSandboxExecutor` | CLI | the project directory | none |
| `ChatDirSandboxExecutor` | web | `<X>/<chatId>` | none |
| `LocalDirSandboxExecutor` | library default | temp dir per chat | none |
| `DockerSandboxExecutor` | opt-in | wraps one of the above | container |

- File tools run **inside the JVM**, jailed to the work dir (no `..`, no symlink escape). Only `bash` leaves the JVM.
- Chat ids are validated (`[A-Za-z0-9._-]{1,64}`, no `..`).
- Every agent has a scratch directory, writable in every mode.
- `bash`: 120 s default timeout (capped at 600), process tree killed on timeout, last 50 KB returned, full output in `bash-N.log`.
- The model is told the OS and shell, so it writes matching syntax.

### Docker: oneshot vs daemon

```
oneshot:  docker run --rm per command             (container start cost every call)
daemon:   long-running container + small HTTP daemon (sandbox-agent.py, stdlib only)
          all tools go through it; files, packages, background processes survive
```

- Container flags: memory, cpus, pids limit, read-only root, 64 MB `/tmp`, `--cap-drop ALL`, `no-new-privileges`, non-root.
- Daemon published on `127.0.0.1` only, random port, **per-container token** (SHA-256 of a random UUID) in `X-Jllm-Token`, compared in constant time. `JLLM_*` variables are stripped from commands, so `env` does not leak it.
- Docker missing → the agent **asks** whether to run without isolation, never silently.
- Remote Docker: workspace uploaded on creation, downloaded as backup on exit; the scratchpad is lost, history is not (always local).
- Lessons: a remote daemon cannot bind-mount the local directory; plan mode cannot stop `bash` writing inside the container (a convention only); the default sandbox has none, untrusted input needs Docker.

## 9. Context management: three strategies

Two problems solved together: the **prompt cache** and the **context window**.

```
compaction (default)   clear old tool results → summarize → rewrite (user approves)
mnemonic               log never rewritten; old results → receipts + recall IDs
scope                  whole-request budget; big results offloaded to disk; pinned first msg; todo list
```

### Compaction

- Triggers at window minus max(15%, 16k tokens). If the provider says "too long": compact and retry once.
- Fixed summary template: Objective, Requirements, Decisions, Work State, Next Move, Relevant Files, Important Context.
- Recent user messages kept verbatim; later compactions update the existing summary.
- Cost: every compaction rewrites the cached prefix.

### Mnemonic: render, never rewrite

```
verbatim log ─┐
              ├─► Renderer (pure function) ─► request messages
decisions ────┘     same log + same decisions = same bytes
(masks, frontier, segments)
              log ─► BM25 recall index ─► history_recall tool
```

- **Level 1, masking batch.** Above 60% of the ceiling, old tool results/inputs/thinking become one-line receipts:

```
[masked: read_file(path=src/A.java) -> ~4200 tokens, recall id=r3f2a91c0d4e1]
```

- Batches only advance forward past a `frontier`, so the cache break point only moves forward. Under `minBatch` of savings, do nothing: not worth breaking the cache.
- **Level 2, segment switch.** When masking cannot leave room for the answer: the model writes handoff notes (using the cached prefix); the new prompt is *first user request + notes + recall index + recent messages*.
- **Validation.** Each decision carries a hash of its message. On mismatch (`/clear`, edit, truncation) all decisions are dropped.
- **`history_recall`.** Pure-Java BM25 with identifier splitting and rank fusion; scope can be limited to one sub-agent run (`task` results carry a `runId`).
- A guard blocks writing a receipt marker into a real file.
- Finding: batch rare, large rewrites; every rewrite is a cache miss.

### Scope: budget, offload, todo

```
budget = window − reserve − safety − fixed (system, note, tool schemas)
trim order, only while over budget:
   1. offload  tool results > 20k chars → file, keep head + tail preview (1500)
   2. clear    results older than N turns (offload path survives)
   3. summarize everything but the latest turn
```

- First user message is **pinned**, never summarized.
- `todo_write` / `todo_read`: the list lives in a state store, not the transcript, and is appended to the last user message *on a copy* each request. Compaction cannot lose or duplicate it; the cached prefix does not change.

### Choosing

| | Compaction | Mnemonic | Scope |
|---|---|---|---|
| Information loss | lossy | none, recallable | lossy, big outputs on disk |
| Cache | rewrites prefix | breaks at first new mask | like compaction |
| Best for | short chats, hosted | long sessions, local KV cache | long task-driven, huge outputs |

## 10. Noise commands

Shell commands that add no information (`mkdir`, `rm`, `cd`, `cp`...) waste context.

```
bash call < 512 chars, "exit code: 0", result < 200 chars, turn ended
   regex: state-changing chain, no pipe/redirect  → drop
   regex: informative (cat, ls, grep, build, test) → keep
   else: fast model, temperature 0, command text only, 4 s timeout → keep | drop
drop applied in batches → stub "ok (exit 0)"; original kept as an `ignore` line in the log
```

- The qualifier sees the command, **never the output** (untrusted). Any error means keep.
- The `tool_use`/`tool_result` pairing stays valid for every provider.

## 11. Sub-agents

```
parent ──task (up to 8 prompts)──► AgentLoop.runSubAgent (persist=false)
                                   fresh messages, own scratch, own context state
        ◄── answer + runId ──────  transcript saved in SubAgentRunLog
```

- Types: `general`, `explore`, `plan`, custom; `readOnly` per type.
- Same loop code, different wiring: fresh message array, mode from the caller.
- **Context stack** (`execution.useContextStack`, default `true`): the sub-agent request continues the parent's prefix, so
  the sub-agent is a push on the KV cache and returning is a pop. Same `pinned` text, system prompt, mode note and
  **full tool list** as the parent, then the parent's history (`inheritContext`), then one user message holding the role
  text (`subagent-suffix`, read-only note, type prompt) and the task. A call to a tool the type may not use
  (`task`, `request_tool_group`, non-read-only in a read-only type, outside `tools`) is refused when executed ("Tool unavailable for agent"
  tool"): one failed call costs far less than re-prefilling the cache. The parent's context after the call is the
  history before it plus the `task` call and its result; the sub-agent never writes into it. With `false` the
  sub-agent builds its own system text and filtered tool list (different prefix, evicts a single-slot cache).
- Usage is summed and added to the chat; a model error becomes `status: failed`, never an empty `ok`.
- Parallel unless `maxParallel = 1` or the type is writable in `edit`/`auto` (writers would race on the same files).
- Cancelling the parent cancels all sub-agents (same token).

## 12. Persistence and restore

```
<root>/.lock                         one process per history root
<root>/<chatId>/session.json         chat row (atomic: temp + move)
<root>/<chatId>/<agentId>/messages.jsonl   append-only while the log grows
<root>/<chatId>/<agentId>/mnemonic.json    decisions
<root>/<chatId>/state/*.json         grants, todos
<root>/<chatId>/subagent-<runId>/    transcript, result, run.json
```

- The log is written before the row: a crash leaves the row at most one turn behind.
- Everything is keyed by chat and agent id, so restoring the row restores the context. The next request equals the one without the restart (`RestartRestoreTest`).
- Listing is a directory scan, no index file; a corrupt row is skipped with a warning.
- Decisions: always show the chat id and let the user choose (`--resume`), never restore silently.

## 13. Models and resilience

- `ModelClient` has two implementations: Anthropic (prompt caching, thinking, citations) and OpenAI-compatible (OpenAI, Qwen, llama.cpp, vLLM, Ollama). Messages are kept in **Anthropic shape internally**; the OpenAI client translates both ways.
- Retries on 429, 529, 5xx and I/O errors with `retry-after` or exponential backoff (max 30 s), reported as `retry` events.
- One retry after a 401 (for `AuthProvider` token refresh).
- Provider fallback chain via `order`; retry stays per provider.
- Fast-model role: summaries, tool-group selection, classifier, noise qualifier.
- Hooks `POST_LLM` / `POST_TOOL` carry duration and tokens; OpenTelemetry would be a plugin, not a dependency.

## 14. Small and local models

Real runs (llama.cpp + Qwen with 128k, then Haiku) exposed what scripted fakes never did: Docker mount-path bugs, cut-off tool calls, models writing placeholders.

- `small-model` prompt addendum: file list first, one file per response, edit instead of rewrite, no placeholders.
- Partial-write recovery (section 4).
- Cache-read counting verified on a local server.
- Finding: concrete, checkable rules beat general guidance for small models.

## 15. Extension points

- **Hooks:** `PRE_TOOL`, `POST_TOOL`, `USER_PROMPT`, `STOP`, `PRE_COMPACT`, `PRE_LLM`, `POST_LLM`, `PRE_AGENT`, `POST_AGENT`: allow, deny, rewrite input, add context.
- **Plugins** (`HarnessPlugin`, found by `ServiceLoader`): tools, hooks, commands, skills, agents.
- **Skills:** names and descriptions in the prompt; the body loads on demand. `KENDAR.md` instruction files load in a fixed order.
- **Ports with in-memory defaults:** `ChatSessionStore`, `SubAgentRunLog`, `ToolApprovalPolicy`, `HarnessMetrics`, `SandboxExecutor`, `ModelClient`, `ContextStrategy`.

## 16. Engineering lessons

- **Framework-free** (Jackson, JDK `HttpClient`, jsoup); a test bans Spring/JPA/Micrometer.
- **Fake models and fake HTTP servers** give deterministic tests; real models still found bugs the fakes did not.
- Prompts live in resource files, not code.
- Tests made portable (Java fake MCP server instead of `sh`; Docker tests on Windows).
- Static-analysis cleanup passes with no behaviour change.
- Read other harnesses (AgentScope, Solon AI, jOpenAgent, ADK, opencode, Pi, Hermes) for gaps, then add only what fits a small footprint.
- Left out on purpose: filesystem abstraction, per-call identity, distributed state, multi-tenancy, sandbox snapshots.

## 17. What never reaches the model

| Item | Where |
|---|---|
| approval grants | `StateStore`, survive restart |
| todo list | state store, appended on a copy |
| sub-agent transcripts | run log (parent sees only the answer) |
| tool output beyond `toolMaxChars` | dropped with a marker |
| classifier / selector requests | separate plain requests |
| hook decisions | only their effect |
| `ignore` reference lines | log only, for audit |

Finding: deciding what *not* to show the model is as important as what to show it.

## 18. Evolution

```
embedded component → standalone library + CLI → web UI
Anthropic → local Qwen → small models
compaction → mnemonic → scope
approval flag → mode × kind matrix → inheriting sub-agents
memory-only chats → restorable chats
```

Constant throughout: **byte-stable cache prefix**.

## 19. Not done yet

- OAuth/tokens on MCP/API calls and provider logins
- Model streaming (text, thinking, tool-argument deltas, abort mid-stream)
- Messaging gateway (Telegram, Slack...) as a separate module
- Authentication on the web UI (it is a demonstration)

## Credits

mnemonic-proxy (mnemonic strategy), decodingai course (structure), opencode (inspiration).
