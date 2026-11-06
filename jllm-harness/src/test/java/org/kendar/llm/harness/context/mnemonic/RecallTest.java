package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessMetrics;
import org.kendar.llm.harness.HarnessTestSupport.*;
import org.kendar.llm.harness.hooks.HookContext;
import org.kendar.llm.harness.hooks.HookEvent;
import org.kendar.llm.harness.hooks.Hooks;
import org.kendar.llm.harness.session.InMemorySubAgentRunLog;
import org.kendar.llm.harness.session.SubAgentRunData;
import org.kendar.llm.harness.tools.HarnessTool;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.*;
import static org.kendar.llm.harness.context.mnemonic.MnemonicTestSupport.config;

class RecallTest {

    private static List<JsonNode> messages(String... texts) {
        ArrayNode log = log();
        for (String t : texts) user(log, t);
        List<JsonNode> out = new java.util.ArrayList<>();
        log.forEach(out::add);
        return out;
    }

    @Test
    void identifiersMatchWholeOrByParts() {
        RecallIndex index = new RecallIndex();
        index.update(messages("nothing here", "the method computeTotalPrice returns a BigDecimal",
                "see history_recall in the docs"));
        assertEquals(1, index.search(List.of("compute total price"), i -> true, 5).get(0).message());
        assertEquals(1, index.search(List.of("computeTotalPrice"), i -> true, 5).get(0).message());
        assertEquals(1, index.search(List.of("total_price"), i -> true, 5).get(0).message());
        assertTrue(index.search(List.of("total_price"), i -> true, 5).get(0).strong());
        assertEquals(2, index.search(List.of("historyRecall"), i -> true, 5).get(0).message());
    }

    @Test
    void pathsMatchAsSubstringAndFilterApplies() {
        RecallIndex index = new RecallIndex();
        index.update(messages("edited src/main/A.java today", "src main A java words scattered"));
        RecallIndex.Hit h = index.search(List.of("src/main/A.java"), i -> true, 5).get(0);
        assertEquals(0, h.message());
        assertTrue(h.strong());
        assertTrue(index.search(List.of("src/main/A.java"), i -> i != 0, 5).stream().noneMatch(x -> x.message() == 0));
    }

