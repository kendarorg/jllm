package org.kendar.llm.harness;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.permission.ApprovalService;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.sandbox.CwdSandboxExecutor;
import org.kendar.llm.harness.sandbox.LocalDirSandboxExecutor;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ModesScratchContextTest {

    @TempDir
    Path base;

    // ---- settings

    @Test
    void settingsSectionsAreOptionalAndApplyToConfig() throws Exception {
        Path none = Files.writeString(base.resolve("a.json"), "{}");
        HarnessConfig defaults = HarnessConfig.defaults();
        assertEquals(defaults, HarnessSettings.load(none, k -> null).applyTo(defaults));

        Path f = Files.writeString(base.resolve("b.json"), """
                {"sandbox":{"tempDir":"${T}","timeoutSeconds":7},
                 "context":{"maxTokens":50000,"keepRecentTurns":3,"auto":false,"keepTokens":900,"buffer":2000}}""");
        HarnessConfig c = HarnessSettings.load(f, Map.of("T", "/x/tmp")::get).applyTo(defaults);
        assertEquals("/x/tmp", c.sandbox().baseDir());
        assertEquals(7, c.sandbox().timeoutSeconds());
        assertEquals(50_000, c.contextBudgetTokens());
        assertEquals(3, c.keepRecentTurns());
        assertEquals(new HarnessConfig.Compaction(false, 900, 2000), c.compaction());
    }

    @Test
    void dockerSandboxSettingsApplyToConfig() throws Exception {
        Path f = Files.writeString(base.resolve("d.json"), """
                {"sandbox":{"backend":"docker","localFallback":false,
                            "docker":{"image":"maven:3-eclipse-temurin-21","memory":"2g","network":"bridge"}}}""");
        HarnessSettings s = HarnessSettings.load(f, k -> null);
        assertTrue(s.dockerSandbox());
        HarnessConfig c = s.applyTo(HarnessConfig.defaults());
        assertEquals("maven:3-eclipse-temurin-21", c.sandbox().docker().image());
        assertEquals("2g", c.sandbox().docker().memory());
        assertEquals("bridge", c.sandbox().docker().network());
        assertEquals("1.0", c.sandbox().docker().cpus());
        assertFalse(c.sandbox().localDirSandboxAllowed());

        HarnessSettings flag = HarnessSettings.empty().withSandboxBackend("docker").withDockerImage("alpine:3");
        assertTrue(flag.dockerSandbox());
        assertEquals("alpine:3", flag.applyTo(HarnessConfig.defaults()).sandbox().docker().image());
        assertThrows(IllegalArgumentException.class, () -> HarnessSettings.empty().withSandboxBackend("vm"));
    }

    @Test
    void commandLineMaxTokensWinsOverSettings() {
        HarnessSettings s = HarnessSettings.empty().withMaxTokens(1234);
        assertEquals(1234, s.applyTo(HarnessConfig.defaults()).contextBudgetTokens());
    }

    // ---- scratch

    @Test
    void scratchIsPerChatAndAgentAndJailed() {
        LocalDirSandboxExecutor box = new LocalDirSandboxExecutor(config(base));
        Path a = box.scratchDir("chat1", "main");
        Path b = box.scratchDir("chat1", "sub-1");
        assertEquals(box.location().resolve("chat1").resolve("main"), a);
        assertNotEquals(a, b);
        assertTrue(Files.isDirectory(a));
        assertThrows(IllegalArgumentException.class, () -> box.scratchDir("chat1", ".."));
        assertThrows(IllegalArgumentException.class, () -> box.scratchDir("chat1", "../x"));
    }

    @Test
    void planModeWritesOnlyToScratch() throws Exception {
        Path cwd = Files.createDirectories(base.resolve("project"));
        CwdSandboxExecutor box = new CwdSandboxExecutor(config(base.resolve("tmp")), cwd);
        Path scratch = box.scratchDir("c1", "main");
        ToolContext plan = new ToolContext("c1", box.workDir("c1"), scratch, true, null);

        assertEquals(cwd.toAbsolutePath().normalize(), box.workDir("c1"));
        assertEquals(scratch.resolve("n.txt"), FileSystemAgentTools.jailWrite(plan, "n.txt"));
        assertEquals(scratch.resolve("n.txt"), FileSystemAgentTools.jailWrite(plan, scratch.resolve("n.txt").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> FileSystemAgentTools.jailWrite(plan, cwd.resolve("n.txt").toString()));
        assertThrows(IllegalArgumentException.class, () -> FileSystemAgentTools.jailWrite(plan, "../p.txt"));
        // reading cwd and scratch is allowed
        assertEquals(cwd.resolve("r.txt").toAbsolutePath().normalize(), FileSystemAgentTools.jail(plan, "r.txt"));
        assertEquals(scratch.resolve("n.txt"), FileSystemAgentTools.jail(plan, scratch.resolve("n.txt").toString()));

        ToolContext edit = new ToolContext("c1", box.workDir("c1"), scratch, false, null);
        assertEquals(cwd.resolve("n.txt").toAbsolutePath().normalize(), FileSystemAgentTools.jailWrite(edit, "n.txt"));
    }

    @Test
    void deletingAChatNeverTouchesTheProjectDirectory() throws Exception {
        Path cwd = Files.createDirectories(base.resolve("project"));
        Files.writeString(cwd.resolve("keep.txt"), "x");
        CwdSandboxExecutor box = new CwdSandboxExecutor(config(base.resolve("tmp")), cwd);
        Path scratch = box.scratchDir("c1", "main");
        box.deleteWorkDir("c1");
        assertFalse(Files.exists(scratch));
        assertTrue(Files.exists(cwd.resolve("keep.txt")));
    }

    // ---- modes

    @Test
    void modeParsingKeepsBypassAsAlias() {
        assertEquals(PermissionMode.AUTO, PermissionMode.parse("bypass"));
        assertEquals(PermissionMode.AUTO, PermissionMode.parse("AUTO"));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.parse("nonsense"));
    }

    @Test
    void modeMenuListsPlanEditAutoAndSelectsByNumber() {
        AgentHarness h = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools().build();
        String chat = h.sessions().create(null).chatId();
        var c = h.commands();

        String menu = c.execute(chat, "/mode").output();
        assertTrue(c.menuActive(chat));
        assertTrue(menu.indexOf("plan") < menu.indexOf("edit") && menu.indexOf("edit") < menu.indexOf("auto"), menu);
        assertFalse(menu.contains("default"), menu);

        c.menuInput(chat, "2");
        assertEquals("EDIT", h.sessions().get(chat).mode());
        assertFalse(c.menuActive(chat));

        c.execute(chat, "/mode bypass");
        assertEquals("AUTO", h.sessions().get(chat).mode());
        c.execute(chat, "/mode plan");
        assertEquals("PLAN", h.sessions().get(chat).mode());
    }

    // ---- compaction

    @Test
    void ceilingHonorsBufferAndFloor() {
        var def = HarnessConfig.Compaction.defaults();
        assertEquals(100_000 - 16_000, def.ceiling(100_000));
        assertEquals(1_000_000 - 150_000, def.ceiling(1_000_000));
        assertEquals(20_000 - 3_000, def.ceiling(20_000), "no 16k floor below a 32k window");
        assertEquals(100_000 - 5_000, new HarnessConfig.Compaction(true, 1, 5_000).ceiling(100_000));
        assertEquals(100_000 - 16_000, new HarnessConfig.Compaction(true, 1, 100_000).ceiling(100_000),
                "a buffer not below the window is ignored");
    }

    private static ArrayNode conversation(int turns, int chars) {
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= turns; i++) {
            m.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                    .put("text", "q" + i + " " + "p".repeat(chars));
            m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text")
                    .put("text", "a" + i);
        }
        return m;
    }

    @Test
    void autoOffNeverCompactsAutomaticallyButManualWorks() {
        HarnessConfig c = config(base).withKeepRecentTurns(1).withContextBudgetTokens(500)
                .withCompaction(new HarnessConfig.Compaction(false, 15_000, null));
        ScriptedModel model = new ScriptedModel();
        model.simpleReplies.add("S");
        ContextManager cm = new ContextManager(c, model, new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(4, 2000);

        assertFalse(cm.compactIfNeeded("c", m, null, new Events(), () -> false));
        assertTrue(model.simpleUserPrompts.isEmpty());
        assertTrue(cm.compactNow(m, null, u -> { }).changed());
    }

    @Test
    void secondCompactionSendsTheUpdatePromptAndFocus() {
        HarnessConfig c = config(base).withKeepRecentTurns(1);
        ScriptedModel model = new ScriptedModel();
        model.simpleReplies.add("FIRST");
        model.simpleReplies.add("SECOND");
        ContextManager cm = new ContextManager(c, model, new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(3, 50);

        cm.compactNow(m, null, u -> { });
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "q9");
        m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", "a9");
        cm.compactNow(m, "the database", u -> { });

        String first = model.simpleUserPrompts.get(0);
        String second = model.simpleUserPrompts.get(1);
        assertTrue(first.contains("## Objective") && first.contains("Summarize the conversation"), first);
        assertTrue(second.contains("merges both") && second.contains("FIRST") && second.contains("the database"), second);
    }

    @Test
    void oversizedInputIsShortenedStepwiseUntilItFits() {
        HarnessConfig c = config(base).withKeepRecentTurns(1);
        ScriptedModel model = new ScriptedModel();
        model.simpleHandler = user -> {
            if (user.length() > 2000) throw new ModelException("prompt is too long");
            return "OK";
        };
        ContextManager cm = new ContextManager(c, model, new ApprovalService(5), MAPPER);
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= 3; i++) {
            m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "q" + i);
            var use = m.addObject();
            use.put("role", "assistant");
            use.putArray("content").addObject().put("type", "tool_use").put("id", "t" + i).put("name", "grep")
                    .putObject("input").put("pattern", "x".repeat(300));
            m.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                    .put("tool_use_id", "t" + i).put("content", "r".repeat(3000));
        }
        m.addObject().put("role", "user").putArray("content").addObject().put("type", "text").put("text", "last");

        assertTrue(cm.compactNow(m, null, u -> { }).changed());
        assertTrue(model.simpleUserPrompts.size() > 1, "shortened and retried");
        assertTrue(m.get(0).toString().contains("OK"));
    }

    @Test
    void tokenCapKeepsOnlyTheNewestTurnsThatFit() {
        HarnessConfig c = config(base).withKeepRecentTurns(1);
        ContextManager cm = new ContextManager(c, new ScriptedModel(), new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(4, 400); // ~110 tokens per turn
        assertEquals(6, cm.tokenCutIndex(m, 250), "the last two turns fit");
        assertEquals(0, cm.tokenCutIndex(m, 100_000), "everything fits");
    }

    @Test
    void contextOverflowDetection() {
        assertTrue(ModelException.isContextOverflow(new ModelException("HTTP 400: prompt is too long: 210000 tokens")));
        assertTrue(ModelException.isContextOverflow(new ModelException("maximum context length is 8192")));
        assertFalse(ModelException.isContextOverflow(new ModelException("rate limited")));
    }
}
