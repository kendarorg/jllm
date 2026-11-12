package org.kendar.llm.harness.tools;

import org.kendar.llm.harness.permission.ToolApprovalPolicy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tools of a harness. Tools are sorted by group, then name, so the order the model sees (and its prompt cache
 * prefix) does not depend on the order in which the tools were injected.
 * <p>
 * The core tools (standard plus injected) are fixed. External tools are added and removed per source (an MCP server,
 * a REST API) at runtime, and any tool can be disabled by name. Every change publishes a new immutable snapshot.
 */
public class HarnessToolRegistry {

    private static final Comparator<HarnessTool> ORDER = Comparator
            .comparing(HarnessTool::group, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(HarnessTool::name);

    private record Snapshot(Map<String, HarnessTool> byName, List<HarnessTool> sorted) {
    }

    private final List<HarnessTool> core;
    private final ToolApprovalPolicy policy;
    private final Map<String, List<HarnessTool>> sources = new LinkedHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final Set<String> replaced = ConcurrentHashMap.newKeySet();
    private final List<java.util.function.Consumer<Collection<String>>> nameChecks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot;
    private volatile java.util.function.Predicate<String> hidden = n -> false;

    /** @throws IllegalArgumentException on duplicate tool names */
    public HarnessToolRegistry(Collection<? extends HarnessTool> tools, ToolApprovalPolicy policy) {
        this.core = List.copyOf(tools);
        this.policy = policy;
        this.snapshot = build(core, sources);
        if (policy != null) snapshot.sorted().forEach(policy::registered);
    }

    public HarnessToolRegistry(Collection<? extends HarnessTool> tools) {
        this(tools, null);
    }

    /** Names of standard tools replaced by a plugin: a source cannot add them back. */
    public HarnessToolRegistry(Collection<? extends HarnessTool> tools, ToolApprovalPolicy policy,
                               Collection<String> replaced,
                               Collection<java.util.function.Consumer<Collection<String>>> nameChecks) {
        this.core = List.copyOf(tools);
        this.policy = policy;
        this.replaced.addAll(replaced);
        this.nameChecks.addAll(nameChecks);
        this.snapshot = build(core, sources);
        checkNames(snapshot);
        if (policy != null) snapshot.sorted().forEach(policy::registered);
    }

    private void checkNames(Snapshot s) {
        List<String> names = s.sorted().stream().map(HarnessTool::name).toList();
        for (var c : nameChecks) c.accept(names);
    }

    /** The enabled tools in the stable order. */
    public Collection<HarnessTool> all() {
        List<HarnessTool> sorted = snapshot.sorted();
        return sorted.stream().filter(t -> !disabled.contains(t.name()) && !hidden.test(t.name())).toList();
    }

    /** Tools matching the predicate are never offered (deny rules without argument pattern). */
    public void hideIf(java.util.function.Predicate<String> hidden) {
        this.hidden = hidden;
    }

    /** Every tool, enabled or not, in the stable order. */
    public Collection<HarnessTool> every() {
        return snapshot.sorted();
    }

    public HarnessTool get(String name) {
        return snapshot.byName().get(name);
    }

    public boolean isEnabled(String name) {
        return !disabled.contains(name);
    }

    public void enable(Collection<String> names) {
        disabled.removeAll(names);
    }

    public void disable(Collection<String> names) {
        disabled.addAll(names);
    }

    /**
     * Adds or replaces the tools of an external source.
     *
     * @throws IllegalArgumentException if a name clashes with another tool; nothing is changed then
     */
    public synchronized void setSource(String source, Collection<? extends HarnessTool> tools) {
        for (HarnessTool t : tools) {
            if (replaced.contains(t.name())) {
                throw new IllegalStateException("Tool '" + t.name() + "' is replaced by a plugin and cannot be added "
                        + "(source '" + source + "'): run_js replaces bash.");
            }
        }
        Map<String, List<HarnessTool>> next = new LinkedHashMap<>(sources);
        next.put(source, List.copyOf(tools));
        Snapshot built = build(core, next);
        checkNames(built);
        sources.clear();
        sources.putAll(next);
        snapshot = built;
        if (policy != null) tools.forEach(policy::registered);
    }

    public synchronized void removeSource(String source) {
        if (sources.remove(source) == null) return;
        snapshot = build(core, sources);
    }

    private static Snapshot build(List<HarnessTool> core, Map<String, List<HarnessTool>> sources) {
        Map<String, HarnessTool> map = new LinkedHashMap<>();
        List<HarnessTool> all = new ArrayList<>(core);
        sources.values().forEach(all::addAll);
        for (HarnessTool tool : all) {
            if (map.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name '" + tool.name() + "'");
            }
        }
        List<HarnessTool> list = new ArrayList<>(map.values());
        list.sort(ORDER);
        Map<String, HarnessTool> ordered = new LinkedHashMap<>();
        list.forEach(t -> ordered.put(t.name(), t));
        return new Snapshot(java.util.Collections.unmodifiableMap(ordered), List.copyOf(list));
    }
}
