package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Atomic log writes, tolerant loading and the reversible directory names of the file store. */
class FileChatSessionStoreSafetyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String U1 = "{\"role\":\"user\",\"content\":\"one\"}";
    private static final String A1 = "{\"role\":\"assistant\",\"content\":\"two\"}";

    @TempDir
    Path root;

    private Path logOf(String chatId, String agentId) {
        return root.resolve(FileChatSessionStore.safe(chatId)).resolve(FileChatSessionStore.safe(agentId))
                .resolve(FileChatSessionStore.LOG);
    }

    @Test
    void partialLastLineIsDroppedAndTheFileRewritten() throws Exception {
        new FileChatSessionStore(root, MAPPER).save(ChatSessionData.create("c1", "a1", "t").withMessagesJson(U1 + "\n" + A1));
        Path log = logOf("c1", "a1");
        Files.writeString(log, "\n{\"role\":\"us", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        FileChatSessionStore reopened = new FileChatSessionStore(root, MAPPER);
        ChatSessionData row = reopened.find("c1").orElseThrow();
        assertEquals(U1 + "\n" + A1, row.messagesJson());
        assertEquals(U1 + "\n" + A1, Files.readString(log), "repaired on disk");
        assertFalse(Files.exists(log.resolveSibling(FileChatSessionStore.LOG + ".tmp")));
    }

    @Test
    void aBadLineInTheMiddleStaysAnError() throws Exception {
        new FileChatSessionStore(root, MAPPER).save(ChatSessionData.create("c1", "a1", "t").withMessagesJson(U1 + "\n" + A1));
        Path log = logOf("c1", "a1");
        Files.writeString(log, U1 + "\n{broken\n" + A1);
        FileChatSessionStore reopened = new FileChatSessionStore(root, MAPPER);
        ChatSessionService service = new ChatSessionService(reopened, "a", MAPPER);
        ChatSessionData row = reopened.find("c1").orElseThrow();
        assertThrows(IllegalStateException.class, () -> service.messages(row));
        assertEquals(U1 + "\n{broken\n" + A1, Files.readString(log), "left as it was");
    }

    @Test
    void rewriteGoesThroughATemporaryFileAndAFailedSaveIsNotCached() throws Exception {
        FileChatSessionStore store = new FileChatSessionStore(root, MAPPER);
        ChatSessionData c = ChatSessionData.create("c1", "a1", "t");
        store.save(c.withMessagesJson(U1 + "\n" + A1));
        Path log = logOf("c1", "a1");
        Path tmp = log.resolveSibling(FileChatSessionStore.LOG + ".tmp");
        Files.createDirectories(tmp); // the next full rewrite cannot write its temporary file
        assertThrows(java.io.UncheckedIOException.class, () -> store.save(c.withMessagesJson(A1)));
        assertEquals(U1 + "\n" + A1, Files.readString(log), "the old log is intact");
        Files.delete(tmp);
        store.save(c.withMessagesJson(A1 + "\n" + U1));
        assertEquals(A1 + "\n" + U1, Files.readString(log));
    }

    @Test
    void dotIdsAreRejectedAndNeverDeleteTheRoot() throws Exception {
        FileChatSessionStore store = new FileChatSessionStore(root, MAPPER);
        store.save(ChatSessionData.create("keep", "a1", "t").withMessagesJson(U1));
        for (String bad : new String[]{".", "..", "", "a/b", "..\\x"}) {
            assertTrue(store.find(bad).isEmpty(), bad);
            assertThrows(IllegalArgumentException.class, () -> store.save(ChatSessionData.create(bad, "a1", "t")), bad);
            store.delete(bad);
        }
        assertTrue(store.find("keep").isPresent());
        assertThrows(IllegalArgumentException.class, () -> FileChatSessionStore.safe("."));
        assertThrows(IllegalArgumentException.class, () -> FileChatSessionStore.safe(".."));
    }

    @Test
    void namesAreReversibleAndDoNotCollide() {
        assertNotEquals(FileChatSessionStore.safe("a/b"), FileChatSessionStore.safe("a_b"));
        assertNotEquals(FileChatSessionStore.safe("a"), FileChatSessionStore.safe("a."));
        assertEquals("abc-1_2.x", FileChatSessionStore.safe("abc-1_2.x"));
        assertEquals("a%2Fb", FileChatSessionStore.safe("a/b"));
        assertEquals("100%25", FileChatSessionStore.safe("100%"));
        FileChatSessionStore store = new FileChatSessionStore(root, MAPPER);
        store.save(ChatSessionData.create("c1", "agent/x", "t").withMessagesJson(U1));
        store.save(ChatSessionData.create("c2", "agent_x", "t").withMessagesJson(A1));
        FileChatSessionStore reopened = new FileChatSessionStore(root, MAPPER);
        assertEquals(U1, reopened.find("c1").orElseThrow().messagesJson());
        assertEquals(A1, reopened.find("c2").orElseThrow().messagesJson());
    }

    @Test
    void directoriesWithTheOldNamesAreMigrated() throws Exception {
        // written by an earlier version: agent dir "agent_x" for agent id "agent:x"
        new FileChatSessionStore(root, MAPPER).save(ChatSessionData.create("c1", "agent:x", "t").withMessagesJson(U1));
        Path chatDir = root.resolve("c1");
        Files.move(chatDir.resolve(FileChatSessionStore.safe("agent:x")), chatDir.resolve("agent_x"));

        FileChatSessionStore reopened = new FileChatSessionStore(root, MAPPER);
        assertEquals(U1, reopened.find("c1").orElseThrow().messagesJson());
        assertTrue(Files.isDirectory(chatDir.resolve(FileChatSessionStore.safe("agent:x"))), "renamed");
        assertFalse(Files.exists(chatDir.resolve("agent_x")));
    }
}
