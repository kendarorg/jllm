package org.kendar.llm.js;

import org.graalvm.polyglot.Context;

/** Context settings shared by the profiles. */
final class JsContexts {

    private JsContexts() {
    }

    /**
     * The sandbox policies deny host access, files, processes, threads and the environment. They leave three globals of
     * the language itself in place, which the forbidden-globals test caught: {@code print}, {@code load} with
     * {@code loadWithNewGlobal} (reads and evaluates files) and {@code Graal}. {@code Java}, {@code Polyglot},
     * {@code require} and the Java package globals are not defined under either policy, so no option is set for them.
     */
    static void removeGlobals(Context.Builder b) {
        b.option("js.print", "false")
                .option("js.load", "false")
                .option("js.graal-builtin", "false");
    }
}
