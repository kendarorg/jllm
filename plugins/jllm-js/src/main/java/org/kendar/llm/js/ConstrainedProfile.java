package org.kendar.llm.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.SandboxPolicy;

import java.io.OutputStream;

/**
 * Constrained sandbox policy, the engine and heap shared with the host. Bounded by the statement limit and, where the
 * runtime supports the sandbox limits (heap, CPU time, stack frames), by those; where it does not, by the memory
 * watchdog.
 */
final class ConstrainedProfile implements JsProfile {

    private final JsSettings settings;
    private final boolean sandboxLimits;
    private final boolean debug;
    private final OutputStream debugSink;
    private final ResourceLimits limits;
    private final Engine engine;

    ConstrainedProfile(JsSettings settings, boolean sandboxLimits) {
        this(settings, sandboxLimits, JsLog.LOG.isDebugEnabled(), JsLog.engineStream());
    }

    /** @param debug engine diagnostics (the interpreter-only warning) are written to {@code debugSink}; else dropped */
    ConstrainedProfile(JsSettings settings, boolean sandboxLimits, boolean debug, OutputStream debugSink) {
        this.settings = settings;
        this.sandboxLimits = sandboxLimits;
        this.debug = debug;
        this.debugSink = debugSink;
        this.limits = ResourceLimits.newBuilder().statementLimit(settings.statementLimit(), null).build();
        this.engine = buildEngine();
        if (!sandboxLimits) MemoryWatchdog.installGuard(settings.heapKillRatio());
    }

    private Engine buildEngine() {
        return Engine.newBuilder("js", "regex")
                .sandbox(SandboxPolicy.CONSTRAINED)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .logHandler(debug ? debugSink : OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", Boolean.toString(debug))
                .build();
    }

    @Override
    public String name() {
        return "constrained";
    }

    @Override
    public String describe() {
        return "constrained (shared heap, statement limit, "
                + (sandboxLimits ? "sandbox heap, CPU and stack limits" : "memory watchdog") + ")";
    }

    @Override
    public long generation() {
        return 0;
    }

    @Override
    public Context newContext(OutputStream out, long cpuMillis) {
        Context.Builder b = Context.newBuilder("js", "regex")
                .engine(engine)
                .sandbox(SandboxPolicy.CONSTRAINED)
                .resourceLimits(limits)
                .out(out)
                .err(out);
        JsContexts.removeGlobals(b);
        if (sandboxLimits) {
            b.option("sandbox.MaxHeapMemory", Sizes.megabytes(settings.maxHeapMemory()))
                    .option("sandbox.MaxCPUTime", cpuMillis + "ms")
                    .option("sandbox.MaxStackFrames", Integer.toString(settings.maxStackFrames()))
                    .option("sandbox.MaxASTDepth", Integer.toString(settings.maxAstDepth()));
        }
        return b.build();
    }

    @Override
    public boolean usesWatchdog() {
        return !sandboxLimits;
    }

    @Override
    public void recover(long generation) {
        // the engine lives in the host's heap and a failure leaves it usable: nothing to rebuild
    }

    @Override
    public void close() {
        engine.close();
    }
}
