# jllm-js: a JavaScript sandbox over the harness tools

Status: plan, nothing implemented. No Jira ticket yet, so no branch has been created.

## Goal

A new Maven module, `jllm-js`, that adds one tool, `run_js`. The model writes JavaScript; the script runs in GraalJS
(community edition) with no host access. Its only view of the world is the harness tools, which the script sees as a
generated, cached JavaScript library. File access is limited to the chat's virtual directory, through the Files tools.

It replaces the Python scrapbooking scripts. Those ran in one process with full host access. Here the same logic runs
in the sandbox, with file and web operations done through the standard Files and Web tools.

Non-goals for v1: npm packages, `require`, a persistent JS process outside a chat, browser automation, streaming output
to the UI.

## Decisions

| Topic | Decision | Why |
|---|---|---|
| Packaging | `jllm-js` module, depends on `jllm-harness`, installed as a `HarnessPlugin` via `META-INF/services` | Same shape as `jllm-pdf`. The harness keeps no GraalVM dependency. |
| Engine | GraalJS community: `org.graalvm.polyglot:polyglot` and `org.graalvm.polyglot:js` at `25.5.5.0.3` (latest on Maven Central), UPL/MIT, no enterprise artifacts | Requested. `org.graalvm.js:js-community` is deprecated, so it is not used. |
| Access to the world | Only through tools. No host object, no socket, no process, no `require`, no `fetch` | A sandbox, not a scripting host. |
| Virtual filesystem | File operations go through the Files tools, which are jailed to the chat's work directory. The script cannot touch anything else. | Helper files and outputs stay in the chat directory. |
| Permission path | The `run_js` approval covers the script itself (`ToolKind.OTHER`, preview = full code). Calls from the script to Files tools need no prompt. Calls to tools that leave the sandbox (Web group, MCP, REST) ask for approval per call, with the usual "grant for this chat" option. | Files stay inside the chat storage, so they are free. Web requests and external services are the risk, so they keep their prompt. |
| Bash from JS | The bridge refuses the name `bash`. The profile also removes it (see shell exclusion). | Defence in depth: the bridge does not depend on the registry being right. |
| Filesystem | Anything the Files tools allow inside the chat storage, from JS. | The user's choice. The only limit is the storage size below. |
| Storage size | `js.storage.maxBytes` in settings caps the total size of the chat storage. `-1` (default) means no limit. Writes that would exceed a positive limit fail. | Default `-1`: no limit. Set a positive byte count in settings to cap it. |
| Hooks and output | Tool calls from a script still run `PRE_TOOL` / `POST_TOOL` hooks and output sanitizing and taint marking | Audit and the prompt-injection defences keep working for data that enters the model's context. |
| Tool exposure | Generated JS module from each tool's `inputSchema`, cached by content hash | Model writes `tools.db_query({sql})` rather than guessing JSON. |
| Interpreter warning | Truffle's interpreter-only warning and engine diagnostics are logged only at DEBUG level for `org.kendar.llm.js` | Normal runs stay quiet; debugging shows everything. |
| Context reuse | One `Context` per chat, reused by its `run_js` calls. `Engine` shared. Never shared across chats. | Startup cost paid once per chat; no state leaks between chats. |
| Shell | `JsPlugin` and a `bash` tool are mutually exclusive. The build fails if both are present. | A host shell gives a second, unrestricted way out, including helper scripts. |

## Shell exclusion

Models do not ask for Python. Once `bash` is offered, a model that needs to scrape or transform data tends to write a
helper script with `write_file` (`scrape.py`) and run it with `bash python3 scrape.py`. The built-in `bash` tool
(`ShellAgentTools`) runs on the host under `LocalDirSandboxExecutor`, which has no isolation. The script would get the
network and the whole filesystem, and `run_js` would be optional, not the boundary.

Permission rules do not close this: [SANDBOX](../docs/SANDBOX.md) says that `python -c` and quoting bypass a regex. The
boundary is the absence of the tool.

Rules:

1. A `run_js` profile is the standard tools without `bash`, plus `JsPlugin`. The profile removes `bash` explicitly.
   `withoutStandardTools()` is not enough: it also drops Files and Web.
