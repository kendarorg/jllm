package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.MAPPER;

/** Signed reasoning whose preceding content was rewritten after it was made must not be sent. */
class RendererReasoningTest {

    private final Renderer renderer = new Renderer(MAPPER);

    /** user, assistant(thinking + tool_use), user(tool_result), assistant(thinking + tool_use), user(tool_result) */
    private static ArrayNode log() {
        ArrayNode log = MAPPER.createArrayNode();
        log.addObject().put("role", "user").put("content", "go");
        for (int i = 1; i <= 2; i++) {
            ArrayNode a = log.addObject().put("role", "assistant").putArray("content");
            a.addObject().put("type", "thinking").put("thinking", "").put("signature", "s" + i);
            a.addObject().put("type", "tool_use").put("id", "t" + i).put("name", "x").putObject("input");
            log.addObject().put("role", "user").putArray("content").addObject().put("type", "tool_result")
                    .put("tool_use_id", "t" + i).put("content", "result " + i);
        }
        return log;
    }

    private static boolean hasThinking(JsonNode msg) {
        for (JsonNode b : msg.path("content")) if (Messages.isThinking(b)) return true;
        return false;
    }

    @Test
    void reasoningMadeBeforeAMaskBeforeItIsLeftOut() {
        ArrayNode log = log();
        MnemonicState st = new MnemonicState();
        // result 1 (index 2) masked when the log had 5 messages: the reasoning at index 3 was made on the unmasked text
        st.masks.add(new MnemonicState.Mask(2, "h", 0, MnemonicState.TOOL_RESULT, "r1", TextNode.valueOf("[masked]"),
                10, 5));
        ArrayNode view = renderer.render(log, st);
        assertTrue(hasThinking(view.get(1)), "before the mask: still bound to the same content");
        assertFalse(hasThinking(view.get(3)));
        assertEquals("tool_use", view.get(3).path("content").path(0).path("type").asText());
        assertTrue(hasThinking(log.get(3)), "the log is not changed");
    }

    @Test
    void reasoningMadeAfterTheMaskKeepsItsBlock() {
        ArrayNode log = log();
        MnemonicState st = new MnemonicState();
        // masked when the log had 3 messages: the reasoning at index 3 was made with the receipt already in place
        st.masks.add(new MnemonicState.Mask(2, "h", 0, MnemonicState.TOOL_RESULT, "r1", TextNode.valueOf("[masked]"),
                10, 3));
        assertTrue(hasThinking(renderer.render(log, st).get(3)));
    }

    @Test
    void aSegmentHeadVoidsTheReasoningOfTheTailMadeBeforeIt() {
        ArrayNode log = log();
        MnemonicState st = new MnemonicState();
        st.segments.add(new MnemonicState.Segment(3, Messages.hash(3, log.get(3)), 0,
                Messages.hash(0, log.get(0)), "notes", "", null, null, 5));
        ArrayNode view = renderer.render(log, st);
        // head, then the tail from index 3: its reasoning was made after the old prefix
        assertEquals("user", view.get(0).path("role").asText());
        for (JsonNode m : view) assertFalse(hasThinking(m), m.toString());
    }
}
