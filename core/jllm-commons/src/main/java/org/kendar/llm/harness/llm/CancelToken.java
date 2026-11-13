package org.kendar.llm.harness.llm;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Cooperative + push cancellation handle. {@link #cancel()} runs every registered abort hook (in-flight HTTP
 * request, tool threads, ...) and wakes backoff sleeps. Doubles as a {@link BooleanSupplier} for polling code.
 */
public class CancelToken implements BooleanSupplier {

    /** Never cancelled; registering hooks is a no-op. */
    public static final CancelToken NONE = new CancelToken() {
        @Override
        public void cancel() {
        }

        @Override
        public void onRetry(RetryListener listener) {
        }

        @Override
        public Runnable onCancel(Runnable hook) {
            return () -> {
            };
        }
    };

    /** Told when a model client waits before retrying a failed call. */
    @FunctionalInterface
    public interface RetryListener {
        /**
         * @param status  HTTP status, or 0 for a network error
         * @param attempt the retry about to be made (1-based)
         * @param max     maximum number of retries
         * @param delayMs wait before it
         */
        void retrying(int status, int attempt, int max, long delayMs);
    }

    private volatile RetryListener retryListener;
    private volatile boolean cancelled;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    /** Sets the listener the clients call before each retry wait; replaces any previous one. */
    public void onRetry(RetryListener listener) {
        this.retryListener = listener;
    }

    /** Called by the clients before waiting to retry. */
    public void retrying(int status, int attempt, int max, long delayMs) {
        RetryListener l = retryListener;
        if (l == null) return;
        try {
            l.retrying(status, attempt, max, delayMs);
        } catch (RuntimeException ignored) {
            // a listener must not break the call
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private volatile boolean killRequested;

    /** Cancel and ask whatever runs for the user to stop at once: no cleanup (a script is killed, not asked). */
    public void kill() {
        killRequested = true;
        cancel();
    }

    /** True after {@link #kill()}; a plain {@link #cancel()} leaves it false. */
    public boolean isKillRequested() {
        return killRequested;
    }

    @Override
    public boolean getAsBoolean() {
        return cancelled;
    }

    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        latch.countDown();
        for (Runnable h : hooks) {
            try {
                h.run();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
    }

    /** Registers an abort hook (run immediately if already cancelled). @return a handle that unregisters it */
    public Runnable onCancel(Runnable hook) {
        hooks.add(hook);
        if (cancelled) hook.run();
        return () -> hooks.remove(hook);
    }

    /** Sleeps up to {@code ms}, returning early (with {@link ModelCancelledException}) when cancelled. */
    public void sleep(long ms) throws InterruptedException {
        if (latch.await(ms, TimeUnit.MILLISECONDS)) throw new ModelCancelledException();
    }

    /** Sends the request asynchronously and aborts it (closing the connection) if this token is cancelled. */
    public HttpResponse<String> send(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        return send(http, request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * As {@link #send(HttpClient, HttpRequest)} for a streamed body: returns once the headers are in. The caller reads
     * the stream and closes it; to abort the read on cancel it registers {@link #onCancel} with a hook that closes
     * the stream.
     */
    public HttpResponse<java.io.InputStream> sendStreaming(HttpClient http, HttpRequest request)
            throws IOException, InterruptedException {
        return send(http, request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        if (cancelled) throw new ModelCancelledException();
        CompletableFuture<HttpResponse<T>> f = http.sendAsync(request, handler);
        Runnable unregister = onCancel(() -> f.cancel(true));
        try {
            return f.get();
        } catch (CancellationException e) {
            throw new ModelCancelledException();
        } catch (InterruptedException e) {
            f.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (cancelled) throw new ModelCancelledException();
            Throwable c = e.getCause();
            if (c instanceof IOException io) throw io;
            throw new IOException(c == null ? e.getMessage() : c.toString(), c);
        } finally {
            unregister.run();
        }
    }
}
