package org.kendar.llm.harness.integration;

/** Tool names must match {@code [a-zA-Z0-9_-]{1,64}} to be accepted by the model APIs. */
final class ToolNames {

    private ToolNames() {
    }

    static String of(String raw) {
        String n = raw.replaceAll("[^a-zA-Z0-9_-]", "_");
        return n.length() <= 64 ? n : n.substring(0, 64);
    }
}
