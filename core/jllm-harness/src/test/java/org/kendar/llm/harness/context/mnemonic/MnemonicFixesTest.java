package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.HarnessTestSupport.Events;
import org.kendar.llm.harness.HarnessTestSupport.Recorded;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.agent.AgentRun;
import org.kendar.llm.harness.agent.EventSink;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.ContextTurn;
import org.kendar.llm.harness.context.noise.NoiseCommandPruner;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.*;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.config;

/** Handoff request that fits, kept instruction, sub-agent task, inherited recall ids, unreadable state. */
class MnemonicFixesTest {

    private final InMemoryMnemonicStore store = new InMemoryMnemonicStore();
    private final MnemonicStrategy strategy = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), store,
            SubAgentRunLog.noop());

    private static ScriptedModel notesModel() {
        ScriptedModel model = new ScriptedModel();
        model.handler = r -> text("## Objective\n- NOTES");
        return model;
    }

    /** An assistant step with a large text (never masked) and a small tool call, then its small result. */
    private static void step(ArrayNode log, int i) {
        ObjectNode a = log.addObject().put("role", "assistant");
        ArrayNode c = a.putArray("content");
        c.addObject().put("type", "text").put("text", "step " + i + " " + fileText(i));
        c.addObject().put("type", "tool_use").put("id", "s" + i).put("name", "grep").putObject("input").put("q", "x");
        log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", "s" + i).put("content", "ok");
    }

    private static String headText(ArrayNode view) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : view.get(0).path("content")) sb.append(b.path("text").asText()).append('\n');
        return sb.toString();
    }

    // ---- 6. the instruction in progress stays; the notes request fits ---------------------------------------------

    @Test
    void aCutInsideTheCurrentTurnKeepsTheInstructionInTheHead() {
        ScriptedModel model = notesModel();
        ArrayNode log = log();
        user(log, "THE-FIRST-REQUEST");
        assistant(log, "done");
        user(log, "CURRENT-INSTRUCTION");
        for (int i = 0; i < 20; i++) step(log, i);

        ArrayNode view = strategy.prepare(turn(log, prefix(model))).messages();

        MnemonicState st = strategy.state(CHAT, AGENT);
        assertEquals(1, st.segments.size());
        MnemonicState.Segment seg = st.segments.get(0);
        assertTrue(seg.cut() > 2, "the cut falls inside the current turn: " + seg.cut());
        assertEquals(0, seg.firstUser());
        assertEquals(Integer.valueOf(2), seg.instruction());
        String head = headText(view);
        assertTrue(head.contains("THE-FIRST-REQUEST"));
        assertTrue(head.contains(Renderer.INSTRUCTION_NOTE + "\nCURRENT-INSTRUCTION"), head);
        assertTrue(head.indexOf("CURRENT-INSTRUCTION") < head.indexOf("NOTES"));

        // sticky: the same log renders the same bytes, the segment survives validation
        ArrayNode again = strategy.prepare(turn(log, prefix(model))).messages();
        assertEquals(view.toString(), again.toString());
        assertEquals(1, st.segments.size());
    }

    @Test
    void theHandoffRequestIsTrimmedToFitTheWindow() {
        ScriptedModel model = notesModel();
        ArrayNode log = log();
        user(log, "THE-FIRST-REQUEST");
        for (int i = 0; i < 12; i++) {
            assistant(log, "reply " + i + " " + fileText(i) + fileText(i + 50));
            user(log, "question " + i + " " + fileText(i + 100));
        }
        assertTrue(Messages.tokens(log) > 20_000 - 300, "the whole view would not fit with the notes");

        strategy.prepare(turn(log, prefix(model)));

        Recorded notes = model.requests.get(0);
        assertTrue(Messages.tokens(notes.messages()) <= 20_000 - 300, "the notes request fits the window");
        assertTrue(notes.messages().size() < log.size());
        assertTrue(notes.messages().get(0).toString().contains("THE-FIRST-REQUEST"), "first request kept");
        assertTrue(notes.messages().toString().contains(SegmentSwitcher.OMITTED));
        assertEquals("user", notes.messages().get(0).path("role").asText());
        for (int i = 1; i < notes.messages().size(); i++) {
            assertNotEquals(notes.messages().get(i - 1).path("role").asText(),
                    notes.messages().get(i).path("role").asText(), "roles alternate at " + i);
        }
    }

    @Test
    void fitKeepsAToolCallWithItsResults() {
        SegmentSwitcher sw = new SegmentSwitcher(MAPPER);
        ArrayNode view = log();
        user(view, "first");
        for (int i = 0; i < 6; i++) readPair(view, i);
        ArrayNode out = sw.fit(view, 2_500);
        assertTrue(out.size() < view.size());
        assertEquals("first", out.get(0).path("content").path(0).path("text").asText());
        // the first kept message after the head is an assistant tool call, whose result follows
        assertEquals("assistant", out.get(1).path("role").asText());
        assertEquals(view.toString(), sw.fit(view, 1_000_000).toString(), "a view that fits is sent unchanged");
    }

    @Test
    void anOverflowSwitchTrimsTheNotesRequestEvenWhenTheEstimateFits() {
        ScriptedModel model = notesModel();
        ArrayNode log = log();
        user(log, "THE-FIRST-REQUEST");
        for (int i = 0; i < 6; i++) {
            assistant(log, "reply " + i + " " + fileText(i));
            user(log, "question " + i + " " + fileText(i + 100));
        }

        assertNotNull(strategy.onOverflow(turn(log, prefix(model))));

        Recorded notes = model.requests.get(0);
        assertTrue(Messages.tokens(notes.messages()) < Messages.tokens(log), "shorter than the refused request");
    }

    // ---- 7. a sub-agent keeps its own task ------------------------------------------------------------------------

    @Test
    void aSubAgentSegmentKeepsItsOwnTaskNotTheParentsRequest() {
        ScriptedModel model = notesModel();
        ArrayNode log = log();
        user(log, "PARENT-FIRST-REQUEST");
        assistant(log, "Delegating the next request to a sub-agent.");
        user(log, "SUB-TASK");
        for (int i = 0; i < 20; i++) step(log, i);
        ContextTurn sub = new ContextTurn(CHAT, "sub-1", log, new int[]{2}, null, prefix(model), EventSink.NOOP,
                () -> false, u -> { }, false);

        ArrayNode view = strategy.prepare(sub).messages();

        MnemonicState.Segment seg = strategy.state(CHAT, "sub-1").segments.get(0);
        assertEquals(2, seg.firstUser());
        assertNull(seg.instruction(), "the task is also the instruction: not repeated");
        // the head starts with the request kept verbatim (the parent's shows up only in the recall index)
        assertEquals("SUB-TASK", view.get(0).path("content").path(0).path("text").asText());    }

    @Test
    void segmentFallsBackToTheFirstUserMessageOfTheLog() {
        ArrayNode log = log();
        user(log, "only");
        assistant(log, "a");
        assistant(log, "b");
        MnemonicState.Segment s = SegmentSwitcher.segment(log, 1, 2, "n", "i");
        assertEquals(0, s.firstUser());
    }

    // ---- 8. recall ids of the inherited copy resolve through the parent -------------------------------------------

    @Test
    void aSubAgentRecallsIdsFromTheInheritedCopy() throws Exception {
        ArrayNode parent = log();
        user(parent, "Read all the files and report");
        for (int i = 0; i < 15; i++) readPair(parent, i);
        strategy.prepare(turn(parent, null));
        MnemonicState.Mask m = strategy.state(CHAT, AGENT).masks.get(0);

        // the sub-agent starts from the parent's rendered view: receipts, not the originals
        ArrayNode sub = strategy.inheritedView(CHAT, AGENT, parent).deepCopy();
        assistant(sub, "Delegating the next request to a sub-agent.");
        user(sub, "sub task");
        strategy.prepare(new ContextTurn(CHAT, "sub-1", sub, new int[]{sub.size() - 1}, null, null, null, null, null,
                false));
        assertTrue(sub.toString().contains("recall id=" + m.id()));

        HarnessTool tool = strategy.tools().get(0);
        ToolContext subCtx = new ToolContext(CHAT, Path.of("."), Path.of("."), false, null, PermissionMode.DEFAULT,
                new AgentRun(EventSink.NOOP, () -> false, "sub-1"));
        assertEquals(fileText(0), tool.execute(subCtx, json("{'id':'" + m.id() + "'}")));
        String mid = SegmentSwitcher.messageId(2, parent.get(2));
        assertTrue(tool.execute(subCtx, json("{'id':'" + mid + "'}")).contains("marker0Value"));
        assertTrue(tool.execute(subCtx, json("{'id':'rnothere'}")).startsWith("Unknown recall id"));

        // a receipt copied into a write is still refused, pointing at the recall that now works
        Hooks hooks = new Hooks();
        strategy.install(hooks);
        assertTrue(hooks.run(HookContext.preTool(CHAT, "write_file",
                MAPPER.createObjectNode().put("path", "a").put("content", receipts(sub).get(0)))).denied());
    }

    // ---- 11. no noise pruning under mnemonic ----------------------------------------------------------------------

    @Test
    void theNoisePrunerIsNotForTheMnemonicStrategy() {
        assertFalse(NoiseCommandPruner.appliesTo(ContextStrategyKind.MNEMONIC));
        assertTrue(NoiseCommandPruner.appliesTo(ContextStrategyKind.COMPACTION));
        assertTrue(NoiseCommandPruner.appliesTo(ContextStrategyKind.SCOPE));
    }

    // ---- 12. an unreadable mnemonic.json is quarantined -----------------------------------------------------------

    @Test
    void anUnreadableStateIsQuarantinedAndReported(@TempDir Path root) throws Exception {
        FileMnemonicStore files = new FileMnemonicStore(root);
        Path f = root.resolve(CHAT).resolve(AGENT).resolve(FileMnemonicStore.FILE);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{ not json");

        MnemonicState st = files.load(CHAT, AGENT);

        assertTrue(st.masks.isEmpty() && st.segments.isEmpty());
        assertNotNull(st.warning);
        assertFalse(Files.exists(f));
        try (var list = Files.list(f.getParent())) {
            List<Path> corrupt = list.filter(p -> p.getFileName().toString()
                    .startsWith(FileMnemonicStore.FILE + ".corrupt-")).toList();
            assertEquals(1, corrupt.size());
            assertEquals("{ not json", Files.readString(corrupt.get(0)));
        }

        // the strategy reports it once as a warning event, then works normally
        MnemonicStrategy s = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), files, SubAgentRunLog.noop());
        Files.writeString(f, "]]");
        Events ev = new Events();
        ArrayNode log = log();
        user(log, "hi");
        s.prepare(new ContextTurn(CHAT, AGENT, log, null, null, null, ev, null, null, true));
        s.prepare(new ContextTurn(CHAT, AGENT, log, null, null, null, ev, null, null, true));
        assertEquals(1, ev.ofType("warning").size());
        assertTrue(ev.ofType("warning").get(0).path("message").asText().contains("mnemonic.json"));
    }

    @Test
    void aStateWrittenBeforeTheInstructionFieldStillLoads(@TempDir Path root) throws Exception {
        FileMnemonicStore files = new FileMnemonicStore(root);
        Path f = root.resolve(CHAT).resolve(AGENT).resolve(FileMnemonicStore.FILE);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "{\"masks\":[],\"frontier\":3,\"segments\":[{\"cut\":3,\"cutHash\":\"h\",\"firstUser\":0,"
                + "\"firstUserHash\":\"f\",\"notes\":\"n\",\"index\":\"i\"}]}");

        MnemonicState st = files.load(CHAT, AGENT);

        assertNull(st.warning);
        assertEquals(1, st.segments.size());
        assertNull(st.segments.get(0).instruction());
        assertEquals(3, st.frontier);

        files.save(CHAT, AGENT, st);
        assertEquals(st.segments, files.load(CHAT, AGENT).segments, "round trip");
    }
}
