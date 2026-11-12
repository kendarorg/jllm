package org.kendar.llm.harness.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        holder.join();
    }
}
