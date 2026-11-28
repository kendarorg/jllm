package org.kendar.llm.harness.instructions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.auth.Identity;
import org.kendar.llm.harness.auth.UserPolicy;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.harness.HarnessTestSupport.*;

/** The per-user policy decides whether the instruction files of the work dir join the chat, and adds role instructions. */
class ScopedPolicyTest {

    @TempDir
    Path base;

    private Rig rig(Supplier<UserPolicy> policy) {
        Rig rig = new Rig(base, new InMemoryChatSessionStore());
        List<HarnessTool> files = new FileSystemAgentTools().tools();
        rig.register(files.toArray(HarnessTool[]::new));
        rig.scopedInstructions(files, new ScopedInstructions(HarnessSettings.DEFAULT_INSTRUCTION_FILES,
                ScopedInstructions.DEFAULT_MAX_DEPTH, List.of()));
        rig.identity(new Identity(null, p -> policy.get(), null, c -> null));
        return rig;
    }

    private static UserPolicy policy(UserPolicy.Scoped scoped, Supplier<String> instructions) {
        return new UserPolicy(null, null, null, null, null, instructions, scoped, null, false, false);
    }

    /** Runs a chat that reads a file below a directory holding an AGENTS.md; the texts of the second request's last message. */
    private List<String> run(Rig rig) throws Exception {
        String chat = rig.newChat();
        Path work = rig.sandbox.workDir(chat);
        Files.createDirectories(work.resolve("sub"));
        Files.writeString(work.resolve("sub/AGENTS.md"), "Sub rules.\n");
        Files.writeString(work.resolve("sub/a.txt"), "A");
        rig.model.enqueue(toolUse("t1", "read_file", "{'path':'sub/a.txt'}"), text("done"));
        rig.loop.run(chat, "read it", new Events(), () -> false, null);
        var second = rig.model.requests.get(1).messages();
        var last = second.get(second.size() - 1).path("content");
        return last.findValuesAsText("text");
    }

    @Test
    void offByDefaultForARoleWithoutTheSetting() throws Exception {
        List<String> texts = run(rig(() -> policy(UserPolicy.Scoped.OFF, null)));
        assertFalse(texts.stream().anyMatch(t -> t.startsWith("Scoped instructions from")), texts.toString());
    }

    @Test
    void enabledForARoleItIsLabelledAsUserContent() throws Exception {
        List<String> texts = run(rig(() -> policy(UserPolicy.Scoped.USER_CONTENT, null)));
        String block = texts.stream().filter(t -> t.startsWith("Scoped instructions from")).findFirst().orElseThrow();
        assertTrue(block.contains(ScopedInstructions.USER_NOTE), block);
        assertTrue(block.endsWith("Sub rules."), block);
    }

    @Test
    void theCommandLinePolicyIsNotLabelled() throws Exception {
        List<String> texts = run(rig(() -> UserPolicy.ALL));
        String block = texts.stream().filter(t -> t.startsWith("Scoped instructions from")).findFirst().orElseThrow();
        assertFalse(block.contains(ScopedInstructions.USER_NOTE), block);
    }

    @Test
    void aLabelledBlockIsRecognisedAgainInTheLog() throws Exception {
        Rig rig = rig(() -> policy(UserPolicy.Scoped.USER_CONTENT, null));
        run(rig);
        var blocks = ScopedInstructions.inLog(rig.model.requests.get(1).messages());
        assertEquals(1, blocks.size());
        assertEquals("sub/AGENTS.md", blocks.get(0).file());
        assertEquals("Sub rules.", blocks.get(0).text());
    }

    @Test
    void theInstructionsOfTheRoleAndUserJoinTheSystemText() throws Exception {
        Rig rig = rig(() -> policy(UserPolicy.Scoped.OFF, () -> "Instructions for role dev (style):\nBe brief."));
        run(rig);
        assertTrue(rig.model.requests.get(0).extra().contains("Be brief."), rig.model.requests.get(0).extra());
    }
}
