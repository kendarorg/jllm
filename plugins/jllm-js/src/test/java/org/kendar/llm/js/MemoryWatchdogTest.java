package org.kendar.llm.js;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MemoryWatchdogTest {

    /** Runs on its own thread (the clock reads that thread's allocation counter), allocates, then waits. */
    private static final class Runner {
        final AtomicReference<RunClock> clock = new AtomicReference<>();
        final AtomicBoolean killed = new AtomicBoolean();
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(1);
        final Thread thread;
        volatile Object keep;

        Runner(int megabytes) {
            thread = new Thread(() -> {
                clock.set(new RunClock(60_000, 1000, 0, 1L << 40, null, null, () -> killed.set(true)));
                byte[][] blocks = new byte[megabytes][];
                for (int i = 0; i < megabytes; i++) blocks[i] = new byte[1024 * 1024];
                keep = blocks;
                ready.countDown();
                try {
                    end.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Test
    void theHeapGuardKillsTheRunThatAllocatedTheMost() throws Exception {
        Assumptions.assumeTrue(MemoryWatchdog.supported(), "no thread allocation counter on this JVM");
        Runner small = new Runner(1);
        Runner big = new Runner(40);
        assertTrue(small.ready.await(30, TimeUnit.SECONDS) && big.ready.await(30, TimeUnit.SECONDS));
        try {
            assertTrue(big.clock.get().allocatedBytes() > small.clock.get().allocatedBytes());

            assertTrue(MemoryWatchdog.killBiggest("heap nearly full"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!big.killed.get() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(big.killed.get(), "the biggest allocator was killed");
            assertFalse(small.killed.get(), "the small one was left alone");
            assertEquals("heap nearly full", big.clock.get().killedForMemory());
        } finally {
            small.clock.get().close();
            big.clock.get().close();
            small.end.countDown();
            big.end.countDown();
        }
    }

    @Test
    void withoutRunsThereIsNothingToKill() {
        assertFalse(MemoryWatchdog.killBiggest("none"));
    }

    @Test
    void theGuardInstallsOnceAndNeverThrows() {
        MemoryWatchdog.installGuard(0.85);
        MemoryWatchdog.installGuard(0.5);
    }
}
