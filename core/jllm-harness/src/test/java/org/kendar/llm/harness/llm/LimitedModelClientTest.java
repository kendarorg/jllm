package org.kendar.llm.harness.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LimitedModelClientTest {

    /** Counts how many simpleComplete calls overlap. */
    private static final class Probe implements ModelClient {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger max = new AtomicInteger();

        @Override
        public ModelResponse complete(ModelRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            max.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            active.decrementAndGet();
            return user;
        }
    }

    private static int run(int limit, int calls) throws Exception {
        Probe probe = new Probe();
        ModelClient client = new LimitedModelClient(probe, limit);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            threads.add(Thread.ofVirtual().start(() -> client.simpleComplete("s", "u", 1)));
        }
        for (Thread t : threads) t.join();
        return probe.max.get();
    }

    @Test
    void oneSlotSerializesAllCalls() throws Exception {
        assertEquals(1, run(1, 6));
    }

    @Test
    void limitIsRespected() throws Exception {
        assertEquals(2, run(2, 6));
    }

    @Test
    void cancelledTokenEndsTheWaitForASlot() throws Exception {
        Probe probe = new Probe();
        LimitedModelClient client = new LimitedModelClient(probe, 1);
        Thread holder = Thread.ofVirtual().start(() -> client.simpleComplete("s", "u", 1));
        Thread.sleep(10);
        CancelToken token = new CancelToken();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
            token.cancel();
        });
        assertThrows(ModelCancelledException.class, () -> client.complete(null, token));
        assertFalse(Thread.currentThread().isInterrupted(), "the token's interrupt must not leak");
        holder.join();
    }

    /** Answers complete() reporting whether its thread was interrupted. */
    private static final class InterruptProbe implements ModelClient {
        final AtomicInteger interrupted = new AtomicInteger();

        @Override
        public ModelResponse complete(ModelRequest request) {
            if (Thread.currentThread().isInterrupted()) interrupted.incrementAndGet();
            return null;
        }

        @Override
        public ModelResponse complete(ModelRequest request, CancelToken cancel) {
            return complete(request);
        }

        @Override
        public String simpleComplete(String system, String user, int maxTokens) {
            return user;
        }
    }

    @Test
    void cancelRacingTheSlotNeverLeavesAnInterruptOrAPermit() throws Exception {
        InterruptProbe probe = new InterruptProbe();
        LimitedModelClient client = new LimitedModelClient(probe, 1);
        AtomicInteger leaked = new AtomicInteger();
        for (int i = 0; i < 500; i++) {
            CancelToken token = new CancelToken();
            Thread canceller = Thread.ofPlatform().start(token::cancel);
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    client.complete(null, token);
                } catch (ModelCancelledException ignored) {
                    // expected when the cancel wins
                }
                try {
                    Thread.sleep(2); // a late hook from the token would land here
                } catch (InterruptedException e) {
                    leaked.incrementAndGet();
                }
                if (Thread.interrupted()) leaked.incrementAndGet();
            });
            canceller.join();
            caller.join();
        }
        assertEquals(0, leaked.get());
        assertEquals(0, probe.interrupted.get());
        // every permit came back: a call with a fresh token still gets the only slot
        Thread t = Thread.ofPlatform().start(() -> client.complete(null, new CancelToken()));
        t.join(2000);
        assertFalse(t.isAlive());
    }
}
