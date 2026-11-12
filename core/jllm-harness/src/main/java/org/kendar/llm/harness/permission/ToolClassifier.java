package org.kendar.llm.harness.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.llm.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asks the fast model whether a tool call is safe to run in auto mode. It sees only the tool name, its input, the
 * working directory and what the user asked for, never tool output, so tool results cannot talk to it. Fails closed:
 * an error, timeout, unreadable answer or low confidence is {@link Verdict#UNSURE} and the caller asks the user.
 */
public class ToolClassifier {

    public enum Verdict { SAFE, UNSAFE, UNSURE }

    private static final Logger log = LoggerFactory.getLogger(ToolClassifier.class);
    private static final String SYSTEM = Prompts.load("tool-classifier-system.md");

    private final ModelClient model;
    private final ObjectMapper mapper;
    private final double minConfidence;
    private final long timeoutSeconds;

    public ToolClassifier(ModelClient fastModel, ObjectMapper mapper) {
        this(fastModel, mapper, 0.8, 20);
    }

    public ToolClassifier(ModelClient fastModel, ObjectMapper mapper, double minConfidence, long timeoutSeconds) {
        this.model = fastModel;
        this.mapper = mapper;
        this.minConfidence = minConfidence;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Verdict classify(String tool, JsonNode input, Path cwd, String userIntent) {
        String prompt = "Working directory: " + cwd + "\nUser request: " + clip(userIntent, 1000)
                + "\nTool: " + tool + "\nInput: " + clip(String.valueOf(input), 4000);
        try {
            String answer = CompletableFuture.supplyAsync(() -> model.simpleComplete(SYSTEM, prompt, 60))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            return parse(answer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.UNSURE;
        } catch (Exception e) {
            log.warn("Classifier failed for {}: {}", tool, e.getMessage());
            return Verdict.UNSURE;
        }
    }

    Verdict parse(String answer) {
        try {
            int a = answer.indexOf('{'), b = answer.lastIndexOf('}');
            JsonNode n = mapper.readTree(answer.substring(a, b + 1));
            if (n.path("confidence").asDouble(0) < minConfidence) return Verdict.UNSURE;
            return switch (n.path("verdict").asText("").toLowerCase()) {
                case "safe" -> Verdict.SAFE;
                case "unsafe" -> Verdict.UNSAFE;
                default -> Verdict.UNSURE;
            };
        } catch (Exception e) {
            return Verdict.UNSURE;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
