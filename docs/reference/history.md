# History

## 1. First version
The harness began as an embedded component of a host application. Its design is a conceptual Java port of the
Python course *"building a coding agent from scratch"* (decodingai): agent loop, context management, permissions,
sandbox, sub-agents and memory.

Instead of relying on a framework's model integration, the harness got its own `ModelClient` interface with two
implementations: Anthropic, and OpenAI-compatible (OpenAI, Qwen, llama.cpp, and vLLM/Ollama through the base URL).
The provider was pluggable from the start.

The first build contained:
- A ReAct loop running until a final stop reason, with handling for `refusal`, `max_tokens` and `pause_turn`.
- Tool selection in two passes (group shortlist, then dependency order) and a `request_tool_group` tool.
- A permission gate (`PermissionGate`, `PermissionMode`, `ToolKind`) and an approval service.
- Built-in tools: files (read, write, edit, glob, grep, confined to a working directory), shell, web search and web
  fetch.
- A `SandboxExecutor` interface with a local-directory backend and a Docker backend.
- Three-tier compaction: clear old tool results, then summarize with the model, then a context rewrite the user
  must approve.
- Sub-agent fan-out (`task`), persisted sessions and sub-agent run logs, a streaming event sink, and cancel through
  `CancelToken` (stops the HTTP call, backoff and tools).
- Metrics, and a usage and cost meter.
- Anthropic features: prompt caching, thinking, citations, `tool_choice`, and switching off parallel tool use.

At first everything was verified only against scripted fake models and fake HTTP servers. The first live run used
llama.cpp with Qwen (128k context). It confirmed tool calls, approvals, bypass mode and cache-read counting. It also
exposed Docker mount-path bugs and showed that a remote Docker daemon cannot mount the local working directory. As
a result the Docker sandbox falls back to the local one when Docker is missing or remote.

From the start the main design constraint was prompt-cache stability: the system prompt, tool list and message
prefix must stay byte-identical between turns and across compaction. `CacheBoundaryTest` and
`CacheUsageBoundaryTest` enforce this.

## 2. Standalone library
The harness moved into its own module, `jllm-harness`, package `org.kendar.llm.harness`, to become a
general-purpose library:
- **No framework:** Spring, JPA and Micrometer were removed, and `FrameworkFreeTest` bans them. Remaining
  dependencies are Jackson, the JDK `HttpClient` and jsoup.
- **Facade:** `AgentHarness.builder()` takes a model client, extra tools, session store, approval policy, sandbox,
  metrics, config and a system-prompt addendum.
- **Interfaces for host-specific parts:** `ChatSessionStore` (in-memory by default), `ToolApprovalPolicy` (based
  on `ToolKind` by default), `HarnessMetrics` (no-op by default). A plain `HarnessConfig` record holds the
  configuration.
- **Tools:** file, shell and web tools are registered by default; anything else is passed in by the host. The
  registry rejects duplicate names and sorts tools by group, then name, so the order the host passes them in does
  not matter. This keeps the cache prefix stable.
- **Prompt:** a generic base system prompt; host-specific policy goes in the addendum.
- No backward compatibility with older sessions.

It then got a standalone CLI (`Main`), slash commands, and a set of extension points:
- A JSON settings file with environment-variable expansion; a missing variable stops startup with its name.
- An MCP client over HTTP: remote tools are named `mcp__<server>__<tool>` and grouped by server.
- Tools generated from OpenAPI specs (swagger-parser), limited to an `allowedPaths` allowlist.
- Hooks (`PRE_TOOL`, `POST_TOOL`, `USER_PROMPT`, `STOP`, `PRE_COMPACT`; `PRE_COMPACT` no longer exists) that can allow, deny, change the input or
  add context.
- A plugin interface (`HarnessPlugin`) that can register tools, hooks, commands, skills and agents.
- Skills: names and descriptions go in the system prompt, the body is loaded on demand. `KENDAR.md` instruction
  files are loaded in a fixed order.
- Agent types: built-in `general`, `explore` and `plan`, plus custom definitions.
- MCP and API sources can be enabled, disabled and health-checked at runtime through numbered menus (`/tools`,
  `/mcp`, `/apis`), usable from any front end.

