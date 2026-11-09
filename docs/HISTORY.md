# History

## 1. First version
The harness began as an embedded component of a host application. Its design is a conceptual Java port of the
Python course *"building a coding agent from scratch"* (decodingai): agent loop, context management, permissions,
sandbox, sub-agents and memory.

Instead of relying on a framework's model integration, the harness got its own `ModelClient` interface with two
implementations: Anthropic, and OpenAI-compatible (OpenAI, Qwen, llama.cpp, and vLLM/Ollama through the base URL).
The provider was pluggable from the start.

The first build contained:
- A ReAct loop running until a final stop reason, with handling for `refusal`, `max_tokens` and `pause_turn`.
- Tool selection in two passes (group shortlist, then dependency order) and a `request_tool_group` tool.
- A permission gate (`PermissionGate`, `PermissionMode`, `ToolKind`) and an approval service.
- Built-in tools: files (read, write, edit, glob, grep, confined to a working directory), shell, web search and web
  fetch.
- A `SandboxExecutor` interface with a local-directory backend and a Docker backend.
- Three-tier compaction: clear old tool results, then summarize with the model, then a context rewrite the user
  must approve.
- Sub-agent fan-out (`task`), persisted sessions and sub-agent run logs, a streaming event sink, and cancel through
  `CancelToken` (stops the HTTP call, backoff and tools).
- Metrics, and a usage and cost meter.
- Anthropic features: prompt caching, thinking, citations, `tool_choice`, and switching off parallel tool use.

At first everything was verified only against scripted fake models and fake HTTP servers. The first live run used
llama.cpp with Qwen (128k context). It confirmed tool calls, approvals, bypass mode and cache-read counting. It also
exposed Docker mount-path bugs and showed that a remote Docker daemon cannot mount the local working directory. As
a result the Docker sandbox falls back to the local one when Docker is missing or remote.

From the start the main design constraint was prompt-cache stability: the system prompt, tool list and message
prefix must stay byte-identical between turns and across compaction. `CacheBoundaryTest` and
`CacheUsageBoundaryTest` enforce this.

## 2. Standalone library
The harness moved into its own module, `jllm-harness`, package `org.kendar.llm.harness`, to become a
general-purpose library:
- **No framework:** Spring, JPA and Micrometer were removed, and `FrameworkFreeTest` bans them. Remaining
  dependencies are Jackson, the JDK `HttpClient` and jsoup.
- **Facade:** `AgentHarness.builder()` takes a model client, extra tools, session store, approval policy, sandbox,
  metrics, config and a system-prompt addendum.
- **Interfaces for host-specific parts:** `ChatSessionStore` (in-memory by default), `ToolApprovalPolicy` (based
  on `ToolKind` by default), `HarnessMetrics` (no-op by default). A plain `HarnessConfig` record holds the
  configuration.
- **Tools:** file, shell and web tools are registered by default; anything else is passed in by the host. The
  registry rejects duplicate names and sorts tools by group, then name, so the order the host passes them in does
  not matter. This keeps the cache prefix stable.
- **Prompt:** a generic base system prompt; host-specific policy goes in the addendum.
- No backward compatibility with older sessions.

It then got a standalone CLI (`Main`), slash commands, and a set of extension points:
- A JSON settings file with environment-variable expansion; a missing variable stops startup with its name.
- An MCP client over HTTP: remote tools are named `mcp__<server>__<tool>` and grouped by server.
- Tools generated from OpenAPI specs (swagger-parser), limited to an `allowedPaths` allowlist.
- Hooks (`PRE_TOOL`, `POST_TOOL`, `USER_PROMPT`, `STOP`, `PRE_COMPACT`) that can allow, deny, change the input or
  add context.
- A plugin interface (`HarnessPlugin`) that can register tools, hooks, commands, skills and agents.
- Skills: names and descriptions go in the system prompt, the body is loaded on demand. `KENDAR.md` instruction
  files are loaded in a fixed order.