    @Test
    void variantsAreFusedOncePerMessage() {
        RecallIndex index = new RecallIndex();
        index.update(messages("alpha beta", "gamma"));
        List<RecallIndex.Hit> hits = index.search(List.of("alpha", "beta", "gamma"), i -> true, 5);
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0).message());
    }

    @Test
    void longTextsAreSplitIntoOverlappingPassages() {
        String text = "x".repeat(2000);
        List<int[]> spans = RecallIndex.split(text);
        assertTrue(spans.size() >= 3);
        assertEquals(0, spans.get(0)[0]);
        assertEquals(2000, spans.get(spans.size() - 1)[1]);
        for (int i = 1; i < spans.size(); i++) assertTrue(spans.get(i)[0] < spans.get(i - 1)[1], "overlap");
    }

    @Test
    void crudeStemmerJoinsInflections() {
        assertEquals(RecallIndex.stem("configurazione"), RecallIndex.stem("configurazioni"));
        assertEquals(RecallIndex.stem("connections"), RecallIndex.stem("connection"));
        assertEquals("api", RecallIndex.stem("api"));
    }

    private MnemonicStrategy maskedStrategy(ArrayNode log, InMemorySubAgentRunLog runs) {
        MnemonicStrategy s = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), new InMemoryMnemonicStore(),
                runs);
        user(log, "Read all the files and report");
        for (int i = 0; i < 15; i++) readPair(log, i);
        s.prepare(turn(log, null));
        assertFalse(s.state(CHAT, AGENT).masks.isEmpty());
        return s;
    }

    @Test
    void recallReturnsMaskedContentWordForWord() throws Exception {
        ArrayNode log = log();
        MnemonicStrategy s = maskedStrategy(log, new InMemorySubAgentRunLog());
        HarnessTool tool = s.tools().get(0);
        assertEquals("history_recall", tool.name());
        MnemonicState.Mask m = s.state(CHAT, AGENT).masks.get(0);

        String out = tool.execute(toolContext(), json("{'id':'" + m.id() + "'}"));
        assertEquals(fileText(0), out);

        String found = tool.execute(toolContext(), json("{'query':'src/F3.java'}"));
        assertTrue(found.contains("1. id=" + s.state(CHAT, AGENT).masks.get(3).id()), found);

        String paged = tool.execute(toolContext(), json("{'id':'" + m.id() + "','offset':3000}"));
        assertEquals(fileText(0).substring(3000), paged);
    }

    @Test
    void visibleMessagesAreNotSearched() throws Exception {
        ArrayNode log = log();
        MnemonicStrategy s = maskedStrategy(log, new InMemorySubAgentRunLog());
        // the tail holding marker14Value is visible: only weaker matches from masked messages come back
        String out = s.tools().get(0).execute(toolContext(), json("{'query':'marker14Value'}"));
        assertFalse(out.contains("(message 30,"), out);
        assertFalse(out.contains("all words"), out);
    }

    @Test
    void fileTimelineListsCallsAndResultIds() throws Exception {
        ArrayNode log = log();
        MnemonicStrategy s = maskedStrategy(log, new InMemorySubAgentRunLog());
        String out = s.tools().get(0).execute(toolContext(), json("{'file':'src/F2.java'}"));
        assertTrue(out.contains("message 5: read_file(path=src/F2.java)"), out);
        assertTrue(out.contains("-> id=" + s.state(CHAT, AGENT).masks.get(2).id()), out);
    }

    @Test
    void subAgentRunsAreReachableByRunId() throws Exception {
        InMemorySubAgentRunLog runs = new InMemorySubAgentRunLog();
        ArrayNode transcript = log();
        user(transcript, "find the config loader");
        assistant(transcript, "It is SettingsLoader in settings/Loader.java");
        runs.record(new SubAgentRunData("run-1", CHAT, "task1", "find it", MAPPER.writeValueAsString(transcript.get(0))
                + "\n" + MAPPER.writeValueAsString(transcript.get(1)), "SettingsLoader", "ok", 0, 0, 0, 0,
                Instant.now()));
        MnemonicStrategy s = maskedStrategy(log(), runs);
        HarnessTool tool = s.tools().get(0);

        String hit = tool.execute(toolContext(), json("{'query':'settings loader','scope':'run-1'}"));
        assertTrue(hit.contains("message 1 (assistant)"), hit);
        String whole = tool.execute(toolContext(), json("{'id':'run-1'}"));
        assertTrue(whole.contains("Answer: SettingsLoader"));
        assertTrue(whole.contains("settings/Loader.java"));
    }

    @Test
    void receiptGuardBlocksCopiedReceipts() {
        Hooks hooks = new Hooks();
        new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), null, null).install(hooks);
        var denied = hooks.run(HookContext.preTool(CHAT, "write_file",
                json("{'path':'a.txt','content':'[masked ~300 tokens, recall id=a0123456789ab]'}")));
        assertTrue(denied.denied());
        assertTrue(denied.denyReason().contains("history_recall"));
        assertFalse(hooks.run(HookContext.preTool(CHAT, "write_file", json("{'path':'a.txt','content':'hi'}"))).denied());
        assertFalse(hooks.run(HookContext.preTool(CHAT, "read_file",
                json("{'path':'[masked x recall id=r1]'}"))).denied());
        assertEquals(HookEvent.PRE_TOOL, HookContext.preTool(CHAT, "x", null).event());
    }

    @Test
    void segmentContentBecomesSearchable() throws Exception {
        MnemonicStrategy s = new MnemonicStrategy(config(), MAPPER, HarnessMetrics.noop(), null, null);
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("notes"));
        ArrayNode log = log();
        user(log, "The very first request");
        for (int i = 0; i < 9; i++) {
            assistant(log, "reply " + i + " " + fileText(i));
            user(log, "question " + i + " " + fileText(i + 100));
        }
        s.prepare(turn(log, prefix(model)));
        HarnessTool tool = s.tools().get(0);
        String before = tool.execute(toolContext(), json("{'query':'marker2Value'}"));
        assertTrue(before.contains("\n1. id=m") && before.contains("(message 5, assistant, all words)"), before);
        String tail = tool.execute(toolContext(), json("{'query':'marker108Value'}"));
        assertFalse(tail.contains("(message 18,"), tail);
    }
}
