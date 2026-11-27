package org.kendar.llm.harness.plugin;

import java.util.List;
import java.util.Set;

/** A single model call for a named agent, published by the host so plugins can borrow the agent loop for one task. */
public interface OneShotModel {
    String complete(Call call) throws Exception;

    /** One short plain answer to a closed question, from the host's cheap model (the main model when there is none). */
    String classify(String system, String user) throws Exception;

    /**
     * @param principal the user the work is done for; the task chat belongs to them and shows in their chat list
     * @param title     names the task chat; calls with the same principal and title share one chat
     * @param approved  tools the user already allowed for this chat, so a call to them needs no question
     * @param tools     tools the task may use: null = those of the agent; empty = none (one plain completion);
     *                  {@code ["*"]} = every tool the user's role allows; otherwise those names, still limited by the role
     * @param unattended nobody can approve anything in this task: a tool call that would ask is denied, the model gets
     *                  the denial as the tool result and the task goes on (otherwise the task stops with an
     *                  {@link ApprovalRequiredException})
     * @param onDenied  receives the tool of each call denied in an unattended task (for the flow journal); may be null
     */
    record Call(String agent, String system, String user, String principal, String title, Set<String> approved,
                List<String> tools, boolean unattended, java.util.function.Consumer<String> onDenied) {
    }

    /** Tool calls of the agent needed an approval and the task could not ask, so it stopped without running them. */
    final class ApprovalRequiredException extends RuntimeException {
        private final List<String> tools;

        public ApprovalRequiredException(String message, List<String> tools) {
            super(message);
            this.tools = List.copyOf(tools);
        }

        /** The tools that need an approval, in the order they were called. */
        public List<String> tools() {
            return tools;
        }
    }
}
