# jllm-harness

Framework-free coding-agent harness for Java: a library to embed and a command-line agent. No Spring, JPA or
Micrometer. Java 21, Jackson, JDK `HttpClient`, jsoup, swagger-parser, slf4j-api.

- **Models**: Anthropic (prompt caching, thinking, citations) and any OpenAI-compatible endpoint (OpenAI, Qwen,
  llama.cpp, vLLM, Ollama); an optional fast model for summaries, tool-group selection and the auto-mode classifier;
  a stricter prompt for small models.
- **Loop**: ReAct loop with per-chat sessions, cancel, continuation of cut-off responses and recovery of cut-off
  `write_file` calls.
- **Tools**: files (confined to the working directory), shell, web search and fetch; MCP servers over HTTP; OpenAPI
  operations as tools; your own `HarnessTool`s. The tool set is fixed per chat and sorted, so the prompt-cache
  prefix stays stable.
- **Permissions**: `plan` / `edit` / `auto` modes, deny rules, approval policy, per-chat grants, fast-model
  classifier in `auto`.
- **Sandbox**: current directory (CLI), per-chat directory or Docker; a writable scratch directory per chat and agent.
- **Context**: three strategies. `compaction` (default) clears old tool results, then summarizes, then proposes a
  rewrite for approval. `mnemonic` never rewrites the log: it masks old content behind receipts, switches segments
  with handoff notes and offers `history_recall`. `scope` measures the whole request, offloads large tool results to
  disk, never compacts the first user message and keeps a `todo_write` task list outside the transcript.
- **Sub-agents**: `task` tool with `general`, `explore`, `plan` and custom types; they inherit the caller's mode.
- **Extensions**: settings file, hooks around agents, model calls and tool calls, plugins, skills, `KENDAR.md`
  instruction files, slash commands and numbered menus.
- **Persistence**: in memory or as files under `.kendhar/history`; ports for your own stores. Tool grants and todo
  lists go through the `StateStore` port (`<history>/<chat>/state/*.json` in the CLI and web).
- **Resilience**: `order` on a provider chains fallbacks; hooks report duration and token usage for tracing.


Front ends: [jllm-cli](../jllm-cli/README.md) (terminal) and [jllm-web](../jllm-web/README.md) (browser). Reference docs are in [docs](../docs).

## Build

```sh
mvn -pl jllm-harness -am package            # jar, sources jar, test-jar (HarnessTestSupport)
mvn -pl jllm-harness -am package -Prelease  # also the javadoc jar
```

## Embedding

```java
AgentHarness h = AgentHarness.builder()
    .modelClient(ModelClients.anthropic(AnthropicSettings.defaults(System.getenv("ANTHROPIC_API_KEY")),
                                        new ObjectMapper()))
    .tools(List.of(myTool))                 // added to the standard Files, Shell and Web tools
    .sessionStore(new InMemoryChatSessionStore())   // implement ChatSessionStore to persist
    .approvalPolicy(ToolApprovalPolicy.byKind())    // everything not READ_ONLY asks
    .config(HarnessConfig.defaults())
    .systemPrompt("Project specific rules appended to the generic base prompt.")
    .build();

String chatId = h.sessions().create(null).chatId();
h.subscribe(chatId, 0, ev -> System.out.println(ev.type() + " " + ev.data()));
h.start(chatId, "List the files in the workspace");
```

Providers: `ModelClients.anthropic(...)`, `ModelClients.from(LlmSettings, mapper)` for any OpenAI-compatible endpoint
(openai, gemini, qwen, llamacpp, vLLM) and `ModelClients.ollama("llama3.1", mapper)` for a local Ollama
(`http://localhost:11434/v1`, no key). On the command line: `--provider ollama --model qwen3:8b`
(`--base-url` for a remote Ollama). Ollama's OpenAI endpoint cannot set the context size per request: if long chats
get truncated, raise it on the Ollama side (`OLLAMA_CONTEXT_LENGTH`, or `num_ctx` in a Modelfile) and keep
`--context-tokens` below it.

