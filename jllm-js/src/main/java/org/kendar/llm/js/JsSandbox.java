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
import java.util.function.BooleanSupplier;

/** Runs one script in a fresh {@link Context}, closed at the end of the run, whatever happens. */
public final class JsSandbox implements AutoCloseable {

    static final int CONSOLE_MAX_BYTES = 50 * 1024;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_STACK_LINES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The one callback JS has: runs a tool by its real name with JSON arguments, returns its raw output. */
    @FunctionalInterface
    public interface Bridge {
        /** @throws RuntimeException with the tool's message; it reaches the script as a thrown {@code Error} */
        String invoke(String toolName, String argsJson);
    }

    private final JsSettings settings;
    private final ContextFactory factory;

    public JsSandbox(JsSettings settings) {
        this.settings = settings;
        this.factory = new ContextFactory(settings);
    }

    public JsSettings settings() {
        return settings;
    }

    public JsResult run(String code) {
        return run(code, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsResult run(String code, int timeoutSeconds) {
        return run(code, timeoutSeconds, null, null, null);
    }

    /**
     * Runs {@code code} as a function body; the JSON of its {@code return} value is the result. A timeout or the
     * statement limit end the run with {@code ok = false}; they do not throw.
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
        int secs = Math.max(1, Math.min(timeoutSeconds, settings.maxTimeoutSeconds()));
        CappedOutput console = new CappedOutput(CONSOLE_MAX_BYTES);
        Context ctx = factory.create(console);
        RunClock clock = new RunClock(secs * 1000L, settings.gracefulSeconds() * 1000L, cancelled,
                killNow, () -> ctx.close(true));
        try {
            if (library != null) {
                ctx.getBindings("js").putMember("__invoke", (ProxyExecutable) args -> call(bridge, clock, args));
                ctx.eval(library);
            }
            Value v = ctx.eval("js", "JSON.stringify((function(){\n" + code + "\n})())");
            String json = v.isString() ? v.asString() : null;
            return new JsResult(true, json, console.text(), null, console.truncated(), null);
        } catch (PolyglotException e) {
            String error;
            if (clock.killedByCancel()) error = "Cancelled by the user";
            else if (clock.expired()) error = "Timeout: the script ran longer than " + secs + " seconds";
            else if (e.isResourceExhausted()) error = "Limit reached: the script executed more than "
                    + settings.statementLimit() + " statements";
            else error = describe(e);
            return new JsResult(false, null, console.text(), error, console.truncated(), null);
        } finally {
            clock.close();
            ctx.close(true);
        }
    }

    /** The bridge as JS sees it: a JSON envelope, {value} or {error}; errors are rethrown as Error by the library. */
    private static Object call(Bridge bridge, RunClock clock, Value[] args) {
        ObjectNode env = MAPPER.createObjectNode();
        // the tool may wait for the user or for the network: that time is not the script's
        clock.pause();
        try {
            if (bridge == null) throw new IllegalStateException("No tools are available");
            env.put("value", bridge.invoke(args[0].asString(), args[1].asString()));
        } catch (Throwable t) {
            String m = t.getMessage();
            env.put("error", m == null || m.isBlank() ? t.getClass().getSimpleName() : m);
        } finally {
            clock.resume();
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
            msg = String.join("\n", java.util.Arrays.copyOf(lines, MAX_STACK_LINES));
        }
        return msg;
    }

    @Override
    public void close() {
        factory.close();
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
