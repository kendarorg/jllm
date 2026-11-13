package org.kendar.llm.web.admin;

import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.integration.ExternalSources;
import org.kendar.llm.harness.integration.ToolNames;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.util.Wildcard;
import org.kendar.llm.web.users.Permissions;
import org.kendar.llm.web.users.WebUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Everything a role can be granted, read from the harness: its tools (built-in and plugins), and its MCP servers and
 * REST APIs with their tools, also the disabled or failing ones. The same view, restricted to a role and annotated
 * with a user's choices, is the user's tool settings.
 */
public final class ToolCatalogView {

    private static final int DESCRIPTION_MAX = 240;

    /** @param enabled for a user view: not switched off by the user; always true in the full catalog */
    public record Tool(String name, String group, String kind, String description, boolean enabled) {
    }

    /**
     * @param pattern the tool-name pattern that grants (or, for a user, disables) the whole source
     * @param enabled whether the harness has the source on
     * @param on      for a user view: the whole source is not switched off by the user
     */
    public record Source(String name, String pattern, boolean enabled, String error, List<Tool> tools, boolean on) {
    }

    public record Catalog(List<Tool> tools, List<Source> mcp, List<Source> api) {
    }

    private final AgentHarness harness;

    public ToolCatalogView(AgentHarness harness) {
        this.harness = harness;
    }

    /** The full catalog (admin). */
    public Catalog all() {
        return build(null, null);
    }

    /** What the role allows, with the user's {@code disabledTools} applied as {@code enabled}/{@code on}. */
    public Catalog forUser(Permissions.Role role, WebUser user) {
        return build(role, user);
    }

    /**
     * The entries of {@code disabled} that mean something for the role: a source pattern of a listed source, or a
     * pattern matching at least one tool the role allows. Others are dropped.
     */
    public List<String> keepMeaningful(Permissions.Role role, Collection<String> disabled) {
        Catalog c = forUser(role, null);
        List<String> names = new ArrayList<>();
        c.tools().forEach(t -> names.add(t.name()));
        List<String> patterns = new ArrayList<>();
        for (Source s : concat(c.mcp(), c.api())) {
            patterns.add(s.pattern());
            s.tools().forEach(t -> names.add(t.name()));
        }
        List<String> out = new ArrayList<>();
        for (String d : disabled) {
            if (d == null || d.isBlank() || d.length() > 200 || out.contains(d)) continue;
            if (patterns.contains(d) || names.stream().anyMatch(n -> Wildcard.matches(d, n))) out.add(d);
        }
        return out;
    }

    private Catalog build(Permissions.Role role, WebUser user) {
        List<String> disabled = user == null ? List.of() : user.disabledTools();
        List<Source> mcp = sources(ExternalSources.Kind.MCP, "mcp__", role, disabled);
        List<Source> api = sources(ExternalSources.Kind.API, "", role, disabled);
        List<String> taken = new ArrayList<>();
        for (Source s : concat(mcp, api)) s.tools().forEach(t -> taken.add(t.name()));
        List<String> prefixes = concat(mcp, api).stream().map(s -> s.pattern().substring(0, s.pattern().length() - 1))
                .toList();
        List<Tool> tools = new ArrayList<>();
        for (HarnessTool t : harness.tools().every()) {
            if (taken.contains(t.name()) || prefixes.stream().anyMatch(p -> t.name().startsWith(p))) continue;
            if (role != null && !role.allows(t)) continue;
            tools.add(tool(t, disabled));
        }
        return new Catalog(tools, mcp, api);
    }

    private List<Source> sources(ExternalSources.Kind kind, String namePrefix, Permissions.Role role,
                                 List<String> disabled) {
        List<Source> out = new ArrayList<>();
        Collection<HarnessTool> every = harness.tools().every();
        for (ExternalSources.Status s : harness.sources().list(kind)) {
            String prefix = ToolNames.of(namePrefix + s.name() + "__");
            List<Tool> tools = new ArrayList<>();
            for (HarnessTool t : every) {
                if (!t.name().startsWith(prefix)) continue;
                if (role != null && !role.allows(t)) continue;
                tools.add(tool(t, disabled));
            }
            String pattern = prefix + "*";
            if (role != null && tools.isEmpty() && !role.allowsName(prefix + "x")) continue;
            out.add(new Source(s.name(), pattern, s.enabled(), s.error(), tools, !disabled.contains(pattern)));
        }
        return out;
    }

    private static Tool tool(HarnessTool t, List<String> disabled) {
        String d = t.description() == null ? "" : t.description().strip();
        int nl = d.indexOf('\n');
        if (nl > 0) d = d.substring(0, nl);
        if (d.length() > DESCRIPTION_MAX) d = d.substring(0, DESCRIPTION_MAX) + "…";
        return new Tool(t.name(), t.group(), String.valueOf(t.kind()), d, !Wildcard.matchesAny(disabled, t.name()));
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
