package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Segment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the request messages from the log and the decisions. A pure function: the same log and state always give
 * the same bytes, which is what keeps the provider's prompt cache valid.
 */
final class Renderer {

    static final String HANDOFF_ACK = "Understood. Continuing from the handoff notes.";

    private final ObjectMapper mapper;

    Renderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The request messages. */
    ArrayNode render(ArrayNode log, MnemonicState st) {
        ArrayNode out = mapper.createArrayNode();
        Segment seg = st.current();
        int start = 0;
        if (seg != null && seg.cut() < log.size()) {
            start = seg.cut();
            out.add(head(log, seg, st.segments.size()));
            if ("user".equals(Messages.role(log.get(start)))) {
                out.addObject().put("role", "assistant").putArray("content").addObject().put("type", "text")
                        .put("text", HANDOFF_ACK);
            }
        }
        List<JsonNode> applied = applied(log, st, start);
        applied.forEach(out::add);
        return out;
    }

    /** Messages {@code start..} of the log with their masks applied, aligned with the log indexes. */
    List<JsonNode> applied(ArrayNode log, MnemonicState st, int start) {
        Map<Integer, List<Mask>> byIndex = new HashMap<>();
        for (Mask m : st.masks) byIndex.computeIfAbsent(m.index(), k -> new ArrayList<>()).add(m);
        List<JsonNode> out = new ArrayList<>(Math.max(0, log.size() - start));
        for (int i = start; i < log.size(); i++) {
            List<Mask> ms = byIndex.get(i);
            out.add(ms == null ? log.get(i) : apply(log.get(i), ms));
        }
        return out;
    }

    /** A copy of {@code msg} with the masks applied. */
    static JsonNode apply(JsonNode msg, List<Mask> masks) {
        ObjectNode copy = msg.deepCopy();
        JsonNode content = copy.path("content");
        if (!content.isArray()) return copy;
        ArrayNode blocks = (ArrayNode) content;
        List<Integer> drop = new ArrayList<>();
        for (Mask m : masks) {
            if (m.block() < 0 || m.block() >= blocks.size()) continue;
            JsonNode b = blocks.get(m.block());
            switch (m.kind()) {
                case MnemonicState.TOOL_RESULT -> {
                    if ("tool_result".equals(b.path("type").asText())) ((ObjectNode) b).set("content", m.replacement());
                }
                case MnemonicState.TOOL_INPUT -> {
                    if ("tool_use".equals(b.path("type").asText())) ((ObjectNode) b).set("input", m.replacement());
                }
                case MnemonicState.THINKING -> {
                    if (Messages.isThinking(b)) drop.add(m.block());
                }
                default -> { }
            }
        }
        drop.sort(null);
        for (int i = drop.size() - 1; i >= 0; i--) blocks.remove((int) drop.get(i));
        return copy;
    }

    /** First user request verbatim, then the handoff notes and the recall index. */
    private ObjectNode head(ArrayNode log, Segment seg, int number) {
        ObjectNode head = mapper.createObjectNode();
        head.put("role", "user");
        ArrayNode content = head.putArray("content");
        if (seg.firstUser() >= 0 && seg.firstUser() < seg.cut() && seg.firstUser() < log.size()) {
            JsonNode c = log.get(seg.firstUser()).path("content");
            if (c.isTextual()) content.addObject().put("type", "text").put("text", c.asText());
            else for (JsonNode b : c) if ("text".equals(b.path("type").asText())) content.add(b.deepCopy());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Context segment ").append(number).append(". The earlier conversation was moved out of the prompt. ")
                .append("Handoff notes written at the switch follow; the full text of anything earlier is available ")
                .append("through history_recall.]\n\n").append(seg.notes() == null ? "" : seg.notes().strip());
        if (seg.index() != null && !seg.index().isBlank()) {
            sb.append("\n\nRecall index (newest first):\n").append(seg.index().strip());
        }
        content.addObject().put("type", "text").put("text", sb.toString());
        return head;
    }
}
