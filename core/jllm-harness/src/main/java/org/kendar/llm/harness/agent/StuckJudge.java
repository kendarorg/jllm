package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.llm.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Second opinion on a stuck detection ({@code loop.stuck.confirm}): the fast model reads the last steps of the agent
 * and may judge them progress. Any failure, timeout or unclear answer leaves the detection standing.
 */
public final class StuckJudge {
    private static final Logger log = LoggerFactory.getLogger(StuckJudge.class);
    private static final String SYSTEM = Prompts.load("stuck-confirm-system.md");
    /** Messages from the end of the log the model reads. */
    static final int TAIL = 12;
    private static final int BLOCK_CHARS = 400;
    private static final int TOTAL_CHARS = 8000;

    private final ModelClient model;
    private final ObjectMapper mapper;
    private final long timeoutSeconds;

    public StuckJudge(ModelClient fastModel, ObjectMapper mapper) {
        this(fastModel, mapper, 20);
    }

    public StuckJudge(ModelClient fastModel, ObjectMapper mapper, long timeoutSeconds) {
        this.model = fastModel;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** The verdict and the model's reason; {@code progress} false when the detection stands. */
    public record Verdict(boolean progress, String reason) {
        static final Verdict STANDS = new Verdict(false, "");
    }

    /**
     * @param what     the detector's sentence
     * @param messages the run's message log
     */
    Verdict judge(String what, JsonNode messages) {
        String prompt = "Detector: " + what + "\n\nLast steps:\n" + transcript(messages);
        try {
            String answer = CompletableFuture.supplyAsync(() -> model.simpleComplete(SYSTEM, prompt, 120))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            return parse(answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.STANDS;
        } catch (Exception e) { // boundary: confirmation boundary: the detection stands
            log.warn("Stuck confirmation failed: {}", e.getMessage());
            return Verdict.STANDS;
        }
    }

    Verdict parse(String answer) {
        try {
            int a = answer.indexOf('{'), b = answer.lastIndexOf('}');
            JsonNode n = mapper.readTree(answer.substring(a, b + 1));
            boolean progress = "progress".equalsIgnoreCase(n.path("verdict").asText(""));
            return new Verdict(progress, n.path("reason").asText(""));
        } catch (Exception e) { // boundary: confirmation boundary: the detection stands
            log.debug("Stuck confirmation reply not usable: {}", e.toString());
            return Verdict.STANDS;
        }
    }

    /** The last {@link #TAIL} messages, one line per block, clipped. */
    static String transcript(JsonNode messages) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, messages.size() - TAIL);
        for (int i = from; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            String role = m.path("role").asText();
            JsonNode content = m.path("content");
            if (content.isTextual()) {
                sb.append(role).append(": ").append(clip(content.asText())).append('\n');
                continue;
            }
            for (JsonNode b : content) {
                switch (b.path("type").asText()) {
                    case "text" -> sb.append(role).append(": ").append(clip(b.path("text").asText())).append('\n');
                    case "tool_use" -> sb.append("call ").append(b.path("name").asText()).append(' ')
                            .append(clip(b.path("input").toString())).append('\n');
                    case "tool_result" -> sb.append(b.path("is_error").asBoolean(false) ? "error: " : "result: ")
                            .append(clip(resultText(b.path("content")))).append('\n');
                    default -> { }
                }
            }
        }
        return sb.length() > TOTAL_CHARS ? sb.substring(sb.length() - TOTAL_CHARS) : sb.toString();
    }

    private static String resultText(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : content) sb.append(p.path("text").asText(""));
        return sb.toString();
    }

    private static String clip(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= BLOCK_CHARS ? one : one.substring(0, BLOCK_CHARS) + "...";
    }
}
