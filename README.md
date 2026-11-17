# JLLM: a governed agent runtime for Java

**Embed a tool-using AI agent in your Java application. Your code, your MCP servers and your REST APIs become its
tools, under permissions, approvals and isolation that you control.**

One library, Java 21, no application framework in the core. The agent loop runs inside your process; your application
decides what the agent may touch and what needs a human.

> Not related to other projects named *jllm* (for example beehive-lab/jllm or cmc-labo/jllm, which are inference
> engines). JLLM talks to any OpenAI-compatible endpoint, so those can serve as its model backend.

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

## Why enterprises pick it

| | |
|---|---|
| **Framework-free core** | Jackson, the JDK `HttpClient`, jsoup, slf4j-api. No Spring, JPA or Micrometer in the library. Fits Spring Boot, Quarkus, Jakarta EE or plain Java ([Spring recipes](docs/getting-started/spring-boot.md)) |
| **Governed tool calls** | tool kinds, permission modes, regex deny rules, hidden tools, per-host network approval, hooks to deny, rewrite or audit ([security model](docs/enterprise/security-model.md)) |
| **Isolation** | path jail for file tools, Docker sandbox per command or per chat (also remote), and a JavaScript sandbox with no host access ([JS](docs/guides/js.md)) |
| **Deterministic flows** | business processes as JSON maps of steps: your engine decides the path, the model does bounded work inside a step ([flows](docs/guides/workflow.md)) |
| **Your infrastructure** | ports for chat stores, tokens, metrics, approvals and sandbox; OIDC sign-in and roles in the reference web app ([identity](docs/enterprise/identity-and-access.md), [governance](docs/enterprise/governance-and-audit.md)) |
| **Open license** | Apache-2.0, small dependency footprint ([compliance](docs/enterprise/compliance-and-licensing.md)) |

Model support: Anthropic and any OpenAI-compatible endpoint (OpenAI, Qwen, Grok, Gemini, llama.cpp, vLLM, Ollama),
with fallback chains and an optional fast model for summaries and classification.

## Three ways to give the agent tools

| Source | You write |
|---|---|
| Your Java code | a `HarnessTool` or `SimpleTool` with a lambda |
| MCP servers | a `mcpServers` entry: URL or command, headers, allow and deny lists |
| REST APIs | a `restApis` entry: OpenAPI URL, allowed and denied paths, OAuth per user |

Built-in tools (files in a path jail, shell, web, sub-agents, skills) are also there and can be switched on and off.
See [MCP and REST](docs/guides/mcp.md) and [TOOLS](docs/guides/tools.md).

## Where it fits

JLLM is an agent runtime, not a RAG toolkit or a model SDK. Use it beside Spring AI or LangChain4j when the agent must
act under approval and isolation. Comparison: [JVM landscape](docs/comparisons/jvm-landscape.md),
[terminal agents](docs/comparisons/harnesses-compare.md), [Why JLLM](docs/why-jllm.md).

## Modules

| Module | What | Doc |
|---|---|---|
| [jllm-harness](core/jllm-harness) | the library | [README](core/jllm-harness/README.md) |
| [jllm-cli](jllm-cli) | terminal front end, one shaded jar | [README](jllm-cli/README.md) |
| [jllm-web-commons](jllm-web-commons) | REST + SSE chat API as a Spring Boot auto-configuration | [README](jllm-web-commons/README.md) |
| [jllm-web](jllm-web) | reference web app: OIDC and password sign-in, roles, Vue chat UI, flow editor | [README](jllm-web/README.md) |
| [jllm-workflows](plugins/jllm-workflows), [jllm-workflow-web](plugins/jllm-workflow-web) | optional flow engine and its web endpoints | [WORKFLOW](docs/guides/workflow.md) |
| jllm-js, jllm-pdf, jllm-docs | optional plugins: JavaScript sandbox, document readers | [JS](docs/guides/js.md), [TOOLS](docs/guides/tools.md#document-readers) |

## Quick start

```sh
mvn -pl jllm-cli -am package
java -jar jllm-cli/target/jllm-cli-1.0.0.jar --provider anthropic --mode edit      # terminal

mvn -pl jllm-web -am package
java -jar jllm-web/target/jllm-web-1.0.0.jar --provider anthropic                 # http://localhost:8080
```

Set `ANTHROPIC_API_KEY` (or `OPENAI_API_KEY`, `GROK_API_KEY`, `QWEN_API_KEY`) first.

## Maven Central

All modules are available on Maven Central under the group `org.kendar.llm`.

## Tests and coverage

`mvn test` (or any later phase) runs the tests with JaCoCo and writes the aggregated report to
[docs/coverage](docs/coverage/index.html).

## Documentation

| For | Start here |
|---|---|
| **Evaluating** | [Why JLLM](docs/why-jllm.md), [security model](docs/enterprise/security-model.md), [compliance](docs/enterprise/compliance-and-licensing.md), [limits](docs/enterprise/roadmap-and-limits.md) |
| **Integrating** | [Embed in Java](docs/getting-started/embed-in-java.md), [Spring Boot](docs/getting-started/spring-boot.md), [Extending](docs/guides/extending.md) |
| **Operating** | [Deployment](docs/enterprise/deployment.md), [identity](docs/enterprise/identity-and-access.md), [observability](docs/enterprise/observability.md), [governance](docs/enterprise/governance-and-audit.md) |
| **Everything** | [Docs index](docs/index.md), [long overview](docs/reference/overview.md) |

## Known limits and what is next

Today: single OIDC provider and no IdP role mapping; one global API key per model provider; the web app is a
reference front end to run behind your proxy; no Spring Boot starter (recipes only); no external security audit.

Stores (chat history, users, roles, tokens) are files by default. To use your own storage, implement the store
interfaces and register them as beans or on the builder ([ports](docs/enterprise/governance-and-audit.md#ports-for-your-stores)).

Next: IdP role mapping (token claims to roles) and per-user model credentials (own API key or OAuth login per
provider, in development). Details: [roadmap and limits](docs/enterprise/roadmap-and-limits.md).

## Credits

- [mnemonic-proxy](https://github.com/lastreload/mnemonic-proxy): the alternative `mnemonic` context strategy.
- [building-a-coding-agent-from-scratch-course](https://github.com/decodingai-magazine/building-a-coding-agent-from-scratch-course):
  the structure of the harness.
- [opencode](https://github.com/sst/opencode): the inspiration.

## License

[Apache License 2.0](LICENSE). Redistributions and derivative works must keep the copyright notice and the
[NOTICE](NOTICE) file.
