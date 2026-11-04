package org.kendar.llm.harness.context.mnemonic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.context.RequestPrefix;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Mask;
import org.kendar.llm.harness.context.mnemonic.MnemonicState.Segment;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.Usage;

import java.util.List;
import java.util.function.Consumer;

/** Level 2: moves the older part of the conversation out of the prompt, behind handoff notes. */
final class SegmentSwitcher {

    private final ObjectMapper mapper;

    SegmentSwitcher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Where the new segment starts, in order of preference: the earliest plain user message whose tail fits
     * {@code tailMax}; else the earliest group boundary (an assistant message after a user message) whose tail fits;
     * else the latest valid boundary. A tool call is never separated from its results.
     *
     * @param applied messages {@code from..} as currently rendered, aligned with the log
     * @return the cut index, or -1 when there is nowhere to cut after {@code from}
     */
    static int chooseCut(ArrayNode log, int from, List<JsonNode> applied, long tailMax) {
        int n = log.size();
        long[] suffix = new long[n + 1];
        for (int i = n - 1; i >= from; i--) suffix[i] = suffix[i + 1] + Messages.tokens(applied.get(i - from));
        int latest = -1;
        for (int i = from + 1; i < n; i++) {
            if (Messages.isPlainUserText(log.get(i))) {
                if (suffix[i] <= tailMax) return i;
                latest = i;
            }
        }
        for (int i = from + 1; i < n; i++) {
            if (groupBoundary(log, i)) {
                if (suffix[i] <= tailMax) return i;
                latest = Math.max(latest, i);
            }
        }
        return latest;
    }

    static boolean groupBoundary(ArrayNode log, int i) {
        return i > 0 && "assistant".equals(Messages.role(log.get(i))) && "user".equals(Messages.role(log.get(i - 1)));
    }

    /**
     * Asks the agent's own model for handoff notes: the request it would send now (so the prompt cache is hit)
     * followed by the handoff instruction. @return the notes, or a placeholder when none came back
     */
    String notes(RequestPrefix prefix, ArrayNode view, int maxTokens, Consumer<Usage> usageSink) {
        ArrayNode req = mapper.createArrayNode();
        int n = view.size();
        boolean mergeIntoLast = n > 0 && "user".equals(Messages.role(view.get(n - 1)));
        for (int i = 0; i < n - (mergeIntoLast ? 1 : 0); i++) req.add(view.get(i));
        ObjectNode last;
        if (mergeIntoLast) {
            last = view.get(n - 1).deepCopy();
            JsonNode c = last.path("content");
            ArrayNode blocks = mapper.createArrayNode();
            if (c.isTextual()) blocks.addObject().put("type", "text").put("text", c.asText());
            else blocks.addAll((ArrayNode) c);
            last.set("content", blocks);
            req.add(last);
        } else {
            last = req.addObject().put("role", "user");
            last.putArray("content");
        }
        ((ArrayNode) last.get("content")).addObject().put("type", "text").put("text", Prompts.load("mnemonic-handoff.md"));
        ModelResponse resp = prefix.client().complete(new ModelRequest(prefix.pinned(), prefix.extra(), req,
                prefix.tools(), maxTokens, null, prefix.disableParallelToolUse()));
        if (resp.usage() != null) usageSink.accept(resp.usage());
        StringBuilder text = new StringBuilder();
        for (JsonNode b : resp.content()) {
            if ("text".equals(b.path("type").asText())) text.append(b.path("text").asText());
        }
        return text.toString().isBlank() ? "(no handoff notes were written)" : text.toString().strip();
    }

    /** Recall ids of what is moved out of the prompt, newest first, within {@code maxTokens}. */
    static String index(ArrayNode log, MnemonicState st, int cut, long maxTokens) {
        long maxChars = Math.round(maxTokens * Messages.CHARS_PER_TOKEN);
        StringBuilder sb = new StringBuilder();
        for (int i = st.masks.size() - 1; i >= 0; i--) {
            Mask m = st.masks.get(i);
            if (!MnemonicState.TOOL_RESULT.equals(m.kind()) || m.index() >= cut) continue;
            String line = "- " + m.replacement().asText().replace("[masked: ", "").replace("]", "") + "\n";
            if (sb.length() + line.length() > maxChars) return sb.toString();
            sb.append(line);
        }
        for (int i = cut - 1; i >= 0; i--) {
            JsonNode msg = log.get(i);
            if (!Messages.isPlainUserText(msg)) continue;
            String text = Messages.text(msg).replace('\n', ' ');
            if (text.length() > 100) text = text.substring(0, 100) + "…";
            String line = "- " + MARKER_USER + messageId(i, msg) + " user: " + text + "\n";
            if (sb.length() + line.length() > maxChars) return sb.toString();
            sb.append(line);
        }
        return sb.toString();
    }

    private static final String MARKER_USER = "id=";

    /** Recall id of a whole message. */
    static String messageId(int index, JsonNode msg) {
        return "m" + Messages.hash(index, msg).substring(0, 12);
    }

    static Segment segment(ArrayNode log, int cut, String notes, String index) {
        int first = -1;
        for (int i = 0; i < cut; i++) {
            if (Messages.isPlainUserText(log.get(i))) {
                first = i;
                break;
            }
        }
        return new Segment(cut, Messages.hash(cut, log.get(cut)), first,
                first < 0 ? null : Messages.hash(first, log.get(first)), notes, index);
    }
}
