package org.kendar.llm.eval.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.eval.EvalLog;
import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.StreamListener;
import org.kendar.llm.harness.llm.Usage;
import org.kendar.llm.harness.tools.HarnessTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Wraps a model client and records every request with the token usage of its response. */
public final class RecordingModelClient implements ModelClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ModelClient inner;
    private final String label;
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

    /** @param label names the trial in the progress log, for example {@code files/read-and-answer [compaction] #1} */
    public RecordingModelClient(ModelClient inner, String label) {
        this.inner = inner;
        this.label = label;
    }

    /** {@code turn k/n } shown on the request lines of a multi-turn case; empty for a single-turn one. */
    private volatile String turn = "";

    /** Marks the user turn being run: its requests log {@code turn k/n} when the case has more than one turn. */
    public void turn(int k, int n) {
        this.turn = n > 1 ? "turn " + k + "/" + n + " " : "";
    }

    private void log(boolean simple, long start, Usage usage, Throwable error) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        EvalLog.info("    request " + (requests.size() + 1) + (simple ? " (summary)" : "") + " " + label + " "
                + turn + EvalLog.seconds(ms) + "s"
                + (usage == null ? "" : ", tokens in " + usage.inputTokens() + " out " + usage.outputTokens()
                + (usage.cacheReadTokens() > 0 ? " cached " + usage.cacheReadTokens() : ""))
                + (error == null ? "" : ", ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage()));
    }

    public List<RecordedRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private void record(ModelRequest r, ModelResponse resp, long start, Throwable error) {
        boolean side = RecordedRequest.handoffNotes(r.messages());
        log(side, start, resp == null ? null : resp.usage(), error);
        requests.add(new RecordedRequest(requests.size(), side, r.pinnedSystem(), r.extraSystem(),
                r.messages().deepCopy(), r.tools().stream().map(HarnessTool::name).toList(), r.maxTokens(),
                resp == null ? null : resp.usage(), (System.nanoTime() - start) / 1_000_000));
    }

    @Override
    public ModelResponse complete(ModelRequest r) {
        long t = System.nanoTime();
        ModelResponse resp = null;
        Throwable err = null;
        try {
            resp = inner.complete(r);
            return resp;
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            record(r, resp, t, err);
        }
    }

    @Override
    public ModelResponse complete(ModelRequest r, CancelToken cancel) {
        long t = System.nanoTime();
        ModelResponse resp = null;
        Throwable err = null;
        try {
            resp = inner.complete(r, cancel);
            return resp;
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            record(r, resp, t, err);
        }
    }

    @Override
    public ModelResponse stream(ModelRequest r, CancelToken cancel, StreamListener listener) {
        long t = System.nanoTime();
        ModelResponse resp = null;
        Throwable err = null;
        try {
            resp = inner.stream(r, cancel, listener);
            return resp;
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            record(r, resp, t, err);
        }
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return simple(system, user, maxTokens, null, (sink) -> inner.simpleComplete(system, user, maxTokens, sink));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 Consumer<Usage> usageSink) {
        return simple(system, user, maxTokens, usageSink,
                (sink) -> inner.simpleComplete(system, user, maxTokens, temperature, sink));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Consumer<Usage> usageSink) {
        return simple(system, user, maxTokens, usageSink,
                (sink) -> inner.simpleComplete(system, user, maxTokens, sink));
    }

    private String simple(String system, String user, int maxTokens, Consumer<Usage> outer,
                          java.util.function.Function<Consumer<Usage>, String> call) {
        long t = System.nanoTime();
        Usage[] seen = new Usage[1];
        Throwable err = null;
        try {
            return call.apply(u -> {
                seen[0] = seen[0] == null ? u : seen[0].plus(u);
                if (outer != null) outer.accept(u);
            });
        } catch (RuntimeException e) {
            err = e;
            throw e;
        } finally {
            log(true, t, seen[0], err);
            requests.add(new RecordedRequest(requests.size(), true, system, "", MAPPER.createArrayNode().add(user),
                    List.of(), maxTokens, seen[0], (System.nanoTime() - t) / 1_000_000));
        }
    }
}
