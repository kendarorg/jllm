# jllm-harness: flow

Source of truth: `src/main/java/org/kendar/llm/harness`. Class names below are real; line-level details can drift, re-check against code when changing behavior.

## 1. Components

```mermaid
flowchart TB
    subgraph FE["Front ends"]
        Main["jllm-cli Main (REPL)"]
        Web["Embedder / web UI"]
    end

    subgraph Facade["AgentHarness (facade + Builder)"]
        SC["SlashCommands / MenuController"]
        AS["AgentService<br/>1 run per chat, event buffer, cancel"]
    end

    subgraph Core["Agent core"]
        AL["AgentLoop<br/>ReAct loop"]
        TS["ToolSelector"]
        RTG["RequestToolGroupTool"]
        SAT["SubAgentTool (task)"]
        AC["AgentCatalog<br/>general / explore / plan / custom"]
    end

    subgraph Policy["Permission"]
        PG["PermissionGate<br/>mode x kind"]
        TAP["ToolApprovalPolicy"]
        APS["ApprovalService<br/>pending + grants"]
        CL["ToolClassifier<br/>(auto mode, fast model)"]
    end

    CM["ContextManager<br/>3-tier compaction"]
    HK["Hooks<br/>8 events"]
    SS["ChatSessionService<br/>+ ChatSessionStore<br/>+ SubAgentRunLog"]

    subgraph Tools["Tools"]
        REG["HarnessToolRegistry<br/>sorted group,name"]
        FS["Files: read_file write_file edit_file glob grep"]
        SH["Shell: bash"]
        WB["Web: web_search wikipedia_search web_fetch"]
        SK["skill"]
        EXT["ExternalSources<br/>mcp__srv__tool / api__operationId"]
    end

    SBX["SandboxExecutor<br/>Local / Cwd / Docker"]

    subgraph LLM["LLM layer"]
        MC["ModelClient"]
        LMC["LimitedModelClient<br/>(serializeModelCalls)"]
        ANT["AnthropicModelClient"]
        OAI["OpenAiCompatModelClient<br/>openai qwen llamacpp vLLM ollama"]
        FAST["fast ModelClient (optional)"]
    end

    Main --> Facade
    Web --> Facade
    AS --> AL
    AL --> TS & RTG & SAT & PG & CM & HK & SS & MC
    SAT --> AC
    SAT -->|runSubAgent| AL
    PG --> TAP
    AL --> APS
    AL --> CL
    AL --> REG
    REG --> FS & SH & WB & SK & EXT
    SH --> SBX
    FS --> SBX
    MC --> LMC --> ANT & OAI
    TS -.-> FAST
    CM -.-> FAST
    CL -.-> FAST
```

The fast model is optional. If none is configured, `ContextManager` and `ToolSelector` use the main model, and `ToolClassifier` is null (auto mode never asks).

## 2. Startup: `AgentHarness.Builder.build()`

```mermaid
flowchart TD
    A["settings.applyTo(config)"] --> B{"serializeModelCalls?"}
    B -->|yes, default| C["wrap modelClient in LimitedModelClient(1)"]
    B -->|no| D
    C --> D["sandbox = given or LocalDirSandboxExecutor"]
    D --> E["collect tools: Files + Shell + Web (unless withoutStandardTools) + injected + SkillTool"]
    E --> F["HarnessToolRegistry (dup names rejected, sorted by group,name)"]
    F --> G["ExternalSources: discover MCP + REST tools, setSource()"]
    G --> H["ChatSessionService, ApprovalService"]
    H --> I["fast model = fastModelClient or settings.fastModel"]
    I --> J["ContextManager, ToolSelector, RequestToolGroupTool, SubAgentTool(AgentCatalog)"]
    J --> K["system prompt = system-prompt.md + builder addendum + instruction files"]
    K --> L["AgentLoop(... hooks, ToolClassifier if fast model)"]
    L --> M["AgentService(loop)"]
    M --> N["AgentHarness + plugin commands registered"]
```

