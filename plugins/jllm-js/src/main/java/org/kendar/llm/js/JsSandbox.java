package org.kendar.llm.js;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Runs one script in a fresh context of the selected {@link JsProfile}, closed at the end of the run whatever happens.
 * Limit errors are ordinary failures ({@code ok = false}), never exceptions.
 */
public final class JsSandbox implements AutoCloseable {

    static final int CONSOLE_MAX_BYTES = 50 * 1024;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** How long a script waits for a free slot when {@code maxConcurrentRuns} are running. */
    static final long QUEUE_WAIT_MILLIS = 2_000;
    /** CPU time the engine may count beyond the script's own time, so that the script clock reports a timeout first. */
    private static final int CPU_MARGIN_SECONDS = 5;
    private static final int MAX_STACK_LINES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger RUNNER_COUNT = new AtomicInteger();
    private static final ExecutorService RUNNERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jllm-js-run-" + RUNNER_COUNT.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** The one callback JS has: runs a tool by its real name with JSON arguments, returns its raw output. */
    @FunctionalInterface
    public interface Bridge {
        /** @throws RuntimeException with the tool's message; it reaches the script as a thrown {@code Error} */
        String invoke(String toolName, String argsJson);
    }

    private final JsSettings settings;
    private final JsProfile profile;
    private final Semaphore slots;

    JsSandbox(JsSettings settings, JsProfile profile) {
        this.settings = settings;
        this.profile = profile;
        this.slots = settings.maxConcurrentRuns() > 0 ? new Semaphore(settings.maxConcurrentRuns(), true) : null;
    }

    /**
     * A sandbox over the profile the settings select on this runtime.
     *
     * @throws IllegalStateException when no profile is available, with the reason
     */
    public static JsSandbox open(JsSettings settings) {
        JsProfiles.Selection s = JsProfiles.select(settings, GraalProbe.probe());
        if (s.profile() == null) throw new IllegalStateException(s.reason());
        return new JsSandbox(settings, s.profile());
    }

    public JsSettings settings() {
        return settings;
    }

    /** {@code constrained} or {@code isolated}. */
    public String profileName() {
        return profile.name();
    }

    public String describeProfile() {
        return profile.describe();
    }

