# Plan: Qwen-Coder-style agentic coding service for jllm

## Context

`jllm` today is a thin classify-then-answer library: `LLMEntryPoint.call(prompt)` classifies
the scope and does a single stateless `OllamaClient` round-trip. There is **no conversation
state**, **no tool invocation** (the `LLMTool` interface exists but is never called), and **no
persistence**.

The goal is to grow jllm into a "qwen coder"-like agentic coding assistant that reaches
**feature parity with the Qwen Code CLI** — the same built-in **tools**, the same **sub-agent**
delegation model, and the same **skills** (progressive-disclosure) mechanism — exposed as a
single-user, stateful chat that operates over one working directory in a real tool-calling loop,
with the conversation persisted. We build this as **programmatic service-layer APIs only** — the
user will wire up REST/DI/HTTP separately. Security and scalability are explicitly out of scope.

Decisions confirmed with the user:
- **No HTTP layer now** — expose plain Java service classes.
- **Persistence:** embedded SQL DB (recommend **H2 file mode** — pure-Java, no native lib).
- **Tooling:** full agentic loop + core file/shell tools that actually edit the directory.
- **Parity target:** match the Qwen Code CLI tool/agent/skill surface (see sections below),
  not a reduced subset.

### Parity reference — what the Qwen Code CLI exposes

These are the capabilities we mirror. Names in parens are the qwen/gemini-cli tool names we
keep as aliases so model prompting transfers directly:
- **File tools:** read file (`read_file`), write file (`write_file`), targeted edit /
  string-replace (`edit`/`replace`), list directory (`ls`/`list_directory`), find-by-glob
  (`glob`), content search (`grep`/`search_file_content`), batch read (`read_many_files`).
- **Shell:** run a command (`run_shell_command`/`shell`) in the workdir.
- **Web:** fetch a URL (`web_fetch`), web search (`web_search`).
- **Memory:** persist a durable fact (`save_memory`) into the project context file.
- **Sub-agents:** delegate a scoped task to a specialized agent (a `task`/`delegate` tool).
- **Skills:** progressive-disclosure capability packs (`SKILL.md` + bundled resources), surfaced
  by name/description and loaded on demand.
- **Context/memory files:** hierarchical `QWEN.md`/`JLLM.md` instruction files auto-loaded into
  the system prompt.
