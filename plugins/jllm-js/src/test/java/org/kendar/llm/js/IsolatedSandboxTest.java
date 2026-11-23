package org.kendar.llm.js;

import org.junit.jupiter.api.Assumptions;

/** The same behaviour on the isolated profile; skipped where the runtime has no isolate. */
class IsolatedSandboxTest extends SandboxBehaviour {

    @Override
    String engine() {
        Assumptions.assumeTrue(JsTest.isolateAvailable(), "no GraalJS isolate on this platform");
        return "isolated";
    }
}
