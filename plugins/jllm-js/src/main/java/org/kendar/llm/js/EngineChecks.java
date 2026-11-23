package org.kendar.llm.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

import java.io.ByteArrayOutputStream;
import java.util.function.Supplier;

/** The probe checks that need the engine: each builds the real profile and evaluates a trivial expression. */
final class EngineChecks {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineChecks.class);

    /** @param isolateNote why the isolate could not be built, or empty */
    record Outcome(String implementation, String version, boolean sandboxLimits, boolean isolate, String isolateNote) {
    }

    private EngineChecks() {
    }

    static Outcome run() {
        String impl = "";
        String version = "";
        try (Engine e = Engine.newBuilder("js", "regex").out(new ByteArrayOutputStream())
                .err(new ByteArrayOutputStream()).logHandler(new ByteArrayOutputStream())
                .option("engine.WarnInterpreterOnly", "false").build()) {
            impl = e.getImplementationName();
            version = e.getVersion();
        } catch (RuntimeException | LinkageError e) { // boundary: the engine cannot be built: the checks below fail the same way
            // the engine itself cannot be built: the profile checks below fail the same way
            log.debug("Engine probe failed: {}", e.toString());
        }
        boolean limits = works(() -> new ConstrainedProfile(JsSettings.defaults(), true, false, new ByteArrayOutputStream()));
        String note = "";
        boolean isolate;
        try {
            evaluate(new IsolatedProfile(JsSettings.defaults(), false, new ByteArrayOutputStream()));
            isolate = true;
        } catch (RuntimeException | LinkageError e) { // boundary: an unusable isolate is reported and the constrained profile is used
            isolate = false;
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
            note = m;
        }
        return new Outcome(impl, version, limits, isolate, note);
    }

    private static boolean works(Supplier<JsProfile> f) {
        try {
            evaluate(f.get());
            return true;
        } catch (RuntimeException | LinkageError e) { // boundary: a probe that cannot run means not available
            return false;
        }
    }

    private static void evaluate(JsProfile p) {
        try (p; Context c = p.newContext(new ByteArrayOutputStream(), 10_000)) {
            if (c.eval("js", "1 + 1").asInt() != 2) throw new IllegalStateException("unexpected result");
            if (!c.eval("js", "/a+b/.test('aab')").asBoolean()) throw new IllegalStateException("regex failed");
        }
    }
}
