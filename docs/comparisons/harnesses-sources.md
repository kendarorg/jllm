# Harness comparison: sources

Companion to [harnesses-compare](harnesses-compare.md). Every competitor cell of the matrix comes from the official documentation or repository of that project, read on 2026-11-23. A `no` often means "not documented"; `unknown` means the docs read did not settle it. The jllm column comes from this repository (`docs/`, `core/jllm-harness/README.md`, settings and code), not from external sources.

## mini-swe-agent

- Repository: https://github.com/SWE-agent/mini-swe-agent
- Version: v2.4.6 (2026-07-23)
- GitHub stars: about 10.0k
- Caveat: Many "no" values are "not documented" findings; sources are the README, docs site and models directory.

| Row | Value | Source | Accessed |
| --- | --- | --- | --- |
| Embeddable as a library | partial | https://mini-swe-agent.com/latest/advanced/cookbook | 2026-11-23 |
| Terminal UI | partial | https://mini-swe-agent.com/latest/usage/mini | 2026-11-23 |
| Web UI | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Messaging gateway (chat apps) | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Model streaming | unknown | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Image input | unknown | https://mini-swe-agent.com/latest/advanced/cookbook | 2026-11-23 |
| Broad provider support | yes | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Model registry | partial | https://github.com/SWE-agent/mini-swe-agentmodels dir | 2026-11-23 |
| Provider OAuth logins | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Prompt caching | unknown | https://github.com/SWE-agent/mini-swe-agentmodels dir | 2026-11-23 |
| Native tool calling | partial | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Tool argument validation | no | https://mini-swe-agent.com/latest/cookbook | 2026-11-23 |
| File tools | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Shell tool | yes | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Web search and fetch | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Browser automation | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| LSP feedback | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| MCP | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| MCP OAuth | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| OpenAPI tools | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Sub-agents | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Permission modes | partial | https://mini-swe-agent.com/latest/usage/mini | 2026-11-23 |
| Pattern permission rules | no | https://mini-swe-agent.com/latest/cookbook | 2026-11-23 |
| Auto-mode classifier | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Sandbox isolation | yes | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Context compaction | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Multiple context strategies | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Todo list | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Session persistence and resume | partial | https://mini-swe-agent.com/latest/usage/mini | 2026-11-23 |
| Session fork or tree | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Cross-session memory | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| File undo or checkpoints | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Hooks | no | https://mini-swe-agent.com/latest/cookbook | 2026-11-23 |
| Plugins or extensions | partial | https://mini-swe-agent.com/latest/cookbook | 2026-11-23 |
| Skills | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Instruction files | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Custom prompt templates | partial | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Custom commands | no | https://mini-swe-agent.com/latest/usage/mini | 2026-11-23 |
| Scheduled tasks | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Non-interactive or headless mode | yes | https://mini-swe-agent.com/latest/usage/mini | 2026-11-23 |
| Stuck or loop detection | partial | https://mini-swe-agent.com/latest/usage/mini | 2026-11-23 |
| Verify-on-stop | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| JSON or RPC protocol mode | no | https://mini-swe-agent.com/latest/ | 2026-11-23 |
| Trajectory export for training | yes | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Cost and token metrics | yes | https://github.com/SWE-agent/mini-swe-agentreleases | 2026-11-23 |
| Fallback providers | partial | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |
| Per-user OAuth for REST/OpenAPI tools | no | https://github.com/SWE-agent/mini-swe-agent | 2026-11-23 |

## Pi

- Repository: https://github.com/earendil-works/pi (moved from badlogic/pi-mono)
- Version: v1.6.0 (2026-11-20)
- GitHub stars: about 123.5k
- Caveat: Web UI, messaging gateway, scheduling and loop detection could not be confirmed from the docs read.