`jllm-cli` Main additionally wires `CwdSandboxExecutor` (workdir = project dir, scratch under `.kendhar/scratchpad`) and `FileHistory*` stores (`.kendhar/history/<chatId>/<agentId>/messages.jsonl`).

## 3. One user turn, end to end

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant FE as Main / UI
    participant H as AgentHarness
    participant S as AgentService
    participant L as AgentLoop
    participant Hk as Hooks
    participant CM as ContextManager
    participant LLM as ModelClient
    participant T as Tools

    U->>FE: text
    alt starts with "/"
        FE->>H: commands().execute() (no LLM, except /compact)
    else normal message
        FE->>H: subscribe(chatId, 0, listener)
        FE->>H: start(chatId, text)
        H->>S: start (throws RunAlreadyActive if busy)
        S-->>FE: event run_started
        S->>S: acquire run slot if serializeRuns (else run_queued)
        S->>S: maybeTitle()
        S->>L: run(chatId, text, sink, cancelToken)
        L->>Hk: USER_PROMPT (can deny / add context)
        L->>Hk: PRE_AGENT main (can deny)
        L->>L: append user msg, save
        L->>L: chatTools(): pick tool groups once per chat
        loop up to maxTurns (default 40)
            L->>CM: compactIfNeeded (see section 6)
            L->>Hk: PRE_LLM (can deny)
            L->>LLM: complete(ModelRequest)
            LLM-->>L: ModelResponse (content, stop_reason, usage)
            L->>Hk: POST_LLM
            L-->>FE: events usage, text
            alt stop_reason = tool_use
                L->>T: executeTool x N in parallel (section 4)
                T-->>L: tool_result blocks
                L->>L: append user msg with results, save
            else end_turn / stop_sequence
                L->>L: return final text
            end
        end
        L->>Hk: POST_AGENT
        opt mode = PLAN and answer not blank
            L-->>FE: event plan_ready
        end
        L->>Hk: STOP
        S-->>FE: event run_finished
    end
```

### Stop reasons handled in the loop

| `stop_reason` | Behavior |
|---|---|
| `tool_use` | run tools, loop again |
| `end_turn`, `stop_sequence` | return last text |
| `pause_turn` | `continue`, resend with assistant content as is |
| `max_tokens` | warning; text only: ask to continue (max 3 in a row); cut `tool_use`: complete calls run, the cut one gets a "Not executed: … cut off" result (a cut `write_file` keeps its complete lines in scratch), retried once |
| `refusal`, `model_context_window_exceeded` | warning; return |
| context-overflow error | `compactNow` once, retry same turn (`turn--`); second failure is an `error` event |
| cancel | `cancelled` event, return |
| `maxTurns` reached | warning "Stopped after N turns" |

## 4. Tool call pipeline (`AgentLoop.executeTool`)

All `tool_use` blocks of one response run concurrently on virtual threads. Results are collected in order; every `tool_use` always gets a `tool_result` (cancel yields "Cancelled by user").

```mermaid
flowchart TD
    S["tool_use block"] --> E1["emit tool_call"]
    E1 --> K{"tool in this turn's tool list?"}
    K -->|no| R1["result: Unknown tool (error)"]
    K -->|yes| C{"cancelled?"}
    C -->|yes| R2["result: Cancelled by user"]
    C -->|no| PRE["Hook PRE_TOOL<br/>may deny / rewrite input / add context"]
    PRE --> G["PermissionGate.check(mode, tool)"]
    G --> D1{"decision"}
    D1 -->|ALLOW| AUTO{"mode AUTO + classifier<br/>+ tool not READ_ONLY<br/>+ not hook-denied?"}
    D1 -->|ASK| GR
    AUTO -->|no| GR
    AUTO -->|yes| CLS["ToolClassifier (fast model)<br/>SAFE / UNSAFE / UNSURE"]
    CLS -->|SAFE| GR
    CLS -->|else| ASK["decision = ASK"] --> GR
    GR{"ASK and chat grant exists?"}
    GR -->|yes| ALLOW["ALLOW"]
    GR -->|no| NI{"ASK and not interactive?<br/>(sub-agent)"}
    NI -->|yes| DENY["DENY"]
    NI -->|no| HD
    ALLOW --> HD
    DENY --> HD
    HD{"hook denied?"}
    HD -->|yes| R3["result: Denied by a hook"]
    HD -->|no| DEC{"decision"}
    DEC -->|DENY| R4["result: Denied by permission policy"]
    DEC -->|ASK| AP["ApprovalService.await<br/>emit approval_request, block"]
    DEC -->|ALLOW| RUN
    AP -->|rejected / timeout / cancel| R5["result: user rejected"]
    AP -->|approved| RUN["tool.execute(ctx, input)<br/>truncate output at 30,000 chars"]
    RUN --> POST["Hook POST_TOOL<br/>may append context to result"]
    POST --> OUT
    R1 & R2 & R3 & R4 & R5 --> OUT["metrics + emit tool_result (output cut at 2,000 chars)"]
