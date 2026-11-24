package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Loads a run folder: run.json plus every {@code result.json} and the trial traces it points to. */
public final class ResultLoader {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ResultLoader() {
    }

    public record Check(String name, String status, String reason) {
        public boolean failed() { return "FAIL".equals(status); }
    }

    /** Usage of one model request of a trial. */
    public record Req(boolean simple, long input, long output, long cacheRead, long cacheWrite, long millis) {
        public long context() { return input + cacheRead + cacheWrite; }

        public double cacheRatio() {
            long c = context();
            return c == 0 ? 0 : (double) cacheRead / c;
        }
    }

    public record Trial(int trial, boolean passed, List<Check> checks, long millis, Path traceFile, boolean traceLoaded,
                        List<Req> requests, String finalAnswer) {
    }

    public record CaseRun(String feature, String name, String strategy, String tag, String rubric, boolean risk,
                          double passRate, boolean passed, List<Trial> trials, Path dir) {
        public String key() { return feature + "/" + name + "/" + strategy; }

        /** Number of trials run. */
        public int n() { return trials.size(); }

        /** Number of trials that passed. */
        public int c() { return (int) trials.stream().filter(Trial::passed).count(); }
    }

    public record Run(Path dir, JsonNode meta, List<CaseRun> cases) {
        public String model() {
            String m = text(meta, "targetModel");
            if (m.isEmpty()) m = text(meta, "targetName");
            return m.isEmpty() ? String.valueOf(dir.getFileName()) : m;
        }

        public String judgeModel() { return text(meta, "judgeModel"); }

        public String judgeType() { return text(meta, "judgeType"); }

        public boolean selfGraded() { return meta.path("selfGraded").asBoolean(false); }

        /** The configured k values of pass@k and pass^k, ascending. */
        public List<Integer> passK() {
            JsonNode ks = meta.path("passK");
            if (!ks.isArray() || ks.isEmpty()) {
                throw new IllegalStateException("run.json in " + dir + " has no passK list: it is in the old result "
                        + "format. Old results are not converted: delete this run folder.");
            }
            List<Integer> out = new ArrayList<>();
            ks.forEach(k -> out.add(k.asInt()));
            Collections.sort(out);
            return out;
        }

        /** True when the working tree had tracked changes during the run. */
        public boolean dirty() { return meta.path("build").path("dirty").asBoolean(false); }

        /** The dirty-tree policy the run applied: refuse, warn or allow. */
        public String dirtyTreePolicy() { return text(meta, "dirtyTree"); }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    public static Run load(Path runDir) {
        try {
            JsonNode meta = MAPPER.createObjectNode();
            Path rj = runDir.resolve("run.json");
            if (Files.isRegularFile(rj)) meta = MAPPER.readTree(rj.toFile());
            List<Path> results;
            try (Stream<Path> s = Files.walk(runDir)) {
                results = s.filter(p -> p.getFileName().toString().equals("result.json")).sorted().toList();
            }
            List<CaseRun> cases = new ArrayList<>();
            for (Path r : results) cases.add(loadCase(r));
            cases.sort(Comparator.comparing(CaseRun::key));
            return new Run(runDir, meta, cases);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CaseRun loadCase(Path file) throws IOException {
        JsonNode j = MAPPER.readTree(file.toFile());
        Path dir = file.getParent();
        if (j.has("passK") || !j.path("passAtK").isObject() || !j.path("passPowK").isObject()) {
            throw new IllegalStateException(file + " is in the old result format (no passAtK and passPowK maps). "
                    + "Old results are not converted: delete the run folder that holds it, or re-run the evaluation.");
        }
        List<Trial> trials = new ArrayList<>();
        for (JsonNode t : j.path("trials")) {
            int n = t.path("trial").asInt();
            List<Check> checks = new ArrayList<>();
            for (JsonNode c : t.path("checks"))
                checks.add(new Check(text(c, "name"), text(c, "status"), text(c, "reason")));
            Path trace = dir.resolve("trial-" + n + ".json");
            if (!Files.isRegularFile(trace) && t.hasNonNull("trace")) {
                Path alt = Path.of(t.path("trace").asText());
                if (Files.isRegularFile(alt)) trace = alt;
            }
            List<Req> reqs = new ArrayList<>();
            String answer = "";
            boolean loaded = Files.isRegularFile(trace);
            if (loaded) {
                JsonNode tr = MAPPER.readTree(trace.toFile());
                answer = text(tr, "finalAnswer");
                for (JsonNode r : tr.path("requests")) {
                    JsonNode u = r.path("usage");
                    reqs.add(new Req(r.path("simple").asBoolean(), u.path("inputTokens").asLong(),
                            u.path("outputTokens").asLong(), u.path("cacheReadTokens").asLong(),
                            u.path("cacheCreationTokens").asLong(), r.path("millis").asLong()));
                }
            }
            trials.add(new Trial(n, t.path("passed").asBoolean(), checks, t.path("millis").asLong(),
                    loaded ? trace : null, loaded, reqs, answer));
        }
        String rubric = j.hasNonNull("rubric") && !j.path("rubric").asText().isEmpty()
                ? j.path("rubric").asText() : null;
        return new CaseRun(text(j, "feature"), text(j, "case"), text(j, "strategy"), text(j, "tag"), rubric,
                j.path("risk").asBoolean(), j.path("passRate").asDouble(),
                j.path("passed").asBoolean(), trials, dir);
    }
}
