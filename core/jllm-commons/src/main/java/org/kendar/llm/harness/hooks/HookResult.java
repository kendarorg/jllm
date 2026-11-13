package org.kendar.llm.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outcome of a hook.
 *
 * @param denyReason    non-null blocks the prompt or the tool call
 * @param modifiedInput PRE_TOOL: replaces the tool input
 * @param addedContext  text appended to the user message (USER_PROMPT) or the tool result (PRE_TOOL, POST_TOOL)
 * @param continueWith  STOP: do not stop; the agent keeps working with this message (e.g. the failing test output)
 */
public record HookResult(String denyReason, JsonNode modifiedInput, String addedContext, String continueWith) {

    private static final HookResult ALLOW = new HookResult(null, null, null, null);

    public static HookResult allow() {
        return ALLOW;
    }

    public static HookResult deny(String reason) {
        return new HookResult(reason == null || reason.isBlank() ? "blocked by a hook" : reason, null, null, null);
    }

    public static HookResult modifyInput(JsonNode input) {
        return new HookResult(null, input, null, null);
    }

    public static HookResult addContext(String text) {
        return new HookResult(null, null, text, null);
    }

    /** STOP: veto the stop; the agent is told {@code message} and keeps working. */
    public static HookResult continueWith(String message) {
        return new HookResult(null, null, null,
                message == null || message.isBlank() ? "A stop check failed." : message);
    }

    public boolean denied() {
        return denyReason != null;
    }

    /** True when a STOP hook asked the agent to keep working. */
    public boolean continues() {
        return continueWith != null;
    }
}
