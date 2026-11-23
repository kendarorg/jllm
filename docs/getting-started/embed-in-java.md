# Embed JLLM in Java

The shortest path from nothing to an agent that calls your code. For a Spring Boot application see
[Spring Boot](spring-boot.md).

## 1. Dependency

Java 21, Maven coordinates `org.kendar.llm:jllm-harness`. Build from source with
`mvn -pl core/jllm-harness -am package` or take the released artifact ([PUBLISHING](../reference/publishing.md)).

## 2. Build a harness

```java
AgentHarness h = new AgentHarnessBuilder()
    .modelClient(ModelClients.anthropic(AnthropicSettings.defaults(System.getenv("ANTHROPIC_API_KEY")), mapper))
    .tools(List.of(myTool))                      // your Java code as tools
    .settings(Path.of("harness.json"))           // MCP servers and REST APIs
    .approvalPolicy(ToolApprovalPolicy.byKind()) // who may do what without asking
    .systemPrompt("You work on the billing system. Never refund over 100 EUR without asking.")
    .build();
```

Other providers: `ModelClients.from(settings, mapper)` for any OpenAI-compatible endpoint, and
`ModelClients.ollama("llama3.1", mapper)` for a local model.

## 3. Run a chat

```java
String chatId = h.sessions().create(null).chatId();
h.subscribe(chatId, 0, ev -> push(ev));          // text, tool_call, approval_request, ...
h.start(chatId, "Why was invoice 4711 charged twice?");
```

`start` returns at once; the run is on a virtual thread and reports through events. Any front end (a web socket, a
desktop UI, a queue) can follow it, answer approvals and cancel.

## 4. Give it tools

| Source | You write |
|---|---|
| Your Java code | a `HarnessTool` or `SimpleTool` with a lambda |
| MCP servers | a `mcpServers` entry in the settings file |
| REST APIs | a `restApis` entry: an OpenAPI URL and allowed paths |

```java
HarnessTool sql = new SimpleTool("db_query",
    "Run a read-only SQL query against the reporting database. Returns CSV, at most 500 rows.",
    SimpleTool.schema("sql", "SELECT statement", "limit:integer?", "Row limit (default 100)"),
    "Db", ToolKind.READ_ONLY,
    (ctx, in) -> reporting.query(in.path("sql").asText(), in.path("limit").asInt(100)));
```

Details: [TOOLS](../guides/tools.md), [MCP and REST](../guides/mcp.md).

## 5. Decide what needs a human

Mark each tool `READ_ONLY`, `FILE_EDIT` or `OTHER`. `byKind()` asks for everything that is not read-only. Your UI
receives an `approval_request` event with a preview and answers with `resolveApproval(...)`. Add `deny` rules in the
settings for what must never run.

## 6. Production ports

Defaults are in memory. Replace what you need: `ChatSessionStore`, `SubAgentRunLog`, `StateStore`, `TokenStore`,
`HarnessMetrics`, `SandboxExecutor`. See [EXTENDING](../guides/extending.md) and the
[harness README](../../core/jllm-harness/README.md#embedding).

## Next

[Security model](../enterprise/security-model.md) · [Deployment](../enterprise/deployment.md) ·
[Flows](../guides/workflow.md) · [USAGE reference](../reference/usage.md)
