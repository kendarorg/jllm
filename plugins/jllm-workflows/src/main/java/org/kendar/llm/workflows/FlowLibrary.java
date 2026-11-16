package org.kendar.llm.workflows;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the flows a parent may start as children. Scopes are separate namespaces: a personal flow may call global
 * flows and the user's own; a global flow may call only global flows, so it works for every user it is shared with.
 */
public interface FlowLibrary {
    String GLOBAL = "global", PERSONAL = "personal";

    /** @return the flow, or empty when it does not exist or {@code user} may not run it */
    Optional<FlowDef> find(String scope, String name, String user);

    static FlowLibrary empty() {
        return (scope, name, user) -> Optional.empty();
    }

    /** Simple in-memory library for tests and single-process use. */
    final class Memory implements FlowLibrary {
        private final Map<String, FlowDef> flows = new HashMap<>();

        public Memory global(FlowDef f) {
            flows.put(GLOBAL + "/" + f.name(), f);
            return this;
        }

        public Memory personal(String user, FlowDef f) {
            flows.put(PERSONAL + "/" + user + "/" + f.name(), f);
            return this;
        }

        @Override
        public Optional<FlowDef> find(String scope, String name, String user) {
            return Optional.ofNullable(GLOBAL.equals(scope) ? flows.get(GLOBAL + "/" + name)
                    : flows.get(PERSONAL + "/" + user + "/" + name));
        }
    }

    /**
     * Save-time checks for declared children: they exist, are allowed for the scope, never form a loop (a flow may
     * not call itself, directly or through children) and do not obviously exceed the flow's own depth limit.
     * Children chosen at run time are checked when they start.
     */
    static List<String> checkChildren(FlowDef flow, FlowLibrary lib, String user) {
        List<String> errs = new ArrayList<>();
        int depth = walk(flow, lib, user, new ArrayList<>(List.of(key(flow))), errs);
        int limit = flow.limit("depth", null, FlowDef.DEFAULT_DEPTH);
        if (depth > limit) errs.add("declared children nest " + depth + " levels deep; the depth limit is " + limit);
        return errs;
    }

    private static String key(FlowDef f) {
        return f.scope() + "/" + f.name();
    }

    private static int walk(FlowDef flow, FlowLibrary lib, String user, List<String> chain, List<String> errs) {
        int deepest = 1;
        for (Iterator<Map.Entry<String, JsonNode>> it = flow.steps().fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode s = e.getValue();
            if (!"call".equals(s.path("type").asText())) continue;
            List<String> names = new ArrayList<>();
            if (s.has("flow")) names.add(s.get("flow").asText());
            else s.path("allowed").forEach(n -> names.add(n.asText()));
            String scope = s.path("scope").asText(PERSONAL);
            for (String name : names) {
                String where = "step '" + e.getKey() + "': ";
                if (GLOBAL.equals(flow.scope()) && PERSONAL.equals(scope)) {
                    errs.add(where + "a global flow cannot call the personal flow '" + name + "'");
                    continue;
                }
                var child = lib.find(scope, name, user);
                if (child.isEmpty()) {
                    errs.add(where + "child flow '" + name + "' (" + scope + ") does not exist or is not allowed");
                    continue;
                }
                String k = scope + "/" + name;
                if (chain.contains(k)) {
                    errs.add(where + "child flow '" + name + "' would call itself (" + chain + ")");
                    continue;
                }
                chain.add(k);
                deepest = Math.max(deepest, 1 + walk(child.get(), lib, user, chain, errs));
                chain.remove(chain.size() - 1);
            }
        }
        return deepest;
    }
}