```

Notes:
- Order matters: hook, then gate, then classifier, then settings `ask` rule, then chat grant, then `allow` rule, then non-interactive deny, then hook-deny check; a settings `deny` rule wins over all of them (see [USAGE](USAGE.md#permission-rules)). A "remember" approval (`a`) adds a grant for the tool name for the whole chat, sub-agents included. A grant only replaces the question; deny rules, hooks and plan confinement still apply.
- `ApprovalService.await` serializes dialogs per chat (fair lock), timeout (default 600 s) starts when shown.
- `Decision` is computed by `PermissionGate`, pure policy:

```mermaid
flowchart LR
    M{"mode"} -->|AUTO| A1["ALLOW"]
    M -->|EDIT or PLAN| K{"tool.kind"}
    K -->|FILE_EDIT| A2["ALLOW"]
    K -->|other| P
    M -->|DEFAULT| P{"policy.requiresApproval(tool)<br/>(byKind: everything not READ_ONLY)"}
    P -->|yes| Q["ASK"]
    P -->|no| A3["ALLOW"]
```

### Modes

| Mode | Working dir | Scratch dir | Asks |
|---|---|---|---|
| DEFAULT | per tool policy | writable | every non-READ_ONLY tool |
| PLAN | read-only (file tools confined to scratch via `ToolContext.readOnlyWork`; shell runs in scratch; Docker mounts workdir read-only) | writable | shell and other non-FILE_EDIT tools |
| EDIT | writable | writable | shell and other non-FILE_EDIT tools |
| AUTO | writable | writable | nothing, unless the classifier says not SAFE |

The mode note is the last text of `extraSystem`, so switching modes keeps the cached tool block and the text before the note (see CACHES.md §1).

## 5. Tool set per chat

```mermaid
flowchart TD
    F["first message of a chat"] --> Q{"registry size > toolBatchThreshold (12)?"}
    Q -->|no| ALL["groups = null: offer every tool"]
    Q -->|yes| LLM["ToolSelector.chooseGroups<br/>fast/main model simpleComplete, 256 tokens<br/>input: request + group/tool catalogue<br/>output: JSON array of groups"]
    LLM -->|parse fail / empty| ALL
    LLM -->|ok| G["groups = chosen + coreGroups (Files, Shell)"]
    G --> ST["sessions.setToolGroups (persisted)"]
    ALL --> ST
    ST --> TL["every turn: tools = forGroups(...)<br/>+ request_tool_group if groups are missing<br/>+ task"]
    TL -->|model calls request_tool_group| ADD["sessions.addToolGroups"] --> TL
