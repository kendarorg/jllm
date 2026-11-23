package org.kendar.llm.harness.instructions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.AgentHarnessBuilder;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.InMemoryChatSessionStore;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.settings.HarnessSettingsLoader;
import org.kendar.llm.harness.tools.FileSystemAgentTools;
import org.kendar.llm.harness.tools.HarnessTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ScopedInstructionsTest {

    private static final List<String> NAMES = HarnessSettings.DEFAULT_INSTRUCTION_FILES;

    @TempDir
    Path base;

    private static Path write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static Rig rig(Path base, ChatSessionStore store, ScopedInstructions scoped) {
        Rig rig = new Rig(base, store);
        List<HarnessTool> files = new FileSystemAgentTools().tools();
        rig.register(files.toArray(HarnessTool[]::new));
        rig.scopedInstructions(files, scoped);
        return rig;
    }

    private static ScopedInstructions scoped() {
        return new ScopedInstructions(NAMES, ScopedInstructions.DEFAULT_MAX_DEPTH, List.of());
    }

    /** The scoped blocks in the messages, by file, in order. */
    private static List<String> blockFiles(JsonNode messages) {
        return ScopedInstructions.inLog(messages).stream().map(ScopedInstructions.Block::file).toList();
    }

    private static void assertExtends(Recorded before, Recorded after) {
        assertEquals(before.pinned(), after.pinned());
        assertEquals(before.extra(), after.extra(), "the system prompt does not change");
        assertTrue(after.messages().size() >= before.messages().size());
        for (int i = 0; i < before.messages().size(); i++) {
            assertEquals(before.messages().get(i), after.messages().get(i), "message " + i + " is unchanged");
        }
    }

    @Test
    void aNestedFileIsLoadedOnFirstAccessOnceAndTheCachedPrefixIsKept() throws Exception {
        ChatSessionStore store = new InMemoryChatSessionStore();
        Rig rig = rig(base, store, scoped());
        String chat = rig.newChat();
        Path work = rig.sandbox.workDir(chat);
        write(work.resolve("sub/AGENTS.md"), "Sub rules.\n");
        write(work.resolve("sub/a.txt"), "A");
        write(work.resolve("sub/b.txt"), "B");
        rig.model.enqueue(toolUse("t1", "read_file", "{'path':'sub/a.txt'}"),
                toolUse("t2", "read_file", "{'path':'sub/b.txt'}"), text("done"));
        Events ev = new Events();

        rig.loop.run(chat, "read them", ev, () -> false, null);

        List<Recorded> req = rig.model.requests;
        assertEquals(3, req.size());
        assertEquals(List.of(), blockFiles(req.get(0).messages()));
        assertEquals(List.of("sub/AGENTS.md"), blockFiles(req.get(1).messages()), "added at the next turn");
        assertEquals(List.of("sub/AGENTS.md"), blockFiles(req.get(2).messages()), "not added again");
        JsonNode results = req.get(1).messages().get(req.get(1).messages().size() - 1);
        assertEquals("tool_result", results.path("content").get(0).path("type").asText());
        assertEquals("Scoped instructions from sub/AGENTS.md, for the work under sub/:\nSub rules.",
                results.path("content").get(1).path("text").asText());
        assertExtends(req.get(0), req.get(1));
        assertExtends(req.get(1), req.get(2));
        assertEquals(1, ev.ofType("instructions_loaded").size());
        assertEquals("sub/", ev.ofType("instructions_loaded").get(0).path("dir").asText());

        // a resumed chat (another loop over the same store) does not load it again
        Rig resumed = rig(base, store, scoped());
        resumed.model.enqueue(toolUse("t3", "read_file", "{'path':'sub/a.txt'}"), text("again"));
        resumed.loop.run(chat, "read again", new Events(), () -> false, null);

        assertEquals(List.of("sub/AGENTS.md"), blockFiles(resumed.model.requests.get(1).messages()));
        assertEquals(List.of("sub/AGENTS.md"), blockFiles(resumed.sessions.messages(resumed.sessions.get(chat))));
    }

    @Test
    void theOrderFollowsTheDirectoryDepth() throws Exception {
        Rig rig = rig(base, new InMemoryChatSessionStore(), scoped());
        String chat = rig.newChat();
        Path work = rig.sandbox.workDir(chat);
        write(work.resolve("a/b/CLAUDE.md"), "B claude.");
        write(work.resolve("a/b/AGENTS.md"), "B agents.");
        write(work.resolve("a/AGENTS.md"), "A agents.");
        write(work.resolve("KENDAR.md"), "Root rules.");
        write(work.resolve("a/b/c/x.txt"), "x");
        write(work.resolve("other/AGENTS.md"), "Not on the way.");
        rig.model.enqueue(toolUse("t1", "edit_file", "{'path':'a/b/c/x.txt','old_string':'x','new_string':'y'}"),
                text("done"));

        rig.loop.run(chat, "edit it", new Events(), () -> false, null);

        assertEquals("y", Files.readString(work.resolve("a/b/c/x.txt")));
        assertEquals(List.of("KENDAR.md", "a/AGENTS.md", "a/b/AGENTS.md", "a/b/CLAUDE.md"),
                blockFiles(rig.model.requests.get(1).messages()));
    }

    @Test
    void nothingIsLoadedOutsideTheRoot() throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        Path outside = write(base.resolve("outside/AGENTS.md"), "Outside rules.").getParent();
        write(base.resolve("AGENTS.md"), "Parent rules.");
        ArrayNode none = MAPPER.createArrayNode();

        assertEquals(List.of(), scoped().discover(root, List.of("../outside/x.txt", outside.resolve("x.txt").toString(),
                outside.toString(), ".."), none));
    }

    @Test
    void aLinkToAFileOutsideTheRootIsNotLoaded() throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        Path secret = write(base.resolve("secret.md"), "Secret.");
        Files.createDirectories(root.resolve("sub"));
        try {
            Files.createSymbolicLink(root.resolve("sub/AGENTS.md"), secret);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.abort("symlinks cannot be created here: " + e);
        }

        assertEquals(List.of(), scoped().discover(root, List.of("sub/x.txt"), MAPPER.createArrayNode()));
    }

    @Test
    void theDepthIsCapped() throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        write(root.resolve("a/AGENTS.md"), "Level one.");
        write(root.resolve("a/b/AGENTS.md"), "Level two.");
        ScopedInstructions one = new ScopedInstructions(NAMES, 1, List.of());

        List<ScopedInstructions.Block> found = one.discover(root, List.of("a/b/x.txt"), MAPPER.createArrayNode());

        assertEquals(List.of("a/AGENTS.md"), found.stream().map(ScopedInstructions.Block::file).toList());
    }

    @Test
    void filesInTheSystemPromptOrWithTheSameContentAreSkipped() throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        Path rootAgents = write(root.resolve("AGENTS.md"), "Root rules.");
        write(root.resolve("a/AGENTS.md"), "Root rules.");
        write(root.resolve("a/CLAUDE.md"), "Same twice.");
        write(root.resolve("a/b/AGENTS.md"), "Same twice.");
        ScopedInstructions s = new ScopedInstructions(NAMES, 8, InstructionLoader.read(List.of(rootAgents.toString())));

        List<ScopedInstructions.Block> found = s.discover(root, List.of("a/b/x.txt"), MAPPER.createArrayNode());

        assertEquals(List.of("a/CLAUDE.md"), found.stream().map(ScopedInstructions.Block::file).toList());
    }

    @Test
    void namesComeFromTheConfiguredFilesAndGlobUsesItsLiteralDirectory() {
        ScopedInstructions s = new ScopedInstructions(List.of("~/KENDAR.md", "docs/RULES.md", "KENDAR.md"), 8,
                List.of());

        assertEquals(List.of("KENDAR.md", "RULES.md"), s.names());
        assertEquals("src/main", ScopedInstructions.pathOf("glob", json("{'pattern':'src/main/**/*.java'}")));
        assertEquals("", ScopedInstructions.pathOf("glob", json("{'pattern':'**/*.java'}")));
        assertEquals("", ScopedInstructions.pathOf("grep", json("{'pattern':'x'}")));
        assertEquals("a/b.txt", ScopedInstructions.pathOf("read_file", json("{'path':'a/b.txt'}")));
    }

    @Test
    void theSettingTurnsTheFeatureOff() throws Exception {
        assertTrue(HarnessSettings.empty().scopedInstructions());
        assertEquals(ScopedInstructions.DEFAULT_MAX_DEPTH, HarnessSettings.empty().scopedInstructionsDepth());
        Path off = Files.writeString(base.resolve("off.json"),
                "{\"files\":{\"scopedInstructions\":false,\"scopedInstructionsDepth\":3}}");
        HarnessSettings settings = HarnessSettingsLoader.load(off);
        assertFalse(settings.scopedInstructions());
        assertEquals(3, settings.scopedInstructionsDepth());

        assertEquals(List.of(), runHarness(settings, "off"), "nothing is loaded when the setting is off");
        assertEquals(List.of("sub/AGENTS.md"), runHarness(HarnessSettings.empty(), "on"));
    }

    /** One read_file under {@code sub/} through a built harness: the scoped blocks of the second request. */
    private List<String> runHarness(HarnessSettings settings, String dir) throws Exception {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(toolUse("t1", "read_file", "{'path':'sub/a.txt'}"), text("done"));
        AgentHarness h = new AgentHarnessBuilder().modelClient(model).config(config(base.resolve(dir)))
                .settings(settings).build();
        String chat = h.sessions().create(null).chatId();
        Path work = h.sandbox().workDir(chat);
        write(work.resolve("sub/AGENTS.md"), "Sub rules.");
        write(work.resolve("sub/a.txt"), "A");
        CountDownLatch finished = new CountDownLatch(1);
        h.subscribe(chat, 0, (AgentEvent e) -> {
            if (e.type().equals("run_finished")) finished.countDown();
        });

        h.start(chat, "read it");

        assertTrue(finished.await(20, TimeUnit.SECONDS));
        assertEquals(2, model.requests.size());
        return new ArrayList<>(blockFiles(model.requests.get(1).messages()));
    }
}
