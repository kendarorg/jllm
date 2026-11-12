package org.kendar.llm.harness.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;

/** The catalog of a harness: delegates to the {@link RunScope} of the calling run carried by the context. */
public final class ScopeToolCatalog implements ToolCatalog {

    private static RunScope scope(ToolContext ctx) {
        if (ctx == null || ctx.scope() == null) {
            throw new IllegalStateException("No agent run behind this context: tools can only be called from a tool "
                    + "executed by an agent");
        }
        return ctx.scope();
    }

    @Override
    public Collection<HarnessTool> tools(ToolContext ctx) {
        return ctx == null || ctx.scope() == null ? List.of() : ctx.scope().tools();
    }

    @Override
    public ToolContext jail(ToolContext ctx) {
        return scope(ctx).jail(ctx);
    }

    @Override
    public String display(ToolContext ctx, java.nio.file.Path path) {
        return scope(ctx).display(path);
    }

    @Override
    public boolean free(ToolContext ctx, String name) {
        return scope(ctx).free(name);
    }

    @Override
    public String invoke(ToolContext ctx, String name, JsonNode input) {
        return scope(ctx).invoke(ctx, name, input);
    }
}