2. `JsPlugin` declares `requireAbsent("bash")`. If a `bash` tool is registered when the harness is built (or added
   later through `HarnessToolRegistry.setSource`), the build fails with a message that says run_js and bash cannot
   coexist.
3. The reverse holds too: a harness with `bash` cannot install `JsPlugin`.
4. A `deny` rule on `bash` is kept as a second layer. It hides the tool, it does not remove it, so it is not enough alone.
5. The `docker` backend for shell is not part of the JS profile. It can be a separate profile without `run_js`.
6. Sub-agents inherit the tool set. A test must check that a sub-agent of a `run_js` chat cannot get `bash`.
7. MCP stdio servers start host processes from the settings file. That is configuration, not a model action. It is
   outside the sandbox and must be listed in the profile docs.

Honest scope: the "no host access except tools" guarantee holds only in a profile where no tool gives host access. The
module docs must say so.

## What the harness needs first (core change)

**1. Selective removal of standard tools.** `Builder.withoutTools(Collection<String> names)`. It applies to the tool list
before the registry is built (`AgentHarness.java`, around line 640), so the tools are absent, not hidden.

**2. Build-time exclusion.** `PluginRegistrar.requireAbsent(String toolName)`. The builder checks it at `build()` and in
`HarnessToolRegistry.setSource`, and fails with `IllegalStateException` on a clash.

**3. Tool catalog for the plugin.** A plugin's `install(PluginRegistrar)` gets only the registrar. It cannot see tools
collected later, nor MCP or REST tools, which are added after `build()` and can change at runtime. `ToolContext` has no
registry either. So:

- New interface `org.kendar.llm.harness.tools.ToolCatalog`:
  - `Collection<HarnessTool> tools()`: the enabled tools, the same view the model gets.
  - `String invoke(ToolContext ctx, String name, JsonNode input)`: runs one tool with `PRE_TOOL` hooks, execution,
    `POST_TOOL` hooks, output sanitizing and taint marking. It calls `PermissionGate` only for external tools, as below.
- `PluginRegistrar.catalog()` returns a lazy handle backed by the registry, so it sees MCP and REST tools as they come
  and go.
- `invoke` must not be able to call `run_js` (no recursion), and it must refuse tools hidden by a `deny` rule, and the
  name `bash`.
- `invoke` applies `PermissionGate` for external tools only: the Web group, and tools from MCP and REST sources. Files
  tools skip it. Which group or source counts as external is a list in the code, not inferred from the tool.
- The model loop keeps the gate. The invoke path is extracted from `AgentLoop` so both callers share the hook, execution
  and sanitizing code, without the gate in the shared part.

**4. Storage quota on the write path.** The size check belongs where bytes are written, so it covers writes from the
model and from JS alike. `FileSystemAgentTools` checks the chat storage total (a running counter per chat, recomputed
once at startup) before a write, and fails the write if the new total would exceed `js.storage.maxBytes`, unless the value is `-1`. The check is
in the core, so the limit is also set by the harness, not only by the plugin.

The change lives in `jllm-harness` and needs its own tests before `jllm-js` depends on it.

## Module layout

```
jllm-js/
  pom.xml                       org.graalvm.polyglot:polyglot + :js (community), depends on jllm-harness
  src/main/java/org/kendar/llm/js/
    JsPlugin.java               HarnessPlugin: requireAbsent("bash"), registers run_js
    RunJsTool.java              HarnessTool: input {code, timeoutSeconds?}, kind OTHER, preview = code
    JsSandbox.java              builds the locked-down Context, runs code, enforces limits
    ChatContexts.java           one Context per chatId, closed on chat delete and shutdown
    ToolLibrary.java            generates the JS module from ToolCatalog.tools(), with cache
    ToolLibraryCache.java       content-addressed files on disk
    JsBridge.java               the single host object exposed to JS: invoke(name, json) -> string
  src/main/resources/META-INF/services/org.kendar.llm.harness.plugin.HarnessPlugin
  src/test/java/...
```

