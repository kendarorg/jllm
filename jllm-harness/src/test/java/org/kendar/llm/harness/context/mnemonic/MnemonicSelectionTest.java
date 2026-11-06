package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.context.compaction.CompactionStrategy;
import org.kendar.llm.harness.context.compaction.ContextManager;
import org.kendar.llm.harness.settings.HarnessSettings;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.fileText;

/** Choosing the strategy (settings, config, builder) and the mnemonic strategy inside the real agent loop. */
class MnemonicSelectionTest {

    @TempDir
    Path base;

    @Test
    void settingsChooseTheStrategy() throws Exception {
        Path f = Files.writeString(base.resolve("s.json"), """
                {"context":{"strategy":"mnemonic","mnemonic":{"maskTrigger":50000,"minAgeTurns":3}}}""");
        HarnessConfig c = HarnessSettings.load(f, k -> null).applyTo(HarnessConfig.defaults());
        assertEquals(ContextStrategyKind.MNEMONIC, c.contextStrategy());
        assertEquals(50000, c.mnemonic().maskTrigger());
        assertEquals(3, c.mnemonic().minAgeTurns());
        assertEquals(HarnessConfig.Mnemonic.defaults().notesMaxTokens(), c.mnemonic().notesMaxTokens());

        Path old = Files.writeString(base.resolve("o.json"), "{\"context\":{\"strategy\":\"compaction\"}}");
        assertEquals(ContextStrategyKind.COMPACTION,
                HarnessSettings.load(old, k -> null).applyTo(c).contextStrategy());
        assertEquals(ContextStrategyKind.COMPACTION, HarnessConfig.defaults().contextStrategy());

        Path bad = Files.writeString(base.resolve("b.json"), "{\"context\":{\"strategy\":\"magic\"}}");
        assertThrows(IllegalArgumentException.class, () -> HarnessSettings.load(bad, k -> null).applyTo(c));
        assertEquals(ContextStrategyKind.MNEMONIC,
                HarnessSettings.empty().withContextStrategy("mnemonic").applyTo(HarnessConfig.defaults())
                        .contextStrategy());
    }

    @Test
    void theBuilderBuildsTheChosenStrategyAndAFactoryWins() {
        HarnessSettings mnemonic = HarnessSettings.empty().withContextStrategy("mnemonic");
        AgentHarness h = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools().settings(mnemonic).build();
        assertInstanceOf(MnemonicStrategy.class, h.contextStrategy());
        String chat = h.sessions().create(null).chatId();
        assertEquals(MnemonicStrategy.NOT_USED, h.commands().execute(chat, "/compact").output());
        assertTrue(h.commands().execute(chat, "/status").output().contains("context strategy: mnemonic"));

        AgentHarness byConfig = AgentHarness.builder().modelClient(new ScriptedModel())
                .config(config(base).withContextStrategy(ContextStrategyKind.MNEMONIC)).withoutStandardTools().build();
        assertInstanceOf(MnemonicStrategy.class, byConfig.contextStrategy());

        AgentHarness old = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools().build();
        assertInstanceOf(CompactionStrategy.class, old.contextStrategy());

        AgentHarness custom = AgentHarness.builder().modelClient(new ScriptedModel()).config(config(base))
                .withoutStandardTools().settings(mnemonic)
                .contextStrategy(d -> new CompactionStrategy(new ContextManager(d.config(), d.summaryModel(),
                        d.approvals(), d.mapper(), d.metrics()), d.config(), d.mapper()))
                .build();
        assertInstanceOf(CompactionStrategy.class, custom.contextStrategy());
    }

    /** A loop that calls the {@code big} tool {@code calls} times, then answers. */
    private static void scriptBigCalls(ScriptedModel model, int calls) {
        AtomicInteger n = new AtomicInteger();
        model.handler = r -> {
            String last = r.messages().get(r.messages().size() - 1).toString();
            if (last.contains("handoff notes")) return text("notes");
            int i = n.getAndIncrement();
            return i < calls ? toolUse("t" + i, "big", "{'x':'" + i + "'}") : text("done");
        };
    }

