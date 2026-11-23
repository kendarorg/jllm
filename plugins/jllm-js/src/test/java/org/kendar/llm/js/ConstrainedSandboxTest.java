package org.kendar.llm.js;

/** The sandbox behaviour on the constrained profile, forced with {@code engine=constrained}. */
class ConstrainedSandboxTest extends SandboxBehaviour {

    @Override
    String engine() {
        return "constrained";
    }
}
