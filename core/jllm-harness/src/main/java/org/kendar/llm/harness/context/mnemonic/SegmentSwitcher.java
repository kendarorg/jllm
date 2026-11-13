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

    /** The handoff instruction appended to the notes request. */
    static final String HANDOFF = Prompts.load("mnemonic-handoff.md");

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
        ((ArrayNode) last.get("content")).addObject().put("type", "text").put("text", HANDOFF);
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

    /**
     * @param from where the agent's own messages start (after a copy of the parent's context): a sub-agent's own task
     *             is its first request, not the parent's
     */
    static Segment segment(ArrayNode log, int from, int cut, String notes, String index) {
        int first = firstUser(log, Math.max(0, from), cut);
        if (first < 0) first = firstUser(log, 0, cut);
        // the instruction in progress: when the cut falls after it, it must not leave the prompt
        int last = MaskPlanner.lastPlainUser(log);
        Integer instruction = last >= 0 && last < cut && last != first ? last : null;
        return new Segment(cut, Messages.hash(cut, log.get(cut)), first,
                first < 0 ? null : Messages.hash(first, log.get(first)), notes, index, instruction,
                instruction == null ? null : Messages.hash(instruction, log.get(instruction)));
    }

    private static int firstUser(ArrayNode log, int from, int to) {
        for (int i = from; i < to && i < log.size(); i++) {
            if (Messages.isPlainUserText(log.get(i))) return i;
        }
        return -1;
    }

    /**
     * The request messages for the handoff notes within {@code maxTokens} (unscaled estimate): {@code view} itself when
     * it fits (prompt cache hit), else its first message (the first request or the segment head), a marker, and the
     * newest messages that fit, starting at a plain user message or an assistant message after a user message so a
     * tool call keeps its results. When nothing fits the shortest such tail is used.
     */
    ArrayNode fit(ArrayNode view, long maxTokens) {
        int n = view.size();
        if (n == 0 || Messages.tokens(view) <= maxTokens) return view;
        long[] suffix = new long[n + 1];
        for (int i = n - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + Messages.tokens(view.get(i));
        boolean keepFirst = "user".equals(Messages.role(view.get(0)));
        ObjectNode marker = mapper.createObjectNode().put("role", "assistant");
        marker.putArray("content").addObject().put("type", "text").put("text", OMITTED);
        long head = keepFirst ? Messages.tokens(view.get(0)) + Messages.tokens(marker) : 0;
        int start = -1;
        int latest = -1;
        for (int i = 1; i < n; i++) {
            if (!Messages.isPlainUserText(view.get(i)) && !groupBoundary(view, i)) continue;
            latest = i;
            if (start < 0 && head + suffix[i] <= maxTokens) start = i;
        }
        if (start < 0) start = latest;
        if (start < 0) return view;
        ArrayNode out = mapper.createArrayNode();
        if (keepFirst) {
            if ("user".equals(Messages.role(view.get(start)))) {
                // keep user/assistant alternation: the marker is an assistant turn before a user message
                out.add(view.get(0));
                out.add(marker);
            } else {
                ObjectNode first = view.get(0).deepCopy();
                ArrayNode blocks = mapper.createArrayNode();
                JsonNode c = first.path("content");
                if (c.isTextual()) blocks.addObject().put("type", "text").put("text", c.asText());
                else if (c.isArray()) blocks.addAll((ArrayNode) c);
                blocks.addObject().put("type", "text").put("text", OMITTED);
                first.set("content", blocks);
                out.add(first);
            }
        }
        for (int i = start; i < n; i++) out.add(view.get(i));
        return out;
    }

    static final String OMITTED = "[Earlier messages are left out of this request to fit the context window.]";
}
