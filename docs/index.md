# JLLM documentation

Pick the path that matches your role.

## Evaluating

| Page | Answers |
|---|---|
| [Why JLLM](why-jllm.md) | what it is for, and when not to choose it |
| [JVM landscape](comparisons/jvm-landscape.md) | how it relates to Spring AI, LangChain4j, ADK, Koog, local engines |
| [Harness comparison](comparisons/harnesses-compare.md), [sources](comparisons/harnesses-sources.md) | feature matrix against terminal agents, with the source of each cell |
| [Security model](enterprise/security-model.md) | threats, controls, what is not covered |
| [Compliance and licensing](enterprise/compliance-and-licensing.md) | Apache-2.0, dependencies, data handling |
| [Roadmap and limits](enterprise/roadmap-and-limits.md) | known gaps and future work |

## Integrating

| Page | Answers |
|---|---|
| [Embed in Java](getting-started/embed-in-java.md) | build a harness, run a chat, add tools |
| [Spring Boot](getting-started/spring-boot.md) | web API auto-configuration and harness beans (recipes) |
| [Try the CLI and web app](getting-started/cli-and-web.md) | the two reference front ends |
| [Tools](guides/tools.md), [MCP and REST](guides/mcp.md), [Skills](guides/skills.md) | what the agent can use |
| [JavaScript sandbox](guides/js.md) | scripting with no host access |
| [Flows](guides/workflow.md) | deterministic business processes |
| [Extending](guides/extending.md) | custom tools, hooks, plugins, policies, providers, stores |

## Operating

| Page | Answers |
|---|---|
| [Deployment](enterprise/deployment.md) | shapes, hardening checklist, state and scaling |
| [Securing a deployment](enterprise/securing-and-enterprise-setup.md) | hardening and enterprise setup, with a final checklist |
| [Identity and access](enterprise/identity-and-access.md) | sign-in, roles, whose credentials a call uses |
| [IdP role mapping](enterprise/idp-role-mapping.md) | roles from identity provider groups at sign-in (off by default) |
| [Governance and audit](enterprise/governance-and-audit.md) | approvals, hooks, events, ports |
| [Observability](enterprise/observability.md) | metrics, tracing, cost (Micrometer and OTel recipes) |
| [Sandboxes](guides/sandbox.md) | local, Docker, daemon, remote |

## Reference and contributing

[USAGE](reference/usage.md) · [INTERNALS](reference/internals.md) · [CACHES](reference/caches.md) ·
[FLOW](reference/flow.md) · [HISTORY](reference/history.md) · [PUBLISHING](reference/publishing.md)

Module READMEs: [jllm-harness](../core/jllm-harness/README.md) · [jllm-cli](../jllm-cli/README.md) ·
[jllm-web-commons](../web/jllm-web-commons/README.md) · [jllm-web-spring-starter](../web/jllm-web-spring-starter/README.md) · [jllm-sql-spring-starter](../web/jllm-sql-spring-starter/README.md) · [jllm-web](../jllm-web/README.md)
