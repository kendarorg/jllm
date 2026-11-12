package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Provider-neutral reasoning effort. Each client maps it to its own parameter: Anthropic to an extended-thinking
 * token budget ({@link #MINIMAL} = off), OpenAI-compatible endpoints (OpenAI, Ollama, ...) to {@code reasoning_effort}.
 * Precedence, highest first: the request, the agent definition, the provider default.
 */
public enum ThinkingLevel {
    MINIMAL(0), LOW(2048), MEDIUM(8192), HIGH(16384);

    private final int anthropicBudget;

    ThinkingLevel(int anthropicBudget) {
        this.anthropicBudget = anthropicBudget;
    }

    /** Extended-thinking budget in tokens for Anthropic; 0 = thinking off. */
    public int anthropicBudget() {
        return anthropicBudget;
    }

    /** The {@code reasoning_effort} value for an OpenAI-compatible endpoint of the given provider type. */
    public String reasoningEffort(String providerType) {
        if (this == MINIMAL) return "ollama".equals(providerType) ? "none" : "minimal";
        return name().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive; null/blank = null (not set). */
    @JsonCreator
    public static ThinkingLevel parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown thinking level '" + s + "' (expected minimal | low | medium | high)");
        }
    }
}