## 3. Modes, sandboxing, compaction rewrite
- **Modes** `plan`, `edit` and `auto`, each with a permission matrix:

  | Mode | Working directory | Approvals |
  |---|---|---|
  | `plan` | read-only | writes outside the scratch directory are denied |
  | `edit` | writable | file edits auto-approved, shell asks |
  | `auto` | writable | nothing asks; deny rules still apply |

  The mode is stated in a per-turn note, so switching mode does not break the cache. `/mode` opens a menu, and
  `BYPASS` was renamed `AUTO`.
- **CLI sandbox rooted at the current directory** (`CwdSandboxExecutor`, now `CwdLayout`), which rejects `..` and symlink escapes.
- **A scratch directory** per chat and agent (`<root>/<chatId>/<agentId>`), writable in every mode.
- **Compaction rewritten in the opencode style:**
  - It triggers at the context window minus the larger of 15% and 16k tokens.
  - If the provider rejects a request as too long, it compacts and retries once.
  - The summary uses a fixed template (Objective, Requirements, Decisions, Work State, Next Move, Relevant Files,
    Important Context) and keeps the most recent user messages verbatim.
  - Later compactions update the existing summary; input too large to summarize is shortened step by step.
- **A fast model role** (`ModelRole.FAST`) with a `ToolClassifier` that approves or blocks tool calls in auto mode.
  If the classifier fails, the harness asks the user.
- Context and sandbox sections in the settings file, and a `--context-tokens` CLI flag.
- All prompts moved out of the code into resource files (`prompts/*.md`).

Sub-agents, until then always read-only, became full agents:
- A sub-agent takes the parent's mode and can never be less restricted. A `readOnly` flag per agent type keeps
  `explore` and `plan` read-only.
- Approval requests from sub-agents are sent to the user one dialog at a time per chat, labelled with the agent's
  name.
- "Allow for this chat" grants are shared by the parent and all its sub-agents. They are not added to the model's
  context, so the cache is unaffected.
- Writable sub-agents run one after another in `edit` and `auto` mode to avoid concurrent edits to the same file.

Sessions and sub-agent histories can be stored as files under `.jllm/history`, and IDs are v7 UUIDs.

## 4. Mnemonic context and small models
A second context strategy was added, modelled on mnemonic-proxy:
- **`ContextStrategy` interface:** compaction moved behind it unchanged and stays the default. The strategy is
  chosen in settings, with `--context-strategy`, or in code through a factory.
- **Mnemonic strategy:**
  - The stored log is never rewritten. Each request is rebuilt from the log, saved mask decisions and segment
    boundaries, and comes out byte-identical every time.
  - Old tool results, tool inputs and thinking blocks are replaced in batches by one-line receipts with recall IDs.
    Batches only advance forward and fire when usage crosses set limits, so the cache is invalidated rarely.
  - When the window fills, a segment switch writes handoff notes. The new prompt holds the first request, the
    notes, an index of recall IDs and the recent messages.
  - A `history_recall` tool searches masked content by keyword (BM25 index in pure Java, identifier splitting, rank
    fusion). Its scope can be limited to one sub-agent run, because `task` results now carry a `runId`.
  - A guard blocks writing a receipt marker into a real file.
  - `/compact` first did nothing under this strategy; it now masks everything maskable before the latest turn.
  - Not implemented: llama-server slot per agent and checkpoint alignment.

Then came tuning against real small models such as Haiku:
- System-prompt changes.
- Recovery of a `write_file` cut off mid-output: the part already received is kept in the scratch directory so the
  model can finish it (`PartialWrite`, `PartialToolCall`).
- A `small-model.md` prompt addendum, switched on with `--small-model` or the `smallModel` setting. It gives
  concrete, checkable rules: file list first, one file per response, edit rather than rewrite, no placeholders.
- A full documentation set in `docs/`: FLOW, CACHES, EXTENDING, INTERNALS, MCP, SKILLS, TOOLS, USAGE.

## 5. Front ends and documentation split
The command line moved out of the library into its own module, `jllm-cli` (shaded runnable jar), and a browser front
end, `jllm-web`, was added: Spring Boot REST + SSE over the same `AgentHarness`, with a Vue 3 chat UI. Both use the
same sandbox, file-history stores and slash commands. Documentation followed: a short root README, one README per
module, and `docs/` as the harness reference.

