package org.kendar.llm.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.SandboxPolicy;

import java.io.OutputStream;
import java.util.Locale;

/**
 * Isolated (or untrusted) sandbox policy: the engine runs in a native isolate with its own heap, so memory, CPU time,
 * stack frames, AST depth and the console streams are bounded by the engine. The statement limit is passed too, but the
 * isolate does not count statements: the CPU limit stops a runaway loop. The regex language lives inside the isolate.
 */
final class IsolatedProfile implements JsProfile {

    private static final int OUTPUT_LIMIT_BYTES = 1024 * 1024;

    private final JsSettings settings;
    private final SandboxPolicy policy;
    private final boolean debug;
    private final OutputStream debugSink;
    private final ResourceLimits limits;
    private volatile Engine engine;
    private volatile long generation;

    IsolatedProfile(JsSettings settings) {
        this(settings, JsLog.LOG.isDebugEnabled(), JsLog.engineStream());
    }

    IsolatedProfile(JsSettings settings, boolean debug, OutputStream debugSink) {
        this.settings = settings;
        this.policy = settings.policy() == JsSettings.Policy.UNTRUSTED ? SandboxPolicy.UNTRUSTED : SandboxPolicy.ISOLATED;
        this.debug = debug;
        this.debugSink = debugSink;
        this.limits = ResourceLimits.newBuilder().statementLimit(settings.statementLimit(), null).build();
        this.engine = buildEngine();
    }

    private Engine buildEngine() {
        return Engine.newBuilder("js")
                .sandbox(policy)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .logHandler(debug ? debugSink : OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", Boolean.toString(debug))
                .option("engine.MaxIsolateMemory", Sizes.megabytes(settings.maxIsolateMemory()))
                .build();
    }

    @Override
    public String name() {
        return "isolated";
    }

    @Override
    public String describe() {
        return "isolated (" + policy.name().toLowerCase(Locale.ROOT) + " policy, isolate heap, CPU and stack limits)";
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public Context newContext(OutputStream out, long cpuMillis) {
        Context.Builder b = Context.newBuilder("js")
                .engine(engine)
                .sandbox(policy)
                .resourceLimits(limits)
                .out(out)
                .err(out)
                .option("sandbox.MaxCPUTime", cpuMillis + "ms")
                .option("sandbox.MaxHeapMemory", Sizes.megabytes(settings.maxHeapMemory()))
                .option("sandbox.MaxStackFrames", Integer.toString(settings.maxStackFrames()))
                .option("sandbox.MaxASTDepth", Integer.toString(settings.maxAstDepth()))
                .option("sandbox.MaxOutputStreamSize", OUTPUT_LIMIT_BYTES + "B")
                .option("sandbox.MaxErrorStreamSize", OUTPUT_LIMIT_BYTES + "B");
        if (policy == SandboxPolicy.UNTRUSTED) b.option("sandbox.MaxThreads", "1");
        JsContexts.removeGlobals(b);
        return b.build();
    }

    @Override
    public boolean usesWatchdog() {
        return false;
    }

    @Override
    public synchronized void recover(long gen) {
        if (gen != generation) return; // another run already rebuilt it
        JsLog.LOG.warn("Rebuilding the JavaScript isolate after a failure");
        Engine old = engine;
        generation++;
        engine = buildEngine();
        try {
            old.close(true);
        } catch (RuntimeException e) { // boundary: closing a broken isolate is best effort
            JsLog.LOG.debug("The broken isolate did not close cleanly: {}", e.toString());
        }
    }

    @Override
    public void close() {
        engine.close(true);
    }
}
