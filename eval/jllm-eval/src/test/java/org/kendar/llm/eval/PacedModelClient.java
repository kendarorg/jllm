package org.kendar.llm.eval;

import org.kendar.llm.harness.llm.CancelToken;
import org.kendar.llm.harness.llm.ModelClient;
import org.kendar.llm.harness.llm.ModelRequest;
import org.kendar.llm.harness.llm.ModelResponse;
import org.kendar.llm.harness.llm.ModelException;
import org.kendar.llm.harness.llm.StreamListener;
import org.kendar.llm.harness.llm.Usage;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Waits for the pacer's slot before each model call. Every entry point paces once; the defaults route through these. */
final class PacedModelClient implements ModelClient {

    private final ModelClient inner;
    private final RequestPacer pacer;
    private final int rateLimitRetries;
    private final long rateLimitWaitMs;
    private final long rateLimitStepMs;

    PacedModelClient(ModelClient inner, RequestPacer pacer) {
        this(inner, pacer, 5, 10_000L, 5_000L);
    }

    /**
     * @param rateLimitRetries how many times a call answered with HTTP 429 is repeated (0: never)
     * @param rateLimitWaitMs  pause of the whole host before the first repeat
     * @param rateLimitStepMs  added to the pause of every further repeat (10 s, 15 s, 20 s with the defaults)
     */
    PacedModelClient(ModelClient inner, RequestPacer pacer, int rateLimitRetries, long rateLimitWaitMs,
                     long rateLimitStepMs) {
        this.inner = inner;
        this.pacer = pacer;
        this.rateLimitRetries = rateLimitRetries;
        this.rateLimitWaitMs = rateLimitWaitMs;
        this.rateLimitStepMs = rateLimitStepMs;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return call(null, () -> inner.complete(request));
    }

    @Override
    public ModelResponse complete(ModelRequest request, CancelToken cancel) {
        return call(cancel, () -> inner.complete(request, cancel));
    }

    @Override
    public ModelResponse stream(ModelRequest request, CancelToken cancel, StreamListener listener) {
        return call(cancel, () -> inner.stream(request, cancel, listener));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return call(null, () -> inner.simpleComplete(system, user, maxTokens));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Consumer<Usage> usageSink) {
        return call(null, () -> inner.simpleComplete(system, user, maxTokens, usageSink));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 Consumer<Usage> usageSink) {
        return call(null, () -> inner.simpleComplete(system, user, maxTokens, temperature, usageSink));
    }

    /**
     * One paced call. A 429 pauses every caller of the host for the wait, then the call is repeated (through the pacer
     * again); the harness client's own retry only covers a few seconds, which a limited provider outlasts.
     */
    private <T> T call(CancelToken cancel, Supplier<T> body) {
        for (int attempt = 0; ; attempt++) {
            pace(cancel);
            try {
                return body.get();
            } catch (ModelException e) {
                if (attempt >= rateLimitRetries || !isRateLimited(e)) throw e;
                long scheduled = rateLimitWaitMs + attempt * rateLimitStepMs;
                long told = retryAfterMs(e);
                long pause = Math.max(scheduled, told);
                EvalLog.info("rate limited (429): pausing the host " + EvalLog.seconds(pause) + "s"
                        + (told > scheduled ? " (the provider asked for " + EvalLog.seconds(told) + "s)" : "")
                        + ", retry " + (attempt + 1) + "/" + rateLimitRetries + hint(e));
                pacer.pauseMs(pause);
            }
        }
    }

    private static final java.util.regex.Pattern RETRY_AFTER =
            java.util.regex.Pattern.compile("retry-after=(\\d+(?:\\.\\d+)?)");

    /** The wait the provider asked for in its retry-after header (the harness puts it in the message), 0 if none; at most 2 minutes. */
    static long retryAfterMs(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            var mt = RETRY_AFTER.matcher(m);
            if (mt.find()) return Math.min(120_000L, (long) (Double.parseDouble(mt.group(1)) * 1000));
        }
        return 0;
    }

    /** The rate-limit headers the error carries, to show in the log. */
    private static String hint(Throwable e) {
        String m = e.getMessage();
        int i = m == null ? -1 : m.lastIndexOf(" [");
        return i >= 0 && m.endsWith("]") ? m.substring(i) : "";
    }

    static boolean isRateLimited(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("API error 429") || m.contains("Too Many Requests"))) return true;
        }
        return false;
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
