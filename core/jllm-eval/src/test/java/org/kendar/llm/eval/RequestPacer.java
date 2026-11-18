package org.kendar.llm.eval;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spaces request starts so one host stays under its requests-per-minute limit. Endpoints on the same host share one
 * pacer, so a target and a judge on the same provider count against the same budget.
 */
final class RequestPacer {

    private static final Map<String, RequestPacer> BY_HOST = new ConcurrentHashMap<>();

    private final long intervalMs;
    private long nextSlotMs;

    RequestPacer(int maxRpm) {
        this.intervalMs = 60_000L / maxRpm;
    }

    static RequestPacer forHost(String baseUrl, int maxRpm) {
        return BY_HOST.computeIfAbsent(normalize(baseUrl), k -> new RequestPacer(maxRpm));
    }

    /** Reserves the next start slot and returns how long the caller must wait before starting. */
    synchronized long reserveMs() {
        long now = System.currentTimeMillis();
        long slot = Math.max(now, nextSlotMs);
        nextSlotMs = slot + intervalMs;
        return slot - now;
    }

    long intervalMs() {
        return intervalMs;
    }

    private static String normalize(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
