# jllm → AgentScope parity TODO (single-thread sequential agent loop)

Scope: the **chat / reasoning / acting / reply** loop, tooling and harness only.
Out of scope (handled separately or intentionally excluded):
- Security / permissions
- Multi-LLM / multi-provider support (Ollama only for now)
- Event/streaming system — **jllm is fully synchronous**
- RAG / knowledge bases — handled by the user, only relevant as context plumbing

Reference: `../agentscope-main/src/agentscope` (Python v1.0). jllm equivalents go under
`jllm-lib/src/main/java/org/kendar/jllm`.

---


## 0. Fix the foundation (current code is a stub / has bugs)
- `OllamaClient.buildPayload`: line ~97 throws when `prompt != null` (inverted), and the
  `format` handling overwrites `format` with the system prompt. Rewrite to send a proper
  chat payload (messages array, not single `prompt`). Switch to `/api/chat`.
- `LLMEntryPoint.call`: only the `oneShot` branch works; `plan` is an empty `break`. Wire it
  to a real agent loop once the loop exists.
- `LLMConfigManager.getClassifier("scopeClassifer")` — typo vs config name `scopeClassifier`.

## 1. Message model (prereq for everything) — AgentScope `message/`
- Add `LLMMessage` with role (system/user/assistant/tool), content blocks
  (text, tool-call, tool-result), and a tool-call id. Today there is only flat `prompt`/`system`.
- Add typed content blocks: `TextBlock`, `ToolUseBlock`, `ToolResultBlock`, `ThinkingBlock`.
- `LLMRequest` must carry a `List<LLMMessage> messages` instead of a single string `prompt`.
- `LLMResponse` must expose parsed `toolCalls` (list), not just `response`/`thinking` text.

## 2. The ReAct agent loop — AgentScope `agent/_agent.py` (`Agent._reply`)
- AgentScope has ONE unified `Agent` class (no subclasses). An agent = system prompt + toolkit
  + react config + memory/state. **Fold the loop into `LLMAgent` itself** rather than keeping a
  passive descriptor (`getName/getDescription/getOutputFormat`) separate from a runner — promote
  `LLMAgent` into the unified runnable. `LLMConfigAgent` (XML) then becomes config that BUILDS one.
- Make the agent carry: `systemPrompt`, a `Toolkit` (sect. 3), a `ReActConfig`, its own
  `LLMMemory`/`AgentState` (sect. 5/8). Construction mirrors AgentScope's
  `Agent(name, systemPrompt, client, toolkit, reactConfig, state)`.
- Synchronous `reply(LLMMessage input) -> LLMMessage` loop: reason → (tool calls?) → act →
  observe → repeat.
- Loop config (cf. `ReActConfig`): `maxIters` (default ~20), `stopOnReject` flag.
- Reasoning step: call model with current memory + tool schemas, parse tool calls.
- Acting step: execute each tool call sequentially, append tool results to memory, loop back.
- **Finish detection (this is how AgentScope 1.0 actually does it — NO finish tool):** the loop
  ends when a reasoning step returns a plain assistant message with NO tool calls — that message
  is the reply. Tool calls present → keep looping; none → done. (`generate_response` existed in
  older releases and was dropped.)
- Guard: if `maxIters` hit without finishing, return a "did not finish" message.

## 3. Toolkit / tool execution — AgentScope `tool/_toolkit.py`, `tool/_base.py`
- When searching for a tool to use do not use the WHOLE tools list to ask what should be used.
  Instead run multiple sybchronous request asking at most for 4/5 tools and collect the tools
  to use
- `LLMTool` already exists (`name/toolSchema/act/available/requiresApproval`). Add a `Toolkit`
  registry: register/remove/reset tools, `getToolSchemas()` for the model, `getTool(name)`.
- Tool-call dispatch: parse model tool calls → look up in Toolkit → `act(args)` → wrap result.
- `LLMToolResponse` type (cf. `ToolResponse`): content blocks + state (SUCCESS/ERROR/INTERRUPTED/DENIED)
  + metadata. Today `act` just returns `String` — keep `String` ok for v1 but wrap in a response object.
- Tool error handling: a thrown `LLMToolException` becomes an ERROR tool-result fed back to the
  model (so the agent can recover) rather than aborting the loop.
- Tool groups (cf. `_tool_group.py`): optional — group tools and enable/disable groups at runtime.
- Approval flow: `requiresApproval()` already on the interface — wire a synchronous approval
  callback before executing such tools.

## 4. Built-in tools — AgentScope `tool/_builtin/`
- Port the core filesystem/shell set as needed: `Read`, `Write`, `Edit`, `Glob`, `Grep`, `Bash`.
  Each implements `LLMTool`. Start with whatever the project actually needs (likely Read/Write/Bash).
