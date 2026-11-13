# jllm-web

Browser front end for [jllm-harness](../core/jllm-harness/README.md): a Spring Boot application with a REST + SSE API and a
Vue 3 chat UI. The Spring layer is a thin adapter; all agent logic lives in the framework-free harness.

## Build and run

```sh
mvn -pl jllm-web -am package -DskipTests
java -jar jllm-web/target/jllm-web-1.1.0-jllm.jar --provider anthropic --jllm.webauth-dir=runConfig/webauth
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
| `jllm.web.public-url` | none | the externally visible base URL (`https://jllm.example.com`); OAuth logins redirect to `<public-url>/oauth/callback`. Required when a per-user OAuth source uses the authorization code flow |

Also set: `server.port=8080` and `spring.mvc.async.request-timeout=-1` (SSE streams must not time out).

The sandbox, history and scratch directories are the same as in the CLI (`.jllm/` under the project directory).
There is no isolation unless `--sandbox docker`: shell commands run as the user running the server. Sign-in is a
simple reference implementation (below); bind the server to localhost or put it behind your own HTTPS proxy.

## Users, roles, skills and sign-in

This module is a reference implementation of the multi-user mechanism of
[jllm-web-commons](../jllm-web-commons/README.md); the common module stays generic and knows none of what follows.

Everything lives under one folder (property `jllm.webauth-dir`, default `webauth`, relative to the start folder).
The sample is `runConfig/webauth/`, which the IDE run configurations in `.run/` pass explicitly; they start in
`runConfig/tmp/` (ignored by git). Files are read on each
use, so changes (by hand or from the UI) apply without a restart.

| Path | Content |
|---|---|
| `webauth/users/<id>.json` | `{name, role, password?, credentials, disabledTools?, disabledSkills?}` |
| `webauth/permissions/<role>.json` | `{tools, readOnly?, approval?}` |
| `webauth/global-skills/<name>.md` | skills written by the admin, offered to every user |
| `webauth/user-skills/<id>/<name>.md` | a user's own skills |

### Sign-in
- Login and password. A user file without `password` has the **default password: the login itself** (`admin` /
  `admin`). The UI shows a banner until the user picks their own.
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
  call. Literal values are ignored, so secrets stay out of the directory. An OAuth header of the same source wins.

### OAuth logins

