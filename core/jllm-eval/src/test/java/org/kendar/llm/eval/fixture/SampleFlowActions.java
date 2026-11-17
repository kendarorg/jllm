package org.kendar.llm.eval.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.llm.workflows.ActionProvider;
import org.kendar.llm.workflows.Registry;

import java.util.ArrayList;
import java.util.List;

/**
 * Java actions and the chooser of the three sample flows, registered through
 * {@code META-INF/services/org.kendar.llm.workflows.ActionProvider}. Every run is logged as {@code name} or
 * {@code name:detail}, so a check can see which actions really ran.
 */
public final class SampleFlowActions implements ActionProvider {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> LOG = new ArrayList<>();

    /** Executions since the last {@link #reset()}, in order. */
    public static List<String> executions() {
        synchronized (LOG) {
            return List.copyOf(LOG);
        }
    }

    public static void reset() {
        synchronized (LOG) {
            LOG.clear();
        }
    }

    private static void log(String s) {
        synchronized (LOG) {
            LOG.add(s);
        }
    }

    @Override
    public void register(Registry registry) {
        registry
                .action("normalizeText", call -> {
                    log("normalizeText");
                    String t = call.context().input().path("text").asText().strip().toUpperCase();
                    return M.createObjectNode().put("text", t);
                })
                .action("countWords", call -> {
                    log("countWords");
                    String t = call.context().input().path("text").asText().strip();
                    return M.createObjectNode().put("words", t.isEmpty() ? 0 : t.split("\\s+").length);
                })
                .chooser("amountBand", call -> {
                    int amount = call.context().input().path("amount").asInt();
                    String band = amount <= 100 ? "small" : amount <= 1000 ? "large" : "huge";
                    log("amountBand:" + band);
                    return band;
                })
                .action("approve", call -> {
                    log("approve");
                    return M.createObjectNode().put("ok", true);
                })
                .action("flagReview", call -> {
                    log("flagReview");
                    return M.createObjectNode().put("ok", true);
                })
                .action("loadOrder", call -> {
                    String order = call.context().input().path("order").asText();
                    log("loadOrder:" + order);
                    ObjectNode o = M.createObjectNode();
                    o.put("order", order).put("status", "shipped");
                    return o;
                });
    }
}
