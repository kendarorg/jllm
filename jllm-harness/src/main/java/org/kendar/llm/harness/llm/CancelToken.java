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
        public Runnable onCancel(Runnable hook) {
            return () -> {
            };
        }
    };

    private volatile boolean cancelled;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled;
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
        if (cancelled) throw new ModelCancelledException();
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
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
