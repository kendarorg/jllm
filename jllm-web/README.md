# jllm-web

Browser front end for [jllm-harness](../core/jllm-harness/README.md): a Spring Boot application with a REST + SSE API and a
Vue 3 chat UI. The Spring layer is a thin adapter; all agent logic lives in the framework-free harness.

## Build and run

```sh
mvn -pl jllm-web -am package -DskipTests
java -jar jllm-web/target/jllm-web-1.1.0-jllm.jar --provider anthropic
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
| `jllm.settings` (flag `--settings`) | none | merged over `~/.jllm/settings.json` and `<workdir>/.jllm/settings.json`; harness [settings file](../docs/USAGE.md#5-settings-file) (MCP, REST, skills, agents) |
| `jllm.context-strategy` | `compaction` | `compaction`, `mnemonic` or `scope`; wins over the settings file |
| `jllm.small-model` | `false` | append the small-model rules to the system prompt |
| `jllm.sandbox` | `local` | `local` or `docker` (`bash` in a container, chat directory mounted; see [SANDBOX](../docs/SANDBOX.md)); wins over the settings file |
| `jllm.docker-image` | `python:3.12-slim` | image of the docker sandbox; wins over the settings file |
| `jllm.docker-mode` | `oneshot` | `oneshot` or `daemon`: one long-running container per chat for `bash` and the file tools, removed with the chat (see [SANDBOX](../docs/SANDBOX.md)); wins over the settings file |
| `jllm.docker-remote` | `false` | flag `--docker-remote`: docker on another machine; each chat's workspace is uploaded to its container and backed up under `<X>/<chatId>/.jllm/` ([SANDBOX](../docs/SANDBOX.md#remote-docker)) |
| `jllm.docker-remote-host` | from `DOCKER_HOST` | address where the remote host's published ports are reached |
| `jllm.system-prompt-file` (flag `--system-prompt-file`) | none | file appended to the system prompt |
| `jllm.system-prompt-replace` (flag `--system-prompt-replace`) | none | file that replaces the built-in system prompt |
| `jllm.system-prompt` | none | text appended to the built-in system prompt; `SYSTEM.md` (replace) and `APPEND_SYSTEM.md` in `~/.jllm/` and `<workdir>/.jllm/` are also read, see [USAGE](../docs/USAGE.md#system-prompt-files) |
| `jllm.search-api-key` | `BRAVE_API_KEY` env var | Brave key for `web_search` |
| `jllm.pricing.input-per-million`, `output-per-million`, `cache-read-per-million`, `cache-write-per-million` | 0 | USD per million tokens, for the cost estimate |

Also set: `server.port=8080` and `spring.mvc.async.request-timeout=-1` (SSE streams must not time out).

The sandbox, history and scratch directories are the same as in the CLI (`.jllm/` under the project directory).
There is no isolation unless `--sandbox docker`: shell commands run as the user running the server. Sign-in is a
simple reference implementation (below); bind the server to localhost or put it behind your own HTTPS proxy.

## Users, roles, skills and sign-in

This module is a reference implementation of the multi-user mechanism of
[jllm-web-commons](../jllm-web-commons/README.md); the common module stays generic and knows none of what follows.

Everything lives under one folder, `webauth/` at the project root (property `jllm.webauth-dir`, default `webauth`,
relative to the start folder; run from the project root, or pass `--jllm.webauth-dir=...`). Files are read on each
use, so changes (by hand or from the UI) apply without a restart.

| Path | Content |
|---|---|
| `webauth/users/<id>.json` | `{name, role, password?, credentials, disabledTools?, disabledSkills?}` |
| `webauth/permissions/<role>.json` | `{tools, readOnly?, approval?}` |
| `webauth/global-skills/<name>.md` | skills written by the admin, offered to every user |
| `webauth/user-skills/<id>/<name>.md` | a user's own skills |

### Sign-in
- Login and password. A user file without `password` has the **default password: the lowercase hex SHA-256 of the
  login** (e.g. `admin` → `8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918`). The UI shows a banner
  until the user picks their own.
- Stored passwords are `pbkdf2$<iterations>$<salt>$<hash>` (PBKDF2WithHmacSHA256, 210000 iterations, random salt).
- The cookie `jllm_session` (HttpOnly, SameSite=Strict, Secure on HTTPS) holds a random session token; sessions are
  in memory (7 days, sliding), so a restart signs everybody out. A deleted user is signed out at once.
- Five failed attempts for a login or an address lock it for 30 seconds (429). Unknown login and wrong password give
  the same answer.

### Roles
- `tools`: tool-name patterns, `*` matching any run of characters: `"read_file"`, `"mcp__docs__*"` (a whole MCP
  server, also tools it adds later), `"billing__*"` (a REST API), `["*"]` (everything).
- `readOnly`: optional; `true` keeps only read-only tools. `approval`: optional; `"off"` turns approval prompts off,
  `"ask"` (default) uses the harness policy. A role without a valid file gets no tools.
- `admin` is fixed: every tool, no prompts, whatever its file says; it cannot be edited, deleted or recreated. At least
  one admin user always remains, and an admin cannot delete their own account.
- One role per user. Shipped: `guest` (`run_js` and the web tools), `reader` (read-only file tools), `admin`.

### User settings (every user, own account only)
- **Password**: change it (current password required); the other sessions of the user are signed out.
- **Tools**: switch off tools the role allows (`disabledTools`, patterns too). A tool works only if the role allows
  it and the user has not switched it off: a user can narrow the role, never widen it.
- **Skills**: one list of the skills of the settings file, the global skills and the user's own; each can be switched
  off (`disabledSkills`). Users add, edit and delete their own skills (64 KB each, at most 50); an own skill cannot
  take the name of a shared one, and a global skill created later with its name hides it until it is renamed. Own
  skills need the role to allow the `skill` tool.
- Settings are per user, not per chat: they apply to every chat of the user from its next turn. Existing chats are
  not migrated.

### Administration (role `admin`)
Users (create, rename, change role, set or reset password, delete with their own skills), roles (with every tool,
MCP server and REST API the harness knows, also disabled or failing sources) and global skills.

### Chats and credentials
- Chats belong to the user who created them. Others get 404; the admin sees all chats.
- Credentials: a user file can declare headers per source, e.g.
  `"credentials": { "mcp:docs": { "Authorization": "env:DOCS_TOKEN" } }`. The value is read from the environment on each
  call. Literal values are ignored, so secrets stay out of the directory.

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

### Sign-in, settings and administration (this module)

| Method and path | Body | Result |
|---|---|---|
| `POST /api/login` | `{id, password}` | user `{id, name, role, defaultPassword}` + cookie; 401 `Invalid credentials`; 429 when locked |
| `POST /api/logout` | | 204 |
| `GET /api/me` | | the signed-in user; 401 otherwise |
| `POST /api/me/password` | `{current, next}` | 204; 401 wrong current; 400 shorter than 8 or equal to the default |
| `GET /api/me/tools` | | `{tools, mcp, api, disabled}`: what the role allows, with `enabled` / `on` |
| `PUT /api/me/tools` | `{disabled}` | 204; entries meaning nothing for the role are dropped |
| `GET /api/me/skills` | | `{usable, skills: [{name, description, source, enabled, shadowed}]}` |
| `GET /api/me/skills/{name}` | | `{name, description, source, content}` |
| `PUT /api/me/skills/enabled` | `{disabled}` | 204 |
| `POST /api/me/skills`, `PUT /api/me/skills/{name}`, `DELETE /api/me/skills/{name}` | `{name, description, content}` | own skills; 409 name taken, 413 too large, 403 not own |
| `GET/POST /api/admin/users`, `PUT/DELETE /api/admin/users/{id}` | `{id, name, role, password?}` | admin only (403 otherwise) |
| `POST /api/admin/users/{id}/password` | `{password?}` | set, or reset to the default when absent |
| `GET/POST /api/admin/roles`, `PUT/DELETE /api/admin/roles/{name}` | `{name, tools, readOnly, approval}` | `admin` 403; delete 409 while held |
| `GET /api/admin/catalog` | | `{tools, mcp, api}`: everything a role can be granted |
| `GET/POST /api/admin/skills`, `GET/PUT/DELETE /api/admin/skills/{name}` | `{name, description, content}` | global skills; settings-file skills read-only |

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
| `components/SignIn.js` | login and password |
| `components/SettingsPanel.js` | the user's password, tools and skills |
| `components/AdminPanel.js` | users, roles, global skills |
| `components/RoleEditor.js`, `components/ToolPicker.js` | the grouped tool / MCP / REST tree, shared by the role editor (grant) and the user settings (switch off) |
| `components/SkillEditor.js` | name, description, markdown body of a skill |
| `components/ApprovalDialog.js` | shows `approvals[0]`; approve, "Allow for this chat" (sends `remember: true`, tool approvals only), reject, edit a proposed context summary |
| `app.css`, `chat.css`, `files.css`, `admin.css` | styles |

## Java classes

The chat and file API, the harness wiring and the `jllm.*` properties live in
[jllm-web-commons](../jllm-web-commons/README.md). This module keeps the startup and the reference sign-in, users,
roles and skills, plugged in through the common module's `WebAuthentication`, `ChatAccess` and `HarnessIdentity`.

| Class | Role |
|---|---|
| `JllmWebApplication` | Spring Boot entry point, CLI-style flags, API key prompt |
| `WebAuthConfiguration` | beans below; supplies `WebAuthentication`, `ChatAccess` and `HarnessIdentity` to the common API |
| `auth.AuthController` | `/api/login`, `/api/logout`, `/api/me` |
| `auth.CookieAuthentication` | the user of the session cookie; a chat is visible to its owner and the admin |
| `auth.PasswordHasher`, `auth.SessionStore`, `auth.LoginThrottle` | password hashes and default password, sessions, lockout |
| `me.MeController` | `/api/me/password`, `/api/me/tools`, `/api/me/skills*` |
| `admin.AdminController`, `admin.AdminInterceptor`, `admin.AdminWebConfig` | `/api/admin/**`, admins only |
| `admin.ToolCatalogView` | tools, MCP servers and REST APIs of the harness, for a role or a user |
| `users.WebUser`, `users.UserDirectory` | `webauth/users/<id>.json` |
| `users.Permissions` | `webauth/permissions/<role>.json`; each user's `UserPolicy` (role narrowed by the user, with their skills) |
| `users.SkillFiles` | `webauth/global-skills/` and `webauth/user-skills/<id>/` |
| `users.JsonFiles` | atomic writes of the `webauth` files |
| `users.FileCredentialResolver` | per-call headers from a user's `credentials`, `env:` references only |

Tests: `SampleUsersTest`, `LoginTest`, `MeControllerTest`, `AdminControllerTest`, `JllmWebApplicationArgsTest`
(`mvn -pl jllm-web -am test`).
