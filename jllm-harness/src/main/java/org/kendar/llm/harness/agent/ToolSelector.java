package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shortlists tools by group (one cheap model call when the catalogue is large). The order of the tools is the
 * registry's: sorted by group, then name.
 */
public class ToolSelector {

    private static final Logger log = LoggerFactory.getLogger(ToolSelector.class);
    private final HarnessConfig config;
    private final ModelClient model;
    private final ObjectMapper mapper;

    public ToolSelector(HarnessConfig config, ModelClient model, ObjectMapper mapper) {
        this.config = config;
        this.model = model;
        this.mapper = mapper;
    }

    public List<HarnessTool> select(String userText, Collection<HarnessTool> all) {
        return forGroups(selectGroups(userText, all), all);
    }

    /** Groups to enable for a new chat (always including the core ones), or null when every tool should be offered. */
    public Set<String> selectGroups(String userText, Collection<HarnessTool> all) {
        List<HarnessTool> tools = new ArrayList<>(all);
        if (tools.size() <= config.toolBatchThreshold()) return null;
        Set<String> chosen = chooseGroups(userText, tools);
        if (chosen == null || chosen.isEmpty()) return null;
        Set<String> out = new TreeSet<>(chosen);
        out.addAll(config.coreGroups());
        return out;
    }

    /** The tools of the given groups (null = all), in the order they were given (the registry's stable order). */
    public List<HarnessTool> forGroups(Set<String> groups, Collection<HarnessTool> all) {
        List<HarnessTool> tools = new ArrayList<>(all);
        if (groups != null) tools.removeIf(t -> !groups.contains(t.group()));
        return tools;
    }

    private Set<String> chooseGroups(String userText, List<HarnessTool> tools) {
        Set<String> groups = new TreeSet<>();
        StringBuilder catalogue = new StringBuilder();
        for (HarnessTool t : tools) {
            if (t.group() != null && groups.add(t.group())) catalogue.append("- ").append(t.group()).append('\n');
            catalogue.append("    ").append(t.name()).append('\n');
        }
        try {
            String answer = model.simpleComplete(
                    Prompts.load("tool-selector-system.md"),
                    "Request:\n" + userText + "\n\nCatalogue (group, then its tools):\n" + catalogue, 256);
            int s = answer.indexOf('[');
            int e = answer.lastIndexOf(']');
            if (s < 0 || e < s) return null;
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode n : mapper.readTree(answer.substring(s, e + 1))) {
                if (groups.contains(n.asText())) out.add(n.asText());
            }
            return out;
        } catch (Exception ex) {
            log.warn("Tool group selection failed, using all tools: {}", ex.getMessage());
            return null;
        }
    }
}