## 6. Resilience and the scope strategy
Reading how other Java harnesses (AgentScope, Solon AI, jOpenAgent, ADK) handle long sessions produced a short list of
gaps that fit the small-footprint design:

- **Provider fallback.** `order` on a provider chains fallbacks; retry stays per provider (`maxRetries`).
- **Tracing hooks.** `POST_LLM` and `POST_TOOL` carry duration and token usage; an OpenTelemetry adapter is a plugin,
  not a dependency.
- **State store.** Tool grants are saved next to the chat history instead of living only in memory. Plan state needed
  nothing new: the mode is already in the session and plan files are in the scratch directory.
- **`scope` context strategy.** A whole-request budget, tool results offloaded to disk with a head and tail preview, a
  fixed trim order (offload, clear, summarize), a pinned first user message, and a `todo_write` / `todo_read` task
  list that lives outside the transcript and is appended to the latest user message when it changes.

Left out on purpose: a filesystem abstraction behind the file tools, per-call user and session identity,
distributed state, multi-tenancy, sandbox snapshots.

## 7. Restoring chats

Chats lived in memory; only a write-only copy of the log went to disk, so a restart lost every chat and left the
mnemonic and state files orphaned. `FileChatSessionStore` replaced the mirror: a `session.json` row per chat, the log
read back lazily, append-only writes while the log grows. Because the context state was already keyed by chat and agent
id, restoring the row and the log restores the context too, and the next request is the same as without the restart.
Decisions: the chat id is always shown and the user chooses (startup question or menu, `--resume`), never a silent
restore; chats are kept until deleted (`/chats delete|clean`, optional `chatRetentionLimit`); enumeration is a directory
scan, no index; one process per history root (lock file); an interrupted tool call is closed with a synthetic result.
No backward compatibility with the old `messages.jsonl`-only layout. The web UI shows the id, offers to continue the
remembered chat and marks an interrupted run. Shell commands that add no information (`mkdir`, `rm`, `cd`, ...) are
replaced in the context by a stub after their turn, in batches because the rewrite is a cache miss; the originals
stay in the log as `ignore` reference lines. The qualifier asks the cheap model at temperature 0, with the command
only. Not done on purpose: restoring the last `/model`.

## 8. Cleanup and comparison

- **Static-analysis cleanup** across `jllm-cli` and `jllm-harness`: method references instead of lambdas, `equals`
  instead of `==` for the CLI's `EOF` sentinel (now a plain constant), and small fixes in the context, tools,
  session and provider classes. No behaviour change intended.
- **Portable tests.** `McpStdioClientTest` no longer needs `sh`: the fake MCP server is a Java class run by the
  current JVM. `DockerSandboxExecutorTest` (since removed with the per-command docker runner) was reworked so it runs on Windows too.
- **Docs.** `docs/HARNESSES-COMPARE.md` (now `docs/comparisons/harnesses-compare.md`) added: a feature matrix of jllm against mini-swe-agent, Pi, OpenCode, Qwen
  Code and Hermes Agent (competitor columns are from memory and unverified). The root `history.md` was removed in
  favour of this file; README links updated.

## 9. Hardening review

A full code review produced about 70 findings, each decided (fix, design choice, or accepted) and then fixed in
parallel by area:

- **Security:** the file-tool jail checks real paths (symlinks, Windows junctions, `.git` aliases), checkpoints and
  backup unpacking can no longer write outside the work dir, REST tool paths are resolved before allow/deny checks,
  project settings can only add deny rules and need a trust answer before starting stdio MCP servers, web slash
  commands are scoped to the chat owner (admin-only for global ones), tool/MCP/skill toggles are per chat, and taint
  from untrusted content lasts while that content is in context.
- **Robustness:** ordered and complete event delivery, atomic writes for files, session logs and state, per-path edit
  locks, charset and line-ending preserving edits, bounded output, fetch and read sizes, a log that always pairs
  `tool_use` with `tool_result`, sub-agents reporting partial, refused or cancelled outcomes, MCP sources that reconnect
  once and are then disabled, and a startup failure on tool-name collisions.
