# JLLM — An Embeddable Java Agent Harness

**A frameworkless agent runtime you embed directly in your Java application.**

Add one library, give it a model, and your application gets a tool-using AI agent that can call **your own Java code**, **MCP servers**, and **REST APIs**, all under a permission system you control.

Built for Java 21, with a Maven multi-module architecture.

JLLM includes two ready-made front ends built on the same library:

- **jllm-cli** — terminal-based agent chat.
- **jllm-web** — browser-based agent chat.

They're not separate products or mandatory dependencies. They're working examples of what you can build by embedding the JLLM runtime in your own application.

**Bring the model. Bring your Java code. JLLM connects them.**

| Module | What | Doc |
|---|---|---|
| [jllm-harness](jllm-harness) | the library: framework-free (no Spring, JPA or Micrometer), Jackson, JDK `HttpClient`, jsoup | [README](jllm-harness/README.md) |
| [jllm-cli](jllm-cli) | terminal front end, one shaded runnable jar | [README](jllm-cli/README.md) |
| [jllm-web](jllm-web) | Spring Boot REST + SSE API with a no-build Vue 3 chat UI | [README](jllm-web/README.md) |

Also: [HISTORY](docs/HISTORY.md) (how the harness evolved) and [HARNESSES-COMPARE](docs/HARNESSES-COMPARE.md) (feature matrix against mini-swe-agent, Pi, OpenCode, Qwen Code, Hermes Agent).

What is still missing (more in [FUTURE](docs/FUTURE.md)):

* OAuth & token support on MCP/API calls (static headers only)
* Authentication & authorization on the web UI: `jllm-web` is a reference front end meant for localhost or a trusted
  network. Do not expose it publicly without a reverse proxy that handles authentication.

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

### Three ways to give the agent tools

| Source | You write | The agent sees |
|---|---|---|
| **Your Java code** | a `HarnessTool` (or `SimpleTool` with a lambda): name, description, JSON schema, group, kind | `db_query`, `create_ticket`, ... whatever you register |
| **MCP servers** | a `mcpServers` entry in the settings file: URL and headers | `mcp__<server>__<tool>`, discovered at startup from the server's `tools/list` |
| **REST APIs** | a `restApis` entry: OpenAPI URL, base URL, `allowedPaths` | `<api>__<operationId>`, one tool per allowed operation |

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
  down does not stop the harness. No OAuth.
- **REST**: the OpenAPI 3 spec is parsed and each operation matching `allowedPaths` becomes a tool whose input schema
  comes from the spec. Nothing outside the allowlist is reachable. GET/HEAD are read-only, other methods need approval.
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

Details: [harness README](jllm-harness/README.md), [EXTENDING](docs/EXTENDING.md) (custom tools, hooks, policies,
providers, stores), [MCP and REST](docs/MCP.md), [TOOLS](docs/TOOLS.md).

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
[harness README](jllm-harness/README.md#embedding).

## Documentation

| Document | Content |
|---|---|
| [jllm-harness](jllm-harness/README.md) | embedding, settings file, hooks, plugins, modes, context strategies, fast model |
| [jllm-cli](jllm-cli/README.md) | flags, directories, interactive session, slash commands, local models |
| [jllm-pdf](docs/TOOLS.md#document-readers) / [jllm-docs](docs/TOOLS.md#document-readers) | optional `read_file` readers for pdf and docx (plugins, depend only on the harness) |
| [jllm-web](jllm-web/README.md) | `jllm.*` properties, REST and SSE API, frontend structure |
| [USAGE](docs/USAGE.md) | harness build, permission modes, settings file, embedding, events |
| [TOOLS](docs/TOOLS.md) | built-in tools, kinds, path jail, per-chat tool set, tool-call pipeline |
| [SANDBOX](docs/SANDBOX.md) | the four sandbox executors: directories, isolation, Docker settings |
| [MCP](docs/MCP.md) | MCP servers and OpenAPI REST APIs as tools |
| [SKILLS](docs/SKILLS.md) | skills, instruction files, agent prompts |
| [EXTENDING](docs/EXTENDING.md) | custom tools, hooks, plugins, policies, providers, agent types, context strategies, stores, sandbox, tests |
| [CACHES](docs/CACHES.md) | prompt-cache layout, compaction, mnemonic and scope algorithms |
| [INTERNALS](docs/INTERNALS.md) | architecture, run lifecycle, loop, approvals, sub-agents, concurrency, persistence |
| [FLOW](docs/FLOW.md) | step-by-step diagrams of every pipeline |

## Credits

- [mnemonic-proxy](https://github.com/lastreload/mnemonic-proxy): the alternative `mnemonic` context strategy.
- [building-a-coding-agent-from-scratch-course](https://github.com/decodingai-magazine/building-a-coding-agent-from-scratch-course):
  the structure of the harness.
- [opencode](https://github.com/sst/opencode): the inspiration.

## License

[Apache License 2.0](LICENSE). Redistributions and derivative works must keep the copyright notice and the
[NOTICE](NOTICE) file, so the original attribution is preserved.