- Agent types: built-in `general`, `explore` and `plan`, plus custom definitions.
- MCP and API sources can be enabled, disabled and health-checked at runtime through numbered menus (`/tools`,
  `/mcp`, `/apis`), usable from any front end.

## 3. Modes, sandboxing, compaction rewrite
- **Modes** `plan`, `edit` and `auto`, each with a permission matrix:

  | Mode | Working directory | Approvals |
  |---|---|---|
  | `plan` | read-only | writes outside the scratch directory are denied |
  | `edit` | writable | file edits auto-approved, shell asks |
  | `auto` | writable | nothing asks; deny rules still apply |

  The mode is stated in a per-turn note, so switching mode does not break the cache. `/mode` opens a menu, and
  `BYPASS` was renamed `AUTO`.
- **CLI sandbox rooted at the current directory** (`CwdSandboxExecutor`), which rejects `..` and symlink escapes.
- **A scratch directory** per chat and agent (`<root>/<chatId>/<agentId>`), writable in every mode.
- **Compaction rewritten in the opencode style:**
  - It triggers at the context window minus the larger of 15% and 16k tokens.
  - If the provider rejects a request as too long, it compacts and retries once.
  - The summary uses a fixed template (Objective, Requirements, Decisions, Work State, Next Move, Relevant Files,
    Important Context) and keeps the most recent user messages verbatim.
  - Later compactions update the existing summary; input too large to summarize is shortened step by step.
- **A fast model role** (`ModelRole.FAST`) with a `ToolClassifier` that approves or blocks tool calls in auto mode.
  If the classifier fails, the harness asks the user.
- Context and sandbox sections in the settings file, and a `--context-tokens` CLI flag.
- All prompts moved out of the code into resource files (`prompts/*.md`).

Sub-agents, until then always read-only, became full agents:
- A sub-agent takes the parent's mode and can never be less restricted. A `readOnly` flag per agent type keeps
  `explore` and `plan` read-only.
- Approval requests from sub-agents are sent to the user one dialog at a time per chat, labelled with the agent's
  name.
- "Allow for this chat" grants are shared by the parent and all its sub-agents. They are not added to the model's
  context, so the cache is unaffected.
- Writable sub-agents run one after another in `edit` and `auto` mode to avoid concurrent edits to the same file.

Sessions and sub-agent histories can be stored as files under `.kendhar/history`, and IDs are v7 UUIDs.

## 4. Mnemonic context and small models
A second context strategy was added, modelled on mnemonic-proxy:
- **`ContextStrategy` interface:** compaction moved behind it unchanged and stays the default. The strategy is
  chosen in settings, with `--context-strategy`, or in code through a factory.
- **Mnemonic strategy:**
  - The stored log is never rewritten. Each request is rebuilt from the log, saved mask decisions and segment
    boundaries, and comes out byte-identical every time.
  - Old tool results, tool inputs and thinking blocks are replaced in batches by one-line receipts with recall IDs.
    Batches only advance forward and fire when usage crosses set limits, so the cache is invalidated rarely.
  - When the window fills, a segment switch writes handoff notes. The new prompt holds the first request, the
    notes, an index of recall IDs and the recent messages.
  - A `history_recall` tool searches masked content by keyword (BM25 index in pure Java, identifier splitting, rank
    fusion). Its scope can be limited to one sub-agent run, because `task` results now carry a `runId`.
  - A guard blocks writing a receipt marker into a real file.
  - `/compact` does nothing under this strategy.
  - Not implemented: llama-server slot per agent and checkpoint alignment.

Then came tuning against real small models such as Haiku:
- System-prompt changes.
- Recovery of a `write_file` cut off mid-output: the part already received is kept in the scratch directory so the
  model can finish it (`PartialWrite`, `PartialToolCall`).
- A `small-model.md` prompt addendum, switched on with `--small-model` or the `smallModel` setting. It gives
  concrete, checkable rules: file list first, one file per response, edit rather than rewrite, no placeholders.
- A full documentation set in `docs/`: FLOW, CACHES, EXTENDING, INTERNALS, MCP, SKILLS, TOOLS, USAGE.