| Row | Value | Source | Accessed |
| --- | --- | --- | --- |
| Embeddable as a library | yes | https://github.com/earendil-works/pi | 2026-11-23 |
| Terminal UI | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Web UI | unknown | https://github.com/earendil-works/pi | 2026-11-23 |
| Messaging gateway (chat apps) | unknown | https://github.com/earendil-works/pi | 2026-11-23 |
| Model streaming | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Image input | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/usage.md; https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Broad provider support | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Model registry | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Provider OAuth logins | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Prompt caching | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Native tool calling | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Tool argument validation | yes | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| File tools | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Shell tool | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Web search and fetch | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Browser automation | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| LSP feedback | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| MCP | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/mcp.md | 2026-11-23 |
| MCP OAuth | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/mcp.md | 2026-11-23 |
| OpenAPI tools | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Sub-agents | no | https://github.com/earendil-works/pi | 2026-11-23 |
| Permission modes | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/security.md | 2026-11-23 |
| Pattern permission rules | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md | 2026-11-23 |
| Auto-mode classifier | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/security.md | 2026-11-23 |
| Sandbox isolation | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/security.md | 2026-11-23 |
| Context compaction | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md | 2026-11-23 |
| Multiple context strategies | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md | 2026-11-23 |
| Todo list | no | https://github.com/earendil-works/pi | 2026-11-23 |
| Session persistence and resume | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md | 2026-11-23 |
| Session fork or tree | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md | 2026-11-23 |
| Cross-session memory | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md | 2026-11-23 |
| File undo or checkpoints | partial | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md, extensions.md | 2026-11-23 |
| Hooks | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md | 2026-11-23 |
| Plugins or extensions | yes | https://github.com/earendil-works/pi | 2026-11-23 |
| Skills | yes | https://github.com/earendil-works/pi; https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Instruction files | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/security.md, cli.md | 2026-11-23 |
| Custom prompt templates | yes | https://github.com/earendil-works/pi; https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Custom commands | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md | 2026-11-23 |
| Scheduled tasks | unknown | https://github.com/earendil-works/pi | 2026-11-23 |
| Non-interactive or headless mode | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Stuck or loop detection | unknown | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md | 2026-11-23 |
| Verify-on-stop | partial | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md | 2026-11-23 |
| JSON or RPC protocol mode | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/cli.md | 2026-11-23 |
| Trajectory export for training | partial | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/sessions.md; https://github.com/earendil-works/pi | 2026-11-23 |
| Cost and token metrics | yes | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/usage.md; https://github.com/earendil-works/pi/blob/main/packages/ai/README.md | 2026-11-23 |
| Fallback providers | no | https://github.com/earendil-works/pi/blob/main/packages/ai/README.md; https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/settings.md | 2026-11-23 |
| Per-user OAuth for REST/OpenAPI tools | no | https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/mcp.md | 2026-11-23 |

## OpenCode

- Repository: https://github.com/sst/opencode
- Version: v2.4.0 (2026-11-23; the GitHub "Latest" badge still showed v1.19.6)
- GitHub stars: about 228.7k
- Caveat: Docs are the V2 rewrite (breaking changes from V1). Scheduled tasks and the built-in browser rest on release notes only.

