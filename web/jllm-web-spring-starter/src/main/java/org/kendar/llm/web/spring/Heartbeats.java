package org.kendar.llm.web.spring;

import org.kendar.llm.web.commons.ChatApi;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Drives the heartbeat of the event streams. */
final class Heartbeats {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private Heartbeats() {
    }

    /** Calls {@code beat} every {@link ChatApi#HEARTBEAT_MS} until the sink closes. */
    static void drive(Runnable beat, SseSink sink) {
        ScheduledFuture<?> f = SCHEDULER.scheduleAtFixedRate(beat, ChatApi.HEARTBEAT_MS, ChatApi.HEARTBEAT_MS,
                TimeUnit.MILLISECONDS);
        sink.onClose(() -> f.cancel(false));
    }
}
