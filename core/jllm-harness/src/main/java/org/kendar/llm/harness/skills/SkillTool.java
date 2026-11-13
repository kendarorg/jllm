package org.kendar.llm.harness.skills;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.auth.UserSkill;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Loads the instructions of a skill. The description lists the enabled skills, so toggling one updates it. A view
 * made by {@link #forChat} lists and loads only the skills its filter lets through (the chat's own tool set and the
 * user's policy), plus the user's own skills.
 */
public final class SkillTool implements HarnessTool {

    private final SkillCatalog catalog;
    /** null = every enabled skill */
    private final Predicate<String> allowed;
    /** the user's own skills, by name; never shadowing a catalog skill */
    private final Map<String, SkillCatalog.Skill> extra;

    public SkillTool(SkillCatalog catalog) {
        this(catalog, null, Map.of());
    }

    private SkillTool(SkillCatalog catalog, Predicate<String> allowed, Map<String, SkillCatalog.Skill> extra) {
        this.catalog = catalog;
        this.allowed = allowed;
        this.extra = extra;
    }

    /** The same tool restricted to the skills {@code allowed} accepts (by name). */
    public SkillTool forChat(Predicate<String> allowed) {
        return forChat(allowed, List.of());
    }

    /**
     * The same tool restricted to the catalog skills {@code allowed} accepts, plus {@code extra} skills (the user's
     * own). An extra skill whose name is a catalog skill is left out.
     */
    public SkillTool forChat(Predicate<String> allowed, List<UserSkill> extra) {
        Map<String, SkillCatalog.Skill> own = new LinkedHashMap<>();
        for (UserSkill s : extra == null ? List.<UserSkill>of() : extra) {
            if (s == null || catalog.contains(s.name())) continue;
            if (allowed != null && !allowed.test(s.name())) continue;
            own.putIfAbsent(s.name(), new SkillCatalog.Skill(s.name(), s.description(), s.text()));
        }
        return new SkillTool(catalog, allowed, own);
    }

    /** Whether the harness itself has no skill (the tool then only serves users' own skills). */
    public boolean catalogEmpty() {
        return catalog.isEmpty();
    }

    /** The enabled skills this tool offers. */
    public List<SkillCatalog.Skill> offered() {
        List<SkillCatalog.Skill> out = new ArrayList<>(
                catalog.enabled().stream().filter(s -> allowed == null || allowed.test(s.name())).toList());
        out.addAll(extra.values());
        return out;
    }

    @Override public String name() { return "skill"; }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder("Load the instructions of a skill, then follow them. Skills:");
        for (SkillCatalog.Skill s : offered()) {
            sb.append("\n- ").append(s.name());
            if (!s.description().isBlank()) sb.append(": ").append(s.description());
        }
        return sb.toString();
    }

    @Override
    public JsonNode inputSchema() {
        return SimpleTool.schema("name", "Name of the skill to load");
    }

    @Override public String group() { return "Skills"; }

    @Override public ToolKind kind() { return ToolKind.READ_ONLY; }

    @Override
    public String execute(ToolContext ctx, JsonNode input) throws Exception {
        String name = input.path("name").asText();
        SkillCatalog.Skill own = extra.get(name);
        if (own != null) return own.text().read();
        if (allowed != null && !allowed.test(name)) {
            throw new IllegalArgumentException("Skill disabled for this chat: " + name);
        }
        return catalog.load(name);
    }
}