- `ResetTools` equivalent optional.
- **Task/plan tools** (cf. AgentScope `tool/_task/`: `TaskCreate/TaskGet/TaskList/TaskUpdate`):
  AgentScope has NO planner component — "planning" is just a todo list the agent manages via
  these builtin tools. Implement jllm's `plan` scope this way (builtin task tools), NOT as a
  separate agent type. The task list lives in `AgentState` (sect. 8).

## 5. Memory / conversation history — AgentScope memory module
- `LLMMemory` holding the ordered `List<LLMMessage>` for a session (add, get, clear).
- Agent reads full memory each reasoning step and appends assistant/tool messages.
- When calling an agent/tool should create a "child context so that the agent has its own 
  context to mangle without polluting the parent.
- Optional later: context compression / summarization when memory exceeds a token ratio
  (cf. `ContextConfig`, `_compress_context_impl`, `SummarySchema`). Defer unless needed.

## 6. Prompt construction — AgentScope formatter + `_get_system_prompt`
- `LLMPromptBuilder` (replace the Ollama-specific payload building): take system prompt +
  memory messages + tool schemas → the Ollama `/api/chat` request body.
- System prompt assembly: agent description/role + tool-use instructions + output-format hint
  (`LLMAgent.getOutputFormat()` already exists).

## 7. Structured output — AgentScope structured-model / response parsing
- Support a `structuredModel` / output schema on a request: instruct the model to emit JSON
  matching a schema, parse & validate the final reply into a typed object.
- Reuse `LLMObjectMapper`; the finish-tool (sect. 2) is the natural place for the schema.

## 8. State / session persistence — AgentScope `state/`
- `AgentState` snapshot: serialize agent memory + tool context to JSON (save/load).
- Lets a sequential session be paused/resumed. Use Jackson; mirror `AgentState` shape.

## 9. Hooks / middleware (synchronous) — AgentScope `middleware/`
- Optional, lower priority. Add pre/post-reasoning and pre/post-acting hook interfaces so
  callers can observe/modify the loop. Keep purely synchronous (no event bus).
- `stopOnReject`, budget/iteration limits live here too.

## 10. Classifiers — jllm-original (NO AgentScope equivalent)
AgentScope has NO classifier/router/planner: it sends every prompt through the single unified
agent loop and lets the model decide whether to act or answer. jllm's config-driven
`LLMClassifier` (a constrained single-choice LLM call returning one of N allowed values) is a
distinctive jllm primitive — a cheap synchronous **narrowing** step well suited to small/local
(Ollama) models. Decision: classifiers serve TWO roles (entry scope router + in-loop gates) and
gain a multi-select variant. They are NOT used to select tool-groups/skills/MCP targets, nor to
route to named agents (those stay model-driven / handled per sects. 3, 11–13).

### 10.0 Fix the broken primitive FIRST — `base/LLMClassifier.java`
- `classify()` calls the model with only `withPrompt(text)` and NEVER sends `buildPrompt()`, so
  the model is never told the allowed values; `buildPrompt()` is dead code. Wire it in: set
  `system = buildPrompt()` (or prepend) and `prompt = text`. Keep the retry loop + the
  dual-channel match (response then thinking). Without this, classifiers cannot work at all.
- Also fix the `getClassifier("scopeClassifer")` typo → `scopeClassifier` (also in sect. 0).

### 10.1 Multi-select variant — `base/LLMClassifier.java`, `base/LLMConfigClassifier.java`
- Add `boolean multi` (default false) to `LLMConfigClassifier` + getter on `LLMClassifier`.
- `buildPrompt()` branches: single → existing "reply with exactly one word"; multi → "reply with
  a JSON array containing only the applicable values".
- Add `List<String> classifyAll(LLMContext, String)`; `classify()` = first element (back-compat).
  Multi parsing: parse JSON array via `LLMObjectMapper`, intersect with allowed keys, fall back
  to scanning thinking text; exhausting `getRetries()` throws `LLMClassificationException`.
- Classifiers stay config-loaded + registry-keyed exactly as today (`LLMConfigManager`).

### 10.2 Entry scope router — `LLMEntryPoint.java`, `base/LLMRequest.java`
- Replace the hardcoded `switch` in `call()` with a scope → **agent profile** map (system prompt
  + temperature preset + react config). `scopeClassifier.json` already defines
  `plan/code/oneShot/research`.
