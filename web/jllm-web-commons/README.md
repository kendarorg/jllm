# jllm-web-commons

The framework-free half of the web API: handlers for chats, files, OAuth logins, web plugins, sign-in, the signed-in
user's settings and administration, plus the `Gate` that decides who may call what. It imports no Spring and no servlet
class (a test fails the build if it does). [jllm-web-spring-starter](../jllm-web-spring-starter/README.md) mounts the
handlers on Spring MVC, and [jllm-web](../../jllm-web/README.md) is the application built on both. The REST API, events
and properties are documented there.

```
jllm-harness  <-  jllm-web-commons  <-  jllm-web-spring-starter  <-  jllm-web
```

Another web framework mounts the same handlers by writing its own thin layer: bind the request, call the handler,
translate the result.

## How a handler works

A handler is a plain class. It takes explicit, typed parameters (the signed-in `Principal`, the cookies, the remote
address, the body as a `Map`) and returns a plain object, or throws `ApiException(status, message)`; the framework
layer answers that as the status with the body `{"error": message}`. There is no request abstraction: a handler
receives only what it uses.

| Type | Role |
|---|---|
| `ApiException` | a failed call: HTTP status and message |
| `Reply<T>` | when more than a body is needed: status, headers, cookies (login, logout, redirects, 202, 201) |
| `CookieOut` | a cookie to set: name, value, path, max age, `HttpOnly`, `Secure`, `SameSite` |
| `Cookies` | the request's cookies, read by name |
| `EventSink` | where a server-sent event stream is written: `send`, `comment`, `complete`, `onClose`, `onTimeout` |
| `UploadedFile` | name, size and bytes of one upload |
| `ByteStream` | a download: content type, optional length and file name, a writer called when the response is written |

## What the application supplies

| Interface | Role | Default when missing |
|---|---|---|
| `WebAuthentication` | the `Principal` of a request, from its `Cookies`; the `/api/...` paths open without sign-in | nobody is signed in (401) |
| `ChatAccess` | whether a user may see a chat (`canSee`) and which chats a listing loads (`visibility`, a `ChatVisibility` the store applies) | no chat is visible (404) |
| `HarnessIdentity` | per-user credentials, tool policy, principal by id, directory of the users' token files (`tokenDir()`), handed to the `AgentHarness` | no credentials, no tools |

`ChatAccess`, `HarnessIdentity`, `Principal` and the user, role and skill repositories live in jllm-harness (`org.kendar.llm.harness.identity`).

`Principal` is the user as the API sees it: `id()` and `admin()`.

## Java classes

Package `org.kendar.llm.web.commons`, with sub-packages `auth`, `me`, `admin`, `users`, `plugins`.

| Class | Role |
|---|---|
| `ChatApi` | chats, messages, the event stream (replay after `after`, live events, heartbeat tick), approvals, commands |
| `FilesApi` | file explorer over a chat's work directory, local or in a remote sandbox (remote part: `RemoteFs` in jllm-harness) |
| `OAuthApi` | OAuth logins of REST APIs and MCP servers through the browser: list, start, logout and the redirect target; routes `/login` typed in a chat through the same start; `check()` refuses to start per-user sources without an encrypted token store or `jllm.web.public-url` ([jllm-web](../../jllm-web/README.md#oauth-logins)) |
| `plugins.PluginApi` | web plugins under `/api/plugins/<id>/**` and their event stream |
| `auth.AuthApi`, `auth.OidcApi` | password sign-in (throttled) and OpenID Connect sign-in, ending in the same session cookie |
| `me.MeApi` | the user's own password, tools and skills |
| `admin.AdminApi`, `admin.ToolCatalogView` | users, roles and global skills, the catalog a role can be granted |
| `Gate` | `decide(path, principal)`: allow or 401; `decideChat(principal, chatId)`: 401 or 404 for a chat the user cannot see (the framework layer passes the chat id it matched as path variable, never parsed from the URI); `decideAdmin(principal)`: 401 or 403. `isOpen(path)` takes the path without the context path and fails closed (dot, empty or encoded segments, `;`, `\` need sign-in). Pure functions of their inputs |
| `HarnessFactory` | builds `ModelClient`, `HarnessPricing`, `AgentHarness` and the web plugin host (same wiring and precedence as the CLI); `sweepOrphanContainers` |
| `JllmProperties`, `auth.OidcProperties` | the `jllm.*` and `jllm.auth.*` settings, plain records the framework layer binds |
| `auth.CookieAuthentication` | `WebAuthentication` and `ChatAccess` by session cookie: the owner and the admin see a chat; `visibility` is the same rule for the store |
| `auth.PasswordHasher`, `auth.LoginThrottle`, `auth.OidcClient`, `users.Permissions`, `users.UserCredentialResolver` | password hashing, failed-attempt lock, the OIDC protocol, roles to tool policy, credentials per user |

Tests: `ChatApiTest`, `FilesApiTest`, `GateTest`, `PluginApiTest`, `NoFrameworkTest`, `ExternalLinksWebTest`,
`ScriptPolicyWebTest` (`mvn -pl web/jllm-web-commons -am test`).
