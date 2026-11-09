# Feature matrix

Legend: yes, no, partial. Same caveats as above: all columns except jllm are from memory and unverified; Hermes Agent has the lowest confidence.

<table>
<tr><th>Functionality</th><th>mini-swe-agent</th><th>Pi</th><th>OpenCode</th><th>Qwen Code</th><th>Hermes Agent</th><th>jllm</th></tr>
<tr><td><em>Embeddable as a library</em></td><td><em>partial (Python API)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (TS packages)</em></td><td><em>partial (server and SDK)</em></td><td><em>partial (Node SDK)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (Java)</em></td></tr>
<tr><td>Terminal UI</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Web UI</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td><em>partial (desktop and web client)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Messaging gateway (chat apps)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>Model streaming</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>Image input</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>Broad provider support</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (LiteLLM)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial (OpenAI-compatible)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td></tr>
<tr><td>Model registry</td><td>partial (LiteLLM)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>Provider OAuth logins</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (Qwen)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>Prompt caching</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes (Anthropic)</td></tr>
<tr><td><em>Native tool calling</em></td><td><em>partial</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Tool argument validation</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td>partial</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>File tools</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no (bash only)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Shell tool</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Web search and fetch</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td><em>partial</em></td><td><em>partial (fetch)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Browser automation</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>LSP feedback</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>MCP</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial (via extensions)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>MCP OAuth</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td><td><em>partial</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>OpenAPI tools</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Sub-agents</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Permission modes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Pattern permission rules</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Auto-mode classifier</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Sandbox isolation</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (Docker and others)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (Docker, Podman, Seatbelt)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (Docker, SSH and others)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (Docker)</em></td></tr>
<tr><td>Context compaction</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Multiple context strategies</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Todo list</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Session persistence and resume</em></td><td><em>partial (trajectories)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Session fork or tree</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>Cross-session memory</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td><em>partial (memory tool)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>File undo or checkpoints</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Hooks</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Plugins or extensions</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes (Java)</td></tr>
<tr><td><em>Skills</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td><td><em>partial</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (auto-created)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Instruction files</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
<tr><td><em>Custom prompt templates</em></td><td><em>partial (config)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>Custom commands</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes (plugins)</td></tr>
<tr><td><em>Scheduled tasks</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>Non-interactive or headless mode</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes (<code>--prompt</code>)</td></tr>
<tr><td><em>JSON or RPC protocol mode</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes (server)</em></td><td><em>partial (JSON output)</em></td><td><em>partial</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000"><em>no</em></td></tr>
<tr><td>Trajectory export for training</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td></tr>
<tr><td><em>Cost and token metrics</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td><td><em>partial</em></td><td><em>partial</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000"><em>yes</em></td></tr>
<tr><td>Fallback providers</td><td>partial (LiteLLM)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">no</td><td>partial</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">yes</td></tr>
</table>

---

## Contents