- **Context:** fixes to compaction (summaries built from unpruned messages, the fixed part counted once, previous
  summary kept on retry, cuts inside long turns), thinking blocks stripped after rewrites, sub-agent task and recall ids
  kept under the mnemonic strategy, and a corrupt mnemonic state quarantined instead of failing every turn.
- **Layout:** the per-workdir runtime directory `.kendhar` became `.jllm`, the same name as the configuration
  directory. Existing `.kendhar` directories are not migrated.

New settings: `files.maxReadBytes`, `web.fetchMaxBytes`, `sandbox.outputLogMaxBytes`.

## 10. Web commons
`jllm-web` was split in two. `jllm-web-commons` holds the chat and file API, the harness wiring and the `jllm.*`
properties, and asks the application for `WebAuthentication`, `ChatAccess` and `HarnessIdentity`. `jllm-web` keeps the
startup, the UI and the sample sign-in and permissions that implement them.

## 11. Users and administration
`jllm-web` became a reference implementation of a multi-user front end; `jllm-web-commons` was not touched.
- **Real sign-in:** login and password instead of the picker. The default password was then the hex SHA-256 of the login (now the login itself);
  stored passwords are PBKDF2 hashes. The cookie holds a random in-memory session token instead of the user id, and
  repeated failures lock the login for a while.
- **Administration page** for the `admin` role: users, roles and global skills. The `admin` role is fixed. A role's
  tool list takes `*` patterns, so a whole MCP server or REST API is granted with one entry; the editor lists every
  tool, MCP server and REST API of the harness, disabled ones too.
- **User settings:** every user changes their password, switches off tools their role allows (never more than the
  role) and switches skills on or off, for all their chats rather than per chat. Users write their own skills; admins
  write global ones.
- **Harness:** `UserPolicy` gained the user's extra skills and a filter over the settings skills; the `skill` tool
  exists whenever a user policy resolver is set. Existing chats are not migrated.

## 12. Loop guards and streaming
A second comparison, against the harnesses in the agent-harness survey (OpenHands, Gemini CLI, Claude Code, Codex,
Hermes Agent, mini-swe-agent), picked three gaps, planned in a root `extend.md` (no longer in the repository) and then implemented.
- **Stuck detection:** a per-run detector of repeated calls, repeated errors, A-B-A-B alternation and malformed
  output. The first hit adds a corrective note, the second ends the run as `STUCK` (headless) or asks (interactive),
  so a headless embedder no longer spends its whole `maxTurns` budget on a loop.
- **Verify-on-stop:** `STOP` moves inside the loop, fires for sub-agents too, and can return `continueWith` to send
  the agent back to work, bounded by `maxReentries` (then `UNVERIFIED`). A settings-level command hook (`mvn -q
  test`) runs in the sandbox and makes the common case one line of configuration.
- **Model streaming:** `ModelClient.stream` with a fallback to `complete`, Anthropic and OpenAI-compatible SSE
  parsing that assembles a response byte-identical to the non-streamed one, `text_delta` events coalesced for SSE,
  retry and provider fallback only before the first delta.

- **API:** `HarnessConfig.Loop` (stuck and verify settings), `HookResult.continueWith`, `HookContext.stop` with agent
  and attempt, new outcomes `STUCK` and `UNVERIFIED`, events `stuck`, `verify_failed`, `text_delta`, `thinking_delta`,
  `text_reset`, and `outcome` in `run_finished`. The CLI and the web UI show the new events.

`HARNESSES-COMPARE.md` gained the stuck-detection and verify-on-stop rows, and its jllm column lists the three
features. Model streaming moved out of `FUTURE.md` (since removed; see `docs/enterprise/roadmap-and-limits.md`).

## 13. Plugins, JavaScript tool and module layout
- **`run_js` tool** (module `jllm-js`): the agent runs JavaScript in a sandbox with no host access (no files, network,
  processes or `require`). Scripts reach tools only through a `tools` object. Defaults: 30 s timeout, statement cap,
  50 KB output limit.
- **Plugins from a directory:** plugins load from `pluginDir` (default `./plugins`) and are enabled per id in the
  settings. `pdf` and `docs` became plugins that can be switched on or off; each builds as a standalone jar.
- **Sandboxing and approvals:** scratch-directory jail for scripts and agents, an environment-variable whitelist, an
  approval service for tool calls, web host allow-lists with wildcards, invalid-certificate handling, and per-run or
  per-chat storage quotas. Sandbox backends: local, Docker, remote Docker, CWD and local-directory executors.
