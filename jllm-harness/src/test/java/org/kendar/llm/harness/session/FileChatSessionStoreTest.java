package org.kendar.llm.harness.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;

class FileChatSessionStoreTest {

    @TempDir
    Path root;

    private FileChatSessionStore store() {
        return new FileChatSessionStore(root, MAPPER);
    }

    private static ChatSessionData chat(String id, String title) {
        return ChatSessionData.create(id, "agent-" + id, title);
    }

    private static String msg(String role, String text) {
        return "{\"role\":\"" + role + "\",\"content\":\"" + text + "\"}";
    }

    @Test
    void savedChatSurvivesANewStoreWithRowAndLog() {
        ChatSessionData c = chat("c1", "Fix login").withToolGroups("a,b").withMode("PLAN")
                .withUsageAdded(1, 2, 3, 4).withMessagesJson(msg("user", "hi") + "\n" + msg("assistant", "yo"));
        store().save(c);

        ChatSessionData back = store().find("c1").orElseThrow();
        assertEquals("agent-c1", back.agentId());
        assertEquals("Fix login", back.title());
        assertEquals("PLAN", back.mode());
        assertEquals("a,b", back.toolGroups());
        assertEquals(1, back.inputTokens());
        assertEquals(4, back.cacheCreationTokens());
        assertEquals(c.messagesJson(), back.messagesJson());
        assertEquals(2, back.messageCount());
        assertEquals(c.createdAt(), back.createdAt());
    }

    @Test
    void listReadsRowsOnlyNewestFirst() throws Exception {
        FileChatSessionStore s1 = store();
        s1.save(chat("old", "old").withMessagesJson(msg("user", "a")));
        Thread.sleep(5);
        s1.save(chat("new", "new").withMessagesJson(msg("user", "b")));

        List<ChatSessionData> list = store().list();
        assertEquals(List.of("new", "old"), list.stream().map(ChatSessionData::chatId).toList());
        assertEquals("", list.get(0).messagesJson());
        assertEquals(1, list.get(0).messageCount());
    }

    @Test
    void logIsAppendedWhenExtendedAndRewrittenOtherwise() throws Exception {
        FileChatSessionStore s = store();
        ChatSessionData c = chat("c1", "t");
        String m1 = msg("user", "one");
        s.save(c.withMessagesJson(m1));
        Path log = s.historyDir("c1", "agent-c1").resolve("messages.jsonl");

        String m2 = m1 + "\n" + msg("assistant", "two");
        s.save(c.withMessagesJson(m2));
        assertEquals(m2, Files.readString(log));

        s.save(c.withMessagesJson(msg("user", "summary"))); // not an extension: compaction, /clear
        assertEquals(msg("user", "summary"), Files.readString(log));
        s.save(c.withMessagesJson(""));
        assertEquals("", Files.readString(log));
        assertEquals("", store().find("c1").orElseThrow().messagesJson());
    }

    @Test
    void corruptRowIsSkippedAndTheRestStillLoads() throws Exception {
        store().save(chat("good", "good"));
        Path bad = root.resolve("bad");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("session.json"), "{ not json");

        FileChatSessionStore s = store();
        assertEquals(List.of("good"), s.list().stream().map(ChatSessionData::chatId).toList());
        assertTrue(s.find("bad").isEmpty());
    }

    @Test
    void deleteRemovesTheWholeChatTree() {
        FileChatSessionStore s = store();
        s.save(chat("c1", "t").withMessagesJson(msg("user", "x")));
        s.delete("c1");
        assertFalse(Files.exists(root.resolve("c1")));
        assertTrue(store().find("c1").isEmpty());
        assertTrue(store().list().isEmpty());
    }

    @Test
    void unansweredToolUseIsRepairedAndPersisted() throws Exception {
        String use = "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"shell\",\"input\":{}},"
                + "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"shell\",\"input\":{}}]}";
        store().save(chat("c1", "t").withMessagesJson(msg("user", "go") + "\n" + use));

        FileChatSessionStore s2 = store();
        ChatSessionData back = s2.find("c1").orElseThrow();
        String[] lines = back.messagesJson().split("\n");
        assertEquals(3, lines.length);
        var results = MAPPER.readTree(lines[2]);
        assertEquals("user", results.path("role").asText());
        assertEquals(2, results.path("content").size());
        assertEquals("t2", results.path("content").get(1).path("tool_use_id").asText());
        assertEquals(InterruptedTurnRepair.MARKER, results.path("content").get(0).path("content").asText());
        assertTrue(s2.wasRepaired("c1"));
        // written back: a third process sees the same bytes and has nothing left to repair
        FileChatSessionStore s3 = store();
        assertEquals(back.messagesJson(), s3.find("c1").orElseThrow().messagesJson());
        assertFalse(s3.wasRepaired("c1"));
    }

    @Test
    void answeredLogAndTrailingUserMessageAreLeftAlone() {
        assertNull(InterruptedTurnRepair.repair(msg("user", "a") + "\n" + msg("assistant", "b"), MAPPER));
        assertNull(InterruptedTurnRepair.repair(msg("user", "a"), MAPPER));
        assertNull(InterruptedTurnRepair.repair("", MAPPER));
    }

    @Test
    void perChatRootsAreEnumeratedBySupplier() {
        java.util.function.Function<String, Path> chatRoot = id -> root.resolve(id).resolve("hist");
        java.util.function.Supplier<List<Path>> dirs = () -> {
            try (var st = Files.list(root)) {
                return st.map(d -> d.resolve("hist")).filter(Files::isDirectory).toList();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        new FileChatSessionStore(chatRoot, dirs, MAPPER).save(chat("c1", "one"));
        assertEquals(List.of("c1"),
                new FileChatSessionStore(chatRoot, dirs, MAPPER).list().stream().map(ChatSessionData::chatId).toList());
    }

    @Test
    void rootLockIsExclusive() {
        try (HistoryRootLock first = HistoryRootLock.acquire(root)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> HistoryRootLock.acquire(root));
            assertTrue(e.getMessage().contains(".lock"));
        }
        HistoryRootLock.acquire(root).close(); // free again after close
    }
}
