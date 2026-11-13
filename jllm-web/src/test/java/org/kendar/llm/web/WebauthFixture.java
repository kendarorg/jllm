package org.kendar.llm.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.kendar.llm.harness.AgentHarness;
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
import org.kendar.llm.web.admin.AdminController;
import org.kendar.llm.web.admin.AdminInterceptor;
import org.kendar.llm.web.admin.ToolCatalogView;
import org.kendar.llm.web.auth.AuthController;
import org.kendar.llm.web.auth.CookieAuthentication;
import org.kendar.llm.web.auth.LoginThrottle;
import org.kendar.llm.web.auth.SessionStore;
import org.kendar.llm.web.commons.ApiInterceptor;
import org.kendar.llm.web.commons.ChatController;
import org.kendar.llm.web.me.MeController;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.users.SkillFiles;
import org.kendar.llm.web.users.UserDirectory;
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
    final UserDirectory users;
    final Permissions permissions;
    final SkillFiles skills;
    final SessionStore sessions = new SessionStore();
    final LoginThrottle throttle = new LoginThrottle();
    final AgentHarness harness;
    final MockMvc mvc;

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
        users = new UserDirectory(root.resolve("users"), MAPPER);
        permissions = new Permissions(root.resolve("permissions"), MAPPER);
        skills = new SkillFiles(root);
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
        HarnessSettings settings = new HarnessSettings(
                List.of(new McpServerSettings("docs", "http://127.0.0.1:9/mcp", null, null, false, null)),
                null, List.of(new SkillSettings("pdf", null, pdf.toString())), null, null);
        List<HarnessTool> tools = List.of(tool("read_file", ToolKind.READ_ONLY), tool("grep", ToolKind.READ_ONLY),
                tool("write_file", ToolKind.FILE_EDIT), tool("web_fetch", ToolKind.READ_ONLY));
        harness = AgentHarness.builder().modelClient(unused).sessionStore(new InMemoryChatSessionStore())
                .objectMapper(MAPPER).withoutStandardTools().tools(tools).settings(settings)
                .userPolicyResolver(permissions.resolver(users, skills))
                .principalDirectory(id -> users.find(id).map(u -> u.principal()).orElse(null))
                .build();
        CookieAuthentication auth = new CookieAuthentication(users, sessions);
        ToolCatalogView catalog = new ToolCatalogView(harness);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(users, auth, sessions, throttle),
                        new MeController(users, permissions, skills, sessions, throttle, harness, catalog),
                        new AdminController(users, permissions, skills, sessions, harness, catalog),
                        new ChatController(harness, MAPPER, new HarnessPricing(0, 0, 0, 0), auth, auth))
                .addInterceptors(new ApiInterceptor(auth, auth, harness.sessions(), MAPPER),
                        new MappedInterceptor(new String[]{"/api/admin/**"}, new AdminInterceptor(auth, MAPPER)))
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
        return signIn(id, org.kendar.llm.web.auth.PasswordHasher.defaultPassword(id));
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
