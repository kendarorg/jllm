package org.kendar.llm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.llm.Usage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The headless result of a {@code --prompt} run: built from the run's events, mapped to the process exit code and
 * written as the {@code --json-result} file.
 * <pre>
 * {"chatId", "outcome", "turns", "durationMs", "usage": {"input", "output", "cacheRead", "cacheCreation"},
 *  "costUsd", "toolCalls": [{"name", "input", "isError"}], "dangerouslySkipPermissions",
 *  "denied": [{"tool", "reason"}], "deniedCount", "error"}
 * </pre>
 */
final class RunReport {

    static final int EXIT_COMPLETED = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_STUCK = 2;
    static final int EXIT_INCOMPLETE = 3;
    static final int EXIT_CANCELLED = 4;
    static final int EXIT_USAGE = 64;

    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";

    private final long startMillis;
    private final long deadlineMillis;
    private final long timeoutSeconds;
    private final Map<String, ObjectNode> toolCalls = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private String chatId;
    private String outcome;
    private int turns;
    private String error;
    private boolean timedOut;
    private boolean skipPermissions;
    private final java.util.List<ObjectNode> denied = new java.util.ArrayList<>();

    /**
     * @param timeoutSeconds {@code --timeout}; 0: none
     */
    RunReport(ObjectMapper mapper, long startMillis, long timeoutSeconds) {
        this.mapper = mapper;
        this.startMillis = startMillis;
        this.timeoutSeconds = timeoutSeconds;
        this.deadlineMillis = timeoutSeconds > 0 ? startMillis + timeoutSeconds * 1000 : Long.MAX_VALUE;
    }

    /** The exit code of an outcome of {@code run_finished}; null or unknown: failed. */
    static int exitCode(String outcome) {
        if (outcome == null) return EXIT_FAILED;
        return switch (outcome) {
            case "completed" -> EXIT_COMPLETED;
            case "stuck" -> EXIT_STUCK;
            case "max_turns", "truncated", "unverified" -> EXIT_INCOMPLETE;
            case "cancelled" -> EXIT_CANCELLED;
            default -> EXIT_FAILED; // failed, refused
        };
    }

    int exitCode() {
        return exitCode(outcome());
    }

    /** Records that {@code --dangerously-skip-permissions} was on for the run. */
    void skipPermissions(boolean on) {
        this.skipPermissions = on;
    }

    /** The calls and outputs denied because nobody could approve them, in order: {@code {tool, reason}}. */
    java.util.List<ObjectNode> denied() {
        return java.util.List.copyOf(denied);
    }

    void chatId(String chatId) {
        this.chatId = chatId;
    }

    /** How the run ended: the last {@code run_finished}, {@code cancelled} after a timeout, else failed. */
    String outcome() {
        if (timedOut) return CANCELLED;
        return outcome == null ? FAILED : outcome;
    }

    void fail(String message) {
        error = message;
    }

    /** Whether the {@code --timeout} has passed; true once, the caller then cancels the run. */
    boolean expire(long nowMillis) {
        if (timedOut || nowMillis < deadlineMillis) return false;
        timedOut = true;
        error = "timeout after " + timeoutSeconds + " s";
        return true;
    }

    /** Whether the run ignored the cancel of a timeout for longer than {@code graceMillis}. */
    boolean overdue(long nowMillis, long graceMillis) {
        return timedOut && nowMillis - deadlineMillis > graceMillis;
    }

    /** Takes what the result needs from one event of the chat. */
    void observe(AgentEvent ev) {
        JsonNode d = ev.data();
        switch (ev.type()) {
            case "usage" -> turns++;
            case "run_finished" -> outcome = d.path("outcome").asText(FAILED);
            case "error" -> {
                if (!timedOut) error = d.path("message").asText("error");
            }
            case "permission_denied" -> denied.add(mapper.createObjectNode().put("tool", d.path("tool").asText())
                    .put("reason", d.path("reason").asText("unattended")));
            case "tool_call" -> {
                ObjectNode c = mapper.createObjectNode().put("name", d.path("name").asText());
                c.set("input", d.path("input").deepCopy());
                c.put("isError", true); // until its result says otherwise: a call without a result did not succeed
                toolCalls.put(d.path("id").asText(), c);
            }
            case "tool_result" -> {
                ObjectNode c = toolCalls.get(d.path("id").asText());
                if (c != null) c.put("isError", d.path("isError").asBoolean(false));
            }
            default -> {
            }
        }
    }

    /**
     * @param used tokens of this run
     * @param cost estimated USD of {@code used}
     */
    ObjectNode toJson(Usage used, double cost, long nowMillis) {
        ObjectNode r = mapper.createObjectNode();
        r.put("chatId", chatId);
        r.put("outcome", outcome());
        r.put("turns", turns);
        r.put("durationMs", Math.max(0, nowMillis - startMillis));
        ObjectNode u = r.putObject("usage");
        u.put("input", used.inputTokens());
        u.put("output", used.outputTokens());
        u.put("cacheRead", used.cacheReadTokens());
        u.put("cacheCreation", used.cacheCreationTokens());
        r.put("costUsd", cost);
        ArrayNode calls = r.putArray("toolCalls");
        toolCalls.values().forEach(calls::add);
        r.put("dangerouslySkipPermissions", skipPermissions);
        ArrayNode den = r.putArray("denied");
        denied.forEach(den::add);
        r.put("deniedCount", denied.size());
        r.put("error", error);
        return r;
    }

    void write(Path file, Usage used, double cost) throws IOException {
        Path abs = file.toAbsolutePath();
        if (abs.getParent() != null) Files.createDirectories(abs.getParent());
        Files.writeString(abs, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(toJson(used, cost, System.currentTimeMillis())), StandardCharsets.UTF_8);
    }
}
