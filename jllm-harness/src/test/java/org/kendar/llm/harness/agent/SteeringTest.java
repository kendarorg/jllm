package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class SteeringTest {

    @TempDir
    Path base;

    Rig rig;
    String chat;

    static final class QueueInbox implements AgentLoop.Inbox {
        final Deque<String> steering = new ArrayDeque<>();
        final Deque<String> followUp = new ArrayDeque<>();

        public synchronized String pollSteering() {
            return steering.poll();
        }

        public synchronized String pollFollowUp() {
            return followUp.poll();
        }
    }

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chat = rig.newChat();
    }

    private static JsonNode lastMessage(ArrayNode m) {
        return m.get(m.size() - 1);
    }

    @Test
    void steeringArrivingDuringToolsIsAppliedAfterTheToolRound() {
        QueueInbox inbox = new QueueInbox();
        rig.register(tool("slow", "G", ToolKind.READ_ONLY, (c, in) -> {
            inbox.steering.add("actually, use B");
            return "done";
        }));
        rig.model.enqueue(toolUse("t1", "slow", "{}"), text("ok B"));
        Events ev = new Events();

        rig.loop.run(chat, "do A", ev, () -> false, inbox);

        ArrayNode second = rig.model.requests.get(1).messages();
        JsonNode last = lastMessage(second);
        assertEquals("user", last.get("role").asText());
        assertEquals("actually, use B", last.get("content").get(0).get("text").asText());
        JsonNode before = second.get(second.size() - 2);
        assertEquals("tool_result", before.get("content").get(0).get("type").asText());
        assertEquals(1, ev.ofType("steer_applied").size());
        // persisted only now that it was injected
        assertTrue(rig.sessions.messages(rig.sessions.get(chat)).toString().contains("actually, use B"));
    }

    @Test
    void steeringDuringAFinalAnswerBecomesTheNextTurn() {
        QueueInbox inbox = new QueueInbox();
        rig.model.handler = req -> {
            if (rig.model.requests.size() == 1) {
                inbox.steering.add("one more thing"); // arrives while the first answer is being produced
                return text("first answer");
            }
            return text("second answer");
        };

        rig.loop.run(chat, "hello", new Events(), () -> false, inbox);

        assertEquals(2, rig.model.requests.size());
        assertEquals("one more thing", lastMessage(rig.model.requests.get(1).messages()).get("content").get(0)
                .get("text").asText());
    }

    @Test
    void followUpRunsOnlyAfterTheFinalAnswer() {
        QueueInbox inbox = new QueueInbox();
        inbox.followUp.add("then summarize");
        rig.register(tool("t", "G", ToolKind.READ_ONLY, (c, in) -> "r"));
        rig.model.enqueue(toolUse("t1", "t", "{}"), text("answer"), text("summary"));
        Events ev = new Events();

        rig.loop.run(chat, "go", ev, () -> false, inbox);

        assertEquals(3, rig.model.requests.size());
        // not injected after the tool round, only after the final answer
        assertEquals("tool_result", lastMessage(rig.model.requests.get(1).messages()).get("content").get(0)
                .get("type").asText());
        assertEquals("then summarize", lastMessage(rig.model.requests.get(2).messages()).get("content").get(0)
                .get("text").asText());
        assertEquals("followup", ev.ofType("steer_applied").get(0).get("kind").asText());
    }

    @Test
    void cancelClearsTheQueues() throws Exception {
        CountDownLatch inTool = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        rig.register(tool("block", "G", ToolKind.READ_ONLY, (c, in) -> {
            inTool.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "done";
        }));
        rig.model.enqueue(toolUse("t1", "block", "{}"), text("should not be asked"));
        AgentService service = new AgentService(rig.loop, rig.sessions, rig.approvals, MAPPER, rig.config);
        CountDownLatch finished = new CountDownLatch(1);
        service.subscribe(chat, 0, e -> {
            if (e.type().equals("run_finished")) finished.countDown();
        });

        service.start(chat, "go");
        assertTrue(inTool.await(5, TimeUnit.SECONDS));
        service.steer(chat, "change course");
        service.followUp(chat, "and later");
        service.cancel(chat);
        release.countDown();

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, rig.model.requests.size());
        assertTrue(service.events(chat, 0).stream().noneMatch(e -> e.type().equals("steer_applied")));
        assertTrue(service.events(chat, 0).stream().anyMatch(e -> e.type().equals("steer_queued")));
    }

    @Test
    void steerOnAnIdleChatStartsARun() throws Exception {
        rig.model.enqueue(text("hi"));
        AgentService service = new AgentService(rig.loop, rig.sessions, rig.approvals, MAPPER, rig.config);
        CountDownLatch finished = new CountDownLatch(1);
        service.subscribe(chat, 0, e -> {
            if (e.type().equals("run_finished")) finished.countDown();
        });

        service.steer(chat, "hello");

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(1, rig.model.requests.size());
    }
}