```

The set is stable on purpose (prompt-cache prefix). It only grows, and the new tools apply from the next model call because `toolSource` is re-read every turn.

## 6. Context strategy

`AgentLoop` calls `ContextStrategy.prepare(ContextTurn)` at the top of every turn and sends the `ContextView` it returns;
the log is saved only when the view says `logChanged`. A too-long rejection calls `onOverflow` once; `/compact` calls
`compactNow`. Two implementations: `context.compaction.CompactionStrategy` (below, the default) and
`context.mnemonic.MnemonicStrategy` (log never rewritten; masking batches, segment handoffs, `history_recall`; see the
README). Selected by `config.contextStrategy()` (settings `context.strategy`, CLI `--context-strategy`) or a builder
`contextStrategy(factory)`.

### Compaction (`ContextManager`)

Runs at the top of every loop turn. Ceiling = window - buffer (buffer = 15% of window, min 16k when window >= 32k). Size = max(chars/4, last measured usage).

```mermaid
flowchart TD
    S["turn start"] --> A{"compaction.auto and size > ceiling?"}
    A -->|no| X["no change"]
    A -->|yes| T1["Tier 1 microcompact (no LLM)<br/>blank old tool_result bodies<br/>older than keepRecentTurns (6)"]
    T1 --> B{"fits?"}
    B -->|yes| Done["done"]
    B -->|no| T2["Tier 2 summarize older turns (LLM)<br/>keep newest keepTokens (15k) verbatim"]
    T2 --> C{"fits?"}
    C -->|yes| Done
    C -->|no| R{"allowRewrite?<br/>(main agent only)"}
    R -->|no| Done
    R -->|yes| D{"user declined recently<br/>and size < 1.2 x declinedAt?"}
    D -->|yes| Done
    D -->|no| T3["Tier 3: propose aggressive rewrite (LLM)<br/>approval type=context, user can edit"]
    T3 -->|approved| RW["replace span with summary"] --> Done
    T3 -->|declined| DEC["remember declinedAt"] --> Done
```

Summary request (`summarizeSpan`):

```mermaid
flowchart TD
    S["summarizeSpan(span)"] --> P{"Prefix usable?<br/>(same client as agent)"}
    P -->|yes| CP["send agent's own request<br/>(same system, tools, messages)<br/>+ summary instruction as last user turn<br/>=> provider prompt cache hit"]
    CP -->|ok| OUT["summary text"]
    CP -->|context overflow| SH
    P -->|no| SH["plain simpleComplete(compaction-system, instruction + transcript)"]
    SH -->|overflow| SH2["retry shorter: render levels 0..3<br/>(clip outputs, no outputs, user text only)<br/>then cut history to 85/70/50/35% of first refused size"]
    SH2 -->|still refused| NONE["null: nothing to compact"]
    SH -->|ok| OUT
    SH2 -->|ok| OUT
```

- Prompt: `compaction-create.md` first time, `compaction-update.md` when the span already starts with `[Summary of the earlier conversation]` (merge old summary with newer history). Template/rules in `compaction-template.md`, `compaction-rules.md`.
- Cuts always land on a plain user text message, so a `tool_use` is never separated from its `tool_result`.
- Manual `/compact [focus]`: `AgentLoop.compact` -> `compactNow` (tier 1 with keepTurns=1, then summarize all but the latest turn, no approval). Rejected while a run is active.
- Sub-agents with inherited context: `compactInherited` compacts the agent's own messages first, summarizes the parent copy only if still over, never asks the user.
- Overflow retry: the provider rejecting a request as too long triggers `compactNow` once, then retries the turn.

## 7. Sub-agents (`task` tool)

`task` is a `READ_ONLY` tool added only to the main agent's tool list. Sub-agents never see `task` or `request_tool_group`.

```mermaid
sequenceDiagram
    autonumber
    participant M as Main loop
    participant ST as SubAgentTool (task)
    participant SL as AgentLoop.runSubAgent
    participant LLM as ModelClient
    participant U as User (approvals)

    M->>ST: execute({subagent_type, prompts[<=8]})
    ST->>ST: type = AgentCatalog.get(name)
    ST->>ST: serial if (!parallel) or (writable type and mode EDIT/AUTO)
    par per prompt (parallel) or one after another (serial, global lock)
        ST->>SL: runSubAgent(chatId, toolUseId, prompt, type, parentMode, run)
        SL->>SL: fresh messages [+ copy of parent history if inheritContext]
        SL->>SL: mode = PLAN if readOnly type else parentMode
        SL->>SL: tools = ToolSelector.select(prompt, registry filtered by readOnly + type.tools)
        SL->>SL: extra = system + subagent-suffix (+ readonly-suffix) (+ type prompt)
        SL->>SL: hook PRE_AGENT (deny => task entry fails)
        loop own ReAct loop (persist=false)
            SL->>LLM: complete (type.model or main model)
            SL->>SL: tool calls through same pipeline; ASK skipped if chat grant
            SL-->>U: approval_request forwarded to parent sink, label "[general #2] tool"
        end
        SL->>SL: hook POST_AGENT
        SL->>SL: bill usage to chat, record SubAgentRunData (transcript, answer, status)
        SL-->>ST: answer text or failure
    end
    ST-->>M: JSON array [{prompt, status ok|failed, result}]