- Reuse the EXISTING unused presets in `LLMRequest`: `plan→forPlan`, `code→forCoding`,
  `research→forExploring`, `oneShot→default`. Expose them (they're package-private + unused now),
  e.g. an `applyPreset(scope)` helper.
- `oneShot` → single `client.call`; the others → the unified agent loop (sect. 2) with task tools
  (sect. 4). This is the concrete wiring for the `plan` branch that is currently an empty `break`.
- Multi-scope result (if router is `multi`): resolve by fixed priority
  (`plan > code > research > oneShot`) initially; merging presets is a later option.

### 10.3 In-loop gates — the sect. 2 loop + new configs under `default/.jllm/classifiers/default/`
Named classifiers invoked as OPTIONAL synchronous hook steps in the loop (compose with sect. 9
hooks). No configured gate = skip, so the default behaviour is unchanged. Add a thin
`ClassifierGate` seam so the loop stays readable.
- **Completion gate** (after reasoning yields a no-tool-call answer): single-select
  `{finish, continue}` (`completionGate.json`). `finish` → return reply (default AgentScope
  behaviour); `continue` → inject a follow-up prompt and loop again (bounded by `maxIters`).
  Off by default so the natural "no tool calls = done" rule still holds.
- **Format/schema gate** (before finalizing): classifier whose values are output-format names;
  selects which structured-output schema (sect. 7) to validate the final reply against. Natural
  multi-select candidate.
- **Pre-act policy gate** (before executing a tool): optional classifier labelling a pending tool
  call (e.g. `{auto, needs-approval}`) feeding `LLMTool.requiresApproval()`. Keep light — security
  is out of scope; this is routing convenience, not an enforcement boundary.

### Ordering
10.0/10.1 are standalone and unblock everything (and fix a real bug) — do first. 10.2 depends on
the sect. 2 loop for non-oneShot branches; 10.3 depends on sect. 2 and dovetails with sects. 7 & 9.

## 11. Skills (Claude-Code-style) — AgentScope `skill/`
- `LLMSkill` value type: `name`, `description`, `dir`, `markdown` body, `updatedAt`
  (cf. AgentScope `Skill`).
- `LLMSkillLoader` interface with `listSkills()`; `LocalSkillLoader` scans a directory tree for
  `SKILL.md` files, parses YAML frontmatter (`name`/`description` required) + markdown body.
  Reuse the `.jllm/` config-dir convention already in `LLMConfigManager` (e.g. `.jllm/skills/`).
- Skill exposure: inject the available skills' `name`+`description` into the system prompt (cheap
  index); when the agent picks one, load that skill's full markdown into context on demand
  (progressive disclosure — do NOT dump every skill body up front).
- A skill can ship its own tools/scripts — let a skill dir optionally register tools into the
  Toolkit (sect. 3) when activated, grouped under the skill name (cf. tool groups).
- Synchronous: `listSkills()` returns directly, no async.

## 12. Remote MCP server connection — AgentScope `mcp/`
- `LLMMcpClient` (cf. `MCPClient`) over **HTTP/streamable-HTTP transport** (remote only; stdio
  optional). Config type `HttpMcpConfig` (url, headers/auth). Keep the call blocking/synchronous.
- Lifecycle: `connect()` / `close()` / `isConnected()`.
- `listTools()` → fetch the server's tool list and adapt each into an `LLMTool` (sect. 3) whose
  `act(args)` performs an MCP `tools/call` and maps the result to `LLMToolResponse`.
- `toolSchema()` comes straight from the MCP tool's input JSON schema.
- Register the adapted tools into the Toolkit grouped under the server name (tool group), and
  optionally filter to a subset of tool names at registration.

## 13. OpenAPI → tool wrapper — (no direct AgentScope equivalent)
- `LLMOpenApiToolset`: given an OpenAPI/Swagger doc (URL or file) + a base URL, parse each
  operation (operationId, path, method, params, requestBody schema) into one `LLMTool`.
- Tool name = operationId (or `method_path` fallback); `toolSchema()` built from the operation's
  parameters + requestBody JSON schema; `description` from summary/description.
- `act(args)`: map args → path/query/header params + JSON body, issue the blocking HTTP call
  (reuse the Apache HttpClient5 setup from `OllamaClient`), return response body as `LLMToolResponse`.
- **Grouping**: all operations from one doc register into the Toolkit under a single named group
  ("certain target") so they can be enabled/disabled as a unit (cf. tool groups, sect. 3).
- Auth/headers configurable per target. Security handling itself is out of scope here.

---

## Suggested order
1 (messages) → 3 (toolkit) → 2 (loop) → 4 (builtin tools) → 5 (memory) → 6 (prompt)
→ 7 (structured output) → 8 (state) → 9/10 (hooks, routing). Fix sect. 0 bugs first.
Sects. 11–13 (skills, MCP, OpenAPI) depend on the Toolkit (sect. 3) being in place; do them
after the core loop works.
