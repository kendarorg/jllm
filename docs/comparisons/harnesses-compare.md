# Feature matrix

Legend: 🟢 yes, 🔴 no, uncoloured partial or unknown. Verification date: 2026-11-23. Every competitor cell comes from that project's official documentation or repository; the source URL and access date of each cell are in [harnesses-sources](harnesses-sources.md). `unknown` means the docs read did not settle the cell, and a `no` often means "not documented". The jllm column was checked against this repository (docs, `core/jllm-harness/README.md`, settings, tools). Competitor projects change quickly (Pi and OpenCode have released breaking versions recently), so treat the matrix as a snapshot. Known jllm gaps are also listed in [Roadmap and limits](../enterprise/roadmap-and-limits.md).

<table>
<tr><th>Functionality</th><th>mini-swe-agent</th><th>Pi</th><th>OpenCode</th><th>Qwen Code</th><th>Hermes Agent</th><th>Claude Code</th><th>jllm</th></tr>
<tr><td><em>Embeddable as a library</em></td><td><em>partial (Python API)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (TS SDK, pi-agent-core, pi-ai)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (TS/Bun SDK only)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (TS SDK; Python/Java SDKs alpha)</em></td><td><em>partial (importable from a checkout, no supported wheel)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Agent SDK (TS, Python), drives the CLI binary)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Java)</em></td></tr>
<tr><td>Terminal UI</td><td>partial (Rich REPL, no full TUI documented)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (line-based chat (JLine), not full-screen)</td></tr>
<tr><td><em>Web UI</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (trajectory inspector only)</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td><em>partial (experimental Web Shell)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (dashboard, needs POSIX/WSL2)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (claude.ai/code, desktop, mobile)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (reference front end (no TLS))</em></td></tr>
<tr><td>Messaging gateway (chat apps)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>unknown</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (Telegram, WeChat, DingTalk, Feishu, email, GitHub)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (Telegram, Discord, Slack, WhatsApp, 25+)</td><td>partial (Slack, Channels (Telegram, Discord, iMessage))</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td></tr>
<tr><td><em>Model streaming</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (text and thinking deltas)</em></td></tr>
<tr><td>Image input</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (modality config, vision fallback)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td></tr>
<tr><td><em>Broad provider support</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (LiteLLM, OpenRouter, Portkey)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (30+)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (models.dev catalog)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (OpenAI, Anthropic, Gemini, Vertex protocols)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (43)</em></td><td><em>partial (Claude models only, via Anthropic, Bedrock, Vertex, Foundry)</em></td><td><em>partial (Anthropic and OpenAI-compatible)</em></td></tr>
<tr><td>Model registry</td><td>partial (LiteLLM data)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (models.dev)</td><td>partial (config plus models.dev refresh)</td><td>partial (picker IDs, models.dev limits)</td><td>partial (Claude aliases and pricing)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no (per-provider settings and HarnessPricing)</td></tr>
<tr><td><em>Provider OAuth logins</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Anthropic, ChatGPT/Codex, Copilot, OpenRouter)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (ChatGPT, Copilot)</em></td><td><em>partial (Qwen account only)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Nous, Codex, Claude Max, Copilot, others)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Claude subscription)</em></td><td><em>partial (OAuthLogin SPI, no provider login shipped)</em></td></tr>
<tr><td>Prompt caching</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (Claude routes only)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (Anthropic cache control, stable prefix)</td></tr>
<tr><td><em>Native tool calling</em></td><td><em>partial (bash only, text-based variants)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Tool argument validation</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no (format errors only)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>unknown</td><td>unknown</td><td>unknown</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (lenient, strict, off)</td></tr>
<tr><td><em>File tools</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (bash only)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (path-jailed)</em></td></tr>
<tr><td>Shell tool</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>Web search and fetch</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (search auto-on for some providers)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Browser automation</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>partial (desktop app only)</td><td>partial (Chrome extension, Browser Use skill)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (Chrome extension)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td></tr>
<tr><td><em>LSP feedback</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (removed in V2)</em></td><td><em>partial (experimental, opt-in)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (via plugins)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td></tr>
<tr><td>MCP</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (HTTP and stdio)</td></tr>
<tr><td><em>MCP OAuth</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (HTTP, per user)</em></td></tr>
<tr><td>OpenAPI tools</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>unknown</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>Sub-agents</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (by design)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Permission modes</td><td>partial (confirm, yolo)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no (project trust gate only)</td><td>partial (build/plan agents, no global switch)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (plan, default, edit, auto)</td></tr>
<tr><td><em>Pattern permission rules</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (regex deny/ask/allow)</em></td></tr>
<tr><td>Auto-mode classifier</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (needs a fast model)</td></tr>
<tr><td><em>Sandbox isolation</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Docker, Podman, Singularity, others)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Docker, Podman, Seatbelt)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Docker, Modal, SSH, others)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Seatbelt, bubblewrap; none on native Windows)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (opt-in Docker (local, remote), path jail, run_js; default none)</em></td></tr>
<tr><td>Context compaction</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>Multiple context strategies</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td><em>partial (one pipeline, variants)</em></td><td><em>partial (/compress variants)</em></td><td><em>partial (pluggable engine, one built in)</em></td><td><em>partial (summarize variants)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (compaction, mnemonic, scope)</em></td></tr>
<tr><td>Todo list</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>partial (off by default in V2)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>Session persistence and resume</em></td><td><em>partial (trajectory saved, resume undocumented)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Session fork or tree</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (tree)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (API, ACP)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (no tree view)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td></tr>
<tr><td><em>Cross-session memory</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (instruction files only)</em></td></tr>
<tr><td>File undo or checkpoints</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>partial (/tree rewind only)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (off by default)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (write/edit tools only, not shell changes)</td></tr>
<tr><td><em>Hooks</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Plugins or extensions</td><td>partial (swap classes, no plugin system)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (Java)</td></tr>
<tr><td><em>Skills</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (self-created)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Instruction files</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>Custom prompt templates</em></td><td><em>partial (YAML config)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td><em>partial (no templating)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td></tr>
<tr><td>Custom commands</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (Java plugins only)</td></tr>
<tr><td><em>Scheduled tasks</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (automations (release notes only))</em></td><td><em>partial (session-scoped, expire after 7 days)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (cloud, desktop, /loop)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td></tr>
<tr><td>Non-interactive or headless mode</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (--prompt)</td></tr>
<tr><td><em>Stuck or loop detection</em></td><td><em>partial (cost and step limits)</em></td><td><em>unknown</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (off by default)</em></td><td><em>unknown</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>Verify-on-stop</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>partial (hook can request continuation)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>JSON or RPC protocol mode</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (trajectory file only)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (HTTP API, ACP)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td><em>partial (REST and SSE (jllm-web), no stdio mode)</em></td></tr>
<tr><td>Trajectory export for training</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (HTML/JSONL export)</td><td>partial (sanitized export)</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (library and batch only)</td><td>partial (raw JSONL, text export)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td></tr>
<tr><td><em>Cost and token metrics</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (HarnessMetrics, HarnessPricing)</em></td></tr>
<tr><td>Fallback providers</td><td>partial (via router)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td><td>partial (retry, default-model fallback)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td><td>partial (same-provider models)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes</td></tr>
<tr><td><em>Per-user OAuth for REST/OpenAPI tools</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td></tr>
<tr><td>IDE integrations</td><td>unknown</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (ACP (Zed and others))</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (VS Code, Zed, JetBrains)</td><td>partial (ACP server)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (VS Code, JetBrains)</td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 no</td></tr>
<tr><td><em>Native Gemini, Bedrock, Vertex clients</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (via LiteLLM)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes</em></td><td><em>partial (Bedrock, Vertex, Claude only)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (Gemini via OpenAI endpoint only)</em></td></tr>
<tr><td>Scripted tool calls (code mode)</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (codemode)</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (execute tool)</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (execute_code)</td><td>unknown</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (run_js (optional plugin, GraalJS fetched separately))</td></tr>
<tr><td><em>Hosted or managed options</em></td><td><em>unknown</em></td><td><em>unknown</em></td><td><em>partial (enterprise console (SSO, SCIM, budgets))</em></td><td><em>unknown</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (cloud sessions, managed settings)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (self-hosted only)</em></td></tr>
<tr><td>Benchmark results published</td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 yes (above 74% SWE-bench Verified)</td><td>unknown</td><td>unknown</td><td>unknown</td><td>unknown</td><td>unknown</td><td>partial (own eval module, no public scores)</td></tr>
<tr><td><em>Open source</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (MIT)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (MIT)</em></td><td><em>unknown</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (MIT)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (source not published)</em></td><td bgcolor="#c6efce" style="background-color:#c6efce;color:#000">🟢 <em>yes (Apache-2.0)</em></td></tr>
<tr><td>Release cadence (latest version)</td><td>partial (v2.4.6, 2026-07-23)</td><td>partial (v1.6.0, 2026-11-20)</td><td>partial (v2.4.0, 2026-11-23 (V2 rewrite))</td><td>partial (v0.28.4, 2026-11-22)</td><td>partial (v0.23.3, 2026-11-20)</td><td>partial (2.1.330, 2026-11-20)</td><td>partial (1.2.0, no published cadence)</td></tr>
<tr><td><em>Community size (GitHub stars)</em></td><td><em>partial (about 10k)</em></td><td><em>partial (about 123k)</em></td><td><em>partial (about 229k)</em></td><td><em>partial (about 31k)</em></td><td><em>partial (about 266k)</em></td><td><em>unknown (closed repo)</em></td><td bgcolor="#ffc7ce" style="background-color:#ffc7ce;color:#000">🔴 <em>no (small project)</em></td></tr>
<tr><td>Runtime footprint</td><td>partial (Python, agent class about 100 lines)</td><td>partial (Node 22.19+)</td><td>partial (Bun, TypeScript)</td><td>partial (Node)</td><td>partial (Python)</td><td>partial (native install)</td><td>partial (JDK 21 jar, small dependency list)</td></tr>
</table>

