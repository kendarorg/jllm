# Why JLLM

JLLM is a Java 21 library that runs the agent loop inside your process and lets you decide what the agent can touch
and what needs a person's approval. Five properties, each with where to read more. Comparison with other options:
[JVM landscape](comparisons/jvm-landscape.md).

## 1. A framework-free core

The harness depends on Jackson, the JDK `HttpClient`, jsoup, json-schema-validator, swagger-parser (with its transitive libraries) and `slf4j-api` (full list: `mvn dependency:tree -pl core/jllm-harness`). No Spring, JPA or
Micrometer. It embeds in a plain Java service, a desktop application or a Spring Boot application
([recipes](getting-started/spring-boot.md)), and a small dependency list is easier to review
([compliance](enterprise/compliance-and-licensing.md)).

## 2. Governed tool calls

Your Java code, MCP servers and REST APIs become tools. Each call passes schema validation, hooks and a permission
gate: tool kinds, modes, `deny` rules that hold in every mode, visibility lists that hide tools from the model, and
approval events your UI answers. Per-host approval covers web access. See [security model](enterprise/security-model.md)
and [governance and audit](enterprise/governance-and-audit.md).

## 3. Isolation and sandboxed scripting

File tools are confined to a working directory. Shell commands can run in Docker, with a container per chat. The
optional `jllm-js` plugin lets the agent write JavaScript that has no host access and reaches the world only through
tools you allow. See [SANDBOX](guides/sandbox.md) and [JS](guides/js.md). The default `local` sandbox has no
isolation; choose Docker for untrusted input.

## 4. Deterministic flows

For business processes, a flow is a JSON map of steps: your application decides the path and the model only does
bounded work inside a step. Decisions are recorded, runs wait for answers, survive restarts and resume without
repeating finished steps. The model's answers stay non-deterministic; the orchestration does not. See
[WORKFLOW](guides/workflow.md).

## 5. Ports for your infrastructure, Apache-2.0

Stores for chats, sub-agent runs, state and tokens, metrics, the approval policy and the sandbox are interfaces with
in-memory or file defaults, so you can put them on your database and your observability stack
([observability](enterprise/observability.md), [deployment](enterprise/deployment.md)). The license is
[Apache 2.0](../LICENSE) with a [NOTICE](../NOTICE) file to keep.

## When not to choose JLLM

- You need **many native provider clients**. JLLM has few; combine it with LangChain4j or Spring AI.
- RAG and vector stores are not built in, but they are easy to add: expose your retrieval as a
  [tool](guides/tools.md) (see [extending](guides/extending.md#1-tools)).
- You want a **Spring Boot starter** or a Quarkus extension. Only the web API starter (`jllm-web-spring-starter`) exists; harness beans are documented as [recipes](getting-started/spring-boot.md), and there is no Quarkus extension.
- You need **identity-provider role mapping** (groups or claims to roles), multiple OIDC providers, SAML or SCIM.
  Not implemented yet ([limits](enterprise/roadmap-and-limits.md)).
- You need a **vendor-backed support contract, certifications or an external security audit**. There is none.
- You need **horizontal scale out of the box**. The library defaults are memory and files, a run lives in one process,
  and the SQL store of `jllm-web` (SQLite, MySQL, PostgreSQL; the last two experimental) is not a cluster setup:
  see [limits](enterprise/roadmap-and-limits.md) and [SQL starter](../web/jllm-sql-spring-starter/README.md).
- Provider OAuth logins (subscription accounts instead of API keys) are not shipped.

Start with [Embed in Java](getting-started/embed-in-java.md) or [try the CLI and web app](getting-started/cli-and-web.md).
