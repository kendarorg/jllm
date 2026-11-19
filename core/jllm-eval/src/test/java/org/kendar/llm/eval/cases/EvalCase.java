package org.kendar.llm.eval.cases;

import com.fasterxml.jackson.databind.JsonNode;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.permission.PermissionMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * One eval case, defined fluently in Java. Immutable once built.
 *
 * <pre>{@code
 * EvalCase.named("read-and-answer").feature("files").tag(Tag.SMOKE)
 *     .setup((ws, extras) -> Files.writeString(ws.resolve("a.txt"), "42"))
 *     .user("What number is in a.txt?")
 *     .check(Checks.toolCalled("read_file"))
 *     .check(Checks.answerContains("42"))
 *     .build();
 * }</pre>
 */
public final class EvalCase {

    /** Test group the case belongs to. */
    public enum Tag {
        SMOKE("smoke"), FULL("full"), STRATEGY("strategy"), COMPACTION("compaction");
        private final String group;

        Tag(String group) {
            this.group = group;
        }

        public String group() {
            return group;
        }
    }

    /** One step of the scripted conversation. */
    public sealed interface Step permits UserTurn, Action {
    }

    public record UserTurn(String text) implements Step {
    }

    /** Anything done between turns: mode switch, tool toggle, planting a file. */
    public record Action(String label, BiConsumer<AgentHarness, String> body) implements Step {
    }

    private final String name;
    private final String feature;
    private final Tag tag;
    private final List<Step> steps;
    private final List<Check> checks;
    private final List<ContextStrategyKind> strategies;
    private final BiConsumer<Path, Map<String, Object>> setup;
    private final Consumer<AgentHarness.Builder> harness;
    private final Supplier<ModelClient> model;
    private final UnaryOperator<HarnessConfig> config;
    private final java.util.function.Function<JsonNode, Boolean> approvals;
    private final Integer trials;
    private final boolean risk;
    private final double minPassRate;
    private final String rubric;
    private final int maxTurns;
    private final int timeoutSeconds;
    private final Integer contextBudgetTokens;
    private final PermissionMode mode;

