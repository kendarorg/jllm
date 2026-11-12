package org.kendar.llm.js;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Watchdog of one run: a wall-clock limit that stands still while paused, and the user's cancel. After a cancel the
 * script gets a grace period (its next tool call throws a catchable error, see the bridge) and is then killed.
 */
final class RunClock implements AutoCloseable {

    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jllm-js-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final long limitNanos;
    private final long graceNanos;
    private final BooleanSupplier cancelled;
    private final BooleanSupplier killNow;
    private final Runnable kill;
    private final ScheduledFuture<?> task;
    private long used;
    private long since = System.nanoTime();
    private long cancelledAt = -1;
    private boolean paused;
    private volatile boolean expired;
    private volatile boolean killedByCancel;

    RunClock(long limitMillis, long graceMillis, BooleanSupplier cancelled, BooleanSupplier killNow, Runnable kill) {
        this.limitNanos = TimeUnit.MILLISECONDS.toNanos(limitMillis);
        this.graceNanos = TimeUnit.MILLISECONDS.toNanos(graceMillis);
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.killNow = killNow == null ? () -> false : killNow;
        this.kill = kill;
        this.task = TICKER.scheduleWithFixedDelay(this::tick, 20, 20, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        boolean fire = false;
        synchronized (this) {
            if (expired || killedByCancel) return;
            long now = System.nanoTime();
            if (cancelled.getAsBoolean()) {
                if (cancelledAt < 0) cancelledAt = now;
                if (killNow.getAsBoolean() || now - cancelledAt >= graceNanos) {
                    killedByCancel = true;
                    fire = true;
                }
            }
            if (!fire && !paused && used + (now - since) >= limitNanos) {
                expired = true;
                fire = true;
            }
        }
        if (fire) {
            task.cancel(false);
            kill.run();
        }
    }

    synchronized void pause() {
        if (paused) return;
        used += System.nanoTime() - since;
        paused = true;
    }

    synchronized void resume() {
        if (!paused) return;
        since = System.nanoTime();
        paused = false;
    }

    boolean expired() {
        return expired;
    }

    boolean killedByCancel() {
        return killedByCancel;
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
