package org.kendar.llm.web;

import org.kendar.llm.web.users.FileSkillRepository;
import org.kendar.llm.web.users.FileUserRepository;
import org.kendar.llm.web.users.FileRoleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.McpServerSettings;
import org.kendar.llm.harness.settings.SkillSettings;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.commons.admin.AdminApi;
import org.kendar.llm.web.commons.admin.ToolCatalogView;
import org.kendar.llm.web.commons.auth.AuthApi;
import org.kendar.llm.web.commons.auth.CookieAuthentication;
import org.kendar.llm.web.commons.auth.LoginThrottle;
import org.kendar.llm.web.auth.InMemoryLoginSessionRepository;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.web.commons.me.MeApi;
import org.kendar.llm.web.commons.users.Permissions;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.commons.ChatApi;
import org.kendar.llm.web.commons.Gate;
import org.kendar.llm.web.spring.AdminController;
import org.kendar.llm.web.spring.AdminInterceptor;
import org.kendar.llm.web.spring.ApiExceptionAdvice;
import org.kendar.llm.web.spring.ApiInterceptor;
import org.kendar.llm.web.spring.AuthController;
import org.kendar.llm.web.spring.ChatController;
import org.kendar.llm.web.spring.MeController;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * A {@code webauth} folder in a temp dir and the whole jllm-web API over it, on a harness with a few fake tools, one
 * settings skill and one disabled MCP server.
 */
final class WebauthFixture {

    static final ObjectMapper MAPPER = new ObjectMapper();

    final Path root;
    final UserRepository users;
    final Permissions permissions;
    final SkillRepository skills;
    /** The clock of the sign-in sessions; tests move it. */
    final MutableClock clock = new MutableClock();
    final LoginSessionRepository sessions = new InMemoryLoginSessionRepository(clock);
    /** The stored settings ({@code stored-settings.json}), holding the role mapping options. */
    final org.kendar.llm.harness.settings.SettingsSink settingsSink;
    /** No role mapping properties: the options come from {@link #settingsSink}; default role {@code reader}. */
    final org.kendar.llm.web.commons.auth.RoleMappingSettings roleMapping;
    final LoginThrottle throttle = new LoginThrottle();
    final AgentHarness harness;
    final MockMvc mvc;
    private final CookieAuthentication auth;
    private final ToolCatalogView catalog;
    private final Gate gate;

    /** A clock that stands still unless moved. */
    static final class MutableClock extends java.time.Clock {
        volatile java.time.Instant now = java.time.Instant.now();

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    /** A tool that reports what it runs on, the way run_js reports its engine. */
    static HarnessTool toolWithStatus(String name, String status) {
        SimpleTool t = tool(name, ToolKind.OTHER);
        return new HarnessTool() {
            @Override public String name() { return t.name(); }
            @Override public String description() { return t.description(); }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return t.inputSchema(); }
            @Override public String group() { return t.group(); }
            @Override public ToolKind kind() { return t.kind(); }
            @Override public String status() { return status; }
            @Override public String execute(org.kendar.llm.harness.tools.ToolContext ctx,
                                            com.fasterxml.jackson.databind.JsonNode input) throws Exception {
                return t.execute(ctx, input);
            }
        };
    }

    static SimpleTool tool(String name, ToolKind kind) {
        return new SimpleTool(name, name + " tool", MAPPER.createObjectNode(), "g", kind, (c, i) -> "");
    }

