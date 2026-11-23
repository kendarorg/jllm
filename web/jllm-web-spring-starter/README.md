# jllm-web-spring-starter

Spring Boot starter for the web API. It holds everything that is Spring: the `@RestController` classes, the
interceptors and the auto-configuration. Each controller only binds the request, calls the framework-free handler of
[jllm-web-commons](../jllm-web-commons/README.md) and translates the result; there is no business logic here.

```
jllm-harness  <-  jllm-web-commons  <-  jllm-web-spring-starter  <-  jllm-web
```

Add `org.kendar.llm:jllm-web-spring-starter`. Its auto-configuration (`JllmWebAutoConfiguration`, registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) is all that is needed.

## What it registers

| Bean | Role |
|---|---|
| `JllmProperties`, `OidcProperties` | the `jllm.*` and `jllm.auth.*` properties, bound from the environment |
| `ModelClient`, `HarnessPricing`, `AgentHarness`, `WebPluginHost` | from `HarnessFactory`; beans of the application for `SettingsSource`, `ProjectMcpTrust`, `ChatSessionStore`, `SubAgentRunLog`, `MnemonicStore`, `StateStore` and `PluginStorages` replace the file defaults |
| `WebAuthentication`, `ChatAccess`, `HarnessIdentity` | defaults (`@ConditionalOnMissingBean`): nobody signs in, no chat is visible, no tool is allowed |
| `Gate`, `WebConfig` | the `/api/**` and `/api/admin/**` interceptors, both a one-line delegation to `Gate` |
| `ChatApi`, `FilesApi`, `OAuthApi`, `PluginApi` | the handlers |
| `ChatController`, `FilesController`, `OAuthController`, `PluginController` | `/api/chat/**`, `/api/chat/{id}/fs/**`, OAuth, `/api/plugins/**` |
| `AuthController`, `OidcController`, `MeController`, `AdminController` | mounted when the application defines the matching `AuthApi`, `OidcApi`, `MeApi` or `AdminApi` bean (they need the user, role and skill repositories, which the application chooses) |

## Translation

| Handler | Spring |
|---|---|
| `ApiException` | `ApiExceptionAdvice`: the status with `{"error": message}` |
| `EventSink` | `SseSink` over an `SseEmitter` (timeout 30 min); `Heartbeats` drives the ping every `ChatApi.HEARTBEAT_MS` and stops with the stream |
| `UploadedFile` | `MultipartFile` |
| `ByteStream` | `StreamingResponseBody` with `Content-Disposition`, type and length |
| `Reply`, `CookieOut` | `ResponseEntity`, `Set-Cookie` through `ResponseCookie` |
| `Cookies` | the servlet cookies (`Requests`) |

## Component scanning

The classes of `org.kendar.llm.web.spring` are registered by the auto-configuration, which knows which of them apply.
An application whose own package is a parent of it (as `jllm-web` is) must not scan them too: see the
`@ComponentScan` exclude filter of `JllmWebApplication`.

Tests: `ChatControllerTest`, `FilesControllerTest`, `FilesControllerJailTest`, `WebSecurityFixesTest` (HTTP level),
`JllmWebAutoConfigurationTest` (boots with no bean of the application) (`mvn -pl web/jllm-web-spring-starter -am test`).
