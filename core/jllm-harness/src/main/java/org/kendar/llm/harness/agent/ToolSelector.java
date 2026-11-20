package org.kendar.llm.harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.Prompts;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

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

    /**
     * Groups outside {@code enabled} with a tool the user names verbatim ("call big_report"): the user asked for it,
     * so it is offered without the model having to request its group. Empty when {@code enabled} is null (all tools).
     */
    public static Set<String> namedGroups(String userText, Collection<HarnessTool> all, Set<String> enabled) {
        Set<String> out = new TreeSet<>();
        if (enabled == null) return out;
        for (HarnessTool t : all) {
            if (t.group() == null || enabled.contains(t.group()) || out.contains(t.group())) continue;
            if (java.util.regex.Pattern.compile("(?<![\\w-])" + java.util.regex.Pattern.quote(t.name()) + "(?![\\w-])")
                    .matcher(userText).find()) {
                out.add(t.group());
            }
        }
        return out;
    }

    /** One line of a tool's description for a catalogue: whitespace collapsed, cut at {@code max} chars. */
    static String brief(String description, int max) {
        if (description == null) return "";
        String s = description.replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
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
            // the description tells the selector what a tool is for (a skill's name says nothing of what it does)
            catalogue.append("    ").append(t.name());
            String brief = brief(t.description(), 160);
            if (!brief.isEmpty()) catalogue.append(": ").append(brief);
            catalogue.append('\n');
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