- **MCP (optional, later phase):** external MCP servers contributing tools — designed for but
  not implemented in the first pass (called out so the abstractions don't preclude it).

## Foundation fixes required first

These existing limitations block an agentic loop and must be addressed:

1. **`LLMRequest` carries only `prompt`+`system`** (`base/LLMRequest.java`). Multi-turn tool
   loops need a message list and a tool list. Add:
   - `List<LLMMessage> messages` (role: `system`/`user`/`assistant`/`tool`; fields: role,
     content, optional `toolCallId`, optional `toolName`).
   - `List<String> tools` (or `List<ObjectNode>`) holding the per-tool function JSON schemas.
2. **`format` defaults to `"json"`** (forces structured output — breaks chat/tool calling).
   Agentic requests must send `format` empty/null. Keep the field; just don't default it for the
   coding-session request builder.
3. **`OllamaClient.buildPayload`** (`ollama/OllamaClient.java`) hardcodes a single
   system+user message pair. Update it to emit the full `messages[]` from `LLMRequest.messages`
   when present (fall back to current behavior otherwise), and to add a top-level `tools` array
   when `LLMRequest.tools` is non-empty. Only set `format` when non-empty.
4. **`LLMResponse`** (`base/LLMResponse.java`) must parse `message.tool_calls[]` from Ollama
   (each = `function.name` + `function.arguments` object). Add `List<LLMToolCall> toolCalls`.
5. **Typo bug:** `LLMEntryPoint` calls `getClassifier("scopeClassifer")` (missing `i`) — the
   config file is `scopeClassifier.json`. Fix while here.

## New components

All under `jllm-lib/src/main/java/org/kendar/jllm/`.

### 1. Tool infrastructure (`tools/` package) — full Qwen Code tool set
- `LLMToolRegistry` — holds `Map<String,LLMTool>`, `register(LLMTool)`, `List<String> schemas()`
  (collects each `toolSchema()` for the request), `String dispatch(String name, Map<String,String> args)`.
  Supports tool **name aliases** (qwen/gemini names → our impls) so a tool resolves under either
  name. Also exposes an allow/deny filter so a sub-agent can be given a restricted tool subset.
- An optional `AbstractFileTool` base that centralizes the **working-directory sandbox** (resolve
  a path against the root, reject anything escaping via `..`/absolute paths). All file tools
  extend it.
- Core tools implementing existing `base/LLMTool` interface — the complete parity set:
  - **Filesystem:** `ReadFileTool` (`read_file`; offset/limit line ranges), `WriteFileTool`
    (`write_file`; creates parent dirs), `EditFileTool` (`edit`/`replace`; exact string replace
    with occurrence count + uniqueness check), `ListDirTool` (`ls`/`list_directory`),
    `GlobTool` (`glob`; find files by pattern), `GrepTool` (`grep`/`search_file_content`;
    content search, returns file:line), `ReadManyFilesTool` (`read_many_files`; batch read by
    paths/globs).
  - **Shell:** `ShellTool` (`run_shell_command`/`shell`) — runs in the workdir via
    `ProcessBuilder`, captures stdout/stderr/exit code, with a timeout.
  - **Web:** `WebFetchTool` (`web_fetch`) — uses the existing Apache HttpClient5 dependency;
    `WebSearchTool` (`web_search`) — pluggable search backend (interface + a simple default;
    config-driven endpoint, no key wired = returns a clear "not configured" message).
  - **Memory:** `SaveMemoryTool` (`save_memory`) — appends a fact to the project context file
    (see §6) so it persists across sessions.
  - **Delegation:** `DelegateTool` (`task`) — see §4 (sub-agents).
  - **Skills:** `SkillTool` — see §5 (load a skill body on demand).
  - `toolSchema()` returns the Ollama function-schema JSON (`{"type":"function","function":{name,description,parameters}}`).
  - `requiresApproval()` returns true for mutating tools (write/edit/shell); an approval callback
    is exposed on the session but defaults to auto-approve (security out of scope).

### 2. Persistence (`chat/` package) — H2 embedded
- Add H2 dependency to `jllm-lib/pom.xml` (`com.h2database:h2`).
- POJOs: `Conversation` (id, title, workingDir, createdAt, updatedAt),
  `ChatMessage` (id, conversationId, role, content, toolName, toolCallId, thinking, createdAt).
- `ChatStore` interface + `H2ChatStore` using plain JDBC (no ORM, matches the lean style).
  Schema auto-created on construction. Methods: `createConversation`, `getConversation`,
  `listConversations`, `appendMessage`, `getMessages(conversationId)`.

### 3. Agentic session (the API the user will wire to REST)
- `CodingSession` — constructed with: working dir, `LLMClient`, `ChatStore`,
  `LLMToolRegistry`, conversationId.
  - `String send(String userMessage)`:
    1. append user message to `ChatStore`.
    2. rebuild the `messages[]` from stored history (+ assembled system prompt).
    3. **loop** (bounded by a max-iterations guard): call `client.call(request-with-tools)`;
       if the response has `toolCalls`, run each through the registry, append a `tool` result
       message (persisted), and iterate; otherwise persist + return the assistant content.
  - System prompt assembles, in qwen-cli order: the active agent persona (reuse `code.xml` /
    other agents via `LLMConfigManager.getAgent`), the working-directory path + an `ls` snapshot,
    the loaded **context/memory files** (§6), the available **skills** catalog (name +
    description only — §5), and the tool list.
- `CodingSessionFactory`/builder to create or resume sessions by conversationId, choosing the
  root agent and the tool set.

### 4. Sub-agents (parity with qwen-cli subagent delegation)
Qwen Code lets the main agent delegate a scoped task to a specialized sub-agent that has its own
system prompt, model, and restricted tool set, then returns a single summarized result. Mirror
this so the existing `LLMAgent`/`LLMConfigAgent` XML configs become runnable sub-agents:
- Extend the agent config (or add an `agents/` discovery pass) so an agent declares: name,
  description, system prompt/persona, optional model override, and an **allowed-tools** list.
- `DelegateTool` (`task`) — args: `agent` (name) + `task`/`prompt`. It spins up a **nested
  agentic loop** (its own `LLMToolRegistry` filtered to the agent's allowed tools, a transient
  message history, the parent `LLMClient`), runs to completion with an iteration cap, and returns
  the final text to the parent as the tool result. Nested delegation is depth-capped to avoid
  runaway recursion.
- Sub-agent transcripts are **not** persisted as top-level chat turns; only the summarized
  result is appended to the parent conversation (matches qwen behavior and keeps the stored chat
  readable). Optionally store sub-agent turns in a child table keyed by parent message id.

### 5. Skills (parity with qwen/claude progressive-disclosure skills)
A skill is a folder with a `SKILL.md` whose YAML frontmatter has `name` + `description` (and
optional `allowed-tools`), plus an instruction body and optional bundled resource files.
- **Discovery:** scan `<workdir>/.jllm/skills/*/SKILL.md` and the default config dir; parse
  frontmatter into a `Skill` model (name, description, path, body, allowedTools).
- **Progressive disclosure:** only the catalog (name + one-line description) goes into the system
  prompt — never the full bodies. The model loads a skill on demand.
- **Activation:** a `SkillTool` (`skill` / `use_skill`) with arg `name` returns the full `SKILL.md`
  body (and a manifest of bundled resource paths) as the tool result, injecting those
  instructions into the live loop. Bundled scripts are runnable via the existing `ShellTool`.
- Reuse `LLMObjectMapper.getXmlMapper`-style parsing isn't right for YAML; add a tiny frontmatter
  splitter (parse the `---`-delimited header as simple `key: value` lines — no new YAML dep).

### 6. Context / memory files (parity with QWEN.md)
- Hierarchical instruction files auto-loaded into the system prompt: walk from the working
  directory upward (and the default config dir) collecting `JLLM.md` (accept `QWEN.md` as an
  alias) files; concatenate nearest-last so the closest file wins.
- The `SaveMemoryTool` (`save_memory`) appends a bullet to the workdir-level `JLLM.md` under a
  "## Added Memories" section, so durable facts survive across sessions — same mechanism qwen
  uses for `/memory add`.

## Critical files
- Modify: `base/LLMRequest.java`, `base/LLMResponse.java`, `ollama/OllamaClient.java`,
  `LLMEntryPoint.java`, `base/LLMConfigAgent.java` (+ system prompt / model / allowed-tools
  fields), `base/LLMConfigManager.java` (skills + context-file discovery), `jllm-lib/pom.xml`.
- New (base): `base/LLMMessage.java`, `base/LLMToolCall.java`.
- New (`tools/`): `LLMToolRegistry.java`, `AbstractFileTool.java`, and the parity tool set —
  `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListDirTool`, `GlobTool`, `GrepTool`,
  `ReadManyFilesTool`, `ShellTool`, `WebFetchTool`, `WebSearchTool`, `SaveMemoryTool`,
  `DelegateTool`, `SkillTool`.
- New (`skills/`): `Skill.java`, `SkillRegistry.java` (discovery + frontmatter parse).
- New (`context/`): `ContextFileLoader.java` (`JLLM.md`/`QWEN.md` hierarchical load).
- New (`chat/`): `ChatStore.java`, `H2ChatStore.java`, `Conversation.java`, `ChatMessage.java`.
- New (`session/`): `CodingSession.java` (+ factory), nested sub-agent loop helper.
- Reuse: `base/LLMObjectMapper` (Jackson), `base/LLMConfigManager` (agent/skill/context loading),
  `base/LLMTool` (tool contract), existing `LLMRequest.forCoding()` tuning, Apache HttpClient5
  (already a dependency) for `WebFetchTool`.

## Verification
1. `mvn clean install` — compiles both modules, runs existing tests (must stay green).
2. New unit tests:
   - `H2ChatStoreTest` — create conversation, append/read messages round-trip (in-memory H2 URL).
   - Tool tests — each parity tool against a temp dir: read/write/edit/ls/glob/grep/read-many,
     `ShellTool` (echo), `WebFetchTool` (local stub or skipped), `SaveMemoryTool` (appends to
     `JLLM.md`). Assert path-sandbox rejection for `../`/absolute escapes across all file tools.
   - `LLMToolRegistryTest` — alias resolution and allow/deny filtering.
   - `SkillRegistryTest` — discover a temp `SKILL.md`, parse frontmatter, catalog vs. full-body
     load via `SkillTool`.
   - `ContextFileLoaderTest` — hierarchical `JLLM.md` merge (nearest wins).
   - `OllamaClientTest` additions — payload serializes `messages[]` + `tools[]`, omits empty
     `format`, parses `message.tool_calls[]`.
   - `CodingSessionTest` — stubbed `LLMClient` returns a tool_call then a final answer; assert the
     tool ran, results were persisted, history is correct.
   - `DelegateToolTest` — stubbed client drives a sub-agent nested loop; assert only the summary
     is returned to the parent and the agent's tool allowlist is enforced.
3. Manual end-to-end (requires local Ollama): build a `CodingSession` over a scratch dir, send
   "create a file hello.txt with 'hi' then read it back", confirm the file exists and the
   assistant reports its contents; then "search the web for X and summarize" and a skill-driven
   task to exercise web + skill + delegate paths.