```

Built-in types:

| Type | Tools | Read-only | Prompt |
|---|---|---|---|
| `general` | all (filtered by selector) | no | none |
| `explore` | `read_file`, `glob`, `grep` | yes (PLAN) | `agent-explore.md` |
| `plan` | all READ_ONLY tools | yes (PLAN) | `agent-plan.md` |
| custom (settings `agents`) | `tools` list or all | only if `readOnly: true` | `prompt` / `promptFile`, optional own `model`, `inheritContext` |

Differences from the main loop:
- Transcript is not in the chat log; it goes to `SubAgentRunLog`. Token usage is added to the chat.
- A model failure inside becomes a failed `task` entry, never an empty "ok".
- Inherits the caller's mode (never looser), permission rules, hooks, chat grants. Approvals it needs are raised through the parent's sink and answered by the user (one dialog per chat at a time).
- Its own scratch dir: `<agent>-<uuidv7>`.

## 8. LLM calls

Every model call in the system, who makes it, and with what:

```mermaid
flowchart LR
    subgraph Calls["Call sites"]
        C1["AgentLoop.loop<br/>complete(ModelRequest, cancelToken)<br/>tools, history, maxTokens=8192"]
        C2["ContextManager.summaryOnPrefix<br/>complete(agent's request + summary ask)"]
        C3["ContextManager.summarizeSpan fallback<br/>simpleComplete, 3000 tokens"]
        C4["ToolSelector.chooseGroups<br/>simpleComplete, 256 tokens"]
        C5["ToolClassifier.classify<br/>simpleComplete, 60 tokens, 20 s timeout"]
    end
    C1 & C2 --> MAIN["main ModelClient<br/>(or sub-agent type.model)"]
    C3 & C4 & C5 --> FASTQ["fast ModelClient if set, else main"]
    MAIN & FASTQ --> LMC["LimitedModelClient<br/>fair semaphore, 1 slot when serializeModelCalls"]
    LMC --> P{"provider"}
    P --> ANT["AnthropicModelClient<br/>POST /v1/messages (non-streaming)"]
    P --> OAI["OpenAiCompatModelClient<br/>POST /chat/completions<br/>retry on 429 / 5xx with backoff or retry-after"]
```

`ModelRequest` = `pinnedSystem` (`chatId=...; agentId`, never rewritten) + `extraSystem` (base prompt + date + mode note) + `messages` (raw Anthropic-style JSON, provider clients translate) + `tools` + `maxTokens` + `toolChoice` + `disableParallelToolUse` (= `!parallelToolUse`).

Anthropic request layout (prompt-cache friendly):
1. `system[0]` pinned block, `system[1]` extra block with `cache_control: ephemeral` (ttl `1h` if configured).
2. `tools[]` sorted by group, name; last tool carries `cache_control`.
3. `messages` as is; RAG tool results (`search_local_knowledge`) are converted to `search_result` blocks when citations are on.
4. `tool_choice` only set when non-auto or parallel tool use is disabled; forced choice is dropped if extended thinking is on.

Cancel: `CancelToken` aborts the in-flight HTTP call and backoff sleeps (`ModelCancelledException`). `LimitedModelClient` waiters are interrupted by it too.

## 9. Hooks

```mermaid
sequenceDiagram
    participant L as AgentLoop
    participant H as Hooks
    L->>H: USER_PROMPT (deny, add context)
    L->>H: PRE_AGENT (deny)
    loop each model call
        L->>H: PRE_LLM (deny => run ends with error)
        L->>H: POST_LLM (observe, isError on failure)
    end
    loop each tool call
        L->>H: PRE_TOOL (deny, rewrite input, add context)
        L->>H: POST_TOOL (add context to result)
    end
    L->>H: POST_AGENT (observe, always, even on failure)
    L->>H: STOP (observe, main agent only)