- **Tool catalog:** one catalog with per-scope tool sets; the fixed tool set keeps the prompt prefix stable. The agent
  loop was rewritten with cancellation support.
- **Web settings:** system-prompt files (append or replace), plugin enable/disable, and prices for cost estimates.
- **Module layout:** the library split into `core/` (`jllm-commons`, `jllm-harness`, `jllm-fs`, `jllm-webbrowse`,
  `jllm-apimcp`, `jllm-test-support`) and `plugins/` (`jllm-pdf`, `jllm-docs`, `jllm-js`). Version `1.1.0-jllm`.

## 14. OAuth for MCP servers and REST APIs
Authorization code, device code and client credentials, shared or per user. The browser flow runs through `/api/oauth`
and `/oauth/callback` (single-use state, 10 minutes, bound to the user who started it). A `TokenStore` keeps tokens in
an AES-GCM encrypted file (`JLLM_TOKEN_KEY`, rotation with `JLLM_TOKEN_KEY_OLD`) or in the OS secret store (Keychain,
DPAPI, Secret Service). A missing login gives a `login_required` tool result rather than a changed tool list, so the
cache is unaffected. Visibility lists (`allowList`/`denyList` for MCP, `allowedPaths`/`denyPaths` for REST) keep hidden
tools away from the model. Upload limits were lowered to 100 MB per file and 200 MB per request
(`JLLM_UPLOAD_MAX_FILE_SIZE`, `JLLM_UPLOAD_MAX_REQUEST_SIZE`).

## 15. Storage behind interfaces
Web users, roles, skills, login sessions and harness settings now go through interfaces, so another backend, such as
SQL, can be plugged in without touching controllers or the harness. File implementations stay the default
(`jllm.store=file`).
- **Core:** `SettingsSource` / `SettingsLayer` (global, then project, then explicit; a later layer wins) with
  `FileSettingsSource`; `ProjectMcpTrust` as an interface, with answers tied to a SHA-256 hash of the definition;
  `UserSkill` and `SkillText` for skills whose text is read on every load.
- **`jllm-web-commons`:** `UserRepository`, `RoleRepository`, `SkillRepository`, `LoginSessionRepository`, the `Role`
  record, and an optional token store supplied by `HarnessIdentity`.
- **`jllm-web`:** the file implementations (`FileUserRepository`, `FileRoleRepository`, `FileSkillRepository`,
  `InMemoryLoginSessionRepository`). `webauth/` and `tmp/` moved under `runConfig/`.
- A MySQL store is documented in the `jllm-web` README, not shipped.

## 16. Workflows, web plugin host and OIDC
- **`jllm-workflows` plugin:** a flow engine with run, resume and wait-for-input, nested flows, clarify and classify
  steps, task approvals, ownership and unattended runs; a flow store, run store and validator; a CLI and slash
  commands. Flows use an agentic task path in `AgentLoop` (`AgentOneShotModel`).
- **Plugin API in core:** `OneShotModel`, `PluginEvents`, `PluginStorage`, `ServiceRegistry`, `WebContext`, `WebRequest`,
  `WebResponse`, `WorkflowApi`.
- **Web plugin host:** `WebPlugin`, `WebPluginHost`, `PluginController` (starter; `PluginApi` in web-commons) and file-backed `PluginStorage`. The
  `jllm-workflow-web` module exposes the API only; all UI lives in `jllm-web`.
- **OIDC sign-in** in `jllm-web` (`OidcClient`, `OidcController`, `OidcProperties`).
- **UI:** a flow editor (canvas, step, task and settings forms), workflow and skills panels, with parts of the admin
  and settings panels moved to shared components. Per-role permissions for flows and a sample `develop-app` flow.
- Docs: `guides/workflow.md`.

## 17. Distribution and documentation
- **Release:** `release/release.sh` and `release.ps1` build a distribution from `release/template` (run scripts,
  sample settings, users, roles and an example flow). Poms carry the metadata for Maven Central, and signing and
  publishing live in the `deploy` profile (`docs/sign_and_distribute.md`).
