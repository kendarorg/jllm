package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.session.ChatSessionData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** The /compact command tells the chat's event stream that a compaction happened (scripted model, no real LLM). */
class ManualCompactEventTest {

    @TempDir
    Path base;

    private static void turn(AgentHarness h, String chatId, String text) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Runnable off = h.subscribe(chatId, Long.MAX_VALUE, ev -> {
            if (ev.type().equals("run_finished")) done.countDown();
        });
        // the previous run frees its slot just after its run_finished event
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            try {
                h.start(chatId, text);
                break;
            } catch (AgentService.RunAlreadyActiveException e) {
                if (System.nanoTime() > end) throw e;
                Thread.sleep(20);
            }
        }
        assertTrue(done.await(20, TimeUnit.SECONDS));
        off.run();
        Thread.sleep(100);
    }

    @Test
    void manualCompactionEmitsACompactionEvent() throws Exception {
        String summary = String.join(System.lineSeparator(), "## Objective", "- o", "## Work State", "### Completed", "- c", "## Next Move", "- n");
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("a1"), text("a2"), text("a3"), text(summary));
        model.simpleHandler = u -> summary;
        AgentHarness h = AgentHarness.builder().modelClient(model).config(config(base).withKeepRecentTurns(1))
                .withoutStandardTools().build();
        ChatSessionData chat = h.sessions().create(null);
        turn(h, chat.chatId(), "first " + "x".repeat(2000));
        turn(h, chat.chatId(), "second " + "y".repeat(2000));
        turn(h, chat.chatId(), "third");

        List<String> types = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> manual = Collections.synchronizedList(new ArrayList<>());
        h.subscribe(chat.chatId(), 0, ev -> {
            types.add(ev.type());
            if (ev.type().equals("compaction")) manual.add(ev.data().path("manual").asBoolean());
        });
        var r = h.commands().execute(chat.chatId(), "/compact");

        assertTrue(r.output().startsWith("Compacted"), r.output());
        assertTrue(types.contains("compaction"), types.toString());
        assertEquals(List.of(true), manual);
    }
}
