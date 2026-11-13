package org.kendar.llm.harness.skills;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

import java.util.List;
import java.util.function.Predicate;

/**
 * Loads the instructions of a skill. The description lists the enabled skills, so toggling one updates it. A view
 * made by {@link #forChat} lists and loads only the skills its filter lets through (the chat's own tool set).
 */
public final class SkillTool implements HarnessTool {

    private final SkillCatalog catalog;
    /** null = every enabled skill */
    private final Predicate<String> allowed;

    public SkillTool(SkillCatalog catalog) {
        this(catalog, null);
    }

    private SkillTool(SkillCatalog catalog, Predicate<String> allowed) {
        this.catalog = catalog;
        this.allowed = allowed;
    }

    /** The same tool restricted to the skills {@code allowed} accepts (by name). */
    public SkillTool forChat(Predicate<String> allowed) {
        return new SkillTool(catalog, allowed);
    }

    /** The enabled skills this tool offers. */
    public List<SkillCatalog.Skill> offered() {
        return catalog.enabled().stream().filter(s -> allowed == null || allowed.test(s.name())).toList();
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
        if (allowed != null && !allowed.test(name)) {
            throw new IllegalArgumentException("Skill disabled for this chat: " + name);
        }
        return catalog.load(name);
    }
}