- **Docs reorganized** into `getting-started/`, `guides/`, `reference/`, `enterprise/` and `comparisons/`, with
  `why-jllm.md`, an index, a JVM landscape comparison, a coverage report and enterprise pages (security model,
  identity, governance, observability, deployment, roadmap and limits). `FUTURE.md` was removed.
- The `jllm-js` plugin's own assembly descriptor was dropped in favour of the shared build.

## 18. Evaluation module (`jllm-eval`)
A test-only module, outside the default build (`eval` Maven profile), that runs the harness features against real
models.
- **Cases:** one suite per feature of `FEATURES.md` (harness core, plugins, prompt-cache layout, context strategies),
  graded by deterministic checks (files, tool calls, server state) and an LLM judge with rubrics (`no-false-claim`,
  grounded correctness). Groups `unit`, `smoke`, `full`, `strategy`, `compaction`. Features without cases are listed
  with their reason; `FRAGILE_CASES.md` names the cases that can fail for reasons unrelated to the feature.
- **Roles:** target, judge and fast model, each with its own provider, model, endpoint and key; a connection check
  before the run; a shared `RequestPacer` keeps rpm limits with parallel test classes; a judge cache.
- **Scripts:** `run-smoke.sh` / `run-smoke.ps1` with presets in `scripts/envs/` (Anthropic, NVIDIA NIM, Ollama,
  llama.cpp with a Sonnet judge); `docker/llm.sh` / `llm.ps1` deploy a llama.cpp model on a remote GPU host.
- **Results:** per run `run.json`, per-case results and traces, `report.md` and `summary.json`; per-model baselines
  (`UPDATE_BASELINE`); a merged comparison of the latest run of every model; secrets masked in all outputs;
  `*_MODEL_NAME` names a server alias in results.
- **History:** every run adds a recap to `eval-results/history/` and a row to `INDEX.md` (pass rate, pass^k,
  tokens per trial, change against the baseline). First baselines, 148 cases: `claude-haiku-5-5` 99.3%,
  `Qwen3.6-35B` on llama.cpp 98.0% (pass^k 94.6%), both judged by `claude-sonnet-5-5`.

## 19. Fixes found by the eval
The first runs exposed model-facing wording and guard rails that were then tightened:
- **Denials:** a hook or permission-rule denial now tells the model not to reach the same result another way and to
  report the denial; a failed stop check tells it to stop and say what is missing rather than invent content.
- **Shell writes:** a `deny` rule on `write_file`/`edit_file` also denies a `bash` command writing a matching path
  (redirection, `tee`, `touch`, `cp`/`mv`, `dd of=`, `sed -i`, PowerShell file cmdlets), via `ShellWrites`.
  `web_download` is gated like `write_file`.
- **Plan mode:** a write result says the project file was not changed (the copy went to the scratch dir); a path
  refusal says the limit holds for every tool, so the model does not retry through the shell.
- **Truncated output:** offloaded tool output and `history_recall` pages say how much is not shown and to search it
  before concluding something is absent; `history_recall` `{"id", "query"}` searches inside one text.
- **Compaction:** an updated summary keeps the `Objective`, `User-Stated Facts` and `Work State` sections and restores
  dropped facts unless a bullet with the same label replaced them.
- **Model calls:** `RetryBudget` caps retry sleeps at 10 s per call and errors carry the rate-limit headers;
  `SimpleCompletion` runs helper calls with thinking off; the Anthropic client uses adaptive thinking with
  `output_config.effort`, drops reasoning blocks voided by a mask (`block_binding`) and leaves out empty system parts.
- **Tool groups:** `request_tool_group` lists every group with its tools and stays offered once all are enabled, so the
  tool list (and the cache) never shrinks.

## 20. Shell, instruction files and todos
- **Shell:** `sandbox.shell` picks the shell of the `bash` tool (default `cmd` on Windows, `zsh` on macOS, `bash`
  elsewhere; `sh` in Docker). The shell is described in the `bash` tool description (`shellDescription()`), not in the
  per-turn note, so a chat without the tool is never told about one. `LocalShell` and `ShellPaths` are shared with the
  workflows plugin.
