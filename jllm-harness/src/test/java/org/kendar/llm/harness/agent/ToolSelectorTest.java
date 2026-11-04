package org.kendar.llm.harness.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.tools.HarnessToolRegistry;
import org.kendar.llm.harness.HarnessTestSupport.ScriptedModel;
import org.kendar.llm.harness.tools.HarnessTool;
import org.kendar.llm.harness.tools.ToolKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.kendar.llm.harness.HarnessTestSupport.*;

class ToolSelectorTest {

    private ScriptedModel model;
    private ToolSelector selector;

    @BeforeEach
    void setUp() {
        model = new ScriptedModel();
        selector = new ToolSelector(HarnessConfig.defaults(), model, MAPPER);
    }

    private static HarnessTool t(String name, String group) {
        return tool(name, group, ToolKind.READ_ONLY, (c, i) -> "");
    }

    /** 16 tools, registered in the "wrong" order; returned in the registry's sorted order. */
    private static List<HarnessTool> catalogue() {
        List<HarnessTool> l = new ArrayList<>();
        l.add(t("bash", "Shell"));
        l.add(t("web_fetch", "Web"));
        l.add(t("web_search", "Web"));
        l.add(t("read_file", "Files"));
        l.add(t("glob", "Files"));
        l.add(t("jira_get_issue", "Atlassian"));
        l.add(t("confluence_get_page", "Atlassian/Confluence"));
        l.add(t("cgc_callers", "Cgc/Analysis"));
        l.add(t("cgc_find_code", "Cgc/Search"));
        l.add(t("cgc_find_class", "Cgc/Search"));
        l.add(t("search_local_knowledge", "Rag/Search"));
        l.add(t("list_collections", "Rag/Collections"));
        l.add(t("mystery", "Misc"));
        l.add(t("other_a", "Other"));
        l.add(t("other_b", "Other"));
        l.add(t("grep", "Files"));
        return new ArrayList<>(new HarnessToolRegistry(l).all());
    }

    private static List<String> names(List<HarnessTool> l) {
        return l.stream().map(HarnessTool::name).toList();
    }

    @Test
    void atOrBelowThresholdNoModelCall() {
        List<HarnessTool> small = catalogue().subList(0, 12);

        List<HarnessTool> out = selector.select("anything", small);

        assertEquals(12, out.size());
        assertTrue(model.simpleUserPrompts.isEmpty(), "no selection call at or below the threshold");
        assertEquals(12, new java.util.HashSet<>(names(out)).size());
    }

    @Test
    void overThresholdAsksModelAndKeepsChosenAndCoreGroups() {
        model.simpleReplies.add("Sure: [\"Cgc/Search\", \"Rag/Search\"]");

        List<HarnessTool> out = selector.select("who calls foo?", catalogue());

        assertEquals(1, model.simpleUserPrompts.size());
        assertTrue(model.simpleUserPrompts.get(0).contains("who calls foo?"));
        List<String> n = names(out);
        assertTrue(n.containsAll(List.of("cgc_find_code", "cgc_find_class", "search_local_knowledge")));
        // core groups are always retained
        assertTrue(n.containsAll(List.of("read_file", "glob", "grep", "bash")));
        // unchosen groups are dropped
        assertFalse(n.contains("web_fetch"));
        assertFalse(n.contains("jira_get_issue"));
        assertFalse(n.contains("cgc_callers"));
        assertFalse(n.contains("list_collections"));
    }

    @Test
    void selectionKeepsTheRegistryOrder() {
        model.simpleReplies.add("[\"Web\"]");

        List<String> n = names(selector.select("x", catalogue()));

        assertEquals(names(catalogue().stream()
                .filter(t -> List.of("Files", "Shell", "Web").contains(t.group())).toList()), n);
    }

    @Test
    void coreGroupsComeFromConfig() {
        selector = new ToolSelector(HarnessConfig.defaults().withCoreGroups(java.util.Set.of("Web")), model, MAPPER);
        model.simpleReplies.add("[\"Rag/Search\"]");

        List<String> n = names(selector.select("x", catalogue()));

        assertTrue(n.contains("web_fetch"));
        assertFalse(n.contains("bash"));
    }

    @Test
    void modelFailureFallsBackToAllTools() {
        // empty simpleReplies -> scripted model throws
        List<HarnessTool> out = selector.select("x", catalogue());

        assertEquals(16, out.size());
    }

    @Test
    void unparseableOrHallucinatedGroupsFallBackToAllTools() {
        model.simpleReplies.add("I think you need everything");
        assertEquals(16, selector.select("x", catalogue()).size());

        model.simpleReplies.add("[\"NoSuchGroup\"]");
        assertEquals(16, selector.select("x", catalogue()).size());

        model.simpleReplies.add("[]");
        assertEquals(16, selector.select("x", catalogue()).size());
    }

    @Test
    void catalogueSentToModelListsEveryGroupAndTool() {
        model.simpleReplies.add("[\"Web\"]");

        selector.select("x", catalogue());

        String prompt = model.simpleUserPrompts.get(0);
        for (String g : List.of("Shell", "Web", "Files", "Atlassian", "Cgc/Search", "Rag/Collections")) {
            assertTrue(prompt.contains("- " + g), g);
        }
        assertTrue(prompt.contains("web_search"));
    }
}
