# jllm-cli

Terminal front end for [jllm-harness](../jllm-harness/README.md): an interactive chat, or a single `--prompt`, over the
standard tools. One class, `org.kendar.llm.cli.Main`, packaged as a shaded runnable jar.

## Build and run

```sh
mvn -pl jllm-cli -am package -DskipTests
java -jar jllm-cli/target/jllm-cli-1.0-SNAPSHOT.jar --provider anthropic --mode edit
```

The API key comes from `--api-key` or the `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GROK_API_KEY` / `QWEN_API_KEY` environment variable.
`--api-key ASK_TOKEN` asks for it on startup (hidden on a terminal, one plain line otherwise).

## Flags

```text
Main [--provider anthropic|openai|gemini|grok|qwen|llamacpp|ollama] [--model M] [--base-url U] [--api-key K]
     [--mode default|plan|edit|auto] [--workdir DIR] [--prompt TEXT] [--settings FILE] [--system-prompt TEXT] [--system-prompt-file FILE] [--system-prompt-replace FILE]
     [--max-turns N] [--context-tokens TOKENS] [--max-tokens TOKENS]
     [--context-strategy compaction|mnemonic|scope] [--search-api-key BRAVE_KEY] [--small-model]
     [--sandbox local|docker] [--docker-image IMAGE] [--docker-mode oneshot|daemon]
     [--docker-remote] [--docker-remote-host ADDRESS]
     [--sandbox-clean] [--sandbox-clean-all]
```

