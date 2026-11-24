package org.kendar.llm.harness.agent;

/** A sub-agent that is running now. */
public record ActiveSubAgent(String label, String chatId, String prompt, long startedAtMillis) {
}
