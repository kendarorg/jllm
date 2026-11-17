package org.kendar.llm.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The permanent, compact history of the evals: one recap per run, named {@code <date>_<model>} ({@code .md} for
 * reading, {@code .json} for tools), and an {@code INDEX.md} listing every run, oldest first. Traces stay in the run
 * folders and can be deleted; the history is small enough to commit.
 */
public final class History {

    private History() {
    }

    /**
     * Copies the report and the summary of a finished run (after {@link ReportBuilder#build}) into {@code historyDir},
     * adds target and commit from run.json, and rewrites the index.
     *
     * @return the recap markdown file
     */
    public static Path record(Path runDir, Path historyDir) {
        try {
            ObjectNode summary = (ObjectNode) ResultLoader.MAPPER.readTree(runDir.resolve("summary.json").toFile());
            JsonNode meta = ResultLoader.MAPPER.readTree(runDir.resolve("run.json").toFile());
            ObjectNode run = (ObjectNode) summary.path("run");
            run.put("target", meta.path("targetType").asText("") + ":" + meta.path("targetName").asText(""));
            run.put("quantization", meta.path("quantization").asText(""));
            run.put("contextTokens", meta.path("contextTokens").asInt());
            run.put("commit", meta.path("gitCommit").asText("unknown"));
            String name = stamp(run.path("runFolder").asText(runDir.getFileName().toString())) + "_"
                    + ReportBuilder.safe(run.path("model").asText("model"));
            Files.createDirectories(historyDir);
            Files.writeString(historyDir.resolve(name + ".json"),
                    ResultLoader.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                    StandardCharsets.UTF_8);
            Path md = historyDir.resolve(name + ".md");
            Files.writeString(md, Files.readString(runDir.resolve("report.md"), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
            rebuildIndex(historyDir);
            return md;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code run-20261117-143000} to {@code 20261117-143000}. */
    static String stamp(String runFolder) {
        return runFolder.startsWith("run-") ? runFolder.substring(4) : runFolder;
    }

    /** Rewrites INDEX.md from the recap json files, oldest first. */
    public static Path rebuildIndex(Path historyDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(historyDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        }
        StringBuilder sb = new StringBuilder("# Eval history\n\n");
        sb.append("| date | model | target | judge | cases | pass rate | pass^k | tokens/trial | vs baseline | recap |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Path f : files) {
            JsonNode s = ResultLoader.MAPPER.readTree(f.toFile());
            JsonNode r = s.path("run");
            JsonNode o = s.path("overall");
            JsonNode b = s.path("baseline");
            String judge = r.path("judgeModel").asText() + (r.path("selfGraded").asBoolean() ? " (self-graded)" : "");
            String delta = b.path("present").asBoolean()
                    ? String.format("%+.1f pts", b.path("overallDelta").asDouble() * 100) : "no baseline";
            String base = f.getFileName().toString();
            base = base.substring(0, base.length() - 5);
            sb.append("| ").append(base, 0, Math.min(15, base.length())).append(" | ").append(r.path("model").asText())
                    .append(" | ").append(r.path("target").asText()).append(" | ").append(judge)
                    .append(" | ").append(o.path("cases").asInt())
                    .append(" | ").append(String.format("%.1f%%", o.path("passRate").asDouble() * 100))
                    .append(" | ").append(String.format("%.1f%%", o.path("passKRate").asDouble() * 100))
                    .append(" | ").append(Math.round(o.path("tokens").path("perTrial").asDouble()))
                    .append(" | ").append(delta).append(" | [").append(base).append(".md](").append(base)
                    .append(".md) |\n");
        }
        Path index = historyDir.resolve("INDEX.md");
        Files.writeString(index, sb.toString(), StandardCharsets.UTF_8);
        return index;
    }
}
