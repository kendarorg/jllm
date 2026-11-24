package org.kendar.llm.js;

import java.nio.file.Path;

/**
 * What the GraalJS on the class path can do. Behaviour follows these capabilities, never the edition (which is only
 * reported). The class itself touches no GraalJS type: presence is tested by class name, and the checks that need the
 * engine run in {@link EngineChecks}, loaded only when everything is present.
 */
public final class GraalProbe {

    static final String POLYGLOT_CLASS = "org.graalvm.polyglot.Engine";
    static final String JS_CLASS = "com.oracle.truffle.js.lang.JavaScriptLanguage";
    static final String REGEX_CLASS = "com.oracle.truffle.regex.RegexLanguage";

    /**
     * @param graal         the polyglot API is present
     * @param js            the JavaScript language is present
     * @param regex         the regex language (TRegex) is present; mandatory
     * @param sandboxLimits a constrained context with heap, CPU and stack limits was built and evaluated
     * @param isolate       an isolated engine was built and evaluated: verified, not assumed from a jar
     * @param isolateNote   why there is no isolate, or empty
     * @param implementation implementation name of the engine, or empty
     * @param version       version of the engine, or empty
     * @param edition       diagnostics only
     */
    public record Result(boolean graal, boolean js, boolean regex, boolean sandboxLimits, boolean isolate,
                         String isolateNote, String implementation, String version, GraalEdition edition) {

        /** The tool can run: polyglot, js and regex are all present. */
        public boolean usable() {
            return graal && js && regex;
        }

        /** Why the tool cannot run, or null when it can. */
        public String unusableReason() {
            if (!graal) {
                return "GraalJS is not on the class path: add org.graalvm.polyglot:polyglot and "
                        + "org.graalvm.polyglot:js (type pom), or the lib-ext folder of the release";
            }
            if (!js) return "The GraalJS JavaScript language jar (org.graalvm.js:js-language) is missing";
            if (!regex) {
                return "The GraalJS regex language jar (org.graalvm.regex:regex) is missing: JavaScript regular "
                        + "expressions need it";
            }
            return null;
        }

        /** One line for the log and the tool status. */
        public String summary() {
            if (!usable()) return "GraalJS unavailable";
            return "GraalJS " + version + " (" + implementation + "), edition " + edition.name().toLowerCase(java.util.Locale.ROOT)
                    + ", isolate " + (isolate ? "yes" : "no") + ", sandbox limits " + (sandboxLimits ? "yes" : "no");
        }
    }

    private static volatile Result cached;

    private GraalProbe() {
    }

    /** The probe of this class's own class loader, run once per process. */
    public static Result probe() {
        Result r = cached;
        if (r == null) {
            synchronized (GraalProbe.class) {
                r = cached;
                if (r == null) cached = r = probe(GraalProbe.class.getClassLoader());
            }
        }
        return r;
    }

    /** Probes what {@code loader} sees; the engine checks run only when polyglot, js and regex are all visible. */
    static Result probe(ClassLoader loader) {
        boolean graal = visible(loader, POLYGLOT_CLASS);
        boolean js = graal && visible(loader, JS_CLASS);
        boolean regex = graal && visible(loader, REGEX_CLASS);
        if (!(graal && js && regex)) {
            return new Result(graal, js, regex, false, false, "", "", "", GraalEdition.UNKNOWN);
        }
        EngineChecks.Outcome o = checkOnPlatformThread();
        GraalEdition edition = GraalEdition.detect(loader, o.implementation(), manifestDir());
        return new Result(true, true, true, o.sandboxLimits(), o.isolate(), o.isolateNote(), o.implementation(),
                o.version(), edition);
    }

    /** An isolate does not work on a virtual thread, and the probe may be called from one. */
    private static EngineChecks.Outcome checkOnPlatformThread() {
        EngineChecks.Outcome[] out = new EngineChecks.Outcome[1];
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = EngineChecks.run();
            } catch (Throwable e) { // boundary: probe thread top level: the failure is handed to the caller
                failure[0] = e;
            }
        }, "jllm-js-probe");
        t.setDaemon(true);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new EngineChecks.Outcome("", "", false, false, "interrupted while probing");
        }
        if (out[0] == null) {
            JsLog.LOG.warn("The GraalJS probe failed: {}", String.valueOf(failure[0]));
            return new EngineChecks.Outcome("", "", false, false, String.valueOf(failure[0]));
        }
        return out[0];
    }

    private static boolean visible(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The folder the release leaves its GraalJS manifest in: {@code -Djllm.graal.dir}, else {@code ./lib-ext}. */
    private static Path manifestDir() {
        String dir = System.getProperty("jllm.graal.dir");
        return dir != null && !dir.isBlank() ? Path.of(dir) : Path.of("lib-ext");
    }
}