    @Test
    void inTheLoopTheLogStaysVerbatimAndThePrefixStable() {
        Rig rig = new Rig(base);
        rig.configure(c -> MnemonicTestSupport.config().withAgentId("jllm").withSandboxBaseDir(base.toString())
                .withLocalDirSandboxAllowed(false).withApprovalTimeoutSeconds(5).withSandboxTimeoutSeconds(10));
        AtomicInteger seq = new AtomicInteger();
        rig.register(tool("big", "Misc", ToolKind.READ_ONLY, (c, in) -> fileText(seq.getAndIncrement())));
        String chat = rig.newChat();
        scriptBigCalls(rig.model, 16);
        Events events = new Events();
        rig.loop.run(chat, "go", events, () -> false);

        List<Recorded> reqs = rig.model.requests;
        assertEquals(17, reqs.size());
        assertFalse(events.ofType("compaction").isEmpty(), "a masking batch happened");
        assertEquals("mnemonic", events.ofType("compaction").get(0).path("strategy").asText());
        for (Recorded r : reqs) {
            assertEquals(reqs.get(0).pinned(), r.pinned());
            assertEquals(reqs.get(0).extra(), r.extra());
            assertEquals(reqs.get(0).toolNames(), r.toolNames());
        }
        assertTrue(reqs.get(0).toolNames().contains("history_recall"));

        // the stored log is the verbatim history, the requests carry receipts
        ArrayNode stored = rig.sessions.messages(rig.sessions.get(chat));
        assertFalse(stored.toString().contains("[masked:"));
        assertTrue(reqs.get(reqs.size() - 1).messages().toString().contains("[masked:"));

        // between batches each request extends the previous one byte for byte
        int appendOnly = 0;
        for (int k = 1; k < reqs.size(); k++) {
            String prev = reqs.get(k - 1).messages().toString();
            if (reqs.get(k).messages().toString().startsWith(prev.substring(0, prev.length() - 1))) appendOnly++;
        }
        assertTrue(appendOnly >= reqs.size() - 1 - events.ofType("compaction").size());
    }

    @Test
    void taskResultsCarryTheRunId() throws Exception {
        Rig rig = new Rig(base);
        rig.configure(c -> c.withContextStrategy(ContextStrategyKind.MNEMONIC));
        String chat = rig.newChat();
        rig.model.enqueue(toolUse("k1", "task", "{'prompts':['look around']}"), text("sub answer"), text("main done"));
        rig.loop.run(chat, "delegate", new Events(), () -> false);

        JsonNode result = null;
        for (JsonNode m : rig.sessions.messages(rig.sessions.get(chat))) {
            for (JsonNode b : m.path("content")) if ("tool_result".equals(b.path("type").asText())) result = b;
        }
        assertNotNull(result);
        JsonNode entries = MAPPER.readTree(result.path("content").asText());
        String runId = entries.get(0).path("runId").asText();
        assertFalse(runId.isBlank());
        assertEquals(runId, rig.runLog.list(chat).get(0).id());
    }

    @Test
    void fileStoreRoundTrips() {
        FileMnemonicStore store = new FileMnemonicStore(base);
        MnemonicState st = new MnemonicState();
        st.frontier = 7;
        st.masks.add(new MnemonicState.Mask(7, "h", 0, MnemonicState.TOOL_RESULT, "r1",
                com.fasterxml.jackson.databind.node.TextNode.valueOf("[masked: x]"), 10));
        st.segments.add(new MnemonicState.Segment(9, "h9", 0, "h0", "notes", "index"));
        store.save("chat/1", "agent", st);
        MnemonicState back = store.load("chat/1", "agent");
        assertEquals(7, back.frontier);
        assertEquals(st.masks, back.masks);
        assertEquals(st.segments, back.segments);
        store.delete("chat/1");
        assertTrue(store.load("chat/1", "agent").masks.isEmpty());
    }
}
