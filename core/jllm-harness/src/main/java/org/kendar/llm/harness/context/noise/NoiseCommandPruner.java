package org.kendar.llm.harness.context.noise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.session.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes shell commands that add no information from the context once the turn that issued them has ended.
 *
 * <p>A qualifying call (a {@code bash} call shorter than {@link NoiseQualifier#MAX_COMMAND_CHARS} characters, exit
 * code 0, no timeout, output under {@link #MAX_OUTPUT_CHARS} characters) that the {@link NoiseQualifier} answers
 * {@code drop} for is rewritten in place to a stub that keeps the {@code tool_use} / {@code tool_result} pairing valid
 * for every provider. The original call and result stay in the log file as a reference line
 * ({@code {"ignore":true,"replaces":<tool_use id>,"original":{...}}}) that never reaches the context.
 *
 * <p>Rewriting a message changes the prompt from that message on, so drops are applied in batches: the calls are
 * collected after each turn and rewritten together once they save at least {@code minSavedTokens} tokens. The stub is
 * the decision; after a restart it is in the log and is never qualified again. A call that was not applied yet is
 * qualified again after a restart (nothing was written for it).
 */
public final class NoiseCommandPruner {

    private static final Logger log = LoggerFactory.getLogger(NoiseCommandPruner.class);

    /** The stub that replaces a dropped result. */
    public static final String STUB_RESULT = "ok (exit 0)";
    /** Longest output (characters) of a command that can be dropped. */
    public static final int MAX_OUTPUT_CHARS = 200;
    public static final int DEFAULT_MIN_SAVED_TOKENS = 1000;
    private static final int STUB_COMMAND_CHARS = 80;

    private final ChatSessionService sessions;
    private final NoiseQualifier qualifier;
    private final ObjectMapper mapper;
    private final int minSavedTokens;
    /** Per chat: tool_use ids already judged (kept, or waiting for the next batch). */
    private final Map<String, Set<String>> decided = new ConcurrentHashMap<>();
    /** Per chat: dropped-to-be calls (id to estimated saved tokens). */
    private final Map<String, Map<String, Integer>> pending = new ConcurrentHashMap<>();

    public NoiseCommandPruner(ChatSessionService sessions, NoiseQualifier qualifier, ObjectMapper mapper,
                              int minSavedTokens) {
        this.sessions = sessions;
        this.qualifier = qualifier;
        this.mapper = mapper;
        this.minSavedTokens = minSavedTokens;
    }

    public NoiseCommandPruner(ChatSessionService sessions, NoiseQualifier qualifier, ObjectMapper mapper) {
        this(sessions, qualifier, mapper, DEFAULT_MIN_SAVED_TOKENS);
    }

    /**
     * Call after a turn ended. Never throws: a failure leaves the log as it was.
     *
     * @return the number of calls rewritten by this call (0 while the batch is still filling)
     */
    public int afterTurn(String chatId) {
        try {
            return prune(chatId);
        } catch (RuntimeException e) {
            log.warn("Noise pruning of chat {} failed: {}", chatId, e.getMessage());
            return 0;
        }
    }

    private int prune(String chatId) {
        ArrayNode messages = sessions.messages(sessions.get(chatId));
        Map<String, JsonNode> results = new LinkedHashMap<>();
        for (JsonNode m : messages) {
            if (!m.path("content").isArray()) continue;
            for (JsonNode b : m.path("content")) {
                if ("tool_result".equals(b.path("type").asText())) results.put(b.path("tool_use_id").asText(), b);
            }
        }
        Set<String> seen = decided.computeIfAbsent(chatId, k -> new HashSet<>());
        Map<String, Integer> batch = pending.computeIfAbsent(chatId, k -> new LinkedHashMap<>());
        for (JsonNode m : messages) {
            if (!"assistant".equals(m.path("role").asText()) || !m.path("content").isArray()) continue;
            for (JsonNode b : m.path("content")) {
                if (!"tool_use".equals(b.path("type").asText()) || !"bash".equals(b.path("name").asText())) continue;
                String id = b.path("id").asText();
                if (seen.contains(id)) continue;
                JsonNode result = results.get(id);
                if (result == null) continue; // not answered (yet): look again after the next turn
                seen.add(id);
                String command = b.path("input").path("command").asText("");
                if (!qualifies(command, result)) continue;
                var decision = qualifier.decide(command, u -> sessions.addQualifierUsage(chatId, u));
                if (decision == NoiseQualifier.Decision.DROP) {
                    batch.put(id, savedTokens(command, result));
                }
            }
        }
        int saved = batch.values().stream().mapToInt(Integer::intValue).sum();
        if (batch.isEmpty() || saved < minSavedTokens) return 0;
        return apply(chatId, messages, new ArrayList<>(batch.keySet()), batch);
    }

    /** Exit code 0, no timeout, short output, not a stub already. */
    static boolean qualifies(String command, JsonNode result) {
        if (command.isBlank() || command.length() >= NoiseQualifier.MAX_COMMAND_CHARS) return false;
        if (result.path("is_error").asBoolean(false)) return false;
        String out = result.path("content").asText("");
        if (!out.startsWith("exit code: 0") || out.startsWith("exit code: 0 (timed out)")) return false;
        return out.length() < MAX_OUTPUT_CHARS;
    }

    private static int savedTokens(String command, JsonNode result) {
        int chars = Math.max(0, command.length() - STUB_COMMAND_CHARS)
                + Math.max(0, result.path("content").asText("").length() - STUB_RESULT.length());
        return Math.max(1, chars / 4);
    }

    /** Rewrites the calls to stubs and saves the log with the originals as reference lines. */
    private int apply(String chatId, ArrayNode messages, List<String> ids, Map<String, Integer> batch) {
        Set<String> wanted = new HashSet<>(ids);
        Map<String, ObjectNode> originals = new LinkedHashMap<>();
        for (JsonNode m : messages) {
            if (!m.path("content").isArray()) continue;
            for (JsonNode b : m.path("content")) {
                String type = b.path("type").asText();
                if ("tool_use".equals(type) && wanted.contains(b.path("id").asText())) {
                    ObjectNode ref = originals.computeIfAbsent(b.path("id").asText(), this::reference);
                    ((ObjectNode) ref.get("original")).set("tool_use", b.deepCopy());
                    String command = b.path("input").path("command").asText("");
                    ObjectNode input = mapper.createObjectNode();
                    input.put("command", command.length() > STUB_COMMAND_CHARS
                            ? command.substring(0, STUB_COMMAND_CHARS) + "…" : command);
                    ((ObjectNode) b).set("input", input);
                } else if ("tool_result".equals(type) && wanted.contains(b.path("tool_use_id").asText())) {
                    ObjectNode ref = originals.computeIfAbsent(b.path("tool_use_id").asText(), this::reference);
                    ((ObjectNode) ref.get("original")).set("tool_result", b.deepCopy());
                    ((ObjectNode) b).put("content", STUB_RESULT);
                    ((ObjectNode) b).remove("is_error");
                }
            }
        }
        sessions.saveMessages(chatId, messages, new ArrayList<>(originals.values()));
        batch.keySet().removeAll(wanted);
        return originals.size();
    }

    private ObjectNode reference(String id) {
        ObjectNode r = mapper.createObjectNode();
        r.put("ignore", true); // first, so the line starts with {"ignore":true
        r.put("replaces", id);
        r.putObject("original");
        return r;
    }
}
