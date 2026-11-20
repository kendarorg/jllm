package org.kendar.llm.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kendar.llm.eval.EvalRun;
import org.kendar.llm.eval.cases.EvalCase;
import org.kendar.llm.eval.grade.Check;
import org.kendar.llm.eval.grade.CheckResult;
import org.kendar.llm.eval.grade.TrialContext;
import org.kendar.llm.harness.AgentHarness;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.agent.AgentEvent;
import org.kendar.llm.harness.context.ContextStrategyKind;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.session.ChatSessionData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs one trial: a fresh harness, a temporary workspace, in-memory stores, the case customizations applied last.
 * Strictly sequential: one trial at a time, turns one after another, nothing concurrent.
 */
public final class TrialRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * @param contextTokens context size of the target; the compaction budget is derived from it
     * @param smallModel    use the harness prompt for small models
     * @param turnTimeoutCapSeconds upper bound on each user turn, 0 keeps the case's own timeout
     * @param systemPromptExtra text added to the system prompt of every case (e.g. a reminder on how to call tools); a case's own harness setup can still replace it
     */
    public record Settings(int contextTokens, boolean smallModel, int turnTimeoutCapSeconds, String systemPromptExtra) {
        public Settings(int contextTokens, boolean smallModel) {
            this(contextTokens, smallModel, 0, "");
        }
    }

    private final ModelClient model;
    private final ModelClient fast;
    private final Settings settings;

    /** @param fast the fast model, or null to use the target for summaries as well */
    public TrialRunner(ModelClient model, ModelClient fast, Settings settings) {
        this.model = model;
        this.fast = fast;
        this.settings = settings;
    }

    /** Runs and grades one trial, then saves its trace under {@code traceDir}. */
    public TrialResult run(EvalCase c, ContextStrategyKind strategy, int trial, Path traceDir) {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("jllm-eval-");
            Map<String, Object> extras = new HashMap<>();
            Execution ex = execute(c, strategy, trial, tmp, extras);
            TrialContext ctx = new TrialContext(c.name(), ex.trace, ex.workspace, ex.before, c.userTurns(), extras);
            List<CheckResult> results = grade(c.checks(), ctx);
            if (ex.trace.error() != null) {
                results = new ArrayList<>(results);
                results.add(0, CheckResult.fail("run", ex.trace.error()));
            }
            boolean passed = results.stream().noneMatch(CheckResult::failed);
            Path file = save(traceDir, trial, ex.trace, results);
            return new TrialResult(trial, ex.trace, results, passed, file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) deleteQuietly(tmp);
        }
    }

    /**
     * Cheapest tier first. A failure skips only the paid tiers (embedding and judge); the free trace, state and cache
     * checks still run, so every failing symptom of a trial is recorded. Warnings and not-measurable never skip.
     */
    public static List<CheckResult> grade(List<Check> checks, TrialContext ctx) {
        List<Check> ordered = new ArrayList<>(checks);
        ordered.sort(Comparator.comparing(Check::tier));
        List<CheckResult> out = new ArrayList<>();
        boolean failed = false;
        for (Check k : ordered) {
            if (failed && k.tier().compareTo(Check.Tier.EMBEDDING) >= 0) continue;
            CheckResult r;
            try {
                r = k.check(ctx);
            } catch (RuntimeException e) {
                r = CheckResult.fail(k.name(), "check threw " + e);
            }
            out.add(r);
            if (r.failed()) failed = true;
        }
        return out;
    }

    private record Execution(TrialTrace trace, Path workspace, Map<String, String> before) {
    }

    private int turnTimeout(EvalCase c) {
        int cap = settings.turnTimeoutCapSeconds();
        return cap > 0 ? Math.min(cap, c.timeoutSeconds()) : c.timeoutSeconds();
    }

    private Execution execute(EvalCase c, ContextStrategyKind strategy, int trial, Path base,
                              Map<String, Object> extras) {
        String label = c.feature() + "/" + c.name() + " [" + strategy.name().toLowerCase() + "] #" + trial;
        long start = System.nanoTime();
        RecordingModelClient main = new RecordingModelClient(c.model() != null ? c.model().get() : model, label);
        RecordingModelClient fastRec = fast == null ? null : new RecordingModelClient(fast, label);
        int budget = c.contextBudgetTokens(strategy) != null ? c.contextBudgetTokens(strategy)
                : (int) Math.max(4_000, settings.contextTokens() * 0.75);
        HarnessConfig config = HarnessConfig.defaults().withAgentId("jllm").withMaxTokens(4096)
                .withMaxTurns(c.maxTurns()).withApprovalTimeoutSeconds(Math.max(30, c.timeoutSeconds()))
                .withSandboxBaseDir(base.toString()).withLocalDirSandboxAllowed(true)
                .withSandboxTimeoutSeconds(60).withContextStrategy(strategy).withContextBudgetTokens(budget);
        config = c.config().apply(config);
        AgentHarness.Builder b = AgentHarness.builder().modelClient(main).config(config)
                .smallModel(settings.smallModel());
        if (fastRec != null) b.fastModelClient(fastRec);
        if (settings.systemPromptExtra() != null && !settings.systemPromptExtra().isBlank()) {
            b.systemPrompt(settings.systemPromptExtra());
        }
        c.harness().accept(b);

        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        String error = null;
        String answer = "";
        AgentHarness h = b.build();
        try {
            ChatSessionData chat = h.sessions().create(c.name());
            String chatId = chat.chatId();
            Path workspace = h.workDir(chatId);
            c.setup().accept(workspace, extras);
            Map<String, String> before = snapshot(workspace);
            if (c.mode() != null) h.sessions().setMode(chatId, c.mode());
            CountDownLatch[] finished = {new CountDownLatch(1)};
            h.subscribe(chatId, 0, ev -> {
                events.add(ev);
                if (ev.type().equals("approval_request")) answerApproval(h, chatId, c, ev.data());
                if (ev.type().equals("run_finished")) finished[0].countDown();
            });
            int turns = c.userTurns().size();
            int turnNo = 0;
            for (EvalCase.Step step : c.steps()) {
                if (step instanceof EvalCase.Action a) {
                    a.body().accept(h, chatId);
                } else if (step instanceof EvalCase.UserTurn u) {
                    turnNo++;
                    main.turn(turnNo, turns);
                    if (fastRec != null) fastRec.turn(turnNo, turns);
                    finished[0] = new CountDownLatch(1);
                    h.start(chatId, u.text());
                    int turnTimeout = turnTimeout(c);
                    if (!finished[0].await(turnTimeout, TimeUnit.SECONDS)) {
                        h.cancel(chatId, true);
                        error = "timeout after " + turnTimeout + "s in turn: " + abbreviate(u.text());
                        break;
                    }
                }
            }
            answer = lastAssistantText(h, chat.chatId());
            if (error == null) {
                synchronized (events) {
                    for (AgentEvent e : events) {
                        if (e.type().equals("error")) {
                            error = "harness error: " + e.data().path("message").asText(e.data().toString());
                            break;
                        }
                    }
                }
            }
            List<RecordedRequest> requests = new ArrayList<>(main.requests());
            if (fastRec != null) requests.addAll(fastRec.requests());
            TrialTrace trace = new TrialTrace(List.copyOf(events), requests, answer, error,
                    (System.nanoTime() - start) / 1_000_000);
            return new Execution(trace, workspace, before);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (RuntimeException e) {
            List<RecordedRequest> requests = new ArrayList<>(main.requests());
            TrialTrace trace = new TrialTrace(List.copyOf(events), requests, answer, "run failed: " + e,
                    (System.nanoTime() - start) / 1_000_000);
            return new Execution(trace, base, Map.of());
        } finally {
            h.close();
        }
    }

    private static void answerApproval(AgentHarness h, String chatId, EvalCase c, JsonNode data) {
        boolean ok = Boolean.TRUE.equals(c.approvals().apply(data));
        h.resolveApproval(chatId, data.path("id").asText(), ok, null);
    }

    private static String lastAssistantText(AgentHarness h, String chatId) {
        var msgs = h.sessions().messages(h.sessions().get(chatId));
        for (int i = msgs.size() - 1; i >= 0; i--) {
            JsonNode m = msgs.get(i);
            if (!"assistant".equals(m.path("role").asText())) continue;
            StringBuilder sb = new StringBuilder();
            JsonNode content = m.path("content");
            if (content.isTextual()) return content.asText();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) sb.append(part.path("text").asText());
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        return "";
    }

    /** Relative path to SHA-256 of every regular file under the workspace. */
    public static Map<String, String> snapshot(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                out.put(dir.relativize(p).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(md.digest(Files.readAllBytes(p))));
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot snapshot " + dir, e);
        }
        return out;
    }

    private static Path save(Path traceDir, int trial, TrialTrace trace, List<CheckResult> results)
            throws IOException {
        if (traceDir == null) return null;
        Files.createDirectories(traceDir);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("trial", trial);
        doc.put("error", trace.error());
        doc.put("millis", trace.millis());
        doc.put("finalAnswer", trace.finalAnswer());
        doc.put("checks", results);
        doc.put("events", trace.events());
        doc.put("requests", trace.requests());
        Path file = traceDir.resolve("trial-" + trial + ".json");
        Files.writeString(file, EvalRun.activeSecrets().maskJson(MAPPER.writeValueAsString(doc)), StandardCharsets.UTF_8);
        return file;
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp folder; the OS cleans up
        }
    }
}
