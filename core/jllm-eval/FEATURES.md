# jllm-eval: feature inventory

Step 1 of the eval plan. Scope: harness core (jllm-harness, jllm-fs, jllm-apimcp, jllm-webbrowse), plugins pdf, docs, js, workflows, the three context strategies and the prompt-cache layout. Out of scope: web app, CLI, `jllm-workflow-web`.

Sources: README, `docs/reference/{internals,flow,usage,caches}.md`, `docs/guides/{tools,mcp,skills,extending,workflow,js,sandbox}.md`, module READMEs, and the code (class and event names verified against `src/main`). "Failure modes" are hypotheses about real models, to be confirmed by the first full run.

## 0. Shared observables

### Event contract (`AgentHarness.subscribe(chatId, afterSeq, listener)`)

| Event | Data |
|---|---|
| `run_started` | `text` |
| `run_queued` | run slot taken by another chat (`serializeRuns`) |
| `text_delta`, `thinking_delta`, `text_reset` | live only, `seq` 0, never replayed |
| `text` | `text` of one complete text block |
| `usage` | `inputTokens`, `outputTokens`, `cacheReadTokens`, `cacheCreationTokens` |
| `tool_call` | `id`, `name`, `input`, `parent` (null for model calls, `run_js:<toolUseId>` for calls from a script) |
| `tool_result` | `id`, `name`, `isError`, `output` (cut at `output.eventMaxChars`, 2000), `parent` |
| `approval_request` | `id`, `chatId`, `type` (`tool`, `context`, `continue`, `stuck`, `host`, `insecure_cert`), `title`, `preview`, `proposed`, `agent`, `writeDir` |
| `approval_unavailable` | `tool`: an ask became a deny because the loop is not interactive |
| `classifier` | `tool`, `verdict` (`SAFE`, `UNSAFE`, `UNSURE`) |
| `compaction` | compaction: `tier`, `cleared` / `estimatedTokens`, `inherited`, `inTurn`; mnemonic: `strategy`, `kind`, `masked`, `savedTokens`, `estimatedTokens`; scope: `strategy`, `estimatedTokens` |
| `todos` | `todos` (`id`, `content`, `status`) |
| `instructions_loaded` | `file`: a directory-scoped instruction file was added |
| `content_warning` | `file` and the findings: suspicious content in a loaded file (never stored) |
| `stuck` | `pattern` (`repeat`, `same_error`, `alternation`, `malformed`, `verify_repeat`), `tool`, `count`, `action` (`nudge`, `continue`, `stop`), `message`, `agent` |
| `verify_failed` | `agent`, `attempt`, `message` |
| `retry` | `status` (0 = network), `attempt`, `max`, `delayMs`, `message` |
| `steer_queued`, `steer_applied` | `text`; `kind` (`steer`, `followup`) |
| `login_required`, `login` | `source` (`api:<n>`, `mcp:<n>`), `agent`; `source`, `ok`, `message` |
| `warning`, `error` | `message` |
| `cancelled` | none |
| `plan_ready` | `message` |
| `run_finished` | `cancelled`, `outcome` (`completed`, `stuck`, `unverified`, `max_turns`, `truncated`, `refused`, `cancelled`, `failed`) |

Every event has a per-chat `seq` (deltas: 0). Buffer: 2000 events, cleared at each `start`.

### Other observables

- **Model requests** (request recorder wrapping `ModelClient`): `ModelRequest` = `pinnedSystem`, `extraSystem`, `messages` (Anthropic-shape JSON), `tools`, `maxTokens`, `toolChoice`, `disableParallelToolUse`, `temperature`. Plain helper calls (`simpleComplete`) are separate requests to the fast model: tool selector, classifier, noise qualifier, summaries.
- **Message log**: `ChatSessionService.messages()`; on disk `<history>/<chatId>/session.json`, `<agentId>/messages.jsonl`, `<agentId>/mnemonic.json`, `state/{grants,todos-<agent>}.json`, `subagent-<runId>/{messages.jsonl,result.txt,run.json}`.
- **Workspace**: `workDir(chatId)`, `scratchDir(chatId)` (`<chat>/<agentId>`), `<scratch>/offload/`, `<scratch>/partial/`, `<scratch>/bash-N.log`, `<workdir>/.jllm/checkpoints/<chatId>/<turn>/`.
- **Hook contexts** (`HookEvent`): `USER_PROMPT`, `PRE_AGENT`, `PRE_LLM`, `POST_LLM`, `PRE_TOOL`, `POST_TOOL`, `POST_AGENT`, `STOP`.
- **Tool-result error texts** (stable strings): `Unknown tool '<n>'`, `Tool unavailable for agent`, `Cancelled by user`, `Denied by a hook: <reason>. <what> is not permitted by any means (no other tool, no shell command); report this to the user instead.`, `Denied by permission rule: <rule>. <what> is not permitted by any means (no other tool, no shell command); report this to the user instead.` (`<what>` is `Writing <path>`, `Reading <path>` or `This <tool> call`), `Denied by permission policy (mode <m>)`, `The user rejected this tool call`, `Tool disabled for this chat: <n>`, `Invalid arguments for '<tool>': ...`, `Path escapes the work dir: <p>`, `Not executed: your response was cut off ...`, `Not executed: the call was interrupted.`, `Error: <message>`.
- **Run snapshot**: `AgentHarness.contextTokens(chatId)`, `cost(session)` (needs `builder.pricing(HarnessPricing)`), `subAgentRuns()`, `pendingApprovals(chatId)`, `isRunning(chatId)`.

### Doc and code differences found (historical snapshot from step 1; may be out of date)

- `grep_code` (group Files, READ_ONLY, in `FileSystemAgentTools`; used by `explore`) is not in the `tools.md` table.
- `docs/guides/js.md` lists `sub_agent` as never in the script library; the tool is named `task`.
- `core/jllm-harness/README.md` says REST uses static headers only; `mcp.md` documents OAuth for REST.
- `approval_unavailable`, `steer_queued`, `steer_applied`, `login`, `retry` are emitted but missing from the `usage.md` event table.
- `ToolOutputSanitizer` (`output.sanitize`: `off`, `wrap`, `strict`; `output.taintGating`) is in code and `docs/enterprise/security-model.md`, not in the reference docs read for this step.

---

## 1. Harness core features

### 1.1 Agent loop and run lifecycle
- **Entry**: `AgentHarness.start(chatId, text)`, `AgentService.start`, `AgentLoop.run`; `sessions().create`, `subscribe`, `cancel`, `compact`.
- **Config**: `maxTurns` (40), `maxTokens` (builder default; CLI 32000), `serializeRuns` (true), `parallelToolUse`, `execution.maxParallel` (1, `LimitedModelClient`), `approvalTimeoutSeconds` (600).
- **Signals**: `run_started` -> (`run_queued`) -> `text`/`usage`/`tool_call`/`tool_result` per turn -> `run_finished{outcome}`; second `start` on a busy chat throws `AgentService.RunAlreadyActiveException`; at `maxTurns` an `approval_request` of type `continue`, else `warning` "Stopped after N turns"; the log is valid at every save (every `tool_use` has a `tool_result`); one user message per tool-result round; `plan_ready` after a plan-mode answer; first message of a `New chat` sets the title (60 chars).
- **Failure modes**: (1) model ends the turn with a plan but no tool call ("I will now ..."), answer not grounded in tool results; (2) wrong or empty `tool_result` ignored, model answers from memory; (3) parallel tool calls in one response conflict (two writes to one file) or weak models emit one call per turn and hit `maxTurns`.

