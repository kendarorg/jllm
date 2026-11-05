package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.List;

/**
 * @param pinnedSystem stable first system block (chatId+agentId); never rewritten across turns/compaction
 * @param extraSystem  second system block (policy prompt); may be empty
 * @param toolChoice   null/"auto" (default), "any" (must call some tool), "none", or a specific tool name
 * @param disableParallelToolUse when true the model is asked to emit at most one tool call per turn
 * @param temperature  sampling temperature; null = not sent. The Anthropic client never sends it (Claude 5 models
 *                     and the current API version reject it)
 * @param thinkingLevel reasoning effort; null = the client's own default (Anthropic: its configured budget;
 *                     OpenAI-compatible: nothing sent)
 */
public record ModelRequest(String pinnedSystem, String extraSystem, ArrayNode messages,
                           List<HarnessTool> tools, int maxTokens,
                           String toolChoice, boolean disableParallelToolUse, Double temperature,
                           ThinkingLevel thinkingLevel) {

    public ModelRequest(String pinnedSystem, String extraSystem, ArrayNode messages,
                        List<HarnessTool> tools, int maxTokens,
                        String toolChoice, boolean disableParallelToolUse, Double temperature) {
        this(pinnedSystem, extraSystem, messages, tools, maxTokens, toolChoice, disableParallelToolUse, temperature,
                null);
    }

    public ModelRequest(String pinnedSystem, String extraSystem, ArrayNode messages,
                        List<HarnessTool> tools, int maxTokens,
                        String toolChoice, boolean disableParallelToolUse) {
        this(pinnedSystem, extraSystem, messages, tools, maxTokens, toolChoice, disableParallelToolUse, null, null);
    }

    public ModelRequest(String pinnedSystem, String extraSystem, ArrayNode messages,
                        List<HarnessTool> tools, int maxTokens) {
        this(pinnedSystem, extraSystem, messages, tools, maxTokens, null, false, null, null);
    }

    public ModelRequest withTemperature(Double t) {
        return new ModelRequest(pinnedSystem, extraSystem, messages, tools, maxTokens, toolChoice,
                disableParallelToolUse, t, thinkingLevel);
    }

    public ModelRequest withThinkingLevel(ThinkingLevel level) {
        return new ModelRequest(pinnedSystem, extraSystem, messages, tools, maxTokens, toolChoice,
                disableParallelToolUse, temperature, level);
    }
}
