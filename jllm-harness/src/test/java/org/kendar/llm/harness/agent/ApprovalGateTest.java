package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.mockito.Mockito.when;

/** exec / file-edit approval path: ToolApprovalPolicy -> PermissionGate -> ApprovalService. */
class ApprovalGateTest {

    @TempDir
    Path base;

    Rig rig;
    String chatId;
    AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void setUp() {
        rig = new Rig(base);
        chatId = rig.newChat();
    }

    private void registerBash(boolean flagged) {
        rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> {
            executions.incrementAndGet();
            return "ran " + in.path("x").asText();
        }));
        rig.requiresApproval.put("bash", flagged);
    }

    /** Answers every approval_request as soon as it is emitted. */
    private Events answering(boolean approve, String edited) {
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                assertTrue(rig.approvals.resolve(chatId, data.get("id").asText(), approve, edited));
            }
        };
        return ev;
    }

    private JsonNode toolResultOf(int requestIdx) {
        ArrayNode m = rig.model.requests.get(requestIdx).messages();
        return m.get(m.size() - 1).get("content").get(0);
    }

    @Test
    void approvedCallExecutesAndShowsPreview() {
        registerBash(true);
        rig.model.enqueue(toolUse("t1", "bash", "{\"x\":\"ls\"}"), text("done"));
        Events ev = answering(true, null);

        rig.loop.run(chatId, "run it", ev, () -> false);

        assertEquals(1, executions.get());
        assertEquals(1, ev.ofType("approval_request").size());
        JsonNode req = ev.ofType("approval_request").get(0);
        assertEquals("tool", req.get("type").asText());
        assertEquals("bash", req.get("title").asText());
        assertTrue(req.get("preview").asText().contains("ls"));
        assertEquals("ran ls", toolResultOf(1).get("content").asText());
        assertTrue(rig.approvals.pending(chatId).isEmpty(), "nothing left pending");
    }

    @Test
    void deniedCallIsNotExecutedAndModelIsTold() {
        registerBash(true);
        rig.model.enqueue(toolUse("t1", "bash", "{\"x\":\"rm\"}"), text("ok"));

        rig.loop.run(chatId, "run it", answering(false, null), () -> false);

        assertEquals(0, executions.get());
        JsonNode tr = toolResultOf(1);
        assertTrue(tr.get("is_error").asBoolean());
        assertEquals("The user rejected this tool call", tr.get("content").asText());
    }

    @Test
    void unansweredApprovalTimesOutAsRejected() {
        rig.configure(c -> c.withApprovalTimeoutSeconds(1));
        registerBash(true);
        rig.model.enqueue(toolUse("t1", "bash", "{}"), text("ok"));
        long t0 = System.currentTimeMillis();

        Events ev = new Events();
        rig.loop.run(chatId, "run it", ev, () -> false);

        assertTrue(System.currentTimeMillis() - t0 < 4000);
        assertEquals(0, executions.get());
        assertEquals("The user rejected this tool call", toolResultOf(1).get("content").asText());
        assertTrue(rig.approvals.pending(chatId).isEmpty(), "timed-out approval is cleaned up");
    }

    @Test
    void cancellingWhileWaitingUnblocksAsRejected() {
        registerBash(true);
        rig.model.enqueue(toolUse("t1", "bash", "{}"), text("never"));
        AtomicBoolean stop = new AtomicBoolean();
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) stop.set(true);
        };

        rig.loop.run(chatId, "run it", ev, stop::get);

        assertEquals(0, executions.get());
        assertEquals(1, rig.model.requests.size());
        assertEquals(1, ev.ofType("cancelled").size());
    }

    @Test
    void cancelAllRejectsPendingApprovals() throws Exception {
        registerBash(true);
        rig.model.enqueue(toolUse("t1", "bash", "{}"), text("ok"));
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                Thread.ofVirtual().start(() -> rig.approvals.cancelAll(chatId));
            }
        };

        rig.loop.run(chatId, "run it", ev, () -> false);

        assertEquals(0, executions.get());
    }

    @Test
    void resolvingForTheWrongChatDoesNothing() {
        registerBash(true);
        rig.model.enqueue(toolUse("t1", "bash", "{}"), text("ok"));
        rig.configure(c -> c.withApprovalTimeoutSeconds(1));
        Events ev = new Events();
        ev.also = (type, data) -> {
            if ("approval_request".equals(type)) {
                assertFalse(rig.approvals.resolve("other-chat", data.get("id").asText(), true, null));
                assertFalse(rig.approvals.resolve(chatId, "bogus", true, null));
            }
        };

        rig.loop.run(chatId, "run it", ev, () -> false);

        assertEquals(0, executions.get());
    }

    /** mode, kind, flagged, expect: EXEC (ran, no prompt) | ASK (prompt, ran after approve) | DENY (never ran, no prompt) */
    @ParameterizedTest
    @CsvSource({
            "DEFAULT,OTHER,true,ASK",
            "DEFAULT,OTHER,false,EXEC",
            "DEFAULT,FILE_EDIT,true,ASK",
            "DEFAULT,READ_ONLY,false,EXEC",
            "AUTO,OTHER,true,EXEC",
            "AUTO,FILE_EDIT,true,EXEC",
            "PLAN,OTHER,true,ASK",
            "PLAN,OTHER,false,EXEC",
            "PLAN,FILE_EDIT,false,EXEC",
            "PLAN,READ_ONLY,false,EXEC",
            "EDIT,FILE_EDIT,true,EXEC",
            "EDIT,OTHER,true,ASK",
            "EDIT,OTHER,false,EXEC"
    })
    void permissionModeMatrix(PermissionMode mode, ToolKind kind, boolean flagged, String expect) {
        rig.sessions.setMode(chatId, mode);
        rig.register(tool("t", "Misc", kind, (c, in) -> {
            executions.incrementAndGet();
            return "ok";
        }));
        rig.requiresApproval.put("t", flagged);
        rig.model.enqueue(toolUse("u1", "t", "{}"), text("fin"));
        Events ev = answering(true, null);

        rig.loop.run(chatId, "go", ev, () -> false);

        switch (expect) {
            case "EXEC" -> {
                assertEquals(1, executions.get());
                assertTrue(ev.ofType("approval_request").isEmpty());
            }
            case "ASK" -> {
                assertEquals(1, executions.get());
                assertEquals(1, ev.ofType("approval_request").size());
            }
            default -> {
                assertEquals(0, executions.get());
                assertTrue(ev.ofType("approval_request").isEmpty());
                assertTrue(toolResultOf(1).get("content").asText().contains("Denied by permission policy (mode " + mode));
            }
        }
    }

    @Test
    void fileEditApprovalShowsDiffAndOnlyWritesWhenApproved() throws Exception {
        FileSystemAgentTools fs = new FileSystemAgentTools();
        rig.register(fs.tools().toArray(new org.kendar.llm.harness.tools.HarnessTool[0]));
        rig.requiresApproval.put("write_file", true);
        Path target = rig.sandbox.workDir(chatId).resolve("note.txt");

        // denied
        rig.model.enqueue(toolUse("w1", "write_file", "{\"path\":\"note.txt\",\"content\":\"hello\"}"), text("a"));
        Events denied = answering(false, null);
        rig.loop.run(chatId, "write", denied, () -> false);
        assertFalse(Files.exists(target));
        assertTrue(denied.ofType("approval_request").get(0).get("preview").asText().contains("+ hello"));

        // approved
        rig.model.enqueue(toolUse("w2", "write_file", "{\"path\":\"note.txt\",\"content\":\"hello\"}"), text("b"));
        rig.loop.run(chatId, "write again", answering(true, null), () -> false);
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void jailEscapeInsideAnApprovedToolIsAnErrorNotAWrite() throws Exception {
        rig.register(new FileSystemAgentTools().tools().toArray(new org.kendar.llm.harness.tools.HarnessTool[0]));
        rig.requiresApproval.put("write_file", true);
        rig.model.enqueue(toolUse("w1", "write_file", "{\"path\":\"../../evil.txt\",\"content\":\"x\"}"), text("b"));

        rig.loop.run(chatId, "write", answering(true, null), () -> false);

        JsonNode tr = toolResultOf(1);
        assertTrue(tr.get("is_error").asBoolean());
        assertTrue(tr.get("content").asText().contains("escapes the work dir"));
        assertFalse(Files.exists(base.resolve("evil.txt")));
    }

    @Test
    void subAgentsNeverPromptAndDefaultToDeny() {
        // an ASK decision in a non-interactive loop is converted to DENY; sub-agents only get read-only tools anyway
        registerBash(true);
        rig.model.handler = r -> {
            if (r.pinnedSystem().contains("subagent")) return text("sub");
            return r.messages().size() == 1 ? toolUse("t1", "task", "{\"prompts\":[\"p\"]}") : text("fin");
        };
        Events ev = answering(true, null);

        rig.loop.run(chatId, "go", ev, () -> false);

        assertEquals(0, executions.get());
        assertTrue(ev.ofType("approval_request").isEmpty());
    }

    @Test
    void parseFallsBackToDefault() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.parse("nonsense"));
        assertEquals(PermissionMode.PLAN, PermissionMode.parse(" plan "));
    }
}
