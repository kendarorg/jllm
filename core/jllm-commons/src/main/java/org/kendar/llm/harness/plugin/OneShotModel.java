package org.kendar.llm.harness.plugin;

import java.util.List;
import java.util.Set;

/** A single model call for a named agent, published by the host so plugins can borrow the agent loop for one task. */
@FunctionalInterface
public interface OneShotModel {
    String complete(Call call) throws Exception;

    /**
     * @param principal the user the work is done for; the task chat belongs to them and shows in their chat list
     * @param title     names the task chat; calls with the same principal and title share one chat
     * @param approved  tools the user already allowed for this chat, so a call to them needs no question
     */
    record Call(String agent, String system, String user, String principal, String title, Set<String> approved) {
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
