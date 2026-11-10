# jllm-web

Browser front end for [jllm-harness](../jllm-harness/README.md): a Spring Boot application with a REST + SSE API and a
Vue 3 chat UI. The Spring layer is a thin adapter; all agent logic lives in the framework-free harness.

## Build and run

```sh
mvn -pl jllm-web -am package -DskipTests
java -jar jllm-web/target/jllm-web-1.0-SNAPSHOT.jar --provider anthropic
```

Then open <http://localhost:8080>. The UI is plain static files in `src/main/resources/static`: Vue 3 as an ES module
with its in-browser template compiler (vendored in `vendor/`), no Node, npm, Vite or any build step. Edit a file,
reload the page (with the server running from `target/classes`, or restart it).

## Configuration

Standard Spring Boot properties, prefix `jllm.` (`application.properties`, environment). On the command line use the same
flags as the CLI, without the prefix: `--provider openai`, `--sandbox docker`, `--docker-mode daemon`, `--small-model`
(`--flag value` or `--flag=value`). Spring's own flags (`--server.port=9000`) are unchanged.

| Property | Default | Meaning |
|---|---|---|
| `jllm.provider` | `anthropic` | `anthropic`, `openai`, `gemini`, `grok`, `qwen`, `llamacpp`, `ollama` |
| `jllm.api-key` | `<PROVIDER>_API_KEY` env var | API key |
| `jllm.model` | per provider | model name |
| `jllm.base-url` | per provider | endpoint override |
| `jllm.max-tokens` | 32000 | output limit of one response |
| `jllm.context-tokens` | harness default (100000) | context window; wins over the settings file |
| `jllm.max-turns` | harness default (40) | model calls per user turn |
| `jllm.workdir` | current directory | project directory the agent works in |
| `jllm.settings-file` (flag `--settings`) | none | merged over `~/.jllm/settings.json` and `<workdir>/.jllm/settings.json`; harness [settings file](../docs/USAGE.md#5-settings-file) (MCP, REST, skills, agents) |
| `jllm.context-strategy` | `compaction` | `compaction`, `mnemonic` or `scope`; wins over the settings file |
| `jllm.small-model` | `false` | append the small-model rules to the system prompt |
| `jllm.sandbox` | `local` | `local` or `docker` (`bash` in a container, chat directory mounted; see [SANDBOX](../docs/SANDBOX.md)); wins over the settings file |
| `jllm.docker-image` | `python:3.12-slim` | image of the docker sandbox; wins over the settings file |
| `jllm.docker-mode` | `oneshot` | `oneshot` or `daemon`: one long-running container per chat for `bash` and the file tools, removed with the chat (see [SANDBOX](../docs/SANDBOX.md)); wins over the settings file |
| `jllm.docker-remote` | `false` | flag `--docker-remote`: docker on another machine; each chat's workspace is uploaded to its container and backed up under `<X>/<chatId>/.kendhar/` ([SANDBOX](../docs/SANDBOX.md#remote-docker)) |
| `jllm.docker-remote-host` | from `DOCKER_HOST` | address where the remote host's published ports are reached |
| `jllm.system-prompt` | none | text appended to the built-in system prompt; `SYSTEM.md` (replace) and `APPEND_SYSTEM.md` in `~/.jllm/` and `<workdir>/.jllm/` are also read, see [USAGE](../docs/USAGE.md#system-prompt-files) |
| `jllm.search-api-key` | `BRAVE_API_KEY` env var | Brave key for `web_search` |
| `jllm.pricing.input-per-million`, `output-per-million`, `cache-read-per-million`, `cache-write-per-million` | 0 | USD per million tokens, for the cost estimate |

Also set: `server.port=8080` and `spring.mvc.async.request-timeout=-1` (SSE streams must not time out).

The sandbox, history and scratch directories are the same as in the CLI (`.kendhar/` under the project directory).
There is **no authentication**, and no isolation unless `--sandbox docker`: shell commands run as the user running the server. Bind it to
localhost or put it behind your own proxy.

## REST API

All paths are under `/api/chat`. Errors are `{"error": "..."}`.

| Method and path | Body / params | Result |
|---|---|---|
| `POST /api/chat` | `{title?}` | new chat summary |
| `GET /api/chat` | | chat summaries (`chatId`, `title`, `mode`, `running`, `updatedAt`, `usage`) |
| `GET /api/chat/{id}` | | summary plus `messages`, `pendingApprovals`, `lastSeq`; 404 if unknown |
| `DELETE /api/chat/{id}` | | cancels the run, deletes the chat, its sub-agent runs and its directory; 204 |
| `POST /api/chat/{id}/messages` | `{text}` | a prompt: 202 `{started}`; a slash command: 200 `{command, output, switchTo, refresh}`; 409 if a run is active |
| `POST /api/chat/{id}/cancel` | | `{running}` |
| `POST /api/chat/{id}/mode` | `{mode}` | `{mode}`; `default`, `plan`, `edit`, `auto` (`bypass` is the old name of `auto`) |
| `POST /api/chat/{id}/clear` | | empties the conversation; 409 during a run |
| `POST /api/chat/{id}/compact` | `{focus?}` | `{changed, tokensBefore, tokensAfter, clearedToolResults}`; 409 during a run, 502 on a model error |
| `GET /api/chat/{id}/events?after=N` | | SSE stream, see below |
| `GET /api/chat/{id}/approvals` | | pending approvals |
| `POST /api/chat/{id}/approvals/{approvalId}` | `{approved, editedText?, remember?}` | `{resolved}`; 404 if no longer pending |
| `GET /api/chat/{id}/subagents?toolUseId=` | | sub-agent transcripts, optionally those of one `task` call |
| `GET /api/chat/{id}/files?q=` | | up to 50 file paths of the working directory (depth 8) containing `q`, for `@` mentions |
| `GET /api/chat/{id}/usage` | | tokens and `estimatedCostUsd` |
| `GET /api/chat/commands` | | slash commands (`action: server`) plus one `prompt:` shortcut per tool group |

### Events (SSE)

`GET /api/chat/{id}/events?after=<seq>` replays the buffered events with `seq` greater than `after`, then streams new
ones. Each SSE message has `id` = `seq`, `event` = the event type and the event JSON as data. A reconnecting client
passes the last `seq` it saw and gets no gap and no duplicate; `GET /api/chat/{id}` returns `lastSeq` for the first
subscription. The event types and their data are the harness ones (`run_started`, `text`, `tool_call`, `tool_result`,
`approval_request`, `usage`, `compaction`, `warning`, `error`, `cancelled`, `plan_ready`, `run_finished`): see the
[front-end contract](../docs/USAGE.md#front-end-contract).

Typical turn: `POST /messages` → events until `run_finished`; on `approval_request` answer with
`POST /approvals/{id}`.

## Frontend

Vue 3 loaded as an ES module through an import map (`vue` → `vendor/vue.esm-browser.prod.js`). Components are plain
JavaScript objects with a `template` string, so nothing is compiled or transpiled. No router, one reactive store
(`store.js`) fed by the SSE stream. To upgrade Vue, replace the vendored file with `vue.esm-browser.prod.js` from the
`vue` npm package.

| File (under `src/main/resources/static`) | Role |
|---|---|
| `index.html` | import map, stylesheets, mount point |
| `main.js`, `App.js` | create the app, layout |
| `store.js` | state, SSE subscription, actions (send, cancel, mode, approvals) |
| `api.js` | `fetch` wrapper (`api`, `post`) that turns `{error}` into exceptions |
| `components/SessionList.js` | chats list, new and delete |
| `components/ChatToolbar.js` | mode selector, cancel, token and cost meter |
| `components/MessageStream.js` | messages and grouped tool calls |
| `components/ChatComposer.js` | input, slash-command and `@file` completion |
| `components/ApprovalDialog.js` | shows `approvals[0]`; approve, "Allow for this chat" (sends `remember: true`, tool approvals only), reject, edit a proposed context summary |
| `app.css`, `chat.css` | styles |

## Java classes

| Class | Role |
|---|---|
| `JllmWebApplication` | Spring Boot entry point |
| `JllmProperties` | the `jllm.*` properties |
| `HarnessConfiguration` | builds `ModelClient`, `HarnessPricing` and `AgentHarness` (same wiring and precedence as the CLI) |
| `ChatController` | the REST and SSE endpoints above |

Tests: `ChatControllerTest` (`mvn -pl jllm-web -am test`).
