package org.kendar.llm.js;

/** Run in a JVM of its own by {@link OtherEditionTest}: probes the GraalJS it was started with and runs a script. */
public final class IsolatedSmoke {

    private IsolatedSmoke() {
    }

    public static void main(String[] args) {
        GraalProbe.Result r = GraalProbe.probe();
        System.out.println("edition=" + r.edition() + " isolate=" + r.isolate() + " impl=" + r.implementation());
        if (!r.isolate()) {
            System.out.println("note=" + r.isolateNote());
            System.out.println("selection=" + (JsProfiles.select(JsSettings.defaults(), r).profile() == null ? "disabled" : "enabled"));
            return;
        }
        try (JsSandbox box = JsSandbox.open(JsSettings.defaults())) {
            JsResult out = box.run("return 6 * 7;");
            System.out.println("ran=" + out.ok() + " profile=" + box.profileName());
        }
    }
}
