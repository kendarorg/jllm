package org.kendar.llm.harness.session;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.permission.PermissionMode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;

class ChatSessionServiceTest {

    ChatSessionService svc;

    @BeforeEach
    void setUp() {
        svc = new ChatSessionService(new InMemoryChatSessionStore(), "jllm", MAPPER);
    }

    private ArrayNode msgs(String text) {
        ArrayNode a = MAPPER.createArrayNode();
        a.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", text);
        return a;
    }

    @Test
    void sessionsAreIsolatedByChatId() {
        ChatSessionData a = svc.create("A");
        ChatSessionData b = svc.create(null);

        svc.saveMessages(a.chatId(), msgs("only in A"));
        svc.addUsage(a.chatId(), new Usage(100, 20, 30, 40));
        svc.setMode(a.chatId(), PermissionMode.AUTO);
        svc.setTitle(a.chatId(), "renamed");

        assertNotEquals(a.chatId(), b.chatId());
        assertEquals("New chat", svc.get(b.chatId()).title());
        assertEquals(0, svc.messages(svc.get(b.chatId())).size());
        assertEquals(1, svc.messages(svc.get(a.chatId())).size());
        assertEquals(0, svc.get(b.chatId()).inputTokens());
        assertEquals(0, svc.get(b.chatId()).cacheReadTokens());
        assertEquals("DEFAULT", svc.get(b.chatId()).mode());
        assertEquals("AUTO", svc.get(a.chatId()).mode());
        assertEquals(100, svc.get(a.chatId()).inputTokens());
        assertEquals(40, svc.get(a.chatId()).cacheCreationTokens());
        assertNotEquals(svc.pinnedPrefix(svc.get(a.chatId())), svc.pinnedPrefix(svc.get(b.chatId())));
    }

    @Test
    void deleteOnlyRemovesThatChat() {
        ChatSessionData a = svc.create("A");
        ChatSessionData b = svc.create("B");

        svc.delete(a.chatId());

        assertTrue(svc.find(a.chatId()).isEmpty());
        assertTrue(svc.find(b.chatId()).isPresent());
        assertThrows(IllegalArgumentException.class, () -> svc.get(a.chatId()));
        assertThrows(IllegalArgumentException.class, () -> svc.saveMessages(a.chatId(), msgs("x")));
        assertEquals(1, svc.list().size());
    }

    @Test
    void messagesRoundTripAndCorruptLogIsReported() {
        ChatSessionData a = svc.create("A");
        svc.saveMessages(a.chatId(), msgs("hi"));
        assertEquals("hi", svc.messages(svc.get(a.chatId())).get(0).get("content").get(0).get("text").asText());

        ChatSessionData bad = a.withMessagesJson("{not json");
        assertThrows(IllegalStateException.class, () -> svc.messages(bad));
        assertEquals(0, svc.messages(a.withMessagesJson("")).size());
        assertEquals(2, svc.messages(a.withMessagesJson("{\"role\":\"user\"}\n{\"role\":\"assistant\"}")).size());
    }

    @Test
    void pinnedPrefixContainsOnlyChatAndAgentId() {
        ChatSessionData a = svc.create("whatever the title is");
        svc.saveMessages(a.chatId(), msgs("content must not leak"));

        assertTrue(a.agentId().startsWith("jllm-"), a.agentId());
        assertEquals("chatId=" + a.chatId() + "; agentId=" + a.agentId(), svc.pinnedPrefix(svc.get(a.chatId())));
    }

    @Test
    void concurrentUsageUpdatesOnDifferentAndSameChatsDoNotLoseCounts() throws Exception {
        ChatSessionData a = svc.create("A");
        ChatSessionData b = svc.create("B");
        int n = 200;
        CountDownLatch done = new CountDownLatch(2 * n);
        try (var ex = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < n; i++) {
                ex.submit(() -> { svc.addUsage(a.chatId(), new Usage(1, 0, 0, 0)); done.countDown(); });
                ex.submit(() -> { svc.addUsage(b.chatId(), new Usage(2, 0, 0, 0)); done.countDown(); });
            }
            done.await();
        }
        assertEquals(n, svc.get(a.chatId()).inputTokens());
        assertEquals(2L * n, svc.get(b.chatId()).inputTokens());
    }
}
