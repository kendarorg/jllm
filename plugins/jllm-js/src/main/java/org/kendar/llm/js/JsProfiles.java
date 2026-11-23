package org.kendar.llm.js;

/** Picks the profile for the {@code engine} setting from what the probe found. */
final class JsProfiles {

    /** @param profile the profile, or null when the tool cannot run; @param reason why, when null */
    record Selection(JsProfile profile, String reason) {
    }

    private JsProfiles() {
    }

    /**
     * {@code auto}: isolated if an isolate was verified, else constrained. {@code constrained}: constrained.
     * {@code isolated}: isolated, or no profile with a reason when there is no isolate; never a silent downgrade.
     */
    static Selection select(JsSettings settings, GraalProbe.Result caps) {
        if (!caps.usable()) return new Selection(null, caps.unusableReason());
        JsSettings.EngineChoice choice = settings.engine();
        boolean isolated = choice == JsSettings.EngineChoice.ISOLATED
                || choice == JsSettings.EngineChoice.AUTO && caps.isolate();
        if (isolated && !caps.isolate()) {
            return new Selection(null, "engine=isolated was requested but this runtime has no isolate"
                    + (caps.isolateNote().isEmpty() ? "" : " (" + caps.isolateNote() + ")")
                    + ": add the js-isolate artifact of the platform, or set engine to auto or constrained");
        }
        try {
            return new Selection(isolated ? new IsolatedProfile(settings)
                    : new ConstrainedProfile(settings, caps.sandboxLimits()), null);
        } catch (RuntimeException | LinkageError e) { // boundary: an engine that cannot be created is reported
            return new Selection(null, "The " + (isolated ? "isolated" : "constrained")
                    + " script engine could not be created: " + e);
        }
    }
}
