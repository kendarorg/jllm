package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** Event delivery of {@link AgentService}: order, replay, the end of a run and turn starts. */
class AgentServiceEventsTest {

    @TempDir
    Path base;
    Rig rig;
    AgentService service;

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        service = new AgentService(rig.loop, rig.sessions, rig.approvals, MAPPER, rig.config);
    }

    private static AgentEvent ev(long seq) {
        return new AgentEvent(seq, "x", MAPPER.createObjectNode());
    }

    @Test
    void orderedListenerHoldsEarlyEventsAndDropsDuplicates() {
        List<Long> got = new ArrayList<>();
        AgentService.OrderedListener l = new AgentService.OrderedListener(e -> got.add(e.seq()), 1);

        for (long s : new long[]{3, 1, 1, 2, 5, 2, 4}) l.accept(ev(s));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), got);
    }

    private static ModelResponse parallelCalls(int n) {
        ArrayNode c = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            c.addObject().put("type", "tool_use").put("id", "u" + i).put("name", "work").set("input", json("{}"));
        }
        return new ModelResponse(c, "tool_use", new Usage(10, 5, 0, 0));
    }

    private static void assertContiguousFromOne(List<AgentEvent> got) {
        assertFalse(got.isEmpty());
        for (int i = 0; i < got.size(); i++) {
            assertEquals(i + 1, got.get(i).seq(), "gap, duplicate or reorder at " + i + ": " + got.stream()
                    .map(AgentEvent::seq).toList());
        }
        assertEquals("run_finished", got.get(got.size() - 1).type());
    }

    @Test
    void parallelPublishersAndALateSubscriberSeeEverySeqOnceInOrder() throws Exception {
        rig.register(tool("work", "G", ToolKind.READ_ONLY, (c, in) -> {
            Thread.sleep(ThreadLocalRandom.current().nextInt(3));
            return "r";
        }));
        rig.model.handler = r -> r.messages().size() == 1 ? parallelCalls(16) : text("done");
        for (int round = 0; round < 15; round++) {
            String chat = rig.newChat();
            List<AgentEvent> early = Collections.synchronizedList(new ArrayList<>());
            List<AgentEvent> late = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(2);
            service.subscribe(chat, 0, e -> {
                early.add(e);
                if (e.type().equals("run_finished")) done.countDown();
            });
            service.start(chat, "go");
            // joins while the tools publish from several threads: replay, then live, without a seam
            while (service.lastSeq(chat) < 5) Thread.onSpinWait();
            service.subscribe(chat, 0, e -> {
                late.add(e);
                if (e.type().equals("run_finished")) done.countDown();
            });

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertContiguousFromOne(early);
            assertContiguousFromOne(late);
            assertEquals(service.lastSeq(chat), early.size());
        }
    }

    @Test
    void aSeqAheadOfTheServerReplaysTheBuffer() throws Exception {
        String chat = rig.newChat();
        rig.model.enqueue(text("hi"));
        CountDownLatch done = new CountDownLatch(1);
        service.subscribe(chat, 0, e -> {
            if (e.type().equals("run_finished")) done.countDown();
        });
        service.start(chat, "go");
        assertTrue(done.await(5, TimeUnit.SECONDS));

        // a client that saw seq 9999 of a previous server process
        List<AgentEvent> got = Collections.synchronizedList(new ArrayList<>());
        service.subscribe(chat, 9999, got::add);

        assertEquals(service.lastSeq(chat), got.size());
        assertEquals(1, got.get(0).seq());
    }

    @Test
    void theRunIsOverWhenRunFinishedArrives() throws Exception {
        String chat = rig.newChat();
        rig.model.enqueue(text("one"), text("two"));
        AtomicReference<Boolean> runningAtFinish = new AtomicReference<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(2);
        service.subscribe(chat, 0, e -> {
            if (e.type().equals("run_finished")) {
                runningAtFinish.compareAndSet(null, service.isRunning(chat));
                first.countDown();
                second.countDown();
            }
        });

        service.start(chat, "go");
        assertTrue(first.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, runningAtFinish.get());
        service.start(chat, "next"); // no RunAlreadyActiveException

        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertEquals(2, rig.model.requests.size());
    }

    @Test
    void everyAppliedUserMessageStartsATurnAndARefusedStartDoesNot() throws Exception {
        String chat = rig.newChat();
        AtomicInteger turns = new AtomicInteger();
        service.onTurnStart(id -> turns.incrementAndGet());
        CountDownLatch inTool = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.register(tool("block", "G", ToolKind.READ_ONLY, (c, in) -> {
            inTool.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "done";
        }));
        rig.model.enqueue(toolUse("t1", "block", "{}"), text("a"), text("b"));
        CountDownLatch finished = new CountDownLatch(1);
        service.subscribe(chat, 0, e -> {
            if (e.type().equals("run_finished")) finished.countDown();
        });

        service.start(chat, "go");
        assertTrue(inTool.await(5, TimeUnit.SECONDS));
        assertThrows(AgentService.RunAlreadyActiveException.class, () -> service.start(chat, "again"));
        assertEquals(1, turns.get(), "a start refused because the chat is busy opens no turn");
        service.steer(chat, "steered");
        service.followUp(chat, "later");
        release.countDown();

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(3, turns.get(), "the prompt, the steer and the follow-up");
        assertEquals(3, rig.model.requests.size());
    }
}
