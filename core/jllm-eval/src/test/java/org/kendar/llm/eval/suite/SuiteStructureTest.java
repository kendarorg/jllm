package org.kendar.llm.eval.suite;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.suite.examples.ExampleStrategyEvalTest;
import org.kendar.llm.eval.suite.examples.ExampleToolEvalTest;
import org.kendar.llm.harness.context.ContextStrategyKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Structure of the generated suite, verified without a model: every case builds and has the promised shape. */
@Tag("unit")
class SuiteStructureTest {

    private static final Pattern HEADING = Pattern.compile("^#{2,4}\\s+(\\d+(?:\\.\\d+)*)\\.?\\s+(.*)$");

    private static List<SuiteBase> areas() {
        return List.of(new AgentLoopCasesTest(), new CutOffCasesTest(), new CustomToolsCasesTest(), new FileToolsCasesTest(),
                new ShellCasesTest(), new PermissionModesCasesTest(), new PermissionRulesCasesTest(),
                new HooksCasesTest(), new StuckCasesTest(), new McpCasesTest(), new RestCasesTest(),
                new ToolGroupsCasesTest(), new OutputSafetyCasesTest(), new SubAgentsCasesTest(),
                new SkillsCasesTest(), new OutputLimitsCasesTest(), new SessionsCasesTest(),
                new CommandsCasesTest(), new PromptCacheCasesTest(), new PdfCasesTest(), new DocsCasesTest(),
                new JsCasesTest(), new WorkflowsCasesTest(), new StrategyRefactoringSessionTest(), new StrategyResearchLargeOutputsSessionTest(),
                new StrategyModeSwitchesSessionTest(), new StrategyToolTogglesSessionTest(),
                new SsrfCasesTest(), new TaintCompactionCasesTest(), new SymlinkCasesTest(),
                new AdminApprovalCasesTest(), new WebInjectionCasesTest());
    }

    private static List<EvalCase> all() {
        List<EvalCase> out = new ArrayList<>();
        for (SuiteBase a : areas()) out.addAll(a.cases());
        return out;
    }

