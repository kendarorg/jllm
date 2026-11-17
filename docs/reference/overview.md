# JLLM overview (long form)

The full feature tour that used to be the project README. The short pitch is in the [README](../../README.md); the docs map is [index](../index.md).

**A frameworkless agent runtime you embed directly in your Java application.**

Add one library, give it a model, and your application gets a tool-using AI agent that can call **your own Java code**, **MCP servers**, and **REST APIs**, all under a permission system you control.

Includes a **JavaScript sandbox** (`jllm-js`): the agent can run scripts with no host access, reaching your tools only through the secured tools.

Includes optional **deterministic flows** (`jllm-workflows`): business processes written as JSON maps of steps, where your application decides the path and the model only does bounded fuzzy work inside a step.

Built for Java 21, with a Maven multi-module architecture.

JLLM includes two ready-made front ends built on the same library:

- **jllm-cli** — terminal-based agent chat.
- **jllm-web** — browser-based agent chat.

They're not separate products or mandatory dependencies. They're working examples of what you can build by embedding the JLLM runtime in your own application.

**Bring the model. Bring your Java code. JLLM connects them.**

| Module | What | Doc |
|---|---|---|
| [jllm-harness](../../core/jllm-harness) | the library: framework-free (no Spring, JPA or Micrometer), Jackson, JDK `HttpClient`, jsoup | [README](../../core/jllm-harness/README.md) |
| [jllm-cli](../../jllm-cli) | terminal front end, one shaded runnable jar | [README](../../jllm-cli/README.md) |
| [jllm-web-commons](../../jllm-web-commons) | REST + SSE chat and file API over the harness, independent of sign-in | [README](../../jllm-web-commons/README.md) |
| [jllm-web](../../jllm-web) | Spring Boot application: startup, sample sign-in and permissions, no-build Vue 3 chat UI, flow editor and runs screens | [README](../../jllm-web/README.md) |
| [jllm-workflows](../../plugins/jllm-workflows) / [jllm-workflow-web](../../plugins/jllm-workflow-web) | optional plugins: the flow engine with chat commands and command line, and its web endpoints | [WORKFLOW](../guides/workflow.md) |

Also: [HISTORY](history.md) (how the harness evolved) and [HARNESSES-COMPARE](../comparisons/harnesses-compare.md) (feature matrix against mini-swe-agent, Pi, OpenCode, Qwen Code, Hermes Agent).

What is still missing (more in [FUTURE](../enterprise/roadmap-and-limits.md)):

