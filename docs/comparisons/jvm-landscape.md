# JVM landscape

Where JLLM sits among the Java and JVM options for LLM agents. Descriptions of other projects are brief and taken from
their own positioning; check their documentation for current features. For a feature matrix against other coding-agent
harnesses see [harnesses-compare](harnesses-compare.md).

JLLM is an embeddable agent **runtime**: the loop (model call, tool calls, results, repeat) plus control over what the
agent may touch. It is not a RAG toolkit, a vector-store abstraction or an inference engine.

## Name collision

Two unrelated projects are also called `jllm` / `jitLLM`: beehive-lab's JVM inference and serving engine and
cmc-labo's lightweight GGUF runner. This project is `org.kendar.llm` (Maven coordinates, repository `jllm`). When
searching, use the group id.

## 1. Embeddable JVM agent and LLM libraries

| Project | What it is | Overlap with JLLM | How they combine |
|---|---|---|---|
| LangChain4j | Widely used Java LLM library: tools, MCP, RAG, memory, agents, many providers; Quarkus and Spring integrations | agent loop, tools, MCP | use it for retrieval and provider breadth; wrap its tool methods as `HarnessTool` when the agent must run under JLLM's approvals |
| Spring AI | LLM support for Spring Boot: tool calling, MCP client and server, advisors, vector stores | agent loop, tools, MCP | natural choice if you are all-in on Spring and want its abstractions; run JLLM beside it as plain beans ([Spring Boot](../getting-started/spring-boot.md)) |
| Embabel | JVM agent framework on Spring AI with goal-oriented planning | agent orchestration | different planning model; JLLM's [flows](../guides/workflow.md) are explicit JSON step maps, not planned goals |
| Google ADK for Java | Google's Agent Development Kit, Gemini first, other models supported | agent loop | if you are built around Gemini and Google's stack, ADK is the closer fit |
| Koog (JetBrains) | Kotlin-first agent framework on the JVM | agent loop | Kotlin-first projects may prefer it; JLLM is plain Java 21 |
| Semantic Kernel for Java | Microsoft's orchestration SDK; the Java version lags .NET and Python | orchestration | not a natural pair; choose by your platform |
| Anthropic / OpenAI Java SDKs | official minimal clients | the model call only | use them if you want to write the loop yourself; JLLM is that loop, plus permissions, sandbox and stores |

### Where JLLM is not a replacement

- **RAG, embeddings and vector stores**: JLLM has none. Use LangChain4j or Spring AI for retrieval and expose it to the
  agent as a tool or an MCP server.
- **Provider breadth and framework integration**: JLLM supports Anthropic and any OpenAI-compatible endpoint. It does
  not have native clients for every provider and no Quarkus or Spring starter.
- **Gemini-first or Google-managed agents**: use Google ADK.
- **Ready-made planners and multi-agent topologies**: JLLM has sub-agents and flows, not a goal planner.

### Where it is a different choice

JLLM's emphasis is control: tool kinds and approvals, deny rules, a path jail, Docker sandboxes, sandboxed JavaScript,
per-user credentials and deterministic flows ([why JLLM](../why-jllm.md)), with a core that has no Spring, JPA or
Micrometer dependency.

## 2. Terminal coding-agent front ends

Claude Code, OpenCode, Aider and Goose are standalone tools, not libraries. [`jllm-cli`](../../jllm-cli/README.md) is
a comparable terminal front end, but it is a working example: the product is the library you embed. If your need is a
developer's coding assistant and nothing embedded, those tools or `jllm-cli` all fit.

## 3. Local inference

| Project | What it is | Relation |
|---|---|---|
| beehive-lab jllm / jitLLM | JVM-native inference and serving engine with an official LangChain4j provider and an OpenAI-compatible server | backend |
| llama3.java, Jlama | pure-Java inference engines | backend, if exposed over an OpenAI-compatible endpoint |
| cmc-labo jllm | lightweight Java runner for local GGUF models, similar to Ollama | backend |

These are complementary, not competitors. JLLM talks to any OpenAI-compatible endpoint (llama.cpp, vLLM, Ollama and
others) through a base URL ([embedding](../getting-started/embed-in-java.md)), so a local engine that serves that API
can be the model behind the agent. Small local models may need the stricter `smallModel` prompt.
