package org.kendar.llm.js;

/** Picks the profile for the {@code engine} setting from what the probe found. */
final class JsProfiles {

    /** @param profile the profile, or null when the tool cannot run; @param reason why, when null */
    record Selection(JsProfile profile, String reason) {
    }

    private JsProfiles() {
    }

    /**
     * {@code isolated} (the default): the isolate, or no profile with a reason when the runtime has none: fail closed,
     * never a silent downgrade. {@code constrained}: only when asked for by name; a WARN says it is no boundary.
     */
    static Selection select(JsSettings settings, GraalProbe.Result caps) {
        if (!caps.usable()) return new Selection(null, caps.unusableReason());
        boolean isolated = settings.engine() == JsSettings.EngineChoice.ISOLATED;
        if (isolated && !caps.isolate()) return new Selection(null, noIsolate(caps));
        if (!isolated) {
            JsLog.LOG.warn("run_js uses the constrained engine (engine=constrained): scripts share the JVM heap and it is "
                    + "not a security boundary. Use it for trusted single-user setups only.");
        }
        try {
            return new Selection(isolated ? new IsolatedProfile(settings)
                    : new ConstrainedProfile(settings, caps.sandboxLimits()), null);
        } catch (RuntimeException | LinkageError e) { // boundary: an engine that cannot be created is reported
            return new Selection(null, "The " + (isolated ? "isolated" : "constrained")
                    + " script engine could not be created: " + e);
        }
    }

    /** Why run_js is off without an isolate, naming the artifact of the edition that is on the class path. */
    static String noIsolate(GraalProbe.Result caps) {
        String artifact = switch (caps.edition()) {
            case COMMUNITY -> "org.graalvm.polyglot:js-isolate-community (type pom; GraalVM Community 25.1 or newer)";
            case ORACLE -> "org.graalvm.polyglot:js-isolate (type pom; Oracle GraalVM)";
            case UNKNOWN -> "the isolate artifact of your edition: org.graalvm.polyglot:js-isolate-community (Community) or "
                    + "org.graalvm.polyglot:js-isolate (Oracle), both type pom";
        };
        return "run_js is disabled: it needs the GraalJS isolate (engine=isolated, the default) and this runtime has none"
                + (caps.isolateNote().isEmpty() ? "" : " (" + caps.isolateNote() + ")")
                + ". Add " + artifact + " with the jar of this platform (the release's lib-ext, or scripts/setup), "
                + "or set the js plugin setting engine to constrained for a trusted single-user setup (no isolation)";
    }
}
