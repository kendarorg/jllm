package org.kendar.llm.harness.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Transport of a streamed (server-sent events) model call, shared by the clients: sends, retries like the
 * non-streamed path, and hands the event stream to an {@link Assembler} that rebuilds the response JSON a non-streamed
 * call would have returned.
 */
final class StreamingCall {

    private static final Logger log = LoggerFactory.getLogger(StreamingCall.class);

    /** One server-sent event: {@code event} may be null (OpenAI sends data lines only). */
    record Event(String event, String data) {
    }

    /** Reads the events of one attempt and returns the assembled response JSON. */
    @FunctionalInterface
    interface Assembler {
        JsonNode read(Events events, StreamListener listener) throws IOException;
    }

    /** An error reported inside the stream (or a stream that ended early). */
    static final class StreamError extends IOException {
        final boolean retryable;

        StreamError(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
    }

    /** The events of a response body. */
    static final class Events {
        private final BufferedReader in;

        Events(BufferedReader in) {
            this.in = in;
        }

        /** The next event, or null at the end of the stream. Comments ({@code :}) and ids are skipped. */
        Event next() throws IOException {
            String event = null;
            StringBuilder data = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) return new Event(event, data.toString());
                    event = null;
                    continue;
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if ("event".equals(field)) event = value;
                else if ("data".equals(field)) {
                    if (data == null) data = new StringBuilder(value);
                    else data.append('\n').append(value);
                }
            }
            return data != null ? new Event(event, data.toString()) : null;
        }
    }

    /** Passes deltas on and remembers whether any was delivered in the current attempt. */
    private static final class Tracking implements StreamListener {
        private final StreamListener target;
        boolean started;

        Tracking(StreamListener target) {
            this.target = target;
        }

        @Override
        public void onText(int block, String delta) {
            started = true;
            target.onText(block, delta);
        }

        @Override
        public void onThinking(int block, String delta) {
            started = true;
            target.onThinking(block, delta);
        }

        @Override
        public void onToolCall(int block, String id, String name, String argsDelta) {
            started = true;
            target.onToolCall(block, id, name, argsDelta);
        }
    }

    /**
     * Thrown when the stream failed after deltas were delivered and no retry is left: a fallback provider must not
     * start over in the middle of what was shown.
     */
    static final class AfterFirstDelta extends ModelException {
        AfterFirstDelta(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private StreamingCall() {
    }

    /**
     * @param httpError     status: the message prefix of an HTTP error ({@code "<name> API error <status>"})
     * @param callFailed    message prefix of a network failure
     * @param retryableStatus statuses retried with backoff
     */
    static JsonNode run(HttpClient http, Supplier<HttpRequest> build, org.kendar.llm.harness.auth.AuthProvider auth,
                        CancelToken cancel, StreamListener listener, int maxRetries, long backoffBaseMs,
                        IntPredicate retryableStatus, IntFunction<String> httpError, String callFailed,
                        Supplier<Assembler> assembler) {
        Tracking tracking = new Tracking(listener);
        HttpRequest request = build.get();
        boolean authRetried = false;
        int attempt = 0;
        RetryBudget budget = new RetryBudget();
        while (true) {
            try {
                HttpResponse<InputStream> resp = cancel.sendStreaming(http, request);
                int status = resp.statusCode();
                if (status == 200) {
                    InputStream body = resp.body();
                    Runnable unhook = cancel.onCancel(() -> close(body));
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                        tracking.started = false;
                        return assembler.get().read(new Events(r), tracking);
                    } catch (IOException e) {
                        if (cancel.isCancelled()) throw new ModelCancelledException();
                        boolean retryable = !(e instanceof StreamError se) || se.retryable;
                        if (!retryable || attempt >= maxRetries) {
                            String m = e instanceof StreamError ? e.getMessage() : callFailed + e.getMessage();
                            if (tracking.started) throw new AfterFirstDelta(m, e);
                            throw new ModelException(m, e);
                        }
                        if (tracking.started) {
                            tracking.started = false;
                            listener.onReset();
                        }
                        attempt++;
                        long delay = budget.reserve(attempt, backoffBaseMs, Optional.empty())
                                .orElseThrow(() -> new ModelException(e.getMessage()
                                        + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS + " ms exhausted)", e));
                        log.warn("Model stream broke ({}): retry {} in {} ms", e.getMessage(), attempt, delay);
                        cancel.retrying(0, attempt, maxRetries, delay);
                        cancel.sleep(delay);
                        continue;
                    } finally {
                        unhook.run();
                    }
                }
                String text = readAll(resp.body());
                if (status == 401 && auth != null && !authRetried) {
                    authRetried = true;
                    auth.onUnauthorized();
                    request = build.get();
                    continue;
                }
                if (!retryableStatus.test(status) || attempt >= maxRetries) {
                    throw new ModelException(httpError.apply(status) + ": " + truncate(text) + rateLimitHint(resp));
                }
                attempt++;
                long delayMs = budget.reserve(attempt, backoffBaseMs,
                                resp.headers().firstValue("retry-after").map(StreamingCall::parseSeconds))
                        .orElseThrow(() -> new ModelException(httpError.apply(status) + ": " + truncate(text) + rateLimitHint(resp)
                                + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS + " ms exhausted)"));
                log.warn("{} - retry {} in {} ms{}", httpError.apply(status), attempt, delayMs, rateLimitHint(resp));
                cancel.retrying(status, attempt, maxRetries, delayMs);
                cancel.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while calling the model", e);
            } catch (IOException e) {
                if (cancel.isCancelled()) throw new ModelCancelledException();
                if (attempt >= maxRetries) throw new ModelException(callFailed + e.getMessage(), e);
                attempt++;
                long netDelay = budget.reserve(attempt, backoffBaseMs, Optional.empty())
                        .orElseThrow(() -> new ModelException(callFailed + e.getMessage()
                                + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS + " ms exhausted)", e));
                cancel.retrying(0, attempt, maxRetries, netDelay);
                try {
                    cancel.sleep(netDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ModelException("Interrupted while calling the model", ie);
                }
            }
        }
    }

    /**
     * The non-streamed call: sends, retries with backoff on network errors and {@code retryableStatus}, refreshes the
     * credentials once on 401, and returns the body of the 200 response. Parameters as for {@link #run}.
     */
    static String send(HttpClient http, Supplier<HttpRequest> build, org.kendar.llm.harness.auth.AuthProvider auth,
                       CancelToken cancel, int maxRetries, long backoffBaseMs, IntPredicate retryableStatus,
                       IntFunction<String> httpError, String callFailed) {
        HttpRequest request = build.get();
        boolean authRetried = false;
        int attempt = 0;
        RetryBudget budget = new RetryBudget();
        while (true) {
            try {
                HttpResponse<String> resp = cancel.send(http, request);
                int status = resp.statusCode();
                if (status == 200) return resp.body();
                if (status == 401 && auth != null && !authRetried) {
                    authRetried = true;
                    auth.onUnauthorized();
                    request = build.get();
                    continue;
                }
                if (!retryableStatus.test(status) || attempt >= maxRetries) {
                    throw new ModelException(httpError.apply(status) + ": " + truncate(resp.body()) + rateLimitHint(resp));
                }
                attempt++;
                long delayMs = budget.reserve(attempt, backoffBaseMs,
                                resp.headers().firstValue("retry-after").map(StreamingCall::parseSeconds))
                        .orElseThrow(() -> new ModelException(httpError.apply(status) + ": " + truncate(resp.body())
                                + rateLimitHint(resp) + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS
                                + " ms exhausted)"));
                log.warn("{} - retry {} in {} ms{}", httpError.apply(status), attempt, delayMs, rateLimitHint(resp));
                cancel.retrying(status, attempt, maxRetries, delayMs);
                cancel.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while calling the model", e);
            } catch (IOException e) {
                if (attempt >= maxRetries) throw new ModelException(callFailed + e.getMessage(), e);
                attempt++;
                long netDelay = budget.reserve(attempt, backoffBaseMs, Optional.empty())
                        .orElseThrow(() -> new ModelException(callFailed + e.getMessage()
                                + " (retry wait budget of " + RetryBudget.MAX_WAIT_MS + " ms exhausted)", e));
                cancel.retrying(0, attempt, maxRetries, netDelay);
                try {
                    cancel.sleep(netDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ModelException("Interrupted while calling the model", ie);
                }
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort: the reader fails and sees the cancel
        }
    }

    /**
     * The headers a provider uses to say when to come back ({@code retry-after}, {@code x-ratelimit-*}), as a suffix
     * for an error message: {@code " [retry-after=30, x-ratelimit-reset-requests=12s]"}; empty when there are none.
     */
    static String rateLimitHint(java.net.http.HttpResponse<?> resp) {
        StringBuilder sb = new StringBuilder();
        resp.headers().map().forEach((k, v) -> {
            String n = k.toLowerCase(java.util.Locale.ROOT);
            if (n.equals("retry-after") || n.startsWith("x-ratelimit") || n.startsWith("ratelimit")) {
                sb.append(sb.isEmpty() ? " [" : ", ").append(n).append('=').append(String.join("|", v));
            }
        });
        return sb.isEmpty() ? "" : sb + "]";
    }

    private static long parseSeconds(String v) {
        try {
            return Math.min(120_000L, (long) (Double.parseDouble(v.trim()) * 1000));
        } catch (NumberFormatException e) {
            return 2000L;
        }
    }

    static String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 1000) + "…" : s;
    }
}
