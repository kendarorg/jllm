package org.kendar.llm.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of a hook.
 *
 * @param denyReason   non-null blocks the prompt or the tool call
 * @param modifiedInput PRE_TOOL: replaces the tool input
 * @param addedContext text appended to the user message (USER_PROMPT) or the tool result (PRE_TOOL, POST_TOOL)
 */
public record HookResult(String denyReason, JsonNode modifiedInput, String addedContext) {

    private static final HookResult ALLOW = new HookResult(null, null, null);

    public static HookResult allow() {
        return ALLOW;
    }

    public static HookResult deny(String reason) {
        return new HookResult(reason == null || reason.isBlank() ? "blocked by a hook" : reason, null, null);
    }

    public static HookResult modifyInput(JsonNode input) {
        return new HookResult(null, input, null);
    }

    public static HookResult addContext(String text) {
        return new HookResult(null, null, text);
    }

    public boolean denied() {
        return denyReason != null;
    }
}