### 1.2 Cut-off responses and local-server quirks
- **Entry**: `AgentLoop` stop-reason handling, `PartialToolCall`, `PartialWrite`, `OpenAiCompatModelClient`.
- **Config**: `maxTokens`; `MAX_CONTINUATIONS` 3; local servers (llama.cpp reports `length` for window full and output limit).
- **Signals**: `warning`; user message starting `[harness: response cut off]`; cut `tool_use` gets `Not executed: your response was cut off ...`; cut `write_file` lines saved to `<scratch>/partial/<callId>-<file>`; a response that is only a cut call retried once, twice ends the run (`run_finished.outcome=truncated`); output below 90% of `maxTokens` reported as full context window; unclosed `<tool_call>` text turned into `tool_use`; broken JSON kept as `{"_raw_arguments": ...}`; `refusal` / `model_context_window_exceeded` -> `warning` and return; `pause_turn` continues.
- **Failure modes**: (1) model rewrites the whole file instead of continuing with `append: true`; (2) model loses the part already written after a cut; (3) Qwen XML or raw-text tool calls not parsed, call treated as text.

### 1.3 Steering and follow-up
- **Entry**: `harness.steer(chatId, text)`, `harness.followUp(chatId, text)`, `/cancel`.
- **Signals**: `steer_queued`, `steer_applied{kind}`; queued text enters the log only when applied (after the current tool round, or when the agent would finish); on an idle chat both start a run; `/cancel` clears queues.
- **Failure modes**: (1) model ignores the steer text and finishes the old plan; (2) steer arrives during a long tool call and is applied late; (3) follow-up applied to a final answer causes a repeat of finished work.

### 1.4 Model clients, retries, fallback, streaming, thinking
- **Entry**: `ModelClients.anthropic`, `ModelClients.from`, `ModelClients.ollama`, `ProviderCatalog`, `FallbackModelClient`, `AnthropicModelClient`, `OpenAiCompatModelClient`, `OpenAiResponsesModelClient`, `ThinkingModelClient`, `TemperatureModelClient`, `/model`.
- **Config**: `providers[]` (`type`, `baseUrl`, `model`, `maxRetries`, `timeoutSeconds`, `contextTokens`, `smallModel`, `temperature`, `order`, `thinking`, `stream`), `defaultProvider`, `fastModel`.
- **Signals**: `retry` events; `text_delta`/`text_reset`; `usage` (input excludes cached tokens; `cacheReadTokens` from `prompt_tokens_details.cached_tokens` or Anthropic usage); outbound `POST /v1/messages` or `POST /chat/completions`; fallback only before the first delta; context-overflow errors routed to the strategy's `onOverflow`; `reasoning_effort` retried down or dropped when rejected; temperature retried without when rejected; Anthropic never sends temperature.
- **Failure modes**: (1) server sends no usage in a stream, so cache and cost checks are empty; (2) server rejects `reasoning_effort` or `temperature`, the retry path hides a latency spike; (3) stream breaks mid-way, `text_reset` and duplicated tool calls.

### 1.5 Custom tools and input validation
- **Entry**: `new AgentHarnessBuilder().tools(...)`, `HarnessTool`, `SimpleTool`, `HarnessToolSet`, `plugin(...)`/`PluginRegistrar.tool`, `toolInputValidator`, `addToolInputValidator`.
- **Config**: `output.validation` (`off`, `lenient` default, `strict`), `/validation` per chat; names `[a-zA-Z0-9_-]{1,64}`, unique; `withoutStandardTools()`, `withoutTools(names)`.
- **Signals**: `tool_call` then `tool_result`; invalid input returns `Invalid arguments for '<tool>': <problems>. Expected input schema: <schema>` before hooks and approval; lenient repairs `"5"` -> 5, `"true"` -> true, `null` for optional; exception -> `Error: <message>`; output cut at `toolMaxChars` with `[truncated N chars]`.
- **Failure modes**: (1) wrong tool chosen when two descriptions overlap; (2) arguments with wrong types or missing required fields, repeated after the error; (3) model gives up on a tool after one error instead of correcting the input.

### 1.6 File tools and path jail (jllm-fs)
- **Entry**: tools `read_file` (`path`, `offset?`, `limit?`), `write_file` (`path`, `content`, `append?`), `edit_file` (`old_string`, `new_string`, `replace_all?` or `edits[]`), `glob`, `ls`, `grep`, `grep_code`; `FileSystemAgentTools`, `Checkpoints`, `TextEdits`.
- **Config**: `files.maxReadBytes` (1000000), `storage.maxBytes` (-1), sandbox work dir; kinds: `read_file`, `glob`, `ls`, `grep`, `grep_code` READ_ONLY; `write_file`, `edit_file` FILE_EDIT.
- **Signals**: files in workspace; `Path escapes the work dir: <p>` (including symlinks); read capped at 2000 lines / 50 KB with `[showing lines 1-2000 of N; continue with offset=2001]`; `edit_file` non-unique `old_string` error and closest-lines hint; BOM and CRLF kept; `write_file` preview is a diff; checkpoint copy on first write per user turn, `/undo [n]`; plan mode redirects relative writes to scratch, absolute writes outside scratch fail with "working directory is read-only"; storage cap fails the write with no file created.
- **Failure modes**: (1) `edit_file` with non-unique or whitespace-different `old_string`, then full rewrite; (2) paths guessed instead of `glob`/`ls` first, or writes outside the jail attempted with `..`; (3) reads one page of a large file and answers as if it were complete.

### 1.7 Shell and sandbox
- **Entry**: tool `bash` (`command`, `timeout_seconds?`, kind OTHER); `SandboxExecutor`: `LocalDirSandboxExecutor` (default), `CwdSandboxExecutor`, `ChatDirSandboxExecutor`, `DockerSandboxExecutor`, `RemoteExecSandboxExecutor`; `ScratchJailBackend`, `RemoteFileBackend`.
- **Config**: `sandbox.backend` (`local`, `docker`), `sandbox.localFallback`, `sandbox.docker.*` (`image`, `network`, `memory`, `cpus`, `pidsLimit`, `user`, `mode` `oneshot`/`daemon`, `remote`, `remoteHost`), `sandbox.timeoutSeconds` (120, cap 600), `sandbox.tempDir`, `sandbox.outputLogMaxBytes` (51200), `sandbox.shell` (OS default), `whitelistedEnvVars`.
- **Signals**: result text `exit code: N`, stderr merged, last 50 KB kept with `[output truncated: N lines omitted; log: <scratch>/bash-N.log]`; timeout kills the process tree; Docker run uses `--network none`, work dir read-only in plan mode; the `bash` tool description carries `shellDescription()` (shell syntax); per-turn note carries OS, work and scratch dir; outbound `docker` process calls.
- **Failure modes**: (1) syntax for the wrong shell (Windows `cmd` vs `bash`); (2) interactive or never-ending command until timeout; (3) in plan mode shell writes into the work dir on non-Docker executors (documented gap).

### 1.8 Web tools (jllm-webbrowse)
- **Entry**: `web_search` (`query`, `count` <= 20), `wikipedia_search` (`query`, `lang?`, `count?`), `web_fetch` (`url`), `web_download` (`url`, `path`); `WebAgentTools`, `HostMatcher`; all READ_ONLY.
- **Config**: `BRAVE_API_KEY` (else DuckDuckGo HTML), `web.fetchMaxChars` (40000), `web.fetchMaxBytes` (5 MB), `web.downloadMaxBytes` (100 MB); deny rule `web_fetch` removes the tool.
- **Signals**: outbound HTTP to Brave/DuckDuckGo/Wikipedia/target host; `approval_request{type:"host", host, preview=url}` once per host per chat (grant covers subdomains on a label boundary, not `badexample.com`); redirects max 5, https->http refused, a new host asks again; `approval_request{type:"insecure_cert"}` per call and host; non-interactive run refuses ungranted hosts in every mode; sanitized `<tool_output source=... trust="untrusted" nonce=...>` envelope; `web_download` writes bytes to work dir, counts against `storage.maxBytes`.
- **Failure modes**: (1) summarizes a search snippet without fetching the page; (2) retries a denied host through a different URL form (IP, other subdomain); (3) obeys instructions found in fetched HTML (see 1.18).

