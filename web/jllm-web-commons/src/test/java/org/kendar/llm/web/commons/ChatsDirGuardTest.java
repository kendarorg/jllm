package org.kendar.llm.web.commons;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatsDirGuardTest {

    @TempDir
    Path dir;

    @Test
    void aServerPathOutsideTheChatsDirectoryPasses() {
        ChatsDirGuard.check(dir.resolve("chats"), Map.of("webauth", dir.resolve("webauth"), "db", dir.resolve("data/jllm.db")));
        ChatsDirGuard.check(dir.resolve("chats"), Map.of("same prefix", dir.resolve("chats-other")));
        Map<String, Path> withNull = new LinkedHashMap<>();
        withNull.put("none", null);
        ChatsDirGuard.check(dir.resolve("chats"), withNull);
    }

    @Test
    void aServerPathInsideTheChatsDirectoryOrEqualToItFails() {
        Path chats = dir.resolve("chats");
        for (Path p : new Path[]{chats, chats.resolve("webauth"), chats.resolve("a/b/jllm.db"),
                chats.resolve("a").resolve("..").resolve("x"), chats.resolve("x/../../chats/y")}) {
            var e = assertThrows(IllegalStateException.class, () -> ChatsDirGuard.check(chats, Map.of("thing", p)), p.toString());
            assertTrue(e.getMessage().contains("thing") && e.getMessage().contains("jllm.chats-dir"), e.getMessage());
        }
    }

    @Test
    void dotDotSegmentsAreResolvedBeforeTheComparison() {
        Path chats = dir.resolve("chats");
        ChatsDirGuard.check(chats, Map.of("outside", chats.resolve("..").resolve("webauth")));
    }

    @Test
    void aSymbolicLinkIntoTheChatsDirectoryIsFollowed() throws Exception {
        Path chats = Files.createDirectories(dir.resolve("chats"));
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, chats);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.abort("no symbolic links here: " + e);
        }
        assertThrows(IllegalStateException.class, () -> ChatsDirGuard.check(chats, Map.of("webauth", link.resolve("webauth"))));
        // and the chats directory itself given through the link
        assertThrows(IllegalStateException.class, () -> ChatsDirGuard.check(link, Map.of("webauth", chats.resolve("webauth"))));
    }

    @Test
    void theCanonicalPathOfAMissingFolderKeepsTheMissingPart() throws Exception {
        Path real = Files.createDirectories(dir.resolve("real"));
        Path c = ChatsDirGuard.canonical(real.resolve("not/yet/there"));
        assertEquals(real.toRealPath().resolve("not/yet/there"), c);
    }
}
