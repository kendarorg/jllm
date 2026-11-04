package org.kendar.llm.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a hook sees. Fields that do not apply to the event are null.
 *
 * @param text    USER_PROMPT: the message; STOP: the final answer
 * @param tool    PRE_TOOL, POST_TOOL: tool name
 * @param input   PRE_TOOL, POST_TOOL: tool input
 * @param output  POST_TOOL: tool output
 * @param isError POST_TOOL, POST_LLM, POST_AGENT: the tool, call or agent failed
 * @param agent   PRE_LLM, POST_LLM, PRE_AGENT, POST_AGENT: {@value #MAIN} or the sub-agent type name
 */
public record HookContext(HookEvent event, String chatId, String text, String tool, JsonNode input, String output,
                          boolean isError, String agent) {

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

    public static HookContext prompt(String chatId, String text) {
        return new HookContext(HookEvent.USER_PROMPT, chatId, text, null, null, null, false);
    }

    public static HookContext preTool(String chatId, String tool, JsonNode input) {
        return new HookContext(HookEvent.PRE_TOOL, chatId, null, tool, input, null, false);
    }

    public static HookContext postTool(String chatId, String tool, JsonNode input, String output, boolean isError) {
        return new HookContext(HookEvent.POST_TOOL, chatId, null, tool, input, output, isError);
    }

    public static HookContext stop(String chatId, String text) {
        return new HookContext(HookEvent.STOP, chatId, text, null, null, null, false);
    }
}
