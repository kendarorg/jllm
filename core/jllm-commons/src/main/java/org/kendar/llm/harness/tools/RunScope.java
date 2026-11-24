package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;

/** What one running agent offers to a tool that calls other tools (a script): its tool list and the invoke path. */
public interface RunScope {

    /** The tools the agent can call at this moment. */
    Collection<HarnessTool> tools();

    /**
     * The context a script's file tools run with: confined to the agent's own scratch directory when the work dir is
     * a project shared by every chat, else the chat's own work dir unchanged. Works on the host and, through the file
     * backend, in a container.
     */
    ToolContext jail(ToolContext ctx);

    /** The path as the model's own file tools accept it. */
    String display(java.nio.file.Path path);

    /**
     * Whether {@code name} is a built-in file tool, which a script calls in its jailed context. It also skips the
     * permission gate: always when read-only, and for the others only while the chat holds no untrusted output.
     */
    boolean free(String name);

    /** @see ToolCatalog#invoke */
    String invoke(ToolContext ctx, String name, JsonNode input);
}