Small models (e.g. a local Qwen 27B) skip the abstract rules of the base prompt ("keep it simple", "check imports
before finishing"). `--small-model` (no value), settings `"smallModel": true` or builder `.smallModel(true)` append
`prompts/small-model.md` after the base prompt, for the main agent and its sub-agents: a file manifest before writing,
hard limits for "simple" (at most 10 source files, no DI framework, no use-case layers, no placeholders), only classes
from declared dependencies, explicit imports, read a project file before calling into it, `edit_file` instead of
rewriting, and a per-file check list (package, imports, identifiers, call signatures, wiring) before saying "done".
Leave it off for large models: it costs about 600 tokens of prompt and restricts the design.

`--max-tokens` is the output limit of one response (default 32000, every provider), not
the window. A text response cut off there is continued automatically (up to 3 times). A response cut off in the
middle of a tool call is answered with an error asking for smaller output and retried once; a second cut in a row
ends the run. A cut `write_file` keeps the lines that arrived in the scratch dir so the model can finish them with
`append: true`. A stop well below the limit is reported as a full context window on the server. Details in
[INTERNALS](../docs/INTERNALS.md#stop-reasons-and-errors).

## Settings file, hooks and plugins

```java
AgentHarness h = AgentHarness.builder()
    .modelClient(client)
    .settings(Path.of("harness.json"))                       // MCP, REST, skills, instruction files, agent types
    .hook(HookEvent.PRE_TOOL, ctx -> HookResult.deny("no"))  // USER_PROMPT, PRE_TOOL, POST_TOOL, STOP, PRE_LLM, POST_LLM, PRE_AGENT, POST_AGENT
    .beforeAgent(ctx -> ok()).afterAgent(ctx -> ok())        // fluent: main agent or sub-agent (ctx.agent())
    .beforeLlmCall(ctx -> ok()).afterLlmCall(ctx -> ok())    // every model call
    .beforeToolCall(ctx -> ok()).afterToolCall(ctx -> ok())  // every tool call
    .plugin(registrar -> registrar.tool(t).hook(HookEvent.STOP, h2).command("/deploy", "Deploy", (chat, arg) -> "ok"))
    .build();
```

```json
{
  "mcpServers": [{"name": "docs", "url": "https://h/mcp", "headers": {"Authorization": "Bearer ${DOCS_TOKEN}"},
                  "readOnly": true, "enabled": true}],
  "restApis":   [{"name": "pets", "openApiUrl": "https://h/openapi.yaml", "baseUrl": "https://h/api",
                  "allowedPaths": ["GET /pets", "/pets/{id}", "/store/*"]}],
  "skills":     [{"name": "pdf", "path": "skills/pdf/SKILL.md"}],
  "instructionFiles": ["KENDAR.md", "~/KENDAR.md"],
  "agents":     [{"name": "scout", "description": "Looks around", "tools": ["read_file", "grep"], "readOnly": true, "prompt": "..."}],
  "providers":  [{"name": "local-qwen", "type": "openai", "baseUrl": "http://localhost:8080/v1", "model": "local",
                  "requireApiKey": false, "contextTokens": 32000, "order": 1}],
  "defaultProvider": "local-qwen",
  "permissions": {"deny": ["bash(rm -rf .*)"], "ask": ["bash(.*curl.*)"], "allow": ["bash(mvn .*)"]},
  "execution":  {"maxParallel": 1},
  "sandbox":    {"tempDir": "/var/tmp/harness", "timeoutSeconds": 120, "backend": "docker",
                 "docker": {"image": "maven:3-eclipse-temurin-21", "network": "none"}},
  "context":    {"maxTokens": 100000, "auto": true, "keepTokens": 15000, "buffer": null, "keepRecentTurns": 6,
                 "strategy": "compaction", "scope": {"offloadChars": 20000, "safetyPercent": 2}},
  "output":     {"toolMaxChars": 30000, "eventMaxChars": 2000},
  "smallModel": false
}
```

- `providers`, `defaultProvider`, `permissions` (regex deny/ask/allow rules), temperature, `/model`, `/undo`, steering
  (`steer` / `followUp`), checkpoints and the pdf/docx document readers are described in [USAGE](../docs/USAGE.md#providers-and-temperature)
  and [TOOLS](../docs/TOOLS.md#document-readers). `builder.discoverPlugins()` installs the `HarnessPlugin`s found by
  `ServiceLoader` (for example `jllm-pdf` and `jllm-docs`); `builder.documentReader(...)` adds a reader directly.
- `${ENV_VAR}` is expanded in every string; a missing variable fails the load. OAuth is not supported (static headers).
- A REST operation becomes a tool only if it matches `allowedPaths` (`METHOD /path`, method optional, `*` wildcard).
  Tools are named `<api>__<operationId>`; GET/HEAD are read-only. MCP tools are `mcp__<server>__<tool>`.
- `execution.maxParallel` (default `1`, made for a local LLM; `N` allows N model calls and chat runs at once, `-1` is unlimited). With `1`: one model call at a time, depth-first. The main agent calls the
  model; when it uses `task`, each sub-agent runs to its end (one after the other, even across several `task` calls)
  before control returns to the agent and then to main. This applies to every model call (chats, compaction, tool
  selection), so a local server with one small GPU never sees two requests. Plain tool execution that does not call
  the model still runs concurrently.

  ```json
  {"execution": {"maxParallel": 4}}
  ```
- Hook points, in order for one turn: `beforeAgent` (`PRE_AGENT`), then per model call `beforeLlmCall` /
  `afterLlmCall` (`PRE_LLM` / `POST_LLM`), per tool call `beforeToolCall` / `afterToolCall` (`PRE_TOOL` / `POST_TOOL`),
  then `afterAgent` (`POST_AGENT`). A `task` sub-agent fires the same agent and model-call hooks nested inside the
  tool call. `ctx.agent()` is `main` or the sub-agent type; `ctx.text()` is the prompt (`PRE_AGENT`), the model text
  (`POST_LLM`) or the answer (`POST_AGENT`); `ctx.isError()` marks a failed model call or agent. `USER_PROMPT`, `beforeAgent`,
  `beforeLlmCall` and `beforeToolCall` can deny: a denied main agent does not start, a denied sub-agent fails its `task`
  entry, a denied model call ends the run with an error (the chat stays consistent). `beforeToolCall` can also rewrite
  the input. The `after*` hooks only observe, except that `afterToolCall` can add context to the result.
- A hook that throws is logged and ignored. The first denial wins; `PRE_TOOL` can rewrite the input.
- Sub-agent types (`task` tool, `subagent_type`): `general`, `explore`, `plan` plus the configured ones. A sub-agent
  inherits the caller's mode, permission rules, hooks and chat grants; types marked `readOnly` (`explore`, `plan`) stay
  read-only (always PLAN, read-only tools). Approvals a sub-agent needs go to the user, one dialog at a time per chat,
  labeled with the agent (`[general #2] shell`); answering `a` allows the tool for the rest of the chat. Writable
  types run one after the other in `edit`/`auto`. Custom types are writable unless `"readOnly": true` is set (types
  that used to be implicitly read-only need it to keep the old behavior).
- Slash menus: `/tools`, `/mcp`, `/apis`, `/skills` list numbered items; type a number, then an action number; Esc
  (or `/back`, `b`) goes back. `check` tests that an MCP server or API answers. Toggles are in memory only.
  Scripted forms: `/mcp enable|disable|check <name>`, `/tools enable|disable <tool|group>`.

## Notes

- Tools are sorted by group, then name, once, in the registry. Injection order does not matter, which keeps
  the prompt-cache prefix stable. Duplicate tool names are rejected at `build()`.
- The tool set of a chat is fixed on its first message and only grows through `request_tool_group`.
- The default sandbox, `LocalDirSandboxExecutor`, has **no isolation** (a startup warning is logged).
  Pass `DockerSandboxExecutor` or your own `SandboxExecutor` for untrusted input.
- Ports to implement: `ChatSessionStore`, `SubAgentRunLog`, `ToolApprovalPolicy`, `HarnessMetrics`, `SandboxExecutor`.
- `HarnessTestSupport` (scripted fake model, rig) ships in the `test-jar`.
- `mvn -Prelease package` additionally builds the javadoc jar; the sources jar is always built.

## Modes, working directory and scratch directory

| Mode | working dir | scratch dir | prompts |
|---|---|---|---|
| `default` | per tool | read-write | every tool that is not `READ_ONLY` asks (initial mode) |
| `plan` | read-only | read-write | file tools write only under scratch; the shell runs from scratch and asks as in default |
| `edit` | read-write | read-write | file edits run without asking, the shell asks |
| `auto` | read-write | read-write | nothing asks |

- `/mode` opens a numbered menu (plan, edit, auto); `/mode plan`, `/default`, `/plan`, `/edit`, `/auto` set it directly.
  `/where` prints the working directory and the scratch directory of the chat.
  The mode is the last text of the system prompt, so switching it keeps the cached tool block and the text before it
  (see [CACHES](../docs/CACHES.md)). After a
  plan-mode answer a `plan_ready` event suggests `/edit`.
- Scratch dir: `<sandbox.tempDir>/<chatId>/<agentId>` (default `${java.io.tmpdir}/agent-harness`); the main agent and
  each sub-agent get their own. Sub-agents inherit the caller's mode; types marked `readOnly` (explore, plan) stay read-only.
- Front ends ([jllm-cli](../jllm-cli/README.md), [jllm-web](../jllm-web/README.md)): the working directory is the
  project directory and the file tools are confined to it. There is no isolation: in plan mode shell commands run from scratch but cannot be stopped from
  writing to the working directory. The Docker executor mounts the working directory read-only in plan mode.

## Context window and compaction

`context.maxTokens` (or `--context-tokens`, which wins) is the model's window. Compaction starts when the measured prompt
reaches `maxTokens - buffer` (default buffer: 15% of the window, at least 16k once the window is 32k or more). A request
rejected as too long is compacted and retried once. `context.auto: false` leaves compaction to `/compact`.
The summary follows a fixed template (objective, requirements, decisions, work state, next move, relevant files,
important context); later compactions merge the previous summary with the newer history. The newest `keepTokens` stay
verbatim. The summary request is the agent's own request (same system blocks, tools and history) plus the summary
instruction, so the provider's prompt cache is hit; with a separate fast model it is a plain text request. A request the
provider refuses as too long is retried shorter: clipped outputs, no outputs, user text only, and the oldest history cut
to 85/70/50/35% of the first refused size. If even that is refused there is nothing to compact yet and nothing changes.
Sub-agents compact on their own.

### Choosing the context strategy

Three strategies, in separate packages behind `context.ContextStrategy`:

| Strategy | Package | Log | How space is freed |
|---|---|---|---|
| `compaction` (default) | `context.compaction` | rewritten and saved | clear old tool results, summarize, then a rewrite the user approves |
| `mnemonic` | `context.mnemonic` | never rewritten | sticky receipts, segment handoffs, `history_recall` |
| `scope` | `context.scope` | rewritten and saved | whole-request budget: offload large tool results, clear, summarize; first message and todos pinned |

Select it with settings `"context": {"strategy": "mnemonic"}`, the CLI flag `--context-strategy mnemonic` (wins over
the settings), `config.withContextStrategy(ContextStrategyKind.MNEMONIC)`, or a custom implementation with
`AgentHarness.builder().contextStrategy(deps -> ...)` (wins over everything). `/status` shows the active one.

The mnemonic strategy (after mnemonic-proxy) renders each request from the verbatim log and decisions stored per chat
and agent (`mnemonicStore(...)`; the CLI keeps them in `.kendhar/history/<chat>/<agent>/mnemonic.json`). The same log
always gives the same bytes, so the prompt cache stays valid up to the first newly masked message.
- Over `maskTrigger` (default 60% of the ceiling) a batch replaces the oldest tool outputs, large tool inputs and
  reasoning of earlier user turns with one-line receipts such as
  `[masked: read_file(path=src/A.java) -> ~4200 tokens, recall id=r3f2a91c0d4e1]`, until the prompt is down to
  `maskTarget` (30%). It is committed only if it frees `minBatchTokens` (20%), never touches the newest
  `keepRecentTokens` or the last `minAgeTurns` assistant turns, and never masks before the previous batch's first mask.
  Decisions are never undone.
- When the response would no longer fit under the ceiling, a segment switch asks the model for handoff notes (its own
  request plus an instruction, so the cache is hit) and the request becomes: first user message, notes, recall index,
  verbatim tail (at most `tailMaxTokens`, 25% of the window).
- `history_recall` (always offered, group `Memory`): `{"id"}` returns the original text (paged with `offset`),
  `{"query"}` searches what is masked or before the segment start (BM25 over raw and identifier-split words, exact
  substring for paths; variants separated by ` | `), `{"file"}` lists the calls naming a file, `{"scope": runId}`
  searches a sub-agent run. `task` results carry the `runId`.
- A `PRE_TOOL` hook refuses `write_file`, `edit_file` and `bash` inputs that contain a receipt.
- `/compact` does nothing. A request rejected as too long forces a batch, else a segment switch, else masks the newest
  tool results.

```json
"context": {"maxTokens": 100000, "strategy": "mnemonic",
            "mnemonic": {"maskTrigger": null, "maskTarget": null, "minBatchTokens": null, "minMaskTokens": 300,
                         "keepRecentTokens": null, "minAgeTurns": 2, "tailMaxTokens": null, "notesMaxTokens": 2000,
                         "indexMaxTokens": 1500, "recallMaxChars": 12000, "dropOldThinking": true}}
```

### The scope strategy

`"context": {"strategy": "scope"}` (CLI `--context-strategy scope`). The request is measured as a whole: window minus
the answer reserve (`min(window/8, reserveCap, maxTokens)`), minus `safetyPercent` of the window, minus the system
prompt and tool schemas. While it is over budget, in this order: tool results longer than `offloadChars` are written
to `<scratch>/offload/<tool_use_id>.txt` and replaced by the path plus `previewChars` of head and tail (the agent reads
the file with `read_file`); old tool results are cleared; older turns are summarized. A request the provider still
refuses gets one forced pass (`overflowOffloadChars`, one turn kept). The first user message and the todo list are
never compacted; see [CACHES §5](../docs/CACHES.md#5-scope-strategy).

The strategy offers `todo_write` (the complete list, at most one `in_progress`) and `todo_read`; both are `READ_ONLY`.
The list is kept per chat and agent in the `StateStore`, added to the last user message of every request (never to the
stored log) and sent to front ends as the `todos` event.

```json
"context": {"strategy": "scope",
            "scope": {"offloadChars": 20000, "overflowOffloadChars": 2000, "previewChars": 1500,
                      "safetyPercent": 2, "reserveCap": 4096}}
```

## Fast model and the auto-mode classifier

`"fastModel": {"name": "ollama", "baseUrl": "...", "apiKey": "", "model": "...", ...}` in the settings file (or
`AgentHarness.builder().fastModelClient(..)`) adds a small model used for compaction summaries, tool-group selection and
the classifier. In `auto` mode, with a fast model configured, each tool call that is not read-only is classified as
safe or unsafe from the tool name, its input, the working directory and the user's request only (never tool output).
Safe runs; unsafe, unsure, low confidence (under 0.8), timeout or error asks the user. Without a fast model `auto` asks
nothing. A `classifier` event reports each verdict.

## Credits

- [mnemonic-proxy](https://github.com/lastreload/mnemonic-proxy): the alternative `mnemonic` context strategy.
- [building-a-coding-agent-from-scratch-course](https://github.com/decodingai-magazine/building-a-coding-agent-from-scratch-course):
  the structure of the harness.
- [opencode](https://github.com/sst/opencode): the inspiration.