## The generated JS library

- One function per enabled tool. Name: the tool name with `-` and `.` replaced by `_`. The `__` separator from MCP and
  REST is kept, so `mcp__docs__search` maps to `tools.mcp__docs__search`. A collision after mapping fails generation
  with a clear error.
- Each function takes one object, serialises it to JSON, calls `JsBridge.invoke`, and parses the result if it is JSON;
  otherwise it returns the string. Optional arguments are documented in JSDoc only.
- A JSDoc block per tool (description, group, kind). A short index of the same content goes in the `run_js` description,
  so the model sees the API without a tool call.
- Cache: `<sandbox.tempDir>/js-tools/<sha256>.js`. The hash covers the sorted (name, description, inputSchema, group,
  kind) list. A change produces a new file. Writes are atomic (temp file, then move). Old files are removed after a TTL.
- The library is loaded into a chat's Context when the hash changes, not on every call.

## The sandbox

Each chat has one `Context`, built on a shared `Engine`. The `Context` is single-threaded, so `run_js` calls within one
chat are serialised with a lock. A context that was cancelled or timed out is closed and replaced on the next call. All
contexts of a chat are closed when the chat is deleted, and on shutdown.

Each script runs in its own function scope, so `const`/`let` do not leak between calls. Writes to the global object
persist within the chat, so a scraping session can keep state across calls. They never reach another chat.

Locks, per `Context` build:

| Surface | Setting |
|---|---|
| Files | no I/O access (`IOAccess.NONE` or the equivalent for the chosen version) |
| Network | no host socket access; `fetch`, `XMLHttpRequest`, `WebSocket` not enabled |
| Host classes, `Java.type` | host access `NONE`, no host class lookup |
| Processes, threads, native code | create-process, create-thread and native access denied |
| Environment variables | denied |
| `load`, `read`, CommonJS `require` | disabled |
| Time | wall-clock watchdog that calls `Context.cancel`; default 30 s, maximum from settings |
| Statements | statement limit, to stop tight loops |
| Memory | Context memory limit where the version supports it |
| Output | `console.log` buffered and capped at 50 KB (same convention as `bash`); the result is serialised to JSON |
| Errors | returned as `error: <message> at line N`; stack cut to 20 lines |
| Engine warnings | `engine.WarnInterpreterOnly` off unless DEBUG logging is on for `org.kendar.llm.js` |

Available globals: the ECMAScript built-ins, `console`, `tools`. Nothing else.

Anything the Java tools return is data, and it can contain instructions. It goes through the output sanitizer and taint
marking, not into the script as trusted input.

## Tests

1. Lock tests, each must fail the script: read or write a file outside the tools, `fetch`, `new XMLHttpRequest`,
   `Java.type`, `Packages.java.lang.Runtime`, `require('fs')`, `load('x.js')`, reflection through a host object returned
   by a tool, environment access, infinite loop, runaway allocation, deep recursion. `@ParameterizedTest` with
   `@MethodSource`.
2. Generation: deterministic output, cache hit and miss, name mapping, collision error, MCP and REST names.
3. Bridge: a script calls a Files tool and gets its result with no approval request; a script calls a Web tool and gets
   an approval request, and a denial reaches the script as an error; an MCP or REST tool called from a script asks too;
   the `run_js` approval preview contains the full script; `PRE_TOOL` hook can deny or rewrite an inner call; output
   sanitizing and taint apply to inner results; a tool hidden by a `deny` rule is not callable from JS; the name `bash`
   is refused even when a tool of that name is registered.
3a. Storage: a write that would exceed `js.storage.maxBytes` fails, from JS and from the model, and creates no file; the
    total is kept across restarts; a rejected write does not change the total.
4. Lifecycle: one Context reused across calls in a chat; a second chat gets its own Context; cancel and timeout stop a
   script and the next call gets a fresh Context; deleting a chat closes its Contexts; concurrent `run_js` calls in one
   chat are serialised.
5. Shell exclusion: `withoutTools("bash")` removes the tool from `registry.every()`, and a direct call to it fails; a
   build with `JsPlugin` and `bash` fails; `setSource` that adds a tool named `bash` while `JsPlugin` is installed fails.
