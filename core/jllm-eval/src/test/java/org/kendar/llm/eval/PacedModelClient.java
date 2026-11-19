package org.kendar.llm.eval;

import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.llm.StreamListener;
import org.kendar.llm.harness.llm.Usage;

import java.util.function.Consumer;

/** Waits for the pacer's slot before each model call. Every entry point paces once; the defaults route through these. */
final class PacedModelClient implements ModelClient {

    private final ModelClient inner;
    private final RequestPacer pacer;

    PacedModelClient(ModelClient inner, RequestPacer pacer) {
        this.inner = inner;
        this.pacer = pacer;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        pace(null);
        return inner.complete(request);
    }

    @Override
    public ModelResponse complete(ModelRequest request, CancelToken cancel) {
        pace(cancel);
        return inner.complete(request, cancel);
    }

    @Override
    public ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
        pace(cancel);
        return inner.stream(request, cancel, listener);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        pace(null);
        return inner.simpleComplete(system, user, maxTokens);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Consumer<Usage> usageSink) {
        pace(null);
        return inner.simpleComplete(system, user, maxTokens, usageSink);
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 Consumer<Usage> usageSink) {
        pace(null);
        return inner.simpleComplete(system, user, maxTokens, temperature, usageSink);
    }

    private void pace(CancelToken cancel) {
        long waitMs = pacer.reserveMs();
        if (waitMs <= 0) return;
        if (waitMs >= 2_000) EvalLog.info("rate limit: waiting " + EvalLog.seconds(waitMs) + "s for a request slot");
        try {
            if (cancel != null) {
                cancel.sleep(waitMs);
            } else {
                Thread.sleep(waitMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted while waiting for the request-rate slot", e);
        }
    }
}