| Row | Value | Source | Accessed |
| --- | --- | --- | --- |
| Embeddable as a library | yes | D build/sdk (https://opencode.ai/docs/sdk/) | 2026-11-23 |
| Terminal UI | yes | https://opencode.ai/docs/cli/ | 2026-11-23 |
| Web UI | yes | https://opencode.ai/docs/cli/web/ | 2026-11-23 |
| Messaging gateway (chat apps) | no | https://opencode.ai/docs/ | 2026-11-23 |
| Model streaming | yes | https://opencode.ai/docs/api | 2026-11-23 |
| Image input | yes | https://opencode.ai/docs/attachments/ | 2026-11-23 |
| Broad provider support | yes | https://opencode.ai/docs/providers/ | 2026-11-23 |
| Model registry | yes | https://opencode.ai/docs/models/ | 2026-11-23 |
| Provider OAuth logins | yes | https://opencode.ai/docs/providers/ | 2026-11-23 |
| Prompt caching | yes | https://opencode.ai/docs/models/ | 2026-11-23 |
| Native tool calling | yes | https://opencode.ai/docs/models/ | 2026-11-23 |
| Tool argument validation | unknown | https://opencode.ai/docs/build/plugins/server/ | 2026-11-23 |
| File tools | yes | https://opencode.ai/docs/tools/ | 2026-11-23 |
| Shell tool | yes | https://opencode.ai/docs/tools/ | 2026-11-23 |
| Web search and fetch | yes | https://opencode.ai/docs/websearch/ | 2026-11-23 |
| Browser automation | partial | https://opencode.ai/docs/tools/; https://github.com/sst/opencode/releases | 2026-11-23 |
| LSP feedback | no | https://opencode.ai/docs/migrate-v1/ | 2026-11-23 |
| MCP | yes | https://opencode.ai/docs/mcp-servers/ | 2026-11-23 |
| MCP OAuth | yes | https://opencode.ai/docs/mcp-servers/ | 2026-11-23 |
| OpenAPI tools | no | https://opencode.ai/docs/tools/ | 2026-11-23 |
| Sub-agents | yes | https://opencode.ai/docs/agents/ | 2026-11-23 |
| Permission modes | partial | https://opencode.ai/docs/agents/; https://opencode.ai/docs/cli/commands/ | 2026-11-23 |
| Pattern permission rules | yes | https://opencode.ai/docs/permissions/ | 2026-11-23 |
| Auto-mode classifier | no | https://opencode.ai/docs/permissions/ | 2026-11-23 |
| Sandbox isolation | no | https://opencode.ai/docs/permissions/ | 2026-11-23 |
| Context compaction | yes | https://opencode.ai/docs/compaction/ | 2026-11-23 |
| Multiple context strategies | partial | https://opencode.ai/docs/compaction/ | 2026-11-23 |
| Todo list | partial | https://opencode.ai/docs/migrate-v1/ | 2026-11-23 |
| Session persistence and resume | yes | https://opencode.ai/docs/cli/commands/ | 2026-11-23 |
| Session fork or tree | yes | https://opencode.ai/docs/api | 2026-11-23 |
| Cross-session memory | no | https://opencode.ai/docs/rules/ | 2026-11-23 |
| File undo or checkpoints | yes | https://opencode.ai/docs/snapshots/ | 2026-11-23 |
| Hooks | yes | https://opencode.ai/docs/build/plugins/server/ | 2026-11-23 |
| Plugins or extensions | yes | https://opencode.ai/docs/plugins/ | 2026-11-23 |
| Skills | yes | https://opencode.ai/docs/skills/ | 2026-11-23 |
| Instruction files | yes | https://opencode.ai/docs/rules/ | 2026-11-23 |
| Custom prompt templates | yes | https://opencode.ai/docs/commands/ | 2026-11-23 |
| Custom commands | yes | https://opencode.ai/docs/commands/ | 2026-11-23 |
| Scheduled tasks | yes | https://github.com/sst/opencode/releases | 2026-11-23 |
| Non-interactive or headless mode | yes | https://opencode.ai/docs/cli/commands/ | 2026-11-23 |
| Stuck or loop detection | no | https://opencode.ai/docs/permissions/ | 2026-11-23 |
| Verify-on-stop | no | https://opencode.ai/docs/build/plugins/server/ | 2026-11-23 |
| JSON or RPC protocol mode | yes | https://opencode.ai/docs/cli/acp/ | 2026-11-23 |
| Trajectory export for training | partial | https://opencode.ai/docs/cli/commands/ | 2026-11-23 |
| Cost and token metrics | yes | https://opencode.ai/docs/cli/commands/ | 2026-11-23 |
| Fallback providers | partial | https://opencode.ai/docs/providers/; https://opencode.ai/docs/models/ | 2026-11-23 |
| Per-user OAuth for REST/OpenAPI tools | no | https://opencode.ai/docs/tools/ | 2026-11-23 |

## Qwen Code

- Repository: https://github.com/QwenLM/qwen-code
- Version: v0.28.4 (2026-11-22)
- GitHub stars: about 30.9k
- Caveat: Tool argument validation, OpenAPI tools and trajectory export were not confirmable from the docs; source code was not inspected.

| Row | Value | Source | Accessed |
| --- | --- | --- | --- |
| Embeddable as a library | yes | https://qwenlm.github.io/qwen-code-docs/en/developers/sdk-typescript/ | 2026-11-23 |
| Terminal UI | yes | https://github.com/QwenLM/qwen-code | 2026-11-23 |
| Web UI | partial | https://raw.githubusercontent.com/QwenLM/qwen-code/main/README.md | 2026-11-23 |
| Messaging gateway (chat apps) | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/channels/overview/ | 2026-11-23 |
| Model streaming | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/headless/ | 2026-11-23 |
| Image input | partial | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/model-providers/ | 2026-11-23 |
| Broad provider support | yes | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/model-providers/ | 2026-11-23 |
| Model registry | partial | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/model-providers/ | 2026-11-23 |
| Provider OAuth logins | partial | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/auth/ | 2026-11-23 |
| Prompt caching | yes | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/ | 2026-11-23 |
| Native tool calling | yes | https://qwenlm.github.io/qwen-code-docs/en/developers/sdk-typescript/ | 2026-11-23 |
| Tool argument validation | unknown | https://qwenlm.github.io/qwen-code-docs/en/users/features/structured-output/ | 2026-11-23 |
| File tools | yes | https://qwenlm.github.io/qwen-code-docs/en/users/overview/ | 2026-11-23 |
| Shell tool | yes | https://qwenlm.github.io/qwen-code-docs/en/users/overview/ | 2026-11-23 |
| Web search and fetch | yes | https://qwenlm.github.io/qwen-code-docs/en/developers/tools/web-search/ | 2026-11-23 |
| Browser automation | partial | https://qwenlm.github.io/qwen-code-docs/en/users/features/browser-use/ | 2026-11-23 |
| LSP feedback | partial | https://qwenlm.github.io/qwen-code-docs/en/users/features/lsp/ | 2026-11-23 |
| MCP | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/mcp/ | 2026-11-23 |
| MCP OAuth | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/mcp/ | 2026-11-23 |
| OpenAPI tools | unknown | https://qwenlm.github.io/qwen-code-docs/en/users/overview/ | 2026-11-23 |
| Sub-agents | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/sub-agents/ | 2026-11-23 |
| Permission modes | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/approval-mode/ | 2026-11-23 |
| Pattern permission rules | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/approval-mode/ | 2026-11-23 |
| Auto-mode classifier | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/auto-mode/ | 2026-11-23 |
| Sandbox isolation | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/sandbox/ | 2026-11-23 |
| Context compaction | yes | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/ | 2026-11-23 |
| Multiple context strategies | partial | https://qwenlm.github.io/qwen-code-docs/en/users/features/commands/ | 2026-11-23 |
| Todo list | yes | https://qwenlm.github.io/qwen-code-docs/en/users/overview/ | 2026-11-23 |
| Session persistence and resume | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/headless/ | 2026-11-23 |
| Session fork or tree | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/commands/ | 2026-11-23 |
| Cross-session memory | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/memory/ | 2026-11-23 |
| File undo or checkpoints | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/checkpointing/ | 2026-11-23 |
| Hooks | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/hooks/ | 2026-11-23 |
| Plugins or extensions | yes | https://qwenlm.github.io/qwen-code-docs/en/users/extension/introduction/ | 2026-11-23 |
| Skills | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/skills/ | 2026-11-23 |
| Instruction files | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/memory/ | 2026-11-23 |
| Custom prompt templates | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/commands/ | 2026-11-23 |
| Custom commands | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/commands/ | 2026-11-23 |
| Scheduled tasks | partial | https://qwenlm.github.io/qwen-code-docs/en/users/features/scheduled-tasks/ | 2026-11-23 |
| Non-interactive or headless mode | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/headless/ | 2026-11-23 |
| Stuck or loop detection | yes | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/ | 2026-11-23 |
| Verify-on-stop | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/hooks/ | 2026-11-23 |
| JSON or RPC protocol mode | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/channels/overview/ | 2026-11-23 |
| Trajectory export for training | unknown | https://qwenlm.github.io/qwen-code-docs/en/developers/development/telemetry/ | 2026-11-23 |
| Cost and token metrics | yes | https://qwenlm.github.io/qwen-code-docs/en/users/features/commands/ | 2026-11-23 |
| Fallback providers | yes | https://qwenlm.github.io/qwen-code-docs/en/users/configuration/settings/ | 2026-11-23 |
| Per-user OAuth for REST/OpenAPI tools | no | https://qwenlm.github.io/qwen-code-docs/en/users/features/mcp/ | 2026-11-23 |

## Hermes Agent

- Repository: https://github.com/NousResearch/hermes-agent
- Version: v0.23.3 (2026-11-20)
- GitHub stars: about 265.6k
- Caveat: Several doc pages were only partially read (300+ pages, truncated at 100k characters).

| Row | Value | Source | Accessed |
| --- | --- | --- | --- |
| Embeddable as a library | partial | https://hermes-agent.nousresearch.com/docs/guides/python-library | 2026-11-23 |
| Terminal UI | yes | https://hermes-agent.nousresearch.com/docs/user-guide/tui | 2026-11-23 |
| Web UI | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard | 2026-11-23 |
| Messaging gateway (chat apps) | yes | https://hermes-agent.nousresearch.com/docs/user-guide/messaging | 2026-11-23 |
| Model streaming | yes | https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop | 2026-11-23 |
| Image input | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/vision | 2026-11-23 |
| Broad provider support | yes | https://hermes-agent.nousresearch.com/docs/integrations/providers | 2026-11-23 |
| Model registry | partial | https://hermes-agent.nousresearch.com/docs/reference/model-catalog | 2026-11-23 |
| Provider OAuth logins | yes | https://hermes-agent.nousresearch.com/docs/integrations/providers | 2026-11-23 |
| Prompt caching | yes | https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching | 2026-11-23 |
| Native tool calling | yes | https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop | 2026-11-23 |
| Tool argument validation | unknown | https://hermes-agent.nousresearch.com/docs/user-guide/security | 2026-11-23 |
| File tools | yes | https://hermes-agent.nousresearch.com/docs/reference/tools-reference | 2026-11-23 |
| Shell tool | yes | https://hermes-agent.nousresearch.com/docs/reference/tools-reference | 2026-11-23 |
| Web search and fetch | yes | https://hermes-agent.nousresearch.com/docs/reference/tools-reference | 2026-11-23 |
| Browser automation | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/browser | 2026-11-23 |
| LSP feedback | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/lsp | 2026-11-23 |
| MCP | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp | 2026-11-23 |
| MCP OAuth | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp | 2026-11-23 |
| OpenAPI tools | no | https://hermes-agent.nousresearch.com/docs/reference/tools-reference | 2026-11-23 |
| Sub-agents | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/delegation | 2026-11-23 |
| Permission modes | yes | https://hermes-agent.nousresearch.com/docs/user-guide/security | 2026-11-23 |
| Pattern permission rules | yes | https://hermes-agent.nousresearch.com/docs/user-guide/security | 2026-11-23 |
| Auto-mode classifier | yes | https://hermes-agent.nousresearch.com/docs/user-guide/security | 2026-11-23 |
| Sandbox isolation | yes | https://hermes-agent.nousresearch.com/docs/user-guide/security | 2026-11-23 |
| Context compaction | yes | https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching | 2026-11-23 |
| Multiple context strategies | partial | https://hermes-agent.nousresearch.com/docs/developer-guide/context-compression-and-caching | 2026-11-23 |
| Todo list | yes | https://hermes-agent.nousresearch.com/docs/reference/tools-reference | 2026-11-23 |
| Session persistence and resume | yes | https://hermes-agent.nousresearch.com/docs/reference/cli-commands | 2026-11-23 |
| Session fork or tree | yes | https://hermes-agent.nousresearch.com/docs/reference/slash-commands | 2026-11-23 |
| Cross-session memory | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/memory | 2026-11-23 |
| File undo or checkpoints | yes | https://hermes-agent.nousresearch.com/docs/user-guide/checkpoints-and-rollback | 2026-11-23 |
| Hooks | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/hooks | 2026-11-23 |
| Plugins or extensions | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/plugins | 2026-11-23 |
| Skills | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/skills | 2026-11-23 |
| Instruction files | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/context-files | 2026-11-23 |
| Custom prompt templates | partial | https://hermes-agent.nousresearch.com/docs/user-guide/features/skills | 2026-11-23 |
| Custom commands | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/skills | 2026-11-23 |
| Scheduled tasks | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/cron | 2026-11-23 |
| Non-interactive or headless mode | yes | https://hermes-agent.nousresearch.com/docs/reference/cli-commands | 2026-11-23 |
| Stuck or loop detection | unknown | https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop | 2026-11-23 |
| Verify-on-stop | yes | https://hermes-agent.nousresearch.com/docs/user-guide/features/hooks | 2026-11-23 |
| JSON or RPC protocol mode | yes | https://hermes-agent.nousresearch.com/docs/reference/cli-commands | 2026-11-23 |
| Trajectory export for training | yes | https://hermes-agent.nousresearch.com/docs/developer-guide/trajectory-format | 2026-11-23 |
| Cost and token metrics | yes | https://hermes-agent.nousresearch.com/docs/reference/cli-commands | 2026-11-23 |
| Fallback providers | yes | https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop | 2026-11-23 |
| Per-user OAuth for REST/OpenAPI tools | no | https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp | 2026-11-23 |

## Claude Code

- Repository: https://code.claude.com/docs/en/ (repo anthropics/claude-code)
- Version: 2.1.330 (2026-11-20)
- GitHub stars: not applicable (the repository holds issues, plugins and examples, not the CLI source)
- Caveat: Official docs only; no source inspection.

| Row | Value | Source | Accessed |
| --- | --- | --- | --- |
| Embeddable as a library | yes | https://code.claude.com/docs/en/agent-sdk/overview | 2026-11-23 |
| Terminal UI | yes | https://code.claude.com/docs/en/overview | 2026-11-23 |
| Web UI | yes | https://code.claude.com/docs/en/overview | 2026-11-23 |
| Messaging gateway (chat apps) | partial | https://code.claude.com/docs/en/slack | 2026-11-23 |
| Model streaming | yes | https://code.claude.com/docs/en/headless | 2026-11-23 |
| Image input | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Broad provider support | partial | https://code.claude.com/docs/en/third-party-integrations | 2026-11-23 |
| Model registry | partial | https://code.claude.com/docs/en/model-config | 2026-11-23 |
| Provider OAuth logins | yes | https://code.claude.com/docs/en/overview | 2026-11-23 |
| Prompt caching | yes | https://code.claude.com/docs/en/costs | 2026-11-23 |
| Native tool calling | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Tool argument validation | unknown | https://code.claude.com/docs/en/hooks | 2026-11-23 |
| File tools | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Shell tool | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Web search and fetch | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Browser automation | partial | https://code.claude.com/docs/en/chrome | 2026-11-23 |
| LSP feedback | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| MCP | yes | https://code.claude.com/docs/en/mcp | 2026-11-23 |
| MCP OAuth | yes | https://code.claude.com/docs/en/mcp | 2026-11-23 |
| OpenAPI tools | no | https://code.claude.com/docs/en/mcp | 2026-11-23 |
| Sub-agents | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Permission modes | yes | https://code.claude.com/docs/en/permission-modes | 2026-11-23 |
| Pattern permission rules | yes | https://code.claude.com/docs/en/permissions | 2026-11-23 |
| Auto-mode classifier | yes | https://code.claude.com/docs/en/permission-modes | 2026-11-23 |
| Sandbox isolation | yes | https://code.claude.com/docs/en/sandboxing | 2026-11-23 |
| Context compaction | yes | https://code.claude.com/docs/en/context-window | 2026-11-23 |
| Multiple context strategies | partial | https://code.claude.com/docs/en/checkpointing | 2026-11-23 |
| Todo list | yes | https://code.claude.com/docs/en/tools-reference | 2026-11-23 |
| Session persistence and resume | yes | https://code.claude.com/docs/en/sessions | 2026-11-23 |
| Session fork or tree | yes | https://code.claude.com/docs/en/sessions | 2026-11-23 |
| Cross-session memory | yes | https://code.claude.com/docs/en/memory | 2026-11-23 |
| File undo or checkpoints | yes | https://code.claude.com/docs/en/checkpointing | 2026-11-23 |
| Hooks | yes | https://code.claude.com/docs/en/hooks | 2026-11-23 |
| Plugins or extensions | yes | https://code.claude.com/docs/en/plugins | 2026-11-23 |
| Skills | yes | https://code.claude.com/docs/en/skills | 2026-11-23 |
| Instruction files | yes | https://code.claude.com/docs/en/memory | 2026-11-23 |
| Custom prompt templates | yes | https://code.claude.com/docs/en/skills | 2026-11-23 |
| Custom commands | yes | https://code.claude.com/docs/en/skills | 2026-11-23 |
| Scheduled tasks | yes | https://code.claude.com/docs/en/overview | 2026-11-23 |
| Non-interactive or headless mode | yes | https://code.claude.com/docs/en/headless | 2026-11-23 |
| Stuck or loop detection | unknown | https://github.com/anthropics/claude-code/issues/19699 | 2026-11-23 |
| Verify-on-stop | yes | https://code.claude.com/docs/en/hooks | 2026-11-23 |
| JSON or RPC protocol mode | yes | https://code.claude.com/docs/en/headless | 2026-11-23 |
| Trajectory export for training | partial | https://code.claude.com/docs/en/sessions | 2026-11-23 |
| Cost and token metrics | yes | https://code.claude.com/docs/en/costs | 2026-11-23 |
| Fallback providers | partial | https://code.claude.com/docs/en/model-config | 2026-11-23 |
| Per-user OAuth for REST/OpenAPI tools | no | https://code.claude.com/docs/en/mcp | 2026-11-23 |

## OpenHands

- Repository: https://github.com/OpenHands/OpenHands (MIT; about 94.2k stars; release list empty on the page, quickstart pins `agent-canvas:1.44.0`)
- SDK repository: https://github.com/OpenHands/software-agent-sdk (MIT; about 1.4k stars; SWE-bench badge 77.6)
- Caveat: Documentation and marketing pages only; no source inspection. Pages are summaries produced by a fetch tool, so wording is paraphrased. A claim that the `enterprise/` directory is source-available came from a search-result summary of docs.openhands.dev, not from a page read in full; the enterprise page read says only "Commercial".

| Topic | Value | Source | Accessed |
| --- | --- | --- | --- |
| Architecture (Agent Canvas, Agent Server, Automation Server, sandbox options), integrations, stars | see section | https://github.com/OpenHands/OpenHands | 2026-11-24 |
| SDK workspaces (local, Docker, Kubernetes), Agent Server REST/WebSocket, SWE-bench badge | see section | https://github.com/OpenHands/software-agent-sdk | 2026-11-24 |
| Edition feature table (SAML, RBAC via Keycloak, LiteLLM budgeting, Laminar, audit logs, licences) | see section | https://docs.openhands.dev/enterprise/enterprise-vs-oss | 2026-11-24 |
| Enterprise overview (VPC, on-premises, integrations, BYOK, data residency) | see section | https://docs.openhands.dev/enterprise | 2026-11-24 |
| Kubernetes install (Helm 4, EKS/AKS/GKE, Keycloak, PostgreSQL, Redis, LiteLLM, S3, licence key, 30-day trial) | see section | https://docs.openhands.dev/enterprise/k8s-install/installation | 2026-11-24 |
| Product page (self-hosted in VPC on AWS, Azure, Google Cloud; centralized audit logs; RBAC) | see section | https://openhands.dev/enterprise | 2026-11-24 |
| Plans and prices (Open Source free; Teams $500/month, 10 users, +$50 per seat, 5,000 sessions cap; Enterprise custom) | see section | https://openhands.dev/pricing | 2026-11-24 |
| Confirmation policies and security analyzers | see section | https://docs.openhands.dev/sdk/guides/security | 2026-11-24 |
| Append-only event log | see section | https://docs.openhands.dev/sdk/arch/events | 2026-11-24 |
| Local setup, model providers | see section | https://docs.openhands.dev/openhands/usage/run-openhands/local-setup | 2026-11-24 |
| OpenHands Index (top average 71.88, snapshot 2026-05-28) | see section | https://openhands.dev/blog/openhands-index; https://huggingface.co/datasets/OpenHands/openhands-index | 2026-11-24 |

## Rows added in the second pass

These rows come from the extra-capabilities and weaknesses notes of the same research, accessed 2026-11-23. Version and star counts are in the per-project headers above. A project not named for a row is `unknown` there.

| Row | Basis |
| --- | --- |
| IDE integrations | OpenCode: ACP server for Zed and other editors (https://opencode.ai/docs/cli/acp/). Qwen Code: ACP and VS Code, Zed, JetBrains integrations (docs sidebar and features pages). Hermes Agent: ACP server (docs). Claude Code: VS Code and JetBrains extensions (https://code.claude.com/docs/en/overview) |
| Native Gemini, Bedrock, Vertex clients | Pi: 30+ providers (packages/ai README). OpenCode: Bedrock, Vertex, Azure (https://opencode.ai/docs/providers/). Qwen Code: gemini and vertex-ai protocols (model-providers page). Hermes Agent: Bedrock, Vertex (https://hermes-agent.nousresearch.com/docs/integrations/providers). Claude Code: Bedrock, Vertex, Foundry for Claude models only (https://code.claude.com/docs/en/third-party-integrations). mini-swe-agent: through LiteLLM |
| Scripted tool calls (code mode) | Pi: codemode tool (cli.md). OpenCode: Code Mode `execute` tool (tools and mcp-servers pages). Hermes Agent: `execute_code` (extra capabilities). jllm: `run_js` plugin ([JS guide](../guides/js.md)) |
| Hosted or managed options | OpenCode: enterprise console with SSO, SCIM, budgets (docs sitemap). Claude Code: cloud sessions, managed settings (https://code.claude.com/docs/en/overview, /costs) |
| Benchmark results published | mini-swe-agent: above 74% on SWE-bench Verified (README). jllm: the `eval/jllm-eval` module in this repository runs model evaluations; no scores are published outside it |
| Open source | Pi, OpenCode (MIT) and Hermes Agent (MIT): GitHub repositories. Claude Code: the repository holds issues, plugins, examples and the changelog, not the CLI source (license not confirmed). jllm: Apache-2.0 |
| Release cadence, community size, runtime footprint | GitHub releases and repository pages, and docs for requirements (Pi: Node 22.19+; Claude Code: native installer, npm package deprecated; Hermes Agent dashboard: POSIX or WSL2). jllm: `1.4.0-jllm` in the root POM; no star figure is claimed |