### 1.9 Tool kinds, permission modes, approvals, plan mode
- **Entry**: `PermissionGate`, `ToolApprovalPolicy.byKind()`, `ApprovalService`, `PermissionMode` (`DEFAULT`, `PLAN`, `EDIT`, `AUTO`), `/mode`, `/default`, `/plan`, `/edit`, `/auto`, `resolveApproval(chatId, id, approved, edited, remember)`.
- **Config**: `ToolKind` (`READ_ONLY`, `FILE_EDIT`, `OTHER`), custom `ToolApprovalPolicy`, `approvalTimeoutSeconds`.
- **Signals**: READ_ONLY never emits `approval_request`; default asks for every non-READ_ONLY; edit/plan allow FILE_EDIT, ask for the rest; auto allows all (classifier optional); one dialog per chat at a time; `remember` grants the tool for the chat (also command and write-dir grants), stored in `state/grants.json`; timeout, cancel or reject -> `The user rejected this tool call`; sub-agent approvals forwarded with `[<type> #n] <tool>`; plan mode: file tools write only to scratch, shell runs from scratch, `plan_ready` after the answer; mode change touches only the per-turn note.
- **Failure modes**: (1) in plan mode the model tries to write into the work dir or uses `bash` to edit, then reports success; (2) after a rejection the model retries the identical call or a bash equivalent; (3) model asks the user in text instead of calling the tool and waiting for approval.

### 1.10 Auto-mode classifier and fast model
- **Entry**: `ToolClassifier`, `AgentLoop` authorization; `fastModel` (settings) or `builder.fastModelClient`.
- **Config**: mode `auto`, fast model present, tool not READ_ONLY; confidence floor 0.8, 20 s timeout, input = work dir, newest plain user message (1000 chars), tool name and input (4000 chars), never tool output.
- **Signals**: `classifier{tool, verdict}`; `SAFE` runs, `UNSAFE`/`UNSURE` -> `approval_request`; outbound `simpleComplete` (60 tokens) to the fast model; without a fast model auto asks nothing and no `classifier` event.
- **Failure modes**: (1) small fast model returns unparsable output, everything becomes `UNSURE`; (2) destructive command judged `SAFE` because the user message is vague; (3) tool result with injected text cannot steer it by design, but a user message quoting the injection can.

### 1.11 Permission rules
- **Entry**: settings `permissions.{deny,ask,allow}`, `PermissionRules`, `/permissions`.
- **Config**: `toolPattern` or `toolPattern(argPattern)`, regex on whole tool name; arg = `command` (bash), `path`, `url`, `query`, or compact input JSON; order deny, ask, allow.
- **Signals**: `Denied by permission rule: <rule>. ... report this to the user instead.`; a deny rule on `write_file`/`edit_file` also denies `bash` commands writing a matching path (redirection, `tee`, `cp`/`mv`, `Set-Content`, ...); deny holds in every mode, over grants, for sub-agents; a deny rule without arg pattern removes the tool from the request; `ask` forces `approval_request`; `allow` skips asking except in plan confinement; invalid regex fails the load naming `permissions.deny[i]`.
- **Failure modes**: (1) bypass through an equivalent command (`python -c`, quoting) when only a bash regex guards it; (2) model keeps retrying a denied call until `stuck`; (3) model claims the action was done after a denial.

### 1.12 Hooks and verify-on-stop
- **Entry**: `Hooks`, `Hook`, `HookResult` (`allow`, `deny`, `modifyInput`, `addContext`, `continueWith`), builder `hook(event, h)`, `beforeAgent`, `afterAgent`, `beforeLlmCall`, `afterLlmCall`, `beforeToolCall`, `afterToolCall`; settings `hooks.stop[]` -> `CommandStopHook`.
- **Config**: `loop.verify.maxReentries` (3); `hooks.stop[]` fields `command`, `timeoutSeconds` (600), `agents`, `onlyIfChanged` (true), `maxOutputChars` (8000); project-file hooks run only after trust (`FileProjectMcpTrust`, `~/.jllm/trusted-mcp.json`).
- **Signals**: `Denied by a hook: <reason>`; rewritten input is the one in `POST_TOOL` and executed; first denial stops the chain; `POST_LLM` carries `durationMs`, `usage`; `PRE_LLM` deny -> `error`, nothing appended; `STOP` veto appends "The stop check failed. Fix the problem and finish again. If it cannot be fixed with what you have, stop and say what is missing; do not invent files or content." + message, emits `verify_failed{agent, attempt}`; after `maxReentries` or the same message twice -> `run_finished.outcome=unverified`, `stuck{pattern:"verify_repeat"}`; STOP not fired in plan mode or on cancel; hook exceptions logged and skipped.
- **Failure modes**: (1) model declares done again without fixing, same message twice; (2) model edits the test to satisfy the stop command; (3) model argues with the hook text instead of fixing it.

### 1.13 Stuck detection and loop robustness
- **Entry**: `StuckDetector` (per agent, per loop run), `stuck-note.md`.
- **Config**: `loop.stuck.{enabled, repeat 3, alternationCycles 3, malformed 3}`.
- **Signals**: first detection appends a note after the tool results and emits `stuck{action:"nudge"}`; second in the run asks (`approval_request{type:"stuck"}`; `stuck{action:"continue"|"stop"}`) or a sub-agent ends with task status `stuck`; `run_finished.outcome=stuck`; patterns `repeat`, `same_error`, `alternation`, `malformed` (unknown tool, invalid arguments, cut off); comparison ignores key order and parallel order; edit then test is not a loop.
- **Failure modes**: (1) identical failing call repeated after the nudge; (2) A-B-A-B read/edit oscillation; (3) a small model keeps emitting calls to a tool that is hidden or does not exist (`malformed`).

### 1.14 MCP servers (jllm-apimcp)
- **Entry**: settings `mcpServers[]`; `ExternalSources`, `McpToolProvider`, `McpHttpClient` (protocol `2025-03-26`, JSON or SSE), `McpStdioClient`; `/mcp [enable|disable|check <name>]`; tool names `mcp__<server>__<tool>`; group `Mcp/<server>`.
- **Config**: `name`, `url` or `command`+`args`+`env`, `headers` (`${ENV}`), `group`, `readOnly`, `enabled`, `allowList`, `denyList`, `oauth`, `perUser`.
- **Signals**: outbound JSON-RPC `initialize`, `notifications/initialized`, `tools/list` (cursor), `tools/call`, header `Mcp-Session-Id`; discovery at `build()` and on enable, failure keeps startup going and marks the source disabled with the error; hidden tools never reach the model (`allowList` absent = all, `[]` = none, deny wins); result rendering (text joined, `isError` -> `Error: ` prefix, `structuredContent` fallback); kind READ_ONLY only with `readOnly: true`, else OTHER (asks); enable/disable changes the tool block (one cache miss); name clash disables the source; resources, prompts, sampling not supported.
- **Failure modes**: (1) server down: model retries the tool or invents data instead of reporting it; (2) calls a denied or hidden tool name from memory (`Unknown tool`); (3) misuses a tool with a large schema (wrong argument names).