* Provider (model) OAuth logins: the `OAuthLogin` SPI exists, no provider login is shipped
* Flows: question routing by `who` is a label only, not enforced ([WORKFLOW](../guides/workflow.md#12-runs-history-and-resume))
* Authentication & authorization on the web UI: `jllm-web` is a reference front end meant for localhost or a trusted
  network. Sign-in is password or one OpenID Connect provider ([setup](../../jllm-web/README.md#sign-in-with-openid-connect)); no
  other providers, no IdP role mapping. Do not expose it publicly without a reverse proxy or TLS.

## The idea

You have an application with data, services and rules. You want a model to work with them, not just to chat. The
harness runs the agent loop (model call, tool calls, results, repeat) inside your process and lets you decide what the
agent can touch and what needs a human's approval.

```java
AgentHarness h = AgentHarness.builder()
    .modelClient(ModelClients.anthropic(AnthropicSettings.defaults(System.getenv("ANTHROPIC_API_KEY")), mapper))
    .tools(List.of(myTool))                      // your Java code as tools
    .settings(Path.of("harness.json"))           // MCP servers and REST APIs
    .approvalPolicy(ToolApprovalPolicy.byKind()) // who may do what without asking
    .systemPrompt("You work on the billing system. Never refund over 100 EUR without asking.")
    .build();

String chatId = h.sessions().create(null).chatId();
h.subscribe(chatId, 0, ev -> push(ev));          // text, tool_call, approval_request, ...
h.start(chatId, "Why was invoice 4711 charged twice?");
```

`start` returns at once; the run happens on a virtual thread and reports through events, so any front end (a web
socket, a desktop UI, a message queue) can follow it, answer approvals and cancel.

**Let it script. Keep it contained.** The optional [jllm-js](../guides/js.md) plugin lets the agent write and run
JavaScript on the fly, in a sandbox with no way out except the tools you allow. Real automation, no shell, no
surprises.

### Three ways to give the agent tools

| Source | You write | The agent sees |
|---|---|---|
| **Your Java code** | a `HarnessTool` (or `SimpleTool` with a lambda): name, description, JSON schema, group, kind | `db_query`, `create_ticket`, ... whatever you register |
| **MCP servers** | a `mcpServers` entry in the settings file: URL and headers | `mcp__<server>__<tool>`, discovered at startup from the server's `tools/list` |
| **REST APIs** | a `restApis` entry: OpenAPI URL, base URL, `allowedPaths` / `denyPaths` | `<api>__<operationId>`, one tool per visible operation |

Your own tool, in a few lines:

```java
HarnessTool sql = new SimpleTool("db_query",
    "Run a read-only SQL query against the reporting database. Returns CSV, at most 500 rows.",
    SimpleTool.schema("sql", "SELECT statement", "limit:integer?", "Row limit (default 100)"),
    "Db", ToolKind.READ_ONLY,
    (ctx, in) -> reporting.query(in.path("sql").asText(), in.path("limit").asInt(100)));
```

MCP servers and REST APIs need no code, only configuration:

```json
{
  "mcpServers": [{"name": "docs", "url": "https://h/mcp",
                  "headers": {"Authorization": "Bearer ${DOCS_TOKEN}"}, "readOnly": true}],
  "restApis":   [{"name": "pets", "openApiUrl": "https://h/openapi.yaml", "baseUrl": "https://h/api",
                  "allowedPaths": ["GET /pets", "/pets/{id}"]}]
}
```

- **MCP**: streamable-HTTP servers or stdio child processes (`command`/`args`/`env`), static headers (`${ENV_VAR}` expanded), tools listed at startup. A server that is
  down does not stop the harness. OAuth with `"oauth": true` (`/login mcp:<name>`, see [MCP](../guides/mcp.md#oauth)),
  shared or with each user's own token.
- **REST**: the OpenAPI 3 spec is parsed and each operation that passes `allowedPaths` and `denyPaths` becomes a tool whose
  input schema comes from the spec. Hidden operations are not reachable. GET/HEAD are read-only, other methods need approval.
  OAuth2 per API (`oauth`: authorization code, device code or client credentials; endpoints from the issuer or the
  spec): every call carries the token of the user behind the chat, and a missing login is a tool result asking the
  user to connect, never a changed tool list ([MCP](../guides/mcp.md#oauth-1)).
- **Token storage**: one `TokenStore` for every login: a file (encrypted with `JLLM_TOKEN_KEY` when there are users),
  the OS secret store (macOS Keychain, Windows DPAPI, Linux Secret Service) for a single user, or your own
  ([MCP](../guides/mcp.md#6-token-storage)).
- **Visibility lists**: MCP `allowList`/`denyList` (tool names) and REST `allowedPaths`/`denyPaths` (`METHOD /path`).
  Absent allow = all, `[]` = none, `["*"]` = all; deny hides matches. Hidden tools never reach the model. Separate from the
  `permissions` rules, which decide approval of visible tools.
- **Built-in tools** are also there: files (confined to the working directory), shell, web search and fetch, sub-agents
  (`task`), skills.
- All sources can be switched on and off at runtime (`/tools`, `/mcp`, `/apis` in any front end, or the `tools()` and
  `sources()` facade methods). Tools are sorted once and fixed per chat, so the provider's prompt cache stays valid.

### Control

- **Tool kind** (`READ_ONLY`, `FILE_EDIT`, `OTHER`) drives approval. Read-only tools never ask. Mark your own tools
  honestly: the kind is a promise, and read-only sub-agents only get `READ_ONLY` tools.
- **Modes** `default`, `plan`, `edit`, `auto` set what runs without asking; deny rules apply in every mode.
- **Approvals** arrive as `approval_request` events with a preview. Your UI answers with `resolveApproval(...)`, can
  grant a tool for the rest of the chat, and can edit a proposed context rewrite.
- **Hooks** around agents, model calls and tool calls can observe, deny or rewrite a tool input (audit, policy,
  redaction). **Plugins** bundle tools, hooks and slash commands.
- **Ports** for your infrastructure: `ChatSessionStore`, `SubAgentRunLog`, `ToolApprovalPolicy`, `HarnessMetrics`,
  `SandboxExecutor`. The defaults are in memory.
- **Isolation**: the default sandbox has none. For untrusted input set `sandbox.backend` to `docker` (CLI
  `--sandbox docker`, optionally `--docker-mode daemon`) or use your own `SandboxExecutor`.

Details: [harness README](../../core/jllm-harness/README.md), [EXTENDING](../guides/extending.md) (custom tools, hooks, policies,
providers, stores), [MCP and REST](../guides/mcp.md), [TOOLS](../guides/tools.md).

## Flows

Optional [`jllm-workflows`](../guides/workflow.md) plugin. A flow is a JSON file of steps; the engine, not the model, decides
what runs next. "Deterministic" means the orchestration: the same flow with the same inputs and decisions walks the same
path. The model's answers are not deterministic, so every model call sits in a bounded step.

- **Steps**: `task` (a prompt with slots and an enforced answer shape, run by a harness agent), `action` (a Java action,
  no model), `decide` (a turning point), `ask` (pause and ask a person), `call` (start another flow) and `finish`.
- **Turning points** pick one declared outcome, decided by a Java chooser, by the model from a closed list, or by a
  person. The model can never invent a path. Decisions are recorded and reused on resume.
- **Run, wait, resume**: a run that needs an answer goes to `waiting`, survives restarts and resumes without repeating
  finished steps. Journaled model replies and decisions are not requested again.
- **Questions**: `ask` steps (text, single or multi select, pattern, timeout), `clarify` on tasks (the reply is checked
  for a question and the run pauses to ask its owner), and customer requests sent to a running flow.
- **Nested flows**: `call` runs a child flow as a run of its own with explicit input and a declared result; depth and
  loops are checked.
- **Task approvals**: a task whose agent has tools runs in the real agent loop; a tool that needs approval asks the run's
  owner (allow or refuse). The role and the user's disabled tools can only narrow the task's tools.
- **Ownership**: personal flows per user, global flows defined by an admin and shareable with roles; global flows run as
  the user who starts them. Java actions can be restricted to roles, checked when a flow is saved.
- **Unattended** flows (`"unattended": true`) never ask; a flow that could ask is refused at save time.
- **Error handler**, retries and limits per flow; every flow is validated when saved.

```
/flow-start refund {"order": "A-1001", "message": "I want my money back"}
run 7c1e...: WAITING
Approve the refund of 120? [single, asks operator]
/flow-resume 7c1e... approve
```

Run them from any front end with `/flows`, `/flow-start`, `/flow-resume`, `/flow-request` and `/flow-cancel`; from a
command line with `WorkflowCli` (`list`, `start`, `resume`, `history`, `cancel`; no chat needed); or from the `jllm-web`
Flows screen (graph designer with server-side validation, personal and global flows) and Runs screen (history, cancel,
open questions, a badge with the waiting count). Enable both plugins under `plugins` in the settings file. A sample
flow, `develop-app` (plan, implement, review loop, test loop), is in [runConfig/flows](../../runConfig/flows) and seeded as a
global flow by `jllm.flows-seed-dir` ([jllm-web](../../jllm-web/README.md#sample-global-flows)).

Not yet: the `who` label of a question (operator, customer) is shown but not enforced; the run's owner answers every
question. Details: [WORKFLOW](../guides/workflow.md).

## Models

Anthropic (prompt caching, thinking, citations) and any OpenAI-compatible endpoint (OpenAI, Qwen, llama.cpp, vLLM,
Ollama), selectable by name from a settings `providers` list and switchable with `/model`. An optional fast model handles summaries, tool-group selection and the `auto`-mode classifier. A stricter
prompt for small local models is available. If a provider fails, the next one in the `order` chain is tried. Three
context strategies for long chats: `compaction` (default), `mnemonic` (never rewrites the log) and `scope` (whole-request
budget, large tool results offloaded to disk, pinned first message, a `todo_write` task list).

## Quick start

```sh
# terminal
mvn -pl jllm-cli -am package -DskipTests
java -jar jllm-cli/target/jllm-cli-1.0.0.jar --provider anthropic --mode edit

# browser, http://localhost:8080
mvn -pl jllm-web -am package -DskipTests
java -jar jllm-web/target/jllm-web-1.0.0.jar --provider anthropic
```

Set `ANTHROPIC_API_KEY` (or `OPENAI_API_KEY`, `GROK_API_KEY`, `QWEN_API_KEY`) first. Embedding the library is shown above and in the
[harness README](../../core/jllm-harness/README.md#embedding).

## Documentation

| Document | Content |
|---|---|
| [jllm-harness](../../core/jllm-harness/README.md) | embedding, settings file, hooks, plugins, modes, context strategies, fast model |
| [jllm-cli](../../jllm-cli/README.md) | flags, directories, interactive session, slash commands, local models |
| [jllm-pdf](../guides/tools.md#document-readers) / [jllm-docs](../guides/tools.md#document-readers) | optional `read_file` readers for pdf and docx (plugins: jar in the plugin directory, enabled under `plugins` in the settings) |
| [jllm-js](../guides/js.md) | optional `run_js` tool: JavaScript (GraalJS, regex supported, synchronous `tools.*` calls without `await`) in a sandbox whose only view of the world is the harness tools; `bash` stays available to the roles that list it (plugin: jar in the plugin directory, enabled under `plugins`) |
| [jllm-web](../../jllm-web/README.md) | `jllm.*` properties, REST and SSE API, frontend structure |
| [USAGE](usage.md) | harness build, permission modes, settings file, embedding, events |
| [TOOLS](../guides/tools.md) | built-in tools, kinds, path jail, per-chat tool set, tool-call pipeline |
| [SANDBOX](../guides/sandbox.md) | the four sandbox executors: directories, isolation, Docker settings |
| [MCP](../guides/mcp.md) | MCP servers and OpenAPI REST APIs as tools |
| [SKILLS](../guides/skills.md) | skills, instruction files, agent prompts |
| [EXTENDING](../guides/extending.md) | custom tools, hooks, plugins, policies, providers, agent types, context strategies, stores, sandbox, tests |
| [CACHES](caches.md) | prompt-cache layout, compaction, mnemonic and scope algorithms |
| [INTERNALS](internals.md) | architecture, run lifecycle, loop, approvals, sub-agents, concurrency, persistence |
| [WORKFLOW](../guides/workflow.md) | optional `jllm-workflows` plugin: deterministic flows (JSON steps, Java actions, three choosers, questions and `clarify`, nested flows, task approvals, personal and global flows with roles, unattended runs, error handler), chat commands, command line, web API and flow editor |
| [FLOW](flow.md) | step-by-step diagrams of every pipeline |

## Credits

- [mnemonic-proxy](https://github.com/lastreload/mnemonic-proxy): the alternative `mnemonic` context strategy.
- [building-a-coding-agent-from-scratch-course](https://github.com/decodingai-magazine/building-a-coding-agent-from-scratch-course):
  the structure of the harness.
- [opencode](https://github.com/sst/opencode): the inspiration.

## License

[Apache License 2.0](../../LICENSE). Redistributions and derivative works must keep the copyright notice and the
[NOTICE](../../NOTICE) file, so the original attribution is preserved.