| Flag | Default | Meaning |
|---|---|---|
| `--provider` | settings `defaultProvider`, else `anthropic` | a provider name from the settings `providers` list, or a built-in: `anthropic`, `openai`, `gemini`, `grok`, `qwen`, `llamacpp`, `ollama` |
| `--model` | per provider (`claude-sonnet-5-5`, `gpt-4.1`, `qwen3-coder-plus`, `local`, `llama3.1`) | model name; overrides the named provider for this session only |
| `--base-url` | per provider | endpoint (OpenAI-compatible URLs include `/v1`) |
| `--api-key` | `<PROVIDER>_API_KEY` env var | `ASK_TOKEN` asks for it on startup |
| `--mode` | `default` | initial [permission mode](../jllm-harness/README.md#modes-working-directory-and-scratch-directory) |
| `--workdir` | current directory | project directory; the file tools are confined to it |
| `--prompt` | none | run one turn non-interactively, then exit |
| `--settings` | none | harness [settings file](../docs/USAGE.md#5-settings-file), merged over `~/.jllm/settings.json` and `.jllm/settings.json` |
| `--system-prompt` | none | text appended to the built-in system prompt |
| `--system-prompt-file` | none | file appended to the system prompt |
| `--system-prompt-replace` | none | file that replaces the built-in system prompt; also `SYSTEM.md` / `APPEND_SYSTEM.md` in `~/.jllm/` and `.jllm/` (see [USAGE](../docs/USAGE.md#system-prompt-files)) |
| `--max-turns` | 40 | model calls per user turn |
| `--context-tokens` | 100000 | the model's context window; wins over `context.maxTokens` |
| `--max-tokens` | 32000 | output limit of one response, not the window |
| `--context-strategy` | `compaction` | `compaction`, `mnemonic` or `scope`; wins over `context.strategy` |
| `--search-api-key` | `BRAVE_API_KEY` env var | Brave key for `web_search`; without it DuckDuckGo is scraped |
| `--small-model` | off | no value; adds the small-model rules to the system prompt |
| `--sandbox` | `local` | `local` (no isolation) or `docker`: `bash` runs in a container with the project directory mounted ([SANDBOX](../docs/SANDBOX.md)); wins over `sandbox.backend` |
| `--docker-image` | `python:3.12-slim` | image of the docker sandbox; wins over `sandbox.docker.image` |
| `--docker-mode` | `oneshot` | `oneshot` (a container per command) or `daemon` (one long-running container for `bash` and the file tools; kept between runs); wins over `sandbox.docker.mode` |
| `--docker-remote` | off | no value; the docker daemon is on another machine: the project is uploaded to the container and backed up on exit and on `/sandbox backup` ([SANDBOX](../docs/SANDBOX.md#remote-docker)); wins over `sandbox.docker.remote` |
| `--docker-remote-host` | from `DOCKER_HOST` | address where the remote host's published ports are reached |
| `--sandbox-clean` | | no value; removes the daemon-mode container of the startup directory, prints what was removed, exits. Project files stay |
| `--sandbox-clean-all` | | no value; removes every CLI sandbox container, then exits |
| `-h`, `--help` | | print usage |

Unknown arguments, or a flag without a value, stop the CLI with an error. `--context-budget` is accepted as an old
alias of `--context-tokens`.

## Directories

On startup the CLI prints three directories:

| Directory | Location | Content |
|---|---|---|
| project dir | `--workdir` | the files the agent works on |
| scratch dir | `<project>/.kendhar/scratchpad/<chatId>/<agentId>` | throwaway files, always writable |
| history dir | `<project>/.kendhar/history/<chatId>/<agentId>/` | `messages.jsonl`, sub-agent runs, `mnemonic.json` |

When the project directory is not writable, scratch and history go under `sandbox.tempDir`
(default `${java.io.tmpdir}/agent-harness`). Chats persist there. At startup the CLI offers to continue the newest saved chat (`--resume prompt|no|<id>`, default `prompt`; `--prompt` and piped input never ask), see [USAGE](../docs/USAGE.md#saved-chats-and-restarting).

The CLI has **no isolation**: shell commands run as the current user, and plan mode cannot stop a shell command from
writing to the project directory.

## Interactive session

The prompt shows the current mode (`edit> `). Each line is handled in this order:

1. an open numbered menu (`/tools`, `/mcp`, `/apis`, `/skills`, `/mode`) takes it as input;
2. a known slash command runs without calling the model;
3. anything else is a prompt and starts a run.

During a run the CLI prints:

| Output | Event |
|---|---|
| assistant text | `text` |
| `[tool] name {input}` | `tool_call` |
| `[result] ...` / `[result, error] ...` (cut at 300 chars) | `tool_result` |
| `[warning]`, `[error]` | `warning`, `error` |
| `[compaction] {...}` | `compaction` |
| `[plan] ...` | `plan_ready` (suggests `/edit`) |
| `[cancelled]` | `cancelled` |
| `[queued steer]`, `[applied] ...` | `steer_queued`, `steer_applied` |

Approvals show the tool and a preview, then `Allow? [y/N/a=allow for this chat]`: `y` allows once, `a` allows the tool
for the rest of the chat (sub-agents included), anything else rejects.

While a run is active, a line that is a known command (`/cancel`, `/followup <text>`, ...) runs it, and any other
line **steers** the agent: it is applied after the current tool calls (printed as `[queued steer]`, then
`[applied]`). `/followup <text>` is applied only when the agent would otherwise finish. `/cancel` clears both
queues. Ctrl+C cancels the run and exits. `/exit` or `/quit` leaves.

## Slash commands

The commands are shared with every front end (`SlashCommands` in the harness). `/help` lists them, plugin commands
included.

| Command | Effect |
|---|---|
| `/clear` | empty the conversation of this chat |
| `/new` | new chat in the same mode |
| `/compact [focus]` | summarize older context now (compaction strategy only) |
| `/mode` | numbered menu; `/mode <name>` sets it |
| `/default` `/plan` `/edit` `/auto` | set the mode |
| `/cancel` | cancel the current run |
| `/steer <text>` `/followup <text>` | queue a message for the running agent (see above) |
| `/model` | menu: choose the model for new runs; `/model <provider>` sets it directly |
| `/undo [n]` | restore files changed by `write_file`/`edit_file` in the last n turns (not shell changes) |
| `/validation [off\|lenient\|strict]` | show or set the check of tool-call arguments for this chat |
| `/permissions` | list the permission rules from the settings (read-only) |
| `/tools` | menu: enable/disable tools and groups; `/tools enable\|disable <tool\|group>` |
| `/mcp` | menu: MCP servers; `/mcp enable\|disable\|check <name>` |
| `/apis` | menu: REST APIs; `/apis enable\|disable\|check <name>` |
| `/skills` | menu: skills; `/skills enable\|disable <name>` |
| `/back` | one level up in a menu (also Esc or `b`) |
| `/cost` | tokens (input, output, cache read, cache write) and estimated cost |
| `/where` | working and scratch directory |
| `/status` | context strategy, estimated context size against the window, usage, chat count |
| `/chats` `/resume <id\|prefix>` `/title <text>` | list, switch, rename chats |
| `/chats delete <id>` `/chats clean [days]` | delete one chat; list chats older than `days` (default 30), `/chats clean confirm` deletes them |
| `/export [md\|html] [file]` | write the chat as a transcript into the scratch directory |
| `/login [name]` `/logout <name>` | OAuth login (providers, MCP servers with `oauth`); no name lists them |
| `/exit` `/quit` | leave the CLI |

Menu toggles live in memory only; the settings file is the startup default.

## Local models

```sh
java -jar jllm-cli.jar --provider ollama --model qwen3:8b --context-tokens 32000
java -jar jllm-cli.jar --provider llamacpp --base-url http://localhost:8080/v1 --small-model
```

Ollama's OpenAI endpoint cannot set the context size per request. Raise it on the Ollama side
(`OLLAMA_CONTEXT_LENGTH` or `num_ctx` in a Modelfile) and keep `--context-tokens` below it. Model calls are serialized
by default (`execution.maxParallel`), which suits one local GPU. `--small-model` is described in the
[harness README](../jllm-harness/README.md#embedding).

## Wiring

`Main` builds the harness with `CwdSandboxExecutor` (rooted at the project dir), `FileChatSessionStore`,
`FileHistorySubAgentRunLog` and `FileMnemonicStore`, then subscribes to the chat's events. Precedence for the same
setting: flag, then settings file, then default. See [USAGE](../docs/USAGE.md) for the settings file and the event
contract.
