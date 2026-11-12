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
        Thread waiter = Thread.currentThread();
        Runnable unhook = cancel.onCancel(waiter::interrupt);
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            if (cancel.isCancelled()) {
                Thread.interrupted(); // the interrupt came from the token
                throw new ModelCancelledException();
            }
            waiter.interrupt();
            throw new ModelException("Interrupted while waiting for a model slot");
        } finally {
            unhook.run();
        }
        try {
            return call.get();
        } finally {
            slots.release();
        }
    }
}
