package org.kendar.llm.js;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Watchdog of one run, all on one ticker thread. The script clock stands still while paused (an approval prompt or a
 * slow tool must not time a script out); the wall ceiling never pauses; the user's cancel gives the script a grace
 * period (its next tool call throws a catchable error, see the bridge) and then kills it. For scripts in the host's
 * heap it also reads the thread allocation counter, excluding the time spent in the bridge.
 */
final class RunClock implements AutoCloseable {

    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jllm-js-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final long limitNanos;
    private final long graceNanos;
    private final long wallNanos;
    private final long allocBudget;
    private final long threadId;
    private final BooleanSupplier cancelled;
    private final BooleanSupplier killNow;
    private final Runnable kill;
    private final long start = System.nanoTime();
    private final ScheduledFuture<?> task;
    private long used;
    private long since = start;
    private long cancelledAt = -1;
    private boolean paused;
    private long allocUsed;
    private long allocSince;
    private volatile boolean expired;
    private volatile boolean wallExpired;
    private volatile boolean killedByCancel;
    private volatile String killedForMemory;

    /**
     * @param limitMillis the script's own time, not counting pauses
     * @param wallMillis  the total time, pauses included; zero or less for none
     * @param allocBudget bytes the calling thread may allocate outside pauses; zero or less for none
     */
    RunClock(long limitMillis, long graceMillis, long wallMillis, long allocBudget, BooleanSupplier cancelled,
             BooleanSupplier killNow, Runnable kill) {
        this.limitNanos = TimeUnit.MILLISECONDS.toNanos(limitMillis);
        this.graceNanos = TimeUnit.MILLISECONDS.toNanos(graceMillis);
        this.wallNanos = wallMillis > 0 ? TimeUnit.MILLISECONDS.toNanos(wallMillis) : 0;
        this.threadId = Thread.currentThread().threadId();
        this.allocBudget = allocBudget > 0 && MemoryWatchdog.supported() ? allocBudget : 0;
        this.allocSince = this.allocBudget > 0 ? MemoryWatchdog.allocated(threadId) : 0;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.killNow = killNow == null ? () -> false : killNow;
        this.kill = kill;
        if (this.allocBudget > 0) MemoryWatchdog.register(this);
        this.task = TICKER.scheduleWithFixedDelay(this::tick, 20, 20, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        boolean fire = false;
        synchronized (this) {
            if (expired || wallExpired || killedByCancel || killedForMemory != null) return;
            long now = System.nanoTime();
            if (cancelled.getAsBoolean()) {
                if (cancelledAt < 0) cancelledAt = now;
                if (killNow.getAsBoolean() || now - cancelledAt >= graceNanos) {
                    killedByCancel = true;
                    fire = true;
                }
            }
            if (!fire && wallNanos > 0 && now - start >= wallNanos) {
                wallExpired = true;
                fire = true;
            }
            if (!fire && !paused && used + (now - since) >= limitNanos) {
                expired = true;
                fire = true;
            }
            if (!fire && !paused && allocBudget > 0 && allocatedLocked() >= allocBudget) {
                killedForMemory = "The script allocated more than " + (allocBudget / Sizes.MIB) + " MB";
                fire = true;
            }
        }
        if (fire) fire();
    }

    /** The kill may wait for the script thread to leave a tool call: never on the shared ticker. */
    private void fire() {
        task.cancel(false);
        Thread t = new Thread(kill, "jllm-js-kill");
        t.setDaemon(true);
        t.start();
    }

    private long allocatedLocked() {
        long now = MemoryWatchdog.allocated(threadId);
        return allocUsed + (paused || now < 0 ? 0 : now - allocSince);
    }

    synchronized void pause() {
        if (paused) return;
        used += System.nanoTime() - since;
        if (allocBudget > 0) allocUsed = allocatedLocked();
        paused = true;
    }

    synchronized void resume() {
        if (!paused) return;
        since = System.nanoTime();
        if (allocBudget > 0) allocSince = MemoryWatchdog.allocated(threadId);
        paused = false;
    }

    /** Bytes allocated by the script so far, bridge time excluded. */
    synchronized long allocatedBytes() {
        return allocBudget > 0 ? allocatedLocked() : 0;
    }

    /** Called by the process-wide heap guard. */
    void killForMemory(String reason) {
        synchronized (this) {
            if (expired || wallExpired || killedByCancel || killedForMemory != null) return;
            killedForMemory = reason;
        }
        fire();
    }

    /** True once the total time has passed, whether or not the ticker noticed yet. */
    boolean wallElapsed() {
        return wallExpired || wallNanos > 0 && System.nanoTime() - start >= wallNanos;
    }

    /** The script's own time ran out. */
    boolean expired() {
        return expired;
    }

    /** The wall ceiling killed the run. */
    boolean wallExpired() {
        return wallExpired;
    }

    boolean killedByCancel() {
        return killedByCancel;
    }

    /** Why the memory watchdog killed the run, or null. */
    String killedForMemory() {
        return killedForMemory;
    }

    @Override
    public void close() {
        task.cancel(false);
        if (allocBudget > 0) MemoryWatchdog.unregister(this);
    }
}
