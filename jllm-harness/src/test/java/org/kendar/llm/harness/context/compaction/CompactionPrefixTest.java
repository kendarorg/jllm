package org.kendar.llm.harness.context.compaction;

import org.kendar.llm.harness.context.CompactResult;
import org.kendar.llm.harness.context.RequestPrefix;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.permission.ApprovalService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;
import static org.kendar.llm.harness.HarnessTestSupport.config;
import static org.kendar.llm.harness.HarnessTestSupport.text;

class CompactionPrefixTest {

    private static ArrayNode conversation(int turns) {
        ArrayNode m = MAPPER.createArrayNode();
        for (int i = 1; i <= turns; i++) {
            m.addObject().put("role", "user").putArray("content").addObject().put("type", "text")
                    .put("text", "q" + i + " " + "p".repeat(200));
            m.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text").put("text", "a" + i);
        }
        return m;
    }

    private final HarnessConfig cfg = config(Path.of(".")).withKeepRecentTurns(1);

    @Test
    void summaryRequestIsTheNormalRequestPlusTheInstruction() {
        ScriptedModel model = new ScriptedModel();
        model.enqueue(text("SUMMARY"));
        ContextManager cm = new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(3);
        String firstTurns = m.get(0).toString() + m.get(1).toString();

        var r = cm.compactNow(m, null, u -> { },
                new RequestPrefix("PIN", "EXTRA", List.of(), model, false));

        assertTrue(r.changed());
        assertTrue(model.simpleUserPrompts.isEmpty(), "no plain text request was needed");
        var sent = model.requests.get(0);
        assertEquals("PIN", sent.pinned());
        assertEquals("EXTRA", sent.extra());
        assertEquals(5, sent.messages().size(), "two earlier turns + instruction... ");
        assertTrue((sent.messages().get(0).toString() + sent.messages().get(1).toString()).equals(firstTurns),
                "history is sent unchanged, so the cached prefix matches");
        assertTrue(sent.messages().get(4).toString().contains("## Objective"));
        assertTrue(m.get(0).toString().contains("SUMMARY"));
    }

    @Test
    void anotherClientMeansAPlainTextRequest() {
        ScriptedModel model = new ScriptedModel();
        model.simpleReplies.add("PLAIN");
        ContextManager cm = new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(3);

        cm.compactNow(m, null, u -> { }, new RequestPrefix("P", "E", List.of(), new ScriptedModel(), false));

        assertEquals(1, model.simpleUserPrompts.size());
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void refusedAsTooLongFallsBackToShorterAndShorterTextRequests() {
        ScriptedModel model = new ScriptedModel();
        model.handler = r -> {
            throw new ModelException("prompt is too long");
        };
        List<Integer> sizes = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        model.simpleHandler = user -> {
            sizes.add(user.length());
            if (calls.incrementAndGet() < 3) throw new ModelException("prompt is too long");
            return "OK";
        };
        ContextManager cm = new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(40);

        assertTrue(cm.compactNow(m, null, u -> { }, new RequestPrefix("P", "E", List.of(), model, false))
                .changed());

        assertEquals(3, sizes.size());
        assertTrue(sizes.get(1) <= sizes.get(0) && sizes.get(2) < sizes.get(0), sizes.toString());
    }

    @Test
    void whenNothingFitsItIsNothingToCompact() {
        ScriptedModel model = new ScriptedModel();
        model.simpleHandler = user -> {
            throw new ModelException("prompt is too long");
        };
        ContextManager cm = new ContextManager(cfg, model, new ApprovalService(5), MAPPER);
        ArrayNode m = conversation(5);
        String before = m.toString();

        var r = cm.compactNow(m, null, u -> { });

        assertFalse(r.changed());
        assertEquals(before, m.toString());
        assertEquals(5, model.simpleUserPrompts.size(), "unshortened + 85/70/50/35%");
    }
}
