package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.workflows.ActionProvider;
import org.kendar.llm.workflows.Registry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java actions and the chooser of the three sample flows, registered through
 * {@code META-INF/services/org.kendar.llm.workflows.ActionProvider}. Every run is logged as {@code name} or
 * {@code name:detail} under its run id, so a check can see which actions of its own runs really ran.
 */
public final class SampleFlowActions implements ActionProvider {

    private static final ObjectMapper M = new ObjectMapper();
    /** Every execution of every run, in order, tagged with its run id; runs of concurrent trials interleave here. */
    private static final List<Entry> LOG = new ArrayList<>();

    private record Entry(String runId, String what) {
    }

    /** Executions of the runs stored in {@code runsDir} (a {@code RunStore.Files} folder), in order. */
    public static List<String> executions(Path runsDir) {
        Set<String> runs = runIds(runsDir);
        synchronized (LOG) {
            return LOG.stream().filter(e -> runs.contains(e.runId)).map(Entry::what).toList();
        }
    }

    /** Drops the executions of the runs stored in {@code runsDir}. */
    public static void forget(Path runsDir) {
        Set<String> runs = runIds(runsDir);
        synchronized (LOG) {
            LOG.removeIf(e -> runs.contains(e.runId));
        }
    }

    private static Set<String> runIds(Path runsDir) {
        if (!Files.isDirectory(runsDir)) return Set.of();
        try (Stream<Path> s = Files.list(runsDir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void log(String runId, String s) {
        synchronized (LOG) {
            LOG.add(new Entry(runId, s));
        }
    }

    @Override
    public void register(Registry registry) {
        registry
                .action("normalizeText", call -> {
                    log(call.runId(), "normalizeText");
                    String t = call.context().input().path("text").asText().strip().toUpperCase();
                    return M.createObjectNode().put("text", t);
                })
                .action("countWords", call -> {
                    log(call.runId(), "countWords");
                    String t = call.context().input().path("text").asText().strip();
                    return M.createObjectNode().put("words", t.isEmpty() ? 0 : t.split("\\s+").length);
                })
                .chooser("amountBand", call -> {
                    int amount = call.context().input().path("amount").asInt();
                    String band = amount <= 100 ? "small" : amount <= 1000 ? "large" : "huge";
                    log(call.runId(), "amountBand:" + band);
                    return band;
                })
                .action("approve", call -> {
                    log(call.runId(), "approve");
                    return M.createObjectNode().put("ok", true);
                })
                .action("flagReview", call -> {
                    log(call.runId(), "flagReview");
                    return M.createObjectNode().put("ok", true);
                })
                .action("loadOrder", call -> {
                    String order = call.context().input().path("order").asText();
                    log(call.runId(), "loadOrder:" + order);
                    ObjectNode o = M.createObjectNode();
                    o.put("order", order).put("status", "shipped");
                    return o;
                });
    }
}
