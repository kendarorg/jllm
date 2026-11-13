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
                        long delay = Math.min(30_000L, backoffBaseMs << attempt);
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
                    throw new ModelException(httpError.apply(status) + ": " + truncate(text));
                }
                long delayMs = resp.headers().firstValue("retry-after").map(StreamingCall::parseSeconds)
                        .orElse(Math.min(30_000L, backoffBaseMs << attempt));
                attempt++;
                cancel.retrying(status, attempt, maxRetries, delayMs);
                cancel.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted while calling the model", e);
            } catch (IOException e) {
                if (cancel.isCancelled()) throw new ModelCancelledException();
                if (attempt >= maxRetries) throw new ModelException(callFailed + e.getMessage(), e);
                attempt++;
                long netDelay = Math.min(30_000L, backoffBaseMs << attempt);
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
