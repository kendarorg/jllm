package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Rig;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.CwdLayout;
import org.kendar.llm.harness.sandbox.LocalSandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxExecutor;
import org.kendar.llm.harness.sandbox.SandboxFactory;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/**
 * The unconfined local runner ({@code --sandbox none} of the CLI): the file tools reach paths outside the work dir and
 * shell command lines are not checked for them. The default local runner keeps its jail.
 */
class UnconfinedSandboxTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path base;
    @TempDir
    Path outside;

    private static String input(String... kv) {
        var n = M.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n.toString();
    }

    private static JsonNode toolResultOf(Rig rig, int requestIdx) {
        ArrayNode m = rig.model.requests.get(requestIdx).messages();
        return m.get(m.size() - 1).get("content").get(0);
    }

    private static Rig rig(Path base, boolean unconfined) {
        Rig rig = new Rig(base);
        if (unconfined) rig.unconfined();
        rig.register(new FileSystemAgentTools().tools().toArray(new HarnessTool[0]));
        return rig;
    }

    @Test
    void theDefaultLocalRunnerRefusesAnAbsolutePathOutsideTheWorkDir() {
        Rig rig = rig(base, false);
        String chatId = rig.newChat();
        Path target = outside.resolve("app").resolve("out.txt");
        rig.model.enqueue(toolUse("w1", "write_file", input("path", target.toString(), "content", "x")), text("done"));

        rig.loop.run(chatId, "write", new Events(), () -> false, null);

        JsonNode tr = toolResultOf(rig, 1);
        assertTrue(tr.get("is_error").asBoolean(), tr.toString());
        assertTrue(tr.get("content").asText().contains("escapes the work dir"), tr.toString());
        assertFalse(Files.exists(target));
    }

    @Test
    void theUnconfinedRunnerWritesAndReadsOutsideTheWorkDir() throws Exception {
        Rig rig = rig(base, true);
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.AUTO);
        Path target = outside.resolve("app").resolve("out.txt");
        Files.createDirectories(outside.resolve("tmp"));
        Files.writeString(outside.resolve("tmp").resolve("in.txt"), "FROM-OUTSIDE");
        Path work = rig.sandbox.workDir(chatId);
        String relative = work.relativize(outside.resolve("tmp").resolve("in.txt")).toString();
        rig.model.enqueue(toolUse("w1", "write_file", input("path", target.toString(), "content", "written")),
                toolUse("r1", "read_file", input("path", relative)), text("done"));

        rig.loop.run(chatId, "write and read", new Events(), () -> false, null);

        JsonNode written = toolResultOf(rig, 1);
        assertFalse(written.path("is_error").asBoolean(false), written.toString());
        assertEquals("written", Files.readString(target));
        JsonNode read = toolResultOf(rig, 2);
        assertFalse(read.path("is_error").asBoolean(false), read.toString());
        assertTrue(read.get("content").asText().contains("FROM-OUTSIDE"), read.toString());
    }

    @Test
    void planModeStillWritesOnlyToTheScratchDir() {
        Rig rig = rig(base, true);
        String chatId = rig.newChat();
        rig.sessions.setMode(chatId, PermissionMode.PLAN);
        Path target = outside.resolve("plan.txt");
        rig.model.enqueue(toolUse("w1", "write_file", input("path", target.toString(), "content", "x")), text("done"));

        rig.loop.run(chatId, "write", new Events(), () -> false, null);

        assertTrue(toolResultOf(rig, 1).get("is_error").asBoolean(), toolResultOf(rig, 1).toString());
        assertFalse(Files.exists(target));
    }

    @Test
    void aShellCommandClimbingOutOfTheWorkDirRunsOnlyWhenUnconfined() {
        for (boolean unconfined : new boolean[]{false, true}) {
            Rig rig = new Rig(base.resolve(unconfined ? "u" : "c"));
            if (unconfined) rig.unconfined();
            AtomicInteger runs = new AtomicInteger();
            rig.register(tool("bash", "Shell", ToolKind.OTHER, (c, in) -> {
                runs.incrementAndGet();
                return "ran";
            }));
            String chatId = rig.newChat();
            rig.model.enqueue(toolUse("b1", "bash", input("command", "cat ../../etc/passwd")), text("done"));

            rig.loop.run(chatId, "run", new Events(), () -> false, null);

            JsonNode tr = toolResultOf(rig, 1);
            if (unconfined) {
                assertEquals(1, runs.get(), tr.toString());
                assertEquals("ran", tr.get("content").asText());
            } else {
                assertEquals(0, runs.get(), tr.toString());
                assertTrue(tr.get("content").asText().contains("climbs out of the work dir"), tr.toString());
            }
        }
    }

    @Test
    void aScriptJailIsAlwaysConfined() {
        ToolContext ctx = new ToolContext("c", base).withUnconfined(true);
        assertTrue(ctx.unconfined());
        assertTrue(ctx.withToolUseId("t").unconfined(), "kept by the other copies");
        assertFalse(ctx.withRoot(base.resolve("scratch")).unconfined(), "a script's file jail stays");
        assertFalse(new ToolContext("c", base).unconfined());
    }

    @Test
    void onlyTheFactoryOptionMakesTheLocalRunnerUnconfined() {
        CwdLayout layout = new CwdLayout(org.kendar.llm.harness.HarnessConfig.defaults(), base);
        assertTrue(new LocalSandboxExecutor(layout).confinesPaths());
        assertFalse(LocalSandboxExecutor.unconfined(layout).confinesPaths());
        HarnessSettings local = HarnessSettings.builder().build();
        SandboxExecutor jailed = new SandboxFactory().create(local, org.kendar.llm.harness.HarnessConfig.defaults(),
                layout, org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind.CLI);
        assertTrue(jailed.confinesPaths());
        SandboxExecutor open = new SandboxFactory().confinePaths(false).create(local,
                org.kendar.llm.harness.HarnessConfig.defaults(), layout,
                org.kendar.llm.harness.sandbox.RemoteExecSandboxExecutor.Kind.CLI);
        assertFalse(open.confinesPaths());
        assertTrue(open.sandboxStatus("c").startsWith("runner: none"), open.sandboxStatus("c"));
    }
}