REST APIs with `oauth` and MCP servers with `oauth` (see [MCP](../docs/MCP.md#oauth)) are per user by default here: each
user connects their own account, and every call of their chats carries their own token.

- **Encryption is required**: set `JLLM_TOKEN_KEY` (32 random bytes in base64: `openssl rand -base64 32`). The tokens
  are kept in `<webauth>/tokens/users/<id>.json`, AES-GCM encrypted; the shared (admin) logins in
  `<webauth>/tokens/auth.json`. With a per-user source and no key, the server refuses to start: backups, other admins
  and container volumes all see these files. To rotate, move the old key to `JLLM_TOKEN_KEY_OLD` and set a new one.
- **`jllm.web.public-url`**: the redirect URI is exact, so the authorization server must know
  `<public-url>/oauth/callback` (register it with the client, or it is registered dynamically). Without it, a per-user
  source with the authorization code flow is refused at startup; the device code flow needs no redirect.
- **Connecting**: Settings → **Connections** lists the user's sources (connected, expiry, not connected, service
  account) with Connect and Disconnect. A call without a login shows a **Connect** button in the chat. `/login api:<x>`
  typed in a chat answers with the same link. The authorization page opens in a new tab; when it comes back the chat
  gets a `login` event; resend or steer ("retry").
- Every user can `/login` and `/logout` their own per-user sources; the shared logins (`perUser: false`, and the
  discovery login of a per-user MCP server: `/login mcp:<x> shared`) and provider logins are for the admin.
- The callback is the only route without sign-in. Its `state` is single-use, expires after 10 minutes and must come
  back in the browser of the user who started the login (another signed-in user gets 403: no login CSRF, no account
  mix-up). It never shows tokens or the authorization server's messages.
- Deleting a user deletes their token file and closes their MCP sessions.

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

### OAuth (jllm-web-commons)

| Method and path | Params | Result |
|---|---|---|
| `GET /api/oauth` | | `{sources: [{source, perUser, serviceAccount, connected, expiresAt?, sharedConnected?}], store}`; never a token. A non-admin sees the per-user sources and service accounts |
| `POST /api/oauth/{source}/start` | `chatId?`, `shared?` | `{authorizeUrl}` (or `{message}` with a device code); 403 for a shared login when not admin; 409 when one is in progress |
| `DELETE /api/oauth/{source}` | `shared?` | 204: logout |
| `GET /oauth/callback` | `state`, `code` / `error` | a small HTML page; 400 unknown, used or expired state; 403 another user |

### Events (SSE)

`GET /api/chat/{id}/events?after=<seq>` replays the buffered events with `seq` greater than `after`, then streams new
ones. Each SSE message has `id` = `seq`, `event` = the event type and the event JSON as data. A reconnecting client
passes the last `seq` it saw and gets no gap and no duplicate; `GET /api/chat/{id}` returns `lastSeq` for the first
subscription. The event types and their data are the harness ones (`run_started`, `text_delta`, `text_reset`, `text`,
`tool_call`, `tool_result`, `approval_request`, `usage`, `compaction`, `stuck`, `verify_failed`, `warning`, `error`,
`cancelled`, `plan_ready`, `login_required`, `login`, `run_finished`): see the [front-end contract](../docs/USAGE.md#front-end-contract).

Streaming: `text_delta` (and `thinking_delta`, `text_reset`) are sent without an SSE `id` and with `seq` 0: they are
live only and never replayed, so they must not move the client's last `seq`. The UI appends them to an open
assistant bubble; the final `text` event replaces its content and closes it, `text_reset` drops it, a cancel or an
error leaves it marked as cut off.

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
| `users.FileUserRepository` | `webauth/users/<id>.json` (a `UserRepository`, interface and `WebUser` in jllm-web-commons) |
| `users.FileRoleRepository` | `webauth/permissions/<role>.json` (a `RoleRepository`; `Role` in jllm-web-commons) |
| `users.Permissions` | the fixed `admin` role over the stored ones; each user's `UserPolicy` (role narrowed by the user, with their skills) |
| `users.FileSkillRepository` | `webauth/global-skills/` and `webauth/user-skills/<id>/` (a `SkillRepository`) |
| `users.JsonFiles` | atomic writes of the `webauth` files |
| `users.UserCredentialResolver` | per-call headers from a user's `credentials`, `env:` references only |

The file repositories are used with `jllm.store=file` (the default); another store supplies its own
`UserRepository`, `RoleRepository` and `SkillRepository` beans. The harness settings layers come from a
`SettingsSource` bean (default: the settings files) and the project trust answers from a `ProjectMcpTrust` bean;
`HarnessIdentity.tokenStore()` can replace the OAuth token files.

Tests: `SampleUsersTest`, `LoginTest`, `MeControllerTest`, `AdminControllerTest`, `JllmWebApplicationArgsTest`
(`mvn -pl jllm-web -am test`).

## A possible extension: a MySQL store

Not implemented. The interfaces are in place, so whoever wants it can add it without touching the controllers or the
harness. A short list:

- [ ] A module or package with `JdbcUserRepository`, `JdbcRoleRepository`, `JdbcSkillRepository` (interfaces in
      jllm-web-commons `org.kendar.llm.web.commons.users`), wired when `jllm.store=mysql` (the file beans are already
      `@ConditionalOnProperty(jllm.store=file)`); `spring-boot-starter-jdbc`, the MySQL driver, Flyway for the schema.
- [ ] Tables, roughly: `roles(name, read_only, approval)` + `role_tools(role, pattern, ord)`;
      `users(id, name, role, password_hash)` + `user_credentials(user_id, source, header, env_ref)` +
      `user_disabled_tools` + `user_disabled_skills`; `skills(owner_id NULL = global, name, description, content)`.
      Foreign keys user→role (today a missing role just gives no tools) and cascade deletes for a user's rows.
- [ ] Keep the rules the files have: ids/names `[a-z0-9_-]{1,64}`, credentials only `env:NAME`
      (`UserRepository.checkCredentials`), skills at most 64 KB (`SkillRepository.checkSize`) and 50 per user, the
      `admin` role never stored (`Permissions` adds it).
- [ ] `create` must fail on an existing id (`IllegalStateException`) and the setters on an unknown one
      (`NoSuchElementException`): map duplicate-key and zero-rows-updated to those.
- [ ] OAuth tokens: a `TokenStore` on a `oauth_tokens(principal_id, name, ciphertext, version)` table, encrypted with
      `JLLM_TOKEN_KEY` as the files are, `compareAndPut` as `UPDATE ... WHERE version = ?`, `crossProcess()` true;
      return it from `HarnessIdentity.tokenStore()`.
- [ ] Harness settings: a `SettingsSource` bean reading one JSON document per layer
      (`settings_layer(kind GLOBAL|PROJECT|EXPLICIT, doc)`), and a `ProjectMcpTrust` bean on
      `mcp_trust(project_key, name, sha256, trusted)` (`ProjectMcpTrust.projectKey`/`fingerprint`).
- [ ] An importer from an existing `webauth/` folder (users, roles, skills, tokens) into an empty database.
- [ ] Run the existing web tests against both stores (Testcontainers MySQL, or H2 in MySQL mode).

- [ ] Chat history: the harness already takes interfaces for it, `ChatSessionStore` (the chat row and its messages),
      `StateStore` (per-chat JSON documents: tool grants, todo lists), `MnemonicStore` (per chat and agent) and
      `SubAgentRunLog`. Implement them on tables keyed by `chat_id` (messages as JSON text, ordered by a sequence),
      delete in cascade with the chat, and let `HarnessConfiguration` take them as beans instead of building the
      `File*` stores itself. `ChatSessionStore.list()` is most recent first; chat retention
      (`chatRetentionLimit`) deletes through `delete(chatId)`.
- [ ] Login sessions: `auth.SessionStore` is in memory (lost on restart); a `sessions(token_hash, user_id, expires)`
      table keeps them across restarts and instances.

Still on disk after all of the above: the sandbox workspace of each chat (the files the tools read and write) and
the `HistoryRootLock` that allows one process per base folder. Several instances behind a load balancer would also
need a shared workspace (or the docker daemon sandbox on a shared host) and that lock replaced.