    WebauthFixture(Path root) throws Exception {
        this.root = root;
        Files.createDirectories(root.resolve("users"));
        Files.createDirectories(root.resolve("permissions"));
        Files.writeString(root.resolve("permissions/reader.json"),
                "{\"tools\":[\"read_file\",\"grep\",\"skill\",\"mcp__docs__*\"],\"readOnly\":true}");
        Files.writeString(root.resolve("permissions/guest.json"), "{\"tools\":[\"web_fetch\"]}");
        user("admin", "admin");
        user("rita", "reader");
        user("gus", "guest");
        Path pdf = Files.writeString(root.resolve("pdf.md"), "---\ndescription: PDFs\n---\nUse pdftotext.");
        FileRoleRepository roleRepo = new FileRoleRepository(root.resolve("permissions"), MAPPER);
        users = new FileUserRepository(root.resolve("users"), MAPPER, roleRepo);
        permissions = new Permissions(roleRepo);
        skills = new FileSkillRepository(root);
        settingsSink = new org.kendar.llm.harness.settings.FileSettingsSink(root.resolve("stored-settings.json"));
        roleMapping = new org.kendar.llm.web.commons.auth.RoleMappingSettings(
                org.kendar.llm.web.commons.auth.RoleMappingProperties.NONE, settingsSink, "reader");
        ModelClient unused = new ModelClient() {
            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String simpleComplete(String system, String user, int maxTokens) {
                return "";
            }
        };
        HarnessSettings settings = HarnessSettings.builder()
                .mcpServers(List.of(new McpServerSettings("docs", "http://127.0.0.1:9/mcp", null, null, false, null, null, null, null, null, null, null, null)))
                .skills(List.of(new SkillSettings("pdf", null, pdf.toString()))).build();
        List<HarnessTool> tools = List.of(tool("read_file", ToolKind.READ_ONLY), tool("grep", ToolKind.READ_ONLY),
                tool("write_file", ToolKind.FILE_EDIT), tool("web_fetch", ToolKind.READ_ONLY),
                toolWithStatus("run_js", "edition community, profile isolated, isolate available"));
        harness = new AgentHarnessBuilder().modelClient(unused).sessionStore(new InMemoryChatSessionStore())
                .objectMapper(MAPPER).withoutStandardTools().tools(tools).settings(settings)
                .userPolicyResolver(permissions.resolver(users, skills))
                .principalDirectory(id -> users.find(id).map(u -> u.principal()).orElse(null))
                .build();
        auth = new CookieAuthentication(users, sessions);
        catalog = new ToolCatalogView(harness);
        gate = new Gate(auth, auth, harness.sessions());
        mvc = mvc(false, true);
    }

    /** The API with the default-password enforcement; {@code onLoopback}: also for loopback callers; {@code boundToLoopback}: the server listens on loopback only. */
    MockMvc mvc(boolean onLoopback, boolean boundToLoopback) {
        return MockMvcBuilders.standaloneSetup(
                        new AuthController(new AuthApi(users, auth, sessions, throttle,
                                new org.kendar.llm.web.commons.auth.OidcProperties(null, null),
                                java.time.Duration.ofDays(30), clock), org.kendar.llm.web.commons.ClientAddress.direct()),
                        new MeController(new MeApi(users, permissions, skills, sessions, throttle, harness, catalog)),
                        new AdminController(new AdminApi(users, permissions, skills, sessions, harness, catalog, roleMapping, throttle)),
                        new ChatController(new ChatApi(harness, MAPPER, new HarnessPricing(0, 0, 0, 0), auth), auth))
                .setControllerAdvice(new ApiExceptionAdvice())
                .addInterceptors(new ApiInterceptor(gate, auth, MAPPER, onLoopback, boundToLoopback, null),
                        new MappedInterceptor(new String[]{"/api/admin/**"}, new AdminInterceptor(gate, auth, MAPPER)))
                .build();
    }

    void user(String id, String role) throws Exception {
        Files.writeString(root.resolve("users/" + id + ".json"),
                "{\"name\":\"" + id + "\",\"role\":\"" + role + "\",\"credentials\":{}}");
    }

    ResultActions login(String id, String password) throws Exception {
        return mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(java.util.Map.of("id", id, "password", password))));
    }

    /** Signs in with the default password; the session cookie. */
    Cookie signIn(String id) throws Exception {
        return signIn(id, org.kendar.llm.web.commons.auth.PasswordHasher.defaultPassword(id));
    }

    Cookie signIn(String id, String password) throws Exception {
        Cookie c = login(id, password).andReturn().getResponse().getCookie(CookieAuthentication.COOKIE);
        if (c == null || c.getValue().isEmpty()) throw new AssertionError("sign-in of " + id + " failed");
        return c;
    }

    ResultActions get(Cookie c, String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).cookie(c));
    }

    ResultActions send(Cookie c, String method, String path, Object body) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), path).cookie(c)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body == null ? java.util.Map.of() : body));
        return mvc.perform(req);
    }

    static JsonNode json(ResultActions r) throws Exception {
        return MAPPER.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
