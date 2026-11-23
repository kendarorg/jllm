package org.kendar.llm.js;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.ThreadMXBean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds the memory of scripts that run in the host's heap (constrained profile without engine heap limits). Two parts:
 * the per-run allocation budget, read from the thread allocation counter on each clock tick, and a process-wide guard
 * on the old generation that, when a collection leaves it above a ratio, kills the run that allocated the most.
 */
final class MemoryWatchdog {

    private static final Set<RunClock> RUNS = ConcurrentHashMap.newKeySet();
    private static boolean guardInstalled;

    private MemoryWatchdog() {
    }

    /** True when the JVM can tell how much a thread allocated. */
    static boolean supported() {
        return counter() != null;
    }

    private static com.sun.management.ThreadMXBean counter() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (mx instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
            if (!sun.isThreadAllocatedMemoryEnabled()) sun.setThreadAllocatedMemoryEnabled(true);
            return sun;
        }
        return null;
    }

    /** Bytes allocated so far by the thread, or -1 when unknown. */
    static long allocated(long threadId) {
        var c = counter();
        return c == null ? -1 : c.getThreadAllocatedBytes(threadId);
    }

    static void register(RunClock run) {
        RUNS.add(run);
    }

    static void unregister(RunClock run) {
        RUNS.remove(run);
    }

    /**
     * Kills the registered run that allocated the most.
     *
     * @return false when no run is registered
     */
    static boolean killBiggest(String reason) {
        RunClock biggest = null;
        long most = -1;
        for (RunClock r : RUNS) {
            long a = r.allocatedBytes();
            if (a > most) {
                most = a;
                biggest = r;
            }
        }
        if (biggest == null) return false;
        biggest.killForMemory(reason);
        return true;
    }

    /**
     * Installs, once per process, the listener on the old generation pools: a pool whose usage after a collection is
     * above {@code ratio} of its maximum triggers {@link #killBiggest}.
     */
    static synchronized void installGuard(double ratio) {
        if (guardInstalled) return;
        guardInstalled = true;
        try {
            boolean any = false;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported()) continue;
                String n = pool.getName().toLowerCase(java.util.Locale.ROOT);
                long max = pool.getUsage().getMax();
                if (max <= 0 || !(n.contains("old") || n.contains("tenured"))) continue;
                pool.setCollectionUsageThreshold((long) (max * ratio));
                any = true;
            }
            if (!any) return;
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            ((NotificationEmitter) mem).addNotificationListener((notification, handback) -> {
                if (!MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType())) return;
                MemoryNotificationInfo info = MemoryNotificationInfo.from((CompositeData) notification.getUserData());
                if (killBiggest("The JVM heap is nearly full (" + info.getPoolName() + " above "
                        + Math.round(ratio * 100) + "% after a collection)")) {
                    JsLog.LOG.warn("Killed the script that allocated the most: {} above {}% after a collection",
                            info.getPoolName(), Math.round(ratio * 100));
                }
            }, null, null);
        } catch (RuntimeException e) { // boundary: the guard is optional
            JsLog.LOG.warn("The JVM heap guard for scripts could not be installed: {}", e.toString());
        }
    }
}