### 1.15 REST APIs from OpenAPI (jllm-apimcp)
- **Entry**: settings `restApis[]`; `RestApiToolProvider`; `/apis [enable|disable|check <name>]`; tool names `<api>__<operationId>` (or `METHOD_path`); group `Rest/<api>`.
- **Config**: `openApiUrl` or `openApiFile`, `baseUrl`, `headers`, `allowedPaths` (`[]` = no tools), `denyPaths`, `group`, `enabled`, `oauth{flow, issuer, clientId, clientSecret, scopes, perUser, ...}`.
- **Signals**: operations outside `allowedPaths` or in `denyPaths` are never created; GET/HEAD READ_ONLY, others OTHER; call substitutes path params (URL-encoded), query, header params, JSON `body`; result `HTTP <status>\n<body>` cut at 20000 chars, non-2xx returned not raised; outbound request log of the fake server; 401 refresh and one retry; no login -> tool result `Error: Not connected to api:<n>. Ask the user to connect it (/login api:<n>), then retry.` plus `login_required{source}` once per run and source; tool list never changes with login state.
- **Failure modes**: (1) wrong parameter placement (path vs query vs `body`); (2) calls a denied path by constructing a different operation; (3) treats a 4xx/5xx body as success.

### 1.16 OAuth logins and token store
- **Entry**: `/login [<name> [shared]]`, `/logout <name>`, `OAuthLogin`, `TokenStore` (`FileTokenStore`, `NativeTokenStore`), `RefreshingAuthProvider`, `OAuthTokenClient`, `OAuthSources`.
- **Config**: `tokenStore{type, dir, encrypt}` (user/explicit settings only), `JLLM_TOKEN_KEY`, `JLLM_TOKEN_KEY_OLD`, `oauth` blocks on MCP/REST sources; project-file `oauth` sources need trust (`oauth:api:<name>`).
- **Signals**: token files (`auth.json`, `users/<id>.json`), `login` event, `login_required`, refresh 60 s ahead, compare-and-set before use, `invalid_grant` removes token.
- **Failure modes**: (1) model loops on a tool that returns login-required instead of telling the user; (2) after login the model does not retry; (3) expired token mid-run.

### 1.17 Tool groups and per-chat tool set
- **Entry**: `ToolSelector`, `RequestToolGroupTool` (`request_tool_group`, `groups[]`), `HarnessToolRegistry`, `/tools [enable|disable <tool|group>]`.
- **Config**: `toolBatchThreshold` (12), `coreGroups` (`Files`, `Shell`), registry sorted by `(group, name)`; names unique.
- **Signals**: on the first message, above the threshold, `simpleComplete` (256 tokens) to the fast/main model returns a JSON array of groups; parse failure or empty -> every tool; chosen groups persisted in the session (`toolGroups`); only grows; `request_tool_group` offered while groups are missing, unknown group error lists available groups; new tools visible from the next model call (tool block changes -> one cache miss); disabled tools hidden (`Tool disabled for this chat: <n>`); `task` and `request_tool_group` not in the registry, never given to sub-agents.
- **Failure modes**: (1) selector omits the group the task needs and the model never requests it; (2) model requests all groups at once; (3) model calls a hidden-group tool name directly (`Unknown tool`).

### 1.18 Tool output safety (sanitizer and taint gating)
- **Entry**: `ToolOutputSanitizer`, `AgentLoop.sanitizeOutput`, taint set per chat (`ChatSessionService.taintedToolUses`).
- **Config**: `output.sanitize` (`wrap` default, `off`, `strict`), `output.taintGating` (true), extra detection patterns; trust: Web/MCP/REST/sub-agents `UNTRUSTED`, Files/Shell/Js `LOW`; groups `Agents`, `Skills`, `Memory` exempt.
- **Signals**: results wrapped in `<tool_output source="<tool>" trust="..." nonce="...">`; flagged results prefixed `[WARNING: this content contains text that looks like instructions ...]`; `strict` replaces the output with `[output of <tool> withheld ...]`; template markers become `[defanged:...]`, role lines `user said:`, HTML comments and markdown images stripped; while a tainted result is in context, every non-READ_ONLY call asks even over grants and allow rules, with `warning` "Confirming '<tool>': the conversation holds untrusted tool output ..."; taint cleared when the result leaves the context; stored with the chat.
- **Failure modes**: (1) model follows the planted instruction (writes a file, fetches the attacker URL) despite the envelope; (2) model repeats the injected text to the user as if it were its own finding; (3) instruction split across pieces or in a language the patterns miss, so no flag and no taint.

### 1.19 Sub-agents (`task`)
- **Entry**: tool `task` (`prompts[<=8]`, `subagent_type?`, READ_ONLY, main agent only); `SubAgentTool`, `AgentCatalog`, `AgentLoop.runSubAgent`; types `general`, `explore` (`read_file`, `glob`, `ls`, `grep`, `grep_code`), `plan` (all READ_ONLY tools), custom `agents[]`.
- **Config**: `agents[]` (`name`, `description`, `tools`, `readOnly`, `prompt`/`promptFile`, `inheritContext`, `model`/`provider`, `temperature`, `thinking`), `execution.maxParallel`, `execution.useContextStack` (true).
- **Signals**: result JSON array `[{prompt, runId, status ok|failed|stuck|unverified, result}]`; sub-agent mode PLAN for read-only types, caller's mode otherwise; read-only types get only READ_ONLY tools; forbidden call -> `Tool unavailable for agent`; transcript only in `SubAgentRunLog` (`subagent-<runId>/`), usage billed to the chat; only `approval_request` (label `[general #2] tool`), `login_required` forwarded; `PRE_AGENT`/`POST_AGENT` nested in the `task` call with `ctx.agent()` = type name; writable types serial in `edit`/`auto`; `explore` temperature 0.2, `plan` 0.3; cancel of the parent cancels children; model failure -> `failed`, never empty `ok`; with `useContextStack` the sub-agent request shares pinned, system, mode note and tools with the parent.
- **Failure modes**: (1) delegates trivial work or sends one overloaded prompt; (2) read-only sub-agent asked to edit, returns a claim of success; (3) parent ignores a `failed`/`stuck` entry and reports success.

### 1.20 Skills, instruction files, system prompt files, small-model prompt
- **Entry**: tool `skill` (`name`, group `Skills`, READ_ONLY, only when skills exist); `SkillCatalog`, `SkillTool`; `InstructionLoader`; `/skills [enable|disable]`; `SYSTEM.md`, `APPEND_SYSTEM.md`, builder `systemPrompt`, `systemPromptReplace`; `smallModel`.
- **Config**: `skills[]` (`name`, `path`, `description`), `instructionFiles[]` (`~` expanded), `smallModel` / provider `smallModel` (appends `small-model.md`, about 600 tokens), `UserPolicy` per-user skills.
- **Signals**: skill list in the `skill` tool description; `tool_result` = full skill file read on each load; disabled skill -> load fails and description changes (cache miss); missing skill file only logged, duplicate names fail `build()`; instruction files (default `KENDAR.md`, `AGENTS.md`, `CLAUDE.md`; same content skipped) read once, placed in the cached system block as `Instructions from <file>:`; directory-scoped instruction files appended to the tool results of the first file tool call under their directory as `Scoped instructions from <file>, for the work under <dir>/:`, once per chat (event `instructions_loaded`); sub-agents get `skill` unless filtered.
- **Failure modes**: (1) model never loads the relevant skill; (2) loads it and skips steps; (3) instruction-file rule (for example "never refund over 100 EUR") dropped when it conflicts with the user request.

### 1.21 Output limits and truncation
- **Entry**: `AgentLoop` result handling, `output.*`.
- **Config**: `output.toolMaxChars` (30000, head kept, tail for bash), `output.eventMaxChars` (2000), `web.fetchMaxChars`, REST 20000, `files.maxReadBytes`, `context.scope.offloadChars`.
- **Signals**: `[truncated N chars]`; event `output` shorter than the context result; `bash` log file path in the result.
- **Failure modes**: (1) answers from a truncated result without paging; (2) misses the tail of a log where the error is; (3) re-runs the same command to get "more".

