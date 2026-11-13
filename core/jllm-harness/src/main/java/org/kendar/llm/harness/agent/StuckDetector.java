package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kendar.llm.harness.HarnessConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Notices an agent that repeats itself. One instance per run of the loop (main agent and each sub-agent separately).
 * It works on what the loop observes after each round of tool calls, not on the message log, so compaction and
 * masking do not affect it. Deterministic: no model is asked.
 */
final class StuckDetector {

    /** Results the loop itself writes for a call that could not run as sent. */
    static final String UNKNOWN = "Unknown tool '";
    static final String INVALID = "Invalid arguments for '";
    static final String CUT = "Not executed: your response was cut off";
    private static final List<String> MALFORMED = List.of(UNKNOWN, INVALID, CUT);

    /** One tool call of a step: name, hash of the canonical input, error flag and hash of the result. */
    record Call(String name, String input, boolean error, String result, boolean malformed)
            implements Comparable<Call> {
        String signature() {
            return name + "|" + input + "|" + error + "|" + result;
        }

        @Override
        public int compareTo(Call o) {
            return signature().compareTo(o.signature());
        }
    }

    /** The tool calls of one model response, sorted: parallel calls in another order are the same step. */
    record Step(List<Call> calls) {
        boolean malformed() {
            return !calls.isEmpty() && calls.stream().allMatch(Call::malformed);
        }

        boolean sameAs(Step o) {
            return o != null && calls.equals(o.calls);
        }
    }

    /**
     * A detected loop.
     *
     * @param pattern {@code repeat}, {@code same_error}, {@code alternation} or {@code malformed}
     * @param tool    the tool involved (the first of the step)
     * @param count   how many times it was seen
     * @param what    one sentence for the model and the user
     */
    record Detection(String pattern, String tool, int count, String what) {
    }

    private final HarnessConfig.Stuck cfg;
    private final ObjectMapper canonical;
    private final int capacity;
    private final Deque<Step> steps = new ArrayDeque<>();
    private int detections;
    private String lastVerify;

    StuckDetector(HarnessConfig.Stuck cfg) {
        this.cfg = cfg == null ? HarnessConfig.Stuck.defaults() : cfg;
        this.canonical = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.capacity = Math.max(8, Math.max(2 * this.cfg.alternationCycles(),
                Math.max(this.cfg.repeat(), this.cfg.malformed())));
    }

    boolean enabled() {
        return cfg.enabled();
    }

    /** Detections so far in this run (a reset does not clear it). */
    int detections() {
        return detections;
    }

    /** Forgets the steps seen (after a nudge or when the user lets the agent go on). */
    void reset() {
        steps.clear();
    }

    /**
     * Records the tool calls of one response and their results.
     *
     * @param uses    the tool_use blocks that were run, in the order of the response
     * @param results the tool_result blocks (matched by {@code tool_use_id}); a cut-off call answered without being
     *                run is in here only
     * @return the loop seen, or null
     */
    Detection record(List<JsonNode> uses, Iterable<JsonNode> results) {
        if (!cfg.enabled()) return null;
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode r : results) byId.put(r.path("tool_use_id").asText(), r);
        List<Call> calls = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode use : uses) {
            String id = use.path("id").asText();
            seen.add(id);
            JsonNode r = byId.get(id);
            String out = r == null ? "" : text(r.path("content"));
            boolean error = r != null && r.path("is_error").asBoolean(false);
            boolean malformed = error && MALFORMED.stream().anyMatch(out::startsWith);
            calls.add(new Call(use.path("name").asText(), hash(canonical(use.path("input"))), error, hash(out),
                    malformed));
        }
        for (JsonNode r : results) {
            String out = text(r.path("content"));
            if (!seen.contains(r.path("tool_use_id").asText()) && out.startsWith(CUT)) {
                calls.add(new Call("?", "", true, hash(out), true));
            }
        }
        if (calls.isEmpty()) return null;
        Collections.sort(calls);
        steps.addLast(new Step(List.copyOf(calls)));
        while (steps.size() > capacity) steps.removeFirst();
        Detection d = detect();
        if (d != null) {
            detections++;
            reset();
        }
        return d;
    }

    /**
     * A STOP hook vetoed the stop with {@code message}.
     *
     * @return true when it is the same message as the previous veto: the agent changed nothing that matters, so
     * re-entering again is useless
     */
    boolean verifyRepeated(String message) {
        boolean same = cfg.enabled() && message != null && message.equals(lastVerify);
        lastVerify = message;
        return same;
    }

    private Detection detect() {
        List<Step> s = new ArrayList<>(steps);
        int n = s.size();
        Step last = s.get(n - 1);
        String tool = last.calls().get(0).name();
        if (n >= cfg.malformed() && tail(s, cfg.malformed()).stream().allMatch(Step::malformed)) {
            return new Detection("malformed", tool, cfg.malformed(), "Your last " + cfg.malformed()
                    + " responses had tool calls that could not run (unknown tool, invalid arguments or cut off)");
        }
        if (n >= cfg.repeat() && tail(s, cfg.repeat()).stream().allMatch(last::sameAs)) {
            return new Detection("repeat", tool, cfg.repeat(), "You made the same tool call"
                    + (last.calls().size() > 1 ? "s" : "") + " (" + names(last) + ") " + cfg.repeat()
                    + " times in a row with the same result");
        }
        if (n >= cfg.repeat()) {
            for (Call c : last.calls()) {
                if (!c.error()) continue;
                if (tail(s, cfg.repeat()).stream().allMatch(st -> st.calls().contains(c))) {
                    return new Detection("same_error", c.name(), cfg.repeat(), "The call to " + c.name()
                            + " failed " + cfg.repeat() + " times in a row with the same arguments and the same error");
                }
            }
        }
        int span = 2 * cfg.alternationCycles();
        if (n >= span) {
            List<Step> t = tail(s, span);
            boolean alternates = !t.get(span - 1).sameAs(t.get(span - 2));
            for (int i = 2; i < span && alternates; i++) alternates = t.get(i).sameAs(t.get(i - 2));
            if (alternates) {
                return new Detection("alternation", tool, cfg.alternationCycles(), "You alternated between the same "
                        + "two steps (" + names(t.get(span - 2)) + ", then " + names(last) + ") "
                        + cfg.alternationCycles() + " times with the same results");
            }
        }
        return null;
    }

    private static List<Step> tail(List<Step> s, int k) {
        return s.subList(s.size() - k, s.size());
    }

    private static String names(Step s) {
        return String.join(", ", s.calls().stream().map(Call::name).distinct().toList());
    }

    /** JSON with object keys sorted, so key order does not matter. */
    private String canonical(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "{}";
        try {
            // a tree keeps its order when written: go through plain maps, which the feature sorts
            Object plain = canonical.treeToValue(node, Object.class);
            return canonical.writeValueAsString(plain);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private static String text(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : content) sb.append(p.path("text").asText(""));
        return sb.isEmpty() ? content.toString() : sb.toString();
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Objects.requireNonNullElse(s, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(Objects.hashCode(s));
        }
    }
}
