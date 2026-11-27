package org.kendar.llm.eval.pin;

import org.kendar.llm.js.GraalProbe;

/**
 * The GraalJS the run_js tool had: what the capability probe found on the class path. The profile is the one the
 * default {@code auto} engine setting picks: isolated when an isolate was verified, else constrained.
 *
 * @param engine         {@code isolated}, {@code constrained} or {@code unavailable}
 * @param edition        {@code community}, {@code oracle} or {@code unknown}
 * @param graalDir       the folder of fetched GraalJS jars ({@code -Djllm.graal.dir}), empty when the provided set ran
 */
public record JsInfo(String engine, String edition, String version, String implementation, String graalDir) {

    public static final JsInfo NONE = new JsInfo("unavailable", "unknown", "", "", "");

    public static JsInfo detect() {
        String dir = System.getProperty("jllm.graal.dir", "");
        try {
            GraalProbe.Result r = GraalProbe.probe();
            if (!r.usable()) return new JsInfo("unavailable", "unknown", "", "", dir);
            return new JsInfo(r.isolate() ? "isolated" : "constrained",
                    String.valueOf(r.edition()).toLowerCase(java.util.Locale.ROOT), r.version(), r.implementation(), dir);
        } catch (RuntimeException | LinkageError e) {
            return new JsInfo("unavailable", "unknown", "", "", dir);
        }
    }
}
