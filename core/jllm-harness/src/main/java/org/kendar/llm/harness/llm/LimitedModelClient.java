package org.kendar.llm.harness.llm;

import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Caps the number of model calls in flight across the whole harness (chats, sub-agents, compaction, tool selection),
 * e.g. 1 for a local server with a single small GPU. Callers wait for a free slot; a cancelled token ends the wait.
 */
public final class LimitedModelClient implements ModelClient {

    private final ModelClient delegate;
    private final Semaphore slots;

    public LimitedModelClient(ModelClient delegate, int maxConcurrent) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be >= 1");
        this.delegate = delegate;
        this.slots = new Semaphore(maxConcurrent, true);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return limited(CancelToken.NONE, () -> delegate.complete(request));
    }

    @Override
    public ModelResponse complete(ModelRequest request, CancelToken cancel) {
        return limited(cancel, () -> delegate.complete(request, cancel));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens) {
        return limited(CancelToken.NONE, () -> delegate.simpleComplete(system, user, maxTokens));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Double temperature,
                                 Consumer<Usage> usageSink) {
        return limited(CancelToken.NONE, () -> delegate.simpleComplete(system, user, maxTokens, temperature, usageSink));
    }

    @Override
    public String simpleComplete(String system, String user, int maxTokens, Consumer<Usage> usageSink) {
        return limited(CancelToken.NONE, () -> delegate.simpleComplete(system, user, maxTokens, usageSink));
    }

    private <T> T limited(CancelToken cancel, Supplier<T> call) {
        Waiter waiter = new Waiter(Thread.currentThread());
        Runnable unhook = cancel.onCancel(waiter);
        boolean acquired = false;
        try {
            slots.acquire();
            acquired = true;
        } catch (InterruptedException e) {
            // handled below
        } finally {
            // after this no hook (not even one already snapshotted by cancel()) can interrupt the thread
            waiter.stopWaiting();
            unhook.run();
        }
        boolean fromToken = waiter.fired();
        if (fromToken) Thread.interrupted(); // the token's interrupt must not leak into the call or the caller
        if (!acquired) {
            if (!fromToken) Thread.currentThread().interrupt(); // someone else's interrupt: keep it
            if (fromToken || cancel.isCancelled()) throw new ModelCancelledException();
            throw new ModelException("Interrupted while waiting for a model slot");
        }
        try {
            if (fromToken) throw new ModelCancelledException(); // cancelled while the slot was being taken
            return call.get();
        } finally {
            slots.release();
        }
    }

    /** Cancel hook that interrupts the thread only while it still waits for a slot. */
    private static final class Waiter implements Runnable {
        private final Thread thread;
        private boolean waiting = true;
        private boolean fired;

        Waiter(Thread thread) {
            this.thread = thread;
        }

        @Override
        public synchronized void run() {
            if (!waiting) return;
            fired = true;
            thread.interrupt();
        }

        synchronized void stopWaiting() {
            waiting = false;
        }

        synchronized boolean fired() {
            return fired;
        }
    }
}