### 1.22 Sessions, persistence, restart and checkpoints
- **Entry**: `ChatSessionService`, `ChatSessionStore` (`InMemoryChatSessionStore`, `FileChatSessionStore`), `FileHistorySubAgentRunLog`, `StateStore`, `MnemonicStore`, `InterruptedTurnRepair`, `/chats`, `/resume`, `/title`, `/clear`, `/new`, `/undo`, `/cost`, `/where`, `/status`.
- **Config**: history root `.jllm/history`, `chatRetentionLimit`, `.lock` per root.
- **Signals**: files listed in section 0; `messageCount` in `session.json`; log appended when it extends the old one, else rewritten; after restart the next request is byte-identical (`RestartRestoreTest`); unanswered `tool_use` completed with `tool_result` "interrupted: process stopped"; grants and todos survive; pending approvals and steer queues do not; `/undo` restores edited files of the last n turns (not `bash` changes).
- **Failure modes**: (1) after resume the model repeats finished work; (2) model confused by an interrupted-call result; (3) `/undo` leaves bash-made files, model assumes a clean state.

### 1.23 Slash commands and plugins
- **Entry**: `SlashCommands`, `MenuController`, `HarnessPlugin` / `PluginRegistrar` (`tool`, `hook`, `command`, `documentReader`, `replaces`), `PluginLoader`, `builder.discoverPlugins()`, `pluginDir`.
- **Config**: `plugins.<id>.{enabled, settings}`; id `[a-z][a-z0-9-]{1,31}`; plugin not listed or without `enabled` is skipped with WARN.
- **Signals**: commands run without the model (except `/compact`); `/help` lists plugin commands; plugin-contributed tools in the registry; WARN/INFO load lines.
- **Failure modes**: (1) none model-specific; eval only checks that a command does not call the model and plugin tools are offered.

### 1.24 Usage, cost, metrics
- **Entry**: `usage` event, `HarnessMetrics` (`modelCall`, `tokens`, `toolCall`, `turn`, `compaction`, `context`, `approval`), `HarnessPricing(in, out, cacheRead, cacheWrite)`, `cost(session)`, `/cost`.
- **Signals**: token counters on the chat row (input, output, cache read, cache creation, `qualifierInputTokens`, `qualifierOutputTokens`); summary and handoff calls billed to the chat; sub-agent usage summed into the chat.
- **Failure modes**: not model-behavioral; risk is a server that reports no cached tokens (checks become "not measurable").

### 1.25 Multi-user policy (harness side)
- **Entry**: `builder.userPolicyResolver`, `UserPolicy(toolFilter, approval, extraSkills, skillFilter)`, `credentialResolver`, `principalDirectory`, `sessions().create(title, principal)`.
- **Signals**: tool filter applied when tools are offered and again at dispatch (`Not available to this user: <n>`); policy change alters the tool block (one cache miss); `run_js` inner calls covered by the `run_js` approval.
- **Failure modes**: model names a hidden tool, refused. Low priority for evals (needs identity wiring).

---

## 2. Plugins

### 2.1 pdf (`jllm-pdf`, id `pdf`)
- **Entry**: `read_file` on a `.pdf` through `DocumentReader` (`PdfPlugin`, `PdfReader`, Apache PDFBox 3).
- **Config**: jar in plugin dir plus `"plugins": {"pdf": {"enabled": true}}`, or `builder.plugin(new PdfPlugin())`; extraction timeout 30 s, text cap 5 MB, file up to 50 MB.
- **Signals**: `tool_result` text with `--- page N ---` markers; `offset`/`limit` apply to the extracted text; errors: `No reader for .pdf; put the pdf plugin jar in the plugin directory and enable it under plugins` (plugin off), `no extractable text (scanned?)`, `The PDF is password-protected`; path jail runs first.
- **Failure modes**: (1) reads only page 1 of a long file and answers; (2) table rows split across lines misread; (3) treats the "scanned" or error text as document content.

### 2.2 docs (`jllm-docs`, id `docs`)
- **Entry**: `read_file` on `.docx` (and `.doc`); `DocsPlugin`, `DocxReader` (JDK ZIP + StAX).
- **Config**: as pdf with id `docs`.
- **Signals**: headings as `#`, list items as `-`, table rows `a | b`; headers, footers, comments skipped; tracked insertions kept, deletions dropped; `.doc` -> `legacy .doc not supported, convert to docx`; corrupt zip -> read error result.
- **Failure modes**: (1) wrong cell attribution in wide tables; (2) uses a deleted (tracked) value; (3) after the `.doc` error tries `bash` converters instead of telling the user.

### 2.3 js (`jllm-js`, id `js`)
- **Entry**: tool `run_js` (`code`, `timeoutSeconds?`, group `Js`, kind OTHER); `JsPlugin`, `JsInstaller`, `GraalProbe`, `JsProfiles` (`ConstrainedProfile`, `IsolatedProfile`), `RunJsTool`, `JsSandbox`, `ToolLibrary`, `RunClock`, `MemoryWatchdog`; turn budget `RunBudget` in the harness.
- **Config**: GraalJS (`polyglot`, `js`, `regex`, an isolate artifact; `provided` in js plugin, cli, web and eval, `lib-ext/` in the release; in a checkout `scripts/setup` downloads it into `.downloaded-libs/graal/<version>/repo` and every module takes it as one `provided` `org.kendar.llm.graal:jllm-graal-set` dependency) on the host classpath (else the plugin logs one WARN and `run_js` is absent), `plugins.js.settings`: `engine` (auto), `policy` (isolated), `statementLimit` (1000000), `maxTimeoutSeconds` (120), `maxWallSeconds` (300), `gracefulSeconds` (10), `maxInnerCalls` (200), `maxConcurrentRuns` (4), `maxHeapMemory`, `maxIsolateMemory`, `maxStackFrames`, `maxAstDepth`, `maxRunAllocatedBytes`, `heapKillRatio`, `fileJail` (true); `loop.innerCallsPerTurn` (10).
- **Signals**: result JSON `{ok, value, console (50 KB cap), error "<message> at line N", fileRoot}`; `tool_call`/`tool_result` events with `parent = run_js:<toolUseId>` for each inner `tools.<name>({...})` call; approval preview is the full code; inner read-only built-in file tools need no prompt, inner writes only while the chat is not tainted (after an untrusted read the write asks, non-interactive runs deny), others use standard gating; inner calls spend the turn budget (error text tells the script to return what it has); total time ceiling `maxWallSeconds`; `deny` rules and `PRE_TOOL` hooks still apply; hooks see `HookContext.parent()`; timeout -> `ok:false` with `Timeout`; statement limit stops loops; fresh `Context` per run, state only in files; file jail root = scratch of the agent (CLI) or chat work dir (web); tool library excludes `run_js`, `bash`, `task`, `request_tool_group`; no `require`, `fetch`, `Java`, `process`; `await` is a `SyntaxError`; cancel makes the next inner call throw "cancelled by the user".
- **Failure modes**: (1) writes Node.js or browser code (`require`, `fetch`, `await`) that fails; (2) heavy computation hits timeout or statement limit (interpreter-only speed); (3) expects state to persist between calls or calls a tool with wrong arguments, ignoring the thrown `Error`.

