package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.PermissionMode;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One run of the model loop for an agent.
 *
 * @param toolSource  re-read every turn, so tool groups enabled mid-run apply to the next model call
 * @param pinned      pinned text at the start of the request prefix
 * @param extra       system text after the pinned prefix (the mode note is added to it)
 * @param persist     whether the messages are the chat's own log (saved after each step); false for a sub-agent
 * @param agent       agent name, as hooks see it
 * @param client      model of this agent
 * @param inherited   [0] = leading messages that are a copy of the parent's context (0 for the main agent)
 * @param label       names a sub-agent in its approval requests; null for the main agent
 * @param inbox       messages typed while the run is active; null = none
 * @param frame       request prefix of a sub-agent that continues its parent's (context stack); null otherwise
 */
record LoopRequest(String chatId, ArrayNode messages, Supplier<List<HarnessTool>> toolSource, String pinned,
                   String extra, PermissionMode mode, EventSink sink, BooleanSupplier cancelled, boolean persist,
                   boolean interactive, String agent, ModelClient client, int[] inherited, String label,
                   AgentLoop.Inbox inbox, StackFrame frame) {
}
