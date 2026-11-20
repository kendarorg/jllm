package org.kendar.llm.eval.report;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class HistoryTest {

    @TempDir
    Path tmp;

    private Path fakeRun(String folder, String model, double passRate) throws Exception {
        Path run = tmp.resolve("results").resolve(model).resolve(folder);
        Files.createDirectories(run);
        Files.writeString(run.resolve("summary.json"), "{\"run\":{\"model\":\"" + model + "\",\"judgeModel\":\"j\","
                + "\"selfGraded\":false,\"startTime\":\"2026-11-17T14:30:00Z\",\"runFolder\":\"" + folder + "\"},\"overall\":{\"cases\":4,\"passRate\":"
                + passRate + ",\"passKRate\":0.5,\"tokens\":{\"perTrial\":1234}},\"baseline\":{\"present\":false}}");
        Files.writeString(run.resolve("run.json"),
                "{\"targetType\":\"remote\",\"targetName\":\"mac\",\"quantization\":\"q4\",\"contextTokens\":65536,"
                        + "\"gitCommit\":\"abc\"}");
        Files.writeString(run.resolve("report.md"), "# report " + model);
        return run;
    }

    @Test
    void recapIsNamedByDateAndModelAndIndexListsRunsOldestFirst() throws Exception {
        Path hist = tmp.resolve("history");
        Path md = History.record(fakeRun("run-20261117-143000", "qwen3.6-27b", 0.75), hist);
        History.record(fakeRun("run-20261101-090000", "claude-haiku-5-5", 0.9), hist);

        assertEquals("20261117-143000_qwen3.6-27b.md", md.getFileName().toString());
        assertTrue(Files.exists(hist.resolve("20261117-143000_qwen3.6-27b.json")));
        assertEquals("# report qwen3.6-27b", Files.readString(md));
        String index = Files.readString(hist.resolve("INDEX.md"));
        assertTrue(index.indexOf("claude-haiku-5-5") < index.indexOf("qwen3.6-27b"), index);
        assertTrue(index.contains("75.0%") && index.contains("remote:mac") && index.contains("no baseline"));
        assertTrue(index.contains("| start | end |"), index);
        assertTrue(index.contains("| " + History.localTime("2026-11-17T14:30:00Z") + " | "), index);
        assertTrue(!index.contains("| - |"), index);
        String json = Files.readString(hist.resolve("20261117-143000_qwen3.6-27b.json"));
        assertTrue(json.contains("\"commit\" : \"abc\"") && json.contains("\"contextTokens\" : 65536"));
    }

    @Test
    void aRunStoredAsBaselineIsListedAsTheBaseline() throws Exception {
        Path hist = tmp.resolve("history");
        Path run = fakeRun("run-20261117-143000", "qwen3.6-27b", 0.75);
        Files.writeString(run.resolve("summary.json"), Files.readString(run.resolve("summary.json"))
                .replace("\"baseline\":{\"present\":false}", "\"baseline\":{\"present\":false,\"updated\":true}"));
        History.record(run, hist);

        String index = Files.readString(hist.resolve("INDEX.md"));
        assertTrue(index.contains("| baseline | [20261117-143000_qwen3.6-27b.md]"), index);
    }

    @Test
    void indexKeepsTheRowsOfRecapsWhoseJsonIsNotHere() throws Exception {
        Path hist = tmp.resolve("history");
        History.record(fakeRun("run-20261101-090000", "claude-haiku-5-5", 0.9), hist);
        // a fresh clone: the Markdown is committed, the json is not
        Files.delete(hist.resolve("20261101-090000_claude-haiku-5-5.json"));
        History.record(fakeRun("run-20261117-143000", "qwen3.6-27b", 0.75), hist);

        String index = Files.readString(hist.resolve("INDEX.md"));
        assertTrue(index.contains("[20261101-090000_claude-haiku-5-5.md](20261101-090000_claude-haiku-5-5.md)"), index);
        assertTrue(index.indexOf("claude-haiku-5-5") < index.indexOf("qwen3.6-27b"), index);
        assertEquals(1, index.split("20261117-143000_qwen3.6-27b.md\\]").length - 1, index);
    }
}