```

Hooks run in registration order. The first denial stops the chain; input rewrites chain; added context is concatenated. A hook that throws is logged and skipped. Sub-agents fire `PRE/POST_AGENT` and `PRE/POST_LLM` nested inside the parent's `task` tool call, with `ctx.agent()` = type name (`main` for the main agent).

## 10. Events, cancellation, approvals (front-end contract)

```mermaid
sequenceDiagram
    participant FE as Front end
    participant S as AgentService
    participant L as AgentLoop
    participant A as ApprovalService

    FE->>S: subscribe(chatId, afterSeq, listener)
    S-->>FE: replay buffered events (seq > afterSeq, max 2000), then live
    FE->>S: start(chatId, text)
    S-->>FE: run_started
    L-->>FE: text, usage, tool_call, tool_result, compaction, classifier, warning, error
    L->>A: await(...) blocks the tool thread
    L-->>FE: approval_request {id, title, preview, proposed?, agent?}
    FE->>S: resolveApproval(chatId, id, approved, edited, remember)
    S->>A: resolve
    A-->>L: ApprovalDecision (remember => grant for chat)
    opt user cancels
        FE->>S: cancel(chatId)
        S->>S: token.cancel(), approvals.cancelAll(chatId)
        L-->>FE: cancelled
    end
    S-->>FE: run_finished {cancelled}
```

Event types: `run_started`, `run_queued`, `text`, `usage`, `tool_call`, `tool_result`, `approval_request`, `classifier`, `compaction`, `warning`, `error`, `cancelled`, `plan_ready`, `run_finished`.

## 11. Sessions and persistence

- `ChatSessionData` holds messages (JSON lines), mode, tool groups, usage counters, title, `agentId`.
- `AgentLoop` saves the message log after the user message, after every assistant response, and after every tool-result message. A model call that fails or is denied appends nothing, so the log always stays valid for the next call.
- Stores: `InMemoryChatSessionStore` (default), `FileHistoryChatSessionStore` (mirrors to `messages.jsonl`, CLI). Sub-agent runs: `InMemorySubAgentRunLog`, `FileHistorySubAgentRunLog`.
- Grants (`approve always`) and declined-rewrite markers are runtime state only, never in the context.

## 12. Sandbox

```mermaid
flowchart LR
    BASH["bash tool"] --> SE["SandboxExecutor.execute(chatId, runDir, readOnlyDir, cmd, timeout)"]
    FT["file tools"] --> JAIL["path jail: workDir(chatId)<br/>(PLAN: scratch only)"]
    SE --> L["LocalDirSandboxExecutor<br/>no isolation, process as user"]
    SE --> CW["CwdSandboxExecutor<br/>workDir = project dir<br/>scratch = .kendhar/scratchpad"]
    SE --> DK["DockerSandboxExecutor<br/>docker run: --network none, memory/cpus/pids limits,<br/>workdir mounted, read-only in PLAN"]
```

Plan mode runs the shell from scratch; only Docker can enforce that the workdir is not written.

## 13. Slash commands (no agent loop)

`SlashCommands.handles(line)` is checked before `start()`. Built-ins: `/help /clear /new /compact /mode /default /plan /edit /auto /cancel /tools /mcp /apis /skills /back /cost /where /status /chats /resume /title /exit`, plus plugin commands registered through `PluginRegistrar.command`. Menus (`/tools /mcp /apis /skills /mode`) are stateful per chat via `MenuController`; numbers select, Esc, `/back` or `b` goes up. Only `/compact` calls an LLM.

- Steering: after each tool-result round, and when an answer has no tool calls, the loop drains the chat's steering queue (and, only in the second case, one follow-up) into user messages and calls the model again.
