package org.kendar.llm.harness.context;

/** The placeholder that replaces an old tool result when the context is compacted. */
public final class ClearedResult {
    public static final String TEXT = "[tool result cleared to save context]";

    private ClearedResult() {
    }

    public static boolean is(String content) {
        return content != null && content.startsWith(TEXT);
    }
}
