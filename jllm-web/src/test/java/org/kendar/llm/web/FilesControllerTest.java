package org.kendar.llm.web;

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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FilesControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path dir;
    private MockMvc mvc;
    private String base;

    private Path ws() {
        return harness.sandbox().workDir(base.split("/")[3]);
    }
    private AgentHarness harness;

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
        harness = AgentHarness.builder()
                .modelClient(unused)
                .sandbox(new ChatDirSandboxExecutor(HarnessConfig.defaults(), dir))
                .sessionStore(new InMemoryChatSessionStore())
                .objectMapper(mapper)
                .build();
        mvc = MockMvcBuilders.standaloneSetup(new FilesController(harness)).build();
        base = "/api/chat/" + harness.sessions().create(null).chatId() + "/fs";
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    @Test
    void crudRoundTrip() throws Exception {
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "", "name", "sub", "type", "dir"))).andExpect(status().isCreated());
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "sub", "name", "a.txt", "type", "file", "content", "hi")))
                .andExpect(status().isCreated());
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "sub", "name", "a.txt", "type", "file"))).andExpect(status().isConflict());

        JsonNode list = mapper.readTree(mvc.perform(get(base).param("path", "sub")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("sub/a.txt", list.path("entries").get(0).path("path").asText());

        mvc.perform(put(base + "/content").contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "sub/a.txt", "content", "changed"))).andExpect(status().isOk());
        assertEquals("changed", Files.readString(ws().resolve("sub/a.txt")));

        mvc.perform(post(base + "/rename").contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "sub/a.txt", "name", "b.txt"))).andExpect(status().isOk());
        assertTrue(Files.exists(ws().resolve("sub/b.txt")));

        mvc.perform(multipart(base + "/upload").file(new MockMultipartFile("files", "c.bin", null, new byte[]{1, 2}))
                .param("path", "sub")).andExpect(status().isOk());
        assertTrue(Files.exists(ws().resolve("sub/c.bin")));

        var started = mvc.perform(get(base + "/download").param("path", "sub")).andReturn();
        byte[] zip = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray();
        assertTrue(zip.length > 0 && zip[0] == 'P' && zip[1] == 'K');

        mvc.perform(delete(base).param("path", "sub")).andExpect(status().isNoContent());
        assertFalse(Files.exists(ws().resolve("sub")));
    }

    @Test
    void rejectsEscapesAndRootChanges() throws Exception {
        mvc.perform(get(base).param("path", "../")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/download").param("path", "../../x")).andExpect(status().isBadRequest());
        mvc.perform(delete(base).param("path", "")).andExpect(status().isBadRequest());
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "", "name", "../evil", "type", "file"))).andExpect(status().isBadRequest());
    }

    @Test
    void filesAreScopedToTheChatAndDeletedWithIt() throws Exception {
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "", "name", "mine.txt", "type", "file"))).andExpect(status().isCreated());
        String other = harness.sessions().create(null).chatId();
        String otherBase = "/api/chat/" + other + "/fs";
        JsonNode list = mapper.readTree(mvc.perform(get(otherBase)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, list.path("entries").size());
        mvc.perform(get("/api/chat/nope/fs")).andExpect(status().isNotFound());

        String id = base.split("/")[3];
        Path ws = harness.sandbox().workDir(id);
        assertTrue(Files.exists(ws.resolve("mine.txt")));
        harness.sandbox().scratchDir(id, "a");
        assertTrue(Files.isDirectory(ws.resolve(".kendhar/scratchpad/a")));
        assertEquals(dir.resolve(id), ws);
        mvc.perform(get(base).param("path", ".kendhar")).andExpect(status().isNotFound());
        harness.sandbox().deleteWorkDir(id);
        assertFalse(Files.exists(ws));
        assertTrue(Files.exists(dir.resolve(other).getParent()));
    }

    @Test
    void singleChildDirectoriesAreChained() throws Exception {
        Files.createDirectories(ws().resolve("a/b/c"));
        Files.writeString(ws().resolve("a/b/c/f.txt"), "x");
        Files.createDirectories(ws().resolve("m/n"));
        Files.writeString(ws().resolve("m/g.txt"), "x");
        JsonNode list = mapper.readTree(mvc.perform(get(base)).andReturn().getResponse().getContentAsString());
        JsonNode a = null, m = null;
        for (JsonNode e : list.path("entries")) {
            if ("a".equals(e.path("name").asText())) a = e;
            if ("m".equals(e.path("name").asText())) m = e;
        }
        assertEquals(3, a.path("chain").size());
        assertEquals("a/b/c", a.path("chain").get(2).path("path").asText());
        assertEquals(1, m.path("chain").size());
    }
}