## 5. Front ends and documentation split
The command line moved out of the library into its own module, `jllm-cli` (shaded runnable jar), and a browser front
end, `jllm-web`, was added: Spring Boot REST + SSE over the same `AgentHarness`, with a Vue 3 chat UI. Both use the
same sandbox, file-history stores and slash commands. Documentation followed: a short root README, one README per
module, and `docs/` as the harness reference.

## 6. Resilience and the scope strategy
Reading how other Java harnesses (AgentScope, Solon AI, jOpenAgent, ADK) handle long sessions produced a short list of
gaps that fit the small-footprint design:

- **Provider fallback.** `order` on a provider chains fallbacks; retry stays per provider (`maxRetries`).
- **Tracing hooks.** `POST_LLM` and `POST_TOOL` carry duration and token usage; an OpenTelemetry adapter is a plugin,
  not a dependency.
- **State store.** Tool grants are saved next to the chat history instead of living only in memory. Plan state needed
  nothing new: the mode is already in the session and plan files are in the scratch directory.
- **`scope` context strategy.** A whole-request budget, tool results offloaded to disk with a head and tail preview, a
  fixed trim order (offload, clear, summarize), a pinned first user message, and a `todo_write` / `todo_read` task
  list that lives outside the transcript and is re-injected on every request.

Left out on purpose: a filesystem abstraction behind the file tools, per-call user and session identity,
distributed state, multi-tenancy, sandbox snapshots.

## 7. Restoring chats

Chats lived in memory; only a write-only copy of the log went to disk, so a restart lost every chat and left the
mnemonic and state files orphaned. `FileChatSessionStore` replaced the mirror: a `session.json` row per chat, the log
read back lazily, append-only writes while the log grows. Because the context state was already keyed by chat and agent
id, restoring the row and the log restores the context too, and the next request is the same as without the restart.
Decisions: the chat id is always shown and the user chooses (startup question or menu, `--resume`), never a silent
restore; chats are kept until deleted (`/chats delete|clean`, optional `chatRetentionLimit`); enumeration is a directory
scan, no index; one process per history root (lock file); an interrupted tool call is closed with a synthetic result.
No backward compatibility with the old `messages.jsonl`-only layout. The web UI shows the id, offers to continue the
remembered chat and marks an interrupted run. Shell commands that add no information (`mkdir`, `rm`, `cd`, ...) are
replaced in the context by a stub after their turn, in batches because the rewrite is a cache miss; the originals
stay in the log as `ignore` reference lines. The qualifier asks the cheap model at temperature 0, with the command
only. Not done on purpose: restoring the last `/model`.

## 8. Cleanup and comparison

- **Static-analysis cleanup** across `jllm-cli` and `jllm-harness`: method references instead of lambdas, `equals`
  instead of `==` for the CLI's `EOF` sentinel (now a plain constant), and small fixes in the context, tools,
  session and provider classes. No behaviour change intended.
- **Portable tests.** `McpStdioClientTest` no longer needs `sh`: the fake MCP server is a Java class run by the
  current JVM. `DockerSandboxExecutorTest` was reworked so it runs on Windows too.
- **Docs.** `docs/HARNESSES-COMPARE.md` added: a feature matrix of jllm against mini-swe-agent, Pi, OpenCode, Qwen
  Code and Hermes Agent (competitor columns are from memory and unverified). The root `history.md` was removed in
  favour of this file; README links updated.

## Overall direction
- **Where it lives:** an embedded component, then a standalone library with a CLI, then a web front end.
- **Model target:** Anthropic, then local llama.cpp/Qwen, then small models.
- **Context management:** three-tier compaction, then opencode-style compaction, then a second strategy that never
  rewrites the log, then a budgeted one with offloading and a task list.
- **Permissions:** one approval flag, then a mode-by-tool-kind matrix, then sub-agents inheriting the parent's
  permissions.
- **Unchanged throughout:** the prompt-cache prefix must stay byte-stable.