- **Instruction files:** default `KENDAR.md`, `AGENTS.md`, `CLAUDE.md`, duplicates by content skipped.
  Directory-scoped instruction files (`ScopedInstructions`) are added to the tool results of the turn that first
  touches their directory, once per chat, keeping the cached prefix (`files.scopedInstructions`,
  `scopedInstructionsDepth`, event `instructions_loaded`).
- **Todos:** `todo_write` / `todo_read` moved to `org.kendar.llm.harness.todo` and are offered with every context
  strategy unless `context.todos: false`.

## 21. Content scanning, GPT-6.2 and workflow gates
- **Suspicious content:** `safety.ContentScanner` scans skills, instruction files, agent prompts and settings text for
  hidden Unicode, injection phrasing, download-and-run, encoded blobs, data URLs and secret exfiltration. It only
  reports: CLI lines, a web banner (`GET /api/content-warnings`) and a live `content_warning` event for scoped files;
  never stored nor sent to the model.
- **`openaiGpt62` provider:** `OpenAiResponsesModelClient` on the Responses API, which GPT-6.2 needs for tool calls.
  System parts sent as two `developer` inputs (no `instructions`) to keep them cached, stateless with encrypted
  reasoning kept as `thinking` blocks, no `temperature`, `thinking` as `reasoning.effort`.
- **Gates:** `gate.GateResultsHandler` maps one typed value (boolean, string, number, exit code) to a response of a
  closed list, never reading prose. The workflows plugin adds `BuiltinActions`: the `gate` chooser and the
  `run-command` action (exit code and output tail); steps take a `with` object of parameters, checked at save time;
  `Registry.ExplainingChooser` journals the reason of a choice. The sample `develop-app` flow reaches `done` only after
  a real test exit code 0.

## 22. Hardening 4: shared deployments
A second review of the web application, aimed at running it for several users. No backward compatibility was kept:
signatures changed and callers were fixed.
- **Network and defaults:** `web_fetch`/`web_download` got an address guard (`blockLocalHost`, `blockExtraAddresses`)
  checked on the first URL and on every redirect hop; DNS pinning is a known gap. The release template binds
  `127.0.0.1` and no longer turns admin approvals off. `jllm.web.use-real-password` replaces default passwords with
  random ones at startup (the admin one is logged once). A user on the default password is refused server-side except
  for the password change, logout and auth paths; the loopback exemption is off when `server.address` is not loopback
  or a forwarding header is present. The remote sandbox client accepts `https` (trust store, `allowInsecure`); the
  Python daemon still speaks plain HTTP and says so in a `TODO`. A Content-Security-Policy and two other security
  headers are sent on every response.
- **Auth gate:** `Gate.isOpen` fails closed and strips the context path (a `/jllm` prefix used to skip authentication);
  chat ownership uses the path variable Spring matched instead of a regex on the raw URI. `TokenKeyGuard` warns
  without `JLLM_TOKEN_KEY` and refuses to start for shared setups unless `allow-unencrypted-tokens` is set.
- **Concurrency:** `AgentService` uses `ReentrantLock`s and a bounded queue per subscriber drained on its own virtual
  thread; a subscriber that overflows is dropped with a `subscriber_dropped` event and replays from the run's events.
- **Permission core:** `ToolExecutor.decide` became a pure function, checked against the old logic over the whole
  input space. Taint now survives compaction until the next user message (`ClearedResult.TEXT` is the one shared
  marker). Path rules resolve real paths on every OS, so a symlink cannot dodge a deny rule.
- **Model clients:** the non-streamed retry, backoff and 401-refresh loop moved into `StreamingCall.send`
  (about 130 lines fewer across the three clients).
- **SQL storage:** new modules `jllm-sql` (plain JDBC, HikariCP; no JPA) and `jllm-sql-spring-starter`. The dialect
  (SQLite default, MySQL, PostgreSQL) comes from the JDBC URL alone; with the starter on the class path every store
  (users, roles, sessions, tokens, settings, chats, state, mnemonic, run logs, plugin documents incl. workflows) is
  in SQL, without it the file stores stay. Chat listing filters by principal in the query (`ChatVisibility`).
  `users.role` is a foreign key to `roles` (`ON DELETE RESTRICT`, `admin` is a seeded row); deleting a role still
  in use is a 409 naming the users, in both the file and the SQL store. The schema is one script per
  dialect (`schema.sql`); changing it means deleting the database.
  MySQL and PostgreSQL drivers are downloaded with SHA-256 manifests into `.downloaded-libs/` (checkout, replacing
  `.graal/`) or `lib-ext/` (release), never bundled.
