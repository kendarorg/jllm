package org.kendar.llm.harness.agent;

import org.kendar.llm.harness.Prompts;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The sub-agent types the {@code task} tool can start. Built-ins: general, explore, plan. */
public final class AgentCatalog {

    public static final String DEFAULT = "general";

    private final Map<String, AgentDefinition> byName = new LinkedHashMap<>();

    /** @throws IllegalArgumentException if a name is used twice, or clashes with a built-in */
    public AgentCatalog(Collection<AgentDefinition> custom) {
        add(new AgentDefinition(DEFAULT, "Carries out an independent task with its own context", List.of(), ""));
        add(new AgentDefinition("explore", "Fast search through files and code", List.of("read_file", "glob", "grep"),
                Prompts.load("agent-explore.md"), false, null, true, 0.2));
        add(new AgentDefinition("plan", "Designs an implementation plan without changing anything", List.of(),
                Prompts.load("agent-plan.md"), false, null, true, 0.3));
        custom.forEach(this::add);
    }

    private void add(AgentDefinition d) {
        if (byName.putIfAbsent(d.name(), d) != null) {
            throw new IllegalArgumentException("Duplicate agent type '" + d.name() + "'");
        }
    }

    public AgentDefinition get(String name) {
        return byName.get(name);
    }

    public Collection<AgentDefinition> all() {
        return byName.values();
    }
}
