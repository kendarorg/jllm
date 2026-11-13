package org.kendar.llm.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessPricing;
import org.kendar.llm.harness.auth.HarnessPrincipal;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolKind;
import org.kendar.llm.web.commons.ApiInterceptor;
import org.kendar.llm.web.commons.ChatController;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SampleUsersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static SimpleTool tool(String name, ToolKind kind) {
        return new SimpleTool(name, name, new ObjectMapper().createObjectNode(), "g", kind, (c, i) -> "");
    }

    /** The shipped permission files, copied into a temp folder so the test reads what the sample ships. */
    private static Permissions shipped(Path dir) throws Exception {
        Path src = Path.of("..", "webauth", "permissions");
        if (!Files.isDirectory(src)) src = Path.of("webauth", "permissions");
        for (String role : List.of("guest", "reader", "admin")) {
            Files.copy(src.resolve(role + ".json"), dir.resolve(role + ".json"));
        }
        return new Permissions(dir, new ObjectMapper());
    }

    private static void user(Path dir, String id, String role, String credentials) throws Exception {
        Files.writeString(dir.resolve(id + ".json"),
                "{\"name\":\"" + id + "\",\"role\":\"" + role + "\",\"credentials\":" + credentials + "}");
    }

    @Test
    void guestReachesFilesOnlyThroughRunJsAndWeb(@TempDir Path dir) throws Exception {
        UserPolicy guest = shipped(dir).forRole("guest").orElseThrow();
        assertTrue(guest.allows(tool("run_js", ToolKind.OTHER)));
        assertTrue(guest.allows(tool("web_fetch", ToolKind.READ_ONLY)));
        assertFalse(guest.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertFalse(guest.allows(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void readerHasReadOnlyFileToolsOnly(@TempDir Path dir) throws Exception {
        UserPolicy reader = shipped(dir).forRole("reader").orElseThrow();
        assertTrue(reader.allows(tool("read_file", ToolKind.READ_ONLY)));
        assertTrue(reader.allows(tool("grep", ToolKind.READ_ONLY)));
        assertFalse(reader.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertFalse(reader.allows(tool("edit_file", ToolKind.FILE_EDIT)));
        assertFalse(reader.allows(tool("run_js", ToolKind.OTHER)));
        assertFalse(reader.allows(tool("web_fetch", ToolKind.READ_ONLY)));
    }

    @Test
    void adminHasEveryToolWithoutApprovalPrompts(@TempDir Path dir) throws Exception {
        UserPolicy admin = shipped(dir).forRole("admin").orElseThrow();
        assertTrue(admin.allows(tool("write_file", ToolKind.FILE_EDIT)));
        assertTrue(admin.allows(tool("anything", ToolKind.OTHER)));
        assertFalse(admin.approval().requiresApproval(tool("write_file", ToolKind.FILE_EDIT)));
    }

    @Test
    void roleWithoutFileGetsNothing(@TempDir Path dir) throws Exception {
        Permissions perms = shipped(dir);
        assertTrue(perms.forRole("wizard").isEmpty());
        assertFalse(perms.resolver().resolve(HarnessPrincipal.of("x")).allows(tool("read_file", ToolKind.READ_ONLY)));
        Files.writeString(dir.resolve("broken.json"), "{\"tools\": 3}");
        assertTrue(perms.forRole("broken").isEmpty());
    }

    @Test
    void credentialsComeFromEnvironmentReferencesOnly(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{\"mcp:docs\":{\"Authorization\":\"env:PATH\",\"X-Literal\":\"secret\"},"
                + "\"api:pets\":{\"X-Key\":\"env:JLLM_SAMPLE_UNSET_VARIABLE\"}}");
        FileCredentialResolver r = new FileCredentialResolver(new UserDirectory(dir, mapper));
        HarnessPrincipal rita = HarnessPrincipal.of("rita");

        Map<String, String> docs = r.headers(rita, "mcp:docs");
        assertEquals(System.getenv("PATH"), docs.get("Authorization"));
        assertFalse(docs.containsKey("X-Literal"));
        assertEquals(Map.of(), r.headers(rita, "api:pets"));
        assertEquals(Map.of(), r.headers(HarnessPrincipal.of("nobody"), "mcp:docs"));
        assertEquals(Map.of(), r.headers(null, "mcp:docs"));
    }

    @Test
    void unknownUserIdsAreRejected(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{}");
        UserDirectory users = new UserDirectory(dir, mapper);
        assertTrue(users.find("rita").isPresent());
        assertTrue(users.find("../rita").isEmpty());
        assertTrue(users.find("Rita").isEmpty());
        assertEquals(1, users.all().size());
    }

    @Test
    void chatsAreVisibleToTheirOwnerAndTheAdminOnly(@TempDir Path dir) throws Exception {
        user(dir, "rita", "guest", "{}");
        user(dir, "sam", "reader", "{}");
        user(dir, "admin", "admin", "{}");
        UserDirectory users = new UserDirectory(dir, mapper);
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
        AgentHarness harness = AgentHarness.builder().modelClient(unused)
                .sessionStore(new InMemoryChatSessionStore()).objectMapper(mapper).build();
        CookieAuthentication auth = new CookieAuthentication(users);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new ChatController(harness, mapper, new HarnessPricing(0, 0, 0, 0), auth, auth))
                .addInterceptors(new ApiInterceptor(auth, auth, harness.sessions(), mapper)).build();

        // not signed in
        mvc.perform(get("/api/chat")).andExpect(status().isUnauthorized());

        String created = mvc.perform(post("/api/chat").cookie(new jakarta.servlet.http.Cookie("jllm_user", "rita"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String chat = mapper.readTree(created).path("chatId").asText();

        mvc.perform(get("/api/chat/" + chat).cookie(new jakarta.servlet.http.Cookie("jllm_user", "rita")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/" + chat).cookie(new jakarta.servlet.http.Cookie("jllm_user", "sam")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/chat/" + chat).cookie(new jakarta.servlet.http.Cookie("jllm_user", "admin")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/" + chat).cookie(new jakarta.servlet.http.Cookie("jllm_user", "ghost")))
                .andExpect(status().isUnauthorized());

        String samList = mvc.perform(get("/api/chat").cookie(new jakarta.servlet.http.Cookie("jllm_user", "sam")))
                .andReturn().getResponse().getContentAsString();
        assertEquals("[]", samList);
        String ritaList = mvc.perform(get("/api/chat").cookie(new jakarta.servlet.http.Cookie("jllm_user", "rita")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(ritaList.contains(chat), ritaList);
    }

    @Test
    void chatWithoutPrincipalGetsNoTools(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("reader.json"), "{\"tools\":[\"*\"]}");
        var resolver = new Permissions(dir, mapper).resolver();
        var anyTool = tool("read_file", ToolKind.READ_ONLY);
        assertFalse(resolver.resolve(null).allows(anyTool));
        assertTrue(resolver.resolve(new HarnessPrincipal("u", Set.of("reader"), Map.of())).allows(anyTool));
        var identity = new org.kendar.llm.harness.auth.Identity(null, resolver, null, chat -> null);
        assertFalse(identity.policyOf("c").allows(anyTool));
    }
}
