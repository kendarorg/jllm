package org.kendar.llm.harness.commands;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.session.ChatSessionData;
import org.kendar.llm.harness.session.FileChatSessionStore;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ChatsCommandsTest {

    @TempDir
    Path base;

    private FileChatSessionStore store;
    private AgentHarness harness;

    @BeforeEach
    void start() {
        store = new FileChatSessionStore(base.resolve("history"), MAPPER);
        harness = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base)).withoutStandardTools()
                .sessionStore(store).build();
    }

    /** A chat last updated {@code daysAgo} days ago. */
    private String chatAged(String title, int daysAgo) {
        ChatSessionData c = harness.sessions().create(title);
        ChatSessionData aged = new ChatSessionData(c.chatId(), c.agentId(), title, c.mode(), "", null, 0, 0, 0, 0,
                c.createdAt(), Instant.now().minus(daysAgo, ChronoUnit.DAYS), 0);
        store.save(aged);
        return c.chatId();
    }

    private List<String> ids() {
        return harness.sessions().list().stream().map(ChatSessionData::chatId).toList();
    }

    @Test
    void chatsListsIdTitleAndCountWithCurrentMarked() {
        String a = chatAged("alpha", 0);
        String out = harness.commands().execute(a, "/chats").output();
        assertTrue(out.startsWith("* " + a), out);
        assertTrue(out.contains("\"alpha\""));
        assertTrue(out.contains("0 messages"));
    }

    @Test
    void deleteRemovesOneChatButNeverTheCurrentOne() {
        String cur = chatAged("cur", 0);
        String other = chatAged("other", 1);

        assertTrue(harness.commands().execute(cur, "/chats delete " + cur).output().contains("Cannot delete"));
        assertTrue(ids().contains(cur));
        harness.commands().execute(cur, "/chats delete " + other.substring(0, other.length() - 3));
        assertEquals(List.of(cur), ids());
    }

    @Test
    void cleanAsksFirstThenKeepsCurrentAndRecentChats() {
        String cur = chatAged("cur", 90);
        String recent = chatAged("recent", 2);
        String old1 = chatAged("old1", 40);
        String old2 = chatAged("old2", 60);

        String ask = harness.commands().execute(cur, "/chats clean").output();
        assertTrue(ask.contains("2 chat(s)"), ask);
        assertTrue(ask.contains("confirm"));
        assertEquals(4, ids().size(), "nothing is deleted before the confirmation");

        harness.commands().execute(cur, "/chats clean confirm");
        assertTrue(ids().containsAll(List.of(cur, recent)));
        assertFalse(ids().contains(old1));
        assertFalse(ids().contains(old2));
    }

    @Test
    void retentionLimitDeletesTheOldestFirstAndKeepsTheOpenedChat() {
        String oldest = chatAged("oldest", 30);
        String keep = chatAged("keep-though-old", 20);
        String mid = chatAged("mid", 10);
        String newest = chatAged("newest", 1);

        int deleted = harness.enforceChatRetention(2, keep);

        assertEquals(2, deleted);
        assertEquals(2, ids().size());
        assertTrue(ids().contains(keep));
        assertTrue(ids().contains(newest));
        assertFalse(ids().contains(oldest));
        assertFalse(ids().contains(mid));
        assertEquals(0, harness.enforceChatRetention(null, keep), "unlimited by default");
    }

    @Test
    void resumeAcceptsAUniqueIdPrefix() {
        String a = chatAged("alpha", 1);
        var r = harness.commands().execute(a, "/resume " + a.substring(0, 20));
        assertEquals(a, r.switchToChatId());
        assertNull(harness.commands().execute(a, "/resume nope").switchToChatId());
    }
}
