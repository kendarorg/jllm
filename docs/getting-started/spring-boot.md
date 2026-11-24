# JLLM in a Spring Boot application

The harness has no Spring dependency, so it runs in any Spring Boot application as plain beans. Everything on this
page is a **recipe** for your own code. The shipped Spring layer is the starter `jllm-web-spring-starter`
(Path A), over the framework-free handlers of `jllm-web-commons`; it is what `jllm-web` uses.

| Path | You get | You write |
|---|---|---|
| **A. `jllm-web-spring-starter`** | chat REST + SSE API, file API, OAuth callbacks, `jllm.*` properties, plugin host | three small interfaces (identity) |
| **B. Harness beans** | the agent inside your services, your own API or UI | the `AgentHarness` bean and your tools |

Both can be combined: the web layer is itself a Spring bean configuration over the same harness. The handlers of
`jllm-web-commons` know no web framework, so another one can mount them with its own thin layer.

## Path A: the web API in your application

Add the dependency (`org.kendar.llm:jllm-web-spring-starter`). Its auto-configuration (`JllmWebAutoConfiguration`)
registers the harness, the chat controllers and the web plugin host. The properties use the prefix `jllm.`
([full table](../../jllm-web/README.md#configuration)):

```properties
jllm.provider=anthropic
jllm.model=claude-sonnet-4-5
jllm.workdir=/var/lib/jllm
jllm.web.public-url=https://agents.example.com
spring.mvc.async.request-timeout=-1
```

Without more, every request is refused (401): the three interfaces default to "nobody", "no chat" and "no tools".
Supply them as beans; they replace the defaults (`@ConditionalOnMissingBean`).

```java
@Bean
WebAuthentication webAuthentication() {
    return new WebAuthentication() {
        public Optional<Principal> current(Cookies cookies) {   // the request's cookies; Spring Security needs none
            // bridge from Spring Security
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                return Optional.empty();
            }
            boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return Optional.of(new Principal() {
                public String id() { return auth.getName(); }
                public boolean admin() { return admin; }
            });
        }
        public Set<String> openPaths() { return Set.of(); }   // sign-in is Spring Security's job
    };
}

@Bean
ChatAccess chatAccess() {
    return (user, chat) -> user.admin() || user.id().equals(chat.principal());   // the id the chat was created with
}

@Bean
HarnessIdentity harnessIdentity(MyToolPolicy policy) {
    return new HarnessIdentity() {
        public CredentialResolver credentials() { return policy.credentials(); }
        public UserPolicyResolver policies() { return policy.resolver(); }   // which tools per user
        public Function<String, HarnessPrincipal> principals() { return policy::principal; }
    };
}
```

Override `ChatAccess.visibility(user)` (a `ChatVisibility`) to let the store filter the chat list; the default loads every chat and `canSee` filters.

`ChatSessionData.principal()` is the user id given when the chat was created. Because the API reads the
principal from `WebAuthentication`, the existing Spring Security filter chain and session or JWT handling stay yours.
Keep `/api/**` authenticated and CSRF-protected the way you do for any browser API.

Stores follow the same rule: declare a `ChatSessionStore`, `SubAgentRunLog`, `StateStore` or `SettingsSource` bean and
it replaces the file default.

## Path B: harness beans, no web layer

Add `org.kendar.llm:jllm-harness`.

```java
@ConfigurationProperties("myapp.agent")
record AgentProps(String apiKey, String systemPrompt, Path settings) {}

@Configuration
@EnableConfigurationProperties(AgentProps.class)
class AgentConfig {

    @Bean
    ModelClient modelClient(AgentProps p, ObjectMapper mapper) {
        return ModelClients.anthropic(AnthropicSettings.defaults(p.apiKey()), mapper);
    }

    @Bean(destroyMethod = "close")
    AgentHarness agentHarness(ModelClient model, AgentProps p, ObjectProvider<HarnessTool> tools,
                              ObjectProvider<HarnessMetrics> metrics) {
        return new AgentHarnessBuilder()
                .modelClient(model)
                .tools(tools.orderedStream().toList())     // every HarnessTool bean in the context
                .settings(p.settings())                    // MCP servers, REST APIs, permissions
                .approvalPolicy(ToolApprovalPolicy.byKind())
                .metrics(metrics.getIfAvailable(HarnessMetrics::noop))
                .systemPrompt(p.systemPrompt())
                .build();
    }
}
```

`ObjectProvider` keeps the bean optional: with no `HarnessTool` or `HarnessMetrics` bean the harness gets an empty
list and `HarnessMetrics.noop()`.

## Your services as tools

A tool is a bean: name, description, JSON schema, group and kind. `SimpleTool` is final, so declare it in a `@Bean`
method (or implement `HarnessTool` in a `@Component`).

```java
@Bean
HarnessTool invoiceGet(InvoiceService invoices) {
    return new SimpleTool("invoice_get", "Get one invoice by number. Returns JSON.",
            SimpleTool.schema("number", "Invoice number"),
            "Billing", ToolKind.READ_ONLY,
            (ctx, in) -> invoices.findAsJson(in.path("number").asText()));
}
```

Rules that matter in a Spring application:

- **Kind is a promise.** `READ_ONLY` never asks. A tool that changes anything is `OTHER`, so it asks for approval.
- **Security is yours.** The tool runs in the harness's thread, not in an HTTP request, so there is no
  `SecurityContext`. `ctx.chatId()` identifies the chat; the user is `harness.sessions().get(ctx.chatId()).principal()`
  (the id passed to `sessions().create(title, principal)`). Call services with that identity, for example through a
  method that takes the user, or by setting a security context for the duration of the call.
- **Transactions** do not span a chat. Each tool call is one unit; keep your `@Transactional` boundary inside the
  service the tool calls.
- **Validate input** in the tool or the service. The model is an untrusted caller.

## Starting a chat from a service

```java
@Service
class SupportAgent {
    private final AgentHarness harness;
    SupportAgent(AgentHarness harness) { this.harness = harness; }

    String open(String userId, String firstMessage, Consumer<AgentEvent> sink) {
        String chatId = harness.sessions().create(null, userId).chatId();
        harness.subscribe(chatId, 0, sink);             // AgentEvent(seq, type, data): text, tool_call, approval_request, run_finished
        harness.start(chatId, firstMessage);            // returns at once; the run is on a virtual thread
        return chatId;
    }
}
```

`start` does not block. Forward events to the client with `SseEmitter`, a WebSocket or a message queue, answer
approvals with `resolveApproval(chatId, approvalId, approved, edited, remember, rememberDir)`, and cancel with `cancel(chatId, kill)`. The event
contract is in [USAGE](../reference/usage.md).

## Metrics and tracing

`HarnessMetrics` maps directly to a Micrometer `MeterRegistry`; hooks give you OpenTelemetry spans. Both recipes are in
[Observability](../enterprise/observability.md).

## Configuration from `application.yml`

The harness settings (MCP servers, REST APIs, permissions, sandbox) are a JSON file or a `SettingsSource`. To keep
them in Spring configuration, bind your own properties and implement `SettingsSource` to return them as a layer; or
keep a JSON file next to the application. Secrets use `${ENV_VAR}` references inside the file.

## Running next to Spring AI or LangChain4j

Nothing prevents both on one classpath. A common split: Spring AI or LangChain4j for retrieval and simple completions,
JLLM for the agent that must act under approval and sandbox control. They do not share a tool model, so wrap a Spring
AI `@Tool` or a LangChain4j tool method in a `HarnessTool` (a few lines) rather than registering it twice. See
[JVM landscape](../comparisons/jvm-landscape.md).

## Gotchas

- Event streams must not time out: `spring.mvc.async.request-timeout=-1`, and no proxy buffering.
- One process per history directory: the harness locks it at startup, so two application instances cannot share a
  `workdir`. Give each instance its own, or supply your own stores ([Deployment](../enterprise/deployment.md)).
- Close the harness on shutdown (`destroyMethod = "close"`) so sandbox containers are cleaned up.
- Virtual threads run the agent loop; avoid `synchronized` blocks around blocking calls inside your tools on
  Java 21.
