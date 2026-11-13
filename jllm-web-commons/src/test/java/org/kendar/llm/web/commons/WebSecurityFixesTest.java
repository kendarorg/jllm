package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.sandbox.ChatDirSandboxExecutor;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web gating of slash commands, approvals with a typed folder, upload names, file suggestions. */
class WebSecurityFixesTest {

    private static final TestUser ALICE = new TestUser("alice", false);
    private static final TestUser ADMIN = new TestUser("admin", true);

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path dir;
    private AgentHarness harness;
    private MockMvc chats;
    private MockMvc files;

    @BeforeEach
    void setUp() {
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
        harness = AgentHarness.builder().modelClient(unused)
                .sandbox(new ChatDirSandboxExecutor(HarnessConfig.defaults(), dir))
                .sessionStore(new InMemoryChatSessionStore()).objectMapper(mapper).build();
        chats = MockMvcBuilders.standaloneSetup(TestUser.chatController(harness, mapper))
                .build();
        files = MockMvcBuilders.standaloneSetup(new FilesController(harness)).build();
    }

    private String create(TestUser user) throws Exception {
        String body = chats.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{}")
                .requestAttr(WebPrincipal.ATTR, user)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return mapper.readTree(body).path("chatId").asText();
    }

    private JsonNode send(String chat, TestUser user, String text) throws Exception {
        return mapper.readTree(chats.perform(post("/api/chat/" + chat + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("text", text)))
                .requestAttr(WebPrincipal.ATTR, user)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void slashCommandsAreGatedByUser() throws Exception {
        String a = create(ALICE);
        String b = create(new TestUser("bob", false));
        assertTrue(send(a, ALICE, "/model other").path("output").asText().contains("administrator"));
        assertTrue(send(a, ALICE, "/logout x").path("output").asText().contains("administrator"));
        assertTrue(send(a, ADMIN, "/new").path("output").asText().contains("not available"));
        String list = send(a, ALICE, "/chats").path("output").asText();
        assertTrue(list.contains(a) && !list.contains(b), list);
        assertTrue(send(a, ALICE, "/resume " + b).path("output").asText().contains("Unknown chat"));
        assertTrue(send(a, ADMIN, "/chats").path("output").asText().contains(b));
        // tool toggles stay in the chat
        String tool = harness.tools().all().iterator().next().name();
        send(a, ALICE, "/tools disable " + tool);
        assertFalse(harness.sessions().chatAllowsTool(a, tool));
        assertTrue(harness.sessions().chatAllowsTool(b, tool));
        assertTrue(harness.tools().isEnabled(tool));
    }

    @Test
    void commandListHidesWhatTheUserCannotRun() throws Exception {
        JsonNode cmds = mapper.readTree(chats.perform(get("/api/chat/commands").requestAttr(WebPrincipal.ATTR, ALICE))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode c : cmds) {
            assertFalse(Set.of("/new", "/model", "/login", "/logout").contains(c.path("name").asText()), c.toString());
        }
    }

    @Test
    void approvalWithAnInvalidTypedFolderIsRejected() throws Exception {
        String a = create(ALICE);
        for (String bad : new String[]{"../outside", "/etc", ".git/hooks", "C:\\x", "GIT~1"}) {
            chats.perform(post("/api/chat/" + a + "/approvals/whatever").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("approved", true, "rememberDir", true,
                                    "editedText", bad))))
                    .andExpect(status().isBadRequest());
        }
        // a valid folder goes on to the normal check (no such approval here)
        chats.perform(post("/api/chat/" + a + "/approvals/whatever").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("approved", true, "rememberDir", true,
                                "editedText", "src"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteForgetsTheChatEverywhere() throws Exception {
        String a = create(ALICE);
        chats.perform(delete("/api/chat/" + a)).andExpect(status().isNoContent());
        assertTrue(harness.sessions().find(a).isEmpty());
    }

    @Test
    void uploadNamesAreValidated() throws Exception {
        String a = create(ALICE);
        String base = "/api/chat/" + a + "/fs/upload";
        for (String bad : new String[]{"/", "a:b", "dir/", "x/..", "con", "a?b", "trail."}) {
            files.perform(multipart(base).file(new MockMultipartFile("files", bad, "text/plain", "x".getBytes())))
                    .andExpect(status().isBadRequest());
        }
        files.perform(multipart(base).file(new MockMultipartFile("files", "C:\\Users\\me\\ok.txt", "text/plain",
                "x".getBytes()))).andExpect(status().isOk());
        assertTrue(Files.exists(harness.sandbox().workDir(a).resolve("ok.txt")));
        assertEquals("ok.txt", FilesController.uploadName("some/dir/ok.txt"));
        assertThrows(ResponseStatusException.class, () -> FilesController.uploadName(null));
    }

    @Test
    void suggestionsSkipTheHarnessDirectory() throws Exception {
        Files.createDirectories(dir.resolve("w/.jllm/history"));
        Files.writeString(dir.resolve("w/.jllm/history/secret.txt"), "x");
        Files.createDirectories(dir.resolve("w/src"));
        Files.writeString(dir.resolve("w/src/main.txt"), "x");
        List<String> all = ChatController.suggestions(dir.resolve("w"), "");
        assertEquals(List.of("src/main.txt"), all);
        assertEquals(List.of(), ChatController.suggestions(dir.resolve("missing"), ""));
    }
}