- **Tests and docs:** eval cases for the SSRF guard, taint across compaction, symlinks, admin approval and web
  injection (`FEATURES.md` section 5); real-HTTP integration tests over SQLite; an enterprise setup guide and an
  OpenHands comparison.
- **Left as `TODO` comments:** constant-time default-password check and the PBKDF2 iteration cap (`PasswordHasher`),
  TLS in the sandbox daemon. Not started: rate limits and spend caps, audit log, health and metrics, backups.

## 23. Sandbox per role
Until now the web had one sandbox for everybody, chosen at start with `jllm.sandbox`, and the default `local` ran `bash`
on the server. An administrator now chooses it per role. No backward compatibility was kept: `Role` and
`Role.admin` got a parameter, and the callers were fixed.
- **Kinds:** `none` (the default), `local`, `docker-isolated` (hardened profile, network only when granted) and
  `docker-open` (standard profile). Docker roles also pick the container scope (`chat` or `user`). `RoleSandbox` holds
  the choice; `SandboxKind` parses it, and the old plain value `docker` is rejected.
- **Router:** `RoutedSandboxExecutor` picks the runner of each call from the chat's user, then their role, with a cache
  of two seconds. `DisabledSandboxExecutor` serves `none`: the chat directories exist, but every file tool and `bash`
  throws `SandboxDisabledException`, which the model sees as the error of that call and which never offers to run
  locally. A chat without a user, an unknown role or a failing lookup resolves to `none`. Building the container runner
  does not touch docker, and the orphan sweep at start ignores a missing docker, so a role set to docker on a host
  without it affects only its own chats.
- **Web:** `jllm.sandbox` is ignored (a warning is logged); `jllm.docker-*` remain the defaults of the containers.
  `HarnessIdentity.sandboxes()` gives the sandbox of a user id. The Files tab answers 403 for a role without a sandbox.
  `run_js` does not depend on the kind: it runs in the server's engine, and only the file calls of its scripts follow
  the sandbox.
- **Storage:** `roles.settings` and `users.settings` are free-text JSON columns. The role's sandbox lives in
  `roles.settings` (`{"sandbox": {kind, scope, network}}`) or in the `sandbox` object of the role file. `users.settings`
  is a validated JSON object (`UserRepository.setSettings`), not read by anything yet. A database created before them
  gets the columns by `ALTER TABLE` at start, the one change of the schema with an upgrade path, since a missing
  column must not stop the server. A value that does not parse reads as no sandbox.
- **Admin UI:** the role editor has a Sandbox section (kind, scope, network, a plain warning for `local`). The admin
  role can be set too.
- **Web fixes found on the way:** the inline import map was blocked by the Content-Security-Policy, so the page
  failed to start; the components now import Vue by relative path. A user on the default password is now sent to
  Settings after sign-in, and the chats load only after the password change instead of failing with 403.
- **Not done:** a list of allowed container images per role with their mode (isolated or exposed); a persisted
  per-user docker host and per-role clean-up policy; tarball download; migration of workspaces on a scope change.

## Overall direction
- **Where it lives:** an embedded component, then a standalone library with a CLI, then a web front end, then a
  plugin host with workflows and pluggable storage.
- **Model target:** Anthropic, then local llama.cpp/Qwen, then small models, then OpenAI's Responses API (GPT-6.2).
- **Context management:** three-tier compaction, then opencode-style compaction, then a second strategy that never
  rewrites the log, then a budgeted one with offloading and a task list.
- **Permissions:** one approval flag, then a mode-by-tool-kind matrix, then sub-agents inheriting the parent's
  permissions, then per-user roles narrowed by each user's own settings, then a sandbox kind per role.
- **Hardening:** a full review pass closing jail escapes, races and unbounded reads before adding features, then a
  second one for shared deployments (address guard, fail-closed gate, token key, SQL storage).
- **Verification:** scripted fake models, then a live eval suite against real models with a recorded history and
  baselines, whose failures drive the wording of what the model is told.
- **Unchanged throughout:** the prompt-cache prefix must stay byte-stable.