6. Interpreter warning: absent at normal log level, present at DEBUG.
7. Sub-agent: a sub-agent of a `run_js` chat gets the same tool list, without `bash`.
8. End to end: the scrapbooking workflow, with fake HTTP tools, produces the same output as the original script on the
   same input.

## Migration of the scrapbooking script

The script's data lives in the chat's virtual file storage, the chat work directory the Files tools are jailed to. Every
file and web operation of the script maps to the standard Files and Web tools. Web calls ask for approval; after a per-chat
grant of the tool, later calls to it do not prompt again. The script's HTTP fetches become
`web_fetch`, and its reads and writes become `read_file` and `write_file` in the chat directory. Any other side effect
(such as a database write) needs a tool, which is a Java tool the user registers.

1. List each operation in the script and map it to a tool. Anything with no tool becomes a new tool with an explicit kind.
2. Keep the logic (parsing, transforming, deduplication) in JS. It needs no host.
3. Run the old and new versions on a fixed input and diff the output.
4. Only then switch the trigger over to the agent.

## Milestones

| # | Work | Done when |
|---|---|---|
| 1 | Core change in `jllm-harness`: `withoutTools`, `requireAbsent`, `ToolCatalog` with the invoke path extracted from `AgentLoop`, storage quota on writes | existing harness tests green; new tests for 1 to 4 green |
| 2 | `jllm-js` skeleton: Maven module, Graal dependencies, `JsPlugin` discovered via `discoverPlugins()` | `run_js` appears in `/tools`, runs `return 1+1` |
| 3 | Sandbox locks and limits, chat-scoped Context | lock tests fail the script as expected; lifecycle tests green |
| 4 | `ToolLibrary` generation and cache, index in the `run_js` description | generation and cache tests green |
| 5 | Bridge: catalog invoke, hooks, sanitizing, taint, web and external approval, `bash` refusal | bridge tests green |
| 6 | Wire into `jllm-cli` and `jllm-web` (discovery, `/help`), profile `withoutTools("bash")` + `JsPlugin`, `docs/JS.md`, README row | `run_js` usable from both front ends; shell exclusion tests green |
| 7 | Port the scrapbooking script, compare outputs | outputs match on the reference input |

Milestone 1 is the riskiest. It changes the invoke path that every tool call uses.

## Risks and open questions

- **Approval scope.** The `run_js` approval covers the script's code. Web, MCP and REST calls from the script each ask
  for approval, so a script can still make many requests. The approval preview must show the full script. The list of
  external groups and sources needs confirming, because it decides which calls are free.
- **Plugin settings.** `js.storage.maxBytes` and the other JS options must be read from the settings file. How a
  plugin reads its own section of that file is not checked yet; the fallback is a constructor argument on `JsPlugin`.
- **Graal sandbox API.** The option names above follow the GraalVM Polyglot API. Exact methods depend on the version.
  Check each lock against that version's Javadoc in milestone 3, and let the lock tests prove it.
- **Dependency resolution.** Neither artifact is in the local Maven cache yet. Check the dependency tree with
  `./mvnw dependency:tree` once the module exists, to confirm it has only community artifacts.
- **Shell for builds.** The JS profile has no shell. Anything that needs a build tool needs a different profile.
- **Ticket and branch.** No Jira ticket yet, so the branch and commit naming from the global rules is deferred.

## References in this repo

- Tool contract: [TOOLS](../docs/TOOLS.md), `HarnessTool`, `SimpleTool`, `ToolContext`
- Plugins: [EXTENDING §3](../docs/EXTENDING.md#3-plugins-and-slash-commands), `jllm-pdf` as the reference module
- Sandboxes and their guarantees: [SANDBOX](../docs/SANDBOX.md)
- Permission gate and approval: [EXTENDING §4](../docs/EXTENDING.md#4-approval-policy), `PermissionGate`, `ToolApprovalPolicy`
- Sub-agents: `SubAgentInheritTest`, to be extended in milestone 6
