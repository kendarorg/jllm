package org.kendar.llm.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.llm.Usage;

/**
 * What a hook sees. Fields that do not apply to the event are null.
 *
 * @param text    USER_PROMPT: the message; STOP: the final answer
 * @param tool    PRE_TOOL, POST_TOOL: tool name
 * @param input   PRE_TOOL, POST_TOOL: tool input
 * @param output  POST_TOOL: tool output
 * @param isError POST_TOOL, POST_LLM, POST_AGENT: the tool, call or agent failed
 * @param agent   PRE_LLM, POST_LLM, PRE_AGENT, POST_AGENT: {@value #MAIN} or the sub-agent type name
 * @param durationMs POST_LLM, POST_TOOL: how long the call took; 0 when not measured (tracing adapters)
 * @param usage   POST_LLM: tokens of the call, or null
 * @param parent  PRE_TOOL, POST_TOOL: null for a call from the model; {@code run_js:<toolUseId>} for a call made by a
 *                script
 */
public record HookContext(HookEvent event, String chatId, String text, String tool, JsonNode input, String output,
                          boolean isError, String agent, long durationMs, Usage usage, String parent) {

    public HookContext(HookEvent event, String chatId, String text, String tool, JsonNode input, String output,
                       boolean isError, String agent, long durationMs, Usage usage) {
        this(event, chatId, text, tool, input, output, isError, agent, durationMs, usage, null);
    }

    public HookContext(HookEvent event, String chatId, String text, String tool, JsonNode input, String output,
                       boolean isError, String agent) {
        this(event, chatId, text, tool, input, output, isError, agent, 0L, null);
    }

    public static final String MAIN = "main";

    public HookContext(HookEvent event, String chatId, String text, String tool, JsonNode input, String output,
                       boolean isError) {
        this(event, chatId, text, tool, input, output, isError, null);
    }

    /** PRE_LLM, PRE_AGENT: {@code text} is the prompt of the agent, or null. */
    public static HookContext before(HookEvent event, String chatId, String agent, String text) {
        return new HookContext(event, chatId, text, null, null, null, false, agent);
    }

    /** POST_LLM: {@code text} is the model's text; POST_AGENT: the agent's answer or the failure message. */
    public static HookContext after(HookEvent event, String chatId, String agent, String text, boolean isError) {
        return new HookContext(event, chatId, text, null, null, null, isError, agent);
    }

    /** POST_LLM with the duration and the token usage of the call (usage null when the call failed). */
    public static HookContext afterLlm(String chatId, String agent, String text, boolean isError, long durationMs,
                                       Usage usage) {
        return new HookContext(HookEvent.POST_LLM, chatId, text, null, null, null, isError, agent, durationMs, usage);
    }

    public static HookContext prompt(String chatId, String text) {
        return new HookContext(HookEvent.USER_PROMPT, chatId, text, null, null, null, false);
    }

    public static HookContext preTool(String chatId, String tool, JsonNode input) {
        return new HookContext(HookEvent.PRE_TOOL, chatId, null, tool, input, null, false);
    }

    public static HookContext preTool(String chatId, String tool, JsonNode input, String parent) {
        return new HookContext(HookEvent.PRE_TOOL, chatId, null, tool, input, null, false, null, 0L, null, parent);
    }

    public static HookContext postTool(String chatId, String tool, JsonNode input, String output, boolean isError,
                                       long durationMs, String parent) {
        return new HookContext(HookEvent.POST_TOOL, chatId, null, tool, input, output, isError, null, durationMs, null,
                parent);
    }

    public static HookContext postTool(String chatId, String tool, JsonNode input, String output, boolean isError) {
        return new HookContext(HookEvent.POST_TOOL, chatId, null, tool, input, output, isError);
    }

    /** POST_TOOL with the duration of the call. */
    public static HookContext postTool(String chatId, String tool, JsonNode input, String output, boolean isError,
                                       long durationMs) {
        return new HookContext(HookEvent.POST_TOOL, chatId, null, tool, input, output, isError, null, durationMs, null);
    }

    public static HookContext stop(String chatId, String text) {
        return new HookContext(HookEvent.STOP, chatId, text, null, null, null, false);
    }
}
