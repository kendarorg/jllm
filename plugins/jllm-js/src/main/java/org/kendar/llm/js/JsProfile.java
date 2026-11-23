package org.kendar.llm.js;

import org.graalvm.polyglot.Context;

import java.io.OutputStream;

/**
 * How scripts are isolated: one shared engine (code cache) and a locked-down fresh context per run. The sandbox policy
 * of the engine does the locking down, so no access option is set by hand; the only language options kept are the ones
 * that remove globals a policy leaves in place (see {@link JsContexts#removeGlobals}).
 */
interface JsProfile extends AutoCloseable {

    /** {@code constrained} or {@code isolated}. */
    String name();

    /** One-line description for the log and the tool status. */
    String describe();

    /** Counts rebuilds of the engine: a run that breaks it asks for {@link #recover} with the generation it started on. */
    long generation();

    /**
     * @param out       receives console output (stdout and stderr)
     * @param cpuMillis CPU time the context may use; a profile that cannot count it ignores it
     */
    Context newContext(OutputStream out, long cpuMillis);

    /** True when the heap is bounded by the memory watchdog, not by the engine. */
    boolean usesWatchdog();

    /** Rebuilds the engine after a failure that may have left it unusable; a no-op when {@code generation} is old. */
    void recover(long generation);

    @Override
    void close();
}
