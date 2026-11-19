package org.kendar.llm.eval;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Progress lines of the run on stderr: {@code [jllm-eval HH:mm:ss.SSS thread] message}. The thread name tells
 * parallel test classes apart. Secrets are masked. Set {@code -Deval.log=false} to silence it.
 */
public final class EvalLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("eval.log"));
    private static final AtomicInteger STARTED = new AtomicInteger();
    private static final AtomicInteger FINISHED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    private EvalLog() {
    }

    public static void info(String message) {
        if (!ENABLED) return;
        System.err.println(Secrets.mask("[jllm-eval " + LocalTime.now().format(TIME) + " "
                + Thread.currentThread().getName() + "] " + message));
    }

    /** A case begins; returns its sequence number. */
    public static int caseStarted(String name, int trials) {
        int n = STARTED.incrementAndGet();
        info("case #" + n + " start " + name + " (" + trials + " trial(s))");
        return n;
    }

    /** A case ends. Progress counts cases finished so far against cases started so far. */
    public static void caseFinished(String name, boolean passed, long millis, String detail) {
        int done = FINISHED.incrementAndGet();
        if (!passed) FAILED.incrementAndGet();
        info("case " + (passed ? "PASS" : "FAIL") + " " + name + " in " + seconds(millis) + "s"
                + (detail == null || detail.isBlank() ? "" : " - " + detail)
                + " [finished " + done + "/" + STARTED.get() + " started, " + FAILED.get() + " failed]");
    }

    public static String seconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
