package org.kendar.llm.harness.skills;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.SimpleTool;
import org.kendar.llm.harness.tools.ToolContext;
import org.kendar.llm.harness.tools.ToolKind;

/** Loads the instructions of a skill. The description lists the enabled skills, so toggling one updates it. */
public final class SkillTool implements HarnessTool {

    private final SkillCatalog catalog;

    public SkillTool(SkillCatalog catalog) {
        this.catalog = catalog;
    }

    @Override public String name() { return "skill"; }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder("Load the instructions of a skill, then follow them. Skills:");
        for (SkillCatalog.Skill s : catalog.enabled()) {
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
        return catalog.load(input.path("name").asText());
    }
}