- [Feature matrix](#feature-matrix)
- [jllm-harness vs mini-swe-agent](#jllm-harness-vs-mini-swe-agent)
- [jllm-harness vs Pi](#jllm-harness-vs-pi)
- [jllm-harness vs OpenCode](#jllm-harness-vs-opencode)
- [jllm-harness vs Qwen Code](#jllm-harness-vs-qwen-code)
- [jllm-harness vs Hermes Agent](#jllm-harness-vs-hermes-agent)

## Six-way summary

|  | Core idea | Strength | Weakness vs others |
| --- | --- | --- | --- |
| *mini-swe-agent* | *Bash only, linear history* | *Simplicity, benchmarking* | *No safety, context management or extensions* |
| Pi | Minimal core, extensions | Model layer, session tree, run modes | Fewer built-in safety features |
| *OpenCode* | *Full client/server coding agent* | *Provider breadth, LSP, UX, sharing* | *No sandbox, not embeddable in the JVM* |
| Qwen Code | Gemini CLI fork tuned for Qwen | Qwen integration, vision, sandbox options | Narrow model focus, not embeddable in the JVM |
| *Hermes Agent* | *Persistent self-improving assistant* | *Memory, skills, scheduling, chat gateway* | *Less focused on coding and embedding* |
| jllm | Embeddable Java harness | Permissions, sandbox, sub-agents, MCP, OpenAPI, context strategies | No streaming, images, model registry, fork/rewind, cross-session memory, scheduling |

---

# jllm-harness vs mini-swe-agent

Basis: jllm side from `jllm-harness/README.md` and `gaps.md` (code not read). mini-swe-agent side from memory, not verified against its current source.

| Aspect | mini-swe-agent | jllm-harness |
| --- | --- | --- |
| *Goal* | *Minimal baseline for benchmarks (SWE-bench) and research.* | *Embeddable production agent library, with CLI and web front ends.* |
| Language | Python | Java 21, about 17k lines in 161 files. |
| *Size* | *About 100 lines for the agent class.* | *Large: loop, permissions, context strategies, sub-agents, MCP, auth, hooks, plugins.* |
| Tools | Bash only. | Files, shell, web search and fetch, MCP, OpenAPI operations, own Java tools, sub-agent `task`. |
| *Action format* | *Originally one fenced bash block per reply, parsed from text. Newer versions can also use tool calls.* | *Native tool calling. Tool-argument validation not done.* |
| Execution | Each command is a fresh `subprocess.run`, no persistent shell. Swappable environments: local, Docker, Podman, Singularity. | Shell tool with sandbox backends: local directory (no isolation), per-chat directory, Docker. Files confined to the working directory. |
| *History* | *Linear and append-only; the trajectory is the prompt.* | *Per-chat sessions in `messages.jsonl`, three context strategies (compaction, mnemonic masking, scope with offload and todo list).* |
| Context limits | None beyond cost and step limits. | Compaction, handoff notes, `history_recall`, retry when the provider rejects a request as too long. |
| *Models* | *LiteLLM, broad provider coverage.* | *Anthropic (caching, thinking) and OpenAI-compatible endpoints, including Gemini, Ollama, llama.cpp. Fallback chains, fast model for helper tasks. No streaming, no image input.* |
| Safety | Mostly relies on the sandbox. | `plan`/`edit`/`auto` modes, regex deny/ask/allow rules, approval policy, per-chat grants, classifier in auto mode. |
| *Control flow* | *Step and cost limits; a "task finished" marker command ends the run.* | *Cancel, steering and follow-up messages, continuation of cut-off responses, hooks at agent, model-call and tool-call level.* |
| Extensibility | Subclass or swap config and templates. | Plugins via ServiceLoader, skills, instruction files, slash commands, custom agent types. |
| *Interface* | *CLI and Python API, trajectory output.* | *Java library, terminal chat, browser chat with REST and SSE.* |
| Auth | API keys through LiteLLM. | API keys, plus OAuth utilities (PKCE, device code, token store, MCP OAuth). |

## Where each fits

- **mini-swe-agent**: simplicity, reproducibility, benchmarking. Linear history and plain bash make runs easy to compare. A small model can run it with little setup.
- **jllm**: embedding in a Java application with own tools and approvals; long sessions via compaction; human-in-the-loop use through permission modes and sub-agents.

## Trade-offs

- jllm's prompt, tool set and context management add variables. Benchmark results would depend more on the harness itself.
- jllm has no per-step trajectory export built for evaluation. `/export` writes a readable transcript instead.
- mini-swe-agent has no permission system, MCP, sub-agents or context management. It assumes a disposable container.

---

# jllm-harness vs Pi

Basis: `gaps.md`, which compares jllm with Pi (pi-ai, pi-agent-core, pi-coding-agent) from memory, not Pi's current source. Verify the Pi side before planning. Scope: internals only.

| Aspect | Pi | jllm-harness |
| --- | --- | --- |
| *Goal* | *Minimal, extensible terminal coding agent, layered as pi-ai, pi-agent-core, pi-coding-agent.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | TypeScript | Java 21 |
| *Providers* | *Anthropic, OpenAI, Google/Gemini, Vertex, Bedrock, Azure, Mistral, OpenRouter, native clients.* | *Anthropic and OpenAI-compatible endpoints; Gemini through its OpenAI-compatible endpoint. No native Gemini, Bedrock or Vertex.* |
| Streaming | Text, thinking and tool-call argument deltas, mid-stream abort. | None model-side; turn-level events only (SSE in jllm-web). |
| *Image input* | *Yes* | *No* |
| Model registry | Per-model context window, max output, cost, capabilities. | Per-provider or manual (`HarnessPricing`). |
| *Cross-provider handoff* | *Converts thinking blocks and tool-call IDs on mid-chat model switch.* | *No transform layer.* |
| Auth | API keys, provider OAuth (subscription logins). | API keys; generic OAuth utilities and MCP OAuth done; no provider logins. |
| *Tool progress* | *Tools push partial results (`onUpdate`).* | *Tools return once.* |
| Argument validation | JSON-schema validation, errors returned to the model. | None. |
| *Context hooks* | *`transformContext`, `convertToLlm`, custom message types.* | *Pluggable `contextStrategy(...)`, no custom message roles.* |
| Steering | Steer and follow-up, "one at a time" or "all queued". | Steer and follow-up, no delivery modes. |
| *Sessions* | *Tree (`/tree`), branch summaries, labels.* | *Flat `messages.jsonl`; save, resume, copy, `/undo` for files. No fork/rewind.* |
| Run modes | print, `--mode json`, `--mode rpc`. | `--prompt` only. |
| *Prompt templates* | *`/name args` with `$1`, `$@`.* | *Plugins only.* |
| System prompt | `SYSTEM.md` replace, `APPEND_SYSTEM.md` append. | Done: `--system-prompt-file`, `--system-prompt-replace`, `SystemPromptFiles`. |
| *Input shortcuts* | *`@file`, `!cmd`, `!!cmd`.* | *None.* |
| Settings | Global and project layering. | Done: layered global, project, `--settings`. |
| *Retry* | *Visible retry events, agent-level retry.* | *Done: `retry` event from clients; agent-level retry only on context overflow.* |
| Extensions | TS extensions: UI components, shortcuts, flags, custom providers; packages, hot reload. | Java `HarnessPlugin`: tools, hooks, commands, skills, agents. No UI, packages or hot reload. |

## Ahead of Pi

- Permission modes, classifier, deny/ask/allow rules.
- Docker and remote sandboxes.
- Sub-agents.
- MCP and OpenAPI tools.
- Three context strategies (`compaction`, `mnemonic`, `scope`).

## Three-way summary

- **mini-swe-agent**: smallest; bash only; for benchmarks.
- **Pi**: richest model layer and coding-agent UX; intentionally minimal core with extensions.
- **jllm**: strongest on safety, sandboxing, sub-agents, MCP and context management; weakest on streaming, images and model abstraction.

---

# jllm-harness vs OpenCode

Basis: OpenCode side from memory (sst/opencode, TypeScript on Bun), not verified against its current source. jllm side from `README.md` and `gaps.md`.

| Aspect | OpenCode | jllm-harness |
| --- | --- | --- |
| *Goal* | *Full terminal coding agent (TUI), also desktop app and IDE extension.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | TypeScript (Bun) | Java 21 |
| *Architecture* | *Client/server: a local server with HTTP API and SDK; TUI and other clients attach to it.* | *In-process library; `jllm-web` adds REST and SSE.* |
| Providers | Broad (75+ through Vercel AI SDK and models.dev registry), including subscription logins and Copilot. | Anthropic and OpenAI-compatible endpoints; no provider logins. |
| *Model metadata* | *Registry with context, cost, capabilities.* | *Per-provider or manual.* |
| Streaming | Yes, text, thinking, tool calls. | No model-side streaming. |
| *Image input* | *Yes* | *No* |
| Built-in tools | read, write, edit, patch, bash, grep, glob, list, webfetch, todo, task. LSP diagnostics fed back to the model. | Files, shell, web search and fetch, `ls`, `todo_write`, `task`. No LSP. |
| *Agents* | *`build` (full access), `plan` (read-only), `general` subagent; custom agents with per-agent model, prompt, tools, permissions.* | *`plan`/`edit`/`auto` modes; `general`, `explore`, `plan` and custom sub-agent types.* |
| Permissions | allow/ask/deny per tool with patterns, per agent. | Modes, regex deny/ask/allow rules, approval policy, per-chat grants, fast-model classifier in `auto`. |
| *Sandbox* | *None built in; runs on host.* | *Local directory, per-chat directory, Docker.* |
| MCP | Local and remote, with OAuth. | HTTP and stdio, OAuth (`McpOAuthLogin`). |
| *REST APIs as tools* | *No (via MCP or custom tools).* | *OpenAPI operations as tools.* |
| Context | Auto-compaction and pruning of old tool output. | Three strategies: `compaction`, `mnemonic`, `scope`. |
| *Sessions* | *Persistent, child sessions for subagents, share links, fork.* | *Flat `messages.jsonl`; save, resume, copy; no fork.* |
| Undo | Git snapshot based undo/redo of file changes and messages. | `/undo` restores files; checkpoints. |
| *Instructions* | *`AGENTS.md`, global and project config.* | *`KENDAR.md` instruction files, layered settings, system prompt files.* |
| Extensibility | JS/TS plugins, custom tools, custom commands (markdown), hooks, themes, formatters. | Java `HarnessPlugin`, hooks, skills, slash commands. No prompt templates, formatters or themes. |
| *Run modes* | *TUI, `run` (non-interactive), server, ACP, GitHub integration.* | *CLI chat, `--prompt`, web.* |

## Where each fits

- **OpenCode**: ready-made terminal coding agent with broad model support and LSP feedback.
- **jllm**: embedding in a Java application with own tools, approvals and Docker isolation.

## Four-way summary

|  | Core idea | Strength | Weakness vs others |
| --- | --- | --- | --- |
| *mini-swe-agent* | *Bash only, linear history* | *Simplicity, benchmarking* | *No safety, context management or extensions* |
| Pi | Minimal core, extensions | Model layer, session tree, run modes | Fewer built-in safety features |
| *OpenCode* | *Full client/server coding agent* | *Provider breadth, LSP, UX, sharing* | *No sandbox, not embeddable in the JVM* |
| jllm | Embeddable Java harness | Permissions, sandbox, sub-agents, MCP, OpenAPI, context strategies | No streaming, images, model registry, fork/rewind |

---

# jllm-harness vs Qwen Code

Basis: Qwen Code side from memory (QwenLM/qwen-code, a TypeScript CLI forked from Gemini CLI and tuned for Qwen3-Coder), not verified against its current source. jllm side from `README.md` and `gaps.md`.

| Aspect | Qwen Code | jllm-harness |
| --- | --- | --- |
| *Goal* | *Terminal coding agent tuned for Qwen models.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | TypeScript (Node) | Java 21 |
| *Providers* | *OpenAI-compatible APIs (Qwen, OpenRouter, local), Qwen OAuth login.* | *Anthropic and OpenAI-compatible endpoints; no provider logins. Local Qwen supported through llama.cpp, vLLM, Ollama and a `--small-model` prompt.* |
| Streaming | Yes | No model-side streaming. |
| *Image input* | *Yes, through a vision model.* | *No* |
| Built-in tools | read, write, edit, read-many-files, ls, glob, grep, shell, web fetch, web search, memory, todo, task. | Files, shell, web search and fetch, `ls`, `todo_write`, `task`, document readers (pdf, docx). |
| *Approval modes* | *plan, default, auto-edit, yolo.* | *`plan`, default, `edit`, `auto`, plus regex rules and a fast-model classifier.* |
| Sandbox | Docker, Podman, macOS Seatbelt. | Local directory (no isolation), per-chat directory, Docker. |
| *Sub-agents* | *Yes, configurable.* | *Yes: `general`, `explore`, `plan`, custom types.* |
| MCP | Yes | HTTP and stdio, OAuth. |
| *REST APIs as tools* | *No* | *OpenAPI operations as tools.* |
| Context | `/compress` summarization, session save and resume. | Three strategies: `compaction`, `mnemonic`, `scope`. |
| *Checkpoints* | *Git-based checkpoints and restore.* | *Checkpoints, `/undo` for files.* |
| Instructions | `QWEN.md` context files. | `KENDAR.md` instruction files, system prompt files. |
| *Extensibility* | *Custom commands, extensions, hooks, IDE integration.* | *Java `HarnessPlugin`, hooks, skills, slash commands.* |
| Run modes | Interactive, headless with JSON output. | CLI chat, `--prompt`, web. |
| *Embedding* | *Node SDK, not designed as a JVM library.* | *Java library with ports for stores, sandbox, approvals and metrics.* |

## Where each fits

- **Qwen Code**: ready-made CLI for Qwen models, with a free login tier and vision.
- **jllm**: Java embedding; also works with local Qwen models through its small-model prompt and serialized model calls (`maxParallel: 1`).

## Five-way summary

|  | Core idea | Strength | Weakness vs others |
| --- | --- | --- | --- |
| *mini-swe-agent* | *Bash only, linear history* | *Simplicity, benchmarking* | *No safety, context management or extensions* |
| Pi | Minimal core, extensions | Model layer, session tree, run modes | Fewer built-in safety features |
| *OpenCode* | *Full client/server coding agent* | *Provider breadth, LSP, UX, sharing* | *No sandbox, not embeddable in the JVM* |
| Qwen Code | Gemini CLI fork tuned for Qwen | Qwen integration, vision, sandbox options | Narrow model focus, not embeddable in the JVM |
| *jllm* | *Embeddable Java harness* | *Permissions, sandbox, sub-agents, MCP, OpenAPI, context strategies* | *No streaming, images, model registry, fork/rewind* |

---

# jllm-harness vs Hermes Agent

Basis: Hermes Agent (Nous Research) side from memory, with low confidence; verify before relying on it. jllm side from `README.md` and `gaps.md`.

| Aspect | Hermes Agent | jllm-harness |
| --- | --- | --- |
| *Goal* | *Persistent, self-improving personal agent that lives on a server and is reached from chat apps and a CLI.* | *Embeddable coding and tool agent in a Java application.* |
| Language | Python | Java 21 |
| *Providers* | *Nous Portal, OpenRouter, OpenAI-compatible endpoints, local models.* | *Anthropic and OpenAI-compatible endpoints.* |
| Interfaces | Terminal UI plus a messaging gateway (Telegram, Discord, Slack, WhatsApp and others). | CLI chat, web chat (REST and SSE), Java API. |
| *Memory* | *Persistent memory across sessions, full-text search of past sessions, user modeling.* | *Per-chat session logs; `history_recall` in `mnemonic` strategy; instruction files. No cross-session memory.* |
| Skills | Creates and refines skills from experience. | Loads skills from settings; no automatic creation. |
| *Scheduling* | *Built-in cron-style scheduled tasks with delivery to chat platforms.* | *None.* |
| Execution backends | Local, Docker, SSH, serverless backends. | Local directory, per-chat directory, Docker. |
| *Sub-agents* | *Delegates to isolated sub-agents.* | *`general`, `explore`, `plan`, custom types, inheriting mode and permissions.* |
| Tools | Terminal, files, web, browser, code execution, MCP, many integrations. | Files, shell, web, MCP, OpenAPI operations, own Java tools, document readers. No browser automation. |
| *Permissions* | *Approval prompts for dangerous commands.* | *Modes, regex rules, approval policy, per-chat grants, classifier.* |
| Context | Compression of long conversations. | Three strategies: `compaction`, `mnemonic`, `scope`. |
| *Research use* | *Trajectory export for model training and RL.* | *No trajectory export built for training.* |
| Embedding | Python package and CLI, not a library for host applications. | Java library with ports for stores, sandbox, approvals and metrics. |

## Where each fits

- **Hermes Agent**: long-lived assistant with memory, schedules and chat-app access.
- **jllm**: tool-using agent inside a Java application, with controlled permissions.
