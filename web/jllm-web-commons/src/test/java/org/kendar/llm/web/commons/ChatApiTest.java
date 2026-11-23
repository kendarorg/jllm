package org.kendar.llm.web.commons;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiTest {

    private static final Fixtures.User ALICE = new Fixtures.User("alice", false);
    private static final Fixtures.User BOB = new Fixtures.User("bob", false);
    private static final Fixtures.User ADMIN = new Fixtures.User("admin", true);

    @TempDir
    Path dir;
    private AgentHarness harness;
    private ChatApi chat;

    @BeforeEach
    void setUp() {
        harness = Fixtures.harness(dir);
        chat = Fixtures.chatApi(harness);
    }

    private String create(Fixtures.User user) {
        return (String) chat.create(Map.of(), user).get("chatId");
    }

    private void runOnce(String id) throws Exception {
        chat.send(id, Map.of("text", "hello"), ALICE);
        for (int i = 0; i < 200 && harness.isRunning(id); i++) Thread.sleep(25);
        assertFalse(harness.isRunning(id));
    }

    @Test
    void listShowsOnlyTheChatsOfTheUserAndEverythingToTheAdmin() {
        String a = create(ALICE);
        String b = create(BOB);
        assertEquals(List.of(a), chat.list(ALICE).stream().map(m -> m.get("chatId")).toList());
        assertEquals(2, chat.list(ADMIN).size());
        assertEquals(List.of(), chat.list(null));
        assertNotNull(b);
    }

    @Test
    void unknownChatIs404() {
        ApiException e = assertThrows(ApiException.class, () -> chat.get("nope"));
        assertEquals(404, e.status());
        assertEquals(404, assertThrows(ApiException.class, () -> chat.delete("nope")).status());
    }

    @Test
    void sendRequiresTextAndAnswers202() throws Exception {
        String id = create(ALICE);
        assertEquals(400, assertThrows(ApiException.class, () -> chat.send(id, Map.of(), ALICE)).status());
        Reply<Map<String, Object>> r = chat.send(id, Map.of("text", "hello"), ALICE);
        assertEquals(202, r.status());
        assertEquals(Map.of("started", true), r.body());
    }

    @Test
    void aSlashCommandAnswers200WithItsOutput() {
        String id = create(ALICE);
        Reply<Map<String, Object>> r = chat.send(id, Map.of("text", "/help"), ALICE);
        assertEquals(200, r.status());
        assertEquals(true, r.body().get("command"));
    }

    @Test
    void eventsReplayExactlyWhatFollowsAfter() throws Exception {
        String id = create(ALICE);
        runOnce(id);
        long last = harness.lastSeq(id);
        assertTrue(last > 2, "the run published events");

        Fixtures.RecordingSink all = new Fixtures.RecordingSink();
        chat.events(id, 0, all);
        assertEquals(last, all.sent.stream().filter(s -> s.id() != null).count());

        long after = last - 2;
        Fixtures.RecordingSink tail = new Fixtures.RecordingSink();
        chat.events(id, after, tail);
        assertEquals(List.of(String.valueOf(last - 1), String.valueOf(last)),
                tail.sent.stream().map(Fixtures.RecordingSink.Sent::id).toList());
    }

    @Test
    void aClosedClientFreesTheSubscription() throws Exception {
        String id = create(ALICE);
        Fixtures.RecordingSink sink = new Fixtures.RecordingSink();
        Runnable beat = chat.events(id, 0, sink);

        beat.run();
        assertEquals(List.of("ping"), sink.comments);

        sink.clientGone = true;
        beat.run(); // the write fails: the stream is ended and the subscription dropped
        assertNotNull(sink.failure);

        sink.clientGone = false;
        sink.comments.clear();
        beat.run(); // nothing more is written to a closed stream
        assertEquals(List.of(), sink.comments);
        runOnce(id);
        assertEquals(List.of(), sink.sent, "no event reaches a closed stream");
    }

    @Test
    void completionAndTimeoutFreeTheSubscription() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            String id = create(ALICE);
            Fixtures.RecordingSink sink = new Fixtures.RecordingSink();
            chat.events(id, 0, sink);
            if (timeout) sink.timeoutHooks.forEach(Runnable::run);
            else sink.complete();
            runOnce(id);
            assertEquals(List.of(), sink.sent, "no event after " + (timeout ? "timeout" : "completion"));
        }
    }

    @Test
    void resolvingAnUnknownApprovalIs404() {
        String id = create(ALICE);
        assertEquals(404, assertThrows(ApiException.class,
                () -> chat.resolve(id, "nope", Map.of("approved", true))).status());
    }

    @Test
    void suggestionsSkipTheHarnessDirectory() throws Exception {
        Files.createDirectories(dir.resolve("w/.jllm/history"));
        Files.writeString(dir.resolve("w/.jllm/history/secret.txt"), "x");
        Files.createDirectories(dir.resolve("w/src"));
        Files.writeString(dir.resolve("w/src/main.txt"), "x");
        assertEquals(List.of("src/main.txt"), ChatApi.suggestions(dir.resolve("w"), ""));
        assertEquals(List.of(), ChatApi.suggestions(dir.resolve("missing"), ""));
    }
}
