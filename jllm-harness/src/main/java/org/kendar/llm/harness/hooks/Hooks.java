package org.kendar.llm.harness.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The hooks of a harness, run in registration order. */
public final class Hooks {

    private static final Logger log = LoggerFactory.getLogger(Hooks.class);
    public static final Hooks NONE = new Hooks();

    private final Map<HookEvent, List<Hook>> hooks = new EnumMap<>(HookEvent.class);

    public Hooks() {
        for (HookEvent e : HookEvent.values()) hooks.put(e, new CopyOnWriteArrayList<>());
    }

    public void add(HookEvent event, Hook hook) {
        if (this == NONE) throw new IllegalStateException("Hooks.NONE is read-only");
        hooks.get(event).add(hook);
    }

    /**
     * Runs the hooks of the event. The first denial wins and stops the chain; input rewrites are applied in
     * sequence (each hook sees the previous result); added context is concatenated.
     */
    public HookResult run(HookContext ctx) {
        List<Hook> list = hooks.get(ctx.event());
        if (list.isEmpty()) return HookResult.allow();
        JsonNode input = ctx.input();
        boolean modified = false;
        List<String> context = new ArrayList<>();
        for (Hook h : list) {
            HookResult r;
            try {
                r = h.run(new HookContext(ctx.event(), ctx.chatId(), ctx.text(), ctx.tool(), input, ctx.output(),
                        ctx.isError(), ctx.agent()));
            } catch (Exception e) {
                log.warn("Hook for {} failed and was ignored: {}", ctx.event(), e.toString());
                continue;
            }
            if (r == null) continue;
            if (r.denied()) return r;
            if (r.modifiedInput() != null) {
                input = r.modifiedInput();
                modified = true;
            }
            if (r.addedContext() != null && !r.addedContext().isBlank()) context.add(r.addedContext());
        }
        return new HookResult(null, modified ? input : null, context.isEmpty() ? null : String.join("\n", context));
    }
}
