package org.kendar.llm.web.commons;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesApiTest {

    private static final Fixtures.User ALICE = new Fixtures.User("alice", false);

    @TempDir
    Path dir;
    private AgentHarness harness;
    private FilesApi files;
    private String chat;

    @BeforeEach
    void setUp() {
        harness = Fixtures.harness(dir);
        files = new FilesApi(harness);
        chat = (String) Fixtures.chatApi(harness).create(Map.of(), ALICE).get("chatId");
    }

    private static int status(Runnable call) {
        return assertThrows(ApiException.class, call::run).status();
    }

    private static UploadedFile upload(String name, String content) {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        return new UploadedFile(name, b.length, new ByteArrayInputStream(b));
    }

    @Test
    void uploadNamesAreValidated() {
        assertEquals("ok.txt", FilesApi.uploadName("some/dir/ok.txt"));
        assertEquals("ok.txt", FilesApi.uploadName("C:\\Users\\me\\ok.txt"));
        for (String bad : new String[]{null, "/", "a:b", "dir/", "x/..", "con", "a?b", "trail."}) {
            assertEquals(400, assertThrows(ApiException.class, () -> FilesApi.uploadName(bad)).status(), bad);
        }
    }

    @Test
    void writeReadRenameDeleteRoundTrip() throws Exception {
        files.create(chat, Map.of("name", "a.txt", "content", "one"), "workspace");
        assertEquals("one", files.read(chat, "a.txt", "workspace").get("content"));
        files.write(chat, Map.of("path", "a.txt", "content", "two"), "workspace");
        assertEquals("two", files.read(chat, "a.txt", "workspace").get("content"));
        assertEquals(Map.of("path", "b.txt"), files.rename(chat, Map.of("path", "a.txt", "name", "b.txt"), "workspace"));
        files.delete(chat, "b.txt", "workspace");
        assertEquals(404, assertThrows(ApiException.class, () -> files.read(chat, "b.txt", "workspace")).status());
    }

    @Test
    void creatingTwiceIs409AndTheHarnessDirectoryIsHidden() throws Exception {
        files.create(chat, Map.of("name", "a.txt"), "workspace");
        assertEquals(409, assertThrows(ApiException.class,
                () -> files.create(chat, Map.of("name", "a.txt"), "workspace")).status());
        assertEquals(404, assertThrows(ApiException.class, () -> files.list(chat, ".jllm", "workspace")).status());
        assertEquals(400, assertThrows(ApiException.class,
                () -> files.create(chat, Map.of("name", ".jllm"), "workspace")).status());
    }

    @Test
    void pathsLeavingTheProjectAreRefused() {
        assertEquals(400, assertThrows(ApiException.class, () -> files.read(chat, "../x", "workspace")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> files.list(chat, "../..", "workspace")).status());
    }

    @Test
    void uploadSavesAndRefusesToOverwrite() throws Exception {
        Map<String, Object> saved = files.upload(chat, "", List.of(upload("up.txt", "x")), false, "workspace");
        assertEquals(List.of("up.txt"), saved.get("saved"));
        assertTrue(Files.exists(harness.sandbox().workDir(chat).resolve("up.txt")));
        assertEquals(409, assertThrows(ApiException.class,
                () -> files.upload(chat, "", List.of(upload("up.txt", "y")), false, "workspace")).status());
        files.upload(chat, "", List.of(upload("up.txt", "y")), true, "workspace");
        assertEquals("y", files.read(chat, "up.txt", "workspace").get("content"));
    }

    @Test
    void downloadOfAFileAndOfAFolder() throws Exception {
        files.create(chat, Map.of("name", "a.txt", "content", "one"), "workspace");
        ByteStream file = files.download(chat, "a.txt", "workspace");
        assertEquals("application/octet-stream", file.contentType());
        assertEquals(3L, file.length());
        assertEquals("a.txt", file.filename());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.writer().writeTo(out);
        assertEquals("one", out.toString(StandardCharsets.UTF_8));

        ByteStream zip = files.download(chat, "", "workspace");
        assertEquals("application/zip", zip.contentType());
        assertEquals(null, zip.length());
        assertEquals("project.zip", zip.filename());
    }

    @Test
    void daemonOnlyEndpointsAreNotImplementedOnTheHost() {
        assertEquals(501, status(() -> files.glob(chat, "**/*")));
        assertEquals(501, status(() -> files.tree(chat, "", 2, "workspace")));
        assertEquals(400, status(() -> {
            try {
                files.list(chat, "", "scratch");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    void unknownChatIs404() {
        assertEquals(404, status(() -> {
            try {
                files.list("nope", "", "workspace");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }
}