    private static Map<String, String> headings() throws IOException {
        Path doc = Path.of("FEATURES.md");
        if (!Files.exists(doc)) doc = Path.of("core/jllm-eval/FEATURES.md");
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(doc)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) map.putIfAbsent(m.group(1), m.group(2).toLowerCase(Locale.ROOT));
        }
        return map;
    }

    private static String number(String feature) {
        return feature.substring(0, feature.indexOf('-'));
    }

    private static boolean core(EvalCase c) {
        return c.feature().startsWith("1.") || c.feature().equals(Suite.CACHE);
    }

    private static boolean plugin(EvalCase c) {
        return c.feature().startsWith("2.");
    }

    @Test
    void everyCaseBuildsWithUniqueNamesAndChecks() {
        Set<String> seen = new HashSet<>();
        for (EvalCase c : all()) {
            assertTrue(seen.add(c.feature() + "/" + c.name()), "duplicate case " + c.feature() + "/" + c.name());
            assertFalse(c.checks().isEmpty(), c.name() + " has no check");
            assertFalse(c.userTurns().isEmpty(), c.name() + " has no user turn");
            assertTrue(c.checks().stream().anyMatch(k -> k.tier() == Check.Tier.TRACE || k.tier() == Check.Tier.STATE),
                    c.name() + " has no deterministic check");
            assertEquals(c.name().startsWith("risk-"), c.risk(), c.name() + ": risk flag and name disagree");
        }
        assertFalse(seen.isEmpty());
    }

    @Test
    void featuresExistInFeaturesDocument() throws IOException {
        Map<String, String> headings = headings();
        for (EvalCase c : all()) {
            String num = number(c.feature());
            assertTrue(headings.containsKey(num), c.feature() + ": no heading numbered " + num + " in FEATURES.md");
            String heading = headings.get(num);
            boolean loose = false;
            for (String w : c.feature().substring(num.length() + 1).split("-")) {
                if (w.length() >= 2 && heading.contains(w)) loose = true;
            }
            assertTrue(loose, c.feature() + " does not resemble heading '" + heading + "'");
        }
    }

    @Test
    void everyCoreAndPluginFeatureIsCoveredOrExplained() throws IOException {
        Set<String> covered = new HashSet<>();
        for (EvalCase c : all()) covered.add(number(c.feature()));
        for (String num : Suite.NOT_COVERED.keySet()) {
            assertTrue(headings().containsKey(num), "NOT_COVERED names unknown feature " + num);
            assertFalse(covered.contains(num), num + " is both covered and listed as not covered");
        }
        for (String num : headings().keySet()) {
            if (!num.matches("[12]\\.\\d+")) continue;
            assertTrue(covered.contains(num) || Suite.NOT_COVERED.containsKey(num),
                    "feature " + num + " has neither cases nor an explanation");
        }
    }

    @Test
    void coreFeaturesHaveThreeNormalTwoEdgeOneRisk() {
        Map<String, List<EvalCase>> byFeature = new LinkedHashMap<>();
        for (EvalCase c : all()) if (core(c)) byFeature.computeIfAbsent(c.feature(), k -> new ArrayList<>()).add(c);
        assertFalse(byFeature.isEmpty());
        byFeature.forEach((f, cases) -> {
            // at least 3 normal and 2 edge cases, one risk case; a feature with more ground to cover takes one more
            assertTrue(cases.size() == 6 || cases.size() == 7, f + " case count: " + cases.size());
            assertEquals(1, cases.stream().filter(EvalCase::risk).count(), f + " risk cases");
            long edge = cases.stream().filter(c -> c.name().startsWith("edge-")).count();
            assertTrue(edge >= 2, f + " edge cases: " + edge);
            long normal = cases.stream().filter(c -> !c.risk() && !c.name().startsWith("edge-")).count();
            assertTrue(normal >= 3, f + " normal cases: " + normal);
            long smoke = cases.stream().filter(c -> c.tag() == EvalCase.Tag.SMOKE).count();
            assertTrue(smoke >= 1 && smoke <= 2, f + " smoke cases: " + smoke);
            cases.forEach(c -> assertNotEquals(EvalCase.Tag.COMPACTION, c.tag()));
        });
    }

    @Test
    void pluginsHaveThreeCasesWithOneSmoke() {
        Map<String, List<EvalCase>> byFeature = new LinkedHashMap<>();
        for (EvalCase c : all()) if (plugin(c)) byFeature.computeIfAbsent(c.feature(), k -> new ArrayList<>()).add(c);
        assertEquals(4, byFeature.size(), "pdf, docs, js, workflows");
        byFeature.forEach((f, cases) -> {
            // three cases; a plugin with more ground to cover takes one more
            assertTrue(cases.size() == 3 || cases.size() == 4, f + " case count: " + cases.size());
            assertEquals(1, cases.stream().filter(c -> c.tag() == EvalCase.Tag.SMOKE).count(), f + " smoke");
            assertEquals(1, cases.stream().filter(c -> c.name().startsWith("failure-")).count(), f + " failure case");
        });
    }

    @Test
    void strategySessionsReplayUnderEveryStrategyWithSmallBudget() {
        List<EvalCase> sessions = all().stream().filter(c -> c.feature().equals(Suite.STRATEGIES)).toList();
        assertEquals(4, sessions.size());
        for (EvalCase c : sessions) {
            assertEquals(EvalCase.Tag.COMPACTION, c.tag());
            assertEquals(3, c.strategies().size(), c.name());
            assertNotNull(c.contextBudgetTokens(), c.name());
            assertTrue(c.contextBudgetTokens() <= 12_000, c.name());
            assertTrue(c.strategyBudgets().keySet().stream().allMatch(k -> k == ContextStrategyKind.SCOPE),
                    c.name() + ": only scope may get a larger budget");
            assertTrue(c.userTurns().size() >= 8, c.name() + " is not a long session");
            assertTrue(c.checks().stream().anyMatch(k -> k.name().startsWith("retained")), c.name() + " has no retention check");
            assertTrue(c.checks().stream().anyMatch(k -> k.name().startsWith("breaksAsExpected")), c.name() + " has no cache check");
            assertTrue(c.checks().stream().anyMatch(k -> k.name().contains("@")), c.name() + " has no strategy invariant");
            assertTrue(c.steps().stream().anyMatch(st -> st instanceof EvalCase.Action a && a.label().equals("/compact")),
                    c.name() + " does not force a compaction at a fixed point");
            assertTrue(c.checks().stream().anyMatch(k -> k.name().equals("answerIsNotASummary")), c.name() + " has no echo check");
        }
    }

    @Test
    void examplesBuild() {
        assertEquals(2, ExampleToolEvalTest.cases().size());
        assertEquals(1, ExampleStrategyEvalTest.cases().size());
    }

    @Test
    void tagsMatchFactories() {
        for (EvalCase c : all()) {
            boolean strategy = c.feature().equals(Suite.STRATEGIES);
            assertEquals(strategy, c.tag() == EvalCase.Tag.COMPACTION, c.name());
            if (!strategy) assertEquals(List.of(org.kendar.llm.harness.context.ContextStrategyKind.COMPACTION), c.strategies());
        }
    }
}
