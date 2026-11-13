package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;

/** The tools a plugin may call on behalf of the calling agent. Obtained from {@code PluginRegistrar.catalog()}. */
public interface ToolCatalog {

    /** The tools offered to the calling agent at the moment of the call, the same list its model gets. */
    Collection<HarnessTool> tools(ToolContext ctx);

    /** The context for the file tools of a script, see {@link RunScope#jail}. */
    ToolContext jail(ToolContext ctx);

    /** The path as the model's own file tools accept it. */
    String display(ToolContext ctx, java.nio.file.Path path);

    /** Whether {@code name} is a built-in file tool, which a script calls without a prompt. */
    boolean free(ToolContext ctx, String name);

    /**
     * Runs one tool through input validation, {@code PRE_TOOL} hooks, the permission decision, execution,
     * {@code POST_TOOL} hooks and taint marking, and returns the raw output (no sanitizing, no truncation).
     *
     * @param ctx context of the calling tool; its {@code toolUseId} names the parent call
     * @throws ToolCallException when the call is refused or fails
     */
    String invoke(ToolContext ctx, String name, JsonNode input);

    /** An inner call that was refused or failed; the message is the tool's. */
    class ToolCallException extends RuntimeException {
        public ToolCallException(String message) {
            super(message);
        }
    }
}
