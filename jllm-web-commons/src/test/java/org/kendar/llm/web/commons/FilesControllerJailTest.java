package org.kendar.llm.web.commons;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The file API goes through the path jail: a link out of the project is never read, written, listed or zipped. */
class FilesControllerJailTest {

    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path dir;
    private MockMvc mvc;
    private AgentHarness harness;
    private String base;
    private Path ws;
    private Path outside;

    @BeforeEach
    void setUp() throws Exception {
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
        harness = new AgentHarnessBuilder()
                .modelClient(unused)
                .sandbox(new ChatDirSandboxExecutor(HarnessConfig.defaults(), dir.resolve("chats")))
                .sessionStore(new InMemoryChatSessionStore())
                .objectMapper(mapper)
                .build();
        mvc = MockMvcBuilders.standaloneSetup(new FilesController(harness)).build();
        String id = harness.sessions().create(null).chatId();
        base = "/api/chat/" + id + "/fs";
        ws = Files.createDirectories(harness.sandbox().workDir(id));
        outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "TOP SECRET");
    }

    private boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Windows without the privilege: a junction (directories only)
        }
        if (!Files.isDirectory(target) || !System.getProperty("os.name", "").toLowerCase().contains("win")) return false;
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private void assertOutsideUntouched() throws IOException {
        try (Stream<Path> s = Files.walk(outside)) {
            List<String> names = new ArrayList<>(s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList());
            assertEquals(List.of("secret.txt"), names);
        }
        assertEquals("TOP SECRET", Files.readString(outside.resolve("secret.txt")));
    }

    @Test
    void aDirectoryLinkLeavingTheProjectIsRefusedEverywhere() throws Exception {
        assumeTrue(link(ws.resolve("out"), outside), "links are not permitted on this machine");

        mvc.perform(get(base + "/content").param("path", "out/secret.txt")).andExpect(status().isBadRequest());
        mvc.perform(get(base).param("path", "out")).andExpect(status().isBadRequest());
        mvc.perform(put(base + "/content").contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "out/secret.txt", "content", "pwn"))).andExpect(status().isBadRequest());
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "out", "name", "new.txt", "type", "file", "content", "pwn")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "out", "name", "sub", "type", "dir"))).andExpect(status().isBadRequest());
        mvc.perform(multipart(base + "/upload").file(new MockMultipartFile("files", "up.txt", "text/plain", "pwn".getBytes()))
                .param("path", "out")).andExpect(status().isBadRequest());
        mvc.perform(post(base + "/rename").contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "out/secret.txt", "name", "moved.txt"))).andExpect(status().isBadRequest());
        mvc.perform(delete(base).param("path", "out/secret.txt")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/download").param("path", "out/secret.txt")).andExpect(status().isBadRequest());

        assertOutsideUntouched();
    }

    @Test
    void aLinkLeavingTheProjectIsNotListedOrZipped() throws Exception {
        assumeTrue(link(ws.resolve("out"), outside), "links are not permitted on this machine");
        Files.writeString(ws.resolve("mine.txt"), "mine");

        JsonNode list = mapper.readTree(mvc.perform(get(base)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<String> names = new ArrayList<>();
        list.path("entries").forEach(e -> names.add(e.path("name").asText()));
        assertEquals(List.of("mine.txt"), names);

        var started = mvc.perform(get(base + "/download").param("path", "")).andExpect(request().asyncStarted()).andReturn();
        byte[] zip = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray();
        assertFalse(new String(zip, java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret.txt"));
        assertOutsideUntouched();
    }

    @Test
    void deletingALinkRemovesTheLinkAndNotItsTarget() throws Exception {
        Files.createDirectories(ws.resolve("proj/inner"));
        assumeTrue(link(ws.resolve("proj/inner/out"), outside), "links are not permitted on this machine");

        mvc.perform(delete(base).param("path", "proj")).andExpect(status().isNoContent());

        assertFalse(Files.exists(ws.resolve("proj")));
        assertOutsideUntouched();
    }

    @Test
    void aLinkInsideTheProjectIsFollowed() throws Exception {
        Files.createDirectories(ws.resolve("real"));
        Files.writeString(ws.resolve("real/f.txt"), "inside");
        assumeTrue(link(ws.resolve("alias"), ws.resolve("real")), "links are not permitted on this machine");

        JsonNode read = mapper.readTree(mvc.perform(get(base + "/content").param("path", "alias/f.txt"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("inside", read.path("content").asText());
        mvc.perform(put(base + "/content").contentType(MediaType.APPLICATION_JSON)
                .content(json("path", "alias/f.txt", "content", "changed"))).andExpect(status().isOk());
        assertEquals("changed", Files.readString(ws.resolve("real/f.txt")));
    }

    @Test
    void theHiddenDirectoryStaysHiddenThroughAnAlias() throws Exception {
        Files.createDirectories(ws.resolve(".jllm/data"));
        Files.writeString(ws.resolve(".jllm/data/h.txt"), "internal");
        assumeTrue(link(ws.resolve("alias"), ws.resolve(".jllm")), "links are not permitted on this machine");

        mvc.perform(get(base + "/content").param("path", "alias/data/h.txt")).andExpect(status().isNotFound());
        JsonNode list = mapper.readTree(mvc.perform(get(base)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        list.path("entries").forEach(e -> assertNotEquals("alias", e.path("name").asText()));
    }
}