---

## Contents

- [Feature matrix](#feature-matrix)
- [Where jllm is weaker](#where-jllm-is-weaker)
- [Seven-way summary](#seven-way-summary)
- [jllm-harness vs mini-swe-agent](#jllm-harness-vs-mini-swe-agent)
- [jllm-harness vs Pi](#jllm-harness-vs-pi)
- [jllm-harness vs OpenCode](#jllm-harness-vs-opencode)
- [jllm-harness vs Qwen Code](#jllm-harness-vs-qwen-code)
- [jllm-harness vs Hermes Agent](#jllm-harness-vs-hermes-agent)
- [jllm-harness vs Claude Code](#jllm-harness-vs-claude-code)

## Where jllm is weaker

- **Maturity and community.** The other projects have between about 10k and 266k GitHub stars and release several versions a week or month; Claude Code is at 2.1.330, OpenCode at 2.4.0, Hermes Agent at 0.23.3. jllm is a small project (`1.3.0-jllm`) with no comparable user base, third-party extensions or public issue history.
- **Editor integration.** OpenCode, Qwen Code and Claude Code ship IDE integrations (ACP, VS Code, JetBrains); Hermes Agent has an ACP server. jllm has a terminal chat, a web chat and a Java API, but no editor plugin.
- **Model coverage.** No image input, no native Gemini, Bedrock or Vertex client, no model registry with capabilities, and no provider subscription login (only the `OAuthLogin` SPI). Pi, OpenCode, Qwen Code and Hermes Agent cover 30 to 43 or more providers; Pi, OpenCode and Hermes Agent also offer subscription logins.
- **Interfaces.** The terminal chat is line-based, not a full-screen UI. There is no stdio JSON or RPC mode (jllm-web offers REST and SSE), no messaging gateway, no browser automation, no LSP feedback and no scheduled tasks.
- **Session features.** No fork or tree, no cross-session memory, and `/undo` restores only changes made by `write_file` and `edit_file`, not shell changes. Custom commands need a Java plugin; there are no markdown prompt templates.
- **Benchmarks.** mini-swe-agent reports above 74% on SWE-bench Verified. jllm has an evaluation module (`core/jllm-eval`) but publishes no benchmark scores.
- **Hosted options.** Claude Code has cloud sessions and managed settings, OpenCode an enterprise console. jllm is self-hosted only, and its web application is a reference front end without TLS termination.
- **Isolation defaults.** The default sandbox has no isolation; Docker is opt-in, and MCP stdio servers run as the JVM user. The file-tool path jail has a residual race on Windows ([tools guide](../guides/tools.md#2-path-jail)).
- **Runtime requirements.** `run_js` needs GraalJS fetched separately; it is not in the jars ([JS guide](../guides/js.md)).
- **Unverified competitor cells.** Where this document says `unknown` for a competitor, the feature may exist; the docs read did not show it.

## Seven-way summary

|  | Core idea | Strength | Weakness vs others |
| --- | --- | --- | --- |
| *mini-swe-agent* | *Bash only, linear history, benchmark baseline* | *Simplicity, SWE-bench results, sandbox environments* | *No file tools, MCP, sub-agents or context management; relies on a disposable sandbox* |
| Pi | Minimal core, TypeScript extensions | Model layer (30+ providers), session tree, run modes, built-in MCP, large community | No permission prompts, sandbox, sub-agents or todo by design; extensions run in-process |
| *OpenCode* | *Client/server coding agent, V2 rewrite* | *Provider breadth, web and desktop UI, ACP, sub-agents, snapshots, enterprise console* | *No sandbox, no loop detection, LSP removed in V2, TypeScript/Bun embedding only* |
| Qwen Code | Terminal agent tuned for Qwen, with channels | Sandbox options, hooks, memory, sub-agents, chat channels, IDE integrations | Only Qwen account OAuth, Node only, several features experimental or off by default |
| *Hermes Agent* | *Persistent self-improving assistant* | *Memory, skills, scheduling, 25+ chat platforms, browser, LSP, many sandbox backends* | *Not supported as a library, no OpenAPI tools, dashboard chat needs WSL2 on Windows* |
| Claude Code | Anthropic's agentic coding CLI | Permissions, hooks, skills, plugins, checkpoints, IDE and cloud surfaces, Agent SDK | Claude models only, closed source, no JVM library, sandbox absent on native Windows |
| *jllm* | *Embeddable Java harness* | *Java embedding, permissions with classifier, path jail, Docker and JS sandbox, OpenAPI tools with per-user OAuth, three context strategies* | *No images, native Gemini/Bedrock/Vertex, provider logins, fork, cross-session memory, scheduling, IDE plugin; small community* |

---

# jllm-harness vs mini-swe-agent

Basis: mini-swe-agent v2.4.6 from its README and docs (sources in [harnesses-sources](harnesses-sources.md#mini-swe-agent)); jllm side from `core/jllm-harness/README.md` and the docs.

| Aspect | mini-swe-agent | jllm-harness |
| --- | --- | --- |
| *Goal* | *Minimal baseline for benchmarks (SWE-bench) and research.* | *Embeddable agent library, with CLI and web front ends.* |
| Language | Python | Java 21 |
| *Size* | *About 100 lines for the agent class.* | *Large: loop, permissions, context strategies, sub-agents, MCP, auth, hooks, plugins.* |
| Tools | Bash only. | Files (path-jailed), shell, web search and fetch, MCP, OpenAPI operations, own Java tools, sub-agent `task`, optional `run_js`. |
| *Action format* | *Bash actions parsed from the reply; v2 has text-based and tool-call model variants; the docs are not consistent on tool calling.* | *Native tool calling. Tool arguments are checked against the tool schema (off, lenient, strict).* |
| Execution | Each command is a fresh `subprocess.run`, no persistent shell. Swappable environments: local, Docker, Podman, Singularity, Bubblewrap, SWE-ReX. | Shell tool with sandbox backends: local directory (no isolation), per-chat directory, Docker (local or remote). File tools confined to the working directory by a path jail. |
| *History* | *Linear and append-only; the trajectory is the prompt. Saved as `.traj.json`; resume not documented.* | *Per-chat sessions in `messages.jsonl`, three context strategies (compaction, mnemonic masking, scope with offload and todo list).* |
| Context limits | None beyond cost and step limits. | Compaction, handoff notes, `history_recall`, retry when the provider rejects a request as too long. |
| *Models* | *LiteLLM, OpenRouter, Portkey; broad provider coverage. Streaming, image input and caching not documented.* | *Anthropic (caching, thinking) and OpenAI-compatible endpoints, including Gemini, Ollama, llama.cpp. Fallback chains, fast model for helper tasks. Text streaming. No image input.* |
| Safety | Confirm and yolo modes; otherwise relies on the sandbox. | `plan`/`edit`/`auto` modes, regex deny/ask/allow rules, approval policy, per-chat grants, classifier in auto mode (with a fast model). |
| *Control flow* | *Step and cost limits; a submit marker ends the run; malformed responses raise a format error.* | *Cancel, steering and follow-up messages, continuation of cut-off responses, hooks at agent, model-call and tool-call level. `maxTurns`, stuck detection, verify-on-stop with bounded re-entries.* |
| Extensibility | Subclass or swap agent, environment and model classes; YAML config templates. | Plugins via ServiceLoader, skills, instruction files, slash commands, custom agent types. |
| *Interface* | *CLI (Rich REPL), Python API, batch runner, trajectory inspector.* | *Java library, terminal chat, browser chat with REST and SSE.* |
| Auth | API keys through LiteLLM. | API keys, OAuth utilities (PKCE, device code, token store), MCP OAuth, per-user OAuth for REST/OpenAPI tools. |

## Where each fits

- **mini-swe-agent**: simplicity, reproducibility, benchmarking (above 74% on SWE-bench Verified with the small agent). Linear history and plain bash make runs easy to compare.
- **jllm**: embedding in a Java application with own tools and approvals; long sessions via compaction; human-in-the-loop use through permission modes and sub-agents.

## Trade-offs

- jllm's prompt, tool set and context management add variables. Benchmark results would depend more on the harness itself, and jllm has no published benchmark scores.
- jllm has no per-step trajectory export built for training. `/export` writes a readable transcript instead; mini-swe-agent writes a linear trajectory file.
- mini-swe-agent has no MCP, sub-agents, context management or file tools, and few safety controls. It assumes a disposable container.

---

# jllm-harness vs Pi

Basis: Pi v1.6.0 (repository now earendil-works/pi) from its README and docs (sources in [harnesses-sources](harnesses-sources.md#pi)); jllm side from `core/jllm-harness/README.md` and the docs.

| Aspect | Pi | jllm-harness |
| --- | --- | --- |
| *Goal* | *Minimal, extensible terminal coding agent, layered as pi-ai, pi-agent-core, pi-coding-agent.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | TypeScript (Node 22.19+) | Java 21 |
| *Providers* | *30+ providers plus OpenAI-compatible endpoints, with a model catalog.* | *Anthropic and OpenAI-compatible endpoints; Gemini through its OpenAI-compatible endpoint. No native Gemini, Bedrock or Vertex.* |
| Streaming | Text, thinking and tool-call deltas. | Text and thinking deltas from Anthropic and OpenAI-compatible clients (SSE in jllm-web). |
| *Image input* | *Yes* | *No* |
| Model registry | Typed model catalog with refresh. | Per-provider settings or manual (`HarnessPricing`). |
| *Cross-provider handoff* | *Mid-session model switch; thinking blocks dropped, tool calls kept.* | *No transform layer.* |
| Auth | API keys, provider OAuth (Anthropic, ChatGPT/Codex, Copilot, OpenRouter). | API keys; generic OAuth utilities, MCP OAuth, per-user REST/OpenAPI OAuth; `OAuthLogin` SPI but no provider login implemented. |
| *Tool progress* | *Tools push partial results.* | *Tools return once.* |
| Argument validation | Coerces and validates; errors returned to the model. | JSON-schema validation, errors returned to the model; `lenient` (default), `strict` or `off`; replaceable validator. |
| *Safety* | *No per-call approval, rules, classifier or sandbox; a project trust gate for project-local resources. The docs advise running in a container.* | *Permission modes, rules, classifier, path jail, Docker and `run_js` sandboxes.* |
| Steering | Steer and follow-up with delivery modes. | Steer and follow-up, no delivery modes. |
| *Sessions* | *Tree (`/tree`, `/fork`, `/clone`), branch summaries.* | *Flat `messages.jsonl`; save, resume, copy, `/undo` for files. No fork or rewind.* |
| Run modes | print (`-p`), `--mode json`, `--mode rpc`. | `--prompt` only; REST and SSE in jllm-web. |
| *Prompt templates* | *Markdown templates (`--prompt-template`).* | *Plugins only.* |
| System prompt | `SYSTEM.md` replace, `APPEND_SYSTEM.md` append. | `--system-prompt-file`, `--system-prompt-replace`, `SystemPromptFiles`. |
| *Input shortcuts* | *`@file` and image paste.* | *None.* |
| Settings | Global and project layering. | Layered global, project, `--settings`. |
| *Retry* | *Agent-level retry with backoff; no provider fallback.* | *`retry` event from clients; agent-level retry only on context overflow; provider fallback chains.* |
| Extensions | TS extensions: UI components, shortcuts, flags, custom providers; npm/git packages; extensions run in-process with full user permissions. Built-in MCP since v1.x. | Java `HarnessPlugin`: tools, hooks, commands, skills, agents. No UI, packages or hot reload. |

## Ahead of Pi

- Permission modes, classifier, deny/ask/allow rules (Pi has none built in).
- Docker and remote sandboxes, path jail for file tools.
- Sub-agents, todo list, web search and fetch, OpenAPI tools (Pi omits these by design or does not document them).
- Three context strategies (`compaction`, `mnemonic`, `scope`); Pi has one.
- Provider fallback chains.

## Where Pi leads

- Model layer: 30+ providers, a model catalog, image input, provider subscription logins, cross-provider handoff.
- Session tree, JSON and RPC run modes, packaged extensions, a full-screen TUI.
- Community: about 123k stars against a small jllm user base.

## Three-way summary

- **mini-swe-agent**: smallest; bash only; for benchmarks.
- **Pi**: richest model layer and coding-agent UX; intentionally minimal core with extensions.
- **jllm**: strongest on safety controls, sandboxing, sub-agents, MCP and context management; weakest on images and model abstraction.

---

# jllm-harness vs OpenCode

Basis: OpenCode v2.4.0 from the V2 docs at opencode.ai and the release notes (sources in [harnesses-sources](harnesses-sources.md#opencode)); V2 is a breaking rewrite of V1. jllm side from `core/jllm-harness/README.md` and the docs.

| Aspect | OpenCode | jllm-harness |
| --- | --- | --- |
| *Goal* | *Terminal coding agent (TUI), also web UI, desktop app and ACP editor integration.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | TypeScript (Bun) | Java 21 |
| *Architecture* | *Client/server: a local server with an HTTP API (OpenAPI 3.1, labelled experimental) and a TS/Bun SDK; clients attach to it.* | *In-process library; `jllm-web` adds REST and SSE.* |
| Providers | Broad: models.dev catalog, Bedrock, Vertex, Azure, custom OpenAI-compatible; ChatGPT and Copilot logins. | Anthropic and OpenAI-compatible endpoints; no provider logins. |
| *Model metadata* | *models.dev: limits, modalities, cost, reasoning.* | *Per-provider settings or manual.* |
| Streaming | Event stream API (streaming not stated explicitly in the docs read). | Text and thinking streaming. |
| *Image input* | *Yes (PNG, JPEG, GIF, WebP, PDFs; model must support images).* | *No* |
| Built-in tools | read, glob, grep, edit, write, patch, shell, webfetch, websearch, git worktree tools, Code Mode `execute`. LSP removed in V2; todo off by default. | Files, shell, web search and fetch, `ls`, `todo_write`, `task`, document readers, optional `run_js`. No LSP. |
| *Agents* | *`build`, `plan`, `general`, `explore` and custom agents with per-agent model, prompt, tools, permissions; background child sessions.* | *`plan`/`edit`/`auto` modes; `general`, `explore`, `plan` and custom sub-agent types.* |
| Permissions | allow/ask/deny per tool with wildcard patterns, per agent; policies layer; no classifier. | Modes, regex deny/ask/allow rules, approval policy, per-chat grants, fast-model classifier in `auto`. |
| *Sandbox* | *None; the shell runs with the host user's authority.* | *Local directory, per-chat directory, Docker; path jail on file tools.* |
| MCP | Local stdio and remote Streamable HTTP, OAuth with PKCE. | HTTP and stdio, OAuth (`McpOAuthLogin`), per-user tokens for HTTP servers. |
| *REST APIs as tools* | *No (via MCP or custom tools).* | *OpenAPI operations as tools, with per-user OAuth.* |
| Context | Checkpoint-summary compaction (automatic, background pre-write); one pipeline. | Three strategies: `compaction`, `mnemonic`, `scope`. |
| *Sessions* | *Persistent, export/import, fork via API and ACP, share links.* | *Flat `messages.jsonl`; save, resume, copy; no fork.* |
| Undo | Git-snapshot based `/undo` and `/redo`. | `/undo` restores files changed by `write_file` and `edit_file`; not shell changes. |
| *Instructions* | *`AGENTS.md` with `CLAUDE.md` fallback, instruction globs and URLs.* | *`KENDAR.md`, `AGENTS.md`, `CLAUDE.md` instruction files (also per directory), layered settings, system prompt files.* |
| Extensibility | npm or local TS/JS plugins, hooks, markdown commands, skills, formatters. | Java `HarnessPlugin`, hooks, skills, slash commands. No prompt templates, formatters or themes. |
| *Run modes* | *TUI, `run` (non-interactive, `--format json`), `serve`, ACP, web, desktop; automations (timer, cron, MCP event) in v2.4.0.* | *CLI chat, `--prompt`, web.* |
| Loop detection | None documented in V2. | `maxTurns`, interactive "continue?" question, stuck detection. |

## Where each fits

- **OpenCode**: ready-made coding agent with web, desktop and editor surfaces, broad model support and a large community (about 229k stars).
- **jllm**: embedding in a Java application with own tools, approvals and Docker isolation.

## Where OpenCode leads

- Providers and model metadata, image input, UI surfaces (web, desktop, ACP), git snapshots, background sub-agents, scheduled automations, enterprise console.
- Its own weaknesses: V2 breaking changes (LSP removed, V1 plugins not running), no sandbox, no loop detection, embedding limited to TypeScript/Bun.

## Four-way summary

|  | Core idea | Strength | Weakness vs others |
| --- | --- | --- | --- |
| *mini-swe-agent* | *Bash only, linear history* | *Simplicity, benchmarking* | *No file tools, MCP or context management* |
| Pi | Minimal core, extensions | Model layer, session tree, run modes | No permission prompts or sandbox |
| *OpenCode* | *Client/server coding agent* | *Provider breadth, UI surfaces, ACP, snapshots* | *No sandbox, TS/Bun embedding only* |
| jllm | Embeddable Java harness | Permissions, sandbox, sub-agents, MCP, OpenAPI, context strategies | No images, model registry, fork/rewind, IDE plugin |

---

# jllm-harness vs Qwen Code

Basis: Qwen Code v0.28.4 from its official docs (sources in [harnesses-sources](harnesses-sources.md#qwen-code)); source code not inspected. jllm side from `core/jllm-harness/README.md` and the docs.

| Aspect | Qwen Code | jllm-harness |
| --- | --- | --- |
| *Goal* | *Terminal coding agent tuned for Qwen models, with daemon mode, channels and IDE integrations.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | TypeScript (Node) | Java 21 |
| *Providers* | *openai, anthropic, gemini and vertex-ai protocols, many third parties; Qwen account OAuth only.* | *Anthropic and OpenAI-compatible endpoints; no provider logins. Local Qwen supported through llama.cpp, vLLM, Ollama and a `--small-model` prompt.* |
| Streaming | Yes (`stream-json` with partial messages). | Text and thinking streaming. |
| *Image input* | *Partial: modality config and vision fallback.* | *No* |
| Built-in tools | File System and Multi-File Read, shell, web search (auto-enabled for some providers), memory, todo, sub-agents, LSP (experimental, opt-in), `image_gen`. | Files, shell, web search and fetch, `ls`, `todo_write`, `task`, document readers (pdf, docx), optional `run_js`. |
| *Approval modes* | *plan, ask, auto-edit, auto, yolo; permissions allow/ask/deny; two-stage LLM classifier in auto mode.* | *`plan`, default, `edit`, `auto`, plus regex rules and a fast-model classifier.* |
| Sandbox | Docker, Podman, Seatbelt; Linux bwrap/Landlock tool sandbox. | Local directory (no isolation), per-chat directory, Docker (local or remote), path jail, `run_js`. |
| *Sub-agents* | *Yes: markdown agents, background, per-agent model and tools.* | *Yes: `general`, `explore`, `plan`, custom types.* |
| MCP | http, sse, stdio; OAuth. | HTTP and stdio, OAuth. |
| *REST APIs as tools* | *Not found in the docs.* | *OpenAPI operations as tools.* |
| Context | `/compress`, `/compress-fast`, auto-compact at 0.85; session resume, `/branch` and `/fork`. | Three strategies: `compaction`, `mnemonic`, `scope`; resume, no fork. |
| *Checkpoints* | *Shadow-git checkpoints and `/restore`, off by default.* | *`/undo` for `write_file` and `edit_file` changes.* |
| Instructions | `QWEN.md`, `AGENTS.md`, `@imports`; auto-memory. | `KENDAR.md`, `AGENTS.md`, `CLAUDE.md` instruction files (also per directory), system prompt files. |
| *Extensibility* | *Commands, extensions (also converted from Gemini CLI and Claude plugins), 25+ hook events, skills.* | *Java `HarnessPlugin`, hooks, skills, slash commands.* |
| Run modes | Interactive, headless (`text`, `json`, `stream-json`), ACP, daemon with HTTP and SSE. | CLI chat, `--prompt`, web. |
| *Embedding* | *TypeScript SDK; Python and Java SDKs are alpha; not JVM-native.* | *Java library with ports for stores, sandbox, approvals and metrics.* |
| Loop detection | `model.loopProtection`; the settings page lists `skipLoopDetection` as true by default. | `maxTurns`, interactive "continue?" question, stuck detection. |

## Where each fits

- **Qwen Code**: ready-made CLI for Qwen models with chat channels, IDE integrations, memory and checkpoints.
- **jllm**: Java embedding; also works with local Qwen models through its small-model prompt and serialized model calls (`maxParallel: 1`).

## Where Qwen Code leads

- Cross-session memory, session fork, messaging channels, IDE integrations, scheduled tasks (session-scoped), a free Qwen login.
- Its own weaknesses: Node only, several features experimental or off by default (web UI, LSP, checkpoints), docs inconsistent in places.

## Five-way summary

|  | Core idea | Strength | Weakness vs others |
| --- | --- | --- | --- |
| *mini-swe-agent* | *Bash only, linear history* | *Simplicity, benchmarking* | *No file tools, MCP or context management* |
| Pi | Minimal core, extensions | Model layer, session tree, run modes | No permission prompts or sandbox |
| *OpenCode* | *Client/server coding agent* | *Provider breadth, UI surfaces, ACP, snapshots* | *No sandbox, TS/Bun embedding only* |
| Qwen Code | Terminal agent for Qwen with channels | Sandbox, hooks, memory, sub-agents, IDE integrations | Only Qwen OAuth, Node only |
| *jllm* | *Embeddable Java harness* | *Permissions, sandbox, sub-agents, MCP, OpenAPI, context strategies* | *No images, model registry, fork/rewind, memory, IDE plugin* |

---

# jllm-harness vs Hermes Agent

Basis: Hermes Agent v0.23.3 (Nous Research) from its official docs (sources in [harnesses-sources](harnesses-sources.md#hermes-agent)); several of its 300+ doc pages were only partly read. jllm side from `core/jllm-harness/README.md` and the docs.

| Aspect | Hermes Agent | jllm-harness |
| --- | --- | --- |
| *Goal* | *Persistent, self-improving personal agent that lives on a server and is reached from chat apps, a CLI, a TUI and a dashboard.* | *Embeddable coding and tool agent in a Java application.* |
| Language | Python | Java 21 |
| *Providers* | *43 providers incl. Anthropic, Gemini, Bedrock, Vertex, custom endpoints; subscription logins (Nous, Codex, Claude Max, others).* | *Anthropic and OpenAI-compatible endpoints.* |
| Interfaces | Ink TUI and classic CLI, messaging gateway (Telegram, Discord, Slack, WhatsApp, Signal and 25+ more), web dashboard, desktop app, ACP, API server. | CLI chat, web chat (REST and SSE), Java API. |
| *Memory* | *`MEMORY.md`/`USER.md`, full-text search of past sessions, Honcho.* | *Per-chat session logs; `history_recall` in `mnemonic` strategy; instruction files. No cross-session memory.* |
| Skills | Creates and refines skills from experience; agentskills.io-compatible. | Loads skills from settings; no automatic creation. |
| *Scheduling* | *Cron-style scheduled tasks with delivery to chat platforms.* | *None.* |
| Execution backends | Local, Docker, Modal, Daytona, Vercel, Singularity, SSH. | Local directory, per-chat directory, Docker (local or remote); scripts in GraalJS without host access (`run_js` plugin); path jail on file tools. |
| *Sub-agents* | *`delegate_task`: isolated, up to 10 parallel, optional worktrees.* | *`general`, `explore`, `plan`, custom types, inheriting mode and permissions; writable types run one after the other.* |
| Tools | Terminal, files, web, browser, LSP diagnostics, `execute_code`, MCP, many integrations. | Files, shell, web, MCP, OpenAPI operations, own Java tools, document readers, optional `run_js`. No browser automation or LSP. |
| *Permissions* | *`manual`, `smart` (LLM judge), `off` approval modes; allowlist and deny globs.* | *Modes, regex rules, approval policy, per-chat grants, classifier.* |
| Context | 4-phase compressor at 50% of context, pluggable context engine. | Three strategies: `compaction`, `mnemonic`, `scope`. |
| *Research use* | *ShareGPT JSONL trajectories (library and batch runner, not the CLI).* | *No trajectory export built for training.* |
| Embedding | Importable `AIAgent` from a checkout; no supported pip package. | Java library with ports for stores, sandbox, approvals and metrics. |
| *Stop control* | *`pre_verify` hook can send the agent back, capped.* | *Verify-on-stop with a bounded number of re-entries.* |

## Where each fits

- **Hermes Agent**: long-lived assistant with memory, schedules, browser and chat-app access (about 266k stars).
- **jllm**: tool-using agent inside a Java application, with controlled permissions and OpenAPI tools.

## Where Hermes Agent leads

- Memory, self-created skills, scheduling, 25+ chat platforms, browser automation, LSP diagnostics, image input, 43 providers with subscription logins, more sandbox backends, session fork and checkpoints.
- Its own weaknesses: no supported library embedding, no OpenAPI tools, dashboard chat needs POSIX or WSL2, prompt caching only on Claude routes, smart approvals skipped inside containers.

---

# jllm-harness vs Claude Code

Basis: Claude Code 2.1.330 from the official docs at code.claude.com (sources in [harnesses-sources](harnesses-sources.md#claude-code)); the CLI source is not public, so no source inspection. jllm side from `core/jllm-harness/README.md` and the docs.

| Aspect | Claude Code | jllm-harness |
| --- | --- | --- |
| *Goal* | *Agentic coding tool: terminal, VS Code and JetBrains, desktop app, web and mobile.* | *Embeddable Java library plus CLI and web front ends.* |
| Language | Closed source; native install | Java 21 |
| *Providers* | *Claude models only, via the Anthropic API, subscription, Bedrock, Vertex or Foundry.* | *Anthropic and OpenAI-compatible endpoints.* |
| Streaming | Yes (`stream-json` with partial messages). | Text and thinking streaming. |
| *Image input* | *Yes* | *No* |
| Built-in tools | Read, Write, Edit, Glob, Grep, NotebookEdit, Bash and PowerShell, WebFetch, WebSearch, task tools, sub-agents, LSP via plugins, Monitor. | Files, shell, web search and fetch, `ls`, `todo_write`, `task`, document readers, optional `run_js`. |
| *Permissions* | *Modes default, acceptEdits, plan, auto, dontAsk, bypassPermissions; allow/ask/deny rules; model-based auto mode classifier.* | *`plan`, default, `edit`, `auto`, regex rules, approval policy, per-chat grants, classifier (with a fast model).* |
| Sandbox | Seatbelt and bubblewrap (none on native Windows; Docker commands bypass it). | Local directory, per-chat directory, Docker (local or remote), path jail on file tools, `run_js` without host access. |
| *MCP* | *stdio, HTTP, WebSocket (SSE deprecated), with OAuth.* | *HTTP and stdio, OAuth.* |
| REST APIs as tools | No (via MCP). | OpenAPI operations as tools, with per-user OAuth. |
| *Context* | *Auto-compaction, `/compact`, tool-result clearing; one summarizer.* | *Three strategies: `compaction`, `mnemonic`, `scope`.* |
| Sessions | Resume, continue, `/branch` and `--fork-session`; per-prompt checkpoints with `/rewind` (Bash-only edits not tracked). | Flat `messages.jsonl`; save, resume, copy; `/undo` for `write_file` and `edit_file` changes; no fork. |
| *Instructions* | *`CLAUDE.md` layers, `.claude/rules`, reads `AGENTS.md`; auto memory.* | *`KENDAR.md`, `AGENTS.md`, `CLAUDE.md` instruction files (also per directory), system prompt files.* |
| Extensibility | 30+ hook events, skills, commands, sub-agent definitions, plugins and marketplaces. | Java `HarnessPlugin`, hooks, skills, slash commands. |
| *Run modes* | *Interactive TUI, `-p` headless with `json` and `stream-json`, CI integrations, cloud sessions.* | *CLI chat, `--prompt`, web.* |
| Embedding | Agent SDK (TypeScript, Python) that drives the Claude Code binary; not a JVM library. | Java library with ports for stores, sandbox, approvals and metrics. |

## Where each fits

- **Claude Code**: ready-made coding agent for Claude models with IDE, desktop, web and cloud surfaces, a broad extension model and managed enterprise settings.
- **jllm**: Java embedding, non-Anthropic models, OpenAPI tools with per-user OAuth, and Docker isolation.

## Where Claude Code leads

- Surfaces (IDE, desktop, web, mobile, Slack, CI), checkpoints, session fork, cross-session memory, image input, scheduled tasks, hook breadth, LSP, hosted cloud sessions.
- Its own weaknesses: Claude models only, closed source, high cost per developer (Anthropic quotes about $13 per active day), several features tied to a claude.ai login, no sandbox on native Windows, fast release cadence changes behavior often.