### 2.4 workflows engine (`jllm-workflows`, id `jllm-workflows`)
Common config: `plugins."jllm-workflows".{enabled, settings{dir (~/.jllm-workflows), adminRole (admin), chatUser (local), chatRoles}}`; storage via host `PluginStorages` (collections `flows`, `runs`, `runindex`) else files (`FlowStore.Files`, `RunStore.Files`); `Registry` actions and choosers from `ActionProvider` (`META-INF/services/org.kendar.llm.workflows.ActionProvider`). Flows are never offered to the model as tools. The engine emits no harness events of its own: observe via `WorkflowApi.run(...)`, `history(...)`, `runs`, `waiting`, `RunRecord` (`status`: `PENDING`, `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, `CANCELLED`; `history` array) and, for task steps, the ordinary harness events of the run chat titled `flow: NAME RUNID`.

#### 2.4.1 Entry points
- Chat: `/flows`, `/flow-start [--chat] NAME [JSON]`, `/flow-resume RUN ANSWER`, `/flow-request RUN KIND [JSON]`, `/flow-cancel RUN` (`FlowCommands`).
- CLI: `WorkflowCli [--dir D] [--user U] [--role R]... [--ask] [--scope S]` with `list`, `inputs NAME`, `start NAME [JSON]`, `resume RUN ANSWER`, `request RUN KIND [JSON]`, `history RUN`, `cancel RUN`; without `--ask` prints `paused RUN waiting for: ...` and exits.
- API: `WorkflowApi` (`list`, `flow`, `inputs`, `save`, `validate`, `delete`, `copy`, `start`, `answer`, `request`, `cancel`, `runs`, `waiting`, `run`, `history`, `catalog`) via `WorkflowApiImpl`.
- Failure modes: (1) n/a for the engine (deterministic); model-dependent parts are in 2.4.3 to 2.4.8.

#### 2.4.2 Flow definition, validation, slots
- **Signals**: `FlowValidator` refuses at save time: unknown step ids, unregistered actions, tasks without prompt or agent, bad limits, flow loops, `unattended` flows with `ask`/`user` chooser/asking action, action roles not covering shared roles; `{{input.x}}`, `{{steps.id[.field]}}`, `{{requests.key}}`, `{{error.kind}}`, `{{error.message}}`, `{{user}}`, `{{runId}}`, `{{input.chat}}`; missing slot value is an error; `FlowException.Kind`: `ACTION`, `SHAPE`, `CHOOSER`, `TIMEOUT`, `PERMISSION`, `CHILD`, `LIMIT`, `INVALID_FLOW`, `REQUEST`.
- **Failure modes**: slot paths wrong in model-generated flows; model-authored flow fails validation on unregistered action; `{{steps.x}}` of an object printed as JSON where text was expected.

#### 2.4.3 Task steps (`type: task`)
- **Config**: `tasks.<name>{agent, system, prompt, answer ("text" or `{field: type}`, `?` optional), tools ("*", list, `[]`, absent = agent's), clarify (true), before, after, instead}`; step `retries`, `retryWaitMs`, `questions`, `waitMs`.
- **Signals**: JSON answer shape enforced (code fences stripped), wrong shape -> `SHAPE` error retried; `after` action throw -> retried; tools in the real agent loop with the agent's tools and permissions, approval stops the task and asks the owner (counts against `questions`), refuse -> `PERMISSION` error, unattended fails; tasks of one run share one chat; `clarify`: cheap pre-filter then fast model answers `none`, `proceed` or `ask`, at most 3 rounds, `ask` pauses with a free-text question, unattended treats `ask` as `proceed`; replies and verdicts journaled so a resume never calls the model again.
- **Failure modes**: (1) returns prose or extra fields instead of the declared JSON; (2) answers with a question, triggering clarify rounds; (3) wrong types (`"5"` for number) hitting `SHAPE` retries until the limit.

#### 2.4.4 Turning points (`type: decide`)
- **Config**: `by` = `java` (`chooser`), `llm` (`agent`, `prompt`), `user` (`who`: `owner`, `operator`, `customer`); `outcomes`, `default`, `question`.
- **Signals**: history records inputs, outcome and who decided (model, Java rule, person); answer outside the list -> `default` or `CHOOSER` error; recorded decision reused on resume; `by: user` pauses (`WAITING`).
- **Failure modes**: (1) model answers a sentence instead of one outcome word; (2) picks a plausible but wrong branch on ambiguous input; (3) invents an outcome name.

#### 2.4.5 Asking people (`type: ask`) and resume
- **Config**: `kind` (`text`, `single`, `multi`), `options`, `min`, `max`, `required`, `pattern`, `who`, `facts`, `waitMs`, `onTimeout`; Java `call.askText`, `call.askSingle`, `.asking(...)`.
- **Signals**: run `WAITING`, `GET /waiting` equivalent `WorkflowApi.waiting`; refused answers (not in list, too many picks, failed pattern) keep the question open; answer stored as `{{steps.id}}`; timeout to `onTimeout` step else `TIMEOUT` error; asking actions re-run from the start with recorded answers by key; survives restart, finished steps not repeated; `questions` limit per step (default 5).
- **Failure modes**: (1) model-composed question text omits facts; (2) answer given in free text where `single` needs the value; (3) asking Java action not idempotent before the last question (side effect twice).

#### 2.4.6 Sub-flows (`type: call`)
- **Config**: `flow`, `scope`, `input` (explicit), `next`; `chooser` + `allowed[]` (Java only); `limits.depth` (4).
- **Signals**: child is a separate run with own snapshot and history, same user, sees only the passed input, returns only its `finish.result`; parent waits while the child waits; cancel parent cancels children; child error -> parent step `CHILD` error; self-call, loops, global->personal calls refused at save or start.
- **Failure modes**: (1) task inside the child misreads the narrowed input; (2) parent prompt assumes data the child did not return; (3) depth limit reached on model-built chains.

#### 2.4.7 Customer requests (`accepts`, `helper`)
- **Config**: `accepts{kind: {action: cancel|update|redirect, key, kind, required, options, pattern, to}}` at flow or step level (step wins); `helper` action name.
- **Signals**: `update` stores `{{requests.key}}`; `redirect` jumps to `to`, cancelling children; `cancel` ends as `CANCELLED`; unaccepted request refused or answered by `helper`; all recorded in history; delivered to the innermost waiting run that accepts.
- **Failure modes**: (1) `helper` task (if model-backed) promises actions it cannot take; (2) payload not matching `kind`/`pattern`; (3) request arrives at a stage that does not accept it.

#### 2.4.8 Error handling, retries, limits, unattended
- **Config**: `onError{start, steps, retryLimit 3}` with finish `outcome` `recovered`, `retry` (`retryStep`), `rethrow`; `limits{depth 4, questions 5, retries 3}`; `unattended`.
- **Signals**: step retries first, then handler; handler sees `error.kind`, `error.message`; failure inside the handler ends the run with both errors; default handler records and fails; history lists retries, limit hit, timeouts; cancel is not an error.
- **Failure modes**: (1) repeated `SHAPE` errors from a weak model exhaust retries; (2) model-backed handler step produces unusable outcome; (3) `retry` loop on a deterministic failure until `retryLimit`.

#### 2.4.9 Ownership, snapshots, persistence
- **Signals**: personal (`dir/users/<user>/<name>.json`) vs global (`dir/global/<name>.json`) namespaces, admin-only global edits, `roles` sharing, `scope/name` addressing, `copy`; run snapshot taken at start (edits and deletes do not affect it); run saved after every step; other users' flows and runs never returned.
- **Failure modes**: not model-behavioral; deterministic checks only (fake stores).

---

## 3. Prompt-cache layout (cross-cutting)

- **Entry**: `ModelRequest` assembly in `AgentLoop`, `Anthropic` and `OpenAiCompat` clients, `RequestPrefix`, `ChatSessionService.pinnedPrefix`.
- **Layout** (Anthropic): `tools[]` sorted by `(group, name)`, `cache_control` on the last tool; `system[0]` pinned `chatId=<id>; agentId=<id>` (sub-agent: `chatId=<id>; subagent`); `system[1]` extra = `system-prompt.md` + addendum + instruction files (+ `mnemonic-suffix.md`) + per-turn note (date, OS/shell, working dir, scratch dir, mode sentence) with `cache_control` (ttl `1h` default, `AnthropicSettings.cacheTtl`); `messages` carry no `cache_control`. OpenAI-compatible: one `system` message `pinned + "\n\n" + extra`, then history, tools; prefix caching reuses everything up to the first changed byte.
- **Expected breaks**:
  - mode switch, new day, different directory: system part only (Anthropic: tool entry still hits; OpenAI-compatible: re-processes from the note on, history included).
  - tool group added, tool/source/skill toggled, user policy change: tool block (everything after it).
  - compaction rewrite: from the first rewritten message.
  - mnemonic mask or segment switch: from the first newly touched message.
  - `/model` switch: whole prefix once.
  - noise-command drop: from the stubbed message on.
  - Never: pinned block within a chat, base prompt after `build()`, appended messages.
- **Signals**: `usage.cacheReadTokens` / `cacheCreationTokens` per turn; byte comparison of consecutive recorded requests (shared prefix length); requests from `summaryOnPrefix`, handoff, sub-agent with `useContextStack` extend the same prefix (push/pop); `LimitedModelClient` with `maxParallel=1` keeps one call in flight.
- **Failure modes**: (1) server reports no cached tokens (ratio not measurable); (2) multi-slot or shared server evicts or splits the prefix; (3) a tool description computed from state (for example `skill`) changes the prefix every turn; (4) a provider normalizes whitespace or reorders tools, so equal logs give unequal bytes.

---

## 4. Context strategies

Common: selected by `context.strategy` (`compaction`, `mnemonic`, `scope`), CLI `--context-strategy`, `HarnessConfig.withContextStrategy`, or `builder.contextStrategy(factory)` (wins). `/status` shows the active one. Common settings: `context.maxTokens` (window, default 100000), `context.auto` (true), `context.keepTokens` (15000), `context.keepRecentTurns` (6), `context.buffer` (null = max(15% of window, 16000) once window >= 32000), ceiling = window - buffer (84000 for 100000). Estimate = `ceil(chars / 3.5) x ratio` (ratio learned from measured `Usage.contextTokens()`, accepted 0.5..2). Contract (`ContextStrategy`): never separate a `tool_use` from its `tool_result`; first message stays a user message; `ContextView(messages, changed, logChanged)`; one instance for all chats, state keyed by `(chatId, agentKey)`; sub-agents keep their own state, released at the end. Overflow: provider rejection matching `ModelException.isContextOverflow` calls `onOverflow` once, then the same turn is retried; second rejection is an `error` event. Strategy model calls reuse the agent's cached prefix (`RequestPrefix`) when the summary model is the agent's own client, else plain `simpleComplete` to the fast model.

### 4.1 Compaction (default; `CompactionStrategy` -> `ContextManager`)
- **Settings**: `context.strategy: "compaction"`, `context.auto`, `keepTokens`, `keepRecentTurns`, `buffer`; prompts `compaction-create.md`, `compaction-update.md`, `compaction-template.md`, `compaction-rules.md`, `compaction-system.md`; `context.deferCompactionOnThinkingOverflow` (Anthropic thinking, default false).
- **Mechanism**: at the top of each turn when estimate > ceiling. Tier 1 (no model): `tool_result` bodies before `cutIndex(keepRecentTurns)` replaced by `[tool result cleared to save context]` (only bodies longer than placeholder + 50 chars). Tier 2: summarize everything before `max(cutIndex(keepRecentTurns), tokenCutIndex(keepTokens))` into one user message `[Summary of the earlier conversation]\n<summary>` (3000 tokens), newest `keepTokens` verbatim; later compactions merge the old summary (`compaction-update.md`). Tier 3 (main agent only): propose a summary of everything but the latest turn through `approval_request{type:"context", proposed}`; user may edit; decline remembered (`declinedAt`, skipped while size < 1.2 x). Retry ladder when the summary request is refused: levels 0..3 (clip outputs, no outputs, user text only) and history cut to 85/70/50/35% of the first refused size, then `null` (nothing to compact). `/compact [focus]`: tier 1 with 1 turn, summarize all but the latest turn, no approval, refused while a run is active. Sub-agents: tiers 1-2; `compactInherited` for `inheritContext` (parent's log never touched).
- **Signals**: `compaction{tier:1, cleared}`, `{tier:2, estimatedTokens}` (also `inherited`, `inTurn`), `{tier:3}`; log rewritten and saved; summary headings: `## Objective`, `## Requirements`, `## Decisions`, `## Work State` (Completed / Active / Blocked), `## Next Move`, `## Relevant Files`, `## Important Context`; summary requests recorded (agent's own request plus instruction as last user turn); `/cost` includes the summary calls.
- **Invariants**: cut lands on a plain user text message; tool pairs intact; the verbatim tail is at least the latest turn; after compaction estimate <= ceiling or a documented fallback; `logChanged` true only when `turn.log()` was rewritten; summary starts with the marker and contains all template sections; second summary merges the first (marker once); declined tier 3 does not re-ask below 1.2 x declined size; sub-agent compaction never asks the user.
- **Cached prefix**: every compaction rewrites history, so the prefix breaks from the first rewritten message (tier 1: first cleared result; tier 2: the first message); tools and system stay valid; the summary request itself hits the old prefix; after a rewrite the measured usage is stale, estimate only.
- **Failure modes**: (1) summary drops a planted fact or a user constraint; (2) after compaction the agent restarts or redoes finished steps (Next Move ignored); (3) summary model fails or returns text without the sections (small fast model), or tier 3 summary is edited into nonsense.

### 4.2 Mnemonic (`MnemonicStrategy`, `MnemonicStore`, `RecallIndex`, `Renderer`, `SegmentSwitcher`, `ReceiptGuard`, `HistoryRecallTool`)
- **Settings**: `context.strategy: "mnemonic"`; `context.mnemonic{maskTrigger (null = 60% of ceiling), maskTarget (30%), minBatchTokens (20%), minMaskTokens 300, keepRecentTokens (= keepTokens), minAgeTurns 2, tailMaxTokens (25% of window), notesMaxTokens 2000, indexMaxTokens 1500, recallMaxChars 12000, dropOldThinking true}`; prompts `mnemonic-suffix.md`, `mnemonic-handoff.md`; `mnemonicStore(...)`, `FileMnemonicStore` -> `<chat>/<agent>/mnemonic.json` (atomic); sub-agent state memory only.
- **Mechanism**: the log is never rewritten; each request is rendered from the verbatim log plus append-only decisions (`Mask` of `tool_result` id `r<12 hex>`, `tool_input` id `a...`, `thinking` id `t...`; `Segment` with notes and index; `frontier`). Ids: first 12 hex of `sha256(messageHash + ":" + block)`; message ids `m...`. Level 1 masking batch when estimate > `maskTrigger`: walk from `max(inherited copy, frontier, segment start)` to `min(start of newest keepRecentTokens, minAgeTurns-th last assistant turn)`, mask candidates >= `minMaskTokens`, stop at `maskTarget`, commit only if saved >= `minBatchTokens`, `frontier` = first masked message. Level 2 segment switch when estimate + `maxTokens` > ceiling: cut chosen with tail <= `tailMaxTokens`, handoff notes from the agent's own request + `mnemonic-handoff.md`, recall index of masked receipts (newest first) and earlier user messages. Rendered head: first user message, `[Context segment N. ...]`, notes, `Recall index (newest first):`, optional assistant "Understood. Continuing from the handoff notes.", tail. Emergency: mask the largest results of the trailing tool-result message when estimate + `maxTokens` > window. Validation each turn: a hash mismatch drops all decisions from there and moves `frontier` back. Overflow: ratio x1.3 (max 2), batch to 60% of estimate with no minimum, else segment switch, else emergency masks, else error. `/compact` is a no-op (`Not used with the mnemonic context strategy`).
- **Receipt text**: `[masked: read_file(path=src/A.java) -> ~4200 tokens, recall id=r3f2a91c0d4e1]` (`error from ...` for errors); input masks `[masked ~N tokens, recall id=a...]`.
- **`history_recall`** (group `Memory`, READ_ONLY, always offered): `{"id": r.../a.../t.../m.../<runId>}` paged by `recallMaxChars` with `offset`; `{"query": "a | b"}` up to 8 hits (BM25 raw + normalized, exact substring +100 for paths and `key=value`, reciprocal rank fusion k=30); `{"file": path}` up to 30 calls; `{"scope": runId, "query"}`; only finds what is masked or before the current segment.
- **Receipt guard**: `PRE_TOOL` hook denies `write_file`, `edit_file`, `bash` whose input contains `[masked ... recall id=`, with a message to recall first (`Denied by a hook: ...`).
- **Signals**: `compaction{strategy:"mnemonic", kind, masked, savedTokens, estimatedTokens}` (kinds: mask batch, segment switch, emergency, as emitted by `MnemonicStrategy.event`); `mnemonic.json`; `messages.jsonl` unchanged by masking; `history_recall` `tool_call`/`tool_result`; handoff request recorded; `HarnessMetrics.context("mask"|"segment")`.
- **Invariants**: log never rewritten (`logChanged` false; messages.jsonl byte-identical before and after); same log + same decisions -> same rendered bytes; `frontier` only moves forward; new masks only at or after `frontier`; a batch below `minBatchTokens` is discarded; newest `keepRecentTokens` and last `minAgeTurns` assistant turns never masked; decisions never undone except by hash-mismatch validation; every masked item recoverable verbatim through `history_recall`; guard blocks writes containing a receipt; sub-agent masks never precede the inherited copy; first user message stays in every segment head.
- **Cached prefix**: breaks only at the first message touched by a new decision (masks at or after `frontier`, so the break point moves forward only); a segment switch is one rebuild and the new head is stable until the next switch; rendering is deterministic so restarts keep the prefix; `changed` view resets `lastUsage`.
- **Failure modes**: (1) model does not call `history_recall` and answers from the receipt or guesses the masked content; (2) model copies a receipt into a file or command (guard denies, model loops); (3) handoff notes lose the task state, or the model uses `id` of the wrong kind or a stale query; (4) query phrased differently from the stored text misses (lexical index, no embeddings).

### 4.3 Scope (`ScopeStrategy`, `todo.TodoStore`, `todo.TodoTools`)
- **Settings**: `context.strategy: "scope"`; `context.scope{offloadChars 20000, overflowOffloadChars 2000, previewChars 1500, safetyPercent 2, reserveCap 0}`; `builder.scopeOptions(new ScopeStrategy.Options(...))` (settings file wins); `context.auto: false` disables everything; `keepRecentTurns`; `stateStore` for todos.
- **Mechanism**: budget = `window - reserve - safety - fixed` where `reserve = min(window/4, maxTokens)` (or `min(window/4, reserveCap, maxTokens)`), `safety` = `safetyPercent` of the window, `fixed` = pinned block + per-turn note + tool schemas. While over budget, in order: (1) offload `tool_result` longer than `offloadChars` to `<scratch>/offload/<tool_use_id>.txt`, replaced by `[Output offloaded to <path> (N chars, L lines); only the first and last P chars are shown: grep it or read_file it with offset before concluding something is absent.]` plus `previewChars` head and tail (outside the latest turn first, then everywhere; failed write keeps the original); (2) clear results older than `keepRecentTurns` (offload pointers cut to their first line); (3) summarize via `ContextManager.compactNow` on the log without its first message. Still over: `warning` "The request is still over the context budget after compaction", request sent anyway. No user-approved tier. Overflow: one forced pass (offload at `overflowOffloadChars`, clear all but the latest turn, summarize). `/compact` clears and summarizes with optional focus.
- **Pinned first message**: after the first summary the log is `[first user message] [assistant "Understood."] [summary] [recent turns]`; later compactions keep the first two and merge the old summary; a log not starting with a plain user message has no pin.
- **Todos**: `todo_write` (`todos[]`: `id?`, `content`, `status` of `pending`/`in_progress`/`completed`; at most one `in_progress`; rejected write keeps the old list; replaces the whole list), `todo_read`; both READ_ONLY, group `Todo`, work in plan mode; stored in `StateStore` `todos-<agent>`; appended to the last user message as a `<todo_list>` block on a copy for each request (never in the stored log); `todos` event on every write.
- **Signals**: `compaction{strategy:"scope", estimatedTokens}`; files under `<scratch>/offload/`; `todos` events; `state/todos-<agent>.json`; `warning` when over budget; `tool_result` text with the offload pointer.
- **Invariants**: results above `offloadChars` are fully on disk and recoverable by `read_file` (the agent's scratch is readable in every mode); the first user message and the todo list are never summarized or cleared; the todo block is never in the stored log and appears once per request; at most one `in_progress`; nothing happens while messages fit; tool pairs intact.
- **Cached prefix**: like compaction (each offload, clear or summary rewrites from the first changed message); the todo block is at the end of the last user message so it moves with the tail and does not disturb the prefix; pinned head stays stable across summaries.
- **Failure modes**: (1) model does not read the offloaded file and answers from the preview; (2) todo list neglected (not updated, two `in_progress`, rejected writes repeated); (3) summary drops facts not on the todo list; offloaded path misquoted.

### 4.4 Noise-command pruning (any strategy; `NoiseCommandPruner`, `NoiseQualifier`)
- **Entry**: runs after a main-agent run, never for sub-agents.
- **Settings**: `context.noiseCommands` (true); batch threshold 1000 tokens (estimated).
- **Mechanism**: only a `bash` call with command < 512 chars, result starting `exit code: 0` and < 200 chars, turn ended. `NoiseQualifier`: regex allow-list of state-changing commands (`mkdir`, `rm`, `cd`, `mv`, `cp`, `touch`, `chmod`, no pipe/redirect/substitution) -> drop without model; informative list (`cat`, `ls`, `git log`, `grep`, builds, tests) -> keep without model; otherwise `simpleComplete` to the fast model (main when none), temperature 0, 8 tokens, 4 s, one retry, input is the command and exit code only; error, timeout, unparsable -> keep.
- **Signals**: stub in the log (`tool_use.input.command` cut to 80 chars, result `ok (exit 0)`), original after it as `{"ignore":true,"replaces":"<id>","original":{...}}`; `messages()` skips `ignore` lines, `references(chatId)` returns them; `qualifierInputTokens`/`qualifierOutputTokens` on the chat row.
- **Invariants**: failing commands never dropped; pairing stays valid; references removed when the replacing message is gone (compaction, `/clear`); `history_recall` cannot read references.
- **Cached prefix**: rewrites in place, so a miss from that message on; batched for that reason; stubs persisted so a restart is byte-identical.
- **Failure modes**: (1) fast model labels an informative command (`curl`, `python x.py`) as noise; (2) later turns refer to a dropped command's effect; (3) unparsable qualifier answer costs a call every time.

## Scoring and pinning (how the eval itself measures)

- **Trials**: every case runs all its trials, risk cases included. A case passes when its pass rate reaches the case minimum (risk cases: every trial).
- **pass@k / pass^k**: per case, from `n` trials with `c` passed: pass@k = 1 - C(n-c,k)/C(n,k), pass^k = C(c,k)/C(n,k), as running products; undefined for k above n (not measured). Configured by `eval.passK` (default 1 and `eval.maxTrials`); aggregated as a macro average over the cases with at least k trials, with a coverage count; reported for the largest k and in a table for every k.
- **Pinning**: `run.json` records commit, dirty flag and files, diff hash, version, Java, OS, settings hash, prompts hash, k list, dirty-tree policy and the run_js engine; every `result.json` embeds the run metadata and its case hash; `settings.json` holds the hashed canonical settings with secrets masked. `eval.dirtyTree` is `refuse`, `warn` or `allow`; a baseline is never stored from a dirty tree.