    public JsResult run(String code) {
        return run(code, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsResult run(String code, int timeoutSeconds) {
        return run(code, timeoutSeconds, null, null, null);
    }

    /**
     * Runs {@code code} as a function body; the JSON of its {@code return} value is the result. A limit, a timeout or a
     * cancel end the run with {@code ok = false}; they do not throw.
     *
     * @param library the tools module evaluated before the script, or null for none
     * @param bridge  the callback behind {@code tools}; null for none
     * @param cancelled the user's stop flag; after it is set the script is killed once the grace period is over
     */
    public JsResult run(String code, int timeoutSeconds, Source library, Bridge bridge, BooleanSupplier cancelled) {
        return run(code, timeoutSeconds, library, bridge, cancelled, null);
    }

    /** @param killNow true when the user asked to stop the script at once, without the grace period */
    public JsResult run(String code, int timeoutSeconds, Source library, Bridge bridge, BooleanSupplier cancelled,
                        BooleanSupplier killNow) {
        if (slots != null) {
            try {
                if (!slots.tryAcquire(QUEUE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    return failure("Too many scripts are running (at most " + settings.maxConcurrentRuns()
                            + " at once): try again shortly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure("Interrupted while waiting for a free script slot");
            }
        }
        try {
            return onPlatformThread(code, timeoutSeconds, library, bridge, cancelled, killNow);
        } finally {
            if (slots != null) slots.release();
        }
    }

    /**
     * Scripts run on a platform thread of their own: an isolate does not work on a virtual thread (the agent loop's
     * threads), and the allocation counter of the memory watchdog is per thread. The caller waits; if it is
     * interrupted the script is stopped.
     */
    private JsResult onPlatformThread(String code, int timeoutSeconds, Source library, Bridge bridge,
                                      BooleanSupplier cancelled, BooleanSupplier killNow) {
        AtomicBoolean abandoned = new AtomicBoolean();
        BooleanSupplier stop = () -> abandoned.get() || cancelled != null && cancelled.getAsBoolean();
        BooleanSupplier stopNow = () -> abandoned.get() || killNow != null && killNow.getAsBoolean();
        Future<JsResult> f = RUNNERS.submit(() -> runOne(code, timeoutSeconds, library, bridge, stop, stopNow));
        try {
            return f.get();
        } catch (InterruptedException e) {
            abandoned.set(true);
            Thread.currentThread().interrupt();
            return failure("Cancelled: the calling thread was interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            JsLog.LOG.warn("Script run failed outside the engine: {}", c.toString());
            return failure("Internal error: " + message(c));
        }
    }

    private static JsResult failure(String error) {
        return new JsResult(false, null, "", error, false, null);
    }

    /** What the bridge needs of the run in progress. */
    private static final class Run {
        final RunClock clock;
        final int maxInnerCalls;
        int innerCalls;

        Run(RunClock clock, int maxInnerCalls) {
            this.clock = clock;
            this.maxInnerCalls = maxInnerCalls;
        }
    }

    private JsResult runOne(String code, int timeoutSeconds, Source library, Bridge bridge, BooleanSupplier cancelled,
                            BooleanSupplier killNow) {
        int secs = Math.max(1, Math.min(timeoutSeconds, settings.maxTimeoutSeconds()));
        CappedOutput console = new CappedOutput(CONSOLE_MAX_BYTES);
        long generation = profile.generation();
        Context ctx;
        try {
            ctx = profile.newContext(console, (secs + CPU_MARGIN_SECONDS) * 1000L);
        } catch (RuntimeException e) { // boundary: the context cannot be created: recover the profile, report
            profile.recover(generation);
            JsLog.LOG.warn("Could not create a script context: {}", e.toString());
            return failure("Internal error: the script engine failed to start a run: " + message(e));
        }
        RunClock clock = new RunClock(secs * 1000L, settings.gracefulSeconds() * 1000L,
                settings.maxWallSeconds() * 1000L, profile.usesWatchdog() ? settings.maxRunAllocatedBytes() : 0,
                cancelled, killNow, () -> closeQuietly(ctx, true));
        Run run = new Run(clock, settings.maxInnerCalls());
        try {
            if (library != null) {
                ctx.getBindings("js").putMember("__invoke", (ProxyExecutable) args -> call(bridge, run, args));
                ctx.eval(library);
            }
            Value v = ctx.eval("js", "JSON.stringify((function(){\n" + code + "\n})())");
            String json = v.isString() ? v.asString() : null;
            return new JsResult(true, json, console.text(), null, console.truncated(), null);
        } catch (PolyglotException e) {
            boolean broken = e.isInternalError() || isolateExhausted(e);
            if (broken && !clock.killedByCancel() && !clock.expired() && !clock.wallExpired()) {
                profile.recover(generation);
            }
            return new JsResult(false, null, console.text(), errorOf(e, clock, secs), console.truncated(), null);
        } catch (RuntimeException e) { // boundary: a context closed under the script or a dead isolate
            // a context closed under the script by the watchdog, or the failure of a dead isolate
            String known = knownEnd(clock, secs);
            if (known == null) {
                profile.recover(generation);
                JsLog.LOG.warn("Script run failed: {}", e.toString());
            }
            return new JsResult(false, null, console.text(),
                    known != null ? known : "Internal error: " + message(e), console.truncated(), null);
        } finally {
            clock.close();
            closeQuietly(ctx, true);
        }
    }

    private static void closeQuietly(Context ctx, boolean cancel) {
        try {
            ctx.close(cancel);
        } catch (RuntimeException e) { // boundary: closing is best effort
            JsLog.LOG.debug("Closing a script context failed: {}", e.toString());
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m.split("\n")[0];
    }

    /** The end the clock decided, in the fixed order: user cancel, timeouts, memory watchdog; null when none. */
    private String knownEnd(RunClock clock, int secs) {
        if (clock.killedByCancel()) return "Cancelled by the user";
        if (clock.expired()) return "Timeout: the script ran longer than " + secs + " seconds";
        if (clock.wallExpired()) return wallMessage();
        if (clock.killedForMemory() != null) return "Memory limit: " + clock.killedForMemory();
        return null;
    }

    private String wallMessage() {
        return "Timeout: the script ran longer than " + settings.maxWallSeconds()
                + " seconds in total, tool calls included";
    }

    /** Failure state decides, in a fixed order; then the kind of limit; then the engine's own message. */
    private String errorOf(PolyglotException e, RunClock clock, int secs) {
        String known = knownEnd(clock, secs);
        if (known != null) return known;
        if (e.isResourceExhausted()) return limitMessage(e);
        if (e.isCancelled()) return "Cancelled";
        return describe(e);
    }

    private static boolean isolateExhausted(PolyglotException e) {
        return e.isResourceExhausted() && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("garbage-collected heap");
    }

    /** One sentence per kind of engine limit. */
    private String limitMessage(PolyglotException e) {
        String m = String.valueOf(e.getMessage());
        String l = m.toLowerCase(Locale.ROOT);
        if (l.contains("statement count")) {
            return "Limit reached: the script executed more than " + settings.statementLimit() + " statements";
        }
        if (l.contains("cpu time")) return "Limit reached: the script used too much CPU time";
        if (l.contains("garbage-collected heap")) {
            return "Limit reached: the script engine ran out of memory (isolate limit "
                    + settings.maxIsolateMemory() / Sizes.MIB + " MB)";
        }
        if (l.contains("heap memory")) {
            return "Limit reached: the script used more than " + settings.maxHeapMemory() / Sizes.MIB + " MB of memory";
        }
        if (l.contains("frame")) {
            return "Limit reached: the script call stack is deeper than " + settings.maxStackFrames() + " frames";
        }
        if (l.contains("ast")) {
            return "Limit reached: the script is nested deeper than " + settings.maxAstDepth() + " levels";
        }
        if (l.contains("stream")) return "Limit reached: the script wrote too much to the console";
        return "Limit reached: " + m.split("\n")[0];
    }

    /** The bridge as JS sees it: a JSON envelope, {value} or {error}; errors are rethrown as Error by the library. */
    private Object call(Bridge bridge, Run run, Value[] args) {
        ObjectNode env = MAPPER.createObjectNode();
        if (run.clock.wallElapsed()) {
            env.put("error", wallMessage() + ": return what you have");
            return env.toString();
        }
        if (++run.innerCalls > run.maxInnerCalls) {
            env.put("error", "Too many tool calls in one script (limit " + run.maxInnerCalls
                    + "): return what you have");
            return env.toString();
        }
        // the tool may wait for the user or for the network: that time is not the script's
        run.clock.pause();
        try {
            if (bridge == null) throw new IllegalStateException("No tools are available");
            env.put("value", bridge.invoke(args[0].asString(), args[1].asString()));
        } catch (Throwable t) { // boundary: script boundary: any failure is the error of the script
            String m = t.getMessage();
            env.put("error", m == null || m.isBlank() ? t.getClass().getSimpleName() : m);
        } finally {
            run.clock.resume();
        }
        return env.toString();
    }

    private static String describe(PolyglotException e) {
        String msg = e.getMessage();
        int line = e.getSourceLocation() != null ? e.getSourceLocation().getStartLine() : -1;
        for (var f : e.getPolyglotStackTrace()) {
            if (line > 1) break;
            if (f.isGuestFrame() && f.getSourceLocation() != null) line = f.getSourceLocation().getStartLine();
        }
        if (line > 1) msg += " at line " + (line - 1); // the wrapper adds one line before the script
        if (e.isInternalError()) msg = "Internal error: " + msg;
        String[] lines = msg.split("\n");
        if (lines.length > MAX_STACK_LINES) {
            msg = String.join("\n", Arrays.copyOf(lines, MAX_STACK_LINES));
        }
        return msg;
    }

    @Override
    public void close() {
        profile.close();
    }

    /** Console buffer that stops growing at the cap. */
    static final class CappedOutput extends ByteArrayOutputStream {
        private final int max;
        private boolean truncated;

        CappedOutput(int max) {
            this.max = max;
        }

        @Override
        public synchronized void write(int b) {
            if (count >= max) truncated = true;
            else super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = max - count;
            if (len > room) truncated = true;
            if (room > 0) super.write(b, off, Math.min(len, room));
        }

        synchronized String text() {
            return new String(buf, 0, count, StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
