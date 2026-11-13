# jllm-web-commons

The part of the web front end that does not depend on how users sign in or what they may do: the REST + SSE chat API,
the file explorer API, the harness wiring and the `jllm.*` properties. [jllm-web](../jllm-web/README.md) is the
application built on it; the REST API, events and properties are documented there.

It is a Spring Boot auto-configuration (`JllmWebCommonsAutoConfiguration`): putting the jar on the classpath of a Spring
Boot application registers everything below.

## What the application supplies

| Interface | Role | Default when missing |
|---|---|---|
| `WebAuthentication` | the `WebPrincipal` of a request; the `/api/...` paths open without sign-in | nobody is signed in (401) |
| `ChatAccess` | whether a user may see a chat | no chat is visible (404) |
| `HarnessIdentity` | per-user credentials, tool policy, principal by id, handed to the `AgentHarness` | no credentials, no tools |

`WebPrincipal` is the user as the API sees it: `id()` and `admin()`. An authenticated request carries it as the request
attribute `WebPrincipal.ATTR`.

## Java classes

| Class | Role |
|---|---|
| `JllmWebCommonsAutoConfiguration` | imports the classes below, defaults for the three interfaces |
| `JllmProperties` | the `jllm.*` properties |
| `HarnessConfiguration` | builds `ModelClient`, `HarnessPricing` and `AgentHarness` (same wiring and precedence as the CLI) |
| `ChatController` | chats, messages, events, approvals, commands |
| `FilesController`, `RemoteFs` | file explorer over a chat's work directory, local or in a remote sandbox |
| `ApiInterceptor`, `WebConfig` | requires a signed-in user on `/api/**`; hides chats the user may not see (404) |
| `SandboxSweep` | at startup, removes daemon sandbox containers whose chat no longer exists |

Tests: `ChatControllerTest`, `FilesControllerTest`, `RemoteFsStreamingTest`, `WebSecurityFixesTest`
(`mvn -pl jllm-web-commons -am test`).