    private EvalCase(Builder b) {
        this.name = b.name;
        this.feature = b.feature;
        this.tag = b.tag;
        this.steps = List.copyOf(b.steps);
        this.checks = List.copyOf(b.checks);
        this.strategies = List.copyOf(b.strategies);
        this.setup = b.setup;
        this.harness = b.harness;
        this.model = b.model;
        this.config = b.config;
        this.approvals = b.approvals;
        this.trials = b.trials;
        this.risk = b.risk;
        this.minPassRate = b.minPassRate;
        this.rubric = b.rubric;
        this.maxTurns = b.maxTurns;
        this.timeoutSeconds = b.timeoutSeconds;
        this.contextBudgetTokens = b.contextBudgetTokens;
        this.mode = b.mode;
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public String name() { return name; }
    public String feature() { return feature; }
    public Tag tag() { return tag; }
    public List<Step> steps() { return steps; }
    public List<Check> checks() { return checks; }
    /** Strategies the case expands into; one run (compaction) when not set. */
    public List<ContextStrategyKind> strategies() { return strategies; }
    public BiConsumer<Path, Map<String, Object>> setup() { return setup; }
    public Consumer<AgentHarness.Builder> harness() { return harness; }
    /** A scripted model used instead of the model under test, or null. Its requests are still recorded. */
    public Supplier<ModelClient> model() { return model; }
    public UnaryOperator<HarnessConfig> config() { return config; }
    /** Scripted answer to an approval request (the request's event data); true approves. */
    public java.util.function.Function<JsonNode, Boolean> approvals() { return approvals; }
    /** Trials for this case, or null for the run default. */
    public Integer trials() { return trials; }
    public boolean risk() { return risk; }
    public double minPassRate() { return minPassRate; }
    /** Name of the judge rubric, or null when no judge applies. */
    public String rubric() { return rubric; }
    public int maxTurns() { return maxTurns; }
    public int timeoutSeconds() { return timeoutSeconds; }
    /** Context budget forcing early compaction, or null for the run's context size. */
    public Integer contextBudgetTokens() { return contextBudgetTokens; }
    /** Initial permission mode, or null for the default one. */
    public PermissionMode mode() { return mode; }

    /** The user messages in order. */
    public List<String> userTurns() {
        List<String> out = new ArrayList<>();
        for (Step s : steps) if (s instanceof UserTurn u) out.add(u.text());
        return out;
    }

    public static final class Builder {
        private final String name;
        private String feature = "general";
        private Tag tag = Tag.FULL;
        private final List<Step> steps = new ArrayList<>();
        private final List<Check> checks = new ArrayList<>();
        private final List<ContextStrategyKind> strategies = new ArrayList<>();
        private BiConsumer<Path, Map<String, Object>> setup = (w, e) -> {
        };
        private Consumer<AgentHarness.Builder> harness = b -> {
        };
        private Supplier<ModelClient> model;
        private UnaryOperator<HarnessConfig> config = c -> c;
        private java.util.function.Function<JsonNode, Boolean> approvals = r -> Boolean.TRUE;
        private Integer trials;
        private boolean risk;
        private double minPassRate = 2.0 / 3.0;
        private String rubric;
        private int maxTurns = 40;
        private int timeoutSeconds = 600;
        private Integer contextBudgetTokens;
        private PermissionMode mode;

        private Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("A case needs a name");
            this.name = name;
        }

        public Builder feature(String f) { this.feature = f; return this; }
        public Builder tag(Tag t) { this.tag = t; return this; }

        /** Prepares the workspace; values put into the map reach the checks as {@code extras}. */
        public Builder setup(BiConsumer<Path, Map<String, Object>> s) { this.setup = s; return this; }

        /** Customizes the harness builder; applied last, after the eval's own defaults. */
        public Builder harness(Consumer<AgentHarness.Builder> h) { this.harness = h; return this; }
        /** Runs the case against this model instead of the model under test (scripted servers); evaluated per trial. */
        public Builder model(Supplier<ModelClient> m) { this.model = m; return this; }

        public Builder config(UnaryOperator<HarnessConfig> c) { this.config = c; return this; }
        public Builder user(String text) { steps.add(new UserTurn(text)); return this; }

        /** A long session: the turns played one after another in the same chat. */
        public Builder session(List<Step> script) { steps.addAll(script); return this; }

        public Builder action(String label, BiConsumer<AgentHarness, String> body) {
            steps.add(new Action(label, body));
            return this;
        }

        public Builder modeSwitch(PermissionMode m) {
            return action("mode " + m, (h, chat) -> h.sessions().setMode(chat, m));
        }

        public Builder startMode(PermissionMode m) { this.mode = m; return this; }

        /** Scripted approvals: true approves the request. Approves everything unless set. */
        public Builder approve(Predicate<JsonNode> p) { this.approvals = p::test; return this; }
        public Builder denyAll() { this.approvals = r -> Boolean.FALSE; return this; }

        public Builder strategies(ContextStrategyKind... k) {
            strategies.addAll(List.of(k));
            return this;
        }

        public Builder trials(int n) {
            if (n < 1) throw new IllegalArgumentException("trials must be at least 1");
            this.trials = n;
            return this;
        }

        /** Risk case: every trial must pass. */
        public Builder risk() { this.risk = true; return this; }

        public Builder minPassRate(double r) { this.minPassRate = r; return this; }
        public Builder check(Check c) { checks.add(c); return this; }
        public Builder rubric(String r) { this.rubric = r; return this; }
        public Builder maxTurns(int n) { this.maxTurns = n; return this; }
        public Builder timeoutSeconds(int s) { this.timeoutSeconds = s; return this; }
        public Builder contextBudgetTokens(int t) { this.contextBudgetTokens = t; return this; }

        public EvalCase build() {
            if (steps.stream().noneMatch(s -> s instanceof UserTurn)) {
                throw new IllegalStateException("Case " + name + " has no user turn");
            }
            if (strategies.isEmpty()) strategies.add(ContextStrategyKind.COMPACTION);
            if (new java.util.HashSet<>(strategies).size() != strategies.size()) {
                throw new IllegalStateException("Case " + name + " lists a strategy twice");
            }
            return new EvalCase(this);
        }
    }

    @Override
    public String toString() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("feature", feature);
        m.put("tag", tag);
        return m.toString();
    }
}
